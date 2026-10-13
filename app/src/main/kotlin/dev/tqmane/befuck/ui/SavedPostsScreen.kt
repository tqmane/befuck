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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tqmane.befuck.R
import dev.tqmane.befuck.download.FeedMediaDownloader
import dev.tqmane.befuck.download.FeedMediaSelection
import dev.tqmane.befuck.download.FeedPostMedia
import dev.tqmane.befuck.download.FeedPostMediaCache
import dev.tqmane.befuck.download.PostMediaMetadata
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.function.Consumer

/** URL-only cache browser: database reads off main, bounded row inflation, no automatic remote previews. */
internal object SavedPostsScreen {
    private val reads = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private const val PAGE = 40

    fun show(activity: Activity, resources: Resources): Dialog {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 12), dp(activity, 12))
        }
        header.addView(ExpressiveUi.text(activity, resources.getString(R.string.befuck_download_title), 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        val refresh = ExpressiveUi.iconButton(activity, TablerIcon.REFRESH, resources.getString(R.string.befuck_saved_refresh)) {}
        header.addView(refresh, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)))
        header.addView(ExpressiveUi.iconButton(activity, TablerIcon.X, resources.getString(R.string.befuck_close)) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)))
        root.addView(header)
        val filters = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(activity, 16), 0, dp(activity, 16), dp(activity, 12)) }
        val search = EditText(activity).apply {
            hint = resources.getString(R.string.befuck_cache_user_filter); contentDescription = hint
            textSize = 16f; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(ExpressiveUi.SECONDARY)
            minimumHeight = dp(activity, 52); background = ExpressiveUi.shape(activity, ExpressiveUi.CONTAINER)
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12))
        }
        filters.addView(search)
        val dates = LinearLayout(activity)
        val date = ExpressiveUi.button(activity, resources.getString(R.string.befuck_cache_date_filter)) {}
        val all = ExpressiveUi.button(activity, resources.getString(R.string.befuck_cache_all_dates)) {}
        dates.addView(date, LinearLayout.LayoutParams(0, -2, 1f))
        dates.addView(all, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(activity, 8) })
        filters.addView(dates, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 8) })
        val status = ExpressiveUi.text(activity, "", 13f, ExpressiveUi.SECONDARY).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        filters.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 10) })
        root.addView(filters)
        val scroll = ScrollView(activity)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(activity, 16), 0, dp(activity, 16), dp(activity, 24)) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        var closed = false
        var generation = 0
        var task: Future<*>? = null
        var snapshot = emptyList<FeedPostMedia>()
        var selectedDate: String? = null
        var visibleLimit = PAGE
        fun active() = !closed && !activity.isDestroyed && !activity.isFinishing
        fun save(post: FeedPostMedia, selection: FeedMediaSelection, cardStatus: TextView) {
            cardStatus.visibility = View.VISIBLE
            cardStatus.text = resources.getString(R.string.befuck_download_in_progress)
            FeedMediaDownloader.download(activity, post, selection, Consumer { result -> main.post {
                if (!active()) return@post
                cardStatus.text = resources.getString(if (result.success) R.string.befuck_download_complete else R.string.befuck_download_failed, result.completedCount)
            } })
        }
        fun render() {
            if (!active()) return
            val query = search.text.toString().trim()
            val matching = snapshot.filter { post ->
                (selectedDate == null || PostMediaMetadata.dateLabel(post, "yyyy-MM-dd") == selectedDate) &&
                    (query.isEmpty() || post.username.orEmpty().contains(query, ignoreCase = true))
            }
            list.removeAllViews()
            status.text = resources.getString(R.string.befuck_download_cached_count, matching.size)
            if (matching.isEmpty()) {
                list.addView(ExpressiveUi.heading(activity, resources.getString(R.string.befuck_saved_empty), resources.getString(R.string.befuck_cache_url_only)))
                return
            }
            matching.take(visibleLimit).forEach { post ->
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL; background = ExpressiveUi.shape(activity)
                    setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16))
                }
                val owner = post.username?.takeIf(String::isNotBlank) ?: resources.getString(R.string.befuck_cache_unknown_user)
                card.addView(ExpressiveUi.text(activity, owner, 17f, bold = true))
                card.addView(ExpressiveUi.text(activity, PostMediaMetadata.dateLabel(post, "yyyy/MM/dd HH:mm:ss") ?: resources.getString(R.string.befuck_cache_unknown_date), 13f, ExpressiveUi.SECONDARY))
                post.caption?.takeIf(String::isNotBlank)?.let { caption ->
                    card.addView(ExpressiveUi.text(activity, caption, 14f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 12) })
                }
                val cardStatus = ExpressiveUi.text(activity, "", 13f, ExpressiveUi.SECONDARY).apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
                val actions = LinearLayout(activity).apply { orientation = if (activity.resources.configuration.fontScale >= 1.5f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
                listOf(post.primary to FeedMediaSelection.PRIMARY, post.secondary to FeedMediaSelection.SECONDARY).forEach { (media, selection) ->
                    if (media != null) {
                        val title = resources.getString(if (selection == FeedMediaSelection.PRIMARY) R.string.befuck_download_back else R.string.befuck_download_front)
                        val button = ExpressiveUi.button(activity, title, icon = if (media.isVideo) TablerIcon.VIDEO else TablerIcon.PHOTO) { save(post, selection, cardStatus) }
                        val params = if (actions.orientation == LinearLayout.VERTICAL) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f)
                        if (actions.childCount > 0) { if (actions.orientation == LinearLayout.VERTICAL) params.topMargin = dp(activity, 8) else params.marginStart = dp(activity, 8) }
                        actions.addView(button, params)
                    }
                }
                card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 16) })
                card.addView(ExpressiveUi.button(activity, resources.getString(R.string.befuck_download_all), true, TablerIcon.DOWNLOAD) { save(post, FeedMediaSelection.ALL, cardStatus) },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 8) })
                card.addView(cardStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 8) })
                list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 12) })
            }
            if (matching.size > visibleLimit) list.addView(ExpressiveUi.button(activity, resources.getString(R.string.befuck_saved_more)) { visibleLimit += PAGE; render() })
        }
        val renderLater = Runnable { visibleLimit = PAGE; render() }
        fun load() {
            task?.cancel(true)
            val ticket = ++generation
            status.text = resources.getString(R.string.befuck_saved_loading)
            task = reads.submit {
                val result = runCatching { FeedPostMediaCache.recent() }
                main.post {
                    if (!active() || ticket != generation) return@post
                    result.onSuccess { posts -> snapshot = posts; visibleLimit = PAGE; render() }
                        .onFailure { status.text = resources.getString(R.string.befuck_saved_load_failed) }
                }
            }
        }
        refresh.setOnClickListener { load() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { main.removeCallbacks(renderLater); main.postDelayed(renderLater, 150) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        all.setOnClickListener { selectedDate = null; date.text = resources.getString(R.string.befuck_cache_date_filter); visibleLimit = PAGE; render() }
        date.setOnClickListener {
            val choices = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val entries = snapshot.mapNotNull { PostMediaMetadata.dateLabel(it, "yyyy-MM-dd") }.distinct().sortedDescending()
            var chosen = selectedDate
            val input = EditText(activity).apply { setText(chosen.orEmpty()); hint = "YYYY-MM-DD"; setTextColor(Color.WHITE); setHintTextColor(ExpressiveUi.SECONDARY); setSingleLine(true) }
            choices.addView(input)
            // A few recent dates are shortcuts; arbitrary dates stay available through the ISO date field.
            entries.take(7).forEach { day -> choices.addView(ExpressiveUi.row(activity, day, TablerIcon.LIST_SEARCH) { input.setText(day) }) }
            ExpressiveDialogs.form(activity, resources.getString(R.string.befuck_cache_date_filter), null, choices,
                resources.getString(R.string.befuck_crop_done), resources.getString(R.string.befuck_close)) {
                chosen = input.text.toString().trim().takeIf(String::isNotEmpty)
                val valid = chosen == null || runCatching { java.time.LocalDate.parse(chosen) }.isSuccess
                if (valid) { selectedDate = chosen; date.text = chosen ?: resources.getString(R.string.befuck_cache_date_filter); visibleLimit = PAGE; render(); true }
                else { input.error = resources.getString(R.string.befuck_saved_date_invalid); false }
            }
        }
        dialog.setOnDismissListener { closed = true; ++generation; task?.cancel(true); main.removeCallbacks(renderLater) }
        ExpressiveUi.showFullScreen(dialog, root)
        load()
        return dialog
    }

    private fun dp(activity: Activity, value: Int) = ExpressiveUi.dp(activity, value)
}
