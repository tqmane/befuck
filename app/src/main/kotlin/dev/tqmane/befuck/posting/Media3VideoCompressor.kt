package dev.tqmane.befuck.posting

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Uses BeReal's bundled Media3 Transformer to reduce oversized gallery videos before upload. */
internal object Media3VideoCompressor {
    private const val TAG = "BeFuck/Video"
    private const val TARGET_HEIGHT = 720
    private const val TARGET_FILE_BYTES = 48L * 1024L * 1024L
    private const val MAX_BITRATE = 8_000_000
    private const val MIN_BITRATE = 350_000
    private const val EXPORT_TIMEOUT_MINUTES = 8L

    fun compressIfNeeded(context: Context, input: File, output: File, durationMs: Long): File {
        require(input.isFile && input.length() > 0L) { "Selected video is missing or empty" }
        require(durationMs in 1L..30_000L) { "Video must be between 1 ms and 30 seconds" }
        if (input.length() <= TARGET_FILE_BYTES) return input

        output.delete()
        try {
            transform(context, input, output, durationMs)
            require(output.isFile && output.length() > 0L) { "Video compressor produced no output" }
            require(output.length() <= TARGET_FILE_BYTES) {
                "Video remains larger than the ${TARGET_FILE_BYTES / 1024 / 1024} MiB upload target after compression"
            }
            Log.i(TAG, "Compressed gallery video from ${input.length()} to ${output.length()} bytes")
            return output
        } catch (failure: Throwable) {
            output.delete()
            Log.e(TAG, "Media3 could not compress the oversized video", failure)
            throw IOException("Could not compress the selected video to BeReal's upload size", failure)
        }
    }

