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
import fcb

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33])
class FriendLocationCaptureTest {
    private enum class Relationship { Accepted, Canceled, Pending, Rejected, Sent, Blacklisted, Unknown }
    private class Owner(val uid: String, val userName: String = "Alice",
        val status: Relationship = Relationship.Unknown)
    private class Point(val latitude: Double, val longitude: Double)
    private class Content(val uri: String = "https://example.invalid/image", val mediaType: String = "IMAGE",
        val width: Int = 640, val height: Int = 480)
    private class Media(val a: Content = Content())
    private class Placeholder(val postId: String, val isFriend: Boolean, val latitude: Double, val longitude: Double)
    private class TypeToken(val type: Class<*>)
    private class MapUseCase {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        var failing = false
        var suspending = false
        var returningNull = false
        val started = java.util.concurrent.CountDownLatch(1)
        var pending: kotlin.coroutines.Continuation<Any?>? = null
        fun fetch(id: String, continuation: kotlin.coroutines.Continuation<Any?>): Any? {
            calls.incrementAndGet()
            check(!failing) { "Synthetic fetch failure" }
            if (suspending) {
                pending = continuation
                started.countDown()
                return kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            }
            if (returningNull) return null
            return Placeholder(id, true, 35.68, 139.76)
        }
    }
    private class MapScope(val useCase: MapUseCase) {
        fun get(token: TypeToken, qualifier: Any?, parameters: Any?): Any {
            check(token.type == MapUseCase::class.java)
            return useCase
        }
    }
    // CorePost's existing media boundary and the semantically resolved coordinate fields are both exercised.
    private class Post(val a: String, val b: Owner, val point: Point?, val c: Any? = null,
        val d: Media = Media(), val e: Media? = null, val t: String? = "2026-01-02T03:04:05Z",
        val k: String? = null, val u: String? = null, val o: Boolean = true)

    private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private val locationSymbols = CorePostLocationSymbols(Post::class.java,
        field(Post::class.java, "b"), field(Owner::class.java, "uid"), field(Post::class.java, "point"),
        field(Point::class.java, "latitude"), field(Point::class.java, "longitude"), field(Post::class.java, "a"),
        field(Owner::class.java, "status"))
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
        MapLocationPrefetcher.configure(null, null)
        fcb.root = null
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

    @Test fun feedCanArchiveAnAlreadyLoadedCorePostWithoutOpeningTheMap() {
        graph(setOf("friend"))
        val post = Post("automatic", Owner("friend"), Point(35.68, 139.76))
        FeedPostMediaCache.captureCorePostModel(post, locationSymbols)
        assertNull(FeedPostMediaCache.get("automatic"))
        // Media can be captured independently of its location reader.
        FeedPostMediaCache.captureCorePost(post, null)
        assertEquals("friend", FeedPostMediaCache.get("automatic")!!.ownerUid)
        assertEquals(PostLocation(35.68, 139.76, false), FeedPostMediaCache.get("automatic")!!.location)
        assertNotNull(FeedPostMediaCache.get("automatic")!!.primary)
    }

    @Test fun loadedCorePostCanWaitForFriendsWithoutFakingOtherLocations() {
        val model = Post("pending-feed", Owner("friend"), Point(35.0, 139.0))
        FeedPostMediaCache.captureCorePostModel(model, locationSymbols)
        FeedPostMediaCache.captureCorePost(model, null)
        assertNull(FeedPostMediaCache.get("pending-feed")!!.location)
        graph(setOf("friend"))
        assertEquals(PostLocation(35.0, 139.0, false), FeedPostMediaCache.get("pending-feed")!!.location)
        val stranger = Post("stranger-feed", Owner("stranger"), Point(50.0, 120.0))
        FeedPostMediaCache.captureCorePostModel(stranger, locationSymbols)
        FeedPostMediaCache.captureCorePost(stranger, null)
        assertNull(FeedPostMediaCache.get("stranger-feed")!!.location)
    }

    @Test fun semanticObservationNeverCreatesEmptyPostsOrCrossesAccounts() {
        val post = Post("scope-guard", Owner("friend"), Point(30.0, 130.0))
        FeedPostMediaCache.captureCorePostModel(post, locationSymbols)
        assertNull(FeedPostMediaCache.get("scope-guard"))
        scope = select("other-viewer")
        graph(setOf("friend"))
        FeedPostMediaCache.captureCorePost(post, null)
        assertNull(FeedPostMediaCache.get("scope-guard")!!.location)
    }

    @Test fun acceptedHostFriendCapturesGpsWithoutOpeningMapOrLoadingFriendsSql() {
        val host = Post("native-accepted", Owner("friend", status = Relationship.Accepted),
            Point(35.68, 139.76))
        FeedPostMediaCache.captureCorePostModel(host, locationSymbols)
        FeedPostMediaCache.captureCorePost(host, null)
        assertEquals(PostLocation(35.68, 139.76, false),
            FeedPostMediaCache.get("native-accepted")!!.location)
    }

