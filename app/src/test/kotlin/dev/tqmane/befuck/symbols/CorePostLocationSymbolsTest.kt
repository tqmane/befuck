package dev.tqmane.befuck.symbols

import dev.tqmane.befuck.download.PostLocation
import org.junit.Assert.*
import org.junit.Test

class CorePostLocationSymbolsTest {
    private enum class Relationship { Accepted, Canceled, Pending, Rejected, Sent, Blacklisted, Unknown }
    private class User(val uid: String, val status: Relationship = Relationship.Unknown)
    private class Coordinate(val latitude: Double, val longitude: Double)
    private class Post(val owner: User, val point: Coordinate?, val id: String = "post")

    private fun field(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }
    private val symbols = CorePostLocationSymbols(Post::class.java,
        field(Post::class.java, "owner"), field(User::class.java, "uid"), field(Post::class.java, "point"),
        field(Coordinate::class.java, "latitude"), field(Coordinate::class.java, "longitude"), field(Post::class.java, "id"), field(User::class.java, "status"))

    @Test fun identityReadsOnlyTheVerifiedPostAndAuthor() {
        val friend = Post(User("friend"), Coordinate(35.0, 139.0), "p-123")
        assertEquals("p-123" to "friend", symbols.identity(friend))
        assertNull(symbols.snapshot(friend, "viewer"))
        assertEquals(PostLocation(35.0, 139.0, false), symbols.snapshot(friend, "viewer", setOf("friend")))
        assertNull(symbols.identity(Any()))
        assertTrue(symbols.constructors.isNotEmpty())
    }

    @Test fun nativeAcceptedRequiresExplicitPreGraphPermission() {
        val accepted = Post(User("friend", Relationship.Accepted), Coordinate(35.68, 139.76))
        assertNull(symbols.snapshot(accepted, "viewer"))
        assertEquals(PostLocation(35.68, 139.76, false),
            symbols.snapshot(accepted, "viewer", allowNativeAccepted = true))
        assertNull(symbols.snapshot(Post(User("pending", Relationship.Pending),
            Coordinate(35.68, 139.76)), "viewer", allowNativeAccepted = true))
    }

    @Test fun ownPostCapturesAnExactValidatedCoordinate() {
        assertEquals(PostLocation(35.0, 139.0, false), symbols.snapshot(Post(User("viewer"), Coordinate(35.0, 139.0)), "viewer"))
        assertNull(symbols.snapshot(Post(User("viewer"), Coordinate(0.0, 0.0)), "viewer"))
    }

    @Test fun unknownViewerOrOtherAuthorNeverLeaksRawCoordinates() {
        val post = Post(User("author"), Coordinate(35.0, 139.0))
        assertNull(symbols.snapshot(post, "viewer"))
        assertNull(symbols.snapshot(post, null))
        assertNull(symbols.snapshot(post, ""))
        assertNull(symbols.snapshot(Any(), "author"))
    }

    @Test fun onlyTheExplicitVerifiedFriendsSetAuthorizesOtherOwners() {
        val post = Post(User("friend"), Coordinate(35.0, 139.0))
        assertEquals(PostLocation(35.0, 139.0, false), symbols.snapshot(post, "viewer", setOf("friend")))
        assertNull(symbols.snapshot(post, "viewer", emptySet()))
        assertNull(symbols.snapshot(post, "viewer", setOf("different-friend")))
        assertNull(symbols.snapshot(post, null, setOf("friend")))
        assertNull(symbols.snapshot(Post(User(""), Coordinate(35.0, 139.0)), "viewer", setOf("")))
    }

    @Test fun absentOrMalformedHostLocationIsOmitted() {
        assertNull(symbols.snapshot(Post(User("viewer"), null), "viewer"))
        assertNull(symbols.snapshot(Post(User("viewer"), Coordinate(Double.NaN, 139.0)), "viewer"))
        assertNull(symbols.snapshot(Post(User("viewer"), Coordinate(35.0, 181.0)), "viewer"))
    }
}
