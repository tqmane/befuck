package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.tqmane.befuck.R
import dev.tqmane.befuck.posting.VideoEdit
import dev.tqmane.befuck.posting.CropRegion
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/** The source stays untouched; only confirmed crop and time coordinates leave this dialog. */
class VideoCropDialog(
    activity: Activity,
    private val labels: Resources,
    private val source: File,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val sourceDurationMs: Long,
    initial: VideoEdit?,
    private val otherAvailableMs: Long?,
    private val onEdited: (VideoEdit) -> Unit,
    onCancelled: () -> Unit,
) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
    private val main = Handler(Looper.getMainLooper())
    private val frames = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private var accepted = false
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var videoFrameReady = false
    private var playing = false
    private var poster: Bitmap? = null
    private var updatingFields = false
    private var inputValid = true
    private val posterRequest = AtomicLong()
    private val maximumLength = min(VideoEdit.MAX_DURATION_MS, otherAvailableMs ?: VideoEdit.MAX_DURATION_MS)
    private val initialEdit = (initial ?: VideoEdit.initial(sourceDurationMs).copy(crop = CropRegion.portrait(sourceWidth, sourceHeight))).let {
        it.withDuration(min(it.durationMs, maximumLength)).validateSource(sourceDurationMs)
    }
    private var startMs = initialEdit.startMs
    private var endMs = initialEdit.endMs
    private val crop = TouchCropView(activity)
    private val texture = TextureView(activity)
    private val timeline = VideoTimelineView(activity, sourceDurationMs, startMs, endMs, maximumLength)
    private val summary = TextView(activity)
    private val play = Button(activity)
    private val done = Button(activity)
    private val startInput = EditText(activity)
    private val lengthInput = EditText(activity)

    private val ticker = object : Runnable {
        override fun run() {
            val current = player ?: return
            if (!prepared || closed) return
            val position = current.currentPosition.toLong()
            if (playing && (position >= endMs || position < startMs)) {
                current.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
            }
            timeline.playheadMs = position.coerceIn(startMs, endMs)
            if (playing) main.postDelayed(this, 60)
        }
    }

    init {
        require(sourceWidth > 0 && sourceHeight > 0 && sourceDurationMs > 0)
        window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F0F12.toInt())
        }
        val toolbar = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        toolbar.addView(button(R.string.befuck_close) { dismiss() })
        toolbar.addView(TextView(activity).apply {
            text = labels.getString(R.string.befuck_video_edit_title)
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        done.text = labels.getString(R.string.befuck_crop_done)
        styleButton(done)
        done.isEnabled = false
        done.setOnClickListener {
            if (!inputValid || poster == null || !crop.hasCropRegion) return@setOnClickListener
            val edit = VideoEdit(startMs, endMs, crop.cropRegion()).validateSource(sourceDurationMs)
            accepted = true
            dismiss()
            onEdited(edit)
        }
        toolbar.addView(done)
        root.addView(toolbar)
        root.addView(TextView(activity).apply {
            text = labels.getString(R.string.befuck_video_crop_hint)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(dp(16), dp(4), dp(16), dp(8))
        })

        crop.setAspectRatio(VideoEdit.ASPECT_RATIO)
        crop.onTransformChanged = {
            texture.setTransform(crop.videoMatrix(texture.width, texture.height))
            done.isEnabled = inputValid && prepared && crop.hasCropRegion
        }
        val preview = FrameLayout(activity)
        preview.addView(texture, FrameLayout.LayoutParams(-1, -1))
        preview.addView(crop, FrameLayout.LayoutParams(-1, -1))
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            setBackgroundColor(0xFF1B1B1F.toInt())
        }
        val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        styleButton(play)
        play.text = labels.getString(R.string.befuck_video_play)
        play.isEnabled = false
        play.setOnClickListener {
            val current = player ?: return@setOnClickListener
            if (!prepared || !inputValid) return@setOnClickListener
            if (playing) pausePreview() else {
                playing = true
                if (current.currentPosition.toLong() !in startMs until endMs) current.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                current.start()
                crop.drawImage = !videoFrameReady
                crop.invalidate()
                play.text = labels.getString(R.string.befuck_video_pause)
                main.post(ticker)
            }
        }
        actions.addView(play, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button(R.string.befuck_crop_rotate) { crop.rotate90() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button(R.string.befuck_crop_reset) { crop.reset() }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(actions)
        controls.addView(summary.apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        controls.addView(timeline, LinearLayout.LayoutParams(-1, dp(64)))
        controls.addView(TextView(activity).apply {
            text = labels.getString(R.string.befuck_video_trim_hint)
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        })
        val inputRow = LinearLayout(activity)
        fun addInput(input: EditText, label: Int) {
            input.id = View.generateViewId()
            input.setTextColor(Color.WHITE)
            input.textSize = 16f
            input.minHeight = dp(48)
            input.setSingleLine()
            input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            input.contentDescription = labels.getString(label)
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(TextView(activity).apply {
                text = labels.getString(label)
                setTextColor(0xFFCCCCCC.toInt())
                labelFor = input.id
            })
            column.addView(input, LinearLayout.LayoutParams(-1, -2))
            inputRow.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        }
        addInput(startInput, R.string.befuck_video_start_seconds)
        addInput(lengthInput, R.string.befuck_video_length_seconds)
        controls.addView(inputRow)
        root.addView(controls)
        setContentView(root)

        timeline.onRangeChanged = { start, end, previewEnd ->
            startMs = start
            endMs = end
            inputValid = true
            updateRange(true)
            seekPreview(if (previewEnd) end - 1 else start)
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updatingFields) return
                fun milliseconds(input: EditText): Long? = input.text.toString().replace(',', '.').toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it >= 0 && it <= sourceDurationMs / 1000.0 }
                    ?.let { (it * 1000).roundToLong() }
                val start = milliseconds(startInput)
                val length = milliseconds(lengthInput)
                inputValid = start != null && length != null && length in 1..maximumLength &&
                    start < sourceDurationMs && length <= sourceDurationMs - start
                lengthInput.error = if (inputValid) null else labels.getString(R.string.befuck_video_invalid_range, maximumLength / 1000.0)
                if (inputValid) {
                    startMs = requireNotNull(start)
                    endMs = startMs + requireNotNull(length)
                    updateRange(false)
                    seekPreview(startMs)
                }
                done.isEnabled = inputValid && prepared && poster != null
            }
        }
        startInput.addTextChangedListener(watcher)
        lengthInput.addTextChangedListener(watcher)
        updateRange(true)

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                if (closed) return
                surface = Surface(value)
                videoFrameReady = false
                crop.drawImage = true
                player = MediaPlayer().also { current ->
                    current.setOnPreparedListener {
                        if (closed) return@setOnPreparedListener
                        prepared = true
                        play.isEnabled = true
                        done.isEnabled = inputValid && poster != null
                        current.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
                        texture.setTransform(crop.videoMatrix(texture.width, texture.height))
                    }
                    current.setOnSeekCompleteListener { main.post(ticker) }
                    current.setOnInfoListener { _, what, _ ->
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                            videoFrameReady = true
                            if (playing) { crop.drawImage = false; crop.invalidate() }
                        }
                        false
                    }
                    current.setOnCompletionListener {
                        if (playing) { current.seekTo(startMs, MediaPlayer.SEEK_CLOSEST); current.start() }
                    }
                    current.setOnErrorListener { _, _, _ ->
                        pausePreview()
                        prepared = false
                        play.isEnabled = false
                        done.isEnabled = false
                        summary.text = labels.getString(R.string.befuck_video_prepare_error)
                        true
                    }
                    runCatching {
                        current.setDataSource(source.absolutePath)
                        current.setSurface(surface)
                        current.prepareAsync()
                    }.onFailure { summary.text = labels.getString(R.string.befuck_video_prepare_error) }
                }
            }
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) {
                texture.setTransform(crop.videoMatrix(width, height))
            }
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) {
                // Surface creation also updates an empty texture. Keep the poster until a decoded frame exists.
                if (playing && videoFrameReady && crop.drawImage) { crop.drawImage = false; crop.invalidate() }
            }
        }
        setOnDismissListener {
            closed = true
            main.removeCallbacks(ticker)
            releasePlayer()
            frames.shutdownNow()
            poster?.recycle()
            poster = null
            timeline.releaseFrames()
            if (!accepted) onCancelled()
        }
        setOnShowListener {
            window?.let { window ->
                window.setLayout(-1, -1)
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                if (Build.VERSION.SDK_INT >= 30) {
                    window.setDecorFitsSystemWindows(false)
                    root.setOnApplyWindowInsetsListener { view, insets ->
                        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                        insets
                    }
                    root.requestApplyInsets()
                } else root.fitsSystemWindows = true
            }
            loadFrames()
        }
    }

    fun pausePreview() {
        val wasPlaying = playing
        playing = false
        main.removeCallbacks(ticker)
        if (prepared) runCatching { player?.pause() }
        crop.drawImage = true
        crop.invalidate()
        if (wasPlaying && !closed) runCatching { player?.currentPosition?.toLong() }.getOrNull()?.let(::requestPoster)
        play.text = labels.getString(R.string.befuck_video_play)
    }

    private fun releasePlayer() {
        pausePreview()
        prepared = false
        player?.release()
        player = null
        surface?.release()
        surface = null
    }

    private fun seekPreview(position: Long) {
        pausePreview()
        if (prepared) player?.seekTo(position.coerceIn(0, sourceDurationMs - 1), MediaPlayer.SEEK_CLOSEST)
        timeline.playheadMs = position
        requestPoster(position)
    }

    private fun updateRange(updateInputs: Boolean) {
        if (updateInputs) {
            updatingFields = true
            startInput.setText(String.format(Locale.US, "%.3f", startMs / 1000.0))
            lengthInput.setText(String.format(Locale.US, "%.3f", (endMs - startMs) / 1000.0))
            lengthInput.error = null
            updatingFields = false
        }
        timeline.setRange(startMs, endMs)
        summary.text = labels.getString(R.string.befuck_video_range_summary,
            time(startMs), time(endMs), time(sourceDurationMs), time(endMs - startMs))
        done.isEnabled = inputValid && prepared && poster != null
    }

    private fun loadFrames() {
        frames.execute {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source.absolutePath)
                val scale = min(1f, 1280f / maxOf(sourceWidth, sourceHeight))
                val bitmap = requireNotNull(retriever.getScaledFrameAtTime(startMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST,
                    (sourceWidth * scale).toInt().coerceAtLeast(1), (sourceHeight * scale).toInt().coerceAtLeast(1)))
                main.post {
                    if (closed) bitmap.recycle() else {
                        poster = bitmap
                        crop.setImageBitmap(bitmap)
                        crop.setCropRegion(initialEdit.crop)
                        done.isEnabled = inputValid && prepared
                    }
                }
                for (index in 0 until 8) {
                    if (closed || Thread.currentThread().isInterrupted) break
                    val position = (sourceDurationMs - 1) * index / 7
                    val frame = retriever.getScaledFrameAtTime(position * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        160, (160f * sourceHeight / sourceWidth).toInt().coerceIn(1, 320)) ?: continue
                    main.post { if (closed) frame.recycle() else timeline.addFrame(index, frame) }
                }
            } catch (failure: Throwable) {
                android.util.Log.w("BeFuck/Video", "Could not load video editor frames", failure)
                main.post { if (!closed && poster == null) summary.text = labels.getString(R.string.befuck_video_prepare_error) }
            } finally { retriever.release() }
        }
    }

    private fun requestPoster(position: Long) {
        if (closed) return
        val request = posterRequest.incrementAndGet()
        frames.execute {
            if (closed || request != posterRequest.get()) return@execute
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source.absolutePath)
                val scale = min(1f, 1280f / maxOf(sourceWidth, sourceHeight))
                val bitmap = retriever.getScaledFrameAtTime(position.coerceIn(0, sourceDurationMs - 1) * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST, (sourceWidth * scale).toInt().coerceAtLeast(1),
                    (sourceHeight * scale).toInt().coerceAtLeast(1)) ?: return@execute
                main.post {
                    if (closed || request != posterRequest.get()) bitmap.recycle() else {
                        val region = if (poster != null && crop.width > 0) crop.cropRegion() else initialEdit.crop
                        val previous = poster
                        poster = bitmap
                        crop.setImageBitmap(bitmap)
                        crop.setCropRegion(region)
                        previous?.recycle()
                    }
                }
            } catch (failure: Throwable) {
                android.util.Log.w("BeFuck/Video", "Could not seek video preview", failure)
            } finally { retriever.release() }
        }
    }

    private fun button(label: Int, action: () -> Unit) = Button(context).apply {
        text = labels.getString(label)
        styleButton(this)
        setOnClickListener { action() }
    }

    private fun styleButton(button: Button) {
        button.setTextColor(Color.WHITE)
        button.textSize = 14f
        button.isAllCaps = false
        button.minWidth = dp(88)
        button.minHeight = dp(48)
        button.gravity = Gravity.CENTER
        button.setPadding(dp(16), dp(8), dp(16), dp(8))
        button.includeFontPadding = false
        button.background = GradientDrawable().apply { setColor(0xFF29292E.toInt()); cornerRadius = dp(12).toFloat() }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun time(ms: Long) = String.format(Locale.getDefault(), "%d:%06.3f", ms / 60_000, ms % 60_000 / 1000.0)
}

