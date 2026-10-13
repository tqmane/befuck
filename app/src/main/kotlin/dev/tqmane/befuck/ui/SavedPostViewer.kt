package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import dev.tqmane.befuck.R
import dev.tqmane.befuck.download.FeedMediaSelection
import dev.tqmane.befuck.download.FeedPostMedia
import dev.tqmane.befuck.download.PostMediaMetadata

/** Full-screen viewer for one post's two cameras. Closing/swiping never changes the source post of a save. */
internal class SavedPostViewer(private val activity: Activity, private val labels: Resources, private val post: FeedPostMedia,
    initial: FeedMediaSelection, private val source: PreviewSource, private val actions: PostMediaActions,
    private val guard: () -> Boolean, private val finished: () -> Unit) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar) {
    private val pages = PostMediaPages(post, initial)
    private val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); tag = "post-viewer" }
    private val main = Handler(Looper.getMainLooper())
    private val photo = PreviewPhotoView(activity).apply { tag = "viewer-photo" }
    private val texture = TextureView(activity).apply { visibility = View.GONE }
    private val stage = CameraSwipeFrame(activity, { pages.pages.size > 1 && !photo.isZoomed }) { left -> if (pages.swipe(left)) bindPage() }.apply { tag = "viewer-swipe" }
    private val state = ExpressiveUi.text(activity, "", 14f, ExpressiveUi.SECONDARY).apply { gravity = Gravity.CENTER; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val saveState = ExpressiveUi.text(activity, "", 13f, ExpressiveUi.SECONDARY).apply { gravity = Gravity.CENTER; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val retry = MaterialControls.button(activity, labels, labels.getString(R.string.befuck_preview_retry), false, TablerIcon.REFRESH) { bindPage() }.apply { tag = "viewer-retry" }
    private val play = MaterialControls.button(activity, labels, labels.getString(R.string.befuck_video_play), false, TablerIcon.PLAYER_PLAY) { playVideo() }.apply { tag = "viewer-play" }
    private val seek = SeekBar(activity).apply { max = 1000; contentDescription = labels.getString(R.string.befuck_preview_seek); minimumHeight = dp(48) }
    private val videoControls = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val pageLabel = ExpressiveUi.text(activity, "", 13f, ExpressiveUi.SECONDARY).apply { gravity = Gravity.CENTER; tag = "viewer-position" }
    private val sideButtons = arrayListOf<MaterialButton>()
    private val previous = ExpressiveUi.iconButton(activity, TablerIcon.CHEVRON_LEFT, labels.getString(R.string.befuck_preview_previous)) { if (pages.swipe(false)) bindPage() }
    private val next = ExpressiveUi.iconButton(activity, TablerIcon.CHEVRON_RIGHT, labels.getString(R.string.befuck_preview_next)) { if (pages.swipe(true)) bindPage() }
    private val save = MaterialControls.button(activity, labels, labels.getString(R.string.befuck_preview_save_current), true, TablerIcon.DOWNLOAD) {
        pages.current?.selection?.let { actions.save(post, it) }
    }.apply { tag = "viewer-save-current" }
    private val saveAll = MaterialControls.button(activity, labels, labels.getString(R.string.befuck_saved_save_all), false, TablerIcon.DOWNLOAD) { actions.save(post, FeedMediaSelection.ALL) }.apply { tag = "viewer-save-all" }
    private var unsubscribe: (() -> Unit)? = null
    private var ended = false
    private var paused = false
    private var generation = 0
    private var imageRequest: PreviewCancel? = null
    private var videoRequest: PreviewCancel? = null
    private var file: PreviewVideo? = null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var playing = false
    private var dragging = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var focus: AudioFocusRequest? = null
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val ticker = object : Runnable {
        override fun run() {
            val current = player ?: return
            if (!valid() || !prepared) return
            runCatching {
                if (!dragging) seek.progress = (current.currentPosition.toLong() * 1000 / current.duration.coerceAtLeast(1)).toInt().coerceIn(0, 1000)
                if (playing) main.postDelayed(this, 250)
            }.onFailure { videoError() }
        }
    }
    init {
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(12), dp(8)) }
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(ExpressiveUi.text(activity, post.username ?: labels.getString(R.string.befuck_cache_unknown_user), 20f, bold = true).apply { maxLines = 2 })
        titles.addView(ExpressiveUi.text(activity, PostMediaMetadata.dateLabel(post, "yyyy/MM/dd HH:mm") ?: labels.getString(R.string.befuck_cache_unknown_date), 12f, ExpressiveUi.SECONDARY))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ExpressiveUi.iconButton(activity, TablerIcon.X, labels.getString(R.string.befuck_close)) { dismiss() }.apply { tag = "viewer-close" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        stage.addView(texture, FrameLayout.LayoutParams(-1, -1))
        stage.addView(photo, FrameLayout.LayoutParams(-1, -1))
        stage.addView(retry, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(12)) }
        footer.addView(state, LinearLayout.LayoutParams(-1, -2))
        val videoRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        videoRow.addView(play, LinearLayout.LayoutParams(-2, -2))
        videoRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        videoControls.addView(videoRow)
        footer.addView(videoControls)
        val switches = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        previous.tag = "viewer-previous"; next.tag = "viewer-next"
        switches.addView(previous, LinearLayout.LayoutParams(dp(48), dp(48)))
        pages.pages.forEachIndexed { index, page ->
            val button = MaterialControls.button(activity, labels, sideName(page.selection)) { if (pages.select(index)) bindPage() }.apply { tag = "viewer-side-${page.selection}" }
            sideButtons += button
            switches.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        switches.addView(next, LinearLayout.LayoutParams(dp(48), dp(48)))
        footer.addView(switches, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        footer.addView(pageLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(6) })
        footer.addView(save, LinearLayout.LayoutParams(-1, -2))
        footer.addView(saveAll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        footer.addView(saveState, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        root.addView(footer)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(bar: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                dragging = false
                if (prepared && valid()) runCatching { player?.let { it.seekTo((it.duration.toLong() * seek.progress / 1000).toInt()) } }.onFailure { videoError() }
            }
        })
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) { startPlayer() }
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) { fitVideo() }
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean { releasePlayer(); return true }
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
        }
        setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_UP) false else when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (pages.swipe(false)) bindPage(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (pages.swipe(true)) bindPage(); true }
                else -> false
            }
        }
        setOnDismissListener { end() }
        unsubscribe = actions.watch { updateSaving() }
    }
    private fun dp(value: Int) = ExpressiveUi.dp(activity, value)
    private fun sideName(selection: FeedMediaSelection) = labels.getString(if (selection == FeedMediaSelection.PRIMARY) R.string.befuck_download_back else R.string.befuck_download_front)
    private fun valid() = !ended && !paused && !activity.isDestroyed && !activity.isFinishing && guard()
    fun showViewer() { ExpressiveUi.showFullScreen(this, root); if (isShowing) bindPage() else end() }
    private fun bindPage() {
        ++generation; imageRequest?.cancel(); imageRequest = null; stopVideo()
        photo.display(null); photo.visibility = View.VISIBLE
        val page = pages.current
        previous.isEnabled = pages.index > 0; next.isEnabled = pages.index + 1 < pages.pages.size
        sideButtons.forEachIndexed { index, button ->
            val selected = index == pages.index
            button.isSelected = selected; button.backgroundTintList = ColorStateList.valueOf(if (selected) Color.WHITE else ExpressiveUi.CONTAINER)
            button.setTextColor(if (selected) Color.BLACK else Color.WHITE)
        }
        updateSaving()
        videoControls.visibility = if (page?.media?.isVideo == true) View.VISIBLE else View.GONE
        play.isEnabled = page != null; seek.isEnabled = false
        pageLabel.text = if (page == null) "" else labels.getString(R.string.befuck_preview_position, pages.index + 1, pages.pages.size, sideName(page.selection))
        photo.contentDescription = page?.let { labels.getString(R.string.befuck_preview_open, post.username.orEmpty(), sideName(it.selection)) }
        retry.visibility = View.GONE
        if (page == null) { state.text = labels.getString(R.string.befuck_preview_missing); return }
        if (!valid()) return
        state.text = labels.getString(R.string.befuck_preview_loading)
        val ticket = generation
        imageRequest = source.image(page.media, 2048) { result ->
            if (!valid() || ticket != generation) return@image
            result.onSuccess {
                photo.display(it)
                state.text = labels.getString(if (page.media.isVideo) R.string.befuck_preview_video_hint else R.string.befuck_preview_photo_hint)
            }.onFailure {
                retry.visibility = View.VISIBLE
                state.text = labels.getString(R.string.befuck_preview_failed)
            }
        }
    }
    private fun updateSaving() {
        val side = pages.current?.selection
        save.isEnabled = side != null && valid() && !actions.busy(post.postId, side)
        saveAll.isEnabled = pages.pages.isNotEmpty() && valid() && !actions.busy(post.postId, FeedMediaSelection.ALL)
        val latest = actions.last?.takeIf { it.postId == post.postId && (it.selection == side || it.selection == FeedMediaSelection.ALL) }
        saveState.text = when {
            side != null && actions.busy(post.postId, side) -> labels.getString(R.string.befuck_download_in_progress)
            latest?.result?.success == true -> labels.getString(R.string.befuck_download_complete, latest.result.completedCount)
            latest?.result != null -> labels.getString(R.string.befuck_download_failed)
            else -> ""
        }
    }
    private fun playVideo() {
        if (!valid() || pages.current?.media?.isVideo != true) return
        if (prepared) {
            if (playing) pausePlayer() else startPlayback()
            return
        }
        if (videoRequest != null) return
        file?.let { texture.visibility = View.VISIBLE; startPlayer(); return }
        val page = pages.current ?: return
        val ticket = generation
        play.isEnabled = false; retry.visibility = View.GONE
        state.text = labels.getString(R.string.befuck_preview_video_loading)
        videoRequest = source.video(page.media) { result ->
            if (!valid() || ticket != generation) { result.getOrNull()?.close(); return@video }
            videoRequest = null
            result.onSuccess { file = it; texture.visibility = View.VISIBLE; startPlayer() }
                .onFailure { videoError() }
        }
    }
    private fun startPlayer() {
        if (!valid() || !texture.isAvailable || file == null || player != null) return
        val current = MediaPlayer()
        player = current
        surface = Surface(requireNotNull(texture.surfaceTexture))
        current.setOnPreparedListener {
            if (!valid() || player !== current) return@setOnPreparedListener
            prepared = true; play.isEnabled = true; seek.isEnabled = true
            fitVideo(); startPlayback()
        }
        current.setOnVideoSizeChangedListener { _, width, height -> if (player === current) { videoWidth = width; videoHeight = height; fitVideo() } }
        current.setOnInfoListener { _, what, _ ->
            if (valid() && player === current && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) photo.visibility = View.GONE
            false
        }
        current.setOnCompletionListener { if (player === current && valid()) { pausePlayer(); seek.progress = 1000 } }
        current.setOnErrorListener { _, _, _ -> if (player === current) videoError(); true }
        runCatching {
            current.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            current.setDataSource(requireNotNull(file).file.absolutePath); current.setSurface(surface); current.prepareAsync()
        }.onFailure { videoError() }
    }
    private fun fitVideo() {
        val w = texture.width; val h = texture.height
        if (w <= 0 || h <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val scale = minOf(w.toFloat() / videoWidth, h.toFloat() / videoHeight)
        texture.setTransform(Matrix().apply { setScale(videoWidth * scale / w, videoHeight * scale / h, w / 2f, h / 2f) })
    }
    private fun startPlayback() {
        if (!valid() || !prepared) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener({ change -> if (change < 0) pausePlayer() }, main).build()
        if (audio != null && audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            state.text = labels.getString(R.string.befuck_preview_audio_unavailable); return
        }
        focus = request
        runCatching {
            player?.let { if (it.currentPosition >= it.duration) it.seekTo(0); it.start() }
            playing = true; play.text = labels.getString(R.string.befuck_video_pause)
            play.icon = TablerIconDrawable(TablerIcon.PLAYER_PAUSE, dp(20), Color.WHITE)
            state.text = labels.getString(R.string.befuck_preview_swipe_hint)
            main.removeCallbacks(ticker); main.post(ticker)
        }.onFailure { videoError() }
    }
    private fun pausePlayer() {
        if (prepared) runCatching { player?.pause() }
        playing = false; main.removeCallbacks(ticker)
        focus?.let { audio?.abandonAudioFocusRequest(it) }; focus = null
        play.text = labels.getString(R.string.befuck_video_play); play.icon = TablerIconDrawable(TablerIcon.PLAYER_PLAY, dp(20), Color.WHITE)
    }
    private fun releasePlayer() {
        pausePlayer(); prepared = false
        val current = player; player = null; runCatching { current?.release() }
        surface?.release(); surface = null; videoWidth = 0; videoHeight = 0; seek.isEnabled = false
    }
    private fun stopVideo() {
        videoRequest?.cancel(); videoRequest = null; releasePlayer()
        file?.close(); file = null; texture.visibility = View.GONE; seek.progress = 0
    }
    private fun videoError() {
        stopVideo(); photo.visibility = View.VISIBLE
        if (valid()) { play.isEnabled = true; state.text = labels.getString(R.string.befuck_preview_video_failed) }
    }
    fun pauseMedia() {
        if (ended) return
        paused = true; ++generation; imageRequest?.cancel(); imageRequest = null
        stopVideo(); photo.display(null); updateSaving()
    }
    fun resumeMedia() { if (!ended && paused) { paused = false; bindPage() } }
    private fun end() {
        if (ended) return
        ended = true; ++generation; imageRequest?.cancel(); imageRequest = null
        stopVideo(); photo.display(null); unsubscribe?.invoke(); unsubscribe = null
        finished()
    }
    override fun dismiss() { end(); super.dismiss() }
}
