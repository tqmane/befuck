package dev.tqmane.befuck.archive

import dev.tqmane.befuck.download.FeedMediaItem
import dev.tqmane.befuck.download.FeedPostMedia
import dev.tqmane.befuck.download.PostMediaMetadata
import dev.tqmane.befuck.download.PostLocation
import java.net.URI

internal class ArchiveFailure(val reason: String) : Exception(reason)
internal fun archiveCheck(condition: Boolean, reason: String = "invalid") { if (!condition) throw ArchiveFailure(reason) }

/** The timestamp is when this exact value was observed, NEVER when a backup was imported. */
internal data class ObservedValue(val value: String, val at: Long)
internal data class MediaKey(val slot: String, val url: String)
internal data class ArchiveMedia(val key: MediaKey, val firstSeen: Long, val lastSeen: Long, val fields: Map<String, ObservedValue>)
internal data class ArchiveRecord(
    val account: String, val id: String, val firstSeen: Long, val lastSeen: Long,
    val fields: Map<String, ObservedValue>, val media: Map<MediaKey, ArchiveMedia>,
) {
    fun post(): FeedPostMedia {
        fun value(name: String) = fields[name]?.value
        fun item(slot: String): FeedMediaItem? = media.values.filter { it.key.slot == slot }
            .maxWithOrNull(compareBy<ArchiveMedia> { it.lastSeen }.thenBy { it.key.url })?.let { variant ->
                FeedMediaItem(variant.key.url, variant.fields["mime_type"]?.value,
                    variant.fields["width"]?.value?.toIntOrNull(), variant.fields["height"]?.value?.toIntOrNull())
            }
        return FeedPostMedia(id, value("taken_at"), value("caption"), item("back"), item("front"),
            value("owner_uid")?.let { account.isNotEmpty() && it == account } ?: value("is_own")?.let { it == "1" },
            lastSeen, value("username"), value("posted_at"), item("bts"), value("owner_uid"), value("moment_id"), value("is_main")?.let { it == "1" },
            PostLocation.decode(value("location")))
    }
    fun forAccount(uid: String) = copy(account = uid, fields = fields - "is_own")

    companion object {
        val POST_FIELDS_V2 = linkedMapOf("owner_uid" to "TEXT", "username" to "TEXT", "posted_at" to "TEXT",
            "taken_at" to "TEXT", "caption" to "TEXT", "moment_id" to "TEXT", "is_main" to "INTEGER", "is_own" to "INTEGER")
        val POST_FIELDS = POST_FIELDS_V2 + ("location" to "TEXT")
        val MEDIA_FIELDS = linkedMapOf("mime_type" to "TEXT", "width" to "INTEGER", "height" to "INTEGER")
        val SLOTS = setOf("back", "front", "bts")
        fun observe(account: String, post: FeedPostMedia): ArchiveRecord {
            val at = post.capturedAtMillis.coerceAtLeast(0L)
            fun fields(values: Map<String, String?>) = values.mapNotNull { (key, value) -> value?.let { key to ObservedValue(it, at) } }.toMap()
            val data = fields(mapOf("owner_uid" to post.ownerUid?.takeIf(String::isNotBlank), "username" to post.username?.takeIf(String::isNotBlank),
                "posted_at" to post.postedAt, "taken_at" to post.takenAt, "caption" to post.caption,
                "moment_id" to post.momentId, "is_main" to post.isMain?.let { if (it) "1" else "0" }, "is_own" to post.isOwnPost?.let { if (it) "1" else "0" },
                "location" to post.location?.encode()))
            val variants = listOf("back" to post.primary, "front" to post.secondary, "bts" to post.bts).mapNotNull { (slot, item) ->
                item?.let {
                    val key = MediaKey(slot, it.url)
                    key to ArchiveMedia(key, at, at, fields(mapOf("mime_type" to (it.mimeType?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { mime -> mime.startsWith("image/") || mime.startsWith("video/") } ?: if (it.isVideo) "video/mp4" else null), "width" to it.width?.toString(), "height" to it.height?.toString())))
                }
            }.toMap()
            return ArchiveRecord(account, post.postId, at, at, data, variants)
        }
        fun validId(value: String, allowEmpty: Boolean = false) = (allowEmpty || value.isNotBlank()) && value.length <= 512 && value.none { it.code < 32 }
        fun validUrl(value: String): Boolean = value.length in 1..8192 && runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
        }.getOrDefault(false)
    }
}

