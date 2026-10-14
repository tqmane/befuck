package dev.tqmane.befuck.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.view.LayoutInflater
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import dev.tqmane.befuck.R

/** Material widgets resolve the module's resources and a NEW theme, never the host Activity theme. */
internal class ModuleUiContext(base: Context, private val moduleResources: Resources) : ContextWrapper(base) {
    private var isolatedTheme: Resources.Theme? = null
    private var inflater: LayoutInflater? = null
    override fun getResources() = moduleResources
    override fun getAssets() = moduleResources.assets
    override fun getClassLoader(): ClassLoader = ModuleUiContext::class.java.classLoader!!
    override fun getTheme(): Resources.Theme = isolatedTheme ?: moduleResources.newTheme().also {
        it.applyStyle(R.style.Theme_BeFuck_Expressive, true)
        isolatedTheme = it
    }
    override fun setTheme(resid: Int) { theme.applyStyle(resid, true) }
    override fun getSystemService(name: String): Any? = if (name == Context.LAYOUT_INFLATER_SERVICE) {
        inflater ?: LayoutInflater.from(baseContext).cloneInContext(this).also { inflater = it }
    } else super.getSystemService(name)
}

internal object MaterialControls {
    fun switch(context: Context, resources: Resources): MaterialSwitch = MaterialSwitch(ModuleUiContext(context, resources)).apply {
        // Keep the official animated thumb/track, including pressed, disabled and RTL states.
        minHeight = ExpressiveUi.dp(context, 56)
        switchPadding = ExpressiveUi.dp(context, 20)
        textSize = 16f
        setTextColor(Color.WHITE)
        showText = false
    }
    fun button(context: Context, resources: Resources, label: String, primary: Boolean = false,
               icon: TablerIcon? = null, action: () -> Unit): MaterialButton = MaterialButton(
        context as? ModuleUiContext ?: ModuleUiContext(context, resources)
    ).apply {
        // The public getter extracts the default model, replacing Expressive's pressed corner morph.
        shapeAppearanceModel = shapeAppearanceModel
        text = label; isAllCaps = false; textSize = 14f; minWidth = 0; minimumWidth = 0
        minHeight = ExpressiveUi.dp(context, 48); minimumHeight = ExpressiveUi.dp(context, 48)
        insetTop = 0; insetBottom = 0
        setPadding(ExpressiveUi.dp(context, 14), ExpressiveUi.dp(context, 10), ExpressiveUi.dp(context, 14), ExpressiveUi.dp(context, 10))
        backgroundTintList = ColorStateList.valueOf(if (primary) Color.WHITE else ExpressiveUi.CONTAINER)
        val colors = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0xFF888888.toInt(), if (primary) Color.BLACK else Color.WHITE))
        setTextColor(colors); iconTint = colors
        icon?.let { this.icon = TablerIconDrawable(it, ExpressiveUi.dp(context, 20), if (primary) Color.BLACK else Color.WHITE) }
        iconSize = ExpressiveUi.dp(context, 20)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        setOnClickListener { action() }
    }
}
