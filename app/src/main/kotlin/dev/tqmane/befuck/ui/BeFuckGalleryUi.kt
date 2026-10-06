package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.Context
import android.content.res.Resources
import android.content.ActivityNotFoundException
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import androidx.exifinterface.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.text.TextPaint
import android.widget.TextView
import android.widget.Toast
import dev.tqmane.befuck.R
import dev.tqmane.befuck.download.FeedMediaDownloadResult
import dev.tqmane.befuck.download.FeedMediaDownloader
import dev.tqmane.befuck.download.FeedMediaSelection
import dev.tqmane.befuck.download.FeedPostMedia
import dev.tqmane.befuck.download.FeedPostMediaCache
import dev.tqmane.befuck.download.PostMediaMetadata
import dev.tqmane.befuck.runtime.RuntimeKnowledge
import dev.tqmane.befuck.posting.GalleryMediaFile
import dev.tqmane.befuck.posting.LocationData
import dev.tqmane.befuck.posting.BeFakeAuthHeaders
import dev.tqmane.befuck.posting.BeFakeUploadController
import dev.tqmane.befuck.posting.GalleryPostController
import dev.tqmane.befuck.posting.GalleryPostRequest
import dev.tqmane.befuck.posting.Media3VideoCompressor
import dev.tqmane.befuck.symbols.ResolvedSymbols
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.math.max
import kotlin.math.min

object BeFuckGalleryUi {
    const val REQUEST_FRONT = 0x4B31
    const val REQUEST_BACK = 0x4B32
    const val REQUEST_FRONT_VIDEO = 0x4B33
    const val REQUEST_BACK_VIDEO = 0x4B34
    private const val ENTRY_TAG = "dev.tqmane.befuck.entry"
    private const val MAX_IMAGE_BYTES = 1_048_576
    private const val MAX_SOURCE_VIDEO_BYTES = 1_073_741_824L
    private val sessions = WeakHashMap<Activity, Session>()
    private val entryTrackers = WeakHashMap<Activity, EntryTracker>()
    private val lastHomeState = WeakHashMap<Activity, Boolean>()
    private val detailDownloadOverlays = WeakHashMap<Activity, FrameLayout>()
    private val detailDownloadBindings = WeakHashMap<Activity, DetailDownloadBinding>()
    private var activeActivity = WeakReference<Activity>(null)
    private val importer = Executors.newSingleThreadExecutor()
    private val mainThread = android.os.Handler(android.os.Looper.getMainLooper())
    private val inlineOverlayCreatedLogOnce = AtomicBoolean(false)

    private class EntryTracker(
        private val activity: Activity,
        val root: ViewGroup,
        val resources: Resources,
        private val symbols: ResolvedSymbols,
    ) : Runnable {
        override fun run() {
            if (activity.isFinishing || activity.isDestroyed || activity.window?.decorView !== root) {
                onActivityPause(activity)
                return
            }
            val onHome = reconcileEntry(activity, root, resources, symbols)
            if (onHome) removeDetailDownloadButton(activity)
            mainThread.postDelayed(this, 900L)
        }
    }

    private data class Session(
        val dialog: Dialog,
        val resources: Resources,
        val frontPreview: ImageView,
        val backPreview: ImageView,
        val frontLabel: TextView,
        val backLabel: TextView,
        val caption: EditText,
        val retakes: SeekBar,
        val late: Switch,
        val status: TextView,
        var keepMediaUntilUpload: Boolean = false,
        var pendingOfficialPostId: String? = null,
        var front: GalleryMediaFile? = null,
        var back: GalleryMediaFile? = null,
        var location: LocationData? = null,
        var postButton: Button? = null,
        var isSubmitting: Boolean = false,
        var isPreparing: Boolean = false,
        val mediaButtons: MutableMap<Int, Button> = linkedMapOf(),
        var frontPhotoSource: Uri? = null,
        var backPhotoSource: Uri? = null,
    )

    private data class InlineFeedDownloadTarget(val post: FeedPostMedia)

    @JvmStatic
    fun installEntryButton(activity: Activity, resources: Resources?, symbols: ResolvedSymbols) {
        if (activity.isFinishing || activity.isDestroyed) return
        activeActivity = WeakReference(activity)
        FeedPostMediaCache.initialize(activity.applicationContext)
        BeFakeLocationDialog.onActivityResume(activity)
        if (resources == null) {
            android.util.Log.e("BeFuck/UI", "Module resources are unavailable; not adding the gallery entry")
            return
        }
        val root = activity.window?.decorView as? ViewGroup ?: return
        android.util.Log.i("BeFuck/UI", "Starting home-only BeFake entry monitor for ${activity.javaClass.name}")
        val previous = entryTrackers[activity]
        if (previous != null) {
            if (previous.root === root) return
            mainThread.removeCallbacks(previous)
        }
        val tracker = EntryTracker(activity, root, resources, symbols)
        entryTrackers[activity] = tracker
        mainThread.post(tracker)
    }

    @JvmStatic
    fun onActivityPause(activity: Activity) {
        BeFakeLocationDialog.onActivityPause(activity)
        entryTrackers.remove(activity)?.let(mainThread::removeCallbacks)
        val root = activity.window?.decorView as? ViewGroup
        if (root != null) {
            removeEntry(root)
        }
        removeDetailDownloadButton(activity)
        lastHomeState.remove(activity)
    }

    @JvmStatic
    fun onActivityDestroy(activity: Activity) {
        onActivityPause(activity)
        BeFakeLocationDialog.onActivityDestroy(activity)
        runCatching { sessions[activity]?.dialog?.dismiss() }
        sessions.remove(activity)?.let { destroyed ->
            if (!destroyed.keepMediaUntilUpload) {
                destroyed.front?.let { discardPreparedMedia(activity, it) }
                destroyed.back?.let { discardPreparedMedia(activity, it) }
            }
        }
    }

    private fun reconcileEntry(activity: Activity, root: ViewGroup, resources: Resources, symbols: ResolvedSymbols): Boolean {
        val onHome = isHomeFeed(activity, root)
        val wasHome = lastHomeState.put(activity, onHome)
        val entry = findEntry(root)
        if (!onHome) {
            if (entry != null) {
                root.removeView(entry)
                android.util.Log.i("BeFuck/UI", "Removed BeFuck entry outside the home feed")
            }
            return false
        }
        val activeEntry = entry ?: ImageButton(activity).apply {
            tag = ENTRY_TAG
            contentDescription = resources.getString(R.string.befuck_entry_description)
            setImageDrawable(resources.getDrawable(R.drawable.ic_befuck_add, activity.theme))
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            background = null
            elevation = 0f
            setOnClickListener { open(activity, resources, symbols) }
        }
        val size = dp(activity, 48)
        val width = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val params = (activeEntry.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START)
        params.width = size
        params.height = size
        params.topMargin = headerLogoTop(activity, size)
        params.leftMargin = width / 2 + logoHalfWidthWithGap(activity)
        if (activeEntry.parent == null) root.addView(activeEntry, params) else activeEntry.layoutParams = params
        activeEntry.bringToFront()
        if (entry == null || wasHome != true) {
            android.util.Log.i("BeFuck/UI", "Attached BeFuck entry beside the BeReal logo on the home feed")
        }
        return true
    }

    @JvmStatic
    @JvmOverloads
    fun createInlineFeedDownloadOverlay(
        context: Context,
        resources: Resources,
        post: FeedPostMedia,
        detailMode: Boolean = false,
    ): View {
        if (inlineOverlayCreatedLogOnce.compareAndSet(false, true)) {
            android.util.Log.i(
                "BeFuck/UI",
                "Created inline download overlay; context=${context.javaClass.name}, activity=${findActivity(context)?.javaClass?.name ?: "none"}"
            )
        }
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            elevation = dp(context, 64).toFloat()
            translationZ = dp(context, 64).toFloat()
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    view.bringToFront()
                    view.invalidate()
                }