    @Test fun nonFriendStatusesCannotYieldRawLocation() {
        for (status in Relationship.entries.filterNot { it == Relationship.Accepted }) {
            val host = Post("native-${status.name}", Owner("stranger", status = status), Point(35.0, 139.0))
            FeedPostMediaCache.captureCorePostModel(host, locationSymbols)
            FeedPostMediaCache.captureCorePost(host, null)
            assertNull(FeedPostMediaCache.get("native-${status.name}")!!.location)
        }
    }

    @Test fun emptyVerifiedGraphOverridesPreviouslyAcceptedHostStatus() {
        graph(emptySet())
        val old = Post("revoked-native", Owner("friend", status = Relationship.Accepted), Point(35.0, 139.0))
        FeedPostMediaCache.captureCorePostModel(old, locationSymbols)
        FeedPostMediaCache.captureCorePost(old, null)
        assertNull(FeedPostMediaCache.get("revoked-native")!!.location)
        val own = Post("own-native", Owner(viewer), Point(35.0, 139.0))
        FeedPostMediaCache.captureCorePostModel(own, locationSymbols)
        FeedPostMediaCache.captureCorePost(own, null)
        assertEquals(PostLocation(35.0, 139.0, false), FeedPostMediaCache.get("own-native")!!.location)
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
        FeedPostMediaCache.captureDisplayedLocation("displayed", approximate, oldScope)
        assertNull(FeedPostMediaCache.get("displayed")!!.location)
        // A genuinely displayed host coordinate belongs only to this current account.
        FeedPostMediaCache.captureDisplayedLocation("displayed", exact, scope)
        assertEquals(exact, FeedPostMediaCache.get("displayed")!!.location)
    }

    @Test fun startupFriendsQueryBeforeViewerResolutionReplaysUponAccountLogin() {
        ArchiveAccounts.invalidate()
        val emptyScope = ArchiveAccounts.snapshot()
        assertNull(emptyScope.uid)
        val token = FeedPostMediaCache.beginFriendSnapshot()
        FeedPostMediaCache.captureObservedFriendUids("login-viewer", emptyScope, token, setOf("friend-1", "friend-2"))
        val post = Post("startup-post", Owner("friend-1"), Point(35.68, 139.76))
        FeedPostMediaCache.captureCorePostModel(post, locationSymbols)
        FeedPostMediaCache.captureCorePost(post, null)
        ArchiveAccounts.accept(emptyScope.generation, "login-viewer")
        scope = ArchiveAccounts.snapshot()
        assertEquals(PostLocation(35.68, 139.76, false), FeedPostMediaCache.get("startup-post")!!.location)
    }

    private fun configurePrefetch(useCase: MapUseCase?) {
        field(RuntimeKnowledge::class.java, "versionName").set(null, "3.99.1")
        field(RuntimeKnowledge::class.java, "versionCode").setLong(null, 3603560L)
        fcb.root = useCase?.let(::MapScope)
        val prefetchSymbols = MapPlaceholderLocationSymbols(Placeholder::class.java.declaredConstructors.toList(),
            field(Placeholder::class.java, "postId"), field(Placeholder::class.java, "isFriend"),
            field(Placeholder::class.java, "latitude"), field(Placeholder::class.java, "longitude"),
            MapUseCase::class.java.declaredMethods.single { it.name == "fetch" }.apply { isAccessible = true })
        MapLocationPrefetcher.configure(prefetchSymbols, locationSymbols)
    }

