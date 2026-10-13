package dev.tqmane.befuck.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Looper
import android.view.View
import dev.tqmane.befuck.download.FeedMediaItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewLoaderTest {
    @Test fun realWorkerDecodesTemporaryImageAndReusesScreenMemoryCache() {
        val app=RuntimeEnvironment.getApplication();val encoded=ByteArrayOutputStream()
        FixturePreviewSource.picture().let { it.compress(Bitmap.CompressFormat.PNG,100,encoded);it.recycle() }
        val bytes=encoded.toByteArray();val calls=AtomicInteger()
        val transport=PreviewTransport(null) { url ->
            calls.incrementAndGet()
            object:HttpURLConnection(url) {
                override fun getResponseCode()=200
                override fun getContentType()="image/png"
                override fun getContentLengthLong()=bytes.size.toLong()
                override fun getInputStream()=ByteArrayInputStream(bytes)
                override fun connect()=Unit;override fun usingProxy()=false;override fun disconnect()=Unit
            }
        }
        val source=NetworkPreviewSource(app,{},transport)
        fun load():Bitmap {
            var result:Result<Bitmap>?=null
            source.image(FeedMediaItem("https://example.invalid/synthetic","image/png",300,400),128) { result=it }
            val deadline=System.nanoTime()+10_000_000_000L
            while(result==null && System.nanoTime()<deadline) { Thread.sleep(10);shadowOf(Looper.getMainLooper()).idle() }
            assertNotNull("Worker result was not delivered",result)
            return requireNotNull(result).getOrThrow()
        }
        try {
            val first=load();assertTrue(first.width<=128 && first.height<=128)
            val second=load();assertSame(first,second);assertEquals(1,calls.get())
            assertTrue(File(app.cacheDir,"befuck-preview").listFiles().orEmpty().isEmpty())
        } finally { source.close() }
    }
    @Test fun fitMatrixUsesDrawableDensityAndClearingReleasesImage() {
        val app=RuntimeEnvironment.getApplication()
        for (density in listOf(72,160,480)) {
            val bitmap=FixturePreviewSource.picture().apply { this.density=density }
            val view=PreviewPhotoView(ModuleUiContext(app,app.resources))
            view.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));view.layout(0,0,400,600)
            view.display(bitmap)
            val drawable=requireNotNull(view.drawable)
            val bounds=RectF(0f,0f,drawable.intrinsicWidth.toFloat(),drawable.intrinsicHeight.toFloat()).also { view.imageMatrix.mapRect(it) }
            assertTrue("Photo cropped at density $density: $bounds",bounds.left>=-1 && bounds.top>=-1 && bounds.right<=401 && bounds.bottom<=601)
            assertTrue(bounds.width()>390 || bounds.height()>590)
            view.display(null);assertNull(view.drawable);assertFalse(view.isZoomed);bitmap.recycle()
        }
    }
}
