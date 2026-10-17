package dev.tqmane.befuck.symbols

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Locale

/** Observe the host's complete Friends query, without starting a query or changing its results. */
class FriendGraphSymbols internal constructor(
    val query: Method,
    private val connectionType: Class<*>,
    private val statementType: Class<*>,
    private val friendsConverter: Method,
    private val friendsType: Enum<*>,
) {
    private val prepare = connectionType.getMethod("prepare", String::class.java)
    private val bindText = statementType.getMethod("bindText", Integer.TYPE, String::class.java)
    private val step = statementType.getMethod("step")
    private val getText = statementType.getMethod("getText", Integer.TYPE)
    private val close = statementType.getMethod("close")
    private val friendsToken: String

    init {
        require(connectionType.isInterface && statementType.isInterface)
        require(query.name == "invoke" && query.parameterTypes.contentEquals(arrayOf(Any::class.java)) &&
            query.returnType == Any::class.java && !Modifier.isStatic(query.modifiers))
        require(prepare.returnType == statementType && bindText.returnType == Void.TYPE &&
            step.returnType == java.lang.Boolean.TYPE && getText.returnType == String::class.java && close.returnType == Void.TYPE)
        require(friendsType.name == "Friends" && Modifier.isStatic(friendsConverter.modifiers) &&
            friendsConverter.returnType == String::class.java &&
            friendsConverter.parameterTypes.contentEquals(arrayOf(friendsType.declaringJavaClass)))
        friendsConverter.isAccessible = true
        friendsToken = (friendsConverter.invoke(null, friendsType) as String).also { require(it.isNotBlank() && it.length <= 128) }
        query.isAccessible = true
    }

    /** A new observer belongs to exactly one synchronous native query invocation. */
    fun observe(connection: Any, viewerUid: String): Observation? {
        if (!connectionType.isInstance(connection) || !validUid(viewerUid)) return null
        return runCatching { Observation(connection, viewerUid) }.getOrNull()
    }

    inner class Observation internal constructor(original: Any, private val viewerUid: String) {
        private var invalid = false
        private var prepared = 0
        private var matched = 0
        private var bindings = arrayOfNulls<String>(4)
        private var started = false
        private var finished = false
        private var closed = false
        private var rowOpen = false
        private var rowRead = false
        private val rows = ArrayList<String>()

        val connection: Any = Proxy.newProxyInstance(connectionType.classLoader, arrayOf(connectionType)) { _, method, args ->
            val result = forward(method, original, args)
            if (method != prepare) return@newProxyInstance result
            prepared++
            if (prepared != 1 || normalizedSql(args?.getOrNull(0) as? String) != normalizedQuery) {
                invalid = true
                return@newProxyInstance result
            }
            matched++
            if (result == null || !statementType.isInstance(result)) {
                invalid = true
                return@newProxyInstance result
            }
            // Proxy creation must never prevent the host from receiving its own statement.
            runCatching {
                Proxy.newProxyInstance(statementType.classLoader, arrayOf(statementType)) { _, operation, arguments ->
                    val returned = forward(operation, result, arguments)
                    runCatching { record(operation, arguments, returned) }.onFailure { invalid = true }
                    returned
                }
            }.getOrElse { invalid = true; result }
        }

        /** Null means unproven; an empty set is a successfully observed empty Friends graph. */
        fun friendUids(result: Any?): Set<String>? {
            if (invalid || prepared != 1 || matched != 1 || !finished || !closed || !correctBindings()) return null
            val values = result as? List<*> ?: return null
            if (values.size > MAX_FRIENDS || values.size != rows.size || values != rows) return null
            if (values.any { it !is String || !validUid(it) }) return null
            return rows.toSet()
        }

        private fun correctBindings() = bindings[0] == viewerUid && bindings[1] == viewerUid &&
            bindings[2] == viewerUid && bindings[3] == friendsToken

        private fun record(method: Method, args: Array<out Any?>?, result: Any?) {
            if (invalid) return
            when (method) {
                bindText -> {
                    val index = args?.getOrNull(0) as? Int
                    val value = args?.getOrNull(1) as? String
                    if (started || closed || index == null || index !in 1..4 || value == null || bindings[index - 1] != null) invalid = true
                    else bindings[index - 1] = value
                }
                step -> {
                    if (closed || finished || !correctBindings() || (rowOpen && !rowRead)) invalid = true
                    started = true
                    rowOpen = result == true
                    rowRead = false
                    if (rowOpen && rows.size >= MAX_FRIENDS) invalid = true
                    if (!rowOpen) finished = true
                }
                getText -> {
                    if (!rowOpen || rowRead || args?.getOrNull(0) != 0 || result !is String || !validUid(result)) invalid = true
                    else { rows.add(result); rowRead = true }
                }
                close -> { if (!finished || closed) invalid = true; closed = true }
                else -> if (method.declaringClass != Any::class.java) invalid = true
            }
        }

        private fun forward(method: Method, receiver: Any, args: Array<out Any?>?): Any? = try {
            method.invoke(receiver, *(args ?: emptyArray()))
        } catch (error: InvocationTargetException) {
            invalid = true
            throw error.targetException
        } catch (error: Throwable) {
            invalid = true
            throw error
        }
    }

    companion object {
        private const val MAX_FRIENDS = 10_000
        internal const val FRIEND_QUERY = "SELECT DISTINCT CASE WHEN sourceUserId = ? THEN targetUserId ELSE sourceUserId END " +
            "FROM RelationshipEntity WHERE (sourceUserId = ? OR targetUserId = ?) AND graphType = ?"
        private val whitespace = Regex("\\s+")
        private val normalizedQuery = normalizedSql(FRIEND_QUERY)
        private fun normalizedSql(sql: String?): String? = sql?.takeIf { it.length <= 4_096 }
            ?.trim()?.removeSuffix(";")?.trim()?.replace(whitespace, " ")?.lowercase(Locale.ROOT)
        private fun validUid(value: String) = value.isNotBlank() && value.length <= 512

        @JvmStatic
        fun resolve(bridge: DexKitBridge, loader: ClassLoader): FriendGraphSymbols? = runCatching {
            val connection = Class.forName("androidx.sqlite.SQLiteConnection", false, loader)
            val statement = Class.forName("androidx.sqlite.SQLiteStatement", false, loader)
            val candidates = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .name("invoke").paramTypes(Any::class.java.name).returnType(Any::class.java.name)
                .usingStrings("SELECT DISTINCT CASE WHEN sourceUserId = ? THEN targetUserId ELSE sourceUserId END")))
                .filter { method -> method.usingStrings.any { normalizedSql(it) == normalizedQuery } }
            val query = candidates.single()
            // R8 can merge several DAO queries into this lambda. Its selector is
            // deliberately irrelevant: each invocation must prove its real SQL and binds.
            val converters = query.invokes.filter { call ->
                Modifier.isStatic(call.modifiers) && call.returnTypeName == String::class.java.name && call.paramCount == 1 &&
                    call.usingStrings.containsAll(listOf("Friends", "FriendRequests", "Follow", "Block"))
            }.mapNotNull { call -> runCatching {
                val converter = call.getMethodInstance(loader)
                val graph = converter.parameterTypes.single().enumConstants?.filterIsInstance<Enum<*>>()
                    ?.singleOrNull { it.name == "Friends" } ?: return@runCatching null
                converter to graph
            }.getOrNull() }.distinctBy { it.first }
            val (converter, graph) = converters.single()
            FriendGraphSymbols(query.getMethodInstance(loader), connection, statement, converter, graph)
        }.getOrNull()
    }
}
