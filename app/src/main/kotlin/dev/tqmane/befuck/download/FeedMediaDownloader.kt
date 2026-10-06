package dev.tqmane.befuck.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import dev.tqmane.befuck.posting.BeFakeAuthHeaders
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.function.Consumer

enum class FeedMediaSelection { PRIMARY, SECONDARY, ALL }

data class FeedMediaDownloadResult(
    val success: Boolean,
    val completedCount: Int,
    val totalBytes: Long,
    val error: Throwable? = null,
)

object FeedMediaDownloader {
    private const val TAG = "BeFuck/Download"
    private const val MAX_MEDIA_BYTES = 512L * 1024L * 1024L
    private const val MAX_REDIRECTS = 5
    private val executor = Executors.newFixedThreadPool(2)

    private class UnauthorizedTrustedHost(val host: String) : IOException("Media server returned HTTP 401")
    private data class DownloadedMedia(val bytes: Long, val responseMimeType: String?)

    @JvmStatic
    fun download(
        context: Context,
        post: FeedPostMedia,
        selection: FeedMediaSelection,
        completion: Consumer<FeedMediaDownloadResult>,
    ) = enqueue(context, post, selection, completion, realMoji = false)

    @JvmStatic
    fun downloadRealMoji(context: Context, media: FeedPostMedia, completion: Consumer<FeedMediaDownloadResult>) =
        enqueue(context, media, FeedMediaSelection.PRIMARY, completion, realMoji = true)

    private fun enqueue(
        context: Context,
        post: FeedPostMedia,
        selection: FeedMediaSelection,
        completion: Consumer<FeedMediaDownloadResult>,
        realMoji: Boolean,
    ) {
        executor.execute {
            var completed = 0
            var totalBytes = 0L
            try {
                val selected = selectMedia(post, selection)
                require(selected.isNotEmpty()) { "This post has no media for that selection" }
                for ((kind, item) in selected) {
                    val metadata = if (realMoji) post else FeedPostMediaCache.get(post.postId) ?: post
                    val result = downloadOne(context.applicationContext, metadata, if (realMoji) "realmoji" else kind, item)
                    completed++
                    totalBytes += result
                }
                Log.i(TAG, "Saved $completed post media item(s), bytes=$totalBytes")
                completion.accept(FeedMediaDownloadResult(true, completed, totalBytes))
            } catch (failure: Throwable) {
                Log.e(TAG, "Media download failed after $completed item(s)", failure)
                completion.accept(FeedMediaDownloadResult(false, completed, totalBytes, failure))
            }
        }
    }

    private fun selectMedia(post: FeedPostMedia, selection: FeedMediaSelection): List<Pair<String, FeedMediaItem>> {
        val available = listOfNotNull(
            post.primary?.let { "back" to it },
            post.secondary?.let { "front" to it },
            post.bts?.let { "bts" to it },
        )
        return when (selection) {
            FeedMediaSelection.PRIMARY -> available.filter { it.first == "back" }
            FeedMediaSelection.SECONDARY -> available.filter { it.first == "front" }
            FeedMediaSelection.ALL -> available
        }
    }

