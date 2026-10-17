package dev.tqmane.befuck.symbols

import dev.tqmane.befuck.download.PostLocation
import org.junit.Assert.*
import org.junit.Test

class CorePostLocationSymbolsTest {
    private class User(val uid: String)
    private class Coordinate(val latitude: Double, val longitude: Double)
    private class Post(val owner: User, val point: Coordinate?)

    private fun field(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }
    private val symbols = CorePostLocationSymbols(Post::class.java,
        field(Post::class.java, "owner"), field(User::class.java, "uid"), field(Post::class.java, "point"),
        field(Coordinate::class.java, "latitude"), field(Coordinate::class.java, "longitude"))

    @Test fun ownPostCapturesAnExactValidatedCoordinate() {
        assertEquals(PostLocation(35.0, 139.0, false), symbols.snapshot(Post(User("viewer"), Coordinate(35.0, 139.0)), "viewer"))
        assertEquals(PostLocation(0.0, 0.0, false), symbols.snapshot(Post(User("viewer"), Coordinate(0.0, 0.0)), "viewer"))
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
