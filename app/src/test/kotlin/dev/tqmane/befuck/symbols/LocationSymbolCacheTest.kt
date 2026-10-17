package dev.tqmane.befuck.symbols

import dev.tqmane.befuck.download.PostLocation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33])
class LocationSymbolCacheTest {
    private class User(val uid: String)
    private class Coordinate(val latitude: Double, val longitude: Double)
    private class Post(val owner: User, val point: Coordinate?, val id: String = "stored-id")
    private class Placeholder(val postId: String, val isFriend: Boolean, val latitude: Double, val longitude: Double)
    interface GraphStatement {
        fun bindText(index: Int, value: String)
        fun step(): Boolean
        fun getText(index: Int): String
        fun close()
    }
    interface GraphConnection { fun prepare(sql: String): GraphStatement }
    class GraphQuery { fun invoke(connection: Any?): Any? = connection }
    enum class GraphType { Friends }
    object GraphConverter { @JvmStatic fun encode(type: GraphType): String = type.name }

    private fun field(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }
    private fun reader() = CorePostLocationSymbols(Post::class.java,
        field(Post::class.java, "owner"), field(User::class.java, "uid"), field(Post::class.java, "point"),
        field(Coordinate::class.java, "latitude"), field(Coordinate::class.java, "longitude"), field(Post::class.java, "id"))

    /** Other nullable signatures stay absent; only the location reader needs a host fixture. */
    private fun fixture(location: CorePostLocationSymbols?, placeholder: MapPlaceholderLocationSymbols? = null,
        graph: FriendGraphSymbols? = null): ResolvedSymbols {
        fun construct(type: Class<*>, special: Any?): Any {
            val constructor = type.declaredConstructors.single { !it.isSynthetic }.apply { isAccessible = true }
            return constructor.newInstance(*constructor.parameterTypes.map { parameter ->
                when {
                    parameter == FeedMediaSymbols::class.java -> special
                    parameter == CorePostLocationSymbols::class.java -> location
                    parameter == MapPlaceholderLocationSymbols::class.java -> placeholder
                    parameter == FriendGraphSymbols::class.java -> graph
                    parameter == String::class.java -> "fixture"
                    parameter == Boolean::class.javaPrimitiveType -> true
                    parameter == Map::class.java -> emptyMap<String, Any>()
                    parameter == List::class.java -> emptyList<Any>()
                    else -> null
                }
            }.toTypedArray())
        }
        return construct(ResolvedSymbols::class.java, construct(FeedMediaSymbols::class.java, location)) as ResolvedSymbols
    }

    @Test fun persistedReaderRestoresFieldsAndCapturesWithoutANewScan() {
        val encoded = SymbolCacheCodec.encode(fixture(reader()))
        assertFalse(encoded.toString().contains("35.68"))
        val restored = SymbolCacheCodec.decode(encoded, javaClass.classLoader!!, "new-identity")
        assertEquals("new-identity", restored.sourceIdentity)
        val location = restored.feedMediaSymbols!!.corePostLocationSymbols!!
            .snapshot(Post(User("viewer"), Coordinate(35.68, 139.76)), "viewer")
        assertEquals(PostLocation(35.68, 139.76, false), location)
        assertEquals("stored-id" to "viewer", restored.feedMediaSymbols!!.corePostLocationSymbols!!
            .identity(Post(User("viewer"), Coordinate(35.68, 139.76))))
    }

    @Test fun unsupportedLocationReaderDoesNotInvalidateOtherCachedFeedSymbols() {
        val restored = SymbolCacheCodec.decode(SymbolCacheCodec.encode(fixture(null)), javaClass.classLoader!!, "identity")
        assertNotNull(restored.feedMediaSymbols)
        assertNull(restored.feedMediaSymbols!!.corePostLocationSymbols)
    }

    @Test fun mapPlaceholderConstructorsAndFriendGateSurviveCacheRoundTrip() {
        val placeholder = MapPlaceholderLocationSymbols(Placeholder::class.java.declaredConstructors.toList(),
            field(Placeholder::class.java, "postId"), field(Placeholder::class.java, "isFriend"),
            field(Placeholder::class.java, "latitude"), field(Placeholder::class.java, "longitude"))
        val restored = SymbolCacheCodec.decode(SymbolCacheCodec.encode(fixture(null, placeholder)), javaClass.classLoader!!, "identity")
            .feedMediaSymbols!!.mapPlaceholderLocationSymbols!!
        assertEquals(placeholder.constructors, restored.constructors)
        assertEquals("post" to PostLocation(35.0, 139.0, false), restored.snapshot(Placeholder("post", true, 35.0, 139.0)) { true })
        assertNull(restored.snapshot(Placeholder("post", false, 35.0, 139.0)) { true })
    }

    @Test fun oldFeedRecordIsRejectedSoItCanBeRescannedOnce() {
        val encoded = SymbolCacheCodec.encode(fixture(null))
        encoded.getJSONObject("values").getJSONObject("feedMediaSymbols").getJSONObject("values")
            .remove("corePostLocationSymbols")
        assertThrows(IllegalArgumentException::class.java) {
            SymbolCacheCodec.decode(encoded, javaClass.classLoader!!, "identity")
        }
    }

    @Test fun friendGraphSignaturesRestoreAUsableObserverWithoutStoringAccountData() {
        val query = GraphQuery::class.java.getDeclaredMethod("invoke", Any::class.java)
        val graph = FriendGraphSymbols(query, GraphConnection::class.java, GraphStatement::class.java,
            GraphConverter::class.java.getDeclaredMethod("encode", GraphType::class.java), GraphType.Friends)
        val encoded = SymbolCacheCodec.encode(fixture(null, graph = graph))
        assertFalse(encoded.toString().contains("private-viewer"))
        val restored = SymbolCacheCodec.decode(encoded, javaClass.classLoader!!, "identity")
            .feedMediaSymbols!!.friendGraphSymbols!!
        assertEquals(query, restored.query)
        val observer = restored.observe(object : GraphConnection {
            override fun prepare(sql: String): GraphStatement = object : GraphStatement {
                override fun bindText(index: Int, value: String) { }
                override fun step() = false
                override fun getText(index: Int) = error("No rows")
                override fun close() { }
            }
        }, "private-viewer")!!
        val statement = (observer.connection as GraphConnection).prepare(FriendGraphSymbols.FRIEND_QUERY)
        (1..3).forEach { statement.bindText(it, "private-viewer") }
        statement.bindText(4, "Friends")
        assertFalse(statement.step())
        statement.close()
        assertEquals(emptySet<String>(), observer.friendUids(emptyList<String>()))
    }
}
