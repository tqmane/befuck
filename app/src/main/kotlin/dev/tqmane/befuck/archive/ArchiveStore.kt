package dev.tqmane.befuck.archive

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.tqmane.befuck.download.FeedPostMedia
import dev.tqmane.befuck.download.FeedPostMediaCache
import dev.tqmane.befuck.download.PostMediaMetadata
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/** Relational storage. Only schema migration retains the old JSON table; it is never the source of new writes. */
internal class ArchiveStore private constructor(internal val db: SQLiteDatabase) : Closeable {
    private val lock = Any()
    fun capture(account: String, post: FeedPostMedia) = merge(listOf(ArchiveRecord.observe(account, post)), account)
    fun get(account: String, id: String): ArchiveRecord? = synchronized(lock) { transaction { readOne(db, account, id) } }
    fun records(account: String): List<ArchiveRecord> = synchronized(lock) {
        transaction { readAll(db, account) }
    }
    fun count(account: String): Int = synchronized(lock) {
        db.rawQuery("SELECT count(*) FROM archive_posts WHERE account_uid=?", arrayOf(account)).use { it.moveToFirst(); it.getInt(0) }
    }
    fun merge(records: List<ArchiveRecord>, account: String, dryRun: Boolean = false, guard: () -> Unit = {}): MergeReport = synchronized(lock) {
        transaction {
            guard()
            archiveCheck(records.size <= MAX_POSTS && records.map { it.id }.distinct().size == records.size, "limit")
            val beforePostCount = count(account)
            val beforeUrlCount = db.rawQuery("SELECT count(*) FROM archive_media WHERE account_uid=?", arrayOf(account)).use { it.moveToFirst(); it.getLong(0) }
            var report = MergeReport()
            records.forEach { incoming ->
                guard(); ArchiveMerge.validate(incoming); archiveCheck(incoming.account == account, "account")
                val before = readOne(db, account, incoming.id)
                val merged = ArchiveMerge.merge(before, incoming)
                ArchiveMerge.validate(merged)
                report = when {
                    before == null -> report.copy(added = report.added + 1)
                    merged != before -> report.copy(updated = report.updated + 1)
                    else -> report.copy(unchanged = report.unchanged + 1)
                }
                report = report.copy(addedUrls = report.addedUrls + (merged.media.keys - before?.media?.keys.orEmpty()).size)
                if (!dryRun && merged != before) write(db, merged)
            }
            archiveCheck(beforePostCount.toLong() + report.added <= MAX_POSTS && beforeUrlCount + report.addedUrls <= MAX_URLS, "limit")
            guard()
            report
        }
    }
    /** Explicitly associate unassigned legacy records, never infer the viewer account from the author. */
    fun associateLegacy(account: String, guard: () -> Unit = {}): MergeReport = synchronized(lock) {
        archiveCheck(account.isNotBlank(), "account")
        transaction {
            guard()
            val legacy = readAll(db, "").map { it.forAccount(account) }
            val result = merge(legacy, account, guard = guard)
            guard()
            db.delete("archive_media", "account_uid=?", arrayOf(""))
            db.delete("archive_posts", "account_uid=?", arrayOf(""))
            result
        }
    }
    fun export(account: String, target: File, guard: () -> Unit = {}) = synchronized(lock) {
        archiveCheck(account.isNotBlank(), "account")
        archiveCheck(!target.exists(), "destination")
        try {
            // SQL reads see one transaction. Never copy the live DB while committed data can be in its WAL.
            transaction {
                guard()
                SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { out ->
                    createSchema(out)
                    out.execSQL(BACKUP_INFO_SQL)
                    out.beginTransaction()
                    try {
                        var count = 0; var urls = 0
                        db.rawQuery("SELECT post_id FROM archive_posts WHERE account_uid=? ORDER BY post_id", arrayOf(account)).use { ids ->
                            while (ids.moveToNext()) {
                                guard(); archiveCheck(++count <= MAX_POSTS, "limit")
                                val record = requireNotNull(readOne(db, account, ids.getString(0)))
                                ArchiveMerge.validate(record)
                                urls += record.media.size; archiveCheck(urls <= MAX_URLS, "limit")
                                write(out, record)
                            }
                        }
                        val info = ContentValues().apply {
                            put("format", FORMAT); put("version", BACKUP_VERSION); put("account_uid", account)
                            put("exported_at", System.currentTimeMillis()); put("post_count", count); put("url_count", urls)
                        }
                        out.insertOrThrow("backup_info", null, info)
                        out.version = SCHEMA_VERSION
                        out.execSQL("PRAGMA application_id=$APPLICATION_ID")
                        guard(); out.setTransactionSuccessful()
                    } finally { out.endTransaction() }
                }
                guard(); archiveCheck(target.length() in 1..MAX_BYTES, "limit")
            }
        } catch (error: Throwable) { SQLiteDatabase.deleteDatabase(target); throw error }
    }
    private fun <T> transaction(action: () -> T): T {
        db.beginTransaction()
        try { return action().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    override fun close() = synchronized(lock) { db.close() }

    companion object {
        const val SCHEMA_VERSION = 3
        const val BACKUP_VERSION = 2
        const val APPLICATION_ID = 0x42464152
        const val FORMAT = "BeFuck saved post URLs"
        const val MAX_POSTS = 50000
        const val MAX_URLS = 300000
        const val MAX_BYTES = 64L * 1024 * 1024
        private fun columns(fields: Map<String, String>) = fields.entries.joinToString(",") { (name, type) -> "$name $type,${name}_observed_at INTEGER" }
        private fun postsSql(fields: Map<String, String>) = "CREATE TABLE archive_posts (account_uid TEXT NOT NULL,post_id TEXT NOT NULL,first_seen INTEGER NOT NULL,last_seen INTEGER NOT NULL," +
            columns(fields) + ",posted_ms INTEGER,taken_ms INTEGER,PRIMARY KEY(account_uid,post_id))"
        val POSTS_V2_SQL = postsSql(ArchiveRecord.POST_FIELDS_V2)
        val POSTS_SQL = postsSql(ArchiveRecord.POST_FIELDS)
        val MEDIA_SQL = "CREATE TABLE archive_media (account_uid TEXT NOT NULL,post_id TEXT NOT NULL,slot TEXT NOT NULL,url TEXT NOT NULL,first_seen INTEGER NOT NULL,last_seen INTEGER NOT NULL," +
            columns(ArchiveRecord.MEDIA_FIELDS) + ",PRIMARY KEY(account_uid,post_id,slot,url),FOREIGN KEY(account_uid,post_id) REFERENCES archive_posts(account_uid,post_id))"
        const val BACKUP_INFO_SQL = "CREATE TABLE backup_info (format TEXT NOT NULL,version INTEGER NOT NULL,account_uid TEXT NOT NULL,exported_at INTEGER NOT NULL,post_count INTEGER NOT NULL,url_count INTEGER NOT NULL)"
        const val DATE_INDEX_SQL = "CREATE INDEX archive_posts_date ON archive_posts(account_uid,posted_ms,taken_ms)"
        const val AUTHOR_INDEX_SQL = "CREATE INDEX archive_posts_author ON archive_posts(account_uid,owner_uid,username)"
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL(POSTS_SQL); db.execSQL(MEDIA_SQL); db.execSQL(DATE_INDEX_SQL); db.execSQL(AUTHOR_INDEX_SQL)
        }
        fun open(file: File): ArchiveStore {
            file.parentFile?.mkdirs()
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                android.database.DatabaseErrorHandler { /* Never auto-delete the user's original archive on corruption. */ })
            try {
                archiveCheck(db.version <= SCHEMA_VERSION, "version")
                db.setForeignKeyConstraintsEnabled(true)
                if (db.version < SCHEMA_VERSION) {
                    db.beginTransaction()
                    try {
                        if (db.version < 2) {
                            createSchema(db)
                            val hasLegacy = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='posts'", null).use { it.moveToFirst() }
                            if (hasLegacy) {
                                db.rawQuery("SELECT id,captured,data FROM posts", null).use { cursor ->
                                    while (cursor.moveToNext()) {
                                        // Abort and retain the entire old DB on a malformed row, rather than silently losing records.
                                        val post = FeedPostMediaCache.fromJson(JSONObject(cursor.getString(2)))
                                        archiveCheck(post.postId == cursor.getString(0), "migration")
                                        val record = ArchiveRecord.observe("", post.copy(capturedAtMillis = cursor.getLong(1)))
                                        ArchiveMerge.validate(record); write(db, record)
                                    }
                                }
                                db.execSQL("ALTER TABLE posts RENAME TO legacy_posts_v1")
                            }
                        } else {
                            // Existing relational archives keep every post, URL variant and observation time.
                            db.execSQL("ALTER TABLE archive_posts ADD COLUMN location TEXT")
                            db.execSQL("ALTER TABLE archive_posts ADD COLUMN location_observed_at INTEGER")
                        }
                        db.version = SCHEMA_VERSION
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
                db.enableWriteAheadLogging()
                return ArchiveStore(db)
            } catch (error: Throwable) { db.close(); throw error }
        }
        internal fun readOne(db: SQLiteDatabase, account: String, id: String): ArchiveRecord? {
            val header = db.rawQuery("SELECT * FROM archive_posts WHERE account_uid=? AND post_id=?", arrayOf(account, id)).use { c ->
                if (!c.moveToFirst()) null else {
                    val fields = c.fields(if (db.version == 2) ArchiveRecord.POST_FIELDS_V2 else ArchiveRecord.POST_FIELDS)
                    for ((raw, millis) in listOf("posted_at" to "posted_ms", "taken_at" to "taken_ms")) {
                        val expected = PostMediaMetadata.parseTimestamp(fields[raw]?.value)
                        val index = c.getColumnIndexOrThrow(millis)
                        archiveCheck(if (expected == null) c.isNull(index) else !c.isNull(index) && c.long(millis) == expected)
                    }
                    ArchiveRecord(account, id, c.long("first_seen"), c.long("last_seen"), fields, emptyMap())
                }
            } ?: return null
            val variants = linkedMapOf<MediaKey, ArchiveMedia>()
            db.rawQuery("SELECT * FROM archive_media WHERE account_uid=? AND post_id=? ORDER BY slot,url", arrayOf(account, id)).use { c ->
                while (c.moveToNext()) {
                    archiveCheck(variants.size < 1024, "limit")
                    val key = MediaKey(c.text("slot"), c.text("url"))
                    archiveCheck(!variants.containsKey(key))
                    variants[key] = ArchiveMedia(key, c.long("first_seen"), c.long("last_seen"), c.fields(ArchiveRecord.MEDIA_FIELDS))
                }
            }
            return header.copy(media = variants)
        }
        internal fun readAll(db: SQLiteDatabase, account: String): List<ArchiveRecord> {
            val records = arrayListOf<ArchiveRecord>(); var urls = 0
            db.rawQuery("SELECT post_id FROM archive_posts WHERE account_uid=? ORDER BY post_id", arrayOf(account)).use { cursor ->
                while (cursor.moveToNext()) {
                    archiveCheck(records.size < MAX_POSTS, "limit")
                    val record = requireNotNull(readOne(db, account, cursor.getString(0)))
                    urls += record.media.size; archiveCheck(urls <= MAX_URLS, "limit")
                    records += record
                }
            }
            return records
        }
        internal fun write(db: SQLiteDatabase, post: ArchiveRecord) {
            val values = ContentValues().apply {
                put("account_uid", post.account); put("post_id", post.id)
                put("first_seen", post.firstSeen); put("last_seen", post.lastSeen)
                putFields(post.fields, ArchiveRecord.POST_FIELDS)
                PostMediaMetadata.parseTimestamp(post.fields["posted_at"]?.value)?.let { put("posted_ms", it) } ?: putNull("posted_ms")
                PostMediaMetadata.parseTimestamp(post.fields["taken_at"]?.value)?.let { put("taken_ms", it) } ?: putNull("taken_ms")
            }
            // UPDATE instead of REPLACE: never delete the parent row/its media as an UPSERT side effect.
            if (db.update("archive_posts", values, "account_uid=? AND post_id=?", arrayOf(post.account, post.id)) == 0) db.insertOrThrow("archive_posts", null, values)
            post.media.values.forEach { item ->
                val media = ContentValues().apply {
                    put("account_uid", post.account); put("post_id", post.id); put("slot", item.key.slot); put("url", item.key.url)
                    put("first_seen", item.firstSeen); put("last_seen", item.lastSeen); putFields(item.fields, ArchiveRecord.MEDIA_FIELDS)
                }
                if (db.update("archive_media", media, "account_uid=? AND post_id=? AND slot=? AND url=?", arrayOf(post.account, post.id, item.key.slot, item.key.url)) == 0) db.insertOrThrow("archive_media", null, media)
            }
        }
        private fun ContentValues.putFields(values: Map<String, ObservedValue>, specs: Map<String, String>) {
            specs.forEach { (key, type) ->
                val value = values[key]
                if (value == null) { putNull(key); putNull("${key}_observed_at") }
                else {
                    if (type == "INTEGER") put(key, value.value.toLong()) else put(key, value.value)
                    put("${key}_observed_at", value.at)
                }
            }
        }
        private fun Cursor.fields(specs: Map<String, String>): Map<String, ObservedValue> = specs.mapNotNull { (key, type) ->
            val index = getColumnIndexOrThrow(key); val stamp = getColumnIndexOrThrow("${key}_observed_at")
            if (isNull(index)) { archiveCheck(isNull(stamp)); null }
            else {
                archiveCheck(!isNull(stamp) && getType(stamp) == Cursor.FIELD_TYPE_INTEGER)
                archiveCheck(getType(index) == if (type == "INTEGER") Cursor.FIELD_TYPE_INTEGER else Cursor.FIELD_TYPE_STRING)
                key to ObservedValue(getString(index), getLong(stamp))
            }
        }.toMap()
        internal fun Cursor.long(name: String): Long = getColumnIndexOrThrow(name).let { archiveCheck(getType(it) == Cursor.FIELD_TYPE_INTEGER); getLong(it) }
        internal fun Cursor.text(name: String): String = getColumnIndexOrThrow(name).let { archiveCheck(getType(it) == Cursor.FIELD_TYPE_STRING); getString(it) }
    }
}
