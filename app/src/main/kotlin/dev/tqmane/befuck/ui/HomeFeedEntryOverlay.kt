package dev.tqmane.befuck.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import dev.tqmane.befuck.R
import dev.tqmane.befuck.archive.ArchiveAccounts
import dev.tqmane.befuck.download.FeedPostMediaCache
import dev.tqmane.befuck.runtime.DailySync
import dev.tqmane.befuck.symbols.ResolvedSymbols
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.max

/** Tracks MainActivity lifetime and displays the BeFuck compose entry only on the BeReal home feed. */
internal object HomeFeedEntryOverlay {
    private const val ENTRY_TAG = "dev.tqmane.befuck.entry"
    private val mainThread = Handler(Looper.getMainLooper())
    private val entryTrackers = WeakHashMap<Activity, EntryTracker>()
    private val lastHomeState = WeakHashMap<Activity, Boolean>()
    private var activeActivity = WeakReference<Activity>(null)

    private class EntryTracker(
        private val activity: Activity,
        val root: ViewGroup,
        val resources: Resources,
        private val symbols: ResolvedSymbols?,
        private val onOpenGallery: () -> Unit,
        private val onEnteredHome: (Activity) -> Unit,
        private val onPauseNeeded: (Activity) -> Unit,
    ) : Runnable {
        override fun run() {
            if (activity.isFinishing || activity.isDestroyed || activity.window?.decorView !== root) {
                onPauseNeeded(activity)
                return
            }
            if (symbols != null) ArchiveAccounts.refresh(activity, symbols)
            val onHome = reconcileEntry(activity, root, resources, onOpenGallery)
            if (onHome) onEnteredHome(activity)
            mainThread.postDelayed(this, 900L)
        }
    }

    fun currentActivity(): Activity? = activeActivity.get()

    fun resourcesFor(activity: Activity): Resources? = entryTrackers[activity]?.resources

    fun start(
        activity: Activity,
        resources: Resources?,
        symbols: ResolvedSymbols?,
        onOpenGallery: () -> Unit,
        onEnteredHome: (Activity) -> Unit,
        onPauseNeeded: (Activity) -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        activeActivity = WeakReference(activity)
        FeedPostMediaCache.initialize(activity.applicationContext)
        if (symbols != null) ArchiveAccounts.refresh(activity, symbols, force = true)
        DailySync.reconcile(activity.applicationContext)
        BeFakeLocationDialog.onActivityResume(activity)
        SavedPreviewSession.resume(activity)
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
        val tracker = EntryTracker(activity, root, resources, symbols, onOpenGallery, onEnteredHome, onPauseNeeded)
        entryTrackers[activity] = tracker
        mainThread.post(tracker)
    }

    fun stop(activity: Activity) {
        entryTrackers.remove(activity)?.let(mainThread::removeCallbacks)
        val root = activity.window?.decorView as? ViewGroup
        if (root != null) {
            findEntry(root)?.let(root::removeView)
        }
        lastHomeState.remove(activity)
    }

    fun isHomeFeed(activity: Activity, root: ViewGroup): Boolean {
        val appResources = activity.resources
        val expectedLabels = listOf("general_myFriends", "general_fof").mapNotNull { key ->
            val id = appResources.getIdentifier(key, "string", activity.packageName)
            if (id == 0) null else runCatching { normalize(appResources.getString(id)) }.getOrNull()
        }.filter { it.isNotBlank() }
        if (expectedLabels.size != 2) return false

        val visibleLabels = HashSet<String>()
        val nodeCount = intArrayOf(0)
        collectViewLabels(root, visibleLabels, nodeCount, 0)
        return expectedLabels.all { expected -> visibleLabels.any { it == expected || it.contains(expected) } }
    }

    private fun reconcileEntry(
        activity: Activity,
        root: ViewGroup,
        resources: Resources,
        onOpenGallery: () -> Unit,
    ): Boolean {
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
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            background = null
            elevation = 0f
            setOnClickListener { onOpenGallery() }
        }
        val size = ExpressiveUi.dp(activity, 48)
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

    private fun findEntry(root: ViewGroup): ImageButton? =
        (0 until root.childCount).asSequence()
            .mapNotNull { root.getChildAt(it) as? ImageButton }
            .firstOrNull { it.tag == ENTRY_TAG }

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
                recycleNodeCompat(child)
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
                        recycleNodeCompat(info)
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
            val semanticsOwnerType = Class.forName(
                "androidx.compose.ui.semantics.SemanticsOwner",
                false,
                composeView.javaClass.classLoader,
            )
            val getOwner = composeView.javaClass.methods.singleOrNull { method ->
                method.parameterCount == 0 && method.returnType == semanticsOwnerType
            } ?: return@runCatching
            val owner = getOwner.invoke(composeView) ?: return@runCatching
            val rootNode = owner.javaClass.methods.singleOrNull { method ->
                method.parameterCount == 0 && method.returnType.name == "androidx.compose.ui.semantics.SemanticsNode"
            }?.invoke(owner) ?: return@runCatching
            val pending = ArrayDeque<Any>()
            val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            pending.addLast(rootNode)
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
                    method.parameterTypes.size == 2 &&
                        method.parameterTypes.all { it == Boolean::class.javaPrimitiveType } &&
                        List::class.java.isAssignableFrom(method.returnType)
                }
                val children = childrenMethod?.invoke(node, false, false) as? List<*> ?: continue
                children.filterNotNull().forEach(pending::addLast)
            }
        }
    }

    private fun recycleNodeCompat(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun normalize(text: String): String =
        text.trim().lowercase(Locale.ROOT).replace("\\s+".toRegex(), " ")

    private fun logoHalfWidthWithGap(activity: Activity): Int {
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            textSize = 24f * activity.resources.displayMetrics.density * activity.resources.configuration.fontScale
        }
        return (paint.measureText("BeReal.") / 2f).toInt() + ExpressiveUi.dp(activity, 6)
    }

    @Suppress("DiscouragedApi")
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
        return max(0, statusInset + ExpressiveUi.dp(activity, 22) - buttonSize / 2)
    }
}
