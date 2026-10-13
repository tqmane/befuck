package dev.tqmane.befuck.ui

import android.app.Activity
import android.content.res.Resources
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.tqmane.befuck.R
import dev.tqmane.befuck.download.FeedMediaSelection
import dev.tqmane.befuck.download.FeedPostMedia

internal class InlinePostPreview(activity: Activity, labels: Resources, private val post: FeedPostMedia,
    private val side: FeedMediaSelection, private val source: PreviewSource, private val actions: PostMediaActions,
    private val guard: () -> Boolean, open: () -> Unit) : LinearLayout(activity) {
    private val media = if (side == FeedMediaSelection.PRIMARY) post.primary else post.secondary
    private val label = labels.getString(if (side == FeedMediaSelection.PRIMARY) R.string.befuck_download_back else R.string.befuck_download_front)
    private val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val status = ExpressiveUi.text(activity, labels.getString(R.string.befuck_preview_loading), 12f, ExpressiveUi.SECONDARY).apply {
        gravity = Gravity.CENTER; setPadding(ExpressiveUi.dp(activity, 8), 0, ExpressiveUi.dp(activity, 8), 0)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var generation = 0
    private var request: PreviewCancel? = null
    private var active = false
    private var ended = false
    private val unsubscribe: () -> Unit
    init {
        orientation = VERTICAL
        tag = "preview-tile-${post.postId}-$side"
        val frame = object : FrameLayout(activity) {
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                val height = (width * 1.1f).toInt().coerceIn(ExpressiveUi.dp(context, 112), ExpressiveUi.dp(context, 192))
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            }
        }.apply {
            tag = "preview-open-${post.postId}-$side"
            background = ExpressiveUi.shape(activity, ExpressiveUi.CONTAINER, 20f); clipToOutline = true
            isClickable = media != null; isFocusable = media != null
            contentDescription = labels.getString(R.string.befuck_preview_open, post.username.orEmpty(), label)
            if (media != null) setOnClickListener { if (guard()) open() }
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        frame.addView(status, FrameLayout.LayoutParams(-1, -1))
        frame.addView(ExpressiveUi.text(activity, label, 12f, Color.WHITE, true).apply {
            background = ExpressiveUi.shape(activity, 0xD9000000.toInt(), 12f)
            setPadding(ExpressiveUi.dp(activity, 9), ExpressiveUi.dp(activity, 5), ExpressiveUi.dp(activity, 9), ExpressiveUi.dp(activity, 5))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(ExpressiveUi.dp(activity, 8), ExpressiveUi.dp(activity, 8), 0, 0) })
        frame.addView(ImageView(activity).apply {
            setImageDrawable(TablerIconDrawable(if (media?.isVideo == true) TablerIcon.PLAYER_PLAY else TablerIcon.ARROW_UP_RIGHT, ExpressiveUi.dp(activity, 18), Color.WHITE))
            background = ExpressiveUi.shape(activity, 0xB3000000.toInt(), 12f)
            setPadding(ExpressiveUi.dp(activity, 6), ExpressiveUi.dp(activity, 6), ExpressiveUi.dp(activity, 6), ExpressiveUi.dp(activity, 6))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(ExpressiveUi.dp(activity, 32), ExpressiveUi.dp(activity, 32), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = ExpressiveUi.dp(activity, 8); marginEnd = ExpressiveUi.dp(activity, 8) })
        addView(frame, LayoutParams(-1, -2))
        val save = MaterialControls.button(activity, labels, labels.getString(R.string.befuck_preview_save), false, TablerIcon.DOWNLOAD) { actions.save(post, side) }.apply {
            tag = "preview-save-${post.postId}-$side"
            contentDescription = labels.getString(R.string.befuck_saved_save_camera, post.username.orEmpty(), label)
        }
        addView(save, LayoutParams(-1, -2).apply { topMargin = ExpressiveUi.dp(activity, 8) })
        unsubscribe = actions.watch { save.isEnabled = media != null && !actions.busy(post.postId, side) && guard() }
        if (media == null) status.text = labels.getString(R.string.befuck_preview_missing)
    }
    fun visible(value: Boolean) {
        if (ended || active == value) return
        active = value; val ticket = ++generation
        request?.cancel(); request = null
        image.setImageDrawable(null)
        if (!value || media == null || !guard()) return
        status.visibility = VISIBLE
        status.setText(R.string.befuck_preview_loading) // Replaced with module-resolved text below at integration.
        request = source.image(media, 512) { result ->
            if (ended || !active || ticket != generation || !guard()) return@image
            result.onSuccess { image.setImageBitmap(it); status.visibility = GONE }
                .onFailure { status.text = failureText; status.visibility = VISIBLE }
        }
    }
    private val failureText = labels.getString(R.string.befuck_preview_failed_small)
    private val loadingText = labels.getString(R.string.befuck_preview_loading)
    fun close() { ended = true; ++generation; request?.cancel(); image.setImageDrawable(null); unsubscribe() }
}
