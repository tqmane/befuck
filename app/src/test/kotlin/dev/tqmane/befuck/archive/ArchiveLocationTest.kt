package dev.tqmane.befuck.archive

import android.database.sqlite.SQLiteDatabase
import dev.tqmane.befuck.download.FeedPostMedia
import dev.tqmane.befuck.download.PostLocation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [29, 33])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArchiveLocationTest {
    private fun path() = File(RuntimeEnvironment.getApplication().cacheDir, "archive-location-${UUID.randomUUID()}.sqlite")

    private fun legacy(file: File, backup: Boolean): List<ArchiveRecord> = SQLiteDatabase.openDatabase(file.absolutePath, null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
        db.execSQL(ArchiveStore.POSTS_V2_SQL)
        db.execSQL(ArchiveStore.MEDIA_SQL)
        db.execSQL(ArchiveStore.DATE_INDEX_SQL)
        db.execSQL(ArchiveStore.AUTHOR_INDEX_SQL)
        db.execSQL("INSERT INTO archive_posts(account_uid,post_id,first_seen,last_seen,username,username_observed_at) VALUES('account','post',100,100,'Alice',100)")
        db.execSQL("INSERT INTO archive_media(account_uid,post_id,slot,url,first_seen,last_seen,width,width_observed_at) VALUES('account','post','back','https://example.invalid/image',100,100,200,100)")
        db.version = 2
        if (backup) {
            db.execSQL(ArchiveStore.BACKUP_INFO_SQL)
            db.execSQL("INSERT INTO backup_info VALUES(?,1,'account',100,1,1)", arrayOf<Any>(ArchiveStore.FORMAT))
            db.execSQL("PRAGMA application_id=${ArchiveStore.APPLICATION_ID}")
        }
        ArchiveStore.readAll(db, "account")
    }

    private fun located(at: Long = 200L) = FeedPostMedia("post", null, null, null, null,
        capturedAtMillis = at, location = PostLocation(35.68, 139.76, false))

    @Test fun relationalV2UpgradePreservesAllRowsVariantsAndObservationTimes() {
        val file = path()
        try {
            val before = legacy(file, false)
            ArchiveStore.open(file).use { store ->
                assertEquals(3, store.db.version)
                assertEquals(before, store.records("account"))
                assertNull(store.get("account", "post")!!.post().location)
                store.capture("account", located())
                val saved = store.get("account", "post")!!
                assertEquals(located().location, saved.post().location)
                assertEquals("Alice", saved.post().username)
                assertEquals(100L, saved.fields["username"]!!.at)
                assertEquals(before.single().media, saved.media)
            }
            ArchiveStore.open(file).use { store ->
                assertEquals(located().location, store.get("account", "post")!!.post().location)
                assertEquals(1, store.count("account"))
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }

    @Test fun v1BackupImportsWithoutChangingItsFileOrErasingNewerLocation() {
        val file = path(); val destination = path()
        try {
            val expected = legacy(file, true)
            val bytes = file.readBytes()
            val imported = ArchiveBackup.read(file, "account")
            assertEquals(expected, imported.records)
            assertArrayEquals(bytes, file.readBytes())
            ArchiveStore.open(destination).use { store ->
                store.capture("account", located())
                store.merge(imported.records, "account")
                assertEquals(located().location, store.get("account", "post")!!.post().location)
                assertEquals("Alice", store.get("account", "post")!!.post().username)
                assertEquals(1, store.get("account", "post")!!.media.size)
            }
        } finally { SQLiteDatabase.deleteDatabase(file); SQLiteDatabase.deleteDatabase(destination) }
    }

    @Test fun legacyOriginLocationIsRetainedInBackupButNotRenderedAsRealPostLocation() {
        val file = path(); val exported = path()
        try {
            ArchiveStore.open(file).use { store ->
                store.capture("account", located().copy(location = PostLocation(0.0, 0.0, false)))
                assertNull(store.get("account", "post")!!.post().location)
                store.export("account", exported)
                val restored = ArchiveBackup.read(exported, "account").records.single()
                assertEquals("0.0,0.0,0", restored.fields["location"]!!.value)
                assertNull(restored.post().location)
            }
        } finally { SQLiteDatabase.deleteDatabase(file); SQLiteDatabase.deleteDatabase(exported) }
    }

    @Test fun currentBackupRoundTripsLocationAndPrivacyAsOneObservedValue() {
        val file = path(); val exported = path()
        try {
            ArchiveStore.open(file).use { store ->
                store.capture("account", located().copy(location = PostLocation(-33.8, 151.2, true)))
                store.export("account", exported)
                val restored = ArchiveBackup.read(exported, "account").records.single()
                assertEquals(store.get("account", "post"), restored)
                assertEquals(PostLocation(-33.8, 151.2, true), restored.post().location)
                assertEquals(200L, restored.fields["location"]!!.at)
            }
        } finally { SQLiteDatabase.deleteDatabase(file); SQLiteDatabase.deleteDatabase(exported) }
    }
}
