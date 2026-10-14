package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Dialog
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import dev.tqmane.befuck.R
import dev.tqmane.befuck.archive.*
import dev.tqmane.befuck.download.FeedMediaItem
import dev.tqmane.befuck.download.FeedPostMedia
import dev.tqmane.befuck.download.FeedPostMediaCache
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArchiveBackupUiTest {
    private fun record(account:String="account") = ArchiveRecord.observe(account,FeedPostMedia("post",null,"Synthetic local archive",
        FeedMediaItem("https://example.invalid/image?signature=fixture","image/jpeg",100,200),null,capturedAtMillis=100,username="Example",postedAt="2026-10-13T01:00:00Z",ownerUid="author"))
    private fun file(activity:Activity) = File(activity.cacheDir,"archive-ui-${UUID.randomUUID()}.sqlite")
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun root(dialog:Dialog):View=dialog.findViewById<FrameLayout>(android.R.id.content).getChildAt(0)
    private fun click(dialog:Dialog,label:String) { assertTrue(views(root(dialog)).filterIsInstance<Button>().first { it.text.toString()==label }.performClick()) }
    private fun drain() { ArchiveRepository.executor.submit {}.get(30,TimeUnit.SECONDS);ArchiveBackupUi.transfers.submit {}.get(30,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle() }
    private fun fixture(block:(Activity,ArchiveStore)->Unit) {
        RuntimeEnvironment.setQualifiers("ja-rJP-w400dp-h1000dp-mdpi")
        ArchiveAccounts.invalidate();ArchiveAccounts.accept(ArchiveAccounts.snapshot().generation,"account")
        val owner=Robolectric.buildActivity(Activity::class.java).setup();val activity=owner.get();val path=file(activity)
        val store=ArchiveStore.open(path)
        val field=ArchiveRepository::class.java.getDeclaredField("store").apply { isAccessible=true }
        val old=field.get(null);field.set(null,store)
        try { block(activity,store) }
        finally {
            BeFuckGalleryUi.onActivityDestroy(activity);drain();field.set(null,old);store.close();SQLiteDatabase.deleteDatabase(path)
            ArchiveAccounts.invalidate();owner.pause().stop().destroy()
        }
    }
    private class Provider(private val file:File, private val beforeOpen: () -> Unit = {}):ContentProvider() {
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/vnd.sqlite3"
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor { beforeOpen(); return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode)) }
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
    }
    private fun register(activity:Activity,file:File,beforeOpen: () -> Unit = {}):Uri {
        val authority="archive.test."+UUID.randomUUID().toString().replace("-","")
        val provider=Provider(file,beforeOpen)
        provider.attachInfo(activity,ProviderInfo().apply { this.authority=authority })
        ShadowContentResolver.registerProviderInternal(authority,provider)
        return Uri.parse("content://$authority/backup")
    }
    private fun backup(activity:Activity,account:String="account"):File {
        val live=file(activity);val out=file(activity)
        try { ArchiveStore.open(live).use { it.merge(listOf(record(account)),account);it.export(account,out) } }
        finally { SQLiteDatabase.deleteDatabase(live) }
        return out
    }
    private fun startImport(activity:Activity,dialog:Dialog,uri:Uri) {
        click(dialog,activity.getString(R.string.befuck_archive_import))
        val request=shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,request.intent.action)
        assertEquals(ArchiveBackupUi.IMPORT,request.requestCode)
        assertTrue(BeFuckGalleryUi.onActivityResult(activity,request.requestCode,Activity.RESULT_OK,Intent().setData(uri)))
        drain()
    }
    @Test fun actualDocumentProviderExportProducesReadableAccountScopedSqlite() = fixture { activity,store ->
        store.merge(listOf(record()),"account");store.merge(listOf(record("other")),"other")
        val output=file(activity);val uri=register(activity,output)
        ArchiveBackupUi.show(activity,activity.resources);drain();val dialog=ShadowDialog.getLatestDialog()
        click(dialog,activity.getString(R.string.befuck_archive_export))
        click(ShadowDialog.getLatestDialog(),activity.getString(R.string.befuck_archive_choose_destination))
        val request=shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT,request.intent.action);assertEquals(ArchiveBackupUi.EXPORT,request.requestCode)
        assertTrue(BeFuckGalleryUi.onActivityResult(activity,request.requestCode,Activity.RESULT_OK,Intent().setData(uri)))
        drain()
        try { assertEquals(store.records("account"),ArchiveBackup.read(output,"account").records) }
        finally { SQLiteDatabase.deleteDatabase(output) }
    }
    @Test fun restoreRequiresConfirmationUsesStagedSnapshotAndRepeatedImportIsUnchanged() = fixture { activity,store ->
        val source=backup(activity);val uri=register(activity,source);var changed=0
        ArchiveBackupUi.show(activity,activity.resources) { changed++ };drain();val dialog=ShadowDialog.getLatestDialog()
        try {
            startImport(activity,dialog,uri)
            assertEquals(0,store.count("account"))
            // Source bytes may change after the picker/preview. Confirmation must use the validated private snapshot.
            val original=source.readBytes();source.writeText("changed after preview")
            click(ShadowDialog.getLatestDialog(),activity.getString(R.string.befuck_archive_merge));drain()
            assertEquals(1,store.count("account"));assertEquals(1,changed)
            source.writeBytes(original);startImport(activity,dialog,uri)
            click(ShadowDialog.getLatestDialog(),activity.getString(R.string.befuck_archive_merge));drain()
            assertEquals(1,store.count("account"));assertEquals(2,changed)
            val expected=activity.getString(R.string.befuck_archive_merged,0,0,1,0)
            assertTrue(views(root(dialog)).filterIsInstance<TextView>().any { it.text.toString()==expected })
        } finally { SQLiteDatabase.deleteDatabase(source) }
    }
    @Test fun accountSwitchRejectsLateDocumentResultWithoutWriting() = fixture { activity,store ->
        val source=backup(activity);val uri=register(activity,source)
        try {
            ArchiveBackupUi.show(activity,activity.resources);drain();val dialog=ShadowDialog.getLatestDialog()
            click(dialog,activity.getString(R.string.befuck_archive_import))
            ArchiveAccounts.invalidate();shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            BeFuckGalleryUi.onActivityResult(activity,ArchiveBackupUi.IMPORT,Activity.RESULT_OK,Intent().setData(uri));drain()
            assertEquals(0,store.count("account"))
        } finally { SQLiteDatabase.deleteDatabase(source) }
    }
    @Test fun mismatchedAccountShowsErrorWithoutOfferingMerge() = fixture { activity,store ->
        val source=backup(activity,"other");val uri=register(activity,source)
        try {
            ArchiveBackupUi.show(activity,activity.resources);drain();val dialog=ShadowDialog.getLatestDialog()
            startImport(activity,dialog,uri)
            assertSame(dialog,ShadowDialog.getLatestDialog());assertEquals(0,store.count("account"))
            assertTrue(views(root(dialog)).filterIsInstance<TextView>().any { it.text.toString()==activity.getString(R.string.befuck_archive_wrong_account) })
        } finally { SQLiteDatabase.deleteDatabase(source) }
    }
    @Test fun arbitraryAuthorConstructionDoesNotChangeActiveAccount() = fixture { _,_ ->
        class Author(@JvmField val uid:String,@JvmField val userName:String)
        val before=ArchiveAccounts.snapshot()
        FeedPostMediaCache.captureCurrentUser(Author("not-the-viewer","Example"))
        assertEquals(before,ArchiveAccounts.snapshot())
    }
    @Test fun confirmedAssociationMovesLegacyRowsAndRemovesTheSavedScreenNotice() = fixture { activity,store ->
        store.merge(listOf(record("")), "")
        val saved = SavedPostsScreen.show(activity, activity.resources, execute = java.util.concurrent.Executor { it.run() })
        shadowOf(Looper.getMainLooper()).idle()
        val notice = views(root(saved)).filterIsInstance<TextView>().single {
            it.text.toString() == activity.getString(R.string.befuck_archive_unassigned_notice)
        }
        assertEquals(View.VISIBLE, notice.visibility)
        assertTrue(views(root(saved)).single { it.tag == "archive-backup-entry" }.performClick())
        drain()
        val backup = ShadowDialog.getLatestDialog()
        assertEquals(1, store.count(""))
        assertEquals(0, store.count("account"))
        click(backup, activity.getString(R.string.befuck_archive_associate))
        assertEquals(1, store.count(""))
        click(ShadowDialog.getLatestDialog(), activity.getString(R.string.befuck_archive_associate_confirm))
        drain()
        assertEquals(0, store.count(""))
        assertEquals(1, store.count("account"))
        assertEquals(View.GONE, notice.visibility)
        assertTrue(views(root(backup)).any { it.tag == "archive-associate" && !it.isEnabled })
        saved.dismiss()
    }
    @Test fun backupScreenRendersActualControlsAndClosesOnOwnerDestruction() = fixture { activity,store ->
        store.merge(listOf(record()),"account")
        ArchiveBackupUi.show(activity,activity.resources);drain();val dialog=ShadowDialog.getLatestDialog();val root=root(dialog)
        repeat(3) { root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY));root.layout(0,0,400,1000) }
        val image=Bitmap.createBitmap(400,1000,Bitmap.Config.ARGB_8888);root.draw(Canvas(image))
        val output=File("build/reports/ui/archive-backup.png").apply { parentFile.mkdirs() }
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
        assertTrue(views(root).any { it.tag=="archive-export" && it.isEnabled })
        assertTrue(views(root).any { it.tag=="archive-associate" && !it.isEnabled })
        BeFuckGalleryUi.onActivityDestroy(activity);assertFalse(dialog.isShowing)
    }
    @Test fun slowDocumentProviderDoesNotBlockCapturingNewPosts() = fixture { activity,store ->
        val source=backup(activity)
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val uri=register(activity,source) { entered.countDown(); check(release.await(10,TimeUnit.SECONDS)) }
        try {
            ArchiveBackupUi.show(activity,activity.resources);drain();val dialog=ShadowDialog.getLatestDialog()
            click(dialog,activity.getString(R.string.befuck_archive_import))
            BeFuckGalleryUi.onActivityResult(activity,ArchiveBackupUi.IMPORT,Activity.RESULT_OK,Intent().setData(uri))
            assertTrue("Transfer never reached provider",entered.await(5,TimeUnit.SECONDS))
            ArchiveRepository.capture("account",record().post().copy(postId="captured-while-provider-blocked"))
            ArchiveRepository.executor.submit {}.get(3,TimeUnit.SECONDS)
            assertNotNull(store.get("account","captured-while-provider-blocked"))
            assertNull(store.get("account","post"))
        } finally { release.countDown();drain();SQLiteDatabase.deleteDatabase(source) }
    }
}
