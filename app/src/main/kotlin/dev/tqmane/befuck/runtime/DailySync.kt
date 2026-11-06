package dev.tqmane.befuck.runtime

import android.content.Context
import android.util.Log
import dev.tqmane.befuck.archive.ArchiveAccounts
import dev.tqmane.befuck.archive.ArchiveRepository
import dev.tqmane.befuck.download.FeedPostMediaCache
import dev.tqmane.befuck.posting.GalleryPostController
import dev.tqmane.befuck.symbols.ResolvedSymbols
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * Periodic work belongs to the hooked BeReal process and its WorkManager database.
 * A dedicated manifest Worker is impossible for an LSPosed-only module because
 * BeReal's package manifest cannot register a service from the separate module APK.
 *
 * WorkManager's built-in DiagnosticsWorker is used only as a process entry point.
 * Our input marker separates BeFuck work from genuine diagnostics requests.
 * Never invoke the diagnostics implementation for marked work.
 */
internal object DailySync {
    private const val TAG = "BeFuck/DailySync"
    private const val PREFERENCES = "befuck_daily_sync"
    private const val ENABLED = "enabled"
    private const val LAST_SUCCESS = "last_success"
    private const val UNIQUE_WORK = "befuck.daily_feed_sync.v1"
    private const val INPUT_KEY = "dev.tqmane.befuck.daily_sync"
    private const val INPUT_VALUE = "v1"
    private const val WORKER = "androidx.work.impl.workers.DiagnosticsWorker"
    private const val INTERVAL_HOURS = 24L

    private val scheduler = Executors.newSingleThreadExecutor { job ->
        Thread(job, "BeFuckDailySyncScheduler")
    }
    private val scheduling = AtomicBoolean(false)

    @JvmStatic
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    @JvmStatic
    fun lastSuccess(context: Context): Long =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getLong(LAST_SUCCESS, 0L)

    /** Explicit user opt-in; do not schedule daily requests by default. */
    @JvmStatic
    fun configure(context: Context, value: Boolean): Boolean {
        val app = context.applicationContext ?: context
        val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return if (value) {
            try {
                enqueue(app)
                preferences.edit().putBoolean(ENABLED, true).commit()
            } catch (failure: Throwable) {
                Log.w(TAG, "Could not enable periodic sync (" + failure.javaClass.simpleName + ")")
                false
            }
        } else {
            // Disable the hook even if cancellation fails; never fetch while disabled.
            preferences.edit().putBoolean(ENABLED, false).commit()
            runCatching { cancel(app) }
                .onFailure { Log.w(TAG, "Could not cancel periodic work (" + it.javaClass.simpleName + ")") }
            true
        }
    }

    /** After an app update, KEEP reconciles the previous 24-hour schedule without resetting its interval. */
    @JvmStatic
    fun reconcile(context: Context) {
        val app = context.applicationContext ?: context
        if (!enabled(app) || !scheduling.compareAndSet(false, true)) return
        scheduler.execute {
            try { enqueue(app) }
            catch (failure: Throwable) {
                Log.w(TAG, "Periodic work reconciliation failed (" + failure.javaClass.simpleName + ")")
            } finally { scheduling.set(false) }
        }
    }

