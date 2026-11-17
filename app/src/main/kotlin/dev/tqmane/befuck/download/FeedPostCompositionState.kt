package dev.tqmane.befuck.download

import android.util.LruCache
import java.lang.reflect.Modifier
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** Transient Compose rendering, flip-side, and grid-detail selection state for feed posts. */
internal object FeedPostCompositionState {
    @Volatile var activeTimelinePostId: String? = null
        private set
    private val composingPostId = ThreadLocal<String?>()
    private val currentDualMediaPostId = ThreadLocal<String?>()
    private val flipStateLock = Any()
    private val flipStatePostIds = WeakHashMap<Any, String>()
    private val primaryDisplayedByPostId = LruCache<String, Boolean>(200)
    private val dualMediaPostLock = Any()
    private val dualMediaPostIds = WeakHashMap<Any, String>()
    private val pendingGridPostLock = Any()
    private var pendingGridPostId: String? = null
    private var pendingGridPostAtMillis: Long = 0L
    @Volatile private var activeGridDetailPostId: String? = null
    private val composingGridDetailPostId = ThreadLocal<String?>()

    fun recordVisibleDualMedia(dualMedia: Any, postId: String) {
        synchronized(dualMediaPostLock) { dualMediaPostIds[dualMedia] = postId }
        activeTimelinePostId = postId
    }

    fun clearVisiblePostComposition() {
        composingPostId.remove()
    }

    fun setVisiblePostComposition(postId: String) {
        composingPostId.set(postId)
    }

    fun endVisiblePostComposition() {
        composingPostId.remove()
    }

    fun currentComposingPostId(): String? = currentDualMediaPostId.get() ?: composingPostId.get()

    fun beginDualMediaComposition(dualMedia: Any?, postsSnapshot: () -> Collection<FeedPostMedia>): String? {
        val postId = dualMedia?.let { postIdForDualMedia(it, postsSnapshot) }
        val selectedPostId = postId ?: composingPostId.get()
        if (selectedPostId == null) currentDualMediaPostId.remove() else currentDualMediaPostId.set(selectedPostId)
        return selectedPostId
    }

    fun endDualMediaComposition() {
        currentDualMediaPostId.remove()
    }

    fun noteGridPostSelected(postId: String?) {
        if (postId.isNullOrBlank()) return
        synchronized(pendingGridPostLock) {
            pendingGridPostId = postId
            pendingGridPostAtMillis = System.currentTimeMillis()
        }
        activeGridDetailPostId = postId
    }

    fun beginGridDetailComposition(postId: String?): String? {
        val selectedPostId = activeGridDetailPostId
        if (selectedPostId != null && selectedPostId == postId) {
            composingGridDetailPostId.set(selectedPostId)
            return selectedPostId
        }
        composingGridDetailPostId.remove()
        return null
    }

    fun composingGridDetailPostId(): String? = composingGridDetailPostId.get()

    fun endGridDetailComposition() {
        composingGridDetailPostId.remove()
    }

    fun endGridDetail(postId: String) {
        if (activeGridDetailPostId == postId) activeGridDetailPostId = null
        clearPendingIfMatches(postId)
    }

    fun hasPendingGridPostForDetail(): Boolean = synchronized(pendingGridPostLock) {
        val elapsed = System.currentTimeMillis() - pendingGridPostAtMillis
        pendingGridPostId != null && elapsed in 0L..15_000L
    }

    fun clearPendingIfMatches(postId: String) {
        synchronized(pendingGridPostLock) {
            if (pendingGridPostId == postId) {
                pendingGridPostId = null
                pendingGridPostAtMillis = 0L
            }
        }
    }

    fun consumePendingGridPostIdForDetail(dualMedia: Any?, postsSnapshot: () -> Collection<FeedPostMedia>): String? {
        val mediaPostId = dualMedia?.let { postIdForDualMedia(it, postsSnapshot) } ?: return null
        return synchronized(pendingGridPostLock) { consumeIfFreshLocked(mediaPostId, 15_000L) }
    }