                override fun onViewDetachedFromWindow(view: View) = Unit
            })
        }
        val button = createInlineFeedDownloadButton(context, resources, post)
        val size = dp(context, 48)
        overlay.addView(button, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(context, 8)
            marginEnd = dp(context, 8)
        })
        return overlay
    }

    private fun createInlineFeedDownloadButton(
        context: Context,
        resources: Resources,
        post: FeedPostMedia,
    ): ImageButton = ImageButton(context).apply {
        tag = InlineFeedDownloadTarget(post)
        contentDescription = resources.getString(R.string.befuck_feed_download_description)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setImageDrawable(resources.getDrawable(R.drawable.ic_befuck_download, context.theme))
        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER
        setPadding(0, 0, 0, 0)
        background = RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x55FFFFFF),
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xB3000000.toInt())
            },
            null,
        )
        elevation = dp(context, 8).toFloat()
        setOnClickListener {
            val target = tag as? InlineFeedDownloadTarget ?: return@setOnClickListener
            val post = FeedPostMediaCache.get(target.post.postId) ?: target.post
            downloadPostMedia(context, resources, post, selectionFor(target, current = true))
        }
        setOnLongClickListener {
            val target = tag as? InlineFeedDownloadTarget ?: return@setOnLongClickListener true
            showPostDownloadMenu(context, resources, this, target)
            true
        }
    }

    @JvmStatic
    fun updateInlineFeedDownloadOverlay(view: View, post: FeedPostMedia) {
        val container = view as? FrameLayout ?: return
        val button = container.getChildAt(0) as? ImageButton ?: return
        button.tag = InlineFeedDownloadTarget(post)
    }

    class DetailDownloadBinding internal constructor(activity: Activity, val post: FeedPostMedia) {
        internal val activity = WeakReference(activity)
        @Volatile internal var disposed = false
    }

    @JvmStatic
    fun showDetailDownloadButton(post: FeedPostMedia): DetailDownloadBinding? {
        val activity = activeActivity.get() ?: return null
        val resources = entryTrackers[activity]?.resources ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        val binding = DetailDownloadBinding(activity, post)
        detailDownloadBindings[activity] = binding
        mainThread.post {
            if (binding.disposed || detailDownloadBindings[activity] !== binding || activity.isFinishing || activity.isDestroyed) return@post
            val root = activity.window?.decorView as? ViewGroup ?: return@post
            if (isHomeFeed(activity, root)) return@post
            val overlay = detailDownloadOverlays[activity] ?: FrameLayout(activity).apply {
                setBackgroundColor(Color.TRANSPARENT)
                clipChildren = false
                clipToPadding = false
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                elevation = dp(activity, 64).toFloat()
                translationZ = dp(activity, 64).toFloat()
            }.also { detailDownloadOverlays[activity] = it }
            val button = overlay.getChildAt(0) as? ImageButton
            if (button == null) {
                overlay.addView(
                    createInlineFeedDownloadButton(activity, resources, post),
                    FrameLayout.LayoutParams(dp(activity, 48), dp(activity, 48), Gravity.TOP or Gravity.END).apply {
                        topMargin = dp(activity, 128)
                        marginEnd = dp(activity, 8)
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

    @JvmStatic
    fun disposeDetailDownloadButton(binding: DetailDownloadBinding?) {
        if (binding == null) return
        binding.disposed = true
        mainThread.post {
            val activity = binding.activity.get() ?: return@post
            // An old screen's queued disposal must not remove a newer screen's selected post.
            if (detailDownloadBindings[activity] === binding) removeDetailDownloadButton(activity)
        }
    }

    private fun removeDetailDownloadButton(activity: Activity) {
        detailDownloadBindings.remove(activity)?.let { binding ->
            binding.disposed = true
            FeedPostMediaCache.endGridDetail(binding.post.postId)
            android.util.Log.i("BeFuck/UI", "Detail download released for post=${binding.post.postId}")
        }
        val overlay = detailDownloadOverlays.remove(activity) ?: return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
    }

    private fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current != null) {
            if (current is Activity) return current
            if (current !is android.content.ContextWrapper) return null
            val base = current.baseContext
            if (base === current) return null
            current = base
        }
        return null
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

    private fun findEntry(root: ViewGroup): ImageButton? =
        (0 until root.childCount).asSequence()
            .mapNotNull { root.getChildAt(it) as? ImageButton }
            .firstOrNull { it.tag == ENTRY_TAG }

    private fun removeEntry(root: ViewGroup) {
        findEntry(root)?.let(root::removeView)
    }

    private fun isHomeFeed(activity: Activity, root: ViewGroup): Boolean {
        val appResources = activity.resources
        val expectedLabels = listOf("general_myFriends", "general_fof").mapNotNull { key ->
            val id = appResources.getIdentifier(key, "string", activity.packageName)
            if (id == 0) null else runCatching { normalize(appResources.getString(id)) }.getOrNull()
        }.filter { it.isNotBlank() }
        if (expectedLabels.size != 2) return false

        val visibleLabels = HashSet<String>()
        val nodeCount = intArrayOf(0)
        collectViewLabels(root, visibleLabels, nodeCount, 0)
        val isHome = expectedLabels.all { expected -> visibleLabels.any { it == expected || it.contains(expected) } }
        return isHome
    }

    private fun collectAccessibilityLabels(
        node: AccessibilityNodeInfo,
        labels: MutableSet<String>,
        visited: IntArray,
        depth: Int,
    ) {
        if (depth > 32 || visited[0] >= 450) return
        visited[0]++
        node.text?.toString()?.let { labels += normalize(it) }
        node.contentDescription?.toString()?.let { labels += normalize(it) }
        for (index in 0 until node.childCount) {
            if (visited[0] >= 450) break
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                collectAccessibilityLabels(child, labels, visited, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    private fun collectViewLabels(
        view: View,
        labels: MutableSet<String>,
        visited: IntArray,
        depth: Int,
    ) {
        if (depth > 32 || visited[0] >= 700) return
        visited[0]++
        if (view is TextView) view.text?.toString()?.let { labels += normalize(it) }
        view.contentDescription?.toString()?.let { labels += normalize(it) }

        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") {
            collectComposeSemanticsLabels(view, labels, visited)
        }

        val provider = runCatching { view.accessibilityNodeProvider }.getOrNull()
        if (provider != null && visited[0] < 700) {
            runCatching { provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID) }
                .getOrNull()
                ?.let { info ->
                    try {
                        collectAccessibilityLabels(info, labels, visited, depth + 1)
                    } finally {
                        info.recycle()
                    }
                }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (visited[0] >= 700) break
                collectViewLabels(view.getChildAt(index), labels, visited, depth + 1)
            }
        }
    }

    private fun collectComposeSemanticsLabels(
        composeView: View,
        labels: MutableSet<String>,
        visited: IntArray,
    ) {
        if (visited[0] >= 700) return
        runCatching {
            val semanticsOwnerType = Class.forName("androidx.compose.ui.semantics.SemanticsOwner", false, composeView.javaClass.classLoader)
            val getOwner = composeView.javaClass.methods.singleOrNull { method ->
                method.parameterCount == 0 && method.returnType == semanticsOwnerType
            } ?: return@runCatching
            val owner = getOwner.invoke(composeView) ?: return@runCatching
            val rootNode = owner.javaClass.methods.singleOrNull { method ->
                method.parameterCount == 0 && method.returnType.name == "androidx.compose.ui.semantics.SemanticsNode"
            }?.invoke(owner) ?: return@runCatching
            val pending = java.util.ArrayDeque<Any>()
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
            pending.add(rootNode)
            while (pending.isNotEmpty() && visited[0] < 700) {
                val node = pending.removeFirst()
                if (!seen.add(node)) continue
                visited[0]++
                val config = node.javaClass.declaredFields.firstOrNull { field ->
                    field.type.name == "androidx.compose.ui.semantics.SemanticsConfiguration"
                }?.let { field ->
                    field.isAccessible = true
                    field.get(node)
                }
                config?.toString()?.let { labels += normalize(it) }
                val childrenMethod = node.javaClass.methods.singleOrNull { method ->
                    method.parameterTypes.size == 2 && method.parameterTypes.all { it == java.lang.Boolean.TYPE } &&
                        List::class.java.isAssignableFrom(method.returnType)
                }
                val children = childrenMethod?.invoke(node, false, false) as? List<*> ?: continue
                children.filterNotNull().forEach(pending::add)
            }
        }
    }

    private fun normalize(text: String): String =
        text.trim().lowercase(Locale.ROOT).replace("\\s+".toRegex(), " ")

    @JvmStatic
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val frontSlot = requestCode == REQUEST_FRONT || requestCode == REQUEST_FRONT_VIDEO
        val backSlot = requestCode == REQUEST_BACK || requestCode == REQUEST_BACK_VIDEO
        val video = requestCode == REQUEST_FRONT_VIDEO || requestCode == REQUEST_BACK_VIDEO
        if (!frontSlot && !backSlot) return false
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            sessions[activity]?.let { it.status.text = it.resources.getString(R.string.befuck_selection_cancelled) }
            return true
        }
        val uri = data.data!!
        val session = sessions[activity] ?: return true
        if (session.isSubmitting || !mediaKindAllowed(session, frontSlot, video)) {
            session.status.text = session.resources.getString(R.string.befuck_media_type_mismatch)
            return true
        }
        if (video) {
            session.isPreparing = true
            updatePostAvailability(session)
            session.status.text = session.resources.getString(R.string.befuck_preparing_image)
            importer.execute {
                val result = runCatching { prepareVideo(activity, uri, if (frontSlot) "front" else "back") }
                mainThread.post { applySelectedMedia(activity, result, frontSlot, video = true, expectedSession = session) }
            }
        } else {
            showPhotoCrop(activity, session, uri, frontSlot)
        }
        return true
    }

    private fun showPhotoCrop(activity: Activity, session: Session, uri: Uri, frontSlot: Boolean) {
        session.isPreparing = true
        updatePostAvailability(session)
        runCatching {
            CropDialog.show(
                activity = activity,
                resources = session.resources,
                imageUri = uri,
                onCropped = { croppedBitmap ->
                    session.isPreparing = true
                    updatePostAvailability(session)
                    session.status.text = session.resources.getString(R.string.befuck_preparing_image)
                    importer.execute {
                        val result = runCatching { prepareCroppedBitmap(activity, croppedBitmap, if (frontSlot) "front" else "back") }
                        mainThread.post { applySelectedMedia(activity, result, frontSlot, video = false, expectedSession = session, photoSource = uri) }
                    }
                },
                onCancelled = {
                    session.isPreparing = false
                    updatePostAvailability(session)
                    session.status.text = session.resources.getString(R.string.befuck_selection_cancelled)
                },
            )
        }.onFailure {
            session.isPreparing = false
            updatePostAvailability(session)
            session.status.text = session.resources.getString(R.string.befuck_image_prepare_error)
            android.util.Log.e("BeFuck/UI", "Could not open photo crop", it)
        }
    }

    @JvmStatic
    fun onRequestPermissionsResult(activity: Activity, requestCode: Int, grantResults: IntArray): Boolean =
        BeFakeLocationDialog.onRequestPermissionsResult(activity, requestCode, grantResults)

    private fun applySelectedMedia(
        activity: Activity,
        result: Result<GalleryMediaFile>,
        frontSlot: Boolean,
        video: Boolean,
        expectedSession: Session,
        photoSource: Uri? = null,
    ) {
        val session = sessions[activity]
        if (session !== expectedSession) {
            result.getOrNull()?.let { discardPreparedMedia(activity, it) }
            return
        }
        result.onSuccess { selected ->
            session.isPreparing = false
            if (!mediaKindAllowed(session, frontSlot, selected.isVideo)) {
                discardPreparedMedia(activity, selected)
                updatePostAvailability(session)
                session.status.text = session.resources.getString(R.string.befuck_media_type_mismatch)
                return@onSuccess
            }
            val previewFile = File(selected.previewPath ?: selected.path)
            if (frontSlot) {
                session.front?.let { discardPreparedMedia(activity, it) }
                session.front = selected
                session.frontPhotoSource = photoSource
                session.frontPreview.setImageURI(Uri.fromFile(previewFile))
                session.frontLabel.text = selectedMediaLabel(session.resources, selected, true)
            } else {
                session.back?.let { discardPreparedMedia(activity, it) }
                session.back = selected
                session.backPhotoSource = photoSource
                session.backPreview.setImageURI(Uri.fromFile(previewFile))
                session.backLabel.text = selectedMediaLabel(session.resources, selected, false)
            }
            updatePostAvailability(session)
            session.status.text = if (mediaTypeMismatch(session)) {
                session.resources.getString(R.string.befuck_media_type_mismatch)
            } else if (session.front != null && session.back != null) {
                session.resources.getString(R.string.befuck_media_ready,
                    session.resources.getString(if (selected.isVideo) R.string.befuck_pick_video else R.string.befuck_pick_photo))
            } else {
                session.resources.getString(R.string.befuck_media_local_status)
            }
        }.onFailure {
            session.isPreparing = false
            updatePostAvailability(session)
            android.util.Log.e("BeFuck/Video", "Could not prepare the selected media", it)
            session.status.text = session.resources.getString(
                if (video) R.string.befuck_video_prepare_error else R.string.befuck_image_prepare_error,
            )
        }
    }

    private fun mediaTypeMismatch(session: Session): Boolean =
        session.front != null && session.back != null && session.front!!.isVideo != session.back!!.isVideo

    private fun mediaKindAllowed(session: Session, front: Boolean, video: Boolean): Boolean =
        (if (front) session.back else session.front)?.let { it.isVideo == video } ?: true

    private fun updatePostAvailability(session: Session) {
        val ready = session.front != null && session.back != null && !mediaTypeMismatch(session) && !session.isSubmitting && !session.isPreparing
        session.postButton?.isEnabled = ready
        session.postButton?.alpha = if (ready) 1f else 0.45f
        val busy = session.isSubmitting || session.isPreparing
        session.mediaButtons.forEach { (request, button) ->
            val front = request == REQUEST_FRONT || request == REQUEST_FRONT_VIDEO
            val video = request == REQUEST_FRONT_VIDEO || request == REQUEST_BACK_VIDEO
            button.isSelected = (if (front) session.front else session.back)?.isVideo == video
            button.isEnabled = !busy && mediaKindAllowed(session, front, video)
            button.alpha = if (button.isEnabled) 1f else 0.3f
        }
        session.frontPreview.isEnabled = !busy && session.front?.isVideo == false
        session.backPreview.isEnabled = !busy && session.back?.isVideo == false
    }

    private fun prepareCroppedBitmap(activity: Activity, bitmap: Bitmap, slot: String): GalleryMediaFile {
        val dir = File(activity.filesDir, "befuck/gallery").apply { mkdirs() }
        val file = File(dir, "${slot}_${UUID.randomUUID()}.webp")
        var completed = false
        try {
            val encodedBytes = encodeWebpUnderLimit(bitmap)
            FileOutputStream(file).use { it.write(encodedBytes) }
            completed = true
            return GalleryMediaFile(
                uri = Uri.fromFile(file).toString(),
                path = file.absolutePath,
                width = bitmap.width,
                height = bitmap.height,
                isVideo = false,
                mimeType = "image/webp",
            )
        } finally {
            bitmap.recycle()
            if (!completed) file.delete()
        }
    }

    private fun open(activity: Activity, resources: Resources, symbols: ResolvedSymbols) {
        val dialog = Dialog(activity)
        val content = FrameLayout(activity).apply { setBackgroundColor(BG) }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            clipToOutline = true
        }
        val scroll = ScrollView(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 20))
        }
        scroll.addView(column, FrameLayout.LayoutParams(-1, -2))
        val windowWidthPx = activity.window?.decorView?.width
            ?.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels
        val screenWidthDp = windowWidthPx / activity.resources.displayMetrics.density
        val sheetWidth = if (screenWidthDp > 600f) dp(activity, 560) else -1
        val screenHeight = activity.window?.decorView?.height
            ?.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.heightPixels
        val sheetHeight = screenHeight
        content.addView(panel, FrameLayout.LayoutParams(sheetWidth, sheetHeight, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 20), dp(activity, 14), dp(activity, 12), dp(activity, 14))
        }
        val titleBlock = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(TextView(activity).apply {
            text = resources.getString(R.string.befuck_title)
            textSize = 24f
            letterSpacing = -0.03f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        titleBlock.addView(TextView(activity).apply {
            text = resources.getString(R.string.befuck_gallery_eyebrow)
            textSize = 10f
            letterSpacing = 0.14f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(MUTED)
        })
        headerRow.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))
        headerRow.addView(
            iconButton(activity, GLYPH_SETTINGS, resources.getString(R.string.befuck_settings_title)) {
                openSettings(activity, resources)
            },
            LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)),
        )
        headerRow.addView(
            iconButton(activity, GLYPH_CLOSE, resources.getString(R.string.befuck_close)) { dialog.dismiss() },
            LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)).apply { marginStart = dp(activity, 8) },
        )
        panel.addView(headerRow)
        panel.addHairline(activity)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val height = min(sheetHeight, bottom - top - content.paddingTop - content.paddingBottom)
            if (height > 0 && panel.layoutParams.height != height) panel.layoutParams = panel.layoutParams.apply { this.height = height }
        }

        column.addView(label(activity, resources.getString(R.string.befuck_intro)).apply { textSize = 12.5f },
            gapParams(activity, 0, 0, 0, 20))

        val downloadRow = rowAction(activity, resources.getString(R.string.befuck_download_recent)) {
            openDownloadDialog(activity, resources)
        }
        column.addHairline(activity)
        column.addView(downloadRow, gapParams(activity, 0, 0, 0, 0))
        column.addHairline(activity)
        column.addView(View(activity), LinearLayout.LayoutParams(-1, dp(activity, 28)))
        column.addView(sectionLabel(activity, resources.getString(R.string.befuck_media_pair), 1), gapParams(activity, 0, 0, 0, 10))
        val mediaRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val frontPreview = mediaPreview(activity)
        val backPreview = mediaPreview(activity)
        val frontLabel = label(activity, resources.getString(R.string.befuck_front_missing)).apply { textSize = 11f }
        val backLabel = label(activity, resources.getString(R.string.befuck_back_missing)).apply { textSize = 11f }
        val mediaButtons = linkedMapOf<Int, Button>()

        fun mediaSlot(
            title: String,
            side: String,
            image: ImageView,
            selected: TextView,
            front: Boolean,
        ): LinearLayout {
            val card = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val imageFrame = FrameLayout(activity).apply {
                background = rounded(activity, SURFACE, RADIUS_DP)
                clipToOutline = true
            }
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.background = null
            image.contentDescription = "$title · ${resources.getString(R.string.befuck_crop_title)}"
            image.setOnClickListener {
                val session = sessions[activity] ?: return@setOnClickListener
                val media = (if (front) session.front else session.back) ?: return@setOnClickListener
                if (media.isVideo || session.isPreparing || session.isSubmitting) return@setOnClickListener
                val source = (if (front) session.frontPhotoSource else session.backPhotoSource) ?: Uri.fromFile(File(media.path))
                showPhotoCrop(activity, session, source, front)
            }
            imageFrame.addView(image, FrameLayout.LayoutParams(-1, -1))
            val chip = TextView(activity).apply {
                text = side
                textSize = 9f
                letterSpacing = 0.14f
                typeface = Typeface.create("monospace", Typeface.BOLD)
                setTextColor(Color.BLACK)
                setPadding(dp(activity, 6), dp(activity, 3), dp(activity, 6), dp(activity, 3))
                background = rounded(activity, Color.WHITE, 6f, Color.WHITE)
            }
            imageFrame.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(activity, 8), dp(activity, 8), 0, 0)
            })
            val mediaWidth = ((if (sheetWidth > 0) sheetWidth else windowWidthPx) - dp(activity, 52)) / 2
            card.addView(imageFrame, LinearLayout.LayoutParams(-1, (mediaWidth * 4f / 3f).toInt()))
            card.addView(TextView(activity).apply {
                text = title
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(0, dp(activity, 10), 0, dp(activity, 2))
            })
            selected.maxLines = 2
            card.addView(selected, LinearLayout.LayoutParams(-1, dp(activity, 32)))
            val choices = LinearLayout(activity).apply {
                background = rounded(activity, BG, RADIUS_DP)
                clipToOutline = true
                setPadding(dp(activity, 1), dp(activity, 1), dp(activity, 1), dp(activity, 1))
            }
            val options = listOf(
                resources.getString(R.string.befuck_pick_photo) to if (front) REQUEST_FRONT else REQUEST_BACK,
                resources.getString(R.string.befuck_pick_video) to if (front) REQUEST_FRONT_VIDEO else REQUEST_BACK_VIDEO,
            )
            options.forEachIndexed { index, (text, request) ->
                if (index > 0) choices.addView(hairline(activity), LinearLayout.LayoutParams(dp(activity, 1), -1))
                choices.addView(secondaryButton(activity, text) {
                    if (sessions[activity]?.isSubmitting != true && sessions[activity]?.isPreparing != true) launchPicker(activity, request)
                }.apply {
                    mediaButtons[request] = this
                    textSize = 12f
                    contentDescription = "$title · $text"
                    background = RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x33808080),
                        StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_selected), ColorDrawable(Color.WHITE))
                            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                        }, ColorDrawable(Color.WHITE),
                    )
                    setTextColor(android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(Color.BLACK, Color.WHITE),
                    ))
                    setPadding(dp(activity, 2), 0, dp(activity, 2), 0)
                }, LinearLayout.LayoutParams(0, dp(activity, 44), 1f))
            }
            card.addView(choices, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 6) })
            return card
        }

        mediaRow.addView(
            mediaSlot(
                resources.getString(R.string.befuck_download_back), "BACK", backPreview, backLabel,
                front = false,
            ),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(activity, 6) },
        )
        mediaRow.addView(
            mediaSlot(
                resources.getString(R.string.befuck_download_front), "FRONT", frontPreview, frontLabel,
                front = true,
            ),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(activity, 6) },
        )
        column.addView(mediaRow, gapParams(activity, 0, 0, 0, 28))

        column.addView(sectionLabel(activity, resources.getString(R.string.befuck_caption), 2), gapParams(activity, 0, 0, 0, 8))
        val caption = EditText(activity).apply {
            hint = resources.getString(R.string.befuck_caption_hint)
            setHintTextColor(0xFF5E5E5E.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 4
            background = rounded(activity, BG, RADIUS_DP)
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
        }
        column.addView(caption, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 28) })

        column.addView(sectionLabel(activity, resources.getString(R.string.befuck_audience), 3), gapParams(activity, 0, 0, 0, 8))
        var selectedAudience = 0
        var refreshAudienceButtons: () -> Unit = {}
        val audienceRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(activity, BG, RADIUS_DP)
            clipToOutline = true
            setPadding(dp(activity, 1), dp(activity, 1), dp(activity, 1), dp(activity, 1))
        }
        val audienceOptions = listOf(
            resources.getString(R.string.befuck_audience_friends),
            resources.getString(R.string.befuck_audience_fof),
            resources.getString(R.string.befuck_audience_public),
        )
        val audienceButtons = audienceOptions.mapIndexed { index, text ->
            TextView(activity).apply {
                this.text = text
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(dp(activity, 4), dp(activity, 6), dp(activity, 4), dp(activity, 6))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedAudience = index
                    refreshAudienceButtons()
                }
            }.also { button ->
                if (index > 0) audienceRow.addView(hairline(activity), LinearLayout.LayoutParams(dp(activity, 1), -1))
                audienceRow.addView(button, LinearLayout.LayoutParams(0, dp(activity, 46), 1f))
            }
        }
        refreshAudienceButtons = {
            audienceButtons.forEachIndexed { index, button ->
                val selected = index == selectedAudience
                button.setTextColor(if (selected) Color.BLACK else Color.WHITE)
                button.background = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(if (selected) 0x33000000 else 0x33FFFFFF),
                    ColorDrawable(if (selected) Color.WHITE else Color.TRANSPARENT),
                    null,
                )
            }
        }
        refreshAudienceButtons()
        column.addView(audienceRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 28) })

        val retakeHeader = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        retakeHeader.addView(sectionLabel(activity, resources.getString(R.string.befuck_retakes), 4), LinearLayout.LayoutParams(0, -2, 1f))
        val retakeCount = TextView(activity).apply {
            text = "00"
            textSize = 16f
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.END
            setTextColor(Color.WHITE)
        }
        retakeHeader.addView(retakeCount)
        column.addView(retakeHeader, gapParams(activity, 0, 0, 0, 4))
        val retakes = SeekBar(activity).apply {
            max = 15
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2E2E2E.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    retakeCount.text = "%02d".format(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        column.addView(retakes, gapParams(activity, 0, 0, 0, 20))

        val late = Switch(activity).apply {
            text = resources.getString(R.string.befuck_post_late)
            textSize = 14f
            setTextColor(Color.WHITE)
            monochromeSwitch(this)
        }
        column.addHairline(activity)
        column.addView(late, LinearLayout.LayoutParams(-1, dp(activity, 54)))
        column.addHairline(activity)

        fun locationText(location: LocationData?): String =
            if (location != null) "%.4f, %.4f".format(location.latitude, location.longitude)
            else resources.getString(R.string.befuck_location_button) + " / " + resources.getString(R.string.befuck_location_none)
        val locationButton = TextView(activity).apply {
            text = locationText(null)
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(activity, 12)
            setCompoundDrawablesRelativeWithIntrinsicBounds(GlyphDrawable(GLYPH_PIN, dp(activity, 20), Color.WHITE), null, null, null)
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(Color.WHITE),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val currentSession = sessions[activity] ?: return@setOnClickListener
                BeFakeLocationDialog.show(activity, resources, currentSession.location) { newLocation ->
                    currentSession.location = newLocation
                    text = locationText(newLocation)
                }
            }
        }
        column.addView(locationButton, LinearLayout.LayoutParams(-1, dp(activity, 54)))
        column.addHairline(activity)
        column.addView(View(activity), LinearLayout.LayoutParams(-1, dp(activity, 20)))

        val status = label(activity, resources.getString(R.string.befuck_media_local_status)).apply {
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(activity, 12), 0, dp(activity, 4))
        }
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), 0, dp(activity, 20), dp(activity, 16))
            setBackgroundColor(BG)
        }
        footer.addHairline(activity)
        footer.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 8) })
        lateinit var postButton: Button
        postButton = primaryButton(activity, resources.getString(R.string.befuck_post_now)) {
            val session = sessions[activity] ?: return@primaryButton
            if (session.isPreparing || session.isSubmitting) return@primaryButton
            val front = session.front
            val back = session.back
            if (front == null || back == null) {
                session.status.text = resources.getString(R.string.befuck_choose_both)
                return@primaryButton
            }
            if (front.isVideo != back.isVideo) {
                session.status.text = resources.getString(R.string.befuck_media_type_mismatch)
                return@primaryButton
            }
            val useOfficialPipeline = front.isVideo || selectedAudience != 0
            if (!useOfficialPipeline && !BeFakeAuthHeaders.hasAuthorization()) {
                session.status.text = resources.getString(R.string.befuck_auth_waiting)
                return@primaryButton
            }
            val post = GalleryPostRequest(
                front = front,
                back = back,
                caption = session.caption.text?.toString().orEmpty(),
                retakeCount = session.retakes.progress,
                isLate = session.late.isChecked,
                visibility = when (selectedAudience) {
                    1 -> "friend-of-friends"
                    2 -> "public"
                    else -> "friends"
                },
                location = session.location,
            )
            session.status.text = resources.getString(R.string.befuck_post_submitting)
            session.isSubmitting = true
            updatePostAvailability(session)
            postButton.text = resources.getString(R.string.befuck_post_submitting)
            postButton.isEnabled = false
            val completion = Consumer<Boolean> { success ->
                mainThread.post {
                    if (success) {
                        if (useOfficialPipeline) {
                            session.keepMediaUntilUpload = true
                        } else {
                            session.front?.let { discardPreparedMedia(activity, it) }
                            session.back?.let { discardPreparedMedia(activity, it) }
                        }
                        GalleryPostController.refreshHomeFeed(activity, symbols.versionName)
                        Toast.makeText(
                            activity,
                            resources.getString(
                                if (useOfficialPipeline) R.string.befuck_post_completed
                                else R.string.befuck_post_accepted,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                        session.dialog.dismiss()
                    } else {
                        session.isSubmitting = false
                        updatePostAvailability(session)
                        postButton.text = resources.getString(R.string.befuck_post_now)
                        session.status.text = resources.getString(R.string.befuck_post_failed)
                        postButton.isEnabled = true
                    }
                }
            }
            if (useOfficialPipeline) {
                if (!GalleryPostController.isReady(symbols)) {
                    session.isSubmitting = false
                    updatePostAvailability(session)
                    postButton.text = resources.getString(R.string.befuck_post_now)
                    session.status.text = resources.getString(R.string.befuck_official_pipeline_unavailable)
                    postButton.isEnabled = true
                    return@primaryButton
                }
                GalleryPostController.postNow(
                    activity,
                    symbols,
                    post,
                    session.pendingOfficialPostId,
                    Consumer { postId ->
                        session.pendingOfficialPostId = postId
                        session.keepMediaUntilUpload = true
                    },
                    completion,
                )
            } else {
                BeFakeUploadController.postNow(activity, post, completion)
            }
        }
        footer.addView(postButton.apply {
            textSize = 16f
            isEnabled = false
            alpha = 0.45f
            minHeight = dp(activity, 54)
        }, gapParams(activity, 0, 2, 0, 12))
        column.addView(
            label(activity, resources.getString(R.string.befuck_footer)).apply { textSize = 11f },
            gapParams(activity, 0, 0, 0, 12),
        )

        panel.addView(footer, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(content)
        dialog.setOnDismissListener {
            sessions.remove(activity)?.let { dismissed ->
            if (!dismissed.keepMediaUntilUpload) {
                dismissed.front?.let { discardPreparedMedia(activity, it) }
                dismissed.back?.let { discardPreparedMedia(activity, it) }
                }
            }
        }
        val session = Session(
            dialog,
            resources,
            frontPreview,
            backPreview,
            frontLabel,
            backLabel,
            caption,
            retakes,
            late,
            status,
        )
        session.postButton = postButton
        session.mediaButtons.putAll(mediaButtons)
        sessions[activity] = session
        updatePostAvailability(session)
        dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            window.setDimAmount(0f)
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                content.setOnApplyWindowInsetsListener { view, insets ->
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
                    val bottom = max(bars.bottom, ime.bottom)
                    view.setPadding(0, bars.top, 0, bottom)
                    panel.layoutParams = panel.layoutParams.apply {
                        height = min(sheetHeight, (view.height.takeIf { it > 0 } ?: screenHeight) - bars.top - bottom)
                    }
                    insets
                }
                content.requestApplyInsets()
            } else {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
    }

    private fun openSettings(activity: Activity, resources: Resources) {
        val dialog = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        val topBar = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 12), dp(activity, 12))
        }
        topBar.addView(TextView(activity).apply {
            text = resources.getString(R.string.befuck_settings_title)
            textSize = 22f
            letterSpacing = -0.02f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(
            iconButton(activity, GLYPH_CLOSE, resources.getString(R.string.befuck_close)) { dialog.dismiss() },
            LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)),
        )
        root.addView(topBar)
        root.addHairline(activity)
        val scroll = ScrollView(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 28))
        }
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val summary = TextView(activity).apply {
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.3f)
            setTextColor(MUTED)
        }
        val toggles = linkedMapOf<String, Switch>()
        column.addView(summary, gapParams(activity, 0, 0, 0, 28))
        fun refresh() {
            summary.text = resources.getString(R.string.befuck_settings_summary,
                RuntimeKnowledge.versionName.orEmpty(), RuntimeKnowledge.versionCode,
                if (RuntimeKnowledge.loadSymbols() != null) resources.getString(R.string.befuck_settings_saved)
                else resources.getString(R.string.befuck_settings_empty), RuntimeKnowledge.repairs().size)
            toggles.forEach { (key, view) -> view.isChecked = RuntimeKnowledge.enabled(key) }
        }
        fun toggle(key: String, title: Int) {
            column.addView(Switch(activity).apply {
                text = resources.getString(title)
                textSize = 14f
                setTextColor(Color.WHITE)
                minHeight = dp(activity, 52)
                isChecked = RuntimeKnowledge.enabled(key)
                monochromeSwitch(this)
                setOnCheckedChangeListener { _, checked -> RuntimeKnowledge.setEnabled(key, checked) }
                toggles[key] = this
            }, gapParams(activity, 0, 0, 0, 0))
            column.addHairline(activity)
        }
        column.addView(sectionLabel(activity, resources.getString(R.string.befuck_settings_title), 1), gapParams(activity, 0, 0, 0, 4))
        column.addHairline(activity)
        toggle("prefer_preset", R.string.befuck_settings_prefer_preset)
        toggle("auto_repair", R.string.befuck_settings_auto_repair)
        toggle("diagnostics", R.string.befuck_settings_diagnostics)
        column.addView(label(activity, resources.getString(R.string.befuck_settings_recovery_hint)).apply { textSize = 12f },
            gapParams(activity, 0, 12, 0, 32))

        fun rowItem(text: String, danger: Boolean = false, action: () -> Unit) {
            column.addView(rowAction(activity, text, danger, action), gapParams(activity, 0, 0, 0, 0))
            column.addHairline(activity)
        }
        fun clearAction(title: Int, action: () -> Unit) = rowItem(resources.getString(title), danger = true) {
            android.app.AlertDialog.Builder(activity).setTitle(resources.getString(title))
                .setMessage(resources.getString(R.string.befuck_settings_clear_hint))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(resources.getString(R.string.befuck_settings_clear)) { _, _ -> action(); refresh() }.show()
        }
        column.addView(sectionLabel(activity, resources.getString(R.string.befuck_settings_add_rule), 2), gapParams(activity, 0, 0, 0, 4))
        column.addHairline(activity)
        rowItem(resources.getString(R.string.befuck_settings_add_rule)) { editRepair(activity, resources) { refresh() } }
        rowItem(resources.getString(R.string.befuck_settings_rules)) { openRepairRules(activity, resources) { refresh() } }
        rowItem(resources.getString(R.string.befuck_settings_test_recovery)) {
            runCatching { RuntimeKnowledge.verifyRecovery() }.onSuccess { passed ->
                refresh()
                Toast.makeText(activity, resources.getString(if (passed) R.string.befuck_settings_test_passed else R.string.befuck_settings_test_failed), Toast.LENGTH_LONG).show()
            }.onFailure {
                RuntimeKnowledge.recordFailure(it)
                Toast.makeText(activity, resources.getString(R.string.befuck_settings_test_failed), Toast.LENGTH_LONG).show()
            }
        }
        rowItem(resources.getString(R.string.befuck_settings_view_log)) {
            val text = RuntimeKnowledge.logFile()?.takeIf { it.isFile }?.readLines()?.takeLast(40)?.joinToString("\n")
                ?: resources.getString(R.string.befuck_settings_no_log)
            val logScroll = ScrollView(activity)
            logScroll.addView(TextView(activity).apply {
                this.text = text
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
            })
            android.app.AlertDialog.Builder(activity).setTitle(resources.getString(R.string.befuck_settings_view_log))
                .setView(logScroll).setPositiveButton(android.R.string.ok, null).show()
        }
        column.addView(label(activity, resources.getString(R.string.befuck_settings_log_path,
            RuntimeKnowledge.logFile()?.absolutePath.orEmpty())).apply {
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF5E5E5E.toInt())
        }, gapParams(activity, 0, 12, 0, 32))

        column.addView(sectionLabel(activity, resources.getString(R.string.befuck_settings_clear), 3), gapParams(activity, 0, 0, 0, 4))
        column.addHairline(activity)
        clearAction(R.string.befuck_settings_clear_symbols) { RuntimeKnowledge.clearSymbols() }
        clearAction(R.string.befuck_settings_clear_repairs) { RuntimeKnowledge.clearRepairs() }
        clearAction(R.string.befuck_settings_clear_all) { RuntimeKnowledge.clearPreferences() }
        refresh()
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.let {
            it.setBackgroundDrawableResource(android.R.color.black)
            it.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
    }

    /** Gray thumb stays visible against the white checked track and the black host background. */
    private fun monochromeSwitch(view: Switch) {
        val d = view.resources.displayMetrics.density
        fun px(v: Int) = (v * d + 0.5f).toInt()
        fun pill(fill: Int, stroke: Int, w: Int, h: Int) = GradientDrawable().apply {
            setColor(fill); cornerRadius = px(h).toFloat(); setSize(px(w), px(h)); setStroke(px(1), stroke)
        }
        fun knob(color: Int) = InsetDrawable(GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color); setSize(px(16), px(16))
        }, px(4))
        view.showText = false
        view.trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), pill(Color.WHITE, Color.WHITE, 44, 24))
            addState(intArrayOf(), pill(BG, MUTED, 44, 24))
        }
        view.thumbDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), knob(0xFF808080.toInt()))
            addState(intArrayOf(), knob(MUTED))
        }
        view.thumbTintList = null
        view.trackTintList = null
    }
    private fun openRepairRules(activity: Activity, resources: Resources, changed: () -> Unit) {
        val dialog = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 20))
        }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(activity, resources.getString(R.string.befuck_settings_rules)).apply {
            textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconButton(activity, GLYPH_CLOSE, resources.getString(R.string.befuck_close)) { dialog.dismiss() },
            LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)))
        root.addView(header)
        val search = EditText(activity).apply {
            hint = resources.getString(R.string.befuck_settings_rule_search)
            setTextColor(Color.WHITE); setHintTextColor(MUTED); textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        root.addView(search)
        root.addView(label(activity, resources.getString(R.string.befuck_settings_rule_edit_hint)), gapParams(activity, 0, 8, 0, 8))
        val scroll = ScrollView(activity)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fun render() {
            list.removeAllViews()
            val query = search.text.toString().trim()
            val rules = RuntimeKnowledge.repairRules().filter {
                query.isEmpty() || "${it.key}\n${it.value}\n${it.reason}".contains(query, ignoreCase = true)
            }
            list.addView(label(activity, resources.getString(R.string.befuck_settings_rule_count, rules.size)), gapParams(activity, 0, 4, 0, 12))
            for (rule in rules) {
                val source = resources.getString(when (rule.source) {
                    "manual" -> R.string.befuck_settings_rule_manual
                    "learned" -> R.string.befuck_settings_rule_learned
                    "preset" -> R.string.befuck_settings_rule_preset
                    else -> R.string.befuck_settings_rule_saved
                })
                val at = rule.updatedAt?.let { text -> runCatching {
                    java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault())
                        .format(java.time.Instant.parse(text))
                }.getOrNull() }
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(activity, 14), 0, dp(activity, 14))
                    background = RippleDrawable(android.content.res.ColorStateList.valueOf(0x33808080), null, ColorDrawable(Color.WHITE))
                }
                row.addView(label(activity, listOfNotNull(source, at).joinToString(" · ")).apply { textSize = 11f })
                row.addView(label(activity, rule.key.replace("#", "\n")).apply {
                    typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(Color.WHITE)
                }, gapParams(activity, 0, 6, 0, 4))
                row.addView(label(activity, rule.value.take(180)).apply { maxLines = 4 })
                if (rule.reason.isNotBlank()) row.addView(label(activity, rule.reason).apply { textSize = 10f }, gapParams(activity, 0, 4, 0, 0))
                row.setOnClickListener { editRepair(activity, resources, rule.key) { render(); changed() } }
                list.addView(row)
                list.addHairline(activity)
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        render()
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.let { window ->
            window.setLayout(-1, -1)
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                root.setOnApplyWindowInsetsListener { view, insets ->
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    val ime = insets.getInsets(WindowInsets.Type.ime())
                    view.setPadding(dp(activity, 20) + bars.left, dp(activity, 12) + bars.top,
                        dp(activity, 20) + bars.right, dp(activity, 20) + max(bars.bottom, ime.bottom))
                    insets
                }
                root.requestApplyInsets()
            }
        }
    }

    private fun editRepair(activity: Activity, resources: Resources, ruleKey: String? = null, saved: () -> Unit) {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 8))
        }
        fun input(hint: Int) = EditText(activity).apply {
            this.hint = resources.getString(hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            column.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val owner = input(R.string.befuck_settings_rule_class)
        val name = input(R.string.befuck_settings_rule_field)
        val value = input(R.string.befuck_settings_rule_value)
        value.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        value.minLines = 2
        value.maxLines = 6
        if (ruleKey != null) {
            owner.setText(ruleKey.substringBeforeLast('#'))
            name.setText(ruleKey.substringAfterLast('#'))
            value.setText(RuntimeKnowledge.repairs()[ruleKey].orEmpty())
            owner.isEnabled = false
            name.isEnabled = false
        }
        val status = label(activity, "")
        column.addView(status)
        val dialog = android.app.AlertDialog.Builder(activity).setTitle(resources.getString(
            if (ruleKey == null) R.string.befuck_settings_add_rule else R.string.befuck_settings_edit_rule))
            .setView(ScrollView(activity).apply { addView(column) }).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(resources.getString(R.string.befuck_settings_save), null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { RuntimeKnowledge.registerRepair(owner.text.toString().trim(), name.text.toString().trim(), value.text.toString()) }
                    .onSuccess { saved(); dialog.dismiss() }
                    .onFailure { status.text = resources.getString(R.string.befuck_settings_rule_error, it.javaClass.simpleName) }
            }
        }
        dialog.show()
    }

    private fun launchPicker(activity: Activity, requestCode: Int) {
        val session = sessions[activity] ?: return
        if (session.isSubmitting || session.isPreparing) return
        val front = requestCode == REQUEST_FRONT || requestCode == REQUEST_FRONT_VIDEO
        val video = requestCode == REQUEST_FRONT_VIDEO || requestCode == REQUEST_BACK_VIDEO
        if (!mediaKindAllowed(session, front, video)) {
            session.status.text = session.resources.getString(R.string.befuck_media_type_mismatch)
            return
        }
        val type = if (requestCode == REQUEST_FRONT_VIDEO || requestCode == REQUEST_BACK_VIDEO) "video/*" else "image/*"
        try {
            activity.startActivityForResult(
                Intent(MediaStore.ACTION_PICK_IMAGES).setType(type),
                requestCode,
            )
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType(type)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    requestCode,
                )
            } catch (_: ActivityNotFoundException) {
                sessions[activity]?.status?.text = sessions[activity]?.resources?.getString(
                    R.string.befuck_photo_picker_unavailable,
                )
            }
        }
    }

    private fun openDownloadDialog(activity: Activity, resources: Resources) {
        val posts = FeedPostMediaCache.recent()
        val dialog = Dialog(activity)
        val content = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        val scroll = ScrollView(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 24))
        }
        scroll.addView(column, FrameLayout.LayoutParams(-1, -2))
        val widthDp = ((activity.window?.decorView?.width
            ?.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels) / activity.resources.displayMetrics.density)
        val panelWidth = if (widthDp > 600f) dp(activity, 560) else -1
        content.addView(scroll, FrameLayout.LayoutParams(panelWidth, -1, Gravity.CENTER))

        column.addView(TextView(activity).apply {
            text = resources.getString(R.string.befuck_download_title)
            textSize = 22f
            letterSpacing = -0.02f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, gapParams(activity, 0, 0, 0, 12))
        val status = label(
            activity,
            if (posts.isEmpty()) resources.getString(R.string.befuck_download_no_posts)
            else resources.getString(R.string.befuck_download_cached_count, posts.size),
        )
        column.addView(status, gapParams(activity, 0, 0, 0, 12))
        column.addView(label(activity, resources.getString(R.string.befuck_cache_url_only)), gapParams(activity, 0, 0, 0, 12))
        val userFilter = EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            hint = resources.getString(R.string.befuck_cache_user_filter)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF5E5E5E.toInt())
            background = rounded(activity, BG, RADIUS_DP)
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
            isSingleLine = true
        }
        column.addView(userFilter, gapParams(activity, 0, 0, 0, 12))
        var selectedDate: String? = null
        val filters = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val dateButton = secondaryButton(activity, resources.getString(R.string.befuck_cache_date_filter)) {}
        filters.addView(dateButton, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
        val allDates = secondaryButton(activity, resources.getString(R.string.befuck_cache_all_dates)) {}
        filters.addView(allDates, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
        column.addView(filters, gapParams(activity, 0, 0, 0, 12))
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list)

        fun renderPosts() {
        list.removeAllViews()
        val matching = posts.filter { post ->
            (selectedDate == null || PostMediaMetadata.dateLabel(post, "yyyy-MM-dd") == selectedDate) &&
                (userFilter.text.isBlank() || post.username.orEmpty().contains(userFilter.text.toString().trim(), ignoreCase = true))
        }
        status.text = resources.getString(R.string.befuck_download_cached_count, matching.size)
        for (post in matching) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
                background = rounded(activity, BG, RADIUS_DP)
            }
            val owner = post.username?.takeIf { it.isNotBlank() } ?: resources.getString(R.string.befuck_cache_unknown_user)
            val date = PostMediaMetadata.dateLabel(post, "yyyy/MM/dd HH:mm:ss")
                ?: resources.getString(R.string.befuck_cache_unknown_date)
            val postLabel = "$owner\n$date"
            card.addView(label(activity, postLabel), gapParams(activity, 0, 0, 0, 8))
            post.caption?.takeIf { it.isNotBlank() }?.let { card.addView(label(activity, it), gapParams(activity, 0, 0, 0, 8)) }
            val cardStatus = label(activity, "").apply { visibility = View.GONE }
            val controls = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            if (post.primary != null) {
                controls.addView(
                    downloadAction(
                        activity,
                        resources.getString(if (post.primary.isVideo) R.string.befuck_download_back_video else R.string.befuck_download_back),
                    ) {
                        downloadMedia(activity, resources, dialog, cardStatus, post, FeedMediaSelection.PRIMARY)
                    },
                    LinearLayout.LayoutParams(0, dp(activity, 44), 1f).apply { marginEnd = dp(activity, 5) },
                )
            }
            if (post.secondary != null) {
                controls.addView(
                    downloadAction(
                        activity,
                        resources.getString(if (post.secondary.isVideo) R.string.befuck_download_front_video else R.string.befuck_download_front),
                    ) {
                        downloadMedia(activity, resources, dialog, cardStatus, post, FeedMediaSelection.SECONDARY)
                    },
                    LinearLayout.LayoutParams(0, dp(activity, 44), 1f).apply { marginEnd = dp(activity, 5) },
                )
            }
            controls.addView(
                downloadAction(activity, resources.getString(R.string.befuck_download_all)) {
                    downloadMedia(activity, resources, dialog, cardStatus, post, FeedMediaSelection.ALL)
                },
                LinearLayout.LayoutParams(0, dp(activity, 44), 1f),
            )
            card.addView(controls)
            card.addView(cardStatus, gapParams(activity, 0, 8, 0, 0))
            list.addView(card, gapParams(activity, 0, 0, 0, 10))
        }
        }
        dateButton.setOnClickListener {
            val date = java.time.LocalDate.now()
            android.app.DatePickerDialog(activity, { _, year, month, day ->
                selectedDate = java.time.LocalDate.of(year, month + 1, day).toString()
                dateButton.text = selectedDate
                renderPosts()
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        allDates.setOnClickListener {
            selectedDate = null
            dateButton.text = resources.getString(R.string.befuck_cache_date_filter)
            renderPosts()
        }
        userFilter.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderPosts() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        renderPosts()

        val close = secondaryButton(activity, resources.getString(R.string.befuck_close)) { dialog.dismiss() }
        column.addView(close, gapParams(activity, 0, 8, 0, 0))
        dialog.setContentView(content)
        dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
    }

    private fun downloadAction(activity: Activity, text: String, action: () -> Unit): Button =
        secondaryButton(activity, text, action).apply { textSize = 12f }

    private fun downloadMedia(
        activity: Activity,
        resources: Resources,
        dialog: Dialog,
        status: TextView,
        post: FeedPostMedia,
        selection: FeedMediaSelection,
    ) {
        status.visibility = View.VISIBLE
        status.text = resources.getString(R.string.befuck_download_in_progress)
        FeedMediaDownloader.download(activity, post, selection, Consumer { result: FeedMediaDownloadResult ->
            mainThread.post {
                if (!dialog.isShowing) return@post
                status.text = if (result.success) {
                    resources.getString(R.string.befuck_download_complete, result.completedCount)
                } else {
                    resources.getString(R.string.befuck_download_failed)
                }
                if (result.success) {
                    Toast.makeText(activity, status.text, Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun prepareImage(activity: Activity, uri: Uri, label: String): GalleryMediaFile {
        val directory = File(activity.filesDir, "befuck/gallery").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val source = File(directory, "$id.source")
        var output: File? = null
        var completed = false
        try {
            activity.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Image provider returned no data" }
                FileOutputStream(source).use { destination -> input.copyTo(destination) }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Selected file is not a readable image" }
            val sample = calculateSample(bounds.outWidth, bounds.outHeight)
            val bitmap = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("Could not decode selected image")
            val oriented = orient(bitmap, source)
            if (oriented !== bitmap) bitmap.recycle()
            val scaled = fit(oriented, 1500, 2000)
            if (scaled !== oriented) oriented.recycle()
            val encoded = try {
                encodeWebpUnderLimit(scaled)
            } finally {
                scaled.recycle()
            }

            val prepared = File(directory, "$label-$id.webp")
            output = prepared
            FileOutputStream(prepared).use { it.write(encoded) }
            val finalBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(prepared.absolutePath, finalBounds)
            require(finalBounds.outWidth > 0 && finalBounds.outHeight > 0) { "Encoded WebP image could not be read back" }
            completed = true
            return GalleryMediaFile(
                uri = Uri.fromFile(prepared).toString(),
                path = prepared.absolutePath,
                width = finalBounds.outWidth,
                height = finalBounds.outHeight,
            )
        } finally {
            source.delete()
            if (!completed) output?.delete()
        }
    }

    private fun prepareVideo(activity: Activity, uri: Uri, camera: String): GalleryMediaFile {
        val mimeType = activity.contentResolver.getType(uri)?.lowercase(Locale.ROOT)
        require(mimeType == null || mimeType == "video/mp4") {
            "BeReal video upload currently accepts MP4 files only"
        }
        val directory = File(activity.filesDir, "befuck/gallery").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val sourceVideo = File(directory, "$camera-video-$id.source.mp4")
        val compressedVideo = File(directory, "$camera-video-$id.mp4")
        var video = sourceVideo
        val thumbnail = File(directory, "$camera-video-$id-preview.webp")
        var completed = false
        try {
            activity.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Video provider returned no data" }
                FileOutputStream(sourceVideo).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_SOURCE_VIDEO_BYTES) { "Video source exceeds the 1 GiB processing limit" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(sourceVideo.length() > 0L) { "Selected video is empty" }
            val originalMetadata = readVideoMetadata(sourceVideo)
            video = Media3VideoCompressor.compressIfNeeded(activity, sourceVideo, compressedVideo, originalMetadata.third)
            if (video !== sourceVideo) sourceVideo.delete()
            require(video.length() <= 512L * 1024L * 1024L) { "Prepared video exceeds the 512 MiB upload limit" }
            // ponytail: BeReal 3.97 rejects otherwise valid videos <=100 KiB. A standard
            // MP4 free atom satisfies its container-size check without changing samples.
            if (video.length() <= 100L * 1024L) {
                val padding = (101L * 1024L - video.length()).toInt()
                java.io.DataOutputStream(FileOutputStream(video, true)).use {
                    it.writeInt(padding)
                    it.writeBytes("free")
                    it.write(ByteArray(padding - 8))
                }
            }
            val metadata = if (video === sourceVideo) originalMetadata else readVideoMetadata(video)

            val previewRetriever = MediaMetadataRetriever()
            try {
                previewRetriever.setDataSource(video.absolutePath)
                val durationUs = metadata.third * 1_000L
                val frame = previewRetriever.getFrameAtTime(min(250_000L, durationUs / 4), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: previewRetriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: error("Could not generate a preview frame for this video")
                val scaled = fit(frame, 900, 900)
                if (scaled !== frame) frame.recycle()
                try {
                    FileOutputStream(thumbnail).use { output ->
                        require(scaled.compress(Bitmap.CompressFormat.WEBP, 84, output)) { "Could not encode the video preview" }
                    }
                } finally {
                    scaled.recycle()
                }
            } finally {
                previewRetriever.release()
            }
            val previewBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(thumbnail.absolutePath, previewBounds)
            require(previewBounds.outWidth > 0 && previewBounds.outHeight > 0) { "Encoded WebP preview could not be read back" }
            completed = true
            return GalleryMediaFile(
                uri = Uri.fromFile(video).toString(),
                path = video.absolutePath,
                width = metadata.first,
                height = metadata.second,
                isVideo = true,
                durationMs = metadata.third,
                previewPath = thumbnail.absolutePath,
                previewWidth = previewBounds.outWidth,
                previewHeight = previewBounds.outHeight,
            )
        } finally {
            if (!completed) {
                sourceVideo.delete()
                compressedVideo.delete()
                thumbnail.delete()
            }
        }
    }

    private fun readVideoMetadata(file: File): Triple<Int, Int, Long> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: error("Video width metadata is missing")
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: error("Video height metadata is missing")
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("Video duration metadata is missing")
            require(durationMs in 1L..30_000L) { "Video must be between 1 ms and 30 seconds" }
            val rotated = rotation == 90 || rotation == 270
            Triple(
                if (rotated) rawHeight else rawWidth,
                if (rotated) rawWidth else rawHeight,
                durationMs,
            )
        } finally {
            retriever.release()
        }
    }

    private fun encodeWebpUnderLimit(bitmap: Bitmap): ByteArray {
        var current = bitmap
        var ownsCurrent = false
        var quality = 90
        try {
            while (true) {
                val output = ByteArrayOutputStream()
                require(current.compress(Bitmap.CompressFormat.WEBP, quality, output)) { "Could not encode selected image as WebP" }
                if (output.size() <= MAX_IMAGE_BYTES) return output.toByteArray()
                if (quality > 50) {
                    quality = (quality - 10).coerceAtLeast(50)
                    continue
                }
                require(max(current.width, current.height) > 480) { "Image remains larger than 1 MiB after resizing" }
                val smaller = Bitmap.createScaledBitmap(
                    current,
                    max(1, current.width * 4 / 5),
                    max(1, current.height * 4 / 5),
                    true,
                )
                if (ownsCurrent) current.recycle()
                current = smaller
                ownsCurrent = true
                quality = 86
            }
        } finally {
            if (ownsCurrent) current.recycle()
        }
    }

    private fun discardPreparedMedia(activity: Activity, media: GalleryMediaFile) {
        val directory = File(activity.filesDir, "befuck/gallery").canonicalFile
        listOfNotNull(media.path, media.previewPath).forEach { path ->
            runCatching {
                val file = File(path).canonicalFile
                if (file.parentFile == directory) file.delete()
            }
        }
    }

    private fun calculateSample(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > 3000 || height / sample > 3000) sample *= 2
        return sample
    }

    private fun orient(bitmap: Bitmap, file: File): Bitmap {
        val orientation = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.setRotate(180f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun fit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val factor = min(1f, min(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height))
        if (factor >= 1f) return bitmap
        val width = max(1, (bitmap.width * factor).toInt())
        val height = max(1, (bitmap.height * factor).toInt())
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    // ---- Design tokens: strictly monochrome, square, hairline ----
    private const val BG = 0xFF000000.toInt()
    private const val SURFACE = 0xFF0B0B0B.toInt()
    private const val LINE = 0xFF2E2E2E
    private const val MUTED = 0xFF8C8C8C.toInt()
    private const val RADIUS_DP = 12f
    private const val GLYPH_CLOSE = 0
    private const val GLYPH_SETTINGS = 1
    private const val GLYPH_ARROW = 2
    private const val GLYPH_PIN = 3

    private fun mediaPreview(activity: Activity): ImageView = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = rounded(activity, SURFACE, RADIUS_DP)
    }

    private fun iconButton(activity: Activity, res: Int, desc: String?, onClick: () -> Unit) =
        ImageButton(activity).apply {
            setImageDrawable(GlyphDrawable(res, dp(activity, 22), Color.WHITE))
            contentDescription = desc
            scaleType = ImageView.ScaleType.CENTER
            background = ripple(activity, BG, RADIUS_DP, 0x33FFFFFF)
            setOnClickListener { onClick() }
        }

    /** List row used in settings: label left, chevron right. */
    private fun rowAction(activity: Activity, text: String, danger: Boolean = false, action: () -> Unit) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 52)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(Color.WHITE),
            )
            setOnClickListener { action() }
            addView(TextView(activity).apply {
                this.text = text
                textSize = 14f
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(activity).apply {
                setImageDrawable(GlyphDrawable(GLYPH_ARROW, dp(activity, 20), if (danger) Color.WHITE else MUTED))
            }, LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20)))
        }

    private fun hairline(activity: Activity) = View(activity).apply { setBackgroundColor(LINE.toInt()) }

    private fun ViewGroup.addHairline(activity: Activity) =
        addView(hairline(activity), LinearLayout.LayoutParams(-1, dp(activity, 1)))


    private fun selectedMediaLabel(resources: Resources, media: GalleryMediaFile, front: Boolean): String {
        if (media.isVideo) {
            return resources.getString(
                R.string.befuck_regular_video_selected,
                media.width,
                media.height,
                media.durationMs ?: 0L,
            )
        }
        return resources.getString(
            if (front) R.string.befuck_front_selected else R.string.befuck_back_selected,
            media.width,
            media.height,
        )
    }

    private fun label(activity: Activity, text: String) = TextView(activity).apply {
        this.text = text
        textSize = 13f
        setLineSpacing(0f, 1.15f)
        setTextColor(MUTED)
    }

    /** Numbered mono caption, e.g. "01  MEDIA PAIR". */
    private fun sectionLabel(activity: Activity, text: String, index: Int? = null) = TextView(activity).apply {
        val number = index?.let { "%02d  ".format(it) }.orEmpty()
        this.text = number + text.uppercase(Locale.ROOT)
        textSize = 10.5f
        letterSpacing = 0.14f
        typeface = Typeface.create("monospace", Typeface.BOLD)
        setTextColor(Color.WHITE)
    }

    private fun secondaryButton(activity: Activity, text: String, action: () -> Unit) = Button(activity).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        stateListAnimator = null
        setTextColor(Color.WHITE)
        background = ripple(activity, BG, RADIUS_DP, 0x33FFFFFF)
        setOnClickListener { action() }
    }

    private fun primaryButton(activity: Activity, text: String, action: () -> Unit) = Button(activity).apply {
        this.text = text
        isAllCaps = false
        stateListAnimator = null
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.04f
        setTextColor(Color.BLACK)
        background = ripple(activity, Color.WHITE, RADIUS_DP, 0x33000000, Color.WHITE)
        setOnClickListener { action() }
    }

    private fun rounded(activity: Activity, color: Int, radiusDp: Float, stroke: Int? = LINE.toInt()) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = minOf(radiusDp, RADIUS_DP) * activity.resources.displayMetrics.density
        if (stroke != null) setStroke(dp(activity, 1), stroke)
    }

    private fun ripple(activity: Activity, color: Int, radiusDp: Float, rippleColor: Int, stroke: Int? = LINE.toInt()) = RippleDrawable(
        android.content.res.ColorStateList.valueOf(rippleColor),
        rounded(activity, color, radiusDp, stroke),
        null,
    )

    private fun gapParams(activity: Activity, left: Int, top: Int, right: Int, bottom: Int) =
        LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(activity, left), dp(activity, top), dp(activity, right), dp(activity, bottom))
        }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun logoHalfWidthWithGap(activity: Activity): Int {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = 24f * activity.resources.displayMetrics.density * activity.resources.configuration.fontScale
        }
        return (paint.measureText("BeReal.") / 2f).toInt() + dp(activity, 6)
    }

    private fun statusBarHeight(activity: Activity): Int {
        val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id == 0) 0 else activity.resources.getDimensionPixelSize(id)
    }

    private fun headerLogoTop(activity: Activity, buttonSize: Int): Int {
        val decor = activity.window?.decorView
        val statusInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decor?.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top
        } else {
            null
        } ?: statusBarHeight(activity)
        return max(0, statusInset + dp(activity, 22) - buttonSize / 2)
    }
}

