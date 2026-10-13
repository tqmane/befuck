package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import dev.tqmane.befuck.download.*
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
class SavedPreviewUiTest {
    private fun post(video: Boolean = false) = FeedPostMedia("fixture", null, "Synthetic saved post",
        FeedMediaItem("https://example.invalid/back", if (video) "video/mp4" else "image/jpeg", 300, 400),
        FeedMediaItem("https://example.invalid/front", if (video) "video/mp4" else "image/jpeg", 300, 400),
        capturedAtMillis = 100, username = "Example", postedAt = "2026-10-13T09:00:00Z")
    private fun root(dialog: Dialog): View = dialog.findViewById<FrameLayout>(android.R.id.content).getChildAt(0)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun view(dialog: Dialog, tag: String): View = views(root(dialog)).first { it.tag == tag }
    private fun layout(root: View, width: Int = 400, height: Int = 900) {
        repeat(4) { root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY)); root.layout(0,0,width,height) }
    }
    private fun screenshot(root: View, name: String, width: Int = 400, height: Int = 900) {
        layout(root,width,height)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
        val file=File("build/reports/ui/$name.png").apply { parentFile.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun inlineSaveDoesNotOpenViewerAndFullscreenSaveFollowsSwipedCamera() {
        RuntimeEnvironment.setQualifiers("ja-rJP-w400dp-h1000dp-mdpi")
        val owner=Robolectric.buildActivity(Activity::class.java).setup();val activity=owner.get()
        val source=FixturePreviewSource();val saved=arrayListOf<FeedMediaSelection>()
        val actions=PostMediaActions({ true }) { _,side,done -> saved+=side;done(FeedMediaDownloadResult(true,1,42)) }
        val screen=SavedPostsScreen.show(activity,activity.resources,{ listOf(post()) },Executor { it.run() },Clock.fixed(Instant.parse("2026-10-13T09:00:00Z"),ZoneId.of("UTC")),source,actions)
        shadowOf(Looper.getMainLooper()).idle();layout(root(screen),400,1000)
        assertTrue(view(screen,"preview-save-fixture-PRIMARY").performClick())
        assertSame(screen,ShadowDialog.getLatestDialog());assertEquals(listOf(FeedMediaSelection.PRIMARY),saved)
        views(root(screen)).filterIsInstance<InlinePostPreview>().forEach { it.visible(true) }
        (view(screen,"saved-media-scroll") as ScrollView).scrollTo(0,430)
        screenshot(root(screen),"saved-camera-previews",400,1000)
        view(screen,"preview-open-fixture-PRIMARY").performClick()
        val viewer=ShadowDialog.getLatestDialog()
        assertTrue(viewer is SavedPostViewer);layout(root(viewer))
        val swipe=view(viewer,"viewer-swipe") as CameraSwipeFrame
        fun event(action:Int,x:Float,y:Float)=MotionEvent.obtain(SystemClock.uptimeMillis(),SystemClock.uptimeMillis()+10,action,x,y,0)
        event(MotionEvent.ACTION_DOWN,280f,100f).let { swipe.onInterceptTouchEvent(it);it.recycle() }
        event(MotionEvent.ACTION_MOVE,100f,110f).let { assertTrue(swipe.onInterceptTouchEvent(it));it.recycle() }
        event(MotionEvent.ACTION_UP,60f,110f).let { swipe.onTouchEvent(it);it.recycle() }
        assertTrue(view(viewer,"viewer-side-SECONDARY").isSelected)
        view(viewer,"viewer-save-current").performClick()
        assertEquals(listOf(FeedMediaSelection.PRIMARY,FeedMediaSelection.SECONDARY),saved)
        screenshot(root(viewer),"saved-camera-fullscreen")
        viewer.dismiss();assertTrue(screen.isShowing)
        BeFuckGalleryUi.onActivityDestroy(activity);assertTrue(source.closed);owner.pause().stop().destroy()
    }
    @Test fun staleImageAfterSwipeOrCloseCannotReplaceCurrentCamera() {
        val owner=Robolectric.buildActivity(Activity::class.java).setup();val activity=owner.get()
        val source=FixturePreviewSource(false)
        val actions=PostMediaActions({ true }) { _,_,_ -> }
        val viewer=SavedPostViewer(activity,activity.resources,post(),FeedMediaSelection.PRIMARY,source,actions,{ true }) {}
        viewer.showViewer();assertEquals(1,source.images.size)
        view(viewer,"viewer-next").performClick();assertTrue(source.images[0].cancelled)
        val first=FixturePreviewSource.picture();val second=FixturePreviewSource.picture(true)
        source.images[0].callback(Result.success(first))
        assertNull((view(viewer,"viewer-photo") as ImageView).drawable)
        source.images[1].callback(Result.success(second))
        assertSame(second,((view(viewer,"viewer-photo") as ImageView).drawable as BitmapDrawable).bitmap)
        viewer.dismiss();source.images[1].callback(Result.success(first))
        assertNull((view(viewer,"viewer-photo") as ImageView).drawable)
        source.close();owner.pause().stop().destroy()
    }
    @Test fun noRequestUntilVisibleAndHiddenTileRejectsLateDelivery() {
        val owner=Robolectric.buildActivity(Activity::class.java).setup();val activity=owner.get()
        val source=FixturePreviewSource(false);val actions=PostMediaActions({ true }) { _,_,_ -> }
        val tile=InlinePostPreview(activity,activity.resources,post(),FeedMediaSelection.PRIMARY,source,actions,{ true }) {}
        assertEquals(0,source.images.size)
        tile.visible(true);assertEquals(1,source.images.size)
        tile.visible(false);assertTrue(source.images[0].cancelled)
        source.images[0].callback(Result.success(FixturePreviewSource.picture()))
        assertFalse(views(tile).filterIsInstance<ImageView>().any { it.drawable is BitmapDrawable })
        tile.close();source.close();owner.pause().stop().destroy()
    }
    @Test fun missingCameraAndFailedPreviewDoNotDisableOriginalDownloadOrCrash() {
        val owner=Robolectric.buildActivity(Activity::class.java).setup();val activity=owner.get()
        val source=FixturePreviewSource(false);var saved:FeedMediaSelection?=null
        val actions=PostMediaActions({ true }) { _,side,_ -> saved=side }
        val viewer=SavedPostViewer(activity,activity.resources,post().copy(primary=null),FeedMediaSelection.SECONDARY,source,actions,{ true }) {}
        viewer.showViewer();source.images.single().callback(Result.failure(java.io.IOException("Fixture expired URL")))
        assertEquals(View.VISIBLE,view(viewer,"viewer-retry").visibility)
        assertFalse(view(viewer,"viewer-next").isEnabled);assertFalse(view(viewer,"viewer-previous").isEnabled)
        view(viewer,"viewer-save-current").performClick();assertEquals(FeedMediaSelection.SECONDARY,saved)
        viewer.dismiss();source.close();owner.pause().stop().destroy()
    }
    @Test fun videoStartsOnlyOnPlayAndLatePlaybackFileIsDeletedAfterSwipe() {
        val owner=Robolectric.buildActivity(Activity::class.java).setup();val activity=owner.get()
        val source=FixturePreviewSource();val actions=PostMediaActions({ true }) { _,_,_ -> }
        val viewer=SavedPostViewer(activity,activity.resources,post(true),FeedMediaSelection.PRIMARY,source,actions,{ true }) {}
        viewer.showViewer();assertEquals(0,source.videos.size)
        view(viewer,"viewer-play").performClick();assertEquals(1,source.videos.size)
        view(viewer,"viewer-next").performClick();assertTrue(source.videos[0].cancelled)
        val file=File(activity.cacheDir,"synthetic-late-video.mp4").apply { writeBytes(byteArrayOf(1,2,3)) }
        source.videos[0].callback(Result.success(PreviewVideo(file)))
        assertFalse(file.exists())
        view(viewer,"viewer-play").performClick();viewer.pauseMedia()
        assertTrue(source.videos.last().cancelled)
        assertFalse(view(viewer,"viewer-save-current").isEnabled)
        viewer.resumeMedia();assertTrue(view(viewer,"viewer-save-current").isEnabled)
        viewer.dismiss();source.close();owner.pause().stop().destroy()
    }
    @Test fun verticalAndCancelledSwipesNeverSwitchCameras() {
        val context=RuntimeEnvironment.getApplication();var moved=0
        val frame=CameraSwipeFrame(context,{ true }) { moved++ };frame.layout(0,0,400,700)
        fun send(action:Int,x:Float,y:Float,intercept:Boolean=true) {
            val e=MotionEvent.obtain(0,10,action,x,y,0)
            if(intercept) frame.onInterceptTouchEvent(e) else frame.onTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN,100f,100f);send(MotionEvent.ACTION_MOVE,105f,400f);send(MotionEvent.ACTION_UP,300f,400f,false)
        assertEquals(0,moved)
        send(MotionEvent.ACTION_DOWN,300f,100f);send(MotionEvent.ACTION_MOVE,50f,100f);send(MotionEvent.ACTION_CANCEL,50f,100f,false);send(MotionEvent.ACTION_UP,50f,100f,false)
        assertEquals(0,moved)
    }
    @Test @Config(sdk=[29,33]) fun imageDecoderBoundsOutputAndHonorsJpegExifRotation() {
        val app=RuntimeEnvironment.getApplication();val image=Bitmap.createBitmap(100,50,Bitmap.Config.ARGB_8888)
        val file=File(app.cacheDir,"synthetic-preview-exif.jpg")
        try {
            file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG,90,it) };image.recycle()
            androidx.exifinterface.media.ExifInterface(file).apply { setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,"6");saveAttributes() }
            val decoded=NetworkPreviewSource.decodeImage(file,40)
            assertTrue(decoded.width<=40 && decoded.height<=40);assertTrue(decoded.height>decoded.width);decoded.recycle()
        } finally { file.delete() }
    }
    @Test fun smallLandscapeAndLargeTypeLeaveAnImageAreaAndScrollableActions() {
        for ((width,height,scale) in listOf(Triple(800,360,1f),Triple(320,800,1.8f),Triple(1440,1000,1f))) {
            RuntimeEnvironment.setQualifiers("ja-rJP-w${width}dp-h${height}dp-mdpi")
            RuntimeEnvironment.setFontScale(scale)
            val owner=Robolectric.buildActivity(Activity::class.java).setup();val activity=owner.get()
            val source=FixturePreviewSource();val actions=PostMediaActions({ true }) { _,_,_ -> }
            val viewer=SavedPostViewer(activity,activity.resources,post(true),FeedMediaSelection.PRIMARY,source,actions,{ true }) {}
            viewer.showViewer();layout(root(viewer),width,height)
            assertTrue("No media area at $width x $height",view(viewer,"viewer-swipe").height>0)
            val footer=view(viewer,"viewer-footer-scroll") as ScrollView
            assertTrue(footer.width<=720)
            assertTrue(view(viewer,"viewer-save-current").isEnabled)
            footer.fullScroll(View.FOCUS_DOWN)
            assertTrue(view(viewer,"viewer-save-all").height>=48)
            viewer.dismiss();source.close();owner.pause().stop().destroy()
        }
        RuntimeEnvironment.setFontScale(1f)
    }
    @Test fun latePosterFailureCannotCoverAnExplicitVideoLoad() {
        val owner=Robolectric.buildActivity(Activity::class.java).setup();val activity=owner.get()
        val source=FixturePreviewSource(false);val actions=PostMediaActions({ true }) { _,_,_ -> }
        val viewer=SavedPostViewer(activity,activity.resources,post(true),FeedMediaSelection.PRIMARY,source,actions,{ true }) {}
        viewer.showViewer();assertEquals(1,source.images.size)
        view(viewer,"viewer-play").performClick()
        assertTrue(source.images[0].cancelled);assertEquals(1,source.videos.size)
        source.images[0].callback(Result.failure(java.io.IOException("Late poster failure")))
        assertEquals(View.GONE,view(viewer,"viewer-retry").visibility)
        assertTrue(view(viewer,"viewer-save-current").isEnabled)
        source.videos[0].callback(Result.failure(java.io.IOException("Synthetic playback failure")))
        assertTrue(view(viewer,"viewer-play").isEnabled)
        viewer.dismiss();source.close();owner.pause().stop().destroy()
    }
}
