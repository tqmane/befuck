package dev.tqmane.befuck.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatTextView

/** Separate layers keep the selection and post marker outside TextView's text/scroll clipping. */
internal class CalendarDayCell(context: Context, label: String, selected: Boolean, today: Boolean, hasPosts: Boolean) : FrameLayout(context) {
    private val circle = View(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = if (selected || today) VISIBLE else INVISIBLE
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (selected) Color.WHITE else Color.TRANSPARENT)
            if (!selected && today) setStroke(dp(1), ExpressiveUi.SECONDARY)
        }
    }
    private val number = AppCompatTextView(context).apply {
        tag = "calendar-date-number"
        text = label; gravity = Gravity.CENTER
        setSingleLine(true); setHorizontallyScrolling(false)
        typeface = if (selected || today) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        setTextColor(if (selected) Color.BLACK else Color.WHITE)
        setPadding(dp(6), 0, dp(6), dp(12))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    init {
        isSelected = selected; isClickable = true; isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33808080), null,
            ExpressiveUi.shape(context, Color.WHITE, 100f))
        addView(circle, LayoutParams(dp(36), dp(36), Gravity.CENTER).apply { bottomMargin = dp(6) })
        addView(number, LayoutParams(-1, -1))
        addView(View(context).apply {
            tag = "calendar-post-dot"
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = if (hasPosts) VISIBLE else INVISIBLE
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        }, LayoutParams(dp(4), dp(4), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(3) })
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val diameter = minOf(width - dp(8), height - dp(16)).coerceAtLeast(0)
        circle.layoutParams.width = diameter
        circle.layoutParams.height = diameter
        // Reserve rounding/bold-glyph slack and measure the actual label as well as a two-digit reference.
        val preferred = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        val reference = Paint(number.paint).apply { textSize = preferred }
        val available = (width - number.compoundPaddingLeft - number.compoundPaddingRight - dp(2)).coerceAtLeast(1)
        val widest = maxOf(reference.measureText("88"), reference.measureText(number.text.toString()))
        val size = preferred * minOf(1f, available / widest)
        if (kotlin.math.abs(number.textSize - size) > .01f) number.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
        info.isSelected = isSelected
    }
    private fun dp(value: Int) = ExpressiveUi.dp(context, value)
}
