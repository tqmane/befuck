package dev.tqmane.befuck.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import dev.tqmane.befuck.R
import dev.tqmane.befuck.download.FeedMediaDownloader
import dev.tqmane.befuck.download.FeedMediaSelection
import dev.tqmane.befuck.download.FeedPostMedia
import dev.tqmane.befuck.download.FeedPostMediaCache
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/** Inline and detail download overlays injected into BeReal's feed and pull-down post views. */
internal object FeedDownloadOverlay {
    private val mainThread = Handler(Looper.getMainLooper())
    private val detailDownloadOverlays = WeakHashMap<Activity, FrameLayout>()
    private val detailDownloadBindings = WeakHashMap<Activity, BeFuckGalleryUi.DetailDownloadBinding>()
    private val inlineOverlayCreatedLogOnce = AtomicBoolean(false)

    private data class InlineFeedDownloadTarget(val post: FeedPostMedia)

    fun createInlineOverlay(
        context: Context,
        resources: Resources,
        post: FeedPostMedia,
        @Suppress("UNUSED_PARAMETER") detailMode: Boolean = false,
    ): View {
        if (inlineOverlayCreatedLogOnce.compareAndSet(false, true)) {
            android.util.Log.i(
                "BeFuck/UI",
                "Created inline download overlay; context=${context.javaClass.name}, " +
                    "activity=${ExpressiveUi.findActivity(context)?.javaClass?.name ?: "none"}",
            )
        }
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            elevation = ExpressiveUi.dp(context, 64).toFloat()
            translationZ = ExpressiveUi.dp(context, 64).toFloat()
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    view.bringToFront()
                    view.invalidate()
                }