/** Hand-drawn rounded line icons (resource ids from the injected module are not resolvable in the host app). */
private class GlyphDrawable(private val kind: Int, private val sizePx: Int, private val color: Int) : android.graphics.drawable.Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.9f; color = this@GlyphDrawable.color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF000000.toInt() }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(canvas: android.graphics.Canvas) {
        val s = bounds.width() / 24f
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(s, s)
        when (kind) {
            0 -> { canvas.drawLine(6f, 6f, 18f, 18f, stroke); canvas.drawLine(18f, 6f, 6f, 18f, stroke) }
            1 -> {
                canvas.drawLine(4f, 8f, 20f, 8f, stroke); canvas.drawLine(4f, 16f, 20f, 16f, stroke)
                canvas.drawCircle(9f, 8f, 2.6f, fill); canvas.drawCircle(9f, 8f, 2.6f, stroke)
                canvas.drawCircle(15f, 16f, 2.6f, fill); canvas.drawCircle(15f, 16f, 2.6f, stroke)
            }
            2 -> {
                canvas.drawLine(7f, 17f, 17f, 7f, stroke)
                canvas.drawPath(android.graphics.Path().apply { moveTo(9f, 7f); lineTo(17f, 7f); lineTo(17f, 15f) }, stroke)
            }
            else -> {
                canvas.drawPath(android.graphics.Path().apply {
                    moveTo(12f, 21f)
                    cubicTo(8f, 17f, 5.5f, 13.5f, 5.5f, 10f)
                    arcTo(android.graphics.RectF(5.5f, 3.5f, 18.5f, 16.5f), 180f, 180f)
                    cubicTo(18.5f, 13.5f, 16f, 17f, 12f, 21f)
                    close()
                }, stroke)
                canvas.drawCircle(12f, 10f, 2.4f, stroke)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { stroke.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