    private fun transform(context: Context, input: File, output: File, durationMs: Long) {
        val thread = HandlerThread("BeFakeVideoTransform").apply { start() }
        val handler = Handler(thread.looper)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val transformer = AtomicReference<Any?>(null)
        handler.post {
            try {
                startTransformer(context, input, output, durationMs, thread, finished, failure, transformer)
            } catch (error: Throwable) {
                failure.set(error)
                finished.countDown()
                thread.quitSafely()
            }
        }
        if (!finished.await(EXPORT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            handler.post {
                transformer.get()?.let { instance ->
                    runCatching {
                        instance.javaClass.getDeclaredMethod("cancel").apply { isAccessible = true }.invoke(instance)
                    }
                }
                thread.quitSafely()
            }
            throw IOException("Video compression timed out")
        }
        failure.get()?.let { throw IOException("Media3 video compression failed (${it.javaClass.simpleName})") }
    }

    private fun startTransformer(
        context: Context,
        input: File,
        output: File,
        durationMs: Long,
        thread: HandlerThread,
        finished: CountDownLatch,
        transformFailure: AtomicReference<Throwable?>,
        transformerInstance: AtomicReference<Any?>,
    ) {
        val loader = context.classLoader
        val mediaItemClass = Class.forName("androidx.media3.common.MediaItem", false, loader)
        val mediaItem = mediaItemClass.getDeclaredMethod("fromUri", Uri::class.java)
            .invoke(null, Uri.fromFile(input))
        val editedClass = Class.forName("androidx.media3.transformer.EditedMediaItem", false, loader)
        val editedBuilderClass = Class.forName("androidx.media3.transformer.EditedMediaItem\$Builder", false, loader)
        val editedBuilder = editedBuilderClass.getDeclaredConstructor(mediaItemClass).newInstance(mediaItem)

        val presentationClass = Class.forName("androidx.media3.effect.Presentation", false, loader)
        val height = readVideoHeight(input).coerceAtMost(TARGET_HEIGHT).coerceAtLeast(360)
        val presentation = presentationClass.getDeclaredMethod("createForHeight", Int::class.javaPrimitiveType)
            .invoke(null, height)
        val effectsClass = Class.forName("androidx.media3.transformer.Effects", false, loader)
        val effects = effectsClass.getDeclaredConstructor(List::class.java, List::class.java)
            .newInstance(Collections.emptyList<Any>(), listOf(presentation))
        editedBuilderClass.methods.single { method ->
            method.name == "setEffects" && method.parameterTypes.contentEquals(arrayOf(effectsClass))
        }.invoke(editedBuilder, effects)
        val edited = editedBuilderClass.methods.single { method ->
            method.name == "build" && method.parameterCount == 0 && editedClass.isAssignableFrom(method.returnType)
        }.invoke(editedBuilder)

        val bitrate = ((TARGET_FILE_BYTES - 2L * 1024L * 1024L) * 8_000L / durationMs)
            .coerceAtMost(MAX_BITRATE.toLong())
            .coerceAtLeast(MIN_BITRATE.toLong())
            .toInt()
        val encoderSettingsClass = Class.forName("androidx.media3.transformer.VideoEncoderSettings", false, loader)
        val encoderSettingsBuilderClass = Class.forName(
            "androidx.media3.transformer.VideoEncoderSettings\$Builder",
            false,
            loader,
        )
        val encoderSettingsBuilder = encoderSettingsBuilderClass.getDeclaredConstructor().newInstance()
        encoderSettingsBuilderClass.getDeclaredMethod("setBitrate", Int::class.javaPrimitiveType)
            .invoke(encoderSettingsBuilder, bitrate)
        val encoderSettings = encoderSettingsBuilderClass.getDeclaredMethod("build")
            .invoke(encoderSettingsBuilder)
        val encoderFactoryClass = Class.forName("androidx.media3.transformer.DefaultEncoderFactory\$Builder", false, loader)
        val encoderFactoryBuilder = encoderFactoryClass.getDeclaredConstructor(Context::class.java)
            .newInstance(context.applicationContext)
        encoderFactoryClass.getDeclaredMethod("setRequestedVideoEncoderSettings", encoderSettingsClass)
            .invoke(encoderFactoryBuilder, encoderSettings)
        val encoderFactory = encoderFactoryClass.getDeclaredMethod("build").invoke(encoderFactoryBuilder)

        val transformerClass = Class.forName("androidx.media3.transformer.Transformer", false, loader)
        val transformerBuilderClass = Class.forName("androidx.media3.transformer.Transformer\$Builder", false, loader)
        val transformerBuilder = transformerBuilderClass.getDeclaredConstructor(Context::class.java)
            .newInstance(context.applicationContext)
        transformerBuilderClass.getDeclaredMethod("setVideoMimeType", String::class.java)
            .invoke(transformerBuilder, "video/avc")
        runCatching {
            transformerBuilderClass.getDeclaredMethod("setAudioMimeType", String::class.java)
                .invoke(transformerBuilder, "audio/mp4a-latm")
        }
        val setEncoderFactory = transformerBuilderClass.methods.single { method ->
            method.name == "setEncoderFactory" && method.parameterCount == 1 &&
                method.parameterTypes[0].isInstance(encoderFactory)
        }
        setEncoderFactory.invoke(transformerBuilder, encoderFactory)

        val listenerClass = Class.forName("androidx.media3.transformer.Transformer\$Listener", false, loader)
        val listener = Proxy.newProxyInstance(
            listenerClass.classLoader ?: loader,
            arrayOf(listenerClass),
        ) { proxy, method, args ->
            when {
                method.name == "onCompleted" -> {
                    finished.countDown()
                    thread.quitSafely()
                }
                method.name == "onError" -> {
                    val exportError = args?.getOrNull(2) as? Throwable
                    if (exportError != null) Log.e(TAG, "Media3 Transformer export failed", exportError)
                    transformFailure.set(exportError?.let(::describeExportFailure) ?: IOException("Media3 export failed"))
                    finished.countDown()
                    thread.quitSafely()
                }
                method.declaringClass == Any::class.java && method.name == "toString" -> "BeFakeVideoTransformListener"
                method.declaringClass == Any::class.java && method.name == "hashCode" -> System.identityHashCode(proxy)
                method.declaringClass == Any::class.java && method.name == "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
        val addListener = transformerBuilderClass.methods.single { method ->
            method.name == "addListener" && method.parameterTypes.contentEquals(arrayOf(listenerClass))
        }
        addListener.invoke(transformerBuilder, listener)
        val transformer = transformerBuilderClass.getDeclaredMethod("build").invoke(transformerBuilder)
        require(transformerClass.isInstance(transformer)) { "Media3 returned an invalid Transformer" }
        transformerInstance.set(transformer)
        transformerClass.getDeclaredMethod("start", editedClass, String::class.java)
            .invoke(transformer, edited, output.absolutePath)
    }

    private fun describeExportFailure(error: Throwable): Throwable {
        val code = runCatching { error.javaClass.getField("errorCode").getInt(error) }.getOrNull()
        val name = runCatching { error.javaClass.getMethod("getErrorCodeName").invoke(error) as? String }.getOrNull()
        val codec = runCatching {
            error.javaClass.getField("codecInfo").get(error)?.toString()
        }.getOrNull()
        val cause = error.cause
        return IOException(
            "Media3 export code=${name ?: code ?: "unknown"}, codec=${codec ?: "none"}, " +
                "message=${error.message ?: "none"}, cause=${cause?.javaClass?.simpleName ?: "none"}: ${cause?.message ?: "none"}",
            error,
        )
    }

    private fun readVideoHeight(file: File): Int = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: TARGET_HEIGHT
        } finally {
            retriever.release()
        }
    }.getOrDefault(TARGET_HEIGHT)
}
