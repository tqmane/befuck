package dev.tqmane.befuck.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TablerTintTest {
    private fun drawnColor(drawable: android.graphics.drawable.Drawable):Int {
        val bitmap=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888)
        drawable.setBounds(0,0,48,48); drawable.draw(Canvas(bitmap))
        val pixels=IntArray(48*48); bitmap.getPixels(pixels,0,48,0,0,48,48)
        val color=pixels.firstOrNull { Color.alpha(it)>240 } ?: error("Icon has no opaque pixels")
        bitmap.recycle(); return color
    }
    @Test fun tintChangesWhiteIconToBlackOnSelectedWhiteButton() {
        val icon=TablerIconDrawable(TablerIcon.CALENDAR,24,Color.WHITE)
        icon.setTint(Color.BLACK)
        assertEquals(Color.BLACK,drawnColor(icon))
        icon.setTint(Color.WHITE)
        assertEquals(Color.WHITE,drawnColor(icon))
    }
    @Test fun statefulTintChangesOnDisabledAndRestoresWhenEnabled() {
        val icon=TablerIconDrawable(TablerIcon.CALENDAR,24,Color.WHITE)
        icon.setTintList(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(Color.GRAY,Color.BLACK)))
        assertTrue(icon.isStateful)
        icon.state=intArrayOf(android.R.attr.state_enabled)
        assertEquals(Color.BLACK,drawnColor(icon))
        icon.state=intArrayOf()
        assertEquals(Color.GRAY,drawnColor(icon))
        icon.state=intArrayOf(android.R.attr.state_enabled)
        assertEquals(Color.BLACK,drawnColor(icon))
    }
    @Test fun explicitFilterWinsAndClearingFilterRestoresTint() {
        val icon=TablerIconDrawable(TablerIcon.CHECK,24,Color.WHITE)
        icon.setTint(Color.BLACK)
        icon.colorFilter=PorterDuffColorFilter(Color.GRAY,PorterDuff.Mode.SRC_IN)
        assertEquals(Color.GRAY,drawnColor(icon))
        icon.clearColorFilter(); assertEquals(Color.BLACK,drawnColor(icon))
        icon.setTintList(null); assertEquals(Color.WHITE,drawnColor(icon))
    }
    @Test fun actualMaterialButtonCanRetintItsIconAfterSelection() {
        val owner=Robolectric.buildActivity(Activity::class.java).setup(); val activity=owner.get()
        val button=MaterialControls.button(activity,activity.resources,"Calendar",false,TablerIcon.CALENDAR) {}
        assertEquals(Color.WHITE,drawnColor(button.icon!!))
        button.iconTint=ColorStateList.valueOf(Color.BLACK)
        assertEquals(Color.BLACK,drawnColor(button.icon!!))
        owner.pause().stop().destroy()
    }
}
