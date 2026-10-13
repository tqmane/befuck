package dev.tqmane.befuck.download

import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.util.Locale

internal data class CopiedMediaBody(val bytes: Long, val mimeType: String?)

/** Owns both streams, including every early-open and partial-write failure. No destination is published here. */
internal object MediaResponseCopy {
    fun copy(connection: HttpURLConnection, maxBytes: Long, openOutput: () -> OutputStream): CopiedMediaBody {
        require(maxBytes > 0)
        val length = connection.contentLengthLong
        if (length == 0L || length > maxBytes) throw IOException("Invalid media response length")
        val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        if (mime != null && mime != "application/octet-stream" && !mime.startsWith("image/") && !mime.startsWith("video/")) {
            throw IOException("Response is not an image or video")
        }
        var total = 0L
        // Open input first: a rejected input must never leave an already opened output unclosed.
        connection.inputStream.use { input ->
            openOutput().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Download interrupted")
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw IOException("Media exceeds download limit")
                    output.write(buffer, 0, count)
                }
                if (total == 0L || length >= 0 && total != length) throw IOException("Media response was empty or truncated")
                output.flush()
            }
        }
        return CopiedMediaBody(total, mime)
    }
}
