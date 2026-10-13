package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Dialog
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Main-thread ownership without replacing each dialog's own dismissal/cleanup listener. */
internal object DialogOwners {
    private val owned = WeakHashMap<Activity, MutableList<WeakReference<Dialog>>>()
    private val destroyed = WeakHashMap<Activity, Boolean>()

    fun show(activity: Activity, dialog: Dialog): Boolean {
        if (activity.isDestroyed || activity.isFinishing || destroyed[activity] == true) return false
        val dialogs = owned.getOrPut(activity) { arrayListOf() }
        dialogs.removeAll { it.get()?.isShowing != true }
        if (dialogs.none { it.get() === dialog }) dialogs.add(WeakReference(dialog))
        dialog.show()
        return true
    }

    fun destroy(activity: Activity) {
        // A dismiss callback must not be able to reopen a window during owner destruction.
        destroyed[activity] = true
        val dialogs = owned.remove(activity).orEmpty().mapNotNull { it.get() }
        dialogs.asReversed().forEach { runCatching { if (it.isShowing) it.dismiss() } }
    }
}
