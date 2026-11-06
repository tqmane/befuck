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
 * Opt-in feed URL/metadata synchronization in the target's WorkManager process.
 *
 * The separate LSPosed APK cannot register a Worker in BeReal's manifest. BeReal
 * 3.98.0 also does not expose unique periodic WorkManager enqueue after R8.
 * Instead we schedule a uniquely named *one-time* DiagnosticsWorker and hook
 * only jobs carrying our private input marker. Each run schedules its successor
 * for 24 hours later, with alternating unique names to avoid KEEP of RUNNING work.
 * No token, Authorization header, or downloaded media is persisted by this class.
 */
internal object DailySync {
    private const val TAG = "BeFuck/DailySync"
    private const val PREFERENCES = "befuck_daily_sync"
    private const val ENABLED = "enabled"
    private const val PENDING_SLOT = "pending_slot"
    private const val LAST_SUCCESS = "last_success"
    private const val INPUT_KEY = "dev.tqmane.befuck.daily_sync"
    private const val INPUT_SLOT = "dev.tqmane.befuck.daily_sync.slot"
    private const val INPUT_VALUE = "v1"
    private const val WORKER = "androidx.work.impl.workers.DiagnosticsWorker"
    private const val DELAY_HOURS = 24L

    private val scheduler = Executors.newSingleThreadExecutor { job ->
        Thread(job, "BeFuckDailySyncScheduler")
    }
    private val reconciling = AtomicBoolean(false)

    @JvmStatic
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    @JvmStatic
    fun lastSuccess(context: Context): Long =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getLong(LAST_SUCCESS, 0L)

    /** Never schedule or download anything without an explicit opt-in. */
    @JvmStatic
    fun configure(context: Context, value: Boolean): Boolean {
        val app = context.applicationContext ?: context
        val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (!value) {
            preferences.edit().putBoolean(ENABLED, false).remove(PENDING_SLOT).commit()
            runCatching { cancel(app) }
                .onFailure { Log.w(TAG, "Background work cancellation incomplete (" + it.javaClass.simpleName + ")") }
            return true
        }
        preferences.edit().putBoolean(ENABLED, true).commit()
        return try {
            // The first run is due immediately; subsequent runs are delayed by 24 hours.
            val slot = preferences.getString(PENDING_SLOT, null) ?: "a"
            enqueue(app, slot, 0L)
            true
        } catch (failure: Throwable) {
            preferences.edit().putBoolean(ENABLED, false).remove(PENDING_SLOT).commit()
            Log.w(TAG, "Could not schedule daily feed sync (" + failure.javaClass.simpleName + ")")
            false
        }
    }

    /** Reconcile a pending one-time job after app/module updates without changing its due time. */
    @JvmStatic
    fun reconcile(context: Context) {
        val app = context.applicationContext ?: context
        if (!enabled(app) || !reconciling.compareAndSet(false, true)) return
        scheduler.execute {
            try {
                val slot = preferences(app).getString(PENDING_SLOT, null) ?: "a"
                enqueue(app, slot, DELAY_HOURS)
            } catch (failure: Throwable) {
                Log.w(TAG, "Background sync schedule reconciliation failed (" + failure.javaClass.simpleName + ")")
            } finally {
                reconciling.set(false)
            }
        }
    }

    /** Check all input markers and never replace an unmarked host diagnostics run. */
    @JvmStatic
    fun isMarkedWork(worker: Any): Boolean =
        worker.javaClass.name == WORKER && inputDataString(worker, INPUT_KEY) == INPUT_VALUE

    /** Return the host's own WorkManager Result; never pass an Xposed module type. */
    @JvmStatic
    fun workResult(loader: ClassLoader): Any {
        val resultType = Class.forName("androidx.work.ListenableWorker\$Result", false, loader)
        val successType = Class.forName("androidx.work.ListenableWorker\$Result\$Success", false, loader)
        val factory = allMethods(resultType).singleOrNull {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                successType.isAssignableFrom(it.returnType)
        } ?: error("WorkManager Result.Success factory is missing")
        factory.isAccessible = true
        return requireNotNull(factory.invoke(null))
    }

