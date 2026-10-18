package dev.tqmane.befuck.symbols

import org.junit.Assert.*
import org.junit.Test

class FriendGraphSymbolsTest {
    interface Connection { fun prepare(sql: String): Statement }
    interface Statement : AutoCloseable {
        fun bindText(index: Int, value: String)
        fun step(): Boolean
        fun getText(index: Int): String
        override fun close()
    }
    enum class Graph { Follow, Friends }
    class Query { fun invoke(connection: Any): Any = connection }
    class Tokens {
        companion object {
            @JvmStatic fun encode(graph: Graph): String = "db_${graph.name.lowercase()}"
        }
    }
    class Database(val values: List<String>) : Connection {
        val prepared = ArrayList<String>()
        val statements = ArrayList<Cursor>()
        var failure: RuntimeException? = null
        override fun prepare(sql: String): Statement {
            failure?.let { throw it }
            prepared.add(sql)
            return Cursor(values).also(statements::add)
        }
    }
    class Cursor(private val values: List<String>) : Statement {
        val bindings = LinkedHashMap<Int, String>()
        var closed = false
        private var position = -1
        override fun bindText(index: Int, value: String) { bindings[index] = value }
        override fun step(): Boolean { position++; return position < values.size }
        override fun getText(index: Int): String { require(index == 0); return values[position] }
        override fun close() { closed = true }
    }

    private fun symbols() = FriendGraphSymbols(
        Query::class.java.getMethod("invoke", Any::class.java), Connection::class.java, Statement::class.java,
        Tokens::class.java.getMethod("encode", Graph::class.java), Graph.Friends,
    )
    private fun execute(
        observer: FriendGraphSymbols.Observation,
        viewer: String = "viewer-a",
        graph: String = "db_friends",
        sql: String = FriendGraphSymbols.FRIEND_QUERY,
    ): List<String> {
        val statement = (observer.connection as Connection).prepare(sql)
        try {
            for (index in 1..3) statement.bindText(index, viewer)
            statement.bindText(4, graph)
            val result = ArrayList<String>()
            while (statement.step()) result.add(statement.getText(0))
            return result
        } finally {
            (statement as AutoCloseable).close() // The supplied DEX invokes the inherited interface.
        }
    }

    @Test fun exactQueryForwardsNativeCallsAndAuthorizesOnlyObservedRows() {
        val database = Database(listOf("friend-a", "friend-b"))
        val observer = symbols().observe(database, "viewer-a")!!
        assertTrue(database.prepared.isEmpty()) // Observation never issues a query.
        val sql = "\n${FriendGraphSymbols.FRIEND_QUERY.replace(" FROM ", "\nFROM ")} ;\n"
        val result = execute(observer, sql = sql)
        assertEquals(database.values, result)
        assertEquals(listOf(sql), database.prepared) // SQL is forwarded byte for byte.
        assertEquals(mapOf(1 to "viewer-a", 2 to "viewer-a", 3 to "viewer-a", 4 to "db_friends"), database.statements.single().bindings)
        assertTrue(database.statements.single().closed)
        assertEquals(setOf("friend-a", "friend-b"), observer.friendUids(result))
        assertNull(observer.friendUids(listOf("unrelated-id")))
    }

    @Test fun successfulEmptyQueryProducesARevocationSnapshot() {
        val observer = symbols().observe(Database(emptyList()), "viewer-a")!!
        assertEquals(emptySet<String>(), observer.friendUids(execute(observer)))
    }

    @Test fun otherFusedQueryBranchesAndGraphsCannotAuthorizeFriends() {
        val symbols = symbols()
        val otherBranch = symbols.observe(Database(listOf("followed-a")), "viewer-a")!!
        assertNull(otherBranch.friendUids(execute(otherBranch, sql = "SELECT targetUserId FROM RelationshipEntity")))
        val otherGraph = symbols.observe(Database(listOf("followed-a")), "viewer-a")!!
        assertNull(otherGraph.friendUids(execute(otherGraph, graph = "db_follow")))
    }

    @Test fun viewerBindingsMustMatchCapturedAccount() {
        val observer = symbols().observe(Database(listOf("other-accounts-friend")), "viewer-a")!!
        assertNull(observer.friendUids(execute(observer, viewer = "viewer-b")))
    }

    @Test fun multipleStatementsCannotBeConfusedWithOneCompleteSnapshot() {
        val observer = symbols().observe(Database(listOf("friend-a")), "viewer-a")!!
        execute(observer)
        val result = execute(observer)
        assertNull(observer.friendUids(result))
    }

    @Test fun partialCursorAndMissingBindingsFailClosed() {
        val observer = symbols().observe(Database(listOf("friend-a", "friend-b")), "viewer-a")!!
        val statement = (observer.connection as Connection).prepare(FriendGraphSymbols.FRIEND_QUERY)
        for (index in 1..3) statement.bindText(index, "viewer-a")
        statement.bindText(4, "db_friends")
        assertTrue(statement.step())
        val partial = listOf(statement.getText(0))
        statement.close()
        assertNull(observer.friendUids(partial))

        val unbound = symbols().observe(Database(emptyList()), "viewer-a")!!
        val cursor = (unbound.connection as Connection).prepare(FriendGraphSymbols.FRIEND_QUERY)
        assertFalse(cursor.step())
        cursor.close()
        assertNull(unbound.friendUids(emptyList<String>()))
    }

    @Test fun delegateFailureRemainsTheSameException() {
        val failure = IllegalStateException("synthetic driver failure")
        val database = Database(emptyList()).apply { this.failure = failure }
        val observer = symbols().observe(database, "viewer-a")!!
        val thrown = assertThrows(IllegalStateException::class.java) {
            (observer.connection as Connection).prepare(FriendGraphSymbols.FRIEND_QUERY)
        }
        assertSame(failure, thrown)
        assertNull(observer.friendUids(emptyList<String>()))
    }

    @Test fun oversizedOrInvalidResultsDoNotGrantLocations() {
        val symbols = symbols()
        val oversized = symbols.observe(Database((0..10_000).map { "friend-$it" }), "viewer-a")!!
        assertNull(oversized.friendUids(execute(oversized)))
        val invalid = symbols.observe(Database(listOf("")), "viewer-a")!!
        assertNull(invalid.friendUids(execute(invalid)))
        assertNull(symbols.observe(Database(emptyList()), ""))
        assertNull(symbols.observe(Any(), "viewer-a"))
    }

    @Test fun startupObservationWithoutViewerUidDerivesAndExposesBoundViewerUid() {
        val database = Database(listOf("startup-friend-1", "startup-friend-2"))
        val observer = symbols().observe(database)!!
        assertNull(observer.observedViewerUid)
        val result = execute(observer, viewer = "startup-viewer")
        assertEquals("startup-viewer", observer.observedViewerUid)
        assertEquals(setOf("startup-friend-1", "startup-friend-2"), observer.friendUids(result))
    }
}
