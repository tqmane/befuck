package dev.tqmane.befuck.download

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object PostMediaMetadata {
    fun parseTimestamp(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
    }

    fun timestamp(post: FeedPostMedia): Long? = parseTimestamp(post.postedAt) ?: parseTimestamp(post.takenAt)

    fun dateLabel(post: FeedPostMedia, pattern: String): String? = timestamp(post)?.let {
        DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
    }

    fun filename(post: FeedPostMedia, kind: String): String {
        val owner = (post.username?.takeIf { it.isNotBlank() } ?: "BeReal").replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(64)
        val date = dateLabel(post, "yyyyMMdd_HHmmss") ?: "unknown-date"
        val id = post.postId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40)
        return "${owner}_${date}_${kind}_$id"
    }

    fun write(file: File, mime: String, post: FeedPostMedia) {
        val millis = timestamp(post)
        if (mime in listOf("image/jpeg", "image/webp", "image/png") && (millis != null || post.location != null)) {
            val exif = ExifInterface(file.absolutePath)
            if (millis != null) {
                val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                val date = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").format(time)
                for (tag in listOf(ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED)) {
                    exif.setAttribute(tag, date)
                }
                for (tag in listOf(ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED)) {
                    exif.setAttribute(tag, time.offset.id.replace("Z", "+00:00"))
                }
                exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "%03d".format(Math.floorMod(millis, 1000)))
            }
            exif.setAttribute(ExifInterface.TAG_ARTIST, post.username)
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, post.caption)
            post.location?.let { location ->
                // A privacy circle's randomized display center is not a photographic GPS fix.
                // Also remove any inherited source GPS when the host marks this post approximate.
                for (tag in listOf(
                    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
                    ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_PROCESSING_METHOD,
                    ExifInterface.TAG_GPS_H_POSITIONING_ERROR, ExifInterface.TAG_GPS_DOP,
                    ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
                    ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
                )) exif.setAttribute(tag, null)
                if (!location.approximate) exif.setLatLong(location.latitude, location.longitude)
            }
            exif.saveAttributes()
        } else if (mime == "video/mp4" && millis != null) {
            writeMp4Date(file, millis)
        }
        if (millis != null) check(file.setLastModified(millis)) { "Could not set media file timestamp" }
    }

    // Patch only the fixed-width ISO BMFF time fields; encoded audio/video samples stay intact.
    internal fun writeMp4Date(file: File, millis: Long) {
        val seconds = Math.floorDiv(millis, 1000L) + 2_082_844_800L
        require(seconds >= 0)
        RandomAccessFile(file, "rw").use { data ->
            fun boxes(start: Long, end: Long, depth: Int) {
                require(depth <= 8) { "MP4 atom nesting exceeds the supported depth" }
                var position = start
                while (position + 8 <= end) {
                    data.seek(position)
                    var size = data.readInt().toLong() and 0xffffffffL
                    val type = data.readInt()
                    var header = 8L
                    if (size == 1L) { size = data.readLong(); header = 16L }
                    if (size == 0L) size = end - position
                    require(size >= header && size <= end - position) { "Invalid MP4 atom size" }
                    val body = position + header
                    when (type) {
                        0x6d6f6f76, 0x7472616b, 0x6d646961 -> boxes(body, position + size, depth + 1) // moov/trak/mdia
                        0x6d766864, 0x746b6864, 0x6d646864 -> { // mvhd/tkhd/mdhd
                            require(size >= header + 12)
                            data.seek(body)
                            val version = data.readUnsignedByte()
                            require(version in 0..1)
                            data.seek(body + 4)
                            if (version == 1) {
                                require(size >= header + 20)
                                data.writeLong(seconds); data.writeLong(seconds)
                            } else {
                                require(seconds <= 0xffffffffL)
                                data.writeInt(seconds.toInt()); data.writeInt(seconds.toInt())
                            }
                        }
                    }
                    position += size
                }
            }
            boxes(0, data.length(), 0)
        }
    }
}