/** Drag either edge to trim, or the highlighted interval to move it without changing length. */
private class VideoTimelineView(context: Context, private val durationMs: Long, private var startMs: Long,
                                private var endMs: Long, private val maximumLength: Long) : View(context) {
    var onRangeChanged: ((Long, Long, Boolean) -> Unit)? = null
    var playheadMs = startMs
        set(value) { field = value; invalidate() }
    private val frames = arrayOfNulls<Bitmap>(8)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    private var drag = 0
    private var downX = 0f
    private var downStart = startMs
    private var downEnd = endMs
    private val handle = 12f * resources.displayMetrics.density

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO } // Numeric start/length fields provide the accessible equivalent.

    fun setRange(start: Long, end: Long) { startMs = start; endMs = end; invalidate() }
    fun addFrame(index: Int, frame: Bitmap) { frames[index]?.recycle(); frames[index] = frame; invalidate() }
    fun releaseFrames() { frames.forEach { it?.recycle() }; frames.fill(null) }
    private fun x(time: Long) = handle + (width - 2 * handle) * (time.toDouble() / durationMs).toFloat()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF29292E.toInt())
        val cell = (width - handle * 2) / frames.size
        frames.forEachIndexed { index, bitmap ->
            if (bitmap != null) {
                bounds.set(handle + cell * index, 0f, handle + cell * (index + 1), height.toFloat())
                val source = if (bitmap.width.toFloat() / bitmap.height > cell / height) {
                    val w = (bitmap.height * cell / height).toInt().coerceAtLeast(1)
                    Rect((bitmap.width - w) / 2, 0, (bitmap.width + w) / 2, bitmap.height)
                } else {
                    val h = (bitmap.width * height / cell).toInt().coerceAtLeast(1)
                    Rect(0, (bitmap.height - h) / 2, bitmap.width, (bitmap.height + h) / 2)
                }
                canvas.drawBitmap(bitmap, source, bounds, paint)
            }
        }
        val left = x(startMs)
        val right = x(endMs)
        paint.color = 0xA6000000.toInt()
        canvas.drawRect(0f, 0f, left, height.toFloat(), paint)
        canvas.drawRect(right, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * resources.displayMetrics.density
        canvas.drawRect(left, 1f, right, height - 1f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(left - handle / 2, 0f, left + handle / 2, height.toFloat(), handle / 3, handle / 3, paint)
        canvas.drawRoundRect(right - handle / 2, 0f, right + handle / 2, height.toFloat(), handle / 3, handle / 3, paint)
        paint.color = 0xFFFFD166.toInt()
        canvas.drawRect(x(playheadMs) - 1, 0f, x(playheadMs) + 1, height.toFloat(), paint)
        paint.color = Color.WHITE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= handle * 2) return false
        val minimum = minOf(100L, durationMs, endMs - startMs)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x; downStart = startMs; downEnd = endMs
                val leftDistance = abs(event.x - x(startMs))
                val rightDistance = abs(event.x - x(endMs))
                drag = when {
                    leftDistance <= handle * 2 || rightDistance <= handle * 2 -> if (leftDistance <= rightDistance) 1 else 2
                    event.x > x(startMs) && event.x < x(endMs) -> 3
                    else -> if (event.x < x(startMs)) 1 else 2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val position = (((event.x - handle) / (width - 2 * handle)) * durationMs).roundToLong().coerceIn(0, durationMs)
                when (drag) {
                    1 -> startMs = position.coerceIn(maxOf(0, endMs - maximumLength), endMs - minimum)
                    2 -> endMs = position.coerceIn(startMs + minimum, min(durationMs, startMs + maximumLength))
                    3 -> {
                        val delta = ((event.x - downX) / (width - handle * 2) * durationMs).roundToLong()
                        startMs = (downStart + delta).coerceIn(0, durationMs - (downEnd - downStart))
                        endMs = startMs + downEnd - downStart
                    }
                }
                onRangeChanged?.invoke(startMs, endMs, drag == 2)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
