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
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/** Interactive 3:4 aspect-ratio touch cropping view and dialog. */
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
            val factor = detector.scaleFactor
            val newScale = currentScale * factor
            if (newScale in 0.8f..6.0f) {
                currentScale = newScale
                currentMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clampMatrix()
                invalidate()
            }
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateCropRect(w, h)
        setupInitialMatrix()
    }

    private fun calculateCropRect(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val padding = dp(20f)
        val availableWidth = w - padding * 2
        val availableHeight = h - padding * 2

        val targetRatio = 3f / 4f
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
        rotationAngle = (rotationAngle + 90) % 360
        setupInitialMatrix()
        invalidate()
    }

    fun reset() {
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
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
        canvas.drawBitmap(bitmap, currentMatrix, null)
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

    fun cropBitmap(targetWidth: Int = 1500, targetHeight: Int = 2000): Bitmap? {
        val bitmap = originalBitmap ?: return null
        if (cropRect.isEmpty) return null

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val outputMatrix = Matrix(currentMatrix)
        val scaleRatio = targetWidth / cropRect.width()
        outputMatrix.postTranslate(-cropRect.left, -cropRect.top)
        outputMatrix.postScale(scaleRatio, scaleRatio)

        canvas.drawBitmap(bitmap, outputMatrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return result
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

/** Fullscreen modal dialog for 3:4 photo cropping. */
object CropDialog {
    fun show(
        activity: Activity,
        resources: Resources,
        imageUri: Uri,
        onCropped: (Bitmap) -> Unit,
        onCancelled: () -> Unit = {},
    ) {
        val bitmap = decodeSampledBitmapFromUri(activity, imageUri, 2500, 2500)
        if (bitmap == null) {
            onCancelled()
            return
        }

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F0F12.toInt())
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header Toolbar
        val toolbar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
            setBackgroundColor(0xFF1B1B1F.toInt())
        }

        val cancelButton = TextView(activity).apply {
            text = resources.getString(R.string.befuck_close)
            setTextColor(0xFF8E8E93.toInt())
            textSize = 16f
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
            setOnClickListener {
                dialog.dismiss()
            }
        }
        toolbar.addView(cancelButton)

        val title = TextView(activity).apply {
            text = resources.getString(R.string.befuck_crop_title)
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val doneButton = TextView(activity).apply {
            text = resources.getString(R.string.befuck_crop_done)
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
        }
        toolbar.addView(doneButton)
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Center Crop View
        val cropView = TouchCropView(activity).apply {
            setImageBitmap(bitmap)
        }
        root.addView(cropView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom Controls
        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
            setBackgroundColor(0xFF1B1B1F.toInt())
        }

        val rotateButton = Button(activity).apply {
            text = resources.getString(R.string.befuck_crop_rotate)
            setTextColor(Color.WHITE)
            textSize = 14f
            background = rounded(activity, 0xFF29292E.toInt(), 10f)
            setOnClickListener { cropView.rotate90() }
        }
        bottomBar.addView(rotateButton, LinearLayout.LayoutParams(dp(activity, 110), dp(activity, 48)).apply {
            marginEnd = dp(activity, 16)
        })

        val resetButton = Button(activity).apply {
            text = resources.getString(R.string.befuck_crop_reset)
            setTextColor(Color.WHITE)
            textSize = 14f
            background = rounded(activity, 0xFF29292E.toInt(), 10f)
            setOnClickListener { cropView.reset() }
        }
        bottomBar.addView(resetButton, LinearLayout.LayoutParams(dp(activity, 110), dp(activity, 48)))

        root.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        var accepted = false
        dialog.setOnDismissListener {
            bitmap.recycle()
            if (!accepted) onCancelled()
        }
        doneButton.setOnClickListener {
            val cropped = cropView.cropBitmap(1500, 2000)
            accepted = cropped != null
            dialog.dismiss()
            if (cropped != null) {
                onCropped(cropped)
            }
        }

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.let { window ->
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                root.setOnApplyWindowInsetsListener { view, insets ->
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    insets
                }
                root.requestApplyInsets()
            } else {
                root.fitsSystemWindows = true
            }
        }
    }

    private fun decodeSampledBitmapFromUri(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

            var inSampleSize = 1
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampled = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            sampled
        }.getOrNull()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun rounded(context: Context, color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * context.resources.displayMetrics.density
        }
}
