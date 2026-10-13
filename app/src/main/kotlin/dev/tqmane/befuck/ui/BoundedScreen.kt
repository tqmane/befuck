package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/** Measures against the current window, not device-wide screenWidthDp. Works after split-screen resize. */
internal class WidthLimitedFrame(activity: Activity, private val maxWidthDp: Int) : FrameLayout(activity) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maximum = ExpressiveUi.dp(context, maxWidthDp)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) maximum
            else minOf(maximum, MeasureSpec.getSize(widthMeasureSpec))
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}

internal object BoundedScreen {
    fun show(activity: Activity, dialog: Dialog, content: View, maxWidthDp: Int = 760) {
        val outer = FrameLayout(activity).apply { setBackgroundColor(ExpressiveUi.BACKGROUND) }
        val bounded = WidthLimitedFrame(activity, maxWidthDp).apply {
            tag = "befuck-bounded-content"
            addView(content, FrameLayout.LayoutParams(-1, -1))
        }
        outer.addView(bounded, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        ExpressiveUi.showFullScreen(dialog, outer)
    }
}
