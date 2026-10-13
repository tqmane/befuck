package dev.tqmane.befuck.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.View
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ButtonShapeTest {
    @Test fun materialPillsKeepTheirCornersAcrossRapidPressReleaseAndSelection() {
        RuntimeEnvironment.setQualifiers("en-w400dp-h800dp-mdpi")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            // Reproduce the theme's actual rounded-to-square animation using only public APIs.
            val inherited = MaterialButton(ModuleUiContext(activity, activity.resources)).apply {
                insetTop = 0; insetBottom = 0
            }
            activity.setContentView(inherited)
            layout(inherited, 240, 48)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(0, alpha(inherited.background, 240, 48)[6 * 240 + 6])
            inherited.isPressed = true
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue("Expressive's inherited pressed corners no longer reproduce", alpha(inherited.background, 240, 48)[6 * 240 + 6] > 0)

            for (primary in listOf(false, true)) {
                val button = MaterialControls.button(activity, activity.resources, "Calendar", primary, TablerIcon.CALENDAR) {}
                activity.setContentView(button)
                layout(button, 240, 48)
                assertTrue(button.background is RippleDrawable)
                val idleCorners = alpha(button.background, 240, 48)
                assertEquals("Idle pill corner must be transparent", 0, idleCorners[6 * 240 + 6])
                repeat(3) {
                    for (pressed in listOf(true, false)) {
                        button.isSelected = it % 2 == 0
                        button.isPressed = pressed
                        button.jumpDrawablesToCurrentState()
                        assertSameShape(idleCorners, alpha(button.background, 240, 48))
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                        assertSameShape(idleCorners, alpha(button.background, 240, 48))
                    }
                }
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun headerAndCalendarIconRipplesKeepCircularMasksAcrossPressRelease() {
        RuntimeEnvironment.setQualifiers("en-w400dp-h800dp-mdpi")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            for (icon in listOf(TablerIcon.REFRESH, TablerIcon.X, TablerIcon.CHEVRON_LEFT, TablerIcon.CHEVRON_RIGHT)) {
                val button = ExpressiveUi.iconButton(activity, icon, icon.name) {}
                layout(button, 48, 48)
                val ripple = button.background as RippleDrawable
                val mask = ripple.findDrawableByLayerId(android.R.id.mask)
                assertNotNull("Custom round ripple must have an explicit shape mask", mask)
                val idle = alpha(ripple, 48, 48)
                assertEquals(0, idle[4 * 48 + 4])
                assertEquals(255, idle[24 * 48 + 24])
                assertArrayEquals("Ripple mask differs from its circular surface", idle, alpha(mask!!, 48, 48))
                repeat(3) {
                    for (pressed in listOf(true, false)) {
                        button.isPressed = pressed
                        button.jumpDrawablesToCurrentState()
                        assertArrayEquals("Round button became square", idle, alpha(ripple, 48, 48))
                    }
                }
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun assertSameShape(idle: IntArray, actual: IntArray) {
        // Ripple alpha may brighten antialiased edge pixels while keeping the same silhouette.
        idle.indices.forEach { index ->
            if (idle[index] == 0) assertEquals("Pressed ripple filled an empty corner at $index", 0, actual[index])
            if (idle[index] == 255) assertEquals("Pressed surface lost an opaque pixel at $index", 255, actual[index])
        }
    }

    private fun alpha(drawable: Drawable, width: Int, height: Int): IntArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return pixels.map(Color::alpha).toIntArray()
    }
}
