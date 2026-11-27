package dev.tqmane.befuck.archive

/** Worker-owned bounded retry buffer. A failed write is not removed or restamped. */
internal class ArchiveWriteBuffer(private val maximumPosts: Int = 4096) {
    private val values = linkedMapOf<Pair<String, String>, ArchiveRecord>()
    @Volatile var size: Int = 0
        private set
    fun enqueue(record: ArchiveRecord) {
        ArchiveMerge.validate(record)
        val key = record.account to record.id
        archiveCheck(values.containsKey(key) || values.size < maximumPosts, "pending_limit")
        val merged = ArchiveMerge.merge(values[key], record)
        ArchiveMerge.validate(merged)
        values[key] = merged
        size = values.size
    }
    fun drain(persist: (ArchiveRecord) -> Unit) = drain({ _, _ -> }, persist)

    fun drain(
        onDropped: (ArchiveRecord, ArchiveFailure) -> Unit,
        persist: (ArchiveRecord) -> Unit,
    ) {
        while (values.isNotEmpty()) {
            val entry = values.entries.first()
            try {
                persist(entry.value)
                values.remove(entry.key)
                size = values.size
            } catch (failure: ArchiveFailure) {
                values.remove(entry.key)
                size = values.size
                onDropped(entry.value, failure)
            }
        }
    }
}
