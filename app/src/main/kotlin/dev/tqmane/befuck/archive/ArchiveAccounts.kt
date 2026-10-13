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
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun snapshot(): Scope = current
    fun requireScope(scope: Scope) { archiveCheck(scope.uid != null && current == scope, "account_changed") }
    fun subscribe(listener: () -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }
    fun invalidate() {
        synchronized(lock) { current = Scope(null, current.generation + 1); inFlight = false; lastAttempt = 0 }
        listeners.forEach { runCatching(it) }
    }
    internal fun accept(expectedGeneration: Long, uid: String?) {
        val normalized = uid?.takeIf { ArchiveRecord.validId(it) }
        val changed = synchronized(lock) {
            if (current.generation != expectedGeneration) return
            inFlight = false
            if (current.uid == normalized) false else {
                current = Scope(normalized, current.generation + 1); true
            }
        }
        if (changed) listeners.forEach { runCatching(it) }
    }
    fun refresh(activity: Activity, symbols: ResolvedSymbols, force: Boolean = false) {
        val scope = synchronized(lock) {
            if (inFlight || !force && (current.uid != null || android.os.SystemClock.elapsedRealtime() - lastAttempt < 5000L)) return
            inFlight = true; lastAttempt = android.os.SystemClock.elapsedRealtime(); current
        }
        GalleryPostController.readCurrentAccount(activity, symbols, java.util.function.Consumer { uid -> accept(scope.generation, uid) })
    }
}