/** A deterministic, idempotent union. Absence is not a delete; conflicting author IDs abort the transaction. */
internal object ArchiveMerge {
    fun choose(left: ObservedValue?, right: ObservedValue?): ObservedValue? = when {
        left == null -> right
        right == null -> left
        right.at > left.at || right.at == left.at && right.value > left.value -> right
        else -> left
    }
    private fun fields(left: Map<String, ObservedValue>, right: Map<String, ObservedValue>) =
        (left.keys + right.keys).associateWith { key ->
            val a = left[key]; val b = right[key]
            val winner = if (key == "posted_at" || key == "taken_at") {
                val validA = PostMediaMetadata.parseTimestamp(a?.value) != null
                val validB = PostMediaMetadata.parseTimestamp(b?.value) != null
                when { validA && !validB -> a; validB && !validA -> b; else -> choose(a, b) }
            } else choose(a, b)
            requireNotNull(winner)
        }
    fun merge(left: ArchiveRecord?, right: ArchiveRecord): ArchiveRecord {
        if (left == null) return right
        archiveCheck(left.account == right.account && left.id == right.id, "account")
        val oldOwner = left.fields["owner_uid"]?.value
        val newOwner = right.fields["owner_uid"]?.value
        archiveCheck(oldOwner == null || newOwner == null || oldOwner == newOwner, "author_conflict")
        val media = (left.media.keys + right.media.keys).associateWith { key ->
            val a = left.media[key]; val b = right.media[key]
            when {
                a == null -> requireNotNull(b)
                b == null -> a
                else -> a.copy(firstSeen = minOf(a.firstSeen, b.firstSeen), lastSeen = maxOf(a.lastSeen, b.lastSeen), fields = fields(a.fields, b.fields))
            }
        }
        return left.copy(firstSeen = minOf(left.firstSeen, right.firstSeen), lastSeen = maxOf(left.lastSeen, right.lastSeen),
            fields = fields(left.fields, right.fields), media = media)
    }
    fun validate(record: ArchiveRecord, now: Long = System.currentTimeMillis()) {
        archiveCheck(ArchiveRecord.validId(record.account, true) && ArchiveRecord.validId(record.id))
        archiveCheck(record.firstSeen in 0..record.lastSeen && record.lastSeen <= now + 86_400_000L, "clock")
        fun values(fields: Map<String, ObservedValue>, specs: Map<String, String>, start: Long, end: Long) {
            archiveCheck(fields.keys.all { it in specs })
            fields.forEach { (key, value) ->
                archiveCheck(value.at in start..end)
                archiveCheck(value.value.length <= if (key == "caption") 32768 else 1024)
                archiveCheck(!value.value.contains('\u0000'))
                if (specs[key] == "INTEGER") {
                    val number = value.value.toLongOrNull()
                    archiveCheck(number != null && number.toString() == value.value)
                    archiveCheck(if (key.startsWith("is_")) number in 0L..1L else number in 1L..100000L)
                }
                if (key == "owner_uid") archiveCheck(ArchiveRecord.validId(value.value))
                if (key == "mime_type") archiveCheck(value.value.startsWith("image/") || value.value.startsWith("video/"))
                if (key == "location") archiveCheck(PostLocation.decode(value.value) != null, "location")
            }
        }
        values(record.fields, ArchiveRecord.POST_FIELDS, record.firstSeen, record.lastSeen)
        archiveCheck(record.media.size <= 1024, "limit")
        record.media.forEach { (key, media) ->
            archiveCheck(key == media.key && key.slot in ArchiveRecord.SLOTS && ArchiveRecord.validUrl(key.url))
            archiveCheck(media.firstSeen >= record.firstSeen && media.lastSeen <= record.lastSeen && media.firstSeen <= media.lastSeen)
            values(media.fields, ArchiveRecord.MEDIA_FIELDS, media.firstSeen, media.lastSeen)
        }
    }
}

internal data class MergeReport(val added: Int = 0, val updated: Int = 0, val unchanged: Int = 0, val addedUrls: Int = 0) {
    val total get() = added + updated + unchanged
}
