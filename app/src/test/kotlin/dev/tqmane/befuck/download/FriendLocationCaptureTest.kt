package dev.tqmane.befuck.download

import dev.tqmane.befuck.archive.ArchiveAccounts
import dev.tqmane.befuck.runtime.RuntimeKnowledge
import dev.tqmane.befuck.symbols.CorePostLocationSymbols
import dev.tqmane.befuck.symbols.FeedMediaSymbols
import dev.tqmane.befuck.symbols.MapPlaceholderLocationSymbols
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.UUID

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33])
class FriendLocationCaptureTest {
    private class Owner(val uid: String, val userName: String = "Alice")
    private class Point(val latitude: Double, val longitude: Double)
    private class Content(val uri: String = "https://example.invalid/image", val mediaType: String = "IMAGE",
        val width: Int = 640, val height: Int = 480)
    private class Media(val a: Content = Content())
    private class Placeholder(val postId: String, val isFriend: Boolean, val latitude: Double, val longitude: Double)
    // CorePost's existing media boundary and the semantically resolved coordinate fields are both exercised.
    private class Post(val a: String, val b: Owner, val point: Point?, val c: Any? = null,
        val d: Media = Media(), val e: Media? = null, val t: String? = "2026-01-02T03:04:05Z",
        val k: String? = null, val u: String? = null, val o: Boolean = true)

    private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private val locationSymbols = CorePostLocationSymbols(Post::class.java,
        field(Post::class.java, "b"), field(Owner::class.java, "uid"), field(Post::class.java, "point"),
        field(Point::class.java, "latitude"), field(Point::class.java, "longitude"))
    private val placeholderSymbols = MapPlaceholderLocationSymbols(Placeholder::class.java.declaredConstructors.toList(),
        field(Placeholder::class.java, "postId"), field(Placeholder::class.java, "isFriend"),
        field(Placeholder::class.java, "latitude"), field(Placeholder::class.java, "longitude"))
    private val symbols = FeedMediaSymbols::class.java.declaredConstructors.single { !it.isSynthetic }
        .newInstance(*FeedMediaSymbols::class.java.declaredConstructors.single { !it.isSynthetic }.parameterTypes.map {
            when (it) {
                CorePostLocationSymbols::class.java -> locationSymbols
                MapPlaceholderLocationSymbols::class.java -> placeholderSymbols
                else -> null
            }
        }.toTypedArray()) as FeedMediaSymbols
    private val viewer = "viewer-${UUID.randomUUID()}"
    private lateinit var scope: ArchiveAccounts.Scope
    private var previousVersionName: String? = null
    private var previousVersionCode = -1L

    private fun select(uid: String): ArchiveAccounts.Scope {
        ArchiveAccounts.invalidate()
        ArchiveAccounts.accept(ArchiveAccounts.snapshot().generation, uid)
        return ArchiveAccounts.snapshot()
    }
    @Before fun start() {
        // The existing media capture boundary is installed only for a known host version.
        previousVersionName = RuntimeKnowledge.versionName
        previousVersionCode = RuntimeKnowledge.versionCode
        field(RuntimeKnowledge::class.java, "versionName").set(null, "3.97.0")
        field(RuntimeKnowledge::class.java, "versionCode").setLong(null, 3597523L)
        scope = select(viewer)
    }
    @After fun finish() {
        ArchiveAccounts.invalidate()
        field(RuntimeKnowledge::class.java, "versionName").set(null, previousVersionName)
        field(RuntimeKnowledge::class.java, "versionCode").setLong(null, previousVersionCode)
    }

    private fun capture(id: String, owner: String = "friend", latitude: Double = 35.0): Post {
        val post = Post(id, Owner(owner), Point(latitude, 139.0))
        FeedPostMediaCache.captureCorePost(post, symbols)
        assertNotNull("CorePost fixture failed: ${ShadowLog.getLogsForTag("BeFuck/Cache").lastOrNull()?.throwable}",
            FeedPostMediaCache.get(id)?.primary)
        return post
    }
    private fun graph(uids: Set<String>, token: Long = FeedPostMediaCache.beginFriendSnapshot(),
        observedScope: ArchiveAccounts.Scope = scope) {
        FeedPostMediaCache.captureFriendUids(observedScope.uid!!, observedScope.generation, token, uids)
    }

    @Test fun firstCompleteGraphReplaysLiveModelsWithoutHavingStoredTheirRawCoordinates() {
        val pending = capture("waiting")
        assertNull(FeedPostMediaCache.get("waiting")!!.location)
        graph(setOf("friend"))
        assertEquals(PostLocation(35.0, 139.0, false), FeedPostMediaCache.get("waiting")!!.location)
        assertEquals("waiting", pending.a) // Keep the host model alive until after replay.
        capture("subsequent")
        assertEquals(PostLocation(35.0, 139.0, false), FeedPostMediaCache.get("subsequent")!!.location)
        capture("not-friend", owner = "stranger")
        assertNull(FeedPostMediaCache.get("not-friend")!!.location)
    }

    @Test fun fullReplacementAndEmptyGraphRevokeFutureCaptureAndRetainHistoricalObservations() {
        graph(setOf("friend"))
        capture("history")
        graph(setOf("different-friend"))
        capture("history", latitude = 50.0)
        assertEquals(PostLocation(35.0, 139.0, false), FeedPostMediaCache.get("history")!!.location)
        capture("revoked")
        assertNull(FeedPostMediaCache.get("revoked")!!.location)
        graph(emptySet())
        capture("also-revoked", owner = "different-friend")
        assertNull(FeedPostMediaCache.get("also-revoked")!!.location)
        capture("own", owner = viewer)
        assertEquals(PostLocation(35.0, 139.0, false), FeedPostMediaCache.get("own")!!.location)
    }

