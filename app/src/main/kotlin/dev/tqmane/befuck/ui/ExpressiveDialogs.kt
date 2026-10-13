package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

/** Natural-height cards; read-only dialogs have one close action, forms have confirm/cancel. */
internal object ExpressiveDialogs {
    fun form(activity: Activity, title: String, message: String?, body: View?, confirm: String, cancel: String?,
             onConfirm: () -> Boolean): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 24), dp(activity, 24), dp(activity, 16))
        }
        column.addView(ExpressiveUi.text(activity, title, 24f, bold = true))
        message?.let {
            column.addView(ExpressiveUi.text(activity, it, 14f, ExpressiveUi.SECONDARY),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 12); bottomMargin = dp(activity, 16) })
        }
        body?.let { column.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        column.addView(ExpressiveUi.button(activity, confirm, true) { if (onConfirm()) dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 20) })
        cancel?.let { label ->
            column.addView(ExpressiveUi.button(activity, label) { dialog.dismiss() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 8) })
        }
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val displayLimit = (activity.resources.displayMetrics.heightPixels * .8f).toInt()
                val parentLimit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) displayLimit
                    else minOf(displayLimit, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(parentLimit, MeasureSpec.AT_MOST))
            }
        }.apply { background = ExpressiveUi.shape(activity); clipToOutline = true; addView(column) }
        dialog.setContentView(scroll)
        if (DialogOwners.show(activity, dialog)) dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(minOf(activity.resources.displayMetrics.widthPixels - dp(activity, 32), dp(activity, 520)), -2)
            window.setGravity(Gravity.CENTER)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            window.setDimAmount(.65f)
        }
        return dialog
    }
    fun confirm(activity: Activity, title: String, message: String, confirm: String, cancel: String,
                action: () -> Unit): Dialog = form(activity, title, message, null, confirm, cancel) { action(); true }
    private fun dp(activity: Activity, value: Int) = ExpressiveUi.dp(activity, value)
}
