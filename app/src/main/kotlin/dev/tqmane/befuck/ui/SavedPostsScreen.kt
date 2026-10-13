package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.Resources
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import dev.tqmane.befuck.R
import dev.tqmane.befuck.download.FeedMediaDownloader
import dev.tqmane.befuck.download.FeedMediaSelection
import dev.tqmane.befuck.download.FeedPostMedia
import dev.tqmane.befuck.download.FeedPostMediaCache
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.function.Consumer

/** One scrolling timeline: month calendar first, then posts from the selected local day. */
internal object SavedPostsScreen {
    private val reads = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private const val PAGE = 40

    fun show(activity: Activity, resources: Resources,
             loadPosts: () -> List<FeedPostMedia> = { FeedPostMediaCache.recent() },
             execute: Executor = reads, clock: Clock = Clock.systemDefaultZone(),
             previewSource: PreviewSource? = null, previewActions: PostMediaActions? = null): Dialog {
        var closeNow: () -> Unit = {}
        val dialog = object : Dialog(activity, android.R.style.Theme_Black_NoTitleBar) {
            override fun dismiss() {
                // onDismiss is delivered through a Handler; cancel before that message can be delayed.
                closeNow()
                super.dismiss()
            }
        }
        val uiContext = ModuleUiContext(activity, resources)
        val locale = resources.configuration.locales[0] ?: Locale.getDefault()
        val model = SavedCalendarModel(LocalDate.now(clock))
        val accountScope = dev.tqmane.befuck.archive.ArchiveAccounts.snapshot()
        val largeText = activity.resources.configuration.fontScale >= 1.5f
        var closed = false
        var generation = 0
        var task: FutureTask<Unit>? = null
        var visibleLimit = PAGE
        var hasLoaded = false
        var unassignedCount = 0
        var loadFailed = false
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 12), dp(activity, 12))
        }
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(ExpressiveUi.text(activity, resources.getString(R.string.befuck_download_title), 24f, bold = true))
        val status = ExpressiveUi.text(activity, "", 13f, ExpressiveUi.SECONDARY).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        titles.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 4) })
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        val refresh = ExpressiveUi.iconButton(activity, TablerIcon.REFRESH, resources.getString(R.string.befuck_saved_refresh)) {}.apply { tag = "saved-refresh" }
        header.addView(refresh, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)))
        header.addView(ExpressiveUi.iconButton(activity, TablerIcon.X, resources.getString(R.string.befuck_close)) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)))
        root.addView(header)
        val controls = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(activity, 16), 0, dp(activity, 16), dp(activity, 8)) }
        val searchRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; background = ExpressiveUi.shape(activity, ExpressiveUi.CONTAINER) }
        searchRow.addView(android.widget.ImageView(activity).apply {
            setImageDrawable(TablerIconDrawable(TablerIcon.SEARCH, dp(activity, 22), ExpressiveUi.SECONDARY))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24)).apply { marginStart = dp(activity, 16) })
        val search = EditText(activity).apply {
            tag = "saved-search"; hint = resources.getString(R.string.befuck_cache_user_filter); contentDescription = hint
            setSingleLine(true); textSize = 16f; setTextColor(Color.WHITE); setHintTextColor(ExpressiveUi.SECONDARY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = null; minimumHeight = dp(activity, 52)
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 16), dp(activity, 12))
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(searchRow)
        val tabs = LinearLayout(activity).apply { orientation = if (largeText) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val calendarTab = action(uiContext, resources.getString(R.string.befuck_calendar_tab), false, TablerIcon.CALENDAR) {}.apply { tag = "saved-calendar-tab" }
        val allTab = action(uiContext, resources.getString(R.string.befuck_cache_all_dates), false, TablerIcon.LIST_SEARCH) {}.apply { tag = "saved-all-tab" }
        tabs.addView(calendarTab, if (largeText) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(allTab, (if (largeText) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f)).apply { if (largeText) topMargin = dp(activity, 8) else marginStart = dp(activity, 8) })
        controls.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 8) })
        val backupButton = action(uiContext, resources.getString(R.string.befuck_archive_title), false, TablerIcon.ARROWS_EXCHANGE) {}.apply { tag = "archive-backup-entry" }
        controls.addView(backupButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 8) })
        val legacyNotice = ExpressiveUi.text(activity, resources.getString(R.string.befuck_archive_unassigned_notice), 13f, ExpressiveUi.SECONDARY).apply {
            visibility = View.GONE; setPadding(dp(activity,4),dp(activity,8),dp(activity,4),0)
        }
        controls.addView(legacyNotice)
        root.addView(controls)
        val scroll = ScrollView(activity).apply { tag = "saved-media-scroll" }
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(activity, 16), dp(activity, 4), dp(activity, 16), dp(activity, 24)) }
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val calendarArea = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(calendarArea)
        val dayBar = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(activity, 4), dp(activity, 20), dp(activity, 4), dp(activity, 12)) }
        val dayTitle = ExpressiveUi.text(activity, "", 19f, bold = true).apply { tag = "saved-day-heading"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        dayBar.addView(dayTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val count = ExpressiveUi.text(activity, "", 13f, ExpressiveUi.SECONDARY).apply { tag = "saved-day-count"; setPadding(dp(activity, 8), 0, 0, 0) }
        dayBar.addView(count)
        column.addView(dayBar)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; tag = "saved-day-posts" }
        column.addView(list)
        column.addView(ExpressiveUi.text(activity, resources.getString(R.string.befuck_preview_network_notice), 12f, ExpressiveUi.SECONDARY),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 12) })
        fun active() = !closed && !activity.isDestroyed && !activity.isFinishing && dev.tqmane.befuck.archive.ArchiveAccounts.snapshot() == accountScope
        val previews = SavedPreviewSession(activity, resources, root, ::active,
            previewSource ?: NetworkPreviewSource(activity, guard = { previewCheck(active()) }),
            previewActions ?: PostMediaActions(::active) { post, side, done ->
                FeedMediaDownloader.download(activity.applicationContext, post, side, Consumer { result -> main.post { done(result) } })
            })
        fun tab(button: MaterialButton, selected: Boolean) {
            button.isSelected = selected
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(if (selected) Color.WHITE else ExpressiveUi.CONTAINER)
            val color = android.content.res.ColorStateList.valueOf(if (selected) Color.BLACK else Color.WHITE)
            button.setTextColor(color); button.iconTint = color
        }
        fun renderPosts() {
            if (!active()) return
            val posts = model.visible
            previews.clearRows()
            list.removeAllViews()
            calendarArea.visibility = if (model.mode == SavedCalendarModel.Mode.CALENDAR) View.VISIBLE else View.GONE
            tab(calendarTab, model.mode == SavedCalendarModel.Mode.CALENDAR); tab(allTab, model.mode == SavedCalendarModel.Mode.ALL)
            dayTitle.text = if (model.mode == SavedCalendarModel.Mode.ALL) resources.getString(R.string.befuck_cache_all_dates)
                else model.selectedDate.format(DateTimeFormatter.ofPattern(if (locale.language == "ja") "yyyy/M/d（E）" else "EEE, MMM d, yyyy", locale))
            count.text = resources.getString(R.string.befuck_calendar_count, posts.size)
            legacyNotice.visibility = if (unassignedCount > 0) View.VISIBLE else View.GONE
            if (hasLoaded) status.text = resources.getString(R.string.befuck_download_cached_count, model.matching.size)
            if (!hasLoaded) {
                count.text = ""
                list.addView(ExpressiveUi.heading(activity, resources.getString(
                    if (loadFailed) R.string.befuck_saved_load_failed else R.string.befuck_saved_loading)))
                return
            }
            if (posts.isEmpty()) {
                val empty = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    background = ExpressiveUi.shape(activity)
                    setPadding(dp(activity, 24), dp(activity, 32), dp(activity, 24), dp(activity, 32))
                    tag = "saved-empty-day"
                }
                empty.addView(android.widget.ImageView(activity).apply {
                    setImageDrawable(TablerIconDrawable(TablerIcon.CALENDAR, dp(activity, 32), ExpressiveUi.SECONDARY))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(activity, 32), dp(activity, 32)))
                empty.addView(ExpressiveUi.text(activity, resources.getString(if (model.mode == SavedCalendarModel.Mode.CALENDAR) R.string.befuck_calendar_empty_day else R.string.befuck_saved_empty), 16f, bold = true).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 14) })
                empty.addView(ExpressiveUi.text(activity, resources.getString(R.string.befuck_calendar_cached_only), 13f, ExpressiveUi.SECONDARY).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 8) })
                list.addView(empty)
                return
            }
            posts.take(visibleLimit).forEach { post ->
                val card = LinearLayout(activity).apply {
                    tag = "saved-post-${post.postId}"
                    orientation = LinearLayout.VERTICAL; background = ExpressiveUi.shape(activity)
                    setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16))
                }
                val owner = post.username?.takeIf(String::isNotBlank) ?: resources.getString(R.string.befuck_cache_unknown_user)
                val ownerRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                ownerRow.addView(ExpressiveUi.text(activity, owner, 17f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                val timestamp = model.entries.firstOrNull { it.post.postId == post.postId }?.timestamp
                val pattern = if (model.mode == SavedCalendarModel.Mode.CALENDAR) "HH:mm" else "yyyy/MM/dd HH:mm"
                val time = timestamp?.let { DateTimeFormatter.ofPattern(pattern, locale).withZone(clock.zone).format(Instant.ofEpochMilli(it)) }
                    ?: resources.getString(R.string.befuck_cache_unknown_date)
                ownerRow.addView(ExpressiveUi.text(activity, time, 13f, ExpressiveUi.SECONDARY).apply { setPadding(dp(activity, 10), 0, 0, 0) })
                card.addView(ownerRow)
                post.caption?.takeIf(String::isNotBlank)?.let { caption ->
                    card.addView(ExpressiveUi.text(activity, caption, 14f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 10) })
                }
                val cardStatus = ExpressiveUi.text(activity, "", 13f, ExpressiveUi.SECONDARY).apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
                val actions = LinearLayout(activity).apply { orientation = if (activity.resources.configuration.fontScale >= 1.5f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
                listOf(FeedMediaSelection.PRIMARY, FeedMediaSelection.SECONDARY).forEachIndexed { index, side ->
                    val params = if (actions.orientation == LinearLayout.VERTICAL) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f)
                    if (index > 0) { if (actions.orientation == LinearLayout.VERTICAL) params.topMargin = dp(activity, 12) else params.marginStart = dp(activity, 12) }
                    actions.addView(previews.tile(post, side), params)
                }
                card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 16) })
                val all = action(uiContext, resources.getString(R.string.befuck_saved_save_all), true, TablerIcon.DOWNLOAD) {}
                all.setOnClickListener { previews.downloads.save(post, FeedMediaSelection.ALL) }
                previews.watch {
                    all.isEnabled = active() && !previews.downloads.busy(post.postId, FeedMediaSelection.ALL)
                    val state = previews.downloads.last?.takeIf { it.postId == post.postId }
                    cardStatus.visibility = if (state == null) View.GONE else View.VISIBLE
                    cardStatus.text = when {
                        state == null -> ""
                        state.result == null -> resources.getString(R.string.befuck_download_in_progress)
                        state.result.success -> resources.getString(R.string.befuck_download_complete, state.result.completedCount)
                        state.result.completedCount > 0 -> resources.getString(R.string.befuck_saved_partial, state.result.completedCount)
                        else -> resources.getString(R.string.befuck_download_failed)
                    }
                }
                card.addView(all, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 8) })
                card.addView(cardStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 8) })
                list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 12) })
            }
            if (posts.size > visibleLimit) list.addView(action(uiContext, resources.getString(R.string.befuck_saved_more), false) { visibleLimit += PAGE; renderPosts() })
        }
        val calendar = PostCalendarView(activity, resources, model, {
            visibleLimit = PAGE; renderPosts()
            (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(search.windowToken, 0)
            search.clearFocus()
        }, { LocalDate.now(clock) })
        val calendarCenter = FrameLayout(activity)
        val limitedCalendar = WidthLimitedFrame(activity, 560).apply { addView(calendar, FrameLayout.LayoutParams(-1, -2)) }
        calendarCenter.addView(limitedCalendar, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        calendarArea.addView(calendarCenter)
        val legendRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; orientation = if (largeText) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        legendRow.addView(ExpressiveUi.text(activity, resources.getString(R.string.befuck_calendar_legend), 12f, ExpressiveUi.SECONDARY), if (largeText) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
        legendRow.addView(action(uiContext, resources.getString(R.string.befuck_calendar_today), false) {
            model.select(LocalDate.now(clock)); calendar.returnToDays(); visibleLimit = PAGE; renderPosts()
        }.apply { tag = "calendar-today" }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; if (largeText) topMargin = dp(activity, 6) })
        calendarArea.addView(legendRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 8) })
        val renderLater = Runnable { model.query = search.text.toString(); visibleLimit = PAGE; calendar.render(); renderPosts() }
        fun load() {
            task?.cancel(true)
            loadFailed = false
            val ticket = ++generation
            status.text = resources.getString(R.string.befuck_saved_loading)
            task = FutureTask<Unit> {
                val result = runCatching { loadPosts().also { unassignedCount = dev.tqmane.befuck.archive.ArchiveRepository.unassignedCount() } }
                main.post {
                    if (!active() || ticket != generation) return@post
                    result.onSuccess { posts ->
                        model.replace(posts, clock.zone); model.query = search.text.toString()
                        hasLoaded = true; visibleLimit = PAGE; calendar.render(); renderPosts()
                    }.onFailure { loadFailed = true; renderPosts(); status.text = resources.getString(R.string.befuck_saved_load_failed) }
                }
            }.also { future -> runCatching { execute.execute(future) }.onFailure { loadFailed = true; renderPosts(); status.text = resources.getString(R.string.befuck_saved_load_failed) } }
        }
        refresh.setOnClickListener { load() }
        backupButton.setOnClickListener { ArchiveBackupUi.show(activity, resources) { if (active()) load() } }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { model.query = s?.toString().orEmpty(); main.removeCallbacks(renderLater); main.postDelayed(renderLater, 150) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        allTab.setOnClickListener { model.query = search.text.toString(); model.showAll(); visibleLimit = PAGE; renderPosts(); scroll.scrollTo(0, 0) }
        calendarTab.setOnClickListener { model.query = search.text.toString(); model.showCalendar(); calendar.render(); visibleLimit = PAGE; renderPosts(); scroll.scrollTo(0, 0) }
        closeNow = {
            if (!closed) {
                closed = true; ++generation; task?.cancel(true); main.removeCallbacks(renderLater); previews.close()
            }
        }
        val unsubscribeAccount = dev.tqmane.befuck.archive.ArchiveAccounts.subscribe {
            main.post { if (dev.tqmane.befuck.archive.ArchiveAccounts.snapshot() != accountScope) dialog.dismiss() }
        }
        val previousClose = closeNow
        closeNow = { previousClose(); unsubscribeAccount() }
        dialog.setOnDismissListener { closeNow() }
        BoundedScreen.show(activity, dialog, root)
        if (dialog.isShowing) { renderPosts(); load(); previews.update() } else previews.close()
        return dialog
    }

    private fun action(context: ModuleUiContext, label: String, primary: Boolean, icon: TablerIcon? = null, action: () -> Unit) =
        MaterialControls.button(context, context.resources, label, primary, icon, action)
    private fun dp(activity: Activity, value: Int) = ExpressiveUi.dp(activity, value)
}
