package dev.tqmane.befuck.download

import dev.tqmane.befuck.archive.ArchiveAccounts
import dev.tqmane.befuck.symbols.CorePostLocationSymbols
import dev.tqmane.befuck.symbols.FeedMediaSymbols
import dev.tqmane.befuck.symbols.RemotePostLocationSymbols
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteFeedLocationCaptureTest {
    private enum class Friendship { Accepted, Pending, Rejected, Unknown }
    private class Person(val uid: String, val status: Friendship)
    private class Geo(val latitude: Double, val longitude: Double)
    private class NullableGeo(val latitude: Double?, val longitude: Double?)
    private class NullableRemote(val id: String, val location: NullableGeo?)
    private class Core(val id: String, val owner: Person, val point: Geo?)
    private class Image(val url: String = "https://example.invalid/back.jpg",
        val width: Int = 640, val height: Int = 480)
    private class Remote(val id: String, val primary: Image = Image(),
        val secondary: Image? = null, val location: Geo? = null)
    private class PostData(val postId: String, val ownerUid: String, val isMyPost: Boolean)

    private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private val coreReader = CorePostLocationSymbols(Core::class.java,
        field(Core::class.java, "owner"), field(Person::class.java, "uid"),
        field(Core::class.java, "point"), field(Geo::class.java, "latitude"),
        field(Geo::class.java, "longitude"), field(Core::class.java, "id"),
        field(Person::class.java, "status"))
    private val remoteReader = RemotePostLocationSymbols(Remote::class.java,
        field(Remote::class.java, "id"), field(Remote::class.java, "location"),
        field(Geo::class.java, "latitude"), field(Geo::class.java, "longitude"))
    private val symbols = FeedMediaSymbols(
        postModelClass = Remote::class.java,
        postDeserializerMethod = Any::class.java.getMethod("toString"),
        postIdField = field(Remote::class.java, "id"),
        postTakenAtField = null,
        postCaptionField = null,
        postPrimaryField = field(Remote::class.java, "primary"),
        postPrimaryMediaField = null,
        postSecondaryField = field(Remote::class.java, "secondary"),
        postSecondaryMediaField = null,
        postBtsMediaField = null,
        mediaUrlField = field(Image::class.java, "url"),
        mediaWidthField = field(Image::class.java, "width"),
        mediaHeightField = field(Image::class.java, "height"),
        mediaTypeField = null,
        postFeedCardComposableMethod = null,
        postDataIdField = field(PostData::class.java, "postId"),
        postDataIsMineField = field(PostData::class.java, "isMyPost"),
        viewStatePostDataField = null,
        viewStateDualMediaField = null,
        viewStateRealSponsoredPostUiStateField = null,
        dualMediaPrimaryField = null,
        dualMediaSecondaryField = null,
        blurredMediaRenderMethod = null,
        blurredOverlayComposableMethod = null,
        corePostLocationSymbols = coreReader,
        remotePostLocationSymbols = remoteReader,
        postDataOwnerUidField = field(PostData::class.java, "ownerUid"),
    )
    private lateinit var viewer: String

    @Before fun start() {
        ArchiveAccounts.invalidate()
        viewer = "viewer-${UUID.randomUUID()}"
    }

    @After fun finish() { ArchiveAccounts.invalidate() }

    @Test fun imagesAndGpsReconcileFromOneFeedDtoWithoutOpeningAPostOrMap() {
        val id = "friend-${UUID.randomUUID()}"
        val remote = Remote(id, location = Geo(35.68, 139.76))
        assertTrue(FeedPostMediaCache.capture(remote, symbols))
        assertNotNull(FeedPostMediaCache.get(id)!!.primary)
        assertNull(FeedPostMediaCache.get(id)!!.location)
        // CorePost may be released after a feed conversion, but its verified owner
        // and friendship must still unlock the original captured remote DTO later.
        FeedPostMediaCache.captureCorePostModel(Core(id, Person("friend", Friendship.Accepted), null), coreReader)
        assertNull(FeedPostMediaCache.get(id)!!.location)
        FeedPostMediaCache.capturePostDataIdentity(PostData("mine", viewer, true), symbols)
        assertEquals(viewer, ArchiveAccounts.snapshot().uid)
        assertEquals(PostLocation(35.68, 139.76, false), FeedPostMediaCache.get(id)!!.location)
        assertNotNull(FeedPostMediaCache.get(id)!!.primary)
    }

    @Test fun corePostLoadedBeforeViewerIdentityCanEnrichFeedWithoutOpeningMap() {
        val id = "early-core-${UUID.randomUUID()}"
        // The feed host constructs its CorePost before the current account
        // service (or PostDataUiModel isMyPost) becomes available.
        FeedPostMediaCache.captureCorePostModel(Core(id,
            Person("friend", Friendship.Accepted), Geo(35.68, 139.76)), coreReader)
        assertTrue(FeedPostMediaCache.capture(Remote(id, location = null), symbols))
        assertNull(FeedPostMediaCache.get(id)!!.location)
        FeedPostMediaCache.capturePostDataIdentity(PostData("viewer-main", viewer, true), symbols)
        assertEquals(viewer, ArchiveAccounts.snapshot().uid)
        assertEquals(PostLocation(35.68, 139.76, false), FeedPostMediaCache.get(id)!!.location)
        assertNotNull(FeedPostMediaCache.get(id)!!.primary)
    }

    @Test fun pendingNonFriendCannotAuthorizeRemoteGpsAfterAccountResolution() {
        val id = "pending-${UUID.randomUUID()}"
        FeedPostMediaCache.capture(Remote(id, location = Geo(35.0, 139.0)), symbols)
        FeedPostMediaCache.captureCorePostModel(Core(id, Person("unrelated", Friendship.Pending), null), coreReader)
        FeedPostMediaCache.capturePostDataIdentity(PostData("own", viewer, true), symbols)
        assertNull(FeedPostMediaCache.get(id)!!.location)
        assertNotNull(FeedPostMediaCache.get(id)!!.primary)
    }

    @Test fun validatedEmptyFriendGraphOverridesNativeAccepted() {
        FeedPostMediaCache.capturePostDataIdentity(PostData("my-post", viewer, true), symbols)
        val scope = ArchiveAccounts.snapshot()
        FeedPostMediaCache.captureFriendUids(scope, FeedPostMediaCache.beginFriendSnapshot(), emptySet())
        val id = "revoked-${UUID.randomUUID()}"
        FeedPostMediaCache.captureCorePostModel(Core(id, Person("friend", Friendship.Accepted), null), coreReader)
        FeedPostMediaCache.capture(Remote(id, location = Geo(35.0, 139.0)), symbols)
        assertNull(FeedPostMediaCache.get(id)!!.location)
    }

    @Test fun ownPostRemoteGpsIsSavedAndZeroZeroPlaceholderIsNot() {
        FeedPostMediaCache.capturePostDataIdentity(PostData("own-source", viewer, true), symbols)
        val id = "self-${UUID.randomUUID()}"
        FeedPostMediaCache.capturePostDataIdentity(PostData(id, viewer, true), symbols)
        FeedPostMediaCache.capture(Remote(id, location = Geo(35.0, 139.0)), symbols)
        assertEquals(PostLocation(35.0, 139.0, false), FeedPostMediaCache.get(id)!!.location)
        val empty = "empty-${UUID.randomUUID()}"
        FeedPostMediaCache.capturePostDataIdentity(PostData(empty, viewer, true), symbols)
        FeedPostMediaCache.capture(Remote(empty, location = Geo(0.0, 0.0)), symbols)
        assertNull(FeedPostMediaCache.get(empty)!!.location)
    }

    @Test fun explicitPermissionRequiredBeforeReadingRemoteCoordinates() {
        val model = Remote("no-read", location = Geo(35.0, 139.0))
        assertNull(remoteReader.snapshot(model) { false })
        assertEquals("no-read" to PostLocation(35.0, 139.0, false),
            remoteReader.snapshot(model) { it == "no-read" })
        assertNull(remoteReader.snapshot(Remote("zero", location = Geo(0.0, 0.0))) { true })
    }

    @Test fun nullableRemoteCoordinatesRequireBothValuesAndPermission() {
        val reader = RemotePostLocationSymbols(NullableRemote::class.java,
            field(NullableRemote::class.java, "id"), field(NullableRemote::class.java, "location"),
            field(NullableGeo::class.java, "latitude"), field(NullableGeo::class.java, "longitude"))
        val valid = NullableRemote("nullable", NullableGeo(35.0, 139.0))
        assertNull(reader.snapshot(valid) { false })
        assertEquals("nullable" to PostLocation(35.0, 139.0, false), reader.snapshot(valid) { true })
        for (location in listOf(null, NullableGeo(null, 139.0), NullableGeo(35.0, null), NullableGeo(0.0, 0.0))) {
            assertNull(reader.snapshot(NullableRemote("missing", location)) { true })
        }
    }
}