    @Test fun delayedOldGraphCannotUndoANewerEmptyResultOrBeAppliedTwice() {
        val older = FeedPostMediaCache.beginFriendSnapshot()
        val newer = FeedPostMediaCache.beginFriendSnapshot()
        graph(emptySet(), newer)
        graph(setOf("friend"), older)
        graph(setOf("friend"), newer)
        capture("revocation-wins")
        assertNull(FeedPostMediaCache.get("revocation-wins")!!.location)
    }

    @Test fun graphAndPendingModelsAreDroppedOnAccountChangeIncludingAwayAndBack() {
        val oldPending = capture("old-pending")
        val oldScope = scope
        val staleToken = FeedPostMediaCache.beginFriendSnapshot()
        select("another-viewer")
        scope = select(viewer)
        graph(setOf("friend"), staleToken, oldScope)
        capture("new-pending")
        assertNull(FeedPostMediaCache.get("new-pending")!!.location)
        graph(setOf("friend"))
        assertNull(FeedPostMediaCache.get("old-pending")?.location)
        assertEquals("old-pending", oldPending.a)

        scope = select("third-viewer")
        capture("third-account")
        assertNull(FeedPostMediaCache.get("third-account")!!.location)
    }

    @Test fun completeGraphIsDefensivelyCopiedAndArchiveResetDropsPendingReplay() {
        val friends = mutableSetOf("friend")
        graph(friends)
        friends += "stranger"
        capture("unverified-mutation", owner = "stranger")
        assertNull(FeedPostMediaCache.get("unverified-mutation")!!.location)

        val pending = capture("cleared", owner = "later-friend")
        FeedPostMediaCache.invalidateArchiveMemory()
        graph(setOf("later-friend"))
        assertNull(FeedPostMediaCache.get("cleared")?.location)
        assertEquals("cleared", pending.a)
    }

    @Test fun hostMapFriendFlagCapturesRealLocationWithoutWaitingForOptionalGraph() {
        // The host has already checked isFriend for its own displayed map placeholder.
        capture("placeholder")
        FeedPostMediaCache.captureMapPlaceholder(Placeholder("placeholder", false, 40.0, 140.0), symbols)
        assertNull(FeedPostMediaCache.get("placeholder")!!.location)
        FeedPostMediaCache.captureMapPlaceholder(Placeholder("placeholder", true, 0.0, 0.0), symbols)
        assertNull(FeedPostMediaCache.get("placeholder")!!.location)
        FeedPostMediaCache.captureMapPlaceholder(Placeholder("placeholder", true, 40.0, 140.0), symbols)
        assertEquals(PostLocation(40.0, 140.0, false), FeedPostMediaCache.get("placeholder")!!.location)
        FeedPostMediaCache.captureMapPlaceholder(Placeholder("missing-owner", true, 40.0, 140.0), symbols)
        assertNull(FeedPostMediaCache.get("missing-owner")?.location)
        graph(emptySet())
        FeedPostMediaCache.captureMapPlaceholder(Placeholder("placeholder", false, 50.0, 150.0), symbols)
        assertEquals(PostLocation(40.0, 140.0, false), FeedPostMediaCache.get("placeholder")!!.location)
    }

    @Test fun pendingHostMapCoordinatesReplayOnceTheActualPostOwnerArrives() {
        val early = Placeholder("late", true, 35.68, 139.76)
        FeedPostMediaCache.captureMapPlaceholder(early, symbols)
        assertNull(FeedPostMediaCache.get("late"))
        capture("late")
        assertEquals(PostLocation(35.68, 139.76, false), FeedPostMediaCache.get("late")!!.location)
        val stale = Placeholder("other-account", true, 55.0, 33.0)
        FeedPostMediaCache.captureMapPlaceholder(stale, symbols)
        scope = select("next-viewer")
        capture("other-account", owner = "friend")
        assertNull(FeedPostMediaCache.get("other-account")!!.location)
    }

    @Test fun hostDisplayedLocationsRequireKnownOwnerAndMatchingAccountScope() {
        val exact = PostLocation(40.0, 140.0, false)
        val approximate = exact.copy(approximate = true)
        capture("displayed")
        FeedPostMediaCache.captureDisplayedLocation("displayed", exact, scope)
        assertEquals(exact, FeedPostMediaCache.get("displayed")!!.location)
        FeedPostMediaCache.captureDisplayedLocation("missing-owner", approximate, scope)
        assertNull(FeedPostMediaCache.get("missing-owner")?.location)
        FeedPostMediaCache.captureDisplayedLocation("displayed", approximate, scope)
        assertEquals(approximate, FeedPostMediaCache.get("displayed")!!.location)
        graph(setOf("friend"))
        FeedPostMediaCache.captureDisplayedLocation("displayed", exact, scope)
        assertEquals(exact, FeedPostMediaCache.get("displayed")!!.location)

        val oldScope = scope
        scope = select("next-viewer")
        capture("displayed")
        FeedPostMediaCache.captureDisplayedLocation("displayed", exact, scope)
        FeedPostMediaCache.captureDisplayedLocation("displayed", approximate, oldScope)
        assertNull(FeedPostMediaCache.get("displayed")!!.location)
    }
}
