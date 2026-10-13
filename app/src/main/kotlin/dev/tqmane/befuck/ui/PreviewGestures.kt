package dev.tqmane.befuck.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs

/** Direction locks before interception: vertical motion and multi-touch never change camera sides. */
internal class CameraSwipeFrame(context: Context, private val canSwipe: () -> Boolean, private val swipe: (Boolean) -> Unit) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop * 2
    private var downX = 0f; private var downY = 0f
    private var direction = 0
    private var multiple = false
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; direction = 0; multiple = false }
            MotionEvent.ACTION_POINTER_DOWN -> { multiple = true; direction = -1 }
            MotionEvent.ACTION_MOVE -> {
                if (!canSwipe() || multiple || event.pointerCount != 1) return false
                val x = abs(event.x - downX); val y = abs(event.y - downY)
                if (direction == 0 && maxOf(x, y) > slop) direction = if (x > y * 1.3f) 1 else -1
                if (direction == 1) { parent?.requestDisallowInterceptTouchEvent(true); return true }
            }
        }
        return false
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) multiple = true
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val distance = event.x - downX
            val threshold = maxOf(ExpressiveUi.dp(context, 32).toFloat(), minOf(ExpressiveUi.dp(context, 80).toFloat(), width * .22f))
            if (!multiple && direction == 1 && canSwipe() && abs(distance) >= threshold) swipe(distance < 0)
            direction = 0; parent?.requestDisallowInterceptTouchEvent(false)
        } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) { direction = 0; multiple = true }
        return true
    }
}

/** Fit-center by default. Zoom/pan stays within the photo and never leaks a pinch into paging. */
internal class PreviewPhotoView(context: Context) : androidx.appcompat.widget.AppCompatImageView(context) {
    private val transform = Matrix()
    private var bitmap: Bitmap? = null
    private var zoom = 1f
    val isZoomed: Boolean get() = zoom > 1.01f
    private var x = 0f; private var y = 0f
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (bitmap == null || !detector.scaleFactor.isFinite()) return false
            val next = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
            transform.postScale(next / zoom, next / zoom, detector.focusX, detector.focusY)
            zoom = next; clamp(); parent?.requestDisallowInterceptTouchEvent(true); return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (bitmap == null) return true
            if (isZoomed) fit() else { transform.postScale(3f, 3f, event.x, event.y); zoom = 3f; clamp() }
            return true
        }
    })
    init { scaleType = ScaleType.MATRIX; isClickable = true }
    fun display(value: Bitmap?) { bitmap = value; if (value == null) setImageDrawable(null) else setImageBitmap(value); fit() }
    private fun fit() {
        zoom = 1f; transform.reset()
        bitmap?.let {
            if (width > 0 && height > 0) {
                val dw = drawable?.intrinsicWidth?.takeIf { value -> value > 0 } ?: it.width
                val dh = drawable?.intrinsicHeight?.takeIf { value -> value > 0 } ?: it.height
                val factor = minOf(width.toFloat() / dw, height.toFloat() / dh)
                transform.setScale(factor, factor)
                transform.postTranslate((width - dw * factor) / 2, (height - dh * factor) / 2)
            }
        }
        imageMatrix = transform
    }
    private fun clamp() {
        val image = bitmap ?: return
        val dw = drawable?.intrinsicWidth?.takeIf { it > 0 } ?: image.width
        val dh = drawable?.intrinsicHeight?.takeIf { it > 0 } ?: image.height
        val bounds = RectF(0f, 0f, dw.toFloat(), dh.toFloat()).also { transform.mapRect(it) }
        val dx = when { bounds.width() <= width -> width / 2f - bounds.centerX(); bounds.left > 0 -> -bounds.left; bounds.right < width -> width - bounds.right; else -> 0f }
        val dy = when { bounds.height() <= height -> height / 2f - bounds.centerY(); bounds.top > 0 -> -bounds.top; bounds.bottom < height -> height - bounds.bottom; else -> 0f }
        transform.postTranslate(dx, dy); imageMatrix = transform
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); fit() }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event); gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x = event.x; y = event.y; parent?.requestDisallowInterceptTouchEvent(isZoomed) }
            MotionEvent.ACTION_MOVE -> {
                if (!scale.isInProgress && event.pointerCount == 1 && isZoomed) { transform.postTranslate(event.x - x, event.y - y); clamp() }
                x = event.x; y = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                x = event.getX(remaining); y = event.getY(remaining)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
