package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import dev.tqmane.befuck.download.FeedPostMedia
import dev.tqmane.befuck.download.FeedMediaItem
import dev.tqmane.befuck.runtime.RuntimeKnowledge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executor

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarScreenTest {
    private val clock=Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private val direct=Executor { it.run() }
    private fun posts():List<FeedPostMedia> {
        val media=FeedMediaItem("https://example.invalid/not-fetched.jpg","image/jpeg",100,100)
        fun post(id:String,at:String?,user:String)=FeedPostMedia(id,null,"Synthetic local fixture",media,media,capturedAtMillis=0,username=user,postedAt=at)
        return listOf(post("today","2026-10-13T00:00:00Z","Example A"),post("boundary","2026-10-12T15:30:00Z","Example B"),post("yesterday","2026-10-12T02:00:00Z","Example A"),post("unknown",null,"Example C"))
    }
    private fun content(dialog:Dialog):View=dialog.findViewById<FrameLayout>(android.R.id.content).getChildAt(0)
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) }else emptyList()
    private fun tagged(root:View,tag:String)=views(root).first { it.tag==tag }
    private fun screenshot(root:View,width:Int,height:Int,name:String) {
        repeat(4) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            root.layout(0,0,width,height)
        }
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file=File("build/reports/ui/$name.png").apply { parentFile.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    @Test fun selectingDateRendersOnlyThatDaysPostsBelowCalendarAndAllRetainsUnknown() {
        val owner=Robolectric.buildActivity(Activity::class.java).setup(); val activity=owner.get()
        val dialog=SavedPostsScreen.show(activity,activity.resources,::posts,direct,clock,previewSource=FixturePreviewSource())
        shadowOf(Looper.getMainLooper()).idle()
        val root=content(dialog)
        assertTrue(views(root).any { it.tag=="saved-post-today" })
        assertTrue(views(root).any { it.tag=="saved-post-boundary" })
        assertFalse(views(root).any { it.tag=="saved-post-yesterday" })
        tagged(root,"calendar-day-2026-10-12").performClick()
        assertTrue(views(root).any { it.tag=="saved-post-yesterday" })
        assertFalse(views(root).any { it.tag=="saved-post-today" })
        assertTrue(tagged(root,"calendar-day-2026-10-12").isSelected)
        tagged(root,"calendar-day-2026-10-11").performClick()
        assertTrue(views(root).any { it.tag=="saved-empty-day" })
        tagged(root,"saved-all-tab").performClick()
        assertEquals(4,views(root).count { it.tag?.toString()?.startsWith("saved-post-")==true })
        tagged(root,"saved-calendar-tab").performClick(); tagged(root,"calendar-today").performClick()
        assertEquals(2,views(root).count { it.tag?.toString()?.startsWith("saved-post-")==true })
        BeFuckGalleryUi.onActivityDestroy(activity); owner.pause().stop().destroy()
    }
    @Test fun searchAndMonthYearNavigationKeepCalendarAndTimelineInSync() {
        val owner=Robolectric.buildActivity(Activity::class.java).setup(); val activity=owner.get()
        val dialog=SavedPostsScreen.show(activity,activity.resources,::posts,direct,clock,previewSource=FixturePreviewSource())
        shadowOf(Looper.getMainLooper()).idle(); val root=content(dialog)
        (tagged(root,"saved-search") as EditText).setText("example b")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertEquals(1,views(root).count { it.tag?.toString()?.startsWith("saved-post-")==true })
        assertTrue(tagged(root,"calendar-day-2026-10-13").contentDescription.contains("1"))
        tagged(root,"calendar-next").performClick()
        assertTrue(views(root).any { it.tag=="calendar-day-2026-11-01" })
        tagged(root,"calendar-month-title").performClick(); tagged(root,"calendar-previous").performClick()
        tagged(root,"calendar-month-2025-02").performClick()
        assertTrue(views(root).any { it.tag=="calendar-day-2025-02-28" })
        assertFalse(views(root).any { it.tag=="calendar-day-2025-02-29" })
        BeFuckGalleryUi.onActivityDestroy(activity); owner.pause().stop().destroy()
    }
    @Test fun cancelledQueuedRefreshCannotRepopulateClosedWindow() {
        val queued=mutableListOf<Runnable>(); var reads=0
        val owner=Robolectric.buildActivity(Activity::class.java).setup(); val activity=owner.get()
        val dialog=SavedPostsScreen.show(activity,activity.resources,{ reads++;posts() },Executor { queued+=it },clock,previewSource=FixturePreviewSource())
        dialog.dismiss(); queued.forEach(Runnable::run); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0,reads); assertFalse(dialog.isShowing)
        BeFuckGalleryUi.onActivityDestroy(activity); owner.pause().stop().destroy()
    }
    @Test fun newerRefreshRejectsAlreadyQueuedOlderResult() {
        var calls=0
        val owner=Robolectric.buildActivity(Activity::class.java).setup(); val activity=owner.get()
        val dialog=SavedPostsScreen.show(activity,activity.resources,{ calls++; if(calls==1) emptyList() else posts() },direct,clock,previewSource=FixturePreviewSource())
        tagged(content(dialog),"saved-refresh").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2,calls)
        assertEquals(2,views(content(dialog)).count { it.tag?.toString()?.startsWith("saved-post-")==true })
        BeFuckGalleryUi.onActivityDestroy(activity);owner.pause().stop().destroy()
    }
    @Test fun failedLoadIsNotMisreportedAsEmptyDayAndCanRetry() {
        var fail=true
        val owner=Robolectric.buildActivity(Activity::class.java).setup(); val activity=owner.get()
        val dialog=SavedPostsScreen.show(activity,activity.resources,{ if(fail) throw java.io.IOException("Synthetic unavailable index"); posts() },direct,clock,previewSource=FixturePreviewSource())
        shadowOf(Looper.getMainLooper()).idle();val root=content(dialog)
        assertFalse(views(root).any { it.tag=="saved-empty-day" })
        assertTrue(views(root).filterIsInstance<TextView>().any { it.text.toString()==activity.resources.getString(dev.tqmane.befuck.R.string.befuck_saved_load_failed) })
        fail=false;tagged(root,"saved-refresh").performClick();shadowOf(Looper.getMainLooper()).idle()
        assertTrue(views(root).any { it.tag=="saved-post-today" })
        BeFuckGalleryUi.onActivityDestroy(activity);owner.pause().stop().destroy()
    }
    @Test fun compactAndTabletRendersUseBoundedWidthsAndActualCalendarViews() {
        for ((width,scale,name) in listOf(Triple(400,1f,"calendar-ja"),Triple(1440,1f,"calendar-tablet"),Triple(320,1.8f,"calendar-large-type"))) {
            RuntimeEnvironment.setQualifiers("ja-rJP-w${width}dp-h1100dp-mdpi")
            RuntimeEnvironment.setFontScale(scale)
            val owner=Robolectric.buildActivity(Activity::class.java).setup(); val activity=owner.get()
            val dialog=SavedPostsScreen.show(activity,activity.resources,::posts,direct,clock,previewSource=FixturePreviewSource())
            shadowOf(Looper.getMainLooper()).idle(); val root=content(dialog)
            screenshot(root,width,1100,name)
            assertTrue(tagged(root,"befuck-bounded-content").width<=760)
            assertTrue(tagged(root,"post-calendar").width<=560)
            val days=views(root).filter { it.tag?.toString()?.startsWith("calendar-day-")==true }
            assertEquals(31,days.size)
            assertTrue(days.all { it.width>0 && it.height>=48 })
            days.flatMap { views(it) }.filterIsInstance<TextView>().forEach { day ->
                assertEquals("A day number must stay on one line", 1, day.layout.lineCount)
                assertTrue("Date text crosses its cell", day.paint.measureText(day.text.toString()) <= day.width - day.paddingLeft - day.paddingRight + 1)
            }
            val monthTitle = tagged(root,"calendar-month-title") as TextView
            assertEquals("Month title must not orphan the month suffix", 1, monthTitle.layout.lineCount)
            assertTrue("Month heading crosses its cell", monthTitle.paint.measureText(monthTitle.text.toString()) <= monthTitle.width - monthTitle.paddingLeft - monthTitle.paddingRight + 1)
            BeFuckGalleryUi.onActivityDestroy(activity); owner.pause().stop().destroy()
        }
        RuntimeEnvironment.setFontScale(1f)
    }
    @Test fun switchIsOfficialMaterialSwitchUsesModuleThemeAndPersistsImmediately() {
        RuntimeEnvironment.setQualifiers("ja-rJP-w720dp-h1100dp-mdpi")
        val owner=Robolectric.buildActivity(Activity::class.java).setup(); val activity=owner.get()
        val prefs=activity.getSharedPreferences("synthetic-material-settings",0)
        prefs.edit().putBoolean("auto_repair",true).putBoolean("diagnostics",false).apply()
        val field=RuntimeKnowledge::class.java.getDeclaredField("prefs").apply { isAccessible=true }
        val previous=field.get(null); field.set(null,prefs)
        try {
            val hostTheme=activity.theme
            BeFuckGalleryUi.openSettings(activity,activity.resources)
            val dialog=ShadowDialog.getLatestDialog(); val root=content(dialog)
            val switches=views(root).filterIsInstance<MaterialSwitch>()
            assertEquals(3,switches.size)
            assertTrue(switches.all { it.context.resources===activity.resources && it.context.theme!==hostTheme })
            val automatic=tagged(root,"setting-auto_repair") as MaterialSwitch
            assertTrue(automatic.isChecked); automatic.performClick()
            assertFalse(automatic.isChecked); assertFalse(prefs.getBoolean("auto_repair",true))
            automatic.isChecked=true; assertTrue(prefs.getBoolean("auto_repair",false))
            assertNotNull(automatic.thumbDrawable); assertNotNull(automatic.trackDrawable)
            assertSame(hostTheme,activity.theme)
            screenshot(root,720,1100,"settings-material3expressive")
            BeFuckGalleryUi.onActivityDestroy(activity)
        } finally { field.set(null,previous); owner.pause().stop().destroy() }
    }
}
