package dev.tqmane.befuck.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.tqmane.befuck.download.FeedMediaItem

/** No network. Callback delivery can deliberately ignore cancellation to exercise stale-result guards. */
internal class FixturePreviewSource(private val immediate: Boolean = true) : PreviewSource {
    class ImageRequest(val media: FeedMediaItem, val edge: Int, val callback: (Result<Bitmap>) -> Unit) { var cancelled = false }
    class VideoRequest(val callback: (Result<PreviewVideo>) -> Unit) { var cancelled = false }
    val images = arrayListOf<ImageRequest>()
    val videos = arrayListOf<VideoRequest>()
    var closed = false
    override fun image(media: FeedMediaItem, edge: Int, callback: (Result<Bitmap>) -> Unit): PreviewCancel {
        val request = ImageRequest(media, edge, callback); images += request
        if (immediate) callback(Result.success(picture(media.url.contains("front"))))
        return PreviewCancel { request.cancelled = true }
    }
    override fun video(media: FeedMediaItem, callback: (Result<PreviewVideo>) -> Unit): PreviewCancel {
        val request = VideoRequest(callback); videos += request
        return PreviewCancel { request.cancelled = true }
    }
    override fun close() { closed = true; images.forEach { it.cancelled = true }; videos.forEach { it.cancelled = true } }
    companion object {
        fun picture(front: Boolean = false): Bitmap {
            val image = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            canvas.drawColor(if (front) 0xFF9BAAA5.toInt() else 0xFFA2B1BC.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0xFFE8DCC4.toInt(); canvas.drawCircle(if (front) 185f else 75f, 95f, 40f, paint)
            paint.color = 0xFF586761.toInt()
            canvas.drawPath(android.graphics.Path().apply { moveTo(0f, 300f); lineTo(135f, 155f); lineTo(300f, 300f); close() }, paint)
            paint.color = 0xFF3C4D4A.toInt(); canvas.drawRect(0f, 295f, 300f, 400f, paint)
            paint.color = Color.WHITE; paint.textSize = 16f
            canvas.drawText(if (front) "FRONT / SYNTHETIC" else "BACK / SYNTHETIC", 18f, 372f, paint)
            return image
        }
    }
}
