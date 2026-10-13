package dev.tqmane.befuck.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Resource-independent Material 3 Expressive shapes, type and controls for injected Views.
 * The host's theme/resource IDs are deliberately not used. The existing monochrome palette stays.
 */
internal object ExpressiveUi {
    const val BACKGROUND = 0xFF000000.toInt()
    const val SURFACE = 0xFF141414.toInt()
    const val CONTAINER = 0xFF202020.toInt()
    const val OUTLINE = 0xFF424242.toInt()
    const val SECONDARY = 0xFFB8B8B8.toInt()
    const val ERROR = 0xFFFFB4AB.toInt()
    const val CORNER = 28f
    fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value + .5f).toInt()
    fun shape(context: Context, color: Int = SURFACE, radius: Float = CORNER, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * context.resources.displayMetrics.density
        stroke?.let { setStroke(dp(context, 1), it) }
    }
    fun ripple(context: Context, color: Int = CONTAINER, radius: Float = CORNER, stroke: Int? = null) =
        RippleDrawable(ColorStateList.valueOf(if (color == Color.WHITE) 0x24000000 else 0x33FFFFFF), shape(context, color, radius, stroke), null)
    fun text(context: Context, value: CharSequence, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setLineSpacing(0f, 1.12f)
    }
    fun button(context: Context, label: String, primary: Boolean = false, icon: TablerIcon? = null, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minimumWidth = 0; minWidth = 0; minimumHeight = dp(context, 52); minHeight = dp(context, 52)
        gravity = Gravity.CENTER
        setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12))
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(if (primary) 0xFF626262.toInt() else 0xFF858585.toInt(), if (primary) Color.BLACK else Color.WHITE)))
        background = ripple(context, if (primary) Color.WHITE else CONTAINER)
        icon?.let {
            val drawable = TablerIconDrawable(it, dp(context, 20), if (primary) Color.BLACK else Color.WHITE)
            drawable.setBounds(0, 0, dp(context, 20), dp(context, 20))
            setCompoundDrawablesRelative(drawable, null, null, null); compoundDrawablePadding = dp(context, 8)
        }
        expressivePress(this)
        setOnClickListener { action() }
    }
    fun iconButton(context: Context, icon: TablerIcon, description: String, action: () -> Unit) = ImageButton(context).apply {
        setImageDrawable(TablerIconDrawable(icon, dp(context, 24), Color.WHITE)); scaleType = ImageView.ScaleType.CENTER
        contentDescription = description; tooltipText = description
        minimumWidth = dp(context, 48); minimumHeight = dp(context, 48)
        setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        background = ripple(context); expressivePress(this)
        setOnClickListener { action() }
    }
    fun row(context: Context, title: String, icon: TablerIcon, subtitle: String? = null, action: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(context, 64)
        setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
        background = ripple(context, SURFACE, 20f); isFocusable = true; isClickable = true
        addView(ImageView(context).apply {
            setImageDrawable(TablerIconDrawable(icon, dp(context, 24), Color.WHITE)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)))
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(context, title, 16f, bold = true))
            subtitle?.let { addView(text(context, it, 13f, SECONDARY), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 3) }) }
        }
        addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(context, 16); marginEnd = dp(context, 12) })
        addView(ImageView(context).apply {
            setImageDrawable(TablerIconDrawable(TablerIcon.CHEVRON_RIGHT, dp(context, 20), SECONDARY)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)))
        setOnClickListener { action() }
    }
    fun heading(context: Context, title: String, subtitle: String? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(context, 12), 0, dp(context, 12))
        addView(text(context, title, 20f, bold = true))
        subtitle?.let { addView(text(context, it, 14f, SECONDARY), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 6) }) }
    }
    private fun expressivePress(view: View) {
        // Uses platform animator duration scale, including accessibility's animations-off setting.
        view.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled), ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, .97f), PropertyValuesHolder.ofFloat(View.SCALE_Y, .97f)).apply { duration = 120 })
            addState(intArrayOf(), ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f)).apply { duration = 180 })
        }
    }
    /** Insets belong to this dialog, never the host Activity. Includes IME and horizontal cutouts. */
    @Suppress("DEPRECATION")
    fun showFullScreen(dialog: Dialog, root: View) {
        dialog.setContentView(root)
        val owner = root.context as? android.app.Activity ?: error("A dialog needs an Activity owner")
        if (!DialogOwners.show(owner, dialog)) return
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            window.statusBarColor = BACKGROUND; window.navigationBarColor = BACKGROUND
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            } else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            val left = root.paddingLeft; val top = root.paddingTop; val right = root.paddingRight; val bottom = root.paddingBottom
            root.setOnApplyWindowInsetsListener { view, insets ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                    view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
                } else view.setPadding(left + insets.systemWindowInsetLeft, top + insets.systemWindowInsetTop, right + insets.systemWindowInsetRight, bottom + insets.systemWindowInsetBottom)
                insets
            }
            root.requestApplyInsets()
        }
    }
}
