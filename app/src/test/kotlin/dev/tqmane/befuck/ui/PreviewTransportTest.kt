package dev.tqmane.befuck.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class PreviewTransportTest {
    private class Response(url: URL, val status: Int = 200, val data: ByteArray = byteArrayOf(1,2,3), val type: String = "image/jpeg",
        val responseHeaders: Map<String, String> = emptyMap(), val declared: Long = data.size.toLong()) : HttpURLConnection(url) {
        val headers = linkedMapOf<String, String>(); var disconnected = false; var inputClosed = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = declared
        override fun getContentType() = type
        override fun getHeaderField(name: String) = responseHeaders[name]
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
        override fun getInputStream() = object : ByteArrayInputStream(data) { override fun close() { inputClosed = true; super.close() } }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
    }
    @Test fun authenticationIsAnonymousFirstAndNotForwardedToAnotherOrigin() {
        val seen = arrayListOf<Response>()
        val transport = PreviewTransport("Bearer synthetic-fixture") { url ->
            val response = when (seen.size) {
                0 -> Response(url, 401)
                1 -> Response(url, 302, responseHeaders = mapOf("Location" to "https://cdn.example.invalid/image"))
                else -> Response(url)
            }
            seen += response; response
        }
        val file = File.createTempFile("preview-auth-test", ".bin")
        try {
            transport.file("https://media.bereal.com/image", file, false, PreviewTicket())
            assertNull(seen[0].headers["Authorization"])
            assertEquals("Bearer synthetic-fixture", seen[1].headers["Authorization"])
            assertNull(seen[2].headers["Authorization"])
            assertTrue(seen.all { it.disconnected })
        } finally { file.delete() }
    }
    @Test fun untrusted401AndDowngradeNeverReceiveCredentials() {
        for (url in listOf("https://evil.invalid/file", "https://bereal.com.evil.invalid/file")) {
            val seen = arrayListOf<Response>()
            val t = PreviewTransport("Bearer fixture") { u -> Response(u, 401).also { seen += it } }
            assertThrows(Exception::class.java) { t.response(url, null, PreviewTicket()) { } }
            assertEquals(1, seen.size); assertNull(seen.single().headers["Authorization"])
        }
        var calls = 0
        val t = PreviewTransport(null) { u -> calls++; Response(u, 302, responseHeaders = mapOf("Location" to "http://example.invalid/file")) }
        assertThrows(Exception::class.java) { t.response("https://example.invalid/file", null, PreviewTicket()) { } }
        assertEquals(1, calls)
    }
    @Test fun invalidMimeEmptyTruncationAndOversizeNeverLeaveStagedFile() {
        for (mode in 0..3) {
            val response = Response(URL("https://example.invalid/file"), data = if (mode == 1) byteArrayOf() else byteArrayOf(1,2,3),
                type = if (mode == 0) "text/html" else "image/jpeg", declared = when(mode) { 1 -> 0; 2 -> 8; 3 -> PreviewTransport.IMAGE_BYTES + 1; else -> 3 })
            val file = File.createTempFile("preview-invalid-test", ".bin")
            try {
                assertThrows(Exception::class.java) { PreviewTransport(null) { response }.file(response.url.toString(), file, false, PreviewTicket()) }
                assertFalse(file.exists()); assertTrue(response.disconnected)
                if (mode == 2) assertTrue(response.inputClosed)
            } finally { file.delete() }
        }
    }
    @Test fun cancelledOrStaleTicketCannotOpenAConnection() {
        var valid = true; var calls = 0
        val ticket = PreviewTicket({ previewCheck(valid) })
        val t = PreviewTransport(null) { calls++; Response(it) }
        valid = false
        assertThrows(Exception::class.java) { t.response("https://example.invalid/a", null, ticket) {} }
        assertEquals(0, calls)
        val cancelled = PreviewTicket(); cancelled.cancel()
        assertThrows(Exception::class.java) { t.response("https://example.invalid/a", null, cancelled) {} }
    }
    @Test fun randomAccessReadsAreCachedBoundedAndCorrectAtEndOfFile() {
        val bytes = ByteArray(300000) { (it % 127).toByte() }; var calls = 0
        val t = PreviewTransport(null) { url ->
            object : HttpURLConnection(url) {
                val headers = mutableMapOf<String,String>()
                var start = 0; var end = 0
                override fun setRequestProperty(key: String, value: String) { headers[key] = value }
                override fun getResponseCode(): Int { calls++; val parts = headers.getValue("Range").removePrefix("bytes=").split('-'); start = parts[0].toInt(); end = minOf(parts[1].toInt(), bytes.lastIndex); return 206 }
                override fun getContentLengthLong() = (end - start + 1).toLong()
                override fun getContentType() = "video/mp4"
                override fun getHeaderField(name: String) = if (name == "Content-Range") "bytes $start-$end/${bytes.size}" else null
                override fun getInputStream() = ByteArrayInputStream(bytes.copyOfRange(start,end+1))
                override fun connect() = Unit; override fun usingProxy() = false; override fun disconnect() = Unit
            }
        }
        // responseCode may be read repeatedly; count only ensures cache avoids new requests below.
        val source = PreviewRanges("https://example.invalid/video", t, PreviewTicket())
        assertEquals(300000, source.size)
        val buffer = ByteArray(20)
        assertEquals(20, source.readAt(10,buffer,0,20)); assertArrayEquals(bytes.copyOfRange(10,30),buffer)
        val before = calls; source.readAt(20,buffer,0,20); assertEquals(before,calls)
        assertEquals(3,source.readAt(299997,buffer,0,20)); assertEquals(-1,source.readAt(300000,buffer,0,20))
        source.close(); assertThrows(Exception::class.java) { source.readAt(0,buffer,0,1) }
    }
    @Test fun rangeMismatchAndIgnoredRangeLargeVideoFailWithoutReadingHugeBody() {
        val variants = listOf(
            Response(URL("https://example.invalid/v"),206,byteArrayOf(1,2,3),"video/mp4",mapOf("Content-Range" to "bytes 5-7/8")),
            Response(URL("https://example.invalid/v"),200,byteArrayOf(1),"video/mp4",declared=50L*1024*1024))
        variants.forEach { response ->
            val source = PreviewRanges(response.url.toString(),PreviewTransport(null) { response },PreviewTicket())
            try { assertThrows(Exception::class.java) { source.size } } finally { source.close() }
        }
    }
}
