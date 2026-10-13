package dev.tqmane.befuck.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

/** Draw official Tabler paths without resolving the injected module's resources in the host. */
internal class TablerIconDrawable(icon: TablerIcon, private val sizePx: Int, color: Int) : Drawable() {
    private val paths = icon.paths.map { requireNotNull(PathParser.createPathFromPathData(it)) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
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
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