    private fun downloadOne(context: Context, post: FeedPostMedia, kind: String, media: FeedMediaItem): Long {
        val parsed = URI(media.url)
        require(parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()) {
            "Media URL is not a valid HTTPS URL"
        }
        require(parsed.userInfo == null) { "Media URL must not contain embedded credentials" }

        val isVideo = media.isVideo
        val mime = resolveMimeType(media, isVideo)
        val extension = extensionFor(mime)
        val filenameBase = PostMediaMetadata.filename(post, kind)
        val relativePath = if (isVideo) "${Environment.DIRECTORY_MOVIES}/BeFuck" else "${Environment.DIRECTORY_PICTURES}/BeFuck"
        val collection: Uri = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$filenameBase.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val temporary = File.createTempFile("befuck-download-", ".media", context.cacheDir)
        val outputUri = try {
            context.contentResolver.insert(collection, values)
                ?: throw IOException("MediaStore refused to create a destination")
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
        try {
            val copied = copyUrlToOutput(media.url) { FileOutputStream(temporary) }
            val detectedMime = copied.responseMimeType
                ?.takeIf { it.startsWith(if (isVideo) "video/" else "image/") }
                ?: mime
            val finalName = "$filenameBase.${extensionFor(detectedMime)}"
            PostMediaMetadata.write(temporary, detectedMime, post)
            temporary.inputStream().use { input ->
                (context.contentResolver.openOutputStream(outputUri, "w")
                    ?: throw IOException("Could not open the MediaStore destination")).use { input.copyTo(it) }
            }
            val ready = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.MIME_TYPE, detectedMime)
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                PostMediaMetadata.timestamp(post)?.let {
                    put(MediaStore.MediaColumns.DATE_TAKEN, it)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, it / 1000L)
                }
            }
            val updated = context.contentResolver.update(outputUri, ready, null, null)
            check(updated == 1) { "MediaStore could not finalize the downloaded item" }
            return copied.bytes
        } catch (failure: Throwable) {
            runCatching { context.contentResolver.delete(outputUri, null, null) }
            throw failure
        } finally {
            temporary.delete()
        }
    }

    private fun copyUrlToOutput(mediaUrl: String, openOutput: () -> OutputStream): DownloadedMedia {
        return try {
            copyWithRedirects(mediaUrl, openOutput, authorization = null)
        } catch (unauthorized: UnauthorizedTrustedHost) {
            val authorization = BeFakeAuthHeaders.snapshot()["Authorization"]
                ?.takeIf { it.isNotBlank() }
                ?: throw IOException("BeReal authorization is unavailable for this media; refresh the feed and retry")
            if (!isTrustedBeRealHost(unauthorized.host)) throw unauthorized
            copyWithRedirects(mediaUrl, openOutput, authorization)
        }
    }

    private fun copyWithRedirects(
        mediaUrl: String,
        openOutput: () -> OutputStream,
        authorization: String?,
    ): DownloadedMedia {
        var current = URL(mediaUrl)
        var redirects = 0
        while (true) {
            val currentUri = runCatching { URI(current.toString()) }.getOrElse {
                throw IOException("Media redirect URL is malformed", it)
            }
            require(current.protocol.equals("https", ignoreCase = true) && currentUri.userInfo == null) {
                "Media redirect must remain HTTPS and must not include credentials"
            }
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 90_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "image/*, video/mp4, */*")
                if (authorization != null && isTrustedBeRealHost(current.host)) {
                    connection.setRequestProperty("Authorization", authorization)
                }

                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED && authorization == null && isTrustedBeRealHost(current.host)) {
                    throw UnauthorizedTrustedHost(current.host)
                }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirects >= MAX_REDIRECTS) throw IOException("Media server exceeded the redirect limit")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Media server returned a redirect without a Location")
                    current = URL(current, location)
                    redirects++
                    continue
                }
                if (status !in 200..299) throw IOException("Media server returned HTTP $status")
                val length = connection.contentLengthLong
                require(length < 0L || length <= MAX_MEDIA_BYTES) { "Media is larger than the 512 MiB download limit" }
                val responseMimeType = connection.contentType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
                val output = openOutput()
                var total = 0L
                connection.inputStream.use { input ->
                    output.use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_MEDIA_BYTES) { "Media is larger than the 512 MiB download limit" }
                            stream.write(buffer, 0, count)
                        }
                        stream.flush()
                    }
                }
                return DownloadedMedia(total, responseMimeType)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun isTrustedBeRealHost(host: String?): Boolean {
        val normalized = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return normalized == "bereal.com" || normalized.endsWith(".bereal.com") ||
            normalized == "bereal.network" || normalized.endsWith(".bereal.network")
    }

    private fun resolveMimeType(media: FeedMediaItem, video: Boolean): String {
        val type = media.mimeType?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (type.startsWith("image/") || type.startsWith("video/")) return type
        return if (video) "video/mp4" else "image/jpeg"
    }

    private fun extensionFor(mimeType: String): String = when (mimeType.lowercase(Locale.ROOT)) {
        "image/webp" -> "webp"
        "image/png" -> "png"
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        else -> if (mimeType.startsWith("video/")) "mp4" else "jpg"
    }
}
