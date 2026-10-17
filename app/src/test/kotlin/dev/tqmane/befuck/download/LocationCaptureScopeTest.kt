package dev.tqmane.befuck.download

import dev.tqmane.befuck.archive.ArchiveAccounts
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33])
class LocationCaptureScopeTest {
    @Test fun oldAccountLocationCannotBeStampedIntoNewAccountAfterHostSnapshot() {
        ArchiveAccounts.invalidate()
        ArchiveAccounts.accept(ArchiveAccounts.snapshot().generation, "location-old-account")
        val scope = ArchiveAccounts.snapshot()
        ArchiveAccounts.invalidate()
        ArchiveAccounts.accept(ArchiveAccounts.snapshot().generation, "location-new-account")
        try {
            FeedPostMediaCache.captureLocation("old-account-location", PostLocation(35.0, 139.0, false), scope)
            assertNull(FeedPostMediaCache.get("old-account-location"))
            assertEquals(0, FeedPostMediaCache.size())
        } finally { ArchiveAccounts.invalidate() }
    }
}
