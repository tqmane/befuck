package dev.tqmane.befuck.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import dev.tqmane.befuck.download.FeedMediaItem
import dev.tqmane.befuck.posting.BeFakeAuthHeaders
import java.io.Closeable
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class PreviewVideo(val file: File) : Closeable { override fun close() { file.delete() } }
internal interface PreviewSource : Closeable {
    fun image(media: FeedMediaItem, edge: Int, callback: (Result<Bitmap>) -> Unit): PreviewCancel
    fun video(media: FeedMediaItem, callback: (Result<PreviewVideo>) -> Unit): PreviewCancel
}

/** Memory-only, screen-scoped image cache. Temporary encoded files are removed on every terminal path. */
internal class NetworkPreviewSource(context: Context, private val guard: () -> Unit,
    private val transport: PreviewTransport = PreviewTransport(BeFakeAuthHeaders.snapshot()["Authorization"])) : PreviewSource {
    private val app = context.applicationContext ?: context
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(64), { r -> Thread(r, "BeFuckPreview") })
    private val requests = java.util.concurrent.ConcurrentHashMap.newKeySet<PreviewCancel>()
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @Volatile private var closed = false
    override fun image(media: FeedMediaItem, edge: Int, callback: (Result<Bitmap>) -> Unit): PreviewCancel {
        require(edge in 1..2048)
        val key = "${media.isVideo}:$edge:${media.url}"
        return request(callback, {}, {
            synchronized(cache) { cache.get(key) } ?: run {
                val bitmap = if (media.isVideo) poster(media, edge, it) else picture(media, edge, it)
                it.check()
                synchronized(cache) { if (!closed) cache.put(key, bitmap) }
                bitmap
            }
        })
    }
    override fun video(media: FeedMediaItem, callback: (Result<PreviewVideo>) -> Unit): PreviewCancel = request(callback, { it.close() }) { ticket ->
        previewCheck(media.isVideo)
        val file = temporary()
        try { transport.file(media.url, file, true, ticket); PreviewVideo(file) }
        catch (failure: Throwable) { file.delete(); throw failure }
    }
    private fun <T> request(callback: (Result<T>) -> Unit, discard: (T) -> Unit, work: (PreviewTicket) -> T): PreviewCancel {
        val ticket = PreviewTicket({ previewCheck(!closed); guard() }, 120)
        lateinit var task: FutureTask<Unit>
        lateinit var handle: PreviewCancel
        handle = PreviewCancel { ticket.cancel(); task.cancel(true); worker.remove(task); requests.remove(handle) }
        task = FutureTask<Unit> {
            val result = runCatching { ticket.check(); work(ticket) }
            main.post {
                requests.remove(handle)
                if (runCatching { ticket.check() }.isSuccess) callback(result)
                else result.getOrNull()?.let(discard)
            }
        }
        requests += handle
        try { worker.execute(task) }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            requests.remove(handle)
            main.post { if (runCatching { ticket.check() }.isSuccess) callback(Result.failure(java.io.IOException("Preview busy"))) }
        }
        return handle
    }
    private fun picture(media: FeedMediaItem, edge: Int, ticket: PreviewTicket): Bitmap {
        val file = temporary()
        try {
            transport.file(media.url, file, false, ticket); ticket.check()
            return decodeImage(file, edge).also { ticket.check() }
        } finally { file.delete() }
    }
    private fun poster(media: FeedMediaItem, edge: Int, ticket: PreviewTicket): Bitmap {
        val source = PreviewRanges(media.url, transport, ticket)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source); ticket.check()
            val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, edge, edge)
                ?: throw java.io.IOException("Video poster unavailable")
            previewCheck(frame.width <= edge && frame.height <= edge)
            return frame
        } finally { runCatching { retriever.release() }; source.close() }
    }
    private fun temporary(): File {
        val dir = File(app.cacheDir, "befuck-preview").apply { mkdirs() }
        return File.createTempFile("media-", ".tmp", dir)
    }
    override fun close() {
        closed = true; requests.toList().forEach { it.cancel() }; requests.clear()
        worker.shutdownNow()
        // Bitmaps can still be referenced by a view during teardown; do not recycle shared cache values.
        synchronized(cache) { cache.evictAll() }
    }
    companion object {
        internal fun decodeImage(file: File, edge: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            previewCheck(info.size.width > 0 && info.size.height > 0)
            val factor = minOf(1.0, edge.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * factor).toInt()), maxOf(1, (info.size.height * factor).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
    }
}