                override fun onViewDetachedFromWindow(view: View) = Unit
            })
        }
        val button = createDownloadButton(context, resources, post)
        val size = ExpressiveUi.dp(context, 48)
        overlay.addView(
            button,
            FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                topMargin = ExpressiveUi.dp(context, 8)
                marginEnd = ExpressiveUi.dp(context, 8)
            },
        )
        return overlay
    }

    fun updateInlineOverlay(view: View, post: FeedPostMedia) {
        val container = view as? FrameLayout ?: return
        val button = container.getChildAt(0) as? ImageButton ?: return
        button.tag = InlineFeedDownloadTarget(post)
    }

    fun showDetailButton(post: FeedPostMedia): BeFuckGalleryUi.DetailDownloadBinding? {
        val activity = HomeFeedEntryOverlay.currentActivity() ?: return null
        val resources = HomeFeedEntryOverlay.resourcesFor(activity) ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        val binding = BeFuckGalleryUi.DetailDownloadBinding(activity, post)
        detailDownloadBindings[activity] = binding
        mainThread.post {
            if (binding.disposed || detailDownloadBindings[activity] !== binding || activity.isFinishing || activity.isDestroyed) {
                return@post
            }
            val root = activity.window?.decorView as? ViewGroup ?: return@post
            if (HomeFeedEntryOverlay.isHomeFeed(activity, root)) return@post
            val overlay = detailDownloadOverlays[activity] ?: FrameLayout(activity).apply {
                setBackgroundColor(Color.TRANSPARENT)
                clipChildren = false
                clipToPadding = false
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                elevation = ExpressiveUi.dp(activity, 64).toFloat()
                translationZ = ExpressiveUi.dp(activity, 64).toFloat()
            }.also { detailDownloadOverlays[activity] = it }
            val button = overlay.getChildAt(0) as? ImageButton
            if (button == null) {
                val size = ExpressiveUi.dp(activity, 48)
                overlay.addView(
                    createDownloadButton(activity, resources, post),
                    FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                        topMargin = ExpressiveUi.dp(activity, 128)
                        marginEnd = ExpressiveUi.dp(activity, 8)
                    },
                )
            } else {
                button.tag = InlineFeedDownloadTarget(post)
            }
            if (overlay.parent !== root) {
                (overlay.parent as? ViewGroup)?.removeView(overlay)
                root.addView(overlay, ViewGroup.LayoutParams(-1, -1))
            }
            overlay.bringToFront()
            android.util.Log.i("BeFuck/UI", "Detail download bound to post=${post.postId}")
        }
        return binding
    }

    fun disposeDetailButton(binding: BeFuckGalleryUi.DetailDownloadBinding?) {
        if (binding == null) return
        binding.disposed = true
        mainThread.post {
            val activity = binding.activity.get() ?: return@post
            // An old screen's queued disposal must not remove a newer screen's selected post.
            if (detailDownloadBindings[activity] === binding) removeDetailButton(activity)
        }
    }

    fun removeDetailButton(activity: Activity) {
        detailDownloadBindings.remove(activity)?.let { binding ->
            binding.disposed = true
            FeedPostMediaCache.endGridDetail(binding.post.postId)
            android.util.Log.i("BeFuck/UI", "Detail download released for post=${binding.post.postId}")
        }
        val overlay = detailDownloadOverlays.remove(activity) ?: return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
    }

    private fun createDownloadButton(
        context: Context,
        resources: Resources,
        post: FeedPostMedia,
    ): ImageButton = ImageButton(context).apply {
        tag = InlineFeedDownloadTarget(post)
        contentDescription = resources.getString(R.string.befuck_feed_download_description)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setImageDrawable(resources.getDrawable(R.drawable.ic_befuck_download, context.theme))
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER
        setPadding(0, 0, 0, 0)
        background = RippleDrawable(
            ColorStateList.valueOf(0x55FFFFFF),
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xB3000000.toInt())
            },
            null,
        )
        elevation = ExpressiveUi.dp(context, 8).toFloat()
        setOnClickListener {
            val target = tag as? InlineFeedDownloadTarget ?: return@setOnClickListener
            val latestPost = FeedPostMediaCache.get(target.post.postId) ?: target.post
            downloadPostMedia(context, resources, latestPost, selectionFor(target, current = true))
        }
        setOnLongClickListener {
            val target = tag as? InlineFeedDownloadTarget ?: return@setOnLongClickListener true
            showPostDownloadMenu(context, resources, this, target)
            true
        }
    }

    private fun showPostDownloadMenu(
        context: Context,
        resources: Resources,
        anchor: View,
        target: InlineFeedDownloadTarget,
    ) {
        PopupMenu(context, anchor).apply {
            menu.add(0, 1, 0, resources.getString(R.string.befuck_download_current))
            menu.add(0, 2, 1, resources.getString(R.string.befuck_download_other))
            menu.add(0, 3, 2, resources.getString(R.string.befuck_download_both))
            setOnMenuItemClickListener { item ->
                val selection = when (item.itemId) {
                    1 -> selectionFor(target, current = true)
                    2 -> selectionFor(target, current = false)
                    else -> FeedMediaSelection.ALL
                }
                downloadPostMedia(context, resources, target.post, selection)
                true
            }
            show()
        }
    }

    private fun selectionFor(target: InlineFeedDownloadTarget, current: Boolean): FeedMediaSelection {
        val isPrimaryDisplayed = FeedPostMediaCache.isPrimaryDisplayed(target.post.postId)
        if (current) {
            return when {
                isPrimaryDisplayed && target.post.primary != null -> FeedMediaSelection.PRIMARY
                !isPrimaryDisplayed && target.post.secondary != null -> FeedMediaSelection.SECONDARY
                target.post.primary != null -> FeedMediaSelection.PRIMARY
                else -> FeedMediaSelection.SECONDARY
            }
        }
        return if (isPrimaryDisplayed) FeedMediaSelection.SECONDARY else FeedMediaSelection.PRIMARY
    }

    private fun downloadPostMedia(
        context: Context,
        resources: Resources,
        post: FeedPostMedia,
        selection: FeedMediaSelection,
    ) {
        val hasSelection = when (selection) {
            FeedMediaSelection.PRIMARY -> post.primary != null
            FeedMediaSelection.SECONDARY -> post.secondary != null
            FeedMediaSelection.ALL -> post.primary != null || post.secondary != null
        }
        if (!hasSelection) {
            Toast.makeText(context, resources.getString(R.string.befuck_download_failed), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, resources.getString(R.string.befuck_download_in_progress), Toast.LENGTH_SHORT).show()
        FeedMediaDownloader.download(context, post, selection, Consumer { result ->
            mainThread.post {
                val message = if (result.success) {
                    resources.getString(R.string.befuck_download_complete, result.completedCount)
                } else {
                    resources.getString(R.string.befuck_download_failed)
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        })
    }
}
