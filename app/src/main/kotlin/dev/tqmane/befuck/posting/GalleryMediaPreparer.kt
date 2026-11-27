package dev.tqmane.befuck.posting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Prepares private WebP photos and trimmed/cropped MP4 videos for BeReal posting. */
internal object GalleryMediaPreparer {
    private const val MAX_IMAGE_BYTES = 1_048_576
    private const val MAX_SOURCE_VIDEO_BYTES = 1_073_741_824L

    fun galleryDirectory(context: Context): File = File(context.filesDir, "befuck/gallery").apply { mkdirs() }

    fun prepareCroppedBitmap(context: Context, bitmap: Bitmap, slot: String): GalleryMediaFile {
        val dir = galleryDirectory(context)
        val file = File(dir, "${slot}_${UUID.randomUUID()}.webp")
        var completed = false
        try {
            val encoded = encodeWebpUnderLimit(bitmap)
            FileOutputStream(file).use { it.write(encoded.bytes) }
            completed = true
            return GalleryMediaFile(
                uri = Uri.fromFile(file).toString(),
                path = file.absolutePath,
                width = encoded.width,
                height = encoded.height,
                isVideo = false,
                mimeType = "image/webp",
            )
        } finally {
            bitmap.recycle()
            if (!completed) file.delete()
        }
    }

    fun prepareMatchingVideo(context: Context, media: GalleryMediaFile, durationMs: Long, slot: String): GalleryMediaFile =
        prepareVideo(context, File(requireNotNull(media.sourcePath)), requireNotNull(media.videoEdit).withDuration(durationMs), slot)

    fun alignVideoPair(context: Context, request: GalleryPostRequest): GalleryPostRequest {
        val front = requireNotNull(request.front)
        val back = requireNotNull(request.back)
        val frontEdit = requireNotNull(front.videoEdit)
        val backEdit = requireNotNull(back.videoEdit)
        val duration = min(frontEdit.durationMs, backEdit.durationMs)
        val created = arrayListOf<GalleryMediaFile>()
        try {
            fun align(media: GalleryMediaFile, edit: VideoEdit, slot: String): GalleryMediaFile {
                if (edit.durationMs == duration) return media
                return prepareMatchingVideo(context, media, duration, slot).also(created::add)
            }
            // BeReal's native trimmer keeps the tail. Equal-length exports preserve both chosen starts.
            return request.copy(front = align(front, frontEdit, "front"), back = align(back, backEdit, "back"))
        } catch (failure: Throwable) {
            val preserve = request.selectedMedia.flatMap { listOfNotNull(it.path, it.previewPath, it.sourcePath) }.toSet()
            created.forEach { discardPreparedMedia(context, it, preserve) }
            throw failure
        }
    }

