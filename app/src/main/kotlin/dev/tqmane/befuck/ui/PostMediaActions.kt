package dev.tqmane.befuck.ui

import dev.tqmane.befuck.download.FeedMediaDownloadResult
import dev.tqmane.befuck.download.FeedMediaItem
import dev.tqmane.befuck.download.FeedMediaSelection
import dev.tqmane.befuck.download.FeedPostMedia

/** A page is bound to one immutable post/side, never the current list position or current swipe index. */
internal data class PostMediaPage(val selection: FeedMediaSelection, val media: FeedMediaItem)
internal class PostMediaPages(post: FeedPostMedia, initial: FeedMediaSelection) {
    val pages = listOfNotNull(post.primary?.let { PostMediaPage(FeedMediaSelection.PRIMARY, it) }, post.secondary?.let { PostMediaPage(FeedMediaSelection.SECONDARY, it) })
    var index: Int = pages.indexOfFirst { it.selection == initial }.coerceAtLeast(0)
        private set
    val current: PostMediaPage? get() = pages.getOrNull(index)
    fun select(index: Int): Boolean {
        if (index !in pages.indices || index == this.index) return false
        this.index = index; return true
    }
    fun swipe(left: Boolean): Boolean = select(index + if (left) 1 else -1)
}

internal class PostMediaActions(private val guard: () -> Boolean,
    private val perform: (FeedPostMedia, FeedMediaSelection, (FeedMediaDownloadResult) -> Unit) -> Unit) {
    data class State(val postId: String, val selection: FeedMediaSelection, val result: FeedMediaDownloadResult?)
    private val pending = mutableSetOf<Pair<String, FeedMediaSelection>>()
    private val listeners = linkedSetOf<() -> Unit>()
    private val results = mutableMapOf<Pair<String, FeedMediaSelection>, FeedMediaDownloadResult>()
    var last: State? = null
        private set
    private var closed = false
    fun busy(postId: String, side: FeedMediaSelection): Boolean = pending.any { it.first == postId && (side == FeedMediaSelection.ALL || it.second == FeedMediaSelection.ALL || it.second == side) }
    fun result(postId: String, side: FeedMediaSelection) = results[postId to side]
    fun watch(listener: () -> Unit): () -> Unit { listeners += listener; listener(); return { listeners -= listener } }
    private fun update() { listeners.toList().forEach { it() } }
    fun save(post: FeedPostMedia, side: FeedMediaSelection): Boolean {
        if (closed || !guard() || busy(post.postId, side)) return false
        val key = post.postId to side
        pending += key; results.remove(key); last = State(post.postId, side, null); update()
        var completed = false
        fun complete(value: FeedMediaDownloadResult) {
            if (completed) return
            completed = true; pending -= key
            if (!closed && guard()) { results[key] = value; last = State(post.postId, side, value); update() }
        }
        try { perform(post, side, ::complete) }
        catch (_: Exception) { complete(FeedMediaDownloadResult(false, 0, 0)) }
        return true
    }
    fun close() { closed = true; listeners.clear() }
}