    /** Executes on WorkManager's background worker thread, without a visible Activity. */
    @JvmStatic
    fun run(context: Context, symbols: ResolvedSymbols?, worker: Any): Boolean {
        val app = context.applicationContext ?: context
        if (!enabled(app) || !isMarkedWork(worker)) return false
        try {
            val currentSlot = inputDataString(worker, INPUT_SLOT) ?: return failure("slot_unavailable")
            if (currentSlot != "a" && currentSlot != "b") return failure("invalid_slot")
            // Register before completing this job: its own name is still RUNNING.
            // A and B alternate so KEEP never suppresses the 24-hour successor.
            enqueue(app, if (currentSlot == "a") "b" else "a", DELAY_HOURS)
        } catch (failure: Throwable) {
            Log.w(TAG, "Could not register the next daily sync (" + failure.javaClass.simpleName + ")")
            return false
        }
        if (symbols == null) return failure("host_symbols_unavailable")
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

            // Host deserializer hooks enqueue SQLite writes asynchronously.
            val storageLatch = CountDownLatch(1)
            ArchiveRepository.executor.execute { storageLatch.countDown() }
            if (!storageLatch.await(30, TimeUnit.SECONDS)) return failure("archive_timeout")
            if (ArchiveRepository.lastError != null || scope != ArchiveAccounts.snapshot()) {
                return failure("archive_unavailable")
            }
            preferences(app).edit().putLong(LAST_SUCCESS, System.currentTimeMillis()).apply()
            Log.i(TAG, "Daily host feed refresh completed; observed post URLs were archived")
            return true
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return failure("interrupted")
        } catch (failure: Throwable) {
            Log.w(TAG, "Daily feed sync failed (" + failure.javaClass.simpleName + ")")
            return false
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun failure(reason: String): Boolean {
        Log.w(TAG, "Daily feed refresh incomplete: " + reason)
        return false
    }

    private fun inputDataString(worker: Any, key: String): String? = runCatching {
        val data = allMethods(worker.javaClass).firstOrNull {
            it.name == "getInputData" && it.parameterCount == 0
        }?.apply { isAccessible = true }?.invoke(worker) ?: return@runCatching null
        val getter = allMethods(data.javaClass).singleOrNull {
            it.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                it.returnType == String::class.java
        } ?: return@runCatching null
        getter.isAccessible = true
        getter.invoke(data, key) as? String
    }.getOrNull()

    /** Match the minified AndroidX methods by signatures, not unobfuscated names. */
    private fun enqueue(context: Context, slot: String, delayHours: Long) {
        require(slot == "a" || slot == "b")
        val loader = context.classLoader
        val worker = Class.forName(WORKER, false, loader)
        val dataType = Class.forName("androidx.work.Data", false, loader)
        val dataBuilderType = Class.forName("androidx.work.Data\$Builder", false, loader)
        val dataBuilder = dataBuilderType.getDeclaredConstructor().newInstance()
        val writer = allMethods(dataBuilderType).singleOrNull {
            it.parameterCount == 2 && it.parameterTypes[0] == String::class.java &&
                (it.parameterTypes[1] == Any::class.java || it.parameterTypes[1] == String::class.java) &&
                (it.returnType == Void.TYPE || dataBuilderType.isAssignableFrom(it.returnType))
        } ?: error("Host WorkManager Data writer unavailable")
        writer.isAccessible = true
        writer.invoke(dataBuilder, INPUT_KEY, INPUT_VALUE)
        writer.invoke(dataBuilder, INPUT_SLOT, slot)
        val data = allMethods(dataBuilderType).singleOrNull {
            it.parameterCount == 0 && it.returnType == dataType
        }?.apply { isAccessible = true }?.invoke(dataBuilder) ?: error("Host WorkManager Data build unavailable")

        val requestBuilderType = Class.forName("androidx.work.OneTimeWorkRequest\$Builder", false, loader)
        val requestType = Class.forName("androidx.work.OneTimeWorkRequest", false, loader)
        val baseBuilderType = Class.forName("androidx.work.WorkRequest\$Builder", false, loader)
        val builder = requestBuilderType.getDeclaredConstructor(Class::class.java).newInstance(worker)
        val setInput = allMethods(requestBuilderType).singleOrNull {
            it.parameterTypes.contentEquals(arrayOf(dataType)) &&
                baseBuilderType.isAssignableFrom(it.returnType)
        } ?: error("Host WorkManager WorkRequest input setter unavailable")
        setInput.isAccessible = true
        setInput.invoke(builder, data)

        val setDelay = allMethods(requestBuilderType).singleOrNull {
            it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType, TimeUnit::class.java)) &&
                baseBuilderType.isAssignableFrom(it.returnType)
        } ?: error("Host WorkManager initial delay setter unavailable")
        setDelay.isAccessible = true
        setDelay.invoke(builder, delayHours, TimeUnit.HOURS)

