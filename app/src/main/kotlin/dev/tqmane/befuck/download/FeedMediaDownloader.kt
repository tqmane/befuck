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
    private val jobs = DownloadJobs()

    private class UnauthorizedTrustedHost(val host: String, val origin: String) : IOException("Media server returned HTTP 401")
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

    private fun enqueue(context: Context, post: FeedPostMedia, selection: FeedMediaSelection,
                        completion: Consumer<FeedMediaDownloadResult>, realMoji: Boolean) {
        val scope = FeedDownloadScope()
        val key = scope.key(post, selection, realMoji)
        if (!jobs.join(key, completion)) return
        val application = context.applicationContext ?: context
        runCatching { executor.execute {
            var completed = 0
            var totalBytes = 0L
            val result = try {
                scope.check()
                val selected = selectMedia(post, selection)
                require(selected.isNotEmpty()) { "This post has no media for that selection" }
                for ((kind, item) in selected) {
                    scope.check()
                    val metadata = if (realMoji) post else FeedPostMediaCache.get(post.postId) ?: post
                    totalBytes += downloadOne(application, metadata, if (realMoji) "realmoji" else kind, item, scope)
                    completed++
                }
                FeedMediaDownloadResult(true, completed, totalBytes)
            } catch (failure: Throwable) {
                Log.e(TAG, "Media download failed after $completed item(s): ${failure.javaClass.simpleName}")
                FeedMediaDownloadResult(false, completed, totalBytes, failure)
            }
            jobs.complete(key, result)
        } }.onFailure { jobs.complete(key, FeedMediaDownloadResult(false, 0, 0L, it)) }
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

    private fun downloadOne(context: Context, post: FeedPostMedia, kind: String, media: FeedMediaItem, scope: FeedDownloadScope): Long {
        scope.check()
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
            val copied = copyUrlToOutput(media.url, scope) { FileOutputStream(temporary) }
            if (copied.responseMimeType?.let { it.startsWith("image/") || it.startsWith("video/") } == true) {
                require(copied.responseMimeType.startsWith(if (isVideo) "video/" else "image/")) { "Media kind does not match its response" }
            }
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
            scope.check()
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

    private fun copyUrlToOutput(mediaUrl: String, scope: FeedDownloadScope, openOutput: () -> OutputStream): DownloadedMedia {
        return try {
            copyWithRedirects(mediaUrl, openOutput, scope, authorization = null, authOrigin = null)
        } catch (unauthorized: UnauthorizedTrustedHost) {
            val authorization = scope.authorization
                ?.takeIf { it.isNotBlank() }
                ?: throw IOException("BeReal authorization is unavailable for this media; refresh the feed and retry")
            if (!isTrustedBeRealHost(unauthorized.host)) throw unauthorized
            copyWithRedirects(mediaUrl, openOutput, scope, authorization, unauthorized.origin)
        }
    }

    private fun copyWithRedirects(
        mediaUrl: String,
        openOutput: () -> OutputStream,
        scope: FeedDownloadScope,
        authorization: String?,
        authOrigin: String?,
    ): DownloadedMedia {
        var current = URL(mediaUrl)
        var redirects = 0
        while (true) {
            scope.check()
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
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (authorization != null && isTrustedBeRealHost(current.host) && "https://${current.host.lowercase(Locale.ROOT)}:${if (current.port == -1) 443 else current.port}" == authOrigin) {
                    connection.setRequestProperty("Authorization", authorization)
                }

                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED && authorization == null && isTrustedBeRealHost(current.host)) {
                    throw UnauthorizedTrustedHost(current.host, "https://${current.host.lowercase(Locale.ROOT)}:${if (current.port == -1) 443 else current.port}")
                }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirects >= MAX_REDIRECTS) throw IOException("Media server exceeded the redirect limit")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Media server returned a redirect without a Location")
                    current = URL(current, location)
                    redirects++
                    continue
                }
                if (status != HttpURLConnection.HTTP_OK) throw IOException("Media server returned HTTP $status")
                val copied = MediaResponseCopy.copy(connection, MAX_MEDIA_BYTES) {
                    scope.check()
                    object : java.io.FilterOutputStream(openOutput()) {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) { scope.check(); out.write(bytes, offset, length) }
                        override fun write(value: Int) { scope.check(); out.write(value) }
                    }
                }
                scope.check()
                return DownloadedMedia(copied.bytes, copied.mimeType)
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
        "image/avif" -> "avif"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/gif" -> "gif"
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        else -> if (mimeType.startsWith("video/")) "mp4" else "jpg"
    }
}
