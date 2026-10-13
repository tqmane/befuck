package dev.tqmane.befuck.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.View
import androidx.exifinterface.media.ExifInterface
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

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaAndUiTest {
    @Test fun panoramaAndPortraitDecodeBudgetUsesEitherDimension() {
        for ((w, h) in listOf(50000 to 500, 500 to 50000, 5001 to 5001, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val sample = PhotoDecoder.sampleSize(w, h)
            assertTrue((w.toLong() + sample - 1) / sample <= PhotoDecoder.MAX_EDGE)
            assertTrue((h.toLong() + sample - 1) / sample <= PhotoDecoder.MAX_EDGE)
        }
        assertEquals(1, PhotoDecoder.sampleSize(2500, 2500))
        assertThrows(IllegalArgumentException::class.java) { PhotoDecoder.sampleSize(0, 100) }
    }
    @Test fun allEightExifOrientationsMatchTheirCornerTransform() {
        val expected = listOf(2f to 3f, -2f to 3f, -2f to -3f, 2f to -3f, 3f to 2f, -3f to 2f, -3f to -2f, 3f to -2f)
        expected.forEachIndexed { index, pair ->
            val point = floatArrayOf(2f, 3f)
            PhotoDecoder.orientationMatrix(index + 1).mapPoints(point)
            assertEquals(pair.first, point[0], .001f); assertEquals(pair.second, point[1], .001f)
        }
    }
    @Test fun decoderReadsRealJpegAndAppliesRotation() {
        val context = RuntimeEnvironment.getApplication()
        val image = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        Canvas(image).drawColor(Color.RED)
        val file = File(context.cacheDir, "orientation.jpg")
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 95, it)) }; image.recycle()
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
        val decoded = PhotoDecoder.decode(context, Uri.fromFile(file))
        assertEquals(40, decoded.width); assertEquals(80, decoded.height); decoded.recycle(); file.delete()
    }
    @Test fun cropExportUsesSnapshotAndRejectsUnboundedTargets() {
        val context = RuntimeEnvironment.getApplication()
        val source = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
        Canvas(source).drawColor(Color.GREEN)
        val crop = TouchCropView(context)
        crop.layout(0, 0, 320, 500); crop.setImageBitmap(source)
        assertTrue(crop.hasCropRegion)
        val snapshot = crop.snapshot()!!
        crop.rotate90(); crop.clearImage()
        assertFalse(crop.hasCropRegion)
        val result = snapshot.render(60, 80)
        assertEquals(60, result.width); assertEquals(80, result.height)
        assertEquals(Color.GREEN, result.getPixel(30, 40))
        assertThrows(IllegalArgumentException::class.java) { snapshot.render(Int.MAX_VALUE, 100) }
        result.recycle(); source.recycle()
    }
    @Test fun geocoderRejectsInvalidResponsesAndUnsafeEndpoints() {
        assertTrue(GeocodingRequest.validEndpoint("https://example.com/search"))
        listOf("http://example.com/search", "https://user:pass@example.com/search", "https://example.com/search?q=x", "file:///etc/passwd").forEach { assertFalse(GeocodingRequest.validEndpoint(it)) }
        assertEquals(1, GeocodingRequest.parse("""[{"lat":"35.0","lon":"139.0","display_name":"Tokyo"},{"lat":"NaN","lon":"1","display_name":"bad"},{"lat":"91","lon":"0","display_name":"bad"}]""").size)
        assertThrows(Exception::class.java) { GeocodingRequest.parse("<html>failure</html>") }
        assertThrows(Exception::class.java) { GeocodingRequest.parse("x".repeat(131073)) }
    }
    @Test fun realControlsUseAccessibleTargetsAndKeepMonochromePalette() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val button = ExpressiveUi.iconButton(activity, TablerIcon.PHOTO, "Pick photo") {}
        assertEquals(ExpressiveUi.dp(activity, 48), button.minimumHeight)
        assertEquals("Pick photo", button.contentDescription)
        assertTrue(button.drawable is TablerIconDrawable)
        assertEquals(Color.BLACK, ExpressiveUi.BACKGROUND)
        for (icon in TablerIcon.entries) {
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            TablerIconDrawable(icon, 24, Color.WHITE).apply { setBounds(0, 0, 24, 24); draw(Canvas(bitmap)) }
            val pixels = IntArray(576); bitmap.getPixels(pixels, 0, 24, 0, 0, 24, 24)
            assertTrue("Empty Tabler icon $icon", pixels.any { Color.alpha(it) > 0 }); bitmap.recycle()
        }
        activity.finish()
    }
    @Test fun galleryRendersActualViewsAndIgnoresUnsolicitedPickerResult() {
        RuntimeEnvironment.setQualifiers("en-rUS-w400dp-h840dp-xhdpi")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        BeFuckGalleryUi.open(activity, activity.resources, null)
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull(dialog); assertTrue(dialog.isShowing)
        val root = dialog.findViewById<android.widget.FrameLayout>(android.R.id.content).getChildAt(0)
        val width = 800; val height = 1680
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("build/reports/ui/gallery-en.png"); file.parentFile.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        assertTrue(BeFuckGalleryUi.onActivityResult(activity, BeFuckGalleryUi.REQUEST_FRONT, Activity.RESULT_CANCELED, null))
        assertTrue(dialog.isShowing)
        BeFuckGalleryUi.onActivityDestroy(activity)
        assertFalse(dialog.isShowing)
        controller.pause().stop().destroy()
    }
}
