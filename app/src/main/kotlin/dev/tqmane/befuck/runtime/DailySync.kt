package dev.tqmane.befuck.runtime

import android.content.Context
import android.util.Log
import dev.tqmane.befuck.archive.ArchiveAccounts
import dev.tqmane.befuck.archive.ArchiveRepository
import dev.tqmane.befuck.download.FeedPostMediaCache
import dev.tqmane.befuck.posting.GalleryPostController
import dev.tqmane.befuck.symbols.ResolvedSymbols
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
 * for three hours later, with alternating unique names to avoid KEEP of RUNNING work.
 * No token, Authorization header, or downloaded media is persisted by this class.
 */
internal object DailySync {
    private const val TAG = "BeFuck/DailySync"
    private const val PREFERENCES = "befuck_daily_sync"
    private const val ENABLED = "enabled"
    private const val PENDING_SLOT = "pending_slot"
    private const val LAST_SUCCESS = "last_success"
    private const val INTERVAL_HOURS = "interval_hours"
    private const val INPUT_KEY = "dev.tqmane.befuck.daily_sync"
    private const val INPUT_SLOT = "dev.tqmane.befuck.daily_sync.slot"
    private const val INPUT_VALUE = "v1"
    private const val WORKER = "androidx.work.impl.workers.DiagnosticsWorker"
    internal val INTERVALS = listOf(1L, 2L, 3L, 6L, 12L, 24L)

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

    fun intervalHours(context: Context): Long =
        preferences(context).getLong(INTERVAL_HOURS, 3L).takeIf { it in INTERVALS } ?: 3L

    @Synchronized
    fun setIntervalHours(context: Context, hours: Long): Boolean {
        require(hours in INTERVALS)
        val app = context.applicationContext ?: context
        val previous = intervalHours(app)
        if (hours == previous) return true
        preferences(app).edit().putLong(INTERVAL_HOURS, hours).commit()
        if (!enabled(app)) return true
        return try {
            cancel(app)
            enqueue(app, "a", hours)
            true
        } catch (failure: Throwable) {
            preferences(app).edit().putLong(INTERVAL_HOURS, previous).commit()
            runCatching { enqueue(app, "a", previous) }
            Log.w(TAG, "Could not update sync interval (" + failure.javaClass.simpleName + ")")
            false
        }
    }

    /** Never schedule or download anything without an explicit opt-in. */
    @JvmStatic
    @Synchronized
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
            // The first run is due immediately; subsequent runs are delayed by three hours.
            val slot = preferences.getString(PENDING_SLOT, null) ?: "a"
            enqueue(app, slot, 0L)
            true
        } catch (failure: Throwable) {
            preferences.edit().putBoolean(ENABLED, false).remove(PENDING_SLOT).commit()
            Log.w(TAG, "Could not schedule background feed sync (" + failure.javaClass.simpleName + ")")
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
                enqueue(app, slot, intervalHours(app))
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
    fun workResult(loader: ClassLoader): Any = HostWorkManager.workResultSuccess(loader)

    /** Executes on WorkManager's background worker thread, without a visible Activity. */
    @JvmStatic
    fun run(context: Context, symbols: ResolvedSymbols?, worker: Any): Boolean {
        val app = context.applicationContext ?: context
        if (!enabled(app) || !isMarkedWork(worker)) return false
        try {
            val currentSlot = inputDataString(worker, INPUT_SLOT) ?: return failure("slot_unavailable")
            if (currentSlot != "a" && currentSlot != "b") return failure("invalid_slot")
            // Register before completing this job: its own name is still RUNNING.
            // A and B alternate so KEEP never suppresses the three-hour successor.
            enqueue(app, if (currentSlot == "a") "b" else "a", intervalHours(app))
        } catch (failure: Throwable) {
            Log.w(TAG, "Could not register the next background sync (" + failure.javaClass.simpleName + ")")
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
            if (!enabled(app) || scope.uid.isNullOrEmpty() || scope != ArchiveAccounts.snapshot()) return failure("account_unavailable")
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
            var stored = false
            ArchiveRepository.executor.execute {
                try {
                    ArchiveRepository.requireStore()
                    stored = ArchiveRepository.lastError == null
                } catch (_: Exception) {
                    stored = false
                } finally {
                    storageLatch.countDown()
                }
            }
            if (!storageLatch.await(30, TimeUnit.SECONDS)) return failure("archive_timeout")
            if (!stored || !enabled(app) || scope != ArchiveAccounts.snapshot()) {
                return failure("archive_unavailable")
            }
            preferences(app).edit().putLong(LAST_SUCCESS, System.currentTimeMillis()).apply()
            Log.i(TAG, "Background host feed refresh completed; observed post URLs were archived")
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
        val data = HostReflection.allMethods(worker.javaClass).firstOrNull {
            it.name == "getInputData" && it.parameterCount == 0
        }?.apply { isAccessible = true }?.invoke(worker) ?: return@runCatching null
        val getter = HostReflection.allMethods(data.javaClass).singleOrNull {
            it.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                it.returnType == String::class.java
        } ?: return@runCatching null
        getter.isAccessible = true
        getter.invoke(data, key) as? String
    }.getOrNull()

    /** Match the minified AndroidX methods by signatures, not unobfuscated names. */
    @Synchronized
    private fun enqueue(context: Context, slot: String, delayHours: Long) {
        check(enabled(context)) { "Background sync is disabled" }
        require(slot == "a" || slot == "b")
        val loader = context.classLoader
        val worker = Class.forName(WORKER, false, loader)
        HostWorkManager.enqueueUniqueOneTimeWork(
            context = context,
            classLoader = loader,
            workerClass = worker,
            uniqueName = workName(slot),
            inputEntries = mapOf(INPUT_KEY to INPUT_VALUE, INPUT_SLOT to slot),
            initialDelayHours = delayHours,
        )
        // The WorkManager operation has been accepted asynchronously, and the
        // pending slot can safely be retained for reconciliation after process death.
        preferences(context).edit().putString(PENDING_SLOT, slot).apply()
    }

    private fun workName(slot: String) = "befuck.daily_feed_sync.$slot.v1"

    private fun cancel(context: Context) {
        HostWorkManager.cancelByNames(context, context.classLoader, listOf("a", "b").map(::workName))
    }
}
