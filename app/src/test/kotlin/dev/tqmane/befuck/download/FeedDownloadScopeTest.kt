package dev.tqmane.befuck.download

import dev.tqmane.befuck.archive.ArchiveAccounts
import org.junit.Assert.*
import org.junit.Test

class FeedDownloadScopeTest {
    private fun post(url: String) = FeedPostMedia("post",null,null,FeedMediaItem(url,"image/jpeg",null,null),null,capturedAtMillis=100)
    @Test fun differentUrlSnapshotsAndAccountsCannotCoalesce() {
        ArchiveAccounts.invalidate();val one=FeedDownloadScope()
        val snapshot=post("https://example.invalid/first")
        val key=one.key(snapshot,FeedMediaSelection.PRIMARY,false)
        assertNotEquals(key,one.key(post("https://example.invalid/second"),FeedMediaSelection.PRIMARY,false))
        assertNotEquals(key,one.key(snapshot,FeedMediaSelection.ALL,false))
        assertFalse(key.contains("example"))
        ArchiveAccounts.invalidate();val two=FeedDownloadScope()
        assertNotEquals(key,two.key(snapshot,FeedMediaSelection.PRIMARY,false))
        assertThrows(java.io.IOException::class.java) { one.check() }
        two.check()
    }
}
