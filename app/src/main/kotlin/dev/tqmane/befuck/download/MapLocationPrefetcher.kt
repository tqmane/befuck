package dev.tqmane.befuck.download

import android.util.Log
import dev.tqmane.befuck.archive.ArchiveAccounts
import dev.tqmane.befuck.symbols.CorePostLocationSymbols
import dev.tqmane.befuck.symbols.MapPlaceholderLocationSymbols
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Invokes the host's own map use case (post ID -> CorePost -> MapPlaceholder) for authorized
 * feed posts whose feed DTO carried no location. The host constructs the MapPlaceholder with
 * its own friendship/approximation decision; the already installed constructor hook then
 * records only what the host would have displayed. Requests are serialized, rate-limited and
 * bounded, and the module never reads coordinates itself.
 */
object MapLocationPrefetcher {
    private const val MAX_QUEUE = 64
    private const val SPACING_MILLIS = 300L
    private const val RESULT_TIMEOUT_SECONDS = 10L

    private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(MAX_QUEUE)) { task ->
        Thread(task, "BeFuck-MapPrefetch").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    @Volatile private var symbols: MapPlaceholderLocationSymbols? = null
    @Volatile private var coreReader: CorePostLocationSymbols? = null
    @Volatile private var diScope: WeakReference<Any>? = null
    private var fetcher: Any? = null
    private val loggedReady = AtomicBoolean(false)
    private val loggedNoScope = AtomicBoolean(false)
    private val loggedFailure = AtomicBoolean(false)

    @JvmStatic
    fun configure(value: MapPlaceholderLocationSymbols?, core: CorePostLocationSymbols?) {
        coreReader = core
        symbols = value?.takeIf { core != null && it.fetchMethod != null && it.factoryMethod != null }
    }

    /** Only a DI scope object observed in the host's own factory call is ever retained (weakly). */
    @JvmStatic
    fun observeScope(scope: Any?) {
        if (scope != null && diScope?.get() !== scope) diScope = WeakReference(scope)
    }

    /** Returns false when nothing was queued, so the caller may retry later. */
    internal fun request(postId: String, scope: ArchiveAccounts.Scope): Boolean {
        if (symbols == null || scope.uid.isNullOrBlank()) return false
        return try {
            executor.execute { run(postId, scope) }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun run(postId: String, scope: ArchiveAccounts.Scope) {
        try {
            if (ArchiveAccounts.snapshot() != scope) return
            val symbols = symbols ?: return
            val target = obtainFetcher(symbols) ?: run {
                if (loggedNoScope.compareAndSet(false, true))
                    Log.i("BeFuck/Prefetch", "Host DI scope or use case not available yet; location prefetch deferred")
                return
            }
            invoke(symbols, target, postId)
            if (loggedReady.compareAndSet(false, true)) Log.i("BeFuck/Prefetch", "Background location prefetch is running")
            Thread.sleep(SPACING_MILLIS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            if (loggedFailure.compareAndSet(false, true))
                Log.w("BeFuck/Prefetch", "Location prefetch failed: ${(failure.cause ?: failure).javaClass.simpleName}")
        }
    }

    /** The host map use case (a DI singleton) that constructs the MapPlaceholder. */
    private fun obtainFetcher(symbols: MapPlaceholderLocationSymbols): Any? {
        fetcher?.let { return it }
        val di = diScope?.get() ?: return null
        val type = symbols.fetchMethod!!.declaringClass
        val getters = generateSequence<Class<*>>(di.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { m ->
                !Modifier.isStatic(m.modifiers) && m.returnType == Any::class.java &&
                    m.parameterCount == 3 &&
                    m.parameterTypes[0].declaredConstructors.any { ctor ->
                        ctor.parameterTypes.contentEquals(arrayOf(Class::class.java))
                    }
            }.onEach { it.isAccessible = true }.toList()
        return getters.firstNotNullOfOrNull { getter ->
            runCatching {
                val tokenConstructor = getter.parameterTypes[0].getDeclaredConstructor(Class::class.java)
                    .apply { isAccessible = true }
                val token = tokenConstructor.newInstance(type)
                getter.invoke(di, token, null, null)
            }.getOrNull()?.takeIf(type::isInstance)
        }?.also { fetcher = it }
    }

    private fun invoke(symbols: MapPlaceholderLocationSymbols, target: Any, postId: String) {
        val method = symbols.fetchMethod!!
        val continuationParamType = method.parameterTypes[1]
        val continuationInterface = if (continuationParamType.isInterface) {
            continuationParamType
        } else {
            generateSequence<Class<*>>(continuationParamType) { it.superclass }
                .flatMap { it.interfaces.asSequence() }
                .firstOrNull { iface ->
                    iface.declaredMethods.any { it.parameterCount == 0 && it.returnType.isInterface } &&
                        iface.declaredMethods.any { it.parameterCount == 1 && it.returnType == Void.TYPE }
                } ?: return
        }
        val contextGetter = continuationInterface.declaredMethods.single { it.parameterCount == 0 }
        val contextType = contextGetter.returnType
        val done = CountDownLatch(1)
        val resumed = java.util.concurrent.atomic.AtomicReference<Any?>()
        val emptyContext = proxy(contextType, object : InvocationHandler {
            override fun invoke(proxy: Any, m: Method, args: Array<out Any?>?): Any? = objectMethod(proxy, m, args) ?: when {
                m.parameterCount == 2 -> args!![0]                       // fold(initial, op)
                m.parameterCount == 1 && m.parameterTypes[0] == contextType -> args!![0] // plus(context)
                m.parameterCount == 1 && m.returnType == contextType -> proxy // minusKey(key)
                else -> primitiveDefault(m.returnType)                   // get(key) -> null
            }
        })
        val callback = proxy(continuationInterface, object : InvocationHandler {
            override fun invoke(proxy: Any, m: Method, args: Array<out Any?>?): Any? = objectMethod(proxy, m, args) ?: when {
                m == contextGetter -> emptyContext
                else -> { resumed.set(args?.getOrNull(0)); done.countDown(); null } // resumeWith(result)
            }
        })
        val continuationArg = if (continuationParamType.isInterface) {
            callback
        } else {
            val bridgeClass = symbols.coroutineBridgeClass ?: return
            val constructor = bridgeClass.declaredConstructors.firstOrNull { it.parameterCount in 3..4 }
                ?.apply { isAccessible = true } ?: return
            val bridgeInstance = if (constructor.parameterCount == 4) {
                constructor.newInstance(callback, emptyContext, null, callback)
            } else {
                constructor.newInstance(callback, emptyContext, null)
            }
            val intField = bridgeClass.declaredFields.firstOrNull {
                !Modifier.isStatic(it.modifiers) && it.type == Integer.TYPE
            }?.apply { isAccessible = true } ?: return
            intField.setInt(bridgeInstance, 1)
            bridgeInstance
        }
        val result = try {
            method.invoke(target, postId, continuationArg)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
        val finished = if (isCoroutineSuspended(result)) {
            if (done.await(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) resumed.get() else null
        } else {
            result
        }
        if (finished != null && symbols.isMapPlaceholder(finished)) {
            FeedPostMediaCache.captureMapPlaceholder(finished, symbols)
        }
    }

    private fun isCoroutineSuspended(value: Any?): Boolean =
        value != null && value.toString() == "COROUTINE_SUSPENDED"

    private fun proxy(type: Class<*>, handler: InvocationHandler): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler)

    private fun objectMethod(proxy: Any, m: Method, args: Array<out Any?>?): Any? = when {
        m.declaringClass != Any::class.java -> null
        m.name == "equals" -> proxy === args!![0]
        m.name == "hashCode" -> System.identityHashCode(proxy)
        else -> "BeFuck-prefetch-proxy"
    }

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> null
    }
}
