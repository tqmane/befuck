package dev.tqmane.befuck.ui

import android.app.Activity
import android.content.res.Resources
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import dev.tqmane.befuck.download.FeedMediaDownloader
import dev.tqmane.befuck.download.FeedMediaSelection
import dev.tqmane.befuck.download.FeedPostMedia
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.function.Consumer

/** Only visible thumbnails load. Viewer opening, activity pause, filtering and dismissal release old requests. */
internal class SavedPreviewSession(private val activity: Activity, private val labels: Resources, private val root: View,
    private val guard: () -> Boolean, val source: PreviewSource,
    val downloads: PostMediaActions = PostMediaActions(guard, { post, selection, done ->
        FeedMediaDownloader.download(activity.applicationContext, post, selection, Consumer { result -> Handler(Looper.getMainLooper()).post { done(result) } })
    })) {
    private val tiles = arrayListOf<InlinePostPreview>()
    private val subscriptions = arrayListOf<() -> Unit>()
    private var viewer: SavedPostViewer? = null
    private var closed = false
    private var paused = false
    private var observed: ViewTreeObserver? = null
    private val scroll = ViewTreeObserver.OnScrollChangedListener { update() }
    private val layout = ViewTreeObserver.OnGlobalLayoutListener { update() }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { listen(); update() }
        override fun onViewDetachedFromWindow(view: View) { unlisten(); tiles.forEach { it.visible(false) } }
    }
    init { register(activity, this); root.addOnAttachStateChangeListener(attach); listen() }
    private fun listen() {
        unlisten()
        observed = root.viewTreeObserver.also { it.addOnScrollChangedListener(scroll); it.addOnGlobalLayoutListener(layout) }
    }
    private fun unlisten() { observed?.takeIf { it.isAlive }?.let { it.removeOnScrollChangedListener(scroll); it.removeOnGlobalLayoutListener(layout) }; observed = null }
    fun tile(post: FeedPostMedia, side: FeedMediaSelection): InlinePostPreview = InlinePostPreview(activity, labels, post, side, source, downloads, guard) {
        show(post, side)
    }.also { tiles += it }
    fun watch(listener: () -> Unit) { subscriptions += downloads.watch(listener) }
    fun clearRows() { tiles.forEach { it.close() }; tiles.clear(); subscriptions.forEach { it() }; subscriptions.clear() }
    fun update() {
        val allowed = !closed && !paused && viewer == null && guard() && root.isAttachedToWindow && root.windowVisibility == View.VISIBLE
        tiles.forEach { tile ->
            val rect = Rect()
            tile.visible(allowed && tile.isShown && tile.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 24)
        }
    }
    fun show(post: FeedPostMedia, side: FeedMediaSelection) {
        if (closed || paused || !guard()) return
        viewer?.dismiss()
        viewer = SavedPostViewer(activity, labels, post, side, source, downloads, guard) { viewer = null; update() }
        update(); viewer?.showViewer()
    }
    private fun pause() { paused = true; update(); viewer?.pauseMedia() }
    private fun resume() { paused = false; viewer?.resumeMedia(); update() }
    fun close() {
        if (closed) return
        closed = true; viewer?.dismiss(); viewer = null; clearRows(); unlisten()
        root.removeOnAttachStateChangeListener(attach); source.close(); downloads.close()
        owners[activity]?.removeAll { it.get() == null || it.get() === this }
    }
    companion object {
        private val owners = WeakHashMap<Activity, MutableList<WeakReference<SavedPreviewSession>>>()
        private fun register(activity: Activity, session: SavedPreviewSession) { owners.getOrPut(activity) { arrayListOf() }.add(WeakReference(session)) }
        fun pause(activity: Activity) { owners[activity]?.toList()?.mapNotNull { it.get() }?.forEach { it.pause() } }
        fun resume(activity: Activity) { owners[activity]?.toList()?.mapNotNull { it.get() }?.forEach { it.resume() } }
        fun destroy(activity: Activity) { owners.remove(activity)?.mapNotNull { it.get() }?.forEach { it.close() } }
    }
}
