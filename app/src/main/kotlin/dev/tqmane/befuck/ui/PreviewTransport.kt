package dev.tqmane.befuck.ui

import android.media.MediaDataSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal fun previewCheck(condition: Boolean) { if (!condition) throw IOException("Preview unavailable") }
internal fun interface PreviewCancel { fun cancel() }

/** Cancellation owns the active connection; authentication is checked again before every read/hop. */
internal class PreviewTicket(private val guard: () -> Unit = {}, seconds: Long = 45) : PreviewCancel {
    private val cancelled = AtomicBoolean()
    private val deadline = System.nanoTime() + seconds * 1_000_000_000L
    @Volatile private var connection: HttpURLConnection? = null
    fun check() {
        previewCheck(!cancelled.get() && !Thread.currentThread().isInterrupted && System.nanoTime() < deadline)
        guard()
    }
    fun attach(value: HttpURLConnection) { connection = value; check() }
    fun detach(value: HttpURLConnection) { if (connection === value) connection = null }
    override fun cancel() { cancelled.set(true); runCatching { connection?.disconnect() } }
}

/** Anonymous first. Credentials are retried only at the exact trusted HTTPS origin that returned 401. */
internal class PreviewTransport(
    private val authorization: String?,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    companion object {
        const val IMAGE_BYTES = 24L * 1024 * 1024
        const val VIDEO_BYTES = 512L * 1024 * 1024
        fun valid(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && url.length <= 8192
        }.getOrDefault(false)
        fun trusted(host: String): Boolean {
            val value = host.lowercase(Locale.ROOT).trimEnd('.')
            return value == "bereal.com" || value.endsWith(".bereal.com") || value == "bereal.network" || value.endsWith(".bereal.network")
        }
        fun origin(url: URL) = "https://${url.host.lowercase(Locale.ROOT)}:${if (url.port == -1) 443 else url.port}"
    }
    fun <T> response(url: String, range: LongRange?, ticket: PreviewTicket, body: (HttpURLConnection) -> T): T {
        var current = URL(url)
        var authOrigin: String? = null
        var redirects = 0
        while (true) {
            ticket.check(); previewCheck(valid(current.toString()))
            val connection = open(current)
            try {
                ticket.attach(connection)
                connection.connectTimeout = 8000; connection.readTimeout = 10000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Accept", "image/*,video/*,application/octet-stream")
                range?.let { connection.setRequestProperty("Range", "bytes=${it.first}-${it.last}") }
                if (authorization != null && authOrigin == origin(current) && trusted(current.host)) connection.setRequestProperty("Authorization", authorization)
                val status = connection.responseCode
                if (status == 401 && authOrigin == null && !authorization.isNullOrBlank() && trusted(current.host)) {
                    authOrigin = origin(current); continue
                }
                if (status in listOf(301, 302, 303, 307, 308)) {
                    previewCheck(++redirects <= 5)
                    current = URL(current, connection.getHeaderField("Location") ?: throw IOException("Preview redirect unavailable"))
                    continue
                }
                previewCheck(status == 200 || range != null && status == 206)
                val encoding = connection.getHeaderField("Content-Encoding")
                previewCheck(encoding.isNullOrBlank() || encoding.equals("identity", true))
                ticket.check()
                return body(connection)
            } finally { ticket.detach(connection); connection.disconnect() }
        }
    }
    fun file(url: String, target: File, video: Boolean, ticket: PreviewTicket) {
        val limit = if (video) VIDEO_BYTES else IMAGE_BYTES
        try {
            response(url, null, ticket) { connection ->
                checkMime(connection, video)
                val expected = connection.contentLengthLong
                previewCheck(expected != 0L && expected <= limit)
                connection.inputStream.use { input -> target.outputStream().use { output ->
                    val bytes = ByteArray(65536); var total = 0L
                    while (true) {
                        ticket.check(); val count = input.read(bytes); if (count < 0) break
                        total += count; previewCheck(total <= limit)
                        output.write(bytes, 0, count)
                    }
                    previewCheck(total > 0 && (expected < 0 || expected == total)); ticket.check()
                } }
            }
        } catch (failure: Throwable) { target.delete(); throw failure }
    }
    internal fun checkMime(connection: HttpURLConnection, video: Boolean) {
        val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        previewCheck(mime.isNullOrBlank() || mime == "application/octet-stream" || mime.startsWith(if (video) "video/" else "image/"))
    }
}

/** Bounded random-access video poster input. A 30s video is not fully downloaded for every list row. */
internal class PreviewRanges(private val url: String, private val transport: PreviewTransport, private val ticket: PreviewTicket,
    private val budget: Long = 8L * 1024 * 1024) : MediaDataSource() {
    private val blockSize = 128 * 1024
    private val blocks = object : LinkedHashMap<Long, ByteArray>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 8
    }
    private var size = -1L
    private var whole: ByteArray? = null
    private var transferred = 0L
    private var etag: String? = null
    @Volatile private var closed = false
    @Synchronized override fun getSize(): Long { previewCheck(!closed); ticket.check(); if (size < 0) fetch(0); return size }
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
        previewCheck(!closed && position >= 0 && offset >= 0 && count >= 0 && offset <= buffer.size - count)
        ticket.check()
        if (count == 0) return 0
        if (position >= getSize()) return -1
        whole?.let { value ->
            val length = minOf(count.toLong(), size - position).toInt()
            value.copyInto(buffer, offset, position.toInt(), position.toInt() + length); return length
        }
        val start = position / blockSize * blockSize
        val data = blocks[start] ?: fetch(start)
        // A server may ignore Range and return a complete small object on a subsequent request.
        whole?.let { return readAt(position, buffer, offset, count) }
        val within = (position - start).toInt()
        val length = minOf(count, data.size - within)
        previewCheck(length > 0)
        data.copyInto(buffer, offset, within, within + length)
        return length
    }
    private fun fetch(start: Long): ByteArray {
        previewCheck(!closed); ticket.check()
        val end = if (size < 0) start + blockSize - 1 else minOf(size - 1, start + blockSize - 1)
        return transport.response(url, start..end, ticket) { connection ->
            transport.checkMime(connection, true)
            val stamp = connection.getHeaderField("ETag")
            if (etag != null && stamp != null) previewCheck(etag == stamp)
            if (etag == null) etag = stamp
            var length: Long
            if (connection.responseCode == 206) {
                val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                    ?: throw IOException("Preview byte range unavailable")
                val from = range.groupValues[1].toLong(); val to = range.groupValues[2].toLong(); val total = range.groupValues[3].toLong()
                previewCheck(from == start && to in from..end && total in 1..PreviewTransport.VIDEO_BYTES && to == minOf(end, total - 1))
                previewCheck(size < 0 || size == total)
                size = total; length = to - from + 1
                previewCheck(connection.contentLengthLong < 0 || connection.contentLengthLong == length)
            } else {
                length = connection.contentLengthLong
                previewCheck(length != 0L && length <= budget - transferred)
                previewCheck(size < 0 || length < 0 || length == size)
            }
            val limit = if (connection.responseCode == 206) length else budget - transferred
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk = ByteArray(16384)
                while (true) {
                    ticket.check(); val n = input.read(chunk); if (n < 0) break
                    transferred += n
                    previewCheck(transferred <= budget && output.size().toLong() + n <= limit)
                    output.write(chunk, 0, n)
                }
            }
            val bytes = output.toByteArray()
            previewCheck(bytes.isNotEmpty() && (length < 0 || bytes.size.toLong() == length))
            if (connection.responseCode == 200) {
                previewCheck(size < 0 || size == bytes.size.toLong())
                size = bytes.size.toLong(); whole = bytes; blocks.clear()
            } else blocks[start] = bytes
            bytes
        }
    }
    override fun close() { closed = true; synchronized(this) { blocks.clear(); whole = null } }
}
