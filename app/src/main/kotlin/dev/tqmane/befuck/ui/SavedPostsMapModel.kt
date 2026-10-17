package dev.tqmane.befuck.ui

import dev.tqmane.befuck.download.FeedPostMedia

/** An immutable day/query snapshot. Pagination only applies to the timeline, never to map points. */
internal class SavedPostsMapModel(posts: List<FeedPostMedia>) {
    private val snapshot = posts.distinctBy { it.postId }
    val points = snapshot.filter { it.location != null }
    val missingCount = snapshot.size - points.size
    var selectedIndex = if (points.isEmpty()) -1 else 0
        private set
    val selected: FeedPostMedia? get() = points.getOrNull(selectedIndex)
    private var lastHits: List<Int> = emptyList()

    /** Rendered features may contain the same post in both its point and photo layers. */
    fun selectHits(hits: Collection<Int>): Boolean {
        val candidates = hits.filter { it in points.indices }.distinct().sorted()
        if (candidates.isEmpty()) return false
        selectedIndex = if (candidates == lastHits) {
            candidates[(candidates.indexOf(selectedIndex) + 1) % candidates.size]
        } else candidates.first()
        lastHits = candidates
        return true
    }

    fun move(delta: Int) {
        if (points.isEmpty()) return
        selectedIndex = Math.floorMod(selectedIndex + delta, points.size)
        lastHits = emptyList()
    }
}
