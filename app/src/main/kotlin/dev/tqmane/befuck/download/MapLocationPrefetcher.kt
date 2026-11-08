package dev.tqmane.befuck.download

import android.util.Log
import dev.tqmane.befuck.archive.ArchiveAccounts
import dev.tqmane.befuck.posting.GalleryPostController
import dev.tqmane.befuck.runtime.RuntimeKnowledge
import dev.tqmane.befuck.symbols.CorePostLocationSymbols
import dev.tqmane.befuck.symbols.MapPlaceholderLocationSymbols
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
    private val loggedReady = AtomicBoolean(false)
    private val loggedFailure = AtomicBoolean(false)

    @JvmStatic
    fun configure(value: MapPlaceholderLocationSymbols?, core: CorePostLocationSymbols?) {
        symbols = value?.takeIf { core != null && it.fetchMethod != null }
    }

    /** Returns false when nothing was queued, so the caller may retry later. */
    internal fun request(postId: String, scope: ArchiveAccounts.Scope): Boolean {
        // A deferred request must not consume the cache's one attempt per post.
        if (symbols == null || scope.uid.isNullOrBlank()) return false
        return try {
            executor.execute { run(postId, scope) }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun run(postId: String, scope: ArchiveAccounts.Scope) {
        var completed = false
        try {
            if (ArchiveAccounts.snapshot() != scope) return
            val symbols = symbols ?: return
            val type = symbols.fetchMethod!!.declaringClass
            val target = GalleryPostController.resolveKoinInstance(type, requireNotNull(type.classLoader), RuntimeKnowledge.versionName)
            val result = invoke(symbols, target, postId)
            if (ArchiveAccounts.snapshot() != scope) return
            check(result != null && symbols.isMapPlaceholder(result)) { "Host map state unavailable" }
            FeedPostMediaCache.captureMapPlaceholder(result, symbols)
            completed = true
            if (loggedReady.compareAndSet(false, true)) Log.i("BeFuck/Prefetch", "Background location prefetch is running")
            Thread.sleep(SPACING_MILLIS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            if (loggedFailure.compareAndSet(false, true))
                Log.w("BeFuck/Prefetch", "Location prefetch failed: ${(failure.cause ?: failure).javaClass.simpleName}")
        } finally {
            if (!completed) FeedPostMediaCache.locationPrefetchFailed(postId, scope)
        }
    }

    private fun invoke(symbols: MapPlaceholderLocationSymbols, target: Any, postId: String): Any? {
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
                } ?: return null
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
            val bridgeClass = symbols.coroutineBridgeClass ?: return null
            val constructor = bridgeClass.declaredConstructors.firstOrNull { it.parameterCount in 3..4 }
                ?.apply { isAccessible = true } ?: return null
            val bridgeInstance = if (constructor.parameterCount == 4) {
                constructor.newInstance(callback, emptyContext, null, callback)
            } else {
                constructor.newInstance(callback, emptyContext, null)
            }
            val intField = bridgeClass.declaredFields.firstOrNull {
                !Modifier.isStatic(it.modifiers) && it.type == Integer.TYPE
            }?.apply { isAccessible = true } ?: return null
            intField.setInt(bridgeInstance, 1)
            bridgeInstance
        }
        val result = try {
            method.invoke(target, postId, continuationArg)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
        return if (isCoroutineSuspended(result)) {
            if (done.await(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) resumed.get() else null
        } else {
            result
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
