package dev.tqmane.befuck.ui

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/** Official Tabler paths with the Drawable tint/state contract used by MaterialButton. */
internal class TablerIconDrawable(icon: TablerIcon, private val sizePx: Int, color: Int) : Drawable() {
    private val paths = icon.paths.map { requireNotNull(PathParser.createPathFromPathData(it)) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private var tintList: ColorStateList? = null
    private var tintMode: PorterDuff.Mode? = PorterDuff.Mode.SRC_IN
    private var resolvedTint: Int? = null
    private var tintFilter: ColorFilter? = null
    private var explicitFilter: ColorFilter? = null

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val scale = minOf(bounds.width(), bounds.height()) / 24f
        val save = canvas.save()
        canvas.translate(bounds.exactCenterX() - 12 * scale, bounds.exactCenterY() - 12 * scale)
        canvas.scale(scale, scale)
        paths.forEach { canvas.drawPath(it, paint) }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun getAlpha() = paint.alpha
    override fun setColorFilter(colorFilter: ColorFilter?) {
        explicitFilter = colorFilter
        paint.colorFilter = explicitFilter ?: tintFilter
        invalidateSelf()
    }
    override fun getColorFilter(): ColorFilter? = explicitFilter
    override fun setTint(tintColor: Int) = setTintList(ColorStateList.valueOf(tintColor))
    override fun setTintList(tint: ColorStateList?) { tintList = tint; updateTint(state, true) }
    override fun setTintMode(tintMode: PorterDuff.Mode?) { this.tintMode = tintMode; updateTint(state, true) }
    override fun isStateful() = tintList?.isStateful == true || super.isStateful()
    override fun onStateChange(state: IntArray): Boolean = updateTint(state, false)

    private fun updateTint(state: IntArray, force: Boolean): Boolean {
        val color = tintList?.getColorForState(state, tintList!!.defaultColor)
        if (!force && color == resolvedTint) return false
        resolvedTint = color
        tintFilter = if (color != null && tintMode != null) PorterDuffColorFilter(color, tintMode!!) else null
        paint.colorFilter = explicitFilter ?: tintFilter
        invalidateSelf()
        return true
    }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
