package dev.tqmane.befuck.download

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.function.Consumer

class MediaTransferTest {
    private class Input(bytes: ByteArray) : ByteArrayInputStream(bytes) { var closed = false; override fun close() { closed = true; super.close() } }
    private class Output : ByteArrayOutputStream() { var closed = false; override fun close() { closed = true; super.close() } }
    private class Response(val source: Input, val length: Long, val mime: String? = "image/jpeg", val failOpen: Boolean = false) : HttpURLConnection(URL("https://example.test/image")) {
        override fun getInputStream() = if (failOpen) throw IOException("Open failed") else source
        override fun getContentLengthLong() = length
        override fun getContentType() = mime
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
    }
    @Test fun successClosesBothStreams() {
        val input = Input(byteArrayOf(1, 2, 3)); val output = Output()
        val result = MediaResponseCopy.copy(Response(input, 3), 10) { output }
        assertEquals(3L, result.bytes); assertTrue(input.closed); assertTrue(output.closed)
    }
    @Test fun openingInputFailureDoesNotOpenDestination() {
        var opened = false
        assertThrows(IOException::class.java) { MediaResponseCopy.copy(Response(Input(byteArrayOf()), -1, failOpen = true), 10) { opened = true; Output() } }
        assertFalse(opened)
    }
    @Test fun openingOutputFailureStillClosesInput() {
        val input = Input(byteArrayOf(1))
        assertThrows(IOException::class.java) { MediaResponseCopy.copy(Response(input, 1), 10) { throw IOException("No space") } }
        assertTrue(input.closed)
    }
    @Test fun truncatedOversizedEmptyAndHtmlResponsesFail() {
        for ((bytes, length, mime, max) in listOf(
            listOf(2, 4, "image/jpeg", 10), listOf(5, -1, "image/jpeg", 4), listOf(0, -1, "image/jpeg", 10), listOf(2, 2, "text/html", 10))) {
            val input = Input(ByteArray(bytes as Int)); val output = Output()
            assertThrows(IOException::class.java) { MediaResponseCopy.copy(Response(input, (length as Int).toLong(), mime as String), (max as Int).toLong()) { output } }
            if (mime != "text/html") { assertTrue(input.closed); assertTrue(output.closed) }
        }
    }
    @Test fun repeatedTapsCoalesceAndThrowingCallbackCannotCauseDoubleCompletion() {
        val jobs = DownloadJobs(); var first = 0; var second = 0
        assertTrue(jobs.join("same", Consumer { first++; throw IllegalStateException("Consumer failure") }))
        assertFalse(jobs.join("same", Consumer { second++ }))
        val result = FeedMediaDownloadResult(true, 1, 3)
        jobs.complete("same", result); jobs.complete("same", result)
        assertEquals(1, first); assertEquals(1, second)
        assertTrue(jobs.join("same", Consumer { second++ }))
        jobs.complete("same", result); assertEquals(2, second)
    }
}
