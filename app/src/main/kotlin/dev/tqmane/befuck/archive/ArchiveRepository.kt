package dev.tqmane.befuck.archive

import android.content.Context
import dev.tqmane.befuck.download.FeedPostMedia
import java.util.concurrent.Executors

/** Mutations and retry drains share one worker. Account identity is fixed before enqueueing. */
internal object ArchiveRepository {
    val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "BeFuckArchive") }
    @Volatile private var store: ArchiveStore? = null
    @Volatile private var opening = false
    @Volatile var lastError: String? = null
        private set
    private val pending = ArchiveWriteBuffer()

    @Synchronized fun initialize(context: Context) {
        if (opening || store != null && pending.size == 0) return
        opening = true
        val path = context.getDatabasePath("befuck-post-urls.db")
        executor.execute {
            try {
                val database = store ?: ArchiveStore.open(path).also { store = it }
                flush(database)
            } catch (failure: Exception) { recordFailure(failure) }
            finally { opening = false }
        }
    }
    fun capture(account: String, post: FeedPostMedia) {
        executor.execute {
            try {
                pending.enqueue(ArchiveRecord.observe(account, post))
                store?.let(::flush)
            } catch (failure: Exception) { recordFailure(failure) }
        }
    }
    private fun flush(database: ArchiveStore) {
        pending.drain { record -> database.merge(listOf(record), record.account) }
        lastError = null
    }
    private fun recordFailure(failure: Exception) {
        lastError = (failure as? ArchiveFailure)?.reason ?: "storage"
        android.util.Log.w("BeFuck/Archive", "Archive write/open failed ($lastError); persisted data retained; previously queued writes are not discarded")
    }
    fun unassignedCount(): Int = store?.count("") ?: 0
    fun requireStore(): ArchiveStore {
        // Do not call an export complete while previously observed rows are still waiting to be persisted.
        if (pending.size > 0) throw ArchiveFailure(lastError ?: "pending_writes")
        return store ?: throw ArchiveFailure(lastError ?: "not_ready")
    }
    fun get(account: String, id: String) = store?.get(account, id)?.post()
    fun recent(account: String) = requireStore().records(account).map { it.post() }.sortedByDescending {
        dev.tqmane.befuck.download.PostMediaMetadata.timestamp(it) ?: Long.MIN_VALUE
    }
}
