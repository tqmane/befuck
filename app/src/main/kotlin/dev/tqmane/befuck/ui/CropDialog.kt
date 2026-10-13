package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import dev.tqmane.befuck.R
import dev.tqmane.befuck.posting.CropRegion
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/** Shared touch crop geometry; photos default to 3:4, videos provide their own ratio. */
class TouchCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var originalBitmap: Bitmap? = null
    private val currentMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private val cropRect = RectF()
    private val bitmapRect = RectF()
    private val transformedBitmapRect = RectF()

    private var rotationAngle = 0
    private var baseScale = 1.0f
    private var currentScale = 1.0f
    private var aspectRatio = 3f / 4f
    private var pendingRegion: CropRegion? = null
    var drawImage = true
    var onTransformChanged: (() -> Unit)? = null
    val hasCropRegion: Boolean get() = originalBitmap?.isRecycled == false && !cropRect.isEmpty

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val maskPaint = Paint().apply {
        color = 0xB3000000.toInt()
        style = Paint.Style.FILL
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val path = Path()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!detector.scaleFactor.isFinite() || detector.scaleFactor <= 0) return true
            val newScale = (currentScale * detector.scaleFactor).coerceIn(baseScale, baseScale * 6f)
            currentMatrix.postScale(newScale / currentScale, newScale / currentScale, detector.focusX, detector.focusY)
            currentScale = newScale
            clampMatrix()
            invalidate()
            return true
        }
    })

    fun setImageBitmap(bitmap: Bitmap) {
        originalBitmap = bitmap
        rotationAngle = 0
        bitmapRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        setupInitialMatrix()
        invalidate()
    }

    fun setAspectRatio(ratio: Float) {
        require(ratio.isFinite() && ratio > 0)
        aspectRatio = ratio
        calculateCropRect(width, height)
        setupInitialMatrix()
        invalidate()
    }

    fun setCropRegion(region: CropRegion) {
        pendingRegion = region
        if (!cropRect.isEmpty) applyRegion(region)
    }

    fun cropRegion(): CropRegion {
        if (cropRect.isEmpty) pendingRegion?.let { return it }
        check(!cropRect.isEmpty && originalBitmap != null)
        val bounds = RectF(bitmapRect).also(currentMatrix::mapRect)
        return CropRegion(
            ((cropRect.left - bounds.left) / bounds.width()).coerceIn(0f, 1f),
            ((cropRect.top - bounds.top) / bounds.height()).coerceIn(0f, 1f),
            ((cropRect.right - bounds.left) / bounds.width()).coerceIn(0f, 1f),
            ((cropRect.bottom - bounds.top) / bounds.height()).coerceIn(0f, 1f), rotationAngle,
        )
    }

    fun videoMatrix(viewWidth: Int, viewHeight: Int): Matrix = Matrix(currentMatrix).apply {
        if (viewWidth > 0 && viewHeight > 0) preScale(bitmapRect.width() / viewWidth, bitmapRect.height() / viewHeight)
    }

    private fun applyRegion(region: CropRegion) {
        if (originalBitmap == null) return
        if (cropRect.isEmpty) return
        rotationAngle = region.rotationDegrees
        setupInitialMatrix()
        currentMatrix.mapRect(transformedBitmapRect, bitmapRect)
        val scale = cropRect.width() / (transformedBitmapRect.width() * (region.right - region.left))
        currentMatrix.postScale(scale, scale)
        currentScale *= scale
        currentMatrix.mapRect(transformedBitmapRect, bitmapRect)
        currentMatrix.postTranslate(
            cropRect.left - transformedBitmapRect.left - region.left * transformedBitmapRect.width(),
            cropRect.top - transformedBitmapRect.top - region.top * transformedBitmapRect.height(),
        )
        clampMatrix()
        pendingRegion = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val region = pendingRegion ?: if (!cropRect.isEmpty && originalBitmap != null) cropRegion() else null
        pendingRegion = region
        calculateCropRect(w, h)
        setupInitialMatrix()
        region?.let(::applyRegion)
        onTransformChanged?.invoke()
    }

    private fun calculateCropRect(w: Int, h: Int) {
        if (w <= 0 || h <= 0) { cropRect.setEmpty(); return }
        val padding = dp(20f)
        val availableWidth = w - padding * 2
        val availableHeight = h - padding * 2
        if (availableWidth <= 0 || availableHeight <= 0) { cropRect.setEmpty(); return }

        val targetRatio = aspectRatio
        var cropW = availableWidth
        var cropH = cropW / targetRatio

        if (cropH > availableHeight) {
            cropH = availableHeight
            cropW = cropH * targetRatio
        }

        val left = (w - cropW) / 2f
        val top = (h - cropH) / 2f
        cropRect.set(left, top, left + cropW, top + cropH)
    }

    private fun setupInitialMatrix() {
        val bitmap = originalBitmap ?: return
        if (cropRect.isEmpty) return

        currentMatrix.reset()
        val rotated = rotationAngle % 180 != 0
        val bw = if (rotated) bitmap.height.toFloat() else bitmap.width.toFloat()
        val bh = if (rotated) bitmap.width.toFloat() else bitmap.height.toFloat()

        val scaleX = cropRect.width() / bw
        val scaleY = cropRect.height() / bh
        baseScale = max(scaleX, scaleY)
        currentScale = baseScale

        currentMatrix.postRotate(rotationAngle.toFloat(), bitmap.width / 2f, bitmap.height / 2f)

        val srcPoints = floatArrayOf(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        val rotatedPoints = FloatArray(4)
        val rotMatrix = Matrix().apply { postRotate(rotationAngle.toFloat(), bitmap.width / 2f, bitmap.height / 2f) }
        rotMatrix.mapPoints(rotatedPoints, srcPoints)

        val mappedRect = RectF()
        rotMatrix.mapRect(mappedRect, bitmapRect)

        currentMatrix.postScale(baseScale, baseScale, bitmap.width / 2f, bitmap.height / 2f)

        val scaledRect = RectF()
        currentMatrix.mapRect(scaledRect, bitmapRect)

        val dx = cropRect.centerX() - scaledRect.centerX()
        val dy = cropRect.centerY() - scaledRect.centerY()
        currentMatrix.postTranslate(dx, dy)

        clampMatrix()
    }

    fun rotate90() {
        pendingRegion = null
        rotationAngle = (rotationAngle + 90) % 360
        setupInitialMatrix()
        invalidate()
    }

    fun reset() {
        pendingRegion = null
        rotationAngle = 0
        setupInitialMatrix()
        invalidate()
    }

    private fun clampMatrix() {
        val bitmap = originalBitmap ?: return
        if (cropRect.isEmpty) return

        currentMatrix.mapRect(transformedBitmapRect, bitmapRect)

        var deltaX = 0f
        var deltaY = 0f

        if (transformedBitmapRect.width() < cropRect.width()) {
            val scale = cropRect.width() / transformedBitmapRect.width()
            currentMatrix.postScale(scale, scale, cropRect.centerX(), cropRect.centerY())
            currentMatrix.mapRect(transformedBitmapRect, bitmapRect)
        }
        if (transformedBitmapRect.height() < cropRect.height()) {
            val scale = cropRect.height() / transformedBitmapRect.height()
            currentMatrix.postScale(scale, scale, cropRect.centerX(), cropRect.centerY())
            currentMatrix.mapRect(transformedBitmapRect, bitmapRect)
        }

        if (transformedBitmapRect.left > cropRect.left) {
            deltaX = cropRect.left - transformedBitmapRect.left
        } else if (transformedBitmapRect.right < cropRect.right) {
            deltaX = cropRect.right - transformedBitmapRect.right
        }

        if (transformedBitmapRect.top > cropRect.top) {
            deltaY = cropRect.top - transformedBitmapRect.top
        } else if (transformedBitmapRect.bottom < cropRect.bottom) {
            deltaY = cropRect.bottom - transformedBitmapRect.bottom
        }

        currentMatrix.postTranslate(deltaX, deltaY)
        currentMatrix.getValues(matrixValues)
        currentScale = kotlin.math.hypot(matrixValues[Matrix.MSCALE_X], matrixValues[Matrix.MSKEW_Y])
        onTransformChanged?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || originalBitmap == null) return false
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    currentMatrix.postTranslate(dx, dy)
                    clampMatrix()
                    invalidate()
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> isDragging = false
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastTouchX = event.getX(remaining)
                lastTouchY = event.getY(remaining)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = originalBitmap ?: return
        if (cropRect.isEmpty) return

        canvas.save()
        if (drawImage) canvas.drawBitmap(bitmap, currentMatrix, null)
        canvas.restore()

        // Mask outside cropRect
        path.reset()
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRect(cropRect, Path.Direction.CCW)
        canvas.drawPath(path, maskPaint)

        // Grid lines
        val stepX = cropRect.width() / 3f
        val stepY = cropRect.height() / 3f
        canvas.drawLine(cropRect.left + stepX, cropRect.top, cropRect.left + stepX, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left + stepX * 2, cropRect.top, cropRect.left + stepX * 2, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + stepY, cropRect.right, cropRect.top + stepY, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + stepY * 2, cropRect.right, cropRect.top + stepY * 2, gridPaint)

        // Frame border
        canvas.drawRect(cropRect, framePaint)
    }

    internal class Snapshot(private val bitmap: Bitmap, private val matrix: Matrix, private val bounds: RectF) {
        fun render(width: Int, height: Int): Bitmap {
            require(width > 0 && height > 0 && width.toLong() * height <= 12_000_000L)
            check(!bitmap.isRecycled && !bounds.isEmpty)
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val transform = Matrix(matrix)
                transform.postTranslate(-bounds.left, -bounds.top)
                transform.postScale(width / bounds.width(), height / bounds.height())
                Canvas(result).drawBitmap(bitmap, transform, Paint(Paint.FILTER_BITMAP_FLAG))
                return result
            } catch (failure: Throwable) { result.recycle(); throw failure }
        }
    }
    internal fun snapshot(): Snapshot? = originalBitmap?.takeIf { !it.isRecycled && !cropRect.isEmpty }
        ?.let { Snapshot(it, Matrix(currentMatrix), RectF(cropRect)) }
    fun cropBitmap(targetWidth: Int = 1500, targetHeight: Int = 2000): Bitmap? = snapshot()?.render(targetWidth, targetHeight)
    fun clearImage() { originalBitmap = null; pendingRegion = null; bitmapRect.setEmpty(); isDragging = false; invalidate() }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