    fun consumeSelectedGridPostIdForDetail(dualMedia: Any?): String? {
        val postId = synchronized(pendingGridPostLock) { consumeIfFreshLocked(null, 20_000L) } ?: return null
        if (dualMedia != null) synchronized(dualMediaPostLock) { dualMediaPostIds[dualMedia] = postId }
        return postId
    }

    private fun consumeIfFreshLocked(expectedPostId: String?, windowMs: Long): String? {
        val selectedPostId = pendingGridPostId ?: return null
        if (expectedPostId != null && selectedPostId != expectedPostId) return null
        val elapsed = System.currentTimeMillis() - pendingGridPostAtMillis
        if (elapsed !in 0L..windowMs) return null
        pendingGridPostId = null
        pendingGridPostAtMillis = 0L
        return selectedPostId
    }

    fun setComposingPrimaryDisplayed(isPrimaryDisplayed: Boolean) {
        val postId = currentComposingPostId() ?: return
        synchronized(flipStateLock) { primaryDisplayedByPostId.put(postId, isPrimaryDisplayed) }
    }

    fun isPrimaryDisplayed(postId: String): Boolean = synchronized(flipStateLock) {
        primaryDisplayedByPostId.get(postId) ?: true
    }

    fun associateFlipStateWithComposingPost(state: Any?) {
        val postId = currentComposingPostId() ?: return
        if (state != null) synchronized(flipStateLock) { flipStatePostIds[state] = postId }
    }

    fun updatePrimaryDisplayedFromFlipState(state: Any?, isSecondaryDisplayedAsPrimary: Boolean) {
        if (state == null) return
        val postId = synchronized(flipStateLock) { flipStatePostIds[state] } ?: return
        synchronized(flipStateLock) { primaryDisplayedByPostId.put(postId, !isSecondaryDisplayedAsPrimary) }
    }

    fun postIdForDualMedia(dualMedia: Any, postsSnapshot: () -> Collection<FeedPostMedia>): String? {
        synchronized(dualMediaPostLock) { dualMediaPostIds[dualMedia] }?.let { return it }
        val urls = mutableListOf<String>()
        collectHttpsStrings(dualMedia, urls, Collections.newSetFromMap(IdentityHashMap()), 0)
        if (urls.isEmpty()) return null
        val exactUrls = urls.toHashSet()
        val normalizedUrls = urls.mapTo(HashSet(), ::normalizedMediaUrl)
        val matchingPostIds = postsSnapshot().asSequence()
            .filter { post ->
                listOfNotNull(post.primary?.url, post.secondary?.url).any { url ->
                    url in exactUrls || normalizedMediaUrl(url) in normalizedUrls
                }
            }
            .map(FeedPostMedia::postId)
            .distinct()
            .toList()
        val postId = matchingPostIds.singleOrNull() ?: return null
        synchronized(dualMediaPostLock) { dualMediaPostIds[dualMedia] = postId }
        return postId
    }

    private fun normalizedMediaUrl(url: String): String = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return@runCatching url.substringBefore('?')
        val path = uri.path.orEmpty()
        if (path.isEmpty()) url.substringBefore('?') else "$host$path"
    }.getOrDefault(url.substringBefore('?'))

    fun collectHttpsStrings(value: Any?, urls: MutableList<String>, seen: MutableSet<Any>, depth: Int) {
        if (value == null || depth > 5 || urls.size >= 8) return
        if (value is String) {
            if (value.startsWith("https://", ignoreCase = true) && value.length < 8_192) urls += value
            return
        }
        if (!seen.add(value)) return
        if (value is Iterable<*>) {
            value.forEach { collectHttpsStrings(it, urls, seen, depth + 1) }
            return
        }
        if (value.javaClass.isArray) {
            for (index in 0 until java.lang.reflect.Array.getLength(value)) {
                collectHttpsStrings(java.lang.reflect.Array.get(value, index), urls, seen, depth + 1)
            }
            return
        }
        val packageName = value.javaClass.name
        if (packageName.startsWith("java.") || packageName.startsWith("android.") || packageName.startsWith("kotlin.")) return
        for (field in value.javaClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive || urls.size >= 8) continue
            try {
                field.isAccessible = true
                collectHttpsStrings(field.get(value), urls, seen, depth + 1)
            } catch (_: Throwable) {
            }
        }
    }
}
