package dev.tqmane.befuck.download

import java.util.function.Consumer

/** Coalesce repeated taps; subscriber failures never become a second download completion. */
internal class DownloadJobs {
    private val pending = mutableMapOf<String, MutableList<Consumer<FeedMediaDownloadResult>>>()
    @Synchronized fun join(key: String, completion: Consumer<FeedMediaDownloadResult>): Boolean {
        val listeners = pending[key]
        if (listeners != null) { listeners.add(completion); return false }
        pending[key] = arrayListOf(completion)
        return true
    }
    fun complete(key: String, result: FeedMediaDownloadResult) {
        val listeners = synchronized(this) { pending.remove(key)?.toList().orEmpty() }
        listeners.forEach { callback -> runCatching { callback.accept(result) } }
    }
}
