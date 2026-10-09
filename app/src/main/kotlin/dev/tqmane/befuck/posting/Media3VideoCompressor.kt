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

/** Crops, trims and encodes videos with BeReal's bundled Media3 Transformer. */
internal object Media3VideoCompressor {
    private const val TAG = "BeFuck/Video"
    private const val TARGET_FILE_BYTES = 48L * 1024L * 1024L
    private const val MAX_BITRATE = 8_000_000
    private const val MIN_BITRATE = 350_000
    private const val EXPORT_TIMEOUT_MINUTES = 8L

    fun export(context: Context, input: File, output: File, sourceDurationMs: Long,
               sourceWidth: Int, sourceHeight: Int, edit: VideoEdit): File {
        require(input.isFile && input.length() > 0L) { "Selected video is missing or empty" }
        edit.validateSource(sourceDurationMs)
        require(input.canonicalFile != output.canonicalFile) { "Export must preserve the original video" }

        output.delete()
        try {
            transform(context, input, output, sourceWidth, sourceHeight, edit)
            require(output.isFile && output.length() > 0L) { "Video compressor produced no output" }
            require(output.length() <= TARGET_FILE_BYTES) {
                "Video remains larger than the ${TARGET_FILE_BYTES / 1024 / 1024} MiB upload target after compression"
            }
            Log.i(TAG, "Edited gallery video from ${input.length()} to ${output.length()} bytes")
            return output
        } catch (failure: Throwable) {
            output.delete()
            Log.e(TAG, "Media3 could not export the video edit", failure)
            throw IOException("Could not export the selected video range", failure)
        }
    }

    private fun transform(context: Context, input: File, output: File, sourceWidth: Int, sourceHeight: Int, edit: VideoEdit) {
        val thread = HandlerThread("BeFakeVideoTransform").apply { start() }
        val handler = Handler(thread.looper)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val transformer = AtomicReference<Any?>(null)
        handler.post {
            try {
                startTransformer(context, input, output, sourceWidth, sourceHeight, edit, thread, finished, failure, transformer)
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
        sourceWidth: Int,
        sourceHeight: Int,
        edit: VideoEdit,
        thread: HandlerThread,
        finished: CountDownLatch,
        transformFailure: AtomicReference<Throwable?>,
        transformerInstance: AtomicReference<Any?>,
    ) {
        val loader = context.classLoader
        val mediaItemClass = Class.forName("androidx.media3.common.MediaItem", false, loader)
        val itemBuilderClass = Class.forName("androidx.media3.common.MediaItem\$Builder", false, loader)
        val clippingClass = Class.forName("androidx.media3.common.MediaItem\$ClippingConfiguration", false, loader)
        val clipBuilderClass = Class.forName("androidx.media3.common.MediaItem\$ClippingConfiguration\$Builder", false, loader)
        val clipBuilder = clipBuilderClass.getDeclaredConstructor().newInstance()
        clipBuilderClass.getMethod("setStartPositionMs", Long::class.javaPrimitiveType).invoke(clipBuilder, edit.startMs)
        clipBuilderClass.getMethod("setEndPositionMs", Long::class.javaPrimitiveType).invoke(clipBuilder, edit.endMs)
        val clipping = clipBuilderClass.getMethod("build").invoke(clipBuilder)
        val itemBuilder = itemBuilderClass.getDeclaredConstructor().newInstance()
        itemBuilderClass.getMethod("setUri", Uri::class.java).invoke(itemBuilder, Uri.fromFile(input))
        itemBuilderClass.getMethod("setClippingConfiguration", clippingClass).invoke(itemBuilder, clipping)
        val mediaItem = itemBuilderClass.getMethod("build").invoke(itemBuilder)
        val editedClass = Class.forName("androidx.media3.transformer.EditedMediaItem", false, loader)
        val editedBuilderClass = Class.forName("androidx.media3.transformer.EditedMediaItem\$Builder", false, loader)
        val editedBuilder = editedBuilderClass.getDeclaredConstructor(mediaItemClass).newInstance(mediaItem)

        val videoEffects = arrayListOf<Any>()
        if (edit.crop.rotationDegrees != 0) {
            val rotationBuilderClass = Class.forName("androidx.media3.effect.ScaleAndRotateTransformation\$Builder", false, loader)
            val rotationBuilder = rotationBuilderClass.getDeclaredConstructor().newInstance()
            // Canvas rotates clockwise; Media3's positive angles are counterclockwise.
            rotationBuilderClass.getMethod("setRotationDegrees", Float::class.javaPrimitiveType)
                .invoke(rotationBuilder, -edit.crop.rotationDegrees.toFloat())
            videoEffects += requireNotNull(rotationBuilderClass.getMethod("build").invoke(rotationBuilder))
        }
        val cropClass = Class.forName("androidx.media3.effect.Crop", false, loader)
        videoEffects += cropClass.getDeclaredConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType).newInstance(
            edit.crop.left * 2f - 1f, edit.crop.right * 2f - 1f,
            1f - edit.crop.bottom * 2f, 1f - edit.crop.top * 2f)
        val (width, height) = edit.crop.outputSize(sourceWidth, sourceHeight)
        val presentationClass = Class.forName("androidx.media3.effect.Presentation", false, loader)
        videoEffects += requireNotNull(presentationClass.getDeclaredMethod("createForWidthAndHeight",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(null, width, height, presentationClass.getField("LAYOUT_SCALE_TO_FIT").getInt(null)))
        val effectsClass = Class.forName("androidx.media3.transformer.Effects", false, loader)
        val effects = effectsClass.getDeclaredConstructor(List::class.java, List::class.java)
            .newInstance(Collections.emptyList<Any>(), videoEffects)
        editedBuilderClass.methods.single { method ->
            method.name == "setEffects" && method.parameterTypes.contentEquals(arrayOf(effectsClass))
        }.invoke(editedBuilder, effects)
        val edited = editedBuilderClass.methods.single { method ->
            method.name == "build" && method.parameterCount == 0 && editedClass.isAssignableFrom(method.returnType)
        }.invoke(editedBuilder)

        val bitrate = ((TARGET_FILE_BYTES - 2L * 1024L * 1024L) * 8_000L / edit.durationMs)
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

}
