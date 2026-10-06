package dev.tqmane.befuck.posting

data class GalleryMediaFile(
    val uri: String,
    val path: String,
    val width: Int,
    val height: Int,
    val isVideo: Boolean = false,
    val mimeType: String = if (isVideo) "video/mp4" else "image/webp",
    val durationMs: Long? = null,
    val previewPath: String? = null,
    val previewWidth: Int? = null,
    val previewHeight: Int? = null,
)

data class LocationData(
    val latitude: Double,
    val longitude: Double,
)

data class GalleryPostRequest(
    val front: GalleryMediaFile?,
    val back: GalleryMediaFile?,
    val caption: String,
    val retakeCount: Int,
    val isLate: Boolean,
    val visibility: String = "friends",
    val location: LocationData? = null,
) {
    val selectedMedia: List<GalleryMediaFile>
        get() = listOfNotNull(front, back).distinctBy { it.path }
}
