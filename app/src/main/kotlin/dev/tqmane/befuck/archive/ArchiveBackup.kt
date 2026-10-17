package dev.tqmane.befuck.archive

import android.database.sqlite.SQLiteDatabase
import dev.tqmane.befuck.archive.ArchiveStore.Companion.long
import dev.tqmane.befuck.archive.ArchiveStore.Companion.text
import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal data class ArchiveBackupData(val account: String, val exportedAt: Long, val records: List<ArchiveRecord>, val urlCount: Int)

/** Only the module's explicit interchange schema is accepted; never ATTACH or replace the active database. */
internal object ArchiveBackup {
    fun copy(input: InputStream, output: OutputStream, guard: () -> Unit = {}): Long {
        var size = 0L
        val buffer = ByteArray(65536)
        while (true) {
            guard(); archiveCheck(!Thread.currentThread().isInterrupted, "cancelled")
            val count = input.read(buffer)
            if (count < 0) break
            size += count; archiveCheck(size <= ArchiveStore.MAX_BYTES, "limit")
            output.write(buffer, 0, count)
        }
        archiveCheck(size > 0); guard(); output.flush()
        return size
    }
    fun read(file: File, account: String, guard: () -> Unit = {}): ArchiveBackupData {
        archiveCheck(account.isNotBlank(), "account")
        archiveCheck(file.length() in 100..ArchiveStore.MAX_BYTES, "limit")
        val magic = ByteArray(16)
        file.inputStream().use { archiveCheck(it.read(magic) == 16 && magic.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) }
        guard()
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            db.execSQL("PRAGMA query_only=ON")
            db.execSQL("PRAGMA trusted_schema=OFF")
            val legacy = db.version == 2
            archiveCheck(legacy || db.version == ArchiveStore.SCHEMA_VERSION, "version")
            val application = db.rawQuery("PRAGMA application_id", null).use { it.moveToFirst(); it.getInt(0) }
            archiveCheck(application == ArchiveStore.APPLICATION_ID, "version")
            val expected = mapOf("archive_posts" to if (legacy) ArchiveStore.POSTS_V2_SQL else ArchiveStore.POSTS_SQL, "archive_media" to ArchiveStore.MEDIA_SQL,
                "backup_info" to ArchiveStore.BACKUP_INFO_SQL, "archive_posts_date" to ArchiveStore.DATE_INDEX_SQL, "archive_posts_author" to ArchiveStore.AUTHOR_INDEX_SQL)
            val actual = linkedMapOf<String, String>()
            db.rawQuery("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY name", null).use { c ->
                var rows = 0
                while (c.moveToNext()) {
                    archiveCheck(++rows <= 16)
                    val type = c.getString(0); val name = c.getString(1)
                    if (name.startsWith("sqlite_autoindex_") && type == "index" && c.isNull(3) && c.getString(2) in setOf("archive_posts", "archive_media")) continue
                    archiveCheck(name in expected && !c.isNull(3) && type in setOf("table", "index"))
                    actual[name] = c.getString(3)
                }
            }
            archiveCheck(actual == expected, "schema")
            db.rawQuery("PRAGMA quick_check(1)", null).use { archiveCheck(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext(), "corrupt") }
            db.rawQuery("PRAGMA foreign_key_check", null).use { archiveCheck(!it.moveToFirst(), "corrupt") }
            var exported = 0L; var expectedPosts = 0L; var expectedUrls = 0L
            db.rawQuery("SELECT * FROM backup_info", null).use { c ->
                archiveCheck(c.moveToFirst())
                archiveCheck(c.text("format") == ArchiveStore.FORMAT && c.long("version") == if (legacy) 1L else ArchiveStore.BACKUP_VERSION.toLong(), "version")
                archiveCheck(c.text("account_uid") == account, "account")
                exported = c.long("exported_at"); expectedPosts = c.long("post_count"); expectedUrls = c.long("url_count")
                archiveCheck(!c.moveToNext())
            }
            archiveCheck(exported in 0..System.currentTimeMillis() + 86_400_000L, "clock")
            archiveCheck(expectedPosts in 0..ArchiveStore.MAX_POSTS.toLong() && expectedUrls in 0..ArchiveStore.MAX_URLS.toLong(), "limit")
            for ((table, expectedCount) in listOf("archive_posts" to expectedPosts, "archive_media" to expectedUrls)) {
                db.rawQuery("SELECT count(*) FROM $table", null).use { archiveCheck(it.moveToFirst() && it.getLong(0) == expectedCount) }
                db.rawQuery("SELECT count(*) FROM $table WHERE account_uid<>?", arrayOf(account)).use { archiveCheck(it.moveToFirst() && it.getLong(0) == 0L, "account") }
            }
            guard()
            val records = ArchiveStore.readAll(db, account)
            archiveCheck(records.size.toLong() == expectedPosts && records.sumOf { it.media.size }.toLong() == expectedUrls)
            records.forEach { guard(); ArchiveMerge.validate(it) }
            return ArchiveBackupData(account, exported, records, expectedUrls.toInt())
        }
    }
}