    @JvmStatic
    fun isMarkedWork(worker: Any): Boolean = runCatching {
        if (worker.javaClass.name != WORKER) return@runCatching false
        val data = allMethods(worker.javaClass).firstOrNull {
            it.name == "getInputData" && it.parameterCount == 0
        }?.apply { isAccessible = true }?.invoke(worker) ?: return@runCatching false
        val getString = allMethods(data.javaClass).firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == String::class.java &&
                it.returnType == String::class.java
        } ?: return@runCatching false
        getString.isAccessible = true
        getString.invoke(data, INPUT_KEY) == INPUT_VALUE
    }.getOrDefault(false)

    /** Executed on the host's WorkManager thread. No Activity or persistent auth token is required. */
    @JvmStatic
    fun run(context: Context, symbols: ResolvedSymbols?): Boolean {
        if (!enabled(context) || symbols == null) return false
        val app = context.applicationContext ?: context
        FeedPostMediaCache.initialize(app)
        val accountLatch = CountDownLatch(1)
        var account: ArchiveAccounts.Scope? = null
        try {
            ArchiveAccounts.refreshInBackground(app, symbols, Consumer { scope ->
                account = scope
                accountLatch.countDown()
            })
            if (!accountLatch.await(22, TimeUnit.SECONDS)) return failure("account_timeout")
            val scope = account ?: return failure("account_unavailable")
            if (scope.uid.isNullOrEmpty() || scope != ArchiveAccounts.snapshot()) return failure("account_unavailable")

            val feedLatch = CountDownLatch(1)
            var feedLoaded = false
            GalleryPostController.refreshHomeFeedInBackground(app, symbols.versionName, Consumer { loaded ->
                feedLoaded = loaded
                feedLatch.countDown()
            })
            if (!feedLatch.await(100, TimeUnit.SECONDS)) return failure("feed_timeout")
            if (!feedLoaded || scope != ArchiveAccounts.snapshot()) return failure("feed_unavailable")

            // Feed hooks enqueue SQLite writes asynchronously. Wait for all preceding captures.
            val storageLatch = CountDownLatch(1)
            ArchiveRepository.executor.execute { storageLatch.countDown() }
            if (!storageLatch.await(30, TimeUnit.SECONDS)) return failure("archive_timeout")
            if (ArchiveRepository.lastError != null || scope != ArchiveAccounts.snapshot()) {
                return failure("archive_unavailable")
            }
            app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putLong(LAST_SUCCESS, System.currentTimeMillis()).apply()
            Log.i(TAG, "Periodic host feed refresh and archive capture finished")
            return true
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return failure("interrupted")
        } catch (failure: Throwable) {
            Log.w(TAG, "Periodic feed refresh failed (" + failure.javaClass.simpleName + ")")
            return false
        }
    }

    private fun failure(reason: String): Boolean {
        Log.w(TAG, "Periodic feed refresh incomplete: " + reason)
        return false
    }

    /** Result factory comes from the target's WorkManager classloader, not the module classloader. */
    @JvmStatic
    fun workResult(loader: ClassLoader, success: Boolean): Any {
        val resultType = Class.forName("androidx.work.ListenableWorker\$Result", false, loader)
        val name = if (success) "success" else "retry"
        val factory = allMethods(resultType).firstOrNull {
            it.name == name && Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 && resultType.isAssignableFrom(it.returnType)
        } ?: error("WorkManager Result." + name + "() is unavailable")
        factory.isAccessible = true
        return requireNotNull(factory.invoke(null))
    }

    private fun enqueue(context: Context) {
        val loader = context.classLoader
        val worker = Class.forName(WORKER, false, loader)
        val dataType = Class.forName("androidx.work.Data", false, loader)
        val dataBuilderType = Class.forName("androidx.work.Data\$Builder", false, loader)
        val dataBuilder = dataBuilderType.getDeclaredConstructor().newInstance()
        val putString = allMethods(dataBuilderType).firstOrNull {
            it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java)) &&
                (it.returnType == Void.TYPE || dataBuilderType.isAssignableFrom(it.returnType))
        } ?: error("WorkManager Data string writer unavailable")
        putString.isAccessible = true
        putString.invoke(dataBuilder, INPUT_KEY, INPUT_VALUE)
        val buildData = allMethods(dataBuilderType).firstOrNull {
            it.parameterCount == 0 && it.returnType == dataType
        } ?: error("WorkManager Data builder unavailable")
        buildData.isAccessible = true
        val data = requireNotNull(buildData.invoke(dataBuilder))

        val builderType = Class.forName("androidx.work.PeriodicWorkRequest\$Builder", false, loader)
        val requestType = Class.forName("androidx.work.PeriodicWorkRequest", false, loader)
        val baseBuilderType = Class.forName("androidx.work.WorkRequest\$Builder", false, loader)
        val builder = builderType.declaredConstructors.firstOrNull {
            it.parameterTypes.contentEquals(arrayOf(Class::class.java, Long::class.javaPrimitiveType, TimeUnit::class.java))
        }?.apply { isAccessible = true }?.newInstance(worker, INTERVAL_HOURS, TimeUnit.HOURS)
            ?: error("PeriodicWorkRequest.Builder(Class,long,TimeUnit) unavailable")

        val inputSetter = allMethods(builderType).firstOrNull {
            it.parameterTypes.contentEquals(arrayOf(dataType)) &&
                baseBuilderType.isAssignableFrom(it.returnType)
        } ?: error("PeriodicWorkRequest input setter unavailable")
        inputSetter.isAccessible = true
        inputSetter.invoke(builder, data)

        // Network gating prevents a 24-hour run from consuming a retry while offline.
        val constraintsBuilderType = Class.forName("androidx.work.Constraints\$Builder", false, loader)
        val networkType = Class.forName("androidx.work.NetworkType", false, loader)
        val constraintsType = Class.forName("androidx.work.Constraints", false, loader)
        val connected = requireNotNull(networkType.enumConstants)
            .firstOrNull { (it as Enum<*>).name == "CONNECTED" }
            ?: error("WorkManager CONNECTED network type unavailable")
        val constraintsBuilder = constraintsBuilderType.getDeclaredConstructor().newInstance()
        val networkSetter = allMethods(constraintsBuilderType).firstOrNull {
            it.parameterTypes.contentEquals(arrayOf(networkType))
        } ?: error("WorkManager network constraint unavailable")
        networkSetter.isAccessible = true
        networkSetter.invoke(constraintsBuilder, connected)
        val constraints = allMethods(constraintsBuilderType).firstOrNull {
            it.parameterCount == 0 && it.returnType == constraintsType
        }?.apply { isAccessible = true }?.invoke(constraintsBuilder)
            ?: error("WorkManager constraints builder unavailable")
        val specType = Class.forName("androidx.work.impl.model.WorkSpec", false, loader)
        val spec = allFields(builderType).singleOrNull { it.type == specType }
            ?.apply { isAccessible = true }?.get(builder)
            ?: error("PeriodicWorkRequest WorkSpec unavailable")
        allFields(specType).singleOrNull { it.type == constraintsType }
            ?.apply { isAccessible = true }?.set(spec, constraints)
            ?: error("WorkSpec constraints setter unavailable")

        val request = allMethods(builderType).firstOrNull {
            it.name == "build" && it.parameterCount == 0 && requestType.isAssignableFrom(it.returnType)
        }?.apply { isAccessible = true }?.invoke(builder)
            ?: allMethods(builderType).firstOrNull {
                it.parameterCount == 0 && requestType.isAssignableFrom(it.returnType) &&
                    Modifier.isFinal(it.modifiers)
            }?.apply { isAccessible = true }?.invoke(builder)
            ?: error("PeriodicWorkRequest build unavailable")

        val manager = manager(context, loader)
        val managerType = Class.forName("androidx.work.WorkManager", false, loader)
        val policyType = Class.forName("androidx.work.ExistingPeriodicWorkPolicy", false, loader)
        val keep = requireNotNull(policyType.enumConstants).firstOrNull {
            (it as Enum<*>).name == "KEEP"
        } ?: error("ExistingPeriodicWorkPolicy.KEEP unavailable")
        val enqueue = allMethods(managerType).firstOrNull {
            it.parameterCount == 3 && it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == policyType && it.parameterTypes[2].isInstance(request)
        } ?: error("Unique periodic WorkManager enqueue unavailable")
        enqueue.isAccessible = true
        enqueue.invoke(manager, UNIQUE_WORK, keep, request)
    }

    private fun cancel(context: Context) {
        val loader = context.classLoader
        val manager = manager(context, loader)
        val managerType = Class.forName("androidx.work.WorkManager", false, loader)
        val cancel = allMethods(managerType).firstOrNull {
            it.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                it.name == "cancelUniqueWork"
        } ?: error("WorkManager.cancelUniqueWork unavailable")
        cancel.isAccessible = true
        cancel.invoke(manager, UNIQUE_WORK)
    }

    private fun manager(context: Context, loader: ClassLoader): Any {
        val managerClass = Class.forName("androidx.work.WorkManager", false, loader)
        val implClass = Class.forName("androidx.work.impl.WorkManagerImpl", false, loader)
        val factory = allMethods(implClass).firstOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&
                managerClass.isAssignableFrom(it.returnType)
        } ?: error("Host WorkManager instance factory unavailable")
        factory.isAccessible = true
        return requireNotNull(factory.invoke(null, context))
    }

    private fun allMethods(type: Class<*>): List<Method> =
        generateSequence(type) { it.superclass }.flatMap { it.declaredMethods.asSequence() }.toList()

    private fun allFields(type: Class<*>): List<java.lang.reflect.Field> =
        generateSequence(type) { it.superclass }.flatMap { it.declaredFields.asSequence() }.toList()
}