    private fun drainPrefetch() {
        (field(MapLocationPrefetcher::class.java, "executor").get(MapLocationPrefetcher)
            as java.util.concurrent.ThreadPoolExecutor).submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test fun feedResolvesTheHostContainerWithoutOpeningMapOrObservingItsFactory() {
        val useCase = MapUseCase()
        configurePrefetch(useCase)
        graph(setOf("friend"))
        val post = Post("without-map", Owner("friend"), null)
        FeedPostMediaCache.captureCorePostModel(post, locationSymbols)
        FeedPostMediaCache.captureCorePost(post, null)
        drainPrefetch()
        assertEquals(PostLocation(35.68, 139.76, false), FeedPostMediaCache.get(post.a)!!.location)
        FeedPostMediaCache.retryMissingLocations()
        drainPrefetch()
        assertEquals(1, useCase.calls.get())
    }

    @Test fun unavailableContainerCanBeRetriedOnTheNextFeedObservation() {
        configurePrefetch(null)
        val post = Post("container-not-ready", Owner(viewer), null)
        FeedPostMediaCache.captureCorePostModel(post, locationSymbols)
        FeedPostMediaCache.captureCorePost(post, null)
        drainPrefetch()
        assertNull(FeedPostMediaCache.get(post.a)!!.location)
        val useCase = MapUseCase()
        fcb.root = MapScope(useCase)
        FeedPostMediaCache.retryMissingLocations()
        drainPrefetch()
        assertEquals(PostLocation(35.68, 139.76, false), FeedPostMediaCache.get(post.a)!!.location)
        assertEquals(1, useCase.calls.get())
    }

    @Test fun failedFetchCanRetryButAnUnfriendedPostDoesNotFetch() {
        val useCase = MapUseCase().apply { failing = true }
        configurePrefetch(useCase)
        graph(setOf("friend"))
        for ((id, owner) in listOf("retry-fetch" to "friend", "not-a-friend" to "stranger")) {
            val post = Post(id, Owner(owner), null)
            FeedPostMediaCache.captureCorePostModel(post, locationSymbols)
            FeedPostMediaCache.captureCorePost(post, null)
        }
        drainPrefetch()
        assertEquals(1, useCase.calls.get())
        useCase.failing = false
        FeedPostMediaCache.retryMissingLocations()
        drainPrefetch()
        assertEquals(2, useCase.calls.get())
        assertNotNull(FeedPostMediaCache.get("retry-fetch")!!.location)
        assertNull(FeedPostMediaCache.get("not-a-friend")!!.location)
    }

    @Test fun suspendedFetchCannotWriteItsResultAfterAccountSwitch() {
        val useCase = MapUseCase().apply { suspending = true }
        configurePrefetch(useCase)
        val old = Post("switched-fetch", Owner(viewer), null)
        FeedPostMediaCache.captureCorePostModel(old, locationSymbols)
        FeedPostMediaCache.captureCorePost(old, null)
        assertTrue(useCase.started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try {
            scope = select("new-prefetch-viewer")
            graph(emptySet())
            val current = Post(old.a, Owner("former-friend"), null)
            FeedPostMediaCache.captureCorePostModel(current, locationSymbols)
            FeedPostMediaCache.captureCorePost(current, null)
        } finally {
            useCase.pending!!.resumeWith(Result.success(Placeholder(old.a, true, 35.68, 139.76)))
            drainPrefetch()
        }
        assertNull(FeedPostMediaCache.get(old.a)!!.location)
        assertEquals(1, useCase.calls.get())
    }

    @Test fun unverifiedAccountPrefetchRespectsHostFriendshipAndCoroutineNullDoesNotRetry() {
        val useCase = MapUseCase().apply { returningNull = true }
        configurePrefetch(useCase)
        scope = select("no-graph-${UUID.randomUUID()}")

        val pendingPost = Post("pending-friend-post", Owner("pending-user", status = Relationship.Pending), null)
        FeedPostMediaCache.captureCorePostModel(pendingPost, locationSymbols)
        FeedPostMediaCache.captureCorePost(pendingPost, null)
        drainPrefetch()
        assertEquals(0, useCase.calls.get())

        val acceptedNullPost = Post("accepted-no-location", Owner("friend-user", status = Relationship.Accepted), null)
        FeedPostMediaCache.captureCorePostModel(acceptedNullPost, locationSymbols)
        FeedPostMediaCache.captureCorePost(acceptedNullPost, null)
        drainPrefetch()
        assertEquals(1, useCase.calls.get())

        // A normal coroutine null return means the host completed cleanly without coordinates; do not retry forever.
        useCase.returningNull = false
        FeedPostMediaCache.retryMissingLocations()
        drainPrefetch()
        assertEquals(1, useCase.calls.get())

        // A kotlin.Result.failure resumption must clear prefetchAttempted so retryMissingLocations can retry.
        useCase.suspending = true
        val resumedFailurePost = Post("resumed-failure-post", Owner("friend-user", status = Relationship.Accepted), null)
        FeedPostMediaCache.captureCorePostModel(resumedFailurePost, locationSymbols)
        FeedPostMediaCache.captureCorePost(resumedFailurePost, null)
        assertTrue(useCase.started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        useCase.pending!!.resumeWith(Result.failure(RuntimeException("Synthetic failure")))
        drainPrefetch()
        assertEquals(2, useCase.calls.get())

        useCase.suspending = false
        FeedPostMediaCache.retryMissingLocations()
        drainPrefetch()
        assertEquals(3, useCase.calls.get())
        assertEquals(PostLocation(35.68, 139.76, false), FeedPostMediaCache.get(resumedFailurePost.a)!!.location)
    }

    @Test fun unknownHostFriendStatusTriggersPrefetchWithoutLeakingRawCoordinates() {
        val useCase = MapUseCase().apply { suspending = true }
        configurePrefetch(useCase)
        scope = select("unknown-prefetch-${UUID.randomUUID()}")

        val roomFriend = Post("room-unknown-friend", Owner("room-friend", status = Relationship.Unknown), Point(11.11, 22.22))
        FeedPostMediaCache.captureCorePostModel(roomFriend, locationSymbols)
        FeedPostMediaCache.captureCorePost(roomFriend, null)
        assertTrue(useCase.started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try {
            // Before prefetch completes and before Friends SQL runs, raw Unknown-status coordinates must not be read.
            assertNull(FeedPostMediaCache.get(roomFriend.a)!!.location)
        } finally {
            useCase.suspending = false
            useCase.pending!!.resumeWith(Result.success(Placeholder(roomFriend.a, true, 35.68, 139.76)))
            drainPrefetch()
        }
        assertEquals(1, useCase.calls.get())
        assertEquals(PostLocation(35.68, 139.76, false), FeedPostMediaCache.get(roomFriend.a)!!.location)

        for (denied in listOf(
            Relationship.Canceled,
            Relationship.Pending,
            Relationship.Rejected,
            Relationship.Sent,
            Relationship.Blacklisted,
        )) {
            val post = Post("denied-${denied.name}", Owner("non-friend-${denied.name}", status = denied), Point(11.11, 22.22))
            FeedPostMediaCache.captureCorePostModel(post, locationSymbols)
            FeedPostMediaCache.captureCorePost(post, null)
        }
        drainPrefetch()
        assertEquals(1, useCase.calls.get())
    }

    @Test fun queuedPrefetchesSkipAfterFriendGraphReplayResolvesOrExcludesThem() {
        val useCase = MapUseCase().apply { suspending = true }
        configurePrefetch(useCase)
        scope = select("replay-skip-${UUID.randomUUID()}")

        val first = Post("first-unknown", Owner("friend-a", status = Relationship.Unknown), Point(12.34, 56.78))
        val second = Post("second-unknown", Owner("friend-b", status = Relationship.Unknown), Point(23.45, 67.89))
        val thirdStranger = Post("third-stranger", Owner("stranger-c", status = Relationship.Unknown), Point(34.56, 78.90))
        val fourthNoPoint = Post("fourth-no-point", Owner("friend-d", status = Relationship.Unknown), null)
        for (p in listOf(first, second, thirdStranger, fourthNoPoint)) {
            FeedPostMediaCache.captureCorePostModel(p, locationSymbols)
            FeedPostMediaCache.captureCorePost(p, null)
        }
        assertTrue(useCase.started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try {
            // Simulate MapUseCase resolving BeReal's Friends graph while running the first item.
            graph(setOf("friend-a", "friend-b", "friend-d"))
        } finally {
            useCase.suspending = false
            useCase.pending!!.resumeWith(Result.success(Placeholder(first.a, true, 35.68, 139.76)))
            drainPrefetch()
        }
        // second-unknown got its coordinate from the Friends graph replay and third-stranger was excluded,
        // so only first-unknown (trigger) and fourth-no-point (missing raw point) invoke MapUseCase.
        assertEquals(2, useCase.calls.get())
        assertEquals(PostLocation(23.45, 67.89, false), FeedPostMediaCache.get(second.a)!!.location)
        assertNull(FeedPostMediaCache.get(thirdStranger.a)!!.location)
        assertEquals(PostLocation(35.68, 139.76, false), FeedPostMediaCache.get(fourthNoPoint.a)!!.location)
    }

    @Test fun firstAuthorizationCapturePreservesResolvedAccountWhileTokenSwitchInvalidates() {
        @Suppress("UNCHECKED_CAST")
        val headersField = dev.tqmane.befuck.posting.BeFakeAuthHeaders::class.java
            .getDeclaredField("headers")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val ref = headersField.get(dev.tqmane.befuck.posting.BeFakeAuthHeaders)
            as java.util.concurrent.atomic.AtomicReference<Map<String, String>>
        val previous = ref.get()
        ref.set(emptyMap())
        try {
            val resolved = select("auth-viewer-${UUID.randomUUID()}")
            dev.tqmane.befuck.posting.BeFakeAuthHeaders.capture("mobile-l7.bereal.com", "Authorization", "Bearer initial")
            assertEquals(resolved, ArchiveAccounts.snapshot())

            dev.tqmane.befuck.posting.BeFakeAuthHeaders.capture("mobile-l7.bereal.com", "Authorization", "Bearer initial")
            assertEquals(resolved, ArchiveAccounts.snapshot())

            dev.tqmane.befuck.posting.BeFakeAuthHeaders.capture("mobile-l7.bereal.com", "Authorization", "Bearer switched")
            assertNull(ArchiveAccounts.snapshot().uid)
            assertEquals(resolved.generation + 1L, ArchiveAccounts.snapshot().generation)
        } finally {
            ref.set(previous)
        }
    }
}
