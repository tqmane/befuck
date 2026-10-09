package dev.tqmane.befuck.posting

import kotlin.math.min
import kotlin.math.roundToInt

/** Coordinates are relative to the upright image after the user's clockwise rotation. */
data class CropRegion(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    val rotationDegrees: Int = 0,
) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(left < right && top < bottom && rotationDegrees in listOf(0, 90, 180, 270))
    }

    fun outputSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val rotated = rotationDegrees % 180 != 0
        val cropWidth = (if (rotated) height else width) * (right - left)
        val cropHeight = (if (rotated) width else height) * (bottom - top)
        val scale = min(1f, 720f / cropHeight)
        // AVC requires even dimensions; retain the selected ratio to the nearest pixel pair.
        return ((cropWidth * scale / 2).roundToInt().coerceAtLeast(1) * 2) to
            ((cropHeight * scale / 2).roundToInt().coerceAtLeast(1) * 2)
    }

    companion object {
        @JvmStatic fun portrait(width: Int, height: Int): CropRegion {
            require(width > 0 && height > 0)
            val sourceRatio = width.toFloat() / height
            return if (sourceRatio > VideoEdit.ASPECT_RATIO) {
                val span = VideoEdit.ASPECT_RATIO / sourceRatio
                CropRegion(left = (1f - span) / 2, right = (1f + span) / 2)
            } else {
                val span = sourceRatio / VideoEdit.ASPECT_RATIO
                CropRegion(top = (1f - span) / 2, bottom = (1f + span) / 2)
            }
        }
    }
}

data class VideoEdit(val startMs: Long, val endMs: Long, val crop: CropRegion) {
    init {
        require(startMs >= 0 && endMs > startMs && endMs - startMs <= MAX_DURATION_MS)
    }

    val durationMs: Long get() = endMs - startMs

    fun validateSource(durationMs: Long): VideoEdit {
        require(durationMs > 0 && endMs <= durationMs) { "Video selection exceeds its source" }
        return this
    }

    fun withDuration(durationMs: Long): VideoEdit {
        require(durationMs in 1..MAX_DURATION_MS)
        return copy(endMs = startMs + durationMs)
    }

    companion object {
        const val MAX_DURATION_MS = 30_000L
        // BeReal 3.97.1 DualVideo constructs CameraVideoUiModel(height=1440, width=1080).
        const val ASPECT_RATIO = 3f / 4f

        @JvmStatic fun initial(durationMs: Long): VideoEdit {
            require(durationMs > 0)
            return VideoEdit(0, min(durationMs, MAX_DURATION_MS), CropRegion())
        }
    }
}
