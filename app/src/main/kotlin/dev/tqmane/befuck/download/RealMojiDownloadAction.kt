package dev.tqmane.befuck.download

import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.tqmane.befuck.R
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.function.Consumer

/** Each menu action owns an immutable snapshot of the selected RealMoji. */
class RealMojiDownloadAction private constructor(private val media: FeedPostMedia) : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
        "hashCode" -> media.hashCode()
        "equals" -> args?.firstOrNull()?.let {
            Proxy.isProxyClass(it.javaClass) && (Proxy.getInvocationHandler(it) as? RealMojiDownloadAction)?.media == media
        } ?: false
        "toString" -> "BeFuckRealMojiDownload(${media.postId})"
        else -> throw UnsupportedOperationException(method.name)
    }

    fun download(context: Context, resources: Resources) {
        val app = context.applicationContext
        Toast.makeText(app, resources.getString(R.string.befuck_download_in_progress), Toast.LENGTH_SHORT).show()
        FeedMediaDownloader.downloadRealMoji(app, media, Consumer { result ->
            Handler(Looper.getMainLooper()).post {
                val text = if (result.success) resources.getString(R.string.befuck_download_complete, result.completedCount)
                    else resources.getString(R.string.befuck_download_failed)
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            }
        })
    }

    companion object {
        @JvmStatic
        fun create(actionType: Class<*>, model: Any): Any {
            require(actionType.isInterface)
            fun field(name: String) = model.javaClass.getDeclaredField(name).get(model)
            val date = field("h") as Long
            val media = FeedPostMedia(
                postId = field("a") as String,
                takenAt = null,
                caption = "RealMoji ${field("c")}",
                primary = FeedMediaItem(field("e") as String, null, null, null),
                secondary = null,
                capturedAtMillis = date,
                username = field("f") as String,
                postedAt = date.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
                ownerUid = field("b") as String,
            )
            return Proxy.newProxyInstance(actionType.classLoader, arrayOf(actionType), RealMojiDownloadAction(media))
        }
    }
}
