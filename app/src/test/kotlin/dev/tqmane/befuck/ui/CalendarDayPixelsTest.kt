package dev.tqmane.befuck.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarDayPixelsTest {
    private fun draw(selected:Boolean, hasPosts:Boolean):Bitmap {
        RuntimeEnvironment.setQualifiers("en-w400dp-h800dp-mdpi")
        val app=RuntimeEnvironment.getApplication()
        val cell=CalendarDayCell(ModuleUiContext(app,app.resources),"13",selected,false,hasPosts)
        repeat(3) {
            cell.measure(View.MeasureSpec.makeMeasureSpec(50,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(52,View.MeasureSpec.EXACTLY))
            cell.layout(0,0,50,52)
        }
        return Bitmap.createBitmap(50,52,Bitmap.Config.ARGB_8888).also { cell.draw(Canvas(it)) }
    }
    @Test fun selectedDateHasVisibleWhiteCircleNotBlackTextOnBlackSurface() {
        val bitmap=draw(true,true)
        val pixels=IntArray(50*52);bitmap.getPixels(pixels,0,50,0,0,50,52)
        assertTrue("Selection fill disappeared",pixels.count { it==Color.WHITE }>500)
        assertEquals(Color.WHITE,bitmap.getPixel(25,8))
        bitmap.recycle()
    }
    @Test fun postDotRemainsVisibleOutsideTextClipOnlyForDaysWithPosts() {
        val present=draw(false,true);val absent=draw(false,false)
        assertEquals(Color.WHITE,present.getPixel(25,47))
        assertEquals(0,Color.alpha(absent.getPixel(25,47)))
        present.recycle();absent.recycle()
    }
}
