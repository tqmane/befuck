package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tqmane.befuck.R
import dev.tqmane.befuck.archive.*
import dev.tqmane.befuck.download.FeedPostMediaCache
import java.io.File
import java.lang.ref.WeakReference
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit SAF export/import. A validated private snapshot is previewed before any live database write. */
internal object ArchiveBackupUi {
    const val EXPORT = 0x4B36
    const val IMPORT = 0x4B37
    private val main = Handler(Looper.getMainLooper())
    internal val transfers = java.util.concurrent.Executors.newSingleThreadExecutor { work -> Thread(work, "BeFuckArchiveTransfer") }
    private val sessions = WeakHashMap<Activity, Session>()
    private data class Pending(val session: WeakReference<Session>, val code: Int)
    private val pending = WeakHashMap<Activity, Pending>()
    private class Session(val activity: Activity, val resources: Resources, val scope: ArchiveAccounts.Scope,
                          val dialog: Dialog, val status: TextView, val changed: () -> Unit) {
        val ended = AtomicBoolean(false)
        var job: FutureTask<Unit>? = null
        var confirmation: Dialog? = null
        var unsubscribe: (() -> Unit)? = null
        var busy = false
        var waiting = false
        val buttons = arrayListOf<Button>()
        var legacy: Button? = null
        var legacyCount = 0
        fun guard() {
            archiveCheck(!ended.get() && !activity.isDestroyed && !activity.isFinishing, "cancelled")
            ArchiveAccounts.requireScope(scope)
            archiveCheck(!Thread.currentThread().isInterrupted, "cancelled")
        }
    }
    fun show(activity: Activity, resources: Resources, changed: () -> Unit = {}) {
        if (activity.isDestroyed || activity.isFinishing) return
        sessions[activity]?.dialog?.dismiss()
        val scope = ArchiveAccounts.snapshot()
        if (scope.uid == null) {
            ExpressiveDialogs.form(activity, resources.getString(R.string.befuck_archive_title), resources.getString(R.string.befuck_archive_account_unavailable),
                null, resources.getString(R.string.befuck_close), null) { true }
            return
        }
        var close: () -> Unit = {}
        val dialog = object : Dialog(activity, android.R.style.Theme_Black_NoTitleBar) {
            override fun dismiss() { close(); super.dismiss() }
        }
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(activity,20),dp(activity,12),dp(activity,12),dp(activity,12)) }
        header.addView(ExpressiveUi.text(activity, resources.getString(R.string.befuck_archive_title), 24f, bold = true), LinearLayout.LayoutParams(0,-2,1f))
        header.addView(ExpressiveUi.iconButton(activity, TablerIcon.X, resources.getString(R.string.befuck_close)) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)))
        root.addView(header)
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(activity,20),dp(activity,12),dp(activity,20),dp(activity,24)) }
        val scroll = ScrollView(activity).apply { addView(column) }
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        column.addView(ExpressiveUi.heading(activity, resources.getString(R.string.befuck_archive_account), scope.uid))
        column.addView(ExpressiveUi.text(activity, resources.getString(R.string.befuck_archive_explanation), 14f, ExpressiveUi.SECONDARY))
        val status = ExpressiveUi.text(activity, resources.getString(R.string.befuck_archive_working), 14f).apply {
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0,dp(activity,20),0,dp(activity,20))
        }
        column.addView(status)
        val session = Session(activity, resources, scope, dialog, status, changed)
        fun button(id: Int, icon: TablerIcon, action: () -> Unit): Button = MaterialControls.button(activity, resources, resources.getString(id), false, icon, action).also {
            column.addView(it, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(activity,12) }); session.buttons += it
        }
        button(R.string.befuck_archive_export, TablerIcon.UPLOAD) {
            session.confirmation = ExpressiveDialogs.confirm(activity, resources.getString(R.string.befuck_archive_export), resources.getString(R.string.befuck_archive_privacy),
                resources.getString(R.string.befuck_archive_choose_destination), resources.getString(R.string.befuck_close)) { launch(session, EXPORT) }
        }.tag = "archive-export"
        button(R.string.befuck_archive_import, TablerIcon.DOWNLOAD) { launch(session, IMPORT) }.tag = "archive-import"
        session.legacy = button(R.string.befuck_archive_associate, TablerIcon.ARROWS_EXCHANGE) {
            session.confirmation = ExpressiveDialogs.confirm(activity, resources.getString(R.string.befuck_archive_associate),
                resources.getString(R.string.befuck_archive_associate_warning, session.legacyCount, scope.uid), resources.getString(R.string.befuck_archive_associate_confirm), resources.getString(R.string.befuck_close)) {
                task(session, { ArchiveRepository.requireStore().associateLegacy(scope.uid, session::guard) }) { report ->
                    FeedPostMediaCache.invalidateArchiveMemory(); session.legacyCount = 0
                    status.text = resources.getString(R.string.befuck_archive_merged, report.added, report.updated, report.unchanged, report.addedUrls)
                    changed()
                }
            }
        }.apply { tag = "archive-associate" }
        column.addView(ExpressiveUi.text(activity, resources.getString(R.string.befuck_archive_limitations), 13f, ExpressiveUi.SECONDARY))
        close = {
            if (session.ended.compareAndSet(false, true)) {
                session.job?.cancel(true); session.confirmation?.dismiss(); session.unsubscribe?.invoke()
                if (sessions[activity] === session) sessions.remove(activity)
            }
        }
        session.unsubscribe = ArchiveAccounts.subscribe { main.post { if (ArchiveAccounts.snapshot() != scope) dialog.dismiss() } }
        dialog.setOnDismissListener { close() }
        sessions[activity] = session
        BoundedScreen.show(activity, dialog, root, 720)
        if (dialog.isShowing) task(session, {
            val db = ArchiveRepository.requireStore()
            db.count(scope.uid) to db.count("")
        }) { (count, unassigned) ->
            session.legacyCount = unassigned
            status.text = resources.getString(R.string.befuck_archive_counts, count, unassigned)
        }
    }
    private fun availability(session: Session) {
        val enabled = !session.busy && !session.waiting && !session.ended.get() && !pending.containsKey(session.activity)
        session.buttons.forEach { it.isEnabled = enabled }
        session.legacy?.isEnabled = enabled && session.legacyCount > 0
    }
    private fun launch(session: Session, code: Int) {
        if (session.busy || pending.containsKey(session.activity)) return
        try {
            session.guard()
            val intent = Intent(if (code == EXPORT) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (code == EXPORT) "application/vnd.sqlite3" else "*/*"
                addFlags(if (code == EXPORT) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (code == EXPORT) putExtra(Intent.EXTRA_TITLE, "BeFuck-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.sqlite")
            }
            pending[session.activity] = Pending(WeakReference(session), code)
            session.waiting = true; availability(session)
            session.activity.startActivityForResult(intent, code)
        } catch (failure: Exception) {
            pending.remove(session.activity); session.waiting = false; availability(session)
            session.status.text = session.resources.getString(R.string.befuck_archive_picker_failed)
        }
    }
    fun onActivityResult(activity: Activity, code: Int, result: Int, intent: Intent?): Boolean {
        if (code != EXPORT && code != IMPORT) return false
        val request = pending[activity] ?: return true
        if (request.code != code) return true
        pending.remove(activity)
        val session = request.session.get() ?: return true
        session.waiting = false
        if (session.ended.get() || sessions[activity] !== session) { sessions[activity]?.let(::availability); return true }
        availability(session)
        val uri = intent?.data
        if (result != Activity.RESULT_OK || uri == null) {
            session.status.text = session.resources.getString(R.string.befuck_archive_cancelled); return true
        }
        if (uri.scheme != "content") { session.status.text = session.resources.getString(R.string.befuck_archive_invalid); return true }
        if (code == EXPORT) {
            task(session, {
                val file = File(activity.cacheDir, "befuck-backup-${UUID.randomUUID()}.sqlite")
                try {
                    ArchiveRepository.requireStore().export(requireNotNull(session.scope.uid), file, session::guard)
                    file.inputStream().use { input ->
                        (activity.contentResolver.openOutputStream(uri, "wt") ?: throw ArchiveFailure("io")).use { output ->
                            ArchiveBackup.copy(input, output, session::guard)
                        }
                    }
                } finally { android.database.sqlite.SQLiteDatabase.deleteDatabase(file) }
            }) { bytes -> session.status.text = session.resources.getString(R.string.befuck_archive_exported, bytes / 1024L) }
        } else {
            task(session, {
                val file = File(activity.cacheDir, "befuck-restore-${UUID.randomUUID()}.sqlite")
                try {
                    (activity.contentResolver.openInputStream(uri) ?: throw ArchiveFailure("io")).use { input ->
                        file.outputStream().use { output -> ArchiveBackup.copy(input, output, session::guard) }
                    }
                    val data = ArchiveBackup.read(file, requireNotNull(session.scope.uid), session::guard)
                    val preview = ArchiveRepository.requireStore().merge(data.records, data.account, dryRun = true, guard = session::guard)
                    data to preview
                } finally { android.database.sqlite.SQLiteDatabase.deleteDatabase(file) }
            }) { (backup, report) ->
                session.status.text = session.resources.getString(R.string.befuck_archive_preview_ready)
                session.confirmation = ExpressiveDialogs.confirm(activity, session.resources.getString(R.string.befuck_archive_confirm_title),
                    session.resources.getString(R.string.befuck_archive_confirm_message, backup.account, report.added, report.updated, report.unchanged, report.addedUrls),
                    session.resources.getString(R.string.befuck_archive_merge), session.resources.getString(R.string.befuck_close)) {
                    task(session, { ArchiveRepository.requireStore().merge(backup.records, backup.account, guard = session::guard) }) { actual ->
                        FeedPostMediaCache.invalidateArchiveMemory()
                        session.status.text = session.resources.getString(R.string.befuck_archive_merged, actual.added, actual.updated, actual.unchanged, actual.addedUrls)
                        session.changed()
                    }
                }
            }
        }
        return true
    }
    private fun <T> task(session: Session, work: () -> T, completed: (T) -> Unit) {
        if (session.busy || session.ended.get()) return
        session.busy = true; availability(session)
        session.status.text = session.resources.getString(R.string.befuck_archive_working)
        // Retry retained writes before an explicit operation; the initializer and readiness barrier use the serial storage worker.
        ArchiveRepository.initialize(session.activity.applicationContext)
        val ready = ArchiveRepository.executor.submit { ArchiveRepository.requireStore() }
        session.job = FutureTask<Unit> {
            val result = runCatching {
                session.guard()
                try { ready.get(30, java.util.concurrent.TimeUnit.SECONDS) }
                catch (failure: java.util.concurrent.ExecutionException) { throw failure.cause ?: failure }
                session.guard()
                work().also { session.guard() }
            }
            main.post {
                if (session.ended.get()) return@post
                if (ArchiveAccounts.snapshot() != session.scope) { session.dialog.dismiss(); return@post }
                session.busy = false
                result.onSuccess(completed).onFailure { failure ->
                    val reason = (failure as? ArchiveFailure)?.reason
                    val message = when (reason) {
                        "account", "account_changed" -> R.string.befuck_archive_wrong_account
                        "author_conflict" -> R.string.befuck_archive_author_conflict
                        "version", "schema" -> R.string.befuck_archive_version
                        "clock" -> R.string.befuck_archive_clock
                        "limit", "pending_limit" -> R.string.befuck_archive_limit
                        "invalid", "corrupt" -> R.string.befuck_archive_invalid
                        else -> R.string.befuck_archive_failed
                    }
                    session.status.text = session.resources.getString(message)
                }
                availability(session)
            }
        }.also { transfers.execute(it) }
    }
    fun onActivityDestroy(activity: Activity) {
        pending.remove(activity)
        sessions.remove(activity)?.dialog?.dismiss()
    }
    private fun dp(activity: Activity, value: Int) = ExpressiveUi.dp(activity, value)
}
