package dev.tqmane.befuck.archive

import dev.tqmane.befuck.download.FeedMediaItem
import dev.tqmane.befuck.download.FeedPostMedia
import org.junit.Assert.*
import org.junit.Test

class ArchiveMergeTest {
    private fun record(at: Long, url: String = "https://example.invalid/image?sig=$at", caption: String? = null, user: String? = null, owner: String? = "author") =
        ArchiveRecord.observe("account", FeedPostMedia("post", null, caption, FeedMediaItem(url, "image/jpeg", 100, 200), null,
            capturedAtMillis = at, username = user, ownerUid = owner))
    @Test fun repeatedImportIsExactlyIdempotent() {
        val a = record(100, caption = "hello", user = "Alice")
        assertEquals(a, ArchiveMerge.merge(a, a))
    }
    @Test fun mergeOrderAndGroupingConvergeIncludingSignedUrlAlternatives() {
        val a = record(100, caption = "first"); val b = record(200, user = "Alice"); val c = record(150, caption = "edited")
        assertEquals(ArchiveMerge.merge(a,b), ArchiveMerge.merge(b,a))
        assertEquals(ArchiveMerge.merge(ArchiveMerge.merge(a,b),c), ArchiveMerge.merge(a,ArchiveMerge.merge(b,c)))
        val merged = ArchiveMerge.merge(ArchiveMerge.merge(a,b),c)
        assertEquals(3, merged.media.size)
        assertEquals("https://example.invalid/image?sig=200", merged.post().primary!!.url)
        assertEquals("edited", merged.post().caption); assertEquals("Alice", merged.post().username)
        assertEquals(100L, merged.firstSeen); assertEquals(200L, merged.lastSeen)
    }
    @Test fun olderBackupDoesNotOverrideFreshFieldsButCanFillMissingFields() {
        val fresh = record(200, caption = "new"); val old = record(100, caption = "old", user = "Alice")
        val result = ArchiveMerge.merge(fresh,old)
        assertEquals("new",result.post().caption); assertEquals("Alice",result.post().username)
        assertEquals(100L,result.fields["username"]!!.at)
        assertEquals("https://example.invalid/image?sig=200",result.post().primary!!.url)
    }
    @Test fun equalObservationTimesUseStableTieBreakNotImportOrder() {
        val a=record(100,caption="alpha",user="A");val b=record(100,caption="beta",user="B")
        assertEquals(ArchiveMerge.merge(a,b),ArchiveMerge.merge(b,a))
        assertEquals("beta",ArchiveMerge.merge(a,b).post().caption)
    }
    @Test fun explicitEmptyCaptionIsAValueButUnknownNullNeverDeletes() {
        val a=record(100,caption="before");val b=record(200,caption="")
        assertEquals("",ArchiveMerge.merge(a,b).post().caption)
        assertEquals("before",ArchiveMerge.merge(a,record(300)).post().caption)
    }
    @Test fun changedUsernameDoesNotCreateADifferentPostOrAuthor() {
        val a=record(100,user="old-name");val b=record(200,user="new-name")
        val result=ArchiveMerge.merge(a,b)
        assertEquals("post",result.id);assertEquals("author",result.post().ownerUid);assertEquals("new-name",result.post().username)
    }
    @Test fun conflictingAuthorOrAccountCannotMerge() {
        assertEquals("author_conflict",assertThrows(ArchiveFailure::class.java) { ArchiveMerge.merge(record(100),record(200,owner="other")) }.reason)
        assertEquals("account",assertThrows(ArchiveFailure::class.java) { ArchiveMerge.merge(record(100),record(200).copy(account="other")) }.reason)
    }
    @Test fun sameUrlDoesNotLoseDimensionsWhenLaterObservationOmitsThem() {
        val a=record(100,url="https://example.invalid/image")
        val b=ArchiveRecord.observe("account",FeedPostMedia("post",null,null,FeedMediaItem("https://example.invalid/image",null,null,null),null,capturedAtMillis=200))
        val result=ArchiveMerge.merge(a,b)
        assertEquals(1,result.media.size);assertEquals(100,result.post().primary!!.width)
        assertEquals(100L,result.media.values.single().fields["width"]!!.at)
        assertEquals(200L,result.media.values.single().lastSeen)
    }
    @Test fun invalidUrlsAndFutureObservationTimesAreRejected() {
        for (url in listOf("http://example.invalid/a","file:///data/private","https://user:pass@example.invalid/a","https://example.invalid/a#fragment")) {
            assertThrows(ArchiveFailure::class.java) { ArchiveMerge.validate(record(100,url)) }
        }
        assertThrows(ArchiveFailure::class.java) { ArchiveMerge.validate(record(100_000_000),now=0) }
    }
    @Test fun legacyAssociationDropsViewerSpecificOwnFlag() {
        val a=record(100).copy(account="",fields=record(100).fields+("is_own" to ObservedValue("1",100)))
        assertFalse(a.forAccount("account").post().isOwnPost!!)
        assertNull(a.forAccount("account").fields["is_own"])
    }
    @Test fun malformedNewDateCannotEraseAKnownValidPostingTime() {
        val a=record(100).let { it.copy(fields=it.fields+("posted_at" to ObservedValue("2026-10-13T01:00:00Z",100))) }
        val b=record(200).let { it.copy(fields=it.fields+("posted_at" to ObservedValue("not-a-date",200))) }
        assertEquals("2026-10-13T01:00:00Z",ArchiveMerge.merge(a,b).post().postedAt)
        assertEquals(ArchiveMerge.merge(a,b),ArchiveMerge.merge(b,a))
    }
}