    fun copyVideoSource(context: Context, uri: Uri, camera: String): File {
        val mimeType = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT)
        require(mimeType == null || mimeType == "video/mp4") { "Choose an MP4 video" }
        val directory = galleryDirectory(context)
        val sourceVideo = File(directory, "$camera-video-${UUID.randomUUID()}.source.mp4")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Video provider returned no data" }
                FileOutputStream(sourceVideo).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_SOURCE_VIDEO_BYTES) { "Video source exceeds the 1 GiB processing limit" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(sourceVideo.length() > 0L) { "Selected video is empty" }
            return sourceVideo
        } catch (failure: Throwable) {
            sourceVideo.delete()
            throw failure
        }
    }

    fun prepareVideo(context: Context, sourceVideo: File, edit: VideoEdit, camera: String): GalleryMediaFile {
        val directory = galleryDirectory(context)
        val id = UUID.randomUUID().toString()
        val compressedVideo = File(directory, "$camera-video-$id.mp4")
        val thumbnail = File(directory, "$camera-video-$id-preview.webp")
        var completed = false
        try {
            val originalMetadata = readVideoMetadata(sourceVideo)
            val video = Media3VideoCompressor.export(
                context, sourceVideo, compressedVideo, originalMetadata.third,
                originalMetadata.first, originalMetadata.second, edit,
            )
            require(video.length() <= 512L * 1024L * 1024L) { "Prepared video exceeds the 512 MiB upload limit" }
            // shortcut: BeReal 3.97 rejects otherwise valid videos <=100 KiB. A standard
            // MP4 free atom satisfies its container-size check without changing samples.
            if (video.length() <= 100L * 1024L) {
                val padding = (101L * 1024L - video.length()).toInt()
                DataOutputStream(FileOutputStream(video, true)).use {
                    it.writeInt(padding)
                    it.writeBytes("free")
                    it.write(ByteArray(padding - 8))
                }
            }
            val metadata = readVideoMetadata(video)
            require(metadata.third <= VideoEdit.MAX_DURATION_MS + 100L) { "Exported video exceeds the selected limit" }

            val previewRetriever = MediaMetadataRetriever()
            try {
                previewRetriever.setDataSource(video.absolutePath)
                val durationUs = metadata.third * 1_000L
                val frame = previewRetriever.getFrameAtTime(min(250_000L, durationUs / 4), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: previewRetriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: error("Could not generate a preview frame for this video")
                val scaled = fit(frame, 900, 900)
                if (scaled !== frame) frame.recycle()
                try {
                    FileOutputStream(thumbnail).use { output ->
                        require(scaled.compress(Bitmap.CompressFormat.WEBP, 84, output)) { "Could not encode the video preview" }
                    }
                } finally {
                    scaled.recycle()
                }
            } finally {
                previewRetriever.release()
            }
            val previewBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(thumbnail.absolutePath, previewBounds)
            require(previewBounds.outWidth > 0 && previewBounds.outHeight > 0) { "Encoded WebP preview could not be read back" }
            completed = true
            return GalleryMediaFile(
                uri = Uri.fromFile(video).toString(),
                path = video.absolutePath,
                width = metadata.first,
                height = metadata.second,
                isVideo = true,
                durationMs = metadata.third,
                previewPath = thumbnail.absolutePath,
                previewWidth = previewBounds.outWidth,
                previewHeight = previewBounds.outHeight,
                sourcePath = sourceVideo.absolutePath,
                videoEdit = edit,
            )
        } finally {
            if (!completed) {
                compressedVideo.delete()
                thumbnail.delete()
            }
        }
    }

    fun readVideoMetadata(file: File): Triple<Int, Int, Long> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: error("Video width metadata is missing")
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: error("Video height metadata is missing")
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("Video duration metadata is missing")
            require(durationMs in 1L..Int.MAX_VALUE.toLong() && rawWidth > 0 && rawHeight > 0) { "Invalid video metadata" }
            val rotated = rotation == 90 || rotation == 270
            Triple(
                if (rotated) rawHeight else rawWidth,
                if (rotated) rawWidth else rawHeight,
                durationMs,
            )
        } finally {
            retriever.release()
        }
    }

    fun discardPreparedMedia(context: Context, media: GalleryMediaFile, preserve: Set<String> = emptySet()) {
        deleteOwnedGalleryFiles(context, listOfNotNull(media.path, media.previewPath, media.sourcePath).distinct().filterNot { it in preserve })
    }

    fun deleteOwnedGalleryFiles(context: Context, paths: Iterable<String>) {
        val directory = File(context.filesDir, "befuck/gallery").canonicalFile
        paths.forEach { path ->
            runCatching {
                val file = File(path).canonicalFile
                if (file.parentFile == directory) file.delete()
            }
        }
    }

    private class EncodedWebp(val bytes: ByteArray, val width: Int, val height: Int)

    private fun encodeWebpUnderLimit(bitmap: Bitmap): EncodedWebp {
        var current = bitmap
        var ownsCurrent = false
        var quality = 90
        try {
            while (true) {
                val output = ByteArrayOutputStream()
                require(current.compress(Bitmap.CompressFormat.WEBP, quality, output)) { "Could not encode selected image as WebP" }
                if (output.size() <= MAX_IMAGE_BYTES) return EncodedWebp(output.toByteArray(), current.width, current.height)
                if (quality > 50) {
                    quality = (quality - 10).coerceAtLeast(50)
                    continue
                }
                require(max(current.width, current.height) > 480) { "Image remains larger than 1 MiB after resizing" }
                val smaller = Bitmap.createScaledBitmap(
                    current,
                    max(1, current.width * 4 / 5),
                    max(1, current.height * 4 / 5),
                    true,
                )
                if (ownsCurrent) current.recycle()
                current = smaller
                ownsCurrent = true
                quality = 86
            }
        } finally {
            if (ownsCurrent) current.recycle()
        }
    }

    private fun fit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val factor = min(1f, min(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height))
        if (factor >= 1f) return bitmap
        val width = max(1, (bitmap.width * factor).toInt())
        val height = max(1, (bitmap.height * factor).toInt())
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
