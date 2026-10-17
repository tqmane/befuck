package dev.tqmane.befuck.download

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostLocationMetadataTest {
    private fun image(format: Bitmap.CompressFormat, action: (File) -> Unit) {
        val file = File.createTempFile("post-gps-", ".image", RuntimeEnvironment.getApplication().cacheDir)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(format, 95, it)) }
            action(file)
        } finally { bitmap.recycle(); file.delete() }
    }

    private fun post(location: PostLocation?, date: String? = null) =
        FeedPostMedia("post", date, "caption", null, null, capturedAtMillis = 100,
            username = "author", location = location)

    @Test fun exactGpsWritesWithoutATimestampInAllSupportedImageContainers() {
        for ((format, mime) in listOf(Bitmap.CompressFormat.JPEG to "image/jpeg",
            Bitmap.CompressFormat.PNG to "image/png", Bitmap.CompressFormat.WEBP_LOSSLESS to "image/webp")) {
            image(format) { file ->
                PostMediaMetadata.write(file, mime, post(PostLocation(-33.8, 151.2, false)))
                val exif = ExifInterface(file)
                assertArrayEquals(doubleArrayOf(-33.8, 151.2), exif.latLong, 0.000001)
                assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                assertEquals("S", exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF))
                assertEquals("E", exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF))
            }
        }
    }

    @Test fun originPlaceholderNeverCreatesGpsAndOtherMetadataSurvives() = image(Bitmap.CompressFormat.JPEG) { file ->
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
        val date = "2026-10-13T01:02:03.123Z"
        PostMediaMetadata.write(file, "image/jpeg", post(PostLocation(0.0, 0.0, false), date))
        val exif = ExifInterface(file)
        assertNull(exif.latLong)
        assertEquals("6", exif.getAttribute(ExifInterface.TAG_ORIENTATION))
        assertEquals(PostMediaMetadata.parseTimestamp(date), file.lastModified())
        assertEquals("author", exif.getAttribute(ExifInterface.TAG_ARTIST))
    }

    @Test fun approximateDisplayCenterNeverExportsAsPreciseGpsAndClearsInheritedFix() = image(Bitmap.CompressFormat.JPEG) { file ->
        ExifInterface(file).apply { setLatLong(35.6812, 139.7671); setAltitude(18.0); saveAttributes() }
        PostMediaMetadata.write(file, "image/jpeg", post(PostLocation(35.69, 139.77, true)))
        val exif = ExifInterface(file)
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE))
    }

    @Test fun absentPostLocationNeverCreatesGpsTags() = image(Bitmap.CompressFormat.JPEG) { file ->
        PostMediaMetadata.write(file, "image/jpeg", post(null, "2026-10-13T01:02:03Z"))
        assertNull(ExifInterface(file).latLong)
    }
}
