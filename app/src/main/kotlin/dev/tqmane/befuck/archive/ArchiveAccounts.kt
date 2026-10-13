package dev.tqmane.befuck.archive

import android.app.Activity
import dev.tqmane.befuck.posting.GalleryPostController
import dev.tqmane.befuck.symbols.ResolvedSymbols
import java.util.concurrent.CopyOnWriteArrayList

/** Account attribution uses the host's CURRENT-USER provider, not constructors of arbitrary author models. */
internal object ArchiveAccounts {
    data class Scope(val uid: String?, val generation: Long)
    private val lock = Any()
    @Volatile private var current = Scope(null, 0)
    private var inFlight = false
    private var lastAttempt = 0L
    private val waiting = arrayListOf<(Scope) -> Unit>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun snapshot(): Scope = current
    fun requireScope(scope: Scope) { archiveCheck(scope.uid != null && current == scope, "account_changed") }
    fun subscribe(listener: () -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }
    fun invalidate() {
        val (invalidated, callbacks) = synchronized(lock) {
            current = Scope(null, current.generation + 1); inFlight = false; lastAttempt = 0
            current to waiting.toList().also { waiting.clear() }
        }
        listeners.forEach { runCatching(it) }
        callbacks.forEach { runCatching { it(invalidated) } }
    }
    internal fun accept(expectedGeneration: Long, uid: String?) {
        val normalized = uid?.takeIf { ArchiveRecord.validId(it) }
        val (changed, resolved, callbacks) = synchronized(lock) {
            if (current.generation != expectedGeneration) return
            inFlight = false
            val callbacks = waiting.toList(); waiting.clear()
            val changed = if (current.uid == normalized) false else {
                current = Scope(normalized, current.generation + 1); true
            }
            Triple(changed, current, callbacks)
        }
        if (changed) listeners.forEach { runCatching(it) }
        callbacks.forEach { runCatching { it(resolved) } }
    }
    fun refresh(activity: Activity, symbols: ResolvedSymbols, force: Boolean = false, completion: ((Scope) -> Unit)? = null) {
        resolve(force, android.os.SystemClock.elapsedRealtime(), { callback ->
            GalleryPostController.readCurrentAccount(activity, symbols, callback)
        }, completion)
    }
    /** Explicit archive opens join the current read instead of capturing its unresolved scope. */
    internal fun resolve(force: Boolean, now: Long, read: (java.util.function.Consumer<String?>) -> Unit, completion: ((Scope) -> Unit)? = null) {
        var immediate: Scope? = null
        val scope = synchronized(lock) {
            if (inFlight) { completion?.let { waiting += it }; return }
            if (!force && (current.uid != null || now - lastAttempt < 5000L)) {
                immediate = current; null
            } else {
                completion?.let { waiting += it }
                inFlight = true; lastAttempt = now; current
            }
        }
        immediate?.let { completion?.invoke(it) }
        if (scope != null) {
            try { read(java.util.function.Consumer { uid -> accept(scope.generation, uid) }) }
            catch (_: Exception) { accept(scope.generation, null) }
        }
    }
}
