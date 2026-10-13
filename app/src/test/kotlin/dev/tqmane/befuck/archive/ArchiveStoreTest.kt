package dev.tqmane.befuck.archive

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.tqmane.befuck.download.FeedMediaItem
import dev.tqmane.befuck.download.FeedPostMedia
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[29,33])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArchiveStoreTest {
    private fun path()=File(RuntimeEnvironment.getApplication().cacheDir,"archive-test-${UUID.randomUUID()}.sqlite")
    private fun record(id:String="post",at:Long=100L,url:String="https://example.invalid/media?sig=$at",user:String?="Alice",owner:String="author")=
        ArchiveRecord.observe("account",FeedPostMedia(id,"2026-10-12T01:00:00Z","caption",FeedMediaItem(url,"image/jpeg",100,200),null,
            capturedAtMillis=at,username=user,postedAt="2026-10-13T01:00:00Z",ownerUid=owner))
    private fun store(action:(ArchiveStore)->Unit) {
        val file=path()
        try { return ArchiveStore.open(file).use(action) } finally { SQLiteDatabase.deleteDatabase(file) }
    }
    @Test fun metadataAndUrlsAreQueryableTypedSQLiteColumns() = store { db ->
        db.merge(listOf(record()),"account")
        db.db.rawQuery("SELECT owner_uid,username,posted_at,posted_ms,caption FROM archive_posts",null).use {
            assertTrue(it.moveToFirst());assertEquals("author",it.getString(0));assertEquals("Alice",it.getString(1))
            assertEquals("2026-10-13T01:00:00Z",it.getString(2));assertEquals(Cursor.FIELD_TYPE_INTEGER,it.getType(3));assertEquals("caption",it.getString(4))
        }
        db.db.rawQuery("SELECT slot,url,width FROM archive_media",null).use {
            assertTrue(it.moveToFirst());assertEquals("back",it.getString(0));assertEquals("https://example.invalid/media?sig=100",it.getString(1));assertEquals(100,it.getInt(2))
        }
    }
    @Test fun repeatMergeReportsUnchangedAndDoesNotDuplicateRows() = store { db ->
        assertEquals(MergeReport(added=1,addedUrls=1),db.merge(listOf(record()),"account"))
        repeat(3) { assertEquals(MergeReport(unchanged=1),db.merge(listOf(record()),"account")) }
        assertEquals(1,db.count("account"));assertEquals(1,db.records("account").single().media.size)
    }
    @Test fun allFieldsAndUrlVariantsRoundTripWithoutWalSidecar() = store { db ->
        db.merge(listOf(record()),"account");db.merge(listOf(record(at=200)),"account")
        val file=path()
        try {
            db.export("account",file)
            assertFalse(File(file.path+"-wal").exists())
            val data=ArchiveBackup.read(file,"account")
            assertEquals(db.records("account"),data.records);assertEquals(2,data.urlCount)
            store { destination ->
                destination.merge(data.records,"account")
                assertEquals(db.records("account"),destination.records("account"))
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }
    @Test fun twoDevicesConvergeWithComplementaryAndOverlappingData() = store { a -> store { b ->
        a.merge(listOf(record(at=200),record(id="only-a")),"account")
        b.merge(listOf(record(at=100,user="OlderName"),record(id="only-b")),"account")
        val fromA=a.records("account");val fromB=b.records("account")
        a.merge(fromB,"account");b.merge(fromA,"account")
        assertEquals(a.records("account"),b.records("account"));assertEquals(3,a.count("account"))
        assertEquals("Alice",a.get("account","post")!!.post().username)
    } }
    @Test fun authorConflictRollsBackEarlierInsertsInSameImport() = store { db ->
        db.merge(listOf(record(id="z")),"account")
        assertThrows(ArchiveFailure::class.java) { db.merge(listOf(record(id="a"),record(id="z",owner="imposter")),"account") }
        assertEquals(1,db.count("account"));assertNull(db.get("account","a"))
        assertEquals("author",db.get("account","z")!!.post().ownerUid)
    }
    @Test fun cancellationAfterFirstWriteRollsBackWholeTransaction() = store { db ->
        var checks=0
        assertThrows(ArchiveFailure::class.java) { db.merge(listOf(record(id="a"),record(id="b")),"account",guard={ if(++checks==3) throw ArchiveFailure("cancelled") }) }
        assertEquals(0,db.count("account"))
    }
    @Test fun dryRunNeverWritesAndCommitRechecksNewerLocalData() = store { db ->
        val backup=record(at=100,user="backup")
        assertEquals(1,db.merge(listOf(backup),"account",dryRun=true).added);assertEquals(0,db.count("account"))
        db.merge(listOf(record(at=300,user="local-new")),"account")
        val result=db.merge(listOf(backup),"account")
        assertEquals(0,result.added);assertEquals(1,result.updated);assertEquals("local-new",db.get("account","post")!!.post().username)
    }
    @Test fun samePostIdInDifferentAccountRemainsIsolated() = store { db ->
        db.merge(listOf(record()),"account");db.merge(listOf(record(owner="other-author").copy(account="other")),"other")
        assertEquals("author",db.get("account","post")!!.post().ownerUid)
        assertEquals("other-author",db.get("other","post")!!.post().ownerUid)
    }
    @Test fun legacyJsonMigratesOnceAndOriginalTableIsRetained() {
        val file=path()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            db.execSQL("CREATE TABLE posts(id TEXT PRIMARY KEY,captured INTEGER NOT NULL,data TEXT NOT NULL)")
            val json=JSONObject().put("id","legacy").put("captured",100).put("username","Alice").put("ownerUid","author")
                .put("postedAt","2026-10-13T01:00:00Z").put("primary",JSONObject().put("url","https://example.invalid/legacy"))
            db.execSQL("INSERT INTO posts VALUES(?,?,?)",arrayOf<Any>("legacy",100,json.toString()))
        }
        try {
            ArchiveStore.open(file).use { db ->
                assertEquals(1,db.count(""));assertEquals(0,db.count("account"))
                assertEquals("Alice",db.get("","legacy")!!.post().username)
                assertEquals(1,db.associateLegacy("account").added)
                assertEquals(0,db.count(""));assertEquals(1,db.count("account"))
                db.db.rawQuery("SELECT count(*) FROM legacy_posts_v1",null).use { it.moveToFirst();assertEquals(1,it.getInt(0)) }
            }
            ArchiveStore.open(file).use { db -> assertEquals(0,db.count(""));assertEquals(1,db.count("account")) }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }
    @Test fun malformedLegacyRecordAbortsMigrationWithoutLosingOldData() {
        val file=path()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            db.execSQL("CREATE TABLE posts(id TEXT PRIMARY KEY,captured INTEGER NOT NULL,data TEXT NOT NULL)")
            db.execSQL("INSERT INTO posts VALUES('broken',100,'not-json')")
        }
        try {
            assertThrows(Exception::class.java) { ArchiveStore.open(file).close() }
            SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
                assertEquals(0,db.version)
                db.rawQuery("SELECT data FROM posts WHERE id='broken'",null).use { assertTrue(it.moveToFirst());assertEquals("not-json",it.getString(0)) }
                db.rawQuery("SELECT name FROM sqlite_master WHERE name='archive_posts'",null).use { assertFalse(it.moveToFirst()) }
            }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }
    @Test fun wrongAccountBackupIsRejectedBeforeAnyMerge() = store { db ->
        db.merge(listOf(record()),"account");val file=path()
        try { db.export("account",file);assertEquals("account",assertThrows(ArchiveFailure::class.java) { ArchiveBackup.read(file,"other") }.reason) }
        finally { SQLiteDatabase.deleteDatabase(file) }
    }
    @Test fun unexpectedSchemaAndSpoofedManifestAreRejected() = store { db ->
        db.merge(listOf(record()),"account")
        for (sql in listOf("CREATE TABLE extra(value TEXT)","UPDATE backup_info SET account_uid='other'","CREATE TRIGGER injected AFTER INSERT ON archive_posts BEGIN DELETE FROM archive_media; END")) {
            val file=path()
            try {
                db.export("account",file)
                SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { it.execSQL(sql) }
                assertThrows(ArchiveFailure::class.java) { ArchiveBackup.read(file,if(sql.startsWith("UPDATE")) "other" else "account") }
                assertEquals(1,db.count("account"))
            } finally { SQLiteDatabase.deleteDatabase(file) }
        }
    }
    @Test fun truncatedUnknownVersionAndManifestCountMismatchAreRejected() = store { db ->
        db.merge(listOf(record()),"account")
        for (mode in 0..2) {
            val file=path()
            try {
                db.export("account",file)
                when(mode) {
                    0 -> java.io.RandomAccessFile(file,"rw").use { it.setLength(100) }
                    else -> SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use {
                        if(mode==1) it.version=99 else it.execSQL("UPDATE backup_info SET post_count=100")
                    }
                }
                assertThrows(Exception::class.java) { ArchiveBackup.read(file,"account") }
            } finally { SQLiteDatabase.deleteDatabase(file) }
        }
    }
    @Test fun emptyAccountBackupIsValidButUnassignedExportIsNot() = store { db ->
        val file=path()
        try { db.export("account",file);assertTrue(ArchiveBackup.read(file,"account").records.isEmpty()) }
        finally { SQLiteDatabase.deleteDatabase(file) }
        assertThrows(ArchiveFailure::class.java) { db.export("",file) }
    }
    @Test fun interruptedExportDeletesOnlyItsTemporaryFile() = store { db ->
        db.merge(listOf(record()),"account");val file=path();var checks=0
        assertThrows(ArchiveFailure::class.java) { db.export("account",file) { if(++checks==3) throw ArchiveFailure("cancelled") } }
        assertFalse(file.exists());assertEquals(1,db.count("account"))
    }
    @Test fun corruptDatabaseOpenNeverAutoDeletesTheOriginalFile() {
        val file=path();val bytes=ByteArray(512) { 0x33.toByte() };file.writeBytes(bytes)
        try {
            assertThrows(Exception::class.java) { ArchiveStore.open(file).close() }
            assertTrue(file.isFile);assertArrayEquals(bytes,file.readBytes())
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }
    @Test fun inconsistentDerivedTimestampAndOrphanMediaAreRejected() = store { db ->
        db.merge(listOf(record()),"account")
        for (sql in listOf("UPDATE archive_posts SET posted_ms=0","UPDATE archive_media SET post_id='orphan'")) {
            val file=path()
            try {
                db.export("account",file)
                SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { it.execSQL(sql) }
                assertThrows(Exception::class.java) { ArchiveBackup.read(file,"account") }
                assertEquals(1,db.count("account"))
            } finally { SQLiteDatabase.deleteDatabase(file) }
        }
    }
    @Test fun combinedDestinationLimitCannotMakeExistingArchiveUnreadable() = store { db ->
        db.db.execSQL("INSERT INTO archive_posts(account_uid,post_id,first_seen,last_seen) WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<50000) SELECT 'account','existing-'||x,0,0 FROM n")
        assertEquals(50000,db.count("account"))
        assertEquals("limit",assertThrows(ArchiveFailure::class.java) { db.merge(listOf(record(id="extra")),"account") }.reason)
        assertEquals(50000,db.count("account"));assertNull(db.get("account","extra"))
        assertEquals("limit",assertThrows(ArchiveFailure::class.java) { db.merge(listOf(record(id="extra")),"account",dryRun=true) }.reason)
        assertEquals(50000,db.count("account"))
    }
}
