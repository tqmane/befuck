package dev.tqmane.befuck.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources

/** Shared detached ContextWrappers and OpenFreeMap style URL for MapLibre views. */
internal object MapLibreSupport {
    const val OPENFREEMAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    data class DetachedContexts(
        val application: Context,
        val render: Context,
    )

    fun createDetachedContexts(context: Context, resources: Resources): DetachedContexts {
        // MapLibre keeps an application Context globally; never let it retain an Activity.
        val appContext = object : ContextWrapper(ModuleUiContext(context.applicationContext, resources)) {
            override fun getApplicationContext(): Context = this
        }
        val renderContext = object : ContextWrapper(ModuleUiContext(context, resources)) {
            override fun getApplicationContext(): Context = appContext
        }
        return DetachedContexts(appContext, renderContext)
    }
}
