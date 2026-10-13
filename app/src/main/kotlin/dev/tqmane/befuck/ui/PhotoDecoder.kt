package dev.tqmane.befuck.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.IOException

/** Bounded gallery decoding, including every mirrored EXIF orientation. */
internal object PhotoDecoder {
    const val MAX_EDGE = 2500
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        require(width > 0 && height > 0 && maxEdge > 0) { "Invalid image dimensions" }
        var sample = 1
        while ((width.toLong() + sample - 1) / sample > maxEdge || (height.toLong() + sample - 1) / sample > maxEdge) {
            require(sample <= 1 shl 29) { "Image dimensions too large" }; sample *= 2
        }
        return sample
    }
    /** The returned bitmap belongs to the caller; streams and intermediate bitmaps never escape. */
    fun decode(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); true } ?: throw IOException("Image is unavailable")
        val sample = sampleSize(bounds.outWidth, bounds.outHeight)
        val orientation = resolver.openInputStream(uri)?.use { input ->
            runCatching { ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: throw IOException("Image is unavailable")
        val bitmap = resolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) } ?: throw IOException("Image could not be decoded")
        try {
            check(bitmap.width <= MAX_EDGE && bitmap.height <= MAX_EDGE) { "Decoder exceeded image budget" }
            val transform = orientationMatrix(orientation)
            if (transform.isIdentity) return bitmap
            val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
            if (upright !== bitmap) bitmap.recycle()
            return upright
        } catch (error: Throwable) { bitmap.recycle(); throw error }
    }
    internal fun orientationMatrix(orientation: Int) = Matrix().apply {
        val values = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(-1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(-1f,0f,0f, 0f,-1f,0f, 0f,0f,1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(1f,0f,0f, 0f,-1f,0f, 0f,0f,1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(0f,1f,0f, 1f,0f,0f, 0f,0f,1f)
            ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(0f,-1f,0f, 1f,0f,0f, 0f,0f,1f)
            ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(0f,-1f,0f, -1f,0f,0f, 0f,0f,1f)
            ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(0f,1f,0f, -1f,0f,0f, 0f,0f,1f)
            else -> null
        }
        values?.let(::setValues)
    }
}