/** Fullscreen modal dialog for 3:4 photo cropping. */
object CropDialog {
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun show(activity: Activity, resources: Resources, imageUri: Uri, onCropped: (Bitmap) -> Unit, onCancelled: () -> Unit = {}): Dialog {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ExpressiveUi.BACKGROUND) }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12)) }
        header.addView(ExpressiveUi.iconButton(activity, TablerIcon.X, resources.getString(R.string.befuck_close)) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)))
        header.addView(ExpressiveUi.text(activity, resources.getString(R.string.befuck_crop_title), 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(activity, 16) })
        root.addView(header)
        val status = ExpressiveUi.text(activity, resources.getString(R.string.befuck_preparing_image), 14f, ExpressiveUi.SECONDARY).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(activity, 20), 0, dp(activity, 20), dp(activity, 12))
        }
        root.addView(status)
        val crop = TouchCropView(activity)
        root.addView(crop, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12)) }
        val controls = LinearLayout(activity)
        val rotate = ExpressiveUi.button(activity, resources.getString(R.string.befuck_crop_rotate), icon = TablerIcon.ROTATE_CLOCKWISE) { crop.rotate90() }
        val reset = ExpressiveUi.button(activity, resources.getString(R.string.befuck_crop_reset), icon = TablerIcon.REFRESH) { crop.reset() }
        controls.addView(rotate, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(reset, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(activity, 8) })
        footer.addView(controls)
        val done = ExpressiveUi.button(activity, resources.getString(R.string.befuck_crop_done), true, TablerIcon.CHECK) {}
        footer.addView(done, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 10) })
        root.addView(footer)
        var closed = false
        var accepted = false
        var exporting = false
        var bitmap: Bitmap? = null
        fun release() { crop.clearImage(); bitmap?.recycle(); bitmap = null }
        fun availability() {
            val ready = !closed && !exporting && bitmap != null && crop.hasCropRegion
            rotate.isEnabled = ready; reset.isEnabled = ready; done.isEnabled = ready
        }
        crop.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> availability() }
        dialog.setOnDismissListener {
            closed = true; crop.clearImage()
            if (!exporting) release()
            if (!accepted) onCancelled()
        }
        done.setOnClickListener {
            if (closed || exporting) return@setOnClickListener
            val snapshot = crop.snapshot() ?: return@setOnClickListener
            exporting = true; crop.isEnabled = false; availability()
            status.text = resources.getString(R.string.befuck_preparing_image)
            worker.execute {
                val result = runCatching { snapshot.render(1500, 2000) }
                main.post {
                    exporting = false
                    if (closed || activity.isDestroyed || activity.isFinishing) {
                        result.getOrNull()?.recycle(); release()
                        if (!closed) dialog.dismiss()
                    } else result.onSuccess { output ->
                        accepted = true; dialog.dismiss(); onCropped(output)
                    }.onFailure {
                        crop.isEnabled = true; status.text = resources.getString(R.string.befuck_image_prepare_error); availability()
                    }
                }
            }
        }
        availability()
        ExpressiveUi.showFullScreen(dialog, root)
        val appContext = activity.applicationContext
        worker.execute {
            val result = runCatching { PhotoDecoder.decode(appContext, imageUri) }
            main.post {
                if (closed || activity.isDestroyed || activity.isFinishing) {
                    result.getOrNull()?.recycle()
                    if (!closed) dialog.dismiss()
                } else result.onSuccess { decoded ->
                    bitmap = decoded; crop.setImageBitmap(decoded)
                    status.text = resources.getString(R.string.befuck_crop_hint); availability()
                }.onFailure { status.text = resources.getString(R.string.befuck_image_prepare_error) }
            }
        }
        return dialog
    }
    private fun dp(context: Context, value: Int) = ExpressiveUi.dp(context, value)
}
