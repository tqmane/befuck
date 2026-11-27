package dev.tqmane.befuck.archive

import dev.tqmane.befuck.download.FeedMediaItem
import dev.tqmane.befuck.download.FeedPostMedia
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ArchiveWriteBufferTest {
    private fun record(id: String, at: Long = 100, account: String = "account") = ArchiveRecord.observe(account,
        FeedPostMedia(id, null, null, FeedMediaItem("https://example.invalid/$id?sig=$at", "image/jpeg", null, null), null, capturedAtMillis = at))
    @Test fun failedWriteRetainsItsRecordAndLaterRecordsForRetry() {
        val buffer = ArchiveWriteBuffer(); val a = record("a"); val b = record("b")
        buffer.enqueue(a); buffer.enqueue(b)
        assertThrows(IOException::class.java) { buffer.drain { throw IOException("Synthetic full storage") } }
        assertEquals(2, buffer.size)
        val written = arrayListOf<ArchiveRecord>()
        buffer.drain(written::add)
        assertEquals(listOf(a,b), written); assertEquals(0, buffer.size)
    }
    @Test fun repeatedObservationsCoalesceWithoutRestampingOrLosingUrls() {
        val buffer = ArchiveWriteBuffer(1)
        buffer.enqueue(record("a",100)); buffer.enqueue(record("a",200)); buffer.enqueue(record("a",100))
        assertEquals(1,buffer.size)
        val saved = arrayListOf<ArchiveRecord>(); buffer.drain(saved::add)
        assertEquals(2,saved.single().media.size); assertEquals(100L,saved.single().firstSeen); assertEquals(200L,saved.single().lastSeen)
    }
    @Test fun accountIdentityAndCapacityArePreserved() {
        val buffer = ArchiveWriteBuffer(2)
        buffer.enqueue(record("a",account="one")); buffer.enqueue(record("a",account="two"))
        assertThrows(ArchiveFailure::class.java) { buffer.enqueue(record("b")) }
        assertEquals(2,buffer.size)
        val saved = arrayListOf<ArchiveRecord>(); buffer.drain(saved::add)
        assertEquals(listOf("one","two"),saved.map { it.account })
    }
    @Test fun nonRetryableArchiveFailureDropsBadRecordWithoutBlockingQueue() {
        val buffer = ArchiveWriteBuffer()
        val first = record("first")
        val corrupt = record("corrupt")
        val last = record("last")
        buffer.enqueue(first)
        buffer.enqueue(corrupt)
        buffer.enqueue(last)
        val written = arrayListOf<ArchiveRecord>()
        val dropped = arrayListOf<Pair<String, String>>()
        buffer.drain(onDropped = { entry, failure -> dropped += entry.id to failure.reason }) { entry ->
            if (entry.id == "corrupt") throw ArchiveFailure("invalid_media_record")
            written += entry
        }
        assertEquals(listOf(first, last), written)
        assertEquals(listOf("corrupt" to "invalid_media_record"), dropped)
        assertEquals(0, buffer.size)
    }
}