        val constraintsBuilderType = Class.forName("androidx.work.Constraints\$Builder", false, loader)
        val networkType = Class.forName("androidx.work.NetworkType", false, loader)
        val constraintsType = Class.forName("androidx.work.Constraints", false, loader)
        val connected = networkType.enumConstants?.firstOrNull { (it as Enum<*>).name == "CONNECTED" }
            ?: error("CONNECTED network requirement unavailable")
        val constraintsBuilder = constraintsBuilderType.getDeclaredConstructor().newInstance()
        val setNetwork = allMethods(constraintsBuilderType).singleOrNull {
            it.parameterTypes.contentEquals(arrayOf(networkType))
        } ?: error("Host WorkManager network constraint unavailable")
        setNetwork.isAccessible = true
        setNetwork.invoke(constraintsBuilder, connected)
        val constraints = allMethods(constraintsBuilderType).singleOrNull {
            it.parameterCount == 0 && it.returnType == constraintsType
        }?.apply { isAccessible = true }?.invoke(constraintsBuilder)
            ?: error("Host WorkManager constraints builder unavailable")
        val specType = Class.forName("androidx.work.impl.model.WorkSpec", false, loader)
        val spec = allFields(requestBuilderType).singleOrNull { it.type == specType }
            ?.apply { isAccessible = true }?.get(builder)
            ?: error("Host WorkSpec unavailable")
        val constraintField = allFields(specType).singleOrNull { it.type == constraintsType }
            ?: error("Host WorkSpec constraints field unavailable")
        constraintField.isAccessible = true
        constraintField.set(spec, constraints)

        val request = allMethods(requestBuilderType).firstOrNull {
            it.parameterCount == 0 && requestType.isAssignableFrom(it.returnType)
        }?.apply { isAccessible = true }?.invoke(builder)
            ?: allMethods(requestBuilderType).singleOrNull {
                it.parameterCount == 0 && it.returnType.name == "androidx.work.WorkRequest" &&
                    Modifier.isFinal(it.modifiers)
            }?.apply { isAccessible = true }?.invoke(builder)
            ?: error("Host WorkRequest build unavailable")
        require(requestType.isInstance(request)) { "Host WorkManager did not build a OneTimeWorkRequest" }
        val manager = manager(context, loader)
        val managerType = Class.forName("androidx.work.WorkManager", false, loader)
        val policyType = Class.forName("androidx.work.ExistingWorkPolicy", false, loader)
        val keep = policyType.enumConstants?.firstOrNull { (it as Enum<*>).name == "KEEP" }
            ?: error("Host ExistingWorkPolicy.KEEP unavailable")
        val enqueue = allMethods(managerType).singleOrNull {
            it.parameterCount == 3 && it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == policyType && it.parameterTypes[2].isInstance(request)
        } ?: error("Host unique one-time enqueue method unavailable")
        enqueue.isAccessible = true
        enqueue.invoke(manager, workName(slot), keep, request)
        // The WorkManager operation has been accepted asynchronously, and the
        // pending slot can safely be retained for reconciliation after process death.
        preferences(context).edit().putString(PENDING_SLOT, slot).apply()
    }

    private fun workName(slot: String) = "befuck.daily_feed_sync.$slot.v1"

    private fun cancel(context: Context) {
        val loader = context.classLoader
        val manager = manager(context, loader)
        val managerType = Class.forName("androidx.work.WorkManager", false, loader)
        val operationType = Class.forName("androidx.work.Operation", false, loader)
        val actions = allMethods(managerType).filter {
            it.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                operationType.isAssignableFrom(it.returnType)
        }
        // Minified WorkManager retains both cancelUniqueWork and cancelAllWorkByTag.
        // Calling both with private, unique BeFuck names is safe, independent of R8 names.
        for (slot in listOf("a", "b")) for (action in actions) {
            action.isAccessible = true
            action.invoke(manager, workName(slot))
        }
    }

    private fun manager(context: Context, loader: ClassLoader): Any {
        val managerType = Class.forName("androidx.work.WorkManager", false, loader)
        val implType = Class.forName("androidx.work.impl.WorkManagerImpl", false, loader)
        val accessor = allMethods(implType).singleOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&
                managerType.isAssignableFrom(it.returnType)
        } ?: error("Host WorkManager instance accessor unavailable")
        accessor.isAccessible = true
        return requireNotNull(accessor.invoke(null, context))
    }

    private fun allMethods(type: Class<*>): List<Method> =
        generateSequence(type) { it.superclass }.flatMap { it.declaredMethods.asSequence() }.toList()

    private fun allFields(type: Class<*>): List<java.lang.reflect.Field> =
        generateSequence(type) { it.superclass }.flatMap { it.declaredFields.asSequence() }.toList()
}
