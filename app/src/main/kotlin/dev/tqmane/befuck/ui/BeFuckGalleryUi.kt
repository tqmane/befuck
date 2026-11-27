package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import dev.tqmane.befuck.R
import dev.tqmane.befuck.archive.ArchiveAccounts
import dev.tqmane.befuck.download.FeedPostMedia
import dev.tqmane.befuck.posting.BeFakeAuthHeaders
import dev.tqmane.befuck.posting.BeFakeUploadController
import dev.tqmane.befuck.posting.CropRegion
import dev.tqmane.befuck.posting.GalleryMediaFile
import dev.tqmane.befuck.posting.GalleryMediaPreparer
import dev.tqmane.befuck.posting.GalleryPostController
import dev.tqmane.befuck.posting.GalleryPostRequest
import dev.tqmane.befuck.posting.LocationData
import dev.tqmane.befuck.posting.VideoEdit
import dev.tqmane.befuck.symbols.ResolvedSymbols
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.math.min

/** BeFake gallery-post compose UI, activity result routing, and lifecycle coordination. */
object BeFuckGalleryUi {
    const val REQUEST_FRONT = 0x4B31
    const val REQUEST_BACK = 0x4B32
    const val REQUEST_FRONT_VIDEO = 0x4B33
    const val REQUEST_BACK_VIDEO = 0x4B34

    private val sessions = WeakHashMap<Activity, Session>()
    private val pickerOwners = WeakHashMap<Activity, Pair<Session, Int>>()
    private val importer = Executors.newSingleThreadExecutor()
    private val mainThread = Handler(Looper.getMainLooper())
    private val pendingSavedScreens = WeakHashMap<Activity, Any>()

    class DetailDownloadBinding internal constructor(activity: Activity, val post: FeedPostMedia) {
        internal val activity = WeakReference(activity)
        @Volatile internal var disposed = false
    }

    private data class Session(
        val dialog: Dialog,
        val resources: Resources,
        val frontPreview: ImageView,
        val backPreview: ImageView,
        val frontLabel: TextView,
        val backLabel: TextView,
        val caption: EditText,
        val retakes: SeekBar,
        val late: MaterialSwitch,
        val status: TextView,
        var keepMediaUntilUpload: Boolean = false,
        var pendingOfficialPostId: String? = null,
        var front: GalleryMediaFile? = null,
        var back: GalleryMediaFile? = null,
        var location: LocationData? = null,
        var postButton: Button? = null,
        var isSubmitting: Boolean = false,
        var isPreparing: Boolean = false,
        val mediaButtons: MutableMap<Int, Button> = linkedMapOf(),
        var frontPhotoSource: Uri? = null,
        var backPhotoSource: Uri? = null,
        var photoEditor: Dialog? = null,
        var pickerRequest: Int? = null,
        var pendingRequest: GalleryPostRequest? = null,
        val formControls: MutableList<View> = arrayListOf(),
        var videoEditor: VideoCropDialog? = null,
        var videoSourcesInUse: Set<String> = emptySet(),
        var frontClearButton: View? = null,
        var backClearButton: View? = null,
        var frontEditBadge: View? = null,
        var backEditBadge: View? = null,
    )

    @JvmStatic
    fun installEntryButton(activity: Activity, resources: Resources?, symbols: ResolvedSymbols?) {
        HomeFeedEntryOverlay.start(
            activity = activity,
            resources = resources,
            symbols = symbols,
            onOpenGallery = { if (resources != null) open(activity, resources, symbols) },
            onEnteredHome = { FeedDownloadOverlay.removeDetailButton(it) },
            onPauseNeeded = ::onActivityPause,
        )
    }

    @JvmStatic
    fun onActivityPause(activity: Activity) {
        pendingSavedScreens.remove(activity)
        SavedPreviewSession.pause(activity)
        BeFakeLocationDialog.onActivityPause(activity)
        sessions[activity]?.videoEditor?.pausePreview()
        HomeFeedEntryOverlay.stop(activity)
        FeedDownloadOverlay.removeDetailButton(activity)
    }

    @JvmStatic
    fun onActivityDestroy(activity: Activity) {
        SavedPreviewSession.destroy(activity)
        ArchiveBackupUi.onActivityDestroy(activity)
        DialogOwners.destroy(activity)
        pickerOwners.remove(activity)
        onActivityPause(activity)
        BeFakeLocationDialog.onActivityDestroy(activity)
        runCatching { sessions[activity]?.dialog?.dismiss() }
        sessions.remove(activity)?.let { destroyed ->
            if (!destroyed.keepMediaUntilUpload) {
                destroyed.front?.let { GalleryMediaPreparer.discardPreparedMedia(activity, it, destroyed.videoSourcesInUse) }
                destroyed.back?.let { GalleryMediaPreparer.discardPreparedMedia(activity, it, destroyed.videoSourcesInUse) }
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun createInlineFeedDownloadOverlay(
        context: android.content.Context,
        resources: Resources,
        post: FeedPostMedia,
        detailMode: Boolean = false,
    ): View = FeedDownloadOverlay.createInlineOverlay(context, resources, post, detailMode)

    @JvmStatic
    fun updateInlineFeedDownloadOverlay(view: View, post: FeedPostMedia) =
        FeedDownloadOverlay.updateInlineOverlay(view, post)

    @JvmStatic
    fun showDetailDownloadButton(post: FeedPostMedia): DetailDownloadBinding? =
        FeedDownloadOverlay.showDetailButton(post)

    @JvmStatic
    fun disposeDetailDownloadButton(binding: DetailDownloadBinding?) =
        FeedDownloadOverlay.disposeDetailButton(binding)

    @JvmStatic
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (ArchiveBackupUi.onActivityResult(activity, requestCode, resultCode, data)) return true
        val frontSlot = requestCode == REQUEST_FRONT || requestCode == REQUEST_FRONT_VIDEO
        val backSlot = requestCode == REQUEST_BACK || requestCode == REQUEST_BACK_VIDEO
        val video = requestCode == REQUEST_FRONT_VIDEO || requestCode == REQUEST_BACK_VIDEO
        if (!frontSlot && !backSlot) return false
        val owner = pickerOwners[activity] ?: return true
        if (owner.second != requestCode) return true
        pickerOwners.remove(activity)
        val session = owner.first
        session.pickerRequest = null
        if (sessions[activity] !== session || activity.isDestroyed || activity.isFinishing) return true
        updatePostAvailability(session)
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            session.status.text = session.resources.getString(R.string.befuck_selection_cancelled)
            return true
        }
        if (session.isSubmitting || session.isPreparing || session.pendingOfficialPostId != null ||
            !mediaKindAllowed(session, frontSlot, video)
        ) {
            return true
        }
        if (video) showVideoCrop(activity, session, uri, frontSlot) else showPhotoCrop(activity, session, uri, frontSlot)
        return true
    }

    @JvmStatic
    fun onRequestPermissionsResult(activity: Activity, requestCode: Int, grantResults: IntArray): Boolean =
        BeFakeLocationDialog.onRequestPermissionsResult(activity, requestCode, grantResults)

    private fun showVideoCrop(activity: Activity, session: Session, uri: Uri?, frontSlot: Boolean) {
        val previous = if (uri == null) (if (frontSlot) session.front else session.back) else null
        val other = if (frontSlot) session.back else session.front
        session.isPreparing = true
        updatePostAvailability(session)
        session.status.text = session.resources.getString(R.string.befuck_preparing_image)
        importer.execute {
            var source: File? = null
            val result = runCatching {
                val file = if (previous != null) {
                    File(previous.sourcePath ?: previous.path)
                } else {
                    GalleryMediaPreparer.copyVideoSource(activity, requireNotNull(uri), if (frontSlot) "front" else "back")
                }
                source = file
                val metadata = GalleryMediaPreparer.readVideoMetadata(file)
                val otherAvailable = other?.let {
                    GalleryMediaPreparer.readVideoMetadata(File(it.sourcePath ?: it.path)).third - (it.videoEdit?.startMs ?: 0L)
                }
                Triple(file, metadata, otherAvailable)
            }
            mainThread.post {
                if (sessions[activity] !== session || activity.isDestroyed || activity.isFinishing) {
                    if (previous == null) source?.delete()
                    return@post
                }
                result.onSuccess { (file, metadata, otherAvailable) ->
                    session.videoSourcesInUse = setOf(file.absolutePath)
                    val initial = previous?.videoEdit ?: VideoEdit.initial(metadata.third).let {
                        it.withDuration(min(it.durationMs, other?.videoEdit?.durationMs ?: it.durationMs))
                            .copy(crop = CropRegion.portrait(metadata.first, metadata.second))
                    }
                    runCatching {
                        VideoCropDialog(
                            activity,
                            session.resources,
                            file,
                            metadata.first,
                            metadata.second,
                            metadata.third,
                            initial,
                            otherAvailable,
                            onEdited = { edit ->
                                session.videoEditor = null
                                val leased = setOfNotNull(file.absolutePath, other?.sourcePath)
                                session.videoSourcesInUse = leased
                                session.status.text = session.resources.getString(R.string.befuck_video_exporting)
                                importer.execute {
                                    val created = arrayListOf<GalleryMediaFile>()
                                    val prepared = runCatching {
                                        val selected = GalleryMediaPreparer.prepareVideo(
                                            activity,
                                            file,
                                            edit,
                                            if (frontSlot) "front" else "back",
                                        ).also(created::add)
                                        val counterpart = other?.takeIf { it.videoEdit?.durationMs != edit.durationMs }?.let {
                                            GalleryMediaPreparer.prepareMatchingVideo(
                                                activity,
                                                it,
                                                edit.durationMs,
                                                if (frontSlot) "back" else "front",
                                            ).also(created::add)
                                        }
                                        selected to counterpart
                                    }.onFailure {
                                        created.forEach { GalleryMediaPreparer.discardPreparedMedia(activity, it, leased) }
                                    }
                                    mainThread.post {
                                        session.videoSourcesInUse = emptySet()
                                        if (sessions[activity] !== session || activity.isDestroyed || activity.isFinishing) {
                                            prepared.getOrNull()?.let { (selected, counterpart) ->
                                                GalleryMediaPreparer.discardPreparedMedia(activity, selected)
                                                counterpart?.let { GalleryMediaPreparer.discardPreparedMedia(activity, it) }
                                            }
                                            GalleryMediaPreparer.deleteOwnedGalleryFiles(activity, leased)
                                            return@post
                                        }
                                        prepared.onSuccess { (selected, counterpart) ->
                                            counterpart?.let {
                                                applySelectedMedia(activity, Result.success(it), !frontSlot, video = true, expectedSession = session)
                                            }
                                            applySelectedMedia(activity, Result.success(selected), frontSlot, video = true, expectedSession = session)
                                        }.onFailure {
                                            if (previous == null) file.delete()
                                            applySelectedMedia(activity, Result.failure(it), frontSlot, video = true, expectedSession = session)
                                        }
                                    }
                                }
                            },
                            onCancelled = {
                                session.videoEditor = null
                                session.videoSourcesInUse = emptySet()
                                if (previous == null || sessions[activity] !== session) file.delete()
                                session.isPreparing = false
                                if (sessions[activity] === session) {
                                    updatePostAvailability(session)
                                    session.status.text = session.resources.getString(R.string.befuck_selection_cancelled)
                                }
                            },
                        ).also { session.videoEditor = it; it.show() }
                    }.onFailure { failure ->
                        session.videoEditor = null
                        session.videoSourcesInUse = emptySet()
                        if (previous == null) file.delete()
                        applySelectedMedia(activity, Result.failure(failure), frontSlot, video = true, expectedSession = session)
                    }
                }.onFailure { failure ->
                    if (previous == null) source?.delete()
                    applySelectedMedia(activity, Result.failure(failure), frontSlot, video = true, expectedSession = session)
                }
            }
        }
    }

    private fun showPhotoCrop(activity: Activity, session: Session, uri: Uri, frontSlot: Boolean) {
        session.isPreparing = true
        updatePostAvailability(session)
        runCatching {
            session.photoEditor = CropDialog.show(
                activity = activity,
                resources = session.resources,
                imageUri = uri,
                onCropped = { croppedBitmap ->
                    session.photoEditor = null
                    if (sessions[activity] !== session || activity.isDestroyed || activity.isFinishing) {
                        croppedBitmap.recycle()
                        return@show
                    }
                    session.isPreparing = true
                    updatePostAvailability(session)
                    session.status.text = session.resources.getString(R.string.befuck_preparing_image)
                    importer.execute {
                        val result = runCatching {
                            GalleryMediaPreparer.prepareCroppedBitmap(activity, croppedBitmap, if (frontSlot) "front" else "back")
                        }
                        mainThread.post {
                            applySelectedMedia(activity, result, frontSlot, video = false, expectedSession = session, photoSource = uri)
                        }
                    }
                },
                onCancelled = {
                    session.photoEditor = null
                    if (sessions[activity] !== session) return@show
                    session.isPreparing = false
                    updatePostAvailability(session)
                    session.status.text = session.resources.getString(R.string.befuck_selection_cancelled)
                },
            )
        }.onFailure {
            session.isPreparing = false
            updatePostAvailability(session)
            session.status.text = session.resources.getString(R.string.befuck_image_prepare_error)
            android.util.Log.e("BeFuck/UI", "Could not open photo crop", it)
        }
    }

    private fun applySelectedMedia(
        activity: Activity,
        result: Result<GalleryMediaFile>,
        frontSlot: Boolean,
        video: Boolean,
        expectedSession: Session,
        photoSource: Uri? = null,
    ) {
        val session = sessions[activity]
        if (session !== expectedSession || activity.isDestroyed || activity.isFinishing) {
            result.getOrNull()?.let { GalleryMediaPreparer.discardPreparedMedia(activity, it) }
            return
        }
        result.onSuccess { selected ->
            session.isPreparing = false
            if (!mediaKindAllowed(session, frontSlot, selected.isVideo)) {
                GalleryMediaPreparer.discardPreparedMedia(activity, selected)
                updatePostAvailability(session)
                session.status.text = session.resources.getString(R.string.befuck_media_type_mismatch)
                return@onSuccess
            }
            val previewFile = File(selected.previewPath ?: selected.path)
            if (frontSlot) {
                session.front?.let {
                    GalleryMediaPreparer.discardPreparedMedia(activity, it, setOfNotNull(selected.path, selected.previewPath, selected.sourcePath))
                }
                session.front = selected
                session.frontPhotoSource = photoSource
                session.frontPreview.setImageURI(Uri.fromFile(previewFile))
                session.frontLabel.text = selectedMediaLabel(session.resources, selected, true)
            } else {
                session.back?.let {
                    GalleryMediaPreparer.discardPreparedMedia(activity, it, setOfNotNull(selected.path, selected.previewPath, selected.sourcePath))
                }
                session.back = selected
                session.backPhotoSource = photoSource
                session.backPreview.setImageURI(Uri.fromFile(previewFile))
                session.backLabel.text = selectedMediaLabel(session.resources, selected, false)
            }
            (if (frontSlot) session.frontPreview else session.backPreview).scaleType =
                if (selected.isVideo) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            updatePostAvailability(session)
            session.status.text = if (mediaTypeMismatch(session)) {
                session.resources.getString(R.string.befuck_media_type_mismatch)
            } else if (session.front?.isVideo == true && session.back?.isVideo == true) {
                val duration = min(
                    session.front?.videoEdit?.durationMs ?: session.front?.durationMs ?: 0L,
                    session.back?.videoEdit?.durationMs ?: session.back?.durationMs ?: 0L,
                )
                session.resources.getString(R.string.befuck_video_pair_duration, duration / 1000.0)
            } else if (session.front != null && session.back != null) {
                session.resources.getString(
                    R.string.befuck_media_ready,
                    session.resources.getString(if (selected.isVideo) R.string.befuck_pick_video else R.string.befuck_pick_photo),
                )
            } else {
                session.resources.getString(R.string.befuck_media_local_status)
            }
        }.onFailure {
            session.isPreparing = false
            updatePostAvailability(session)
            android.util.Log.e("BeFuck/Video", "Could not prepare the selected media", it)
            session.status.text = session.resources.getString(
                if (video) R.string.befuck_video_prepare_error else R.string.befuck_image_prepare_error,
            )
        }
    }

    private fun mediaTypeMismatch(session: Session): Boolean =
        session.front != null && session.back != null && session.front!!.isVideo != session.back!!.isVideo

    private fun mediaKindAllowed(session: Session, front: Boolean, video: Boolean): Boolean =
        (if (front) session.back else session.front)?.let { it.isVideo == video } ?: true

    private fun updatePostAvailability(session: Session) {
        val ready = session.front != null &&
            session.back != null &&
            !mediaTypeMismatch(session) &&
            !session.isSubmitting &&
            !session.isPreparing &&
            session.pickerRequest == null
        session.postButton?.isEnabled = ready
        session.postButton?.alpha = if (ready) 1f else 0.45f
        val busy = session.isSubmitting || session.isPreparing || session.pendingOfficialPostId != null || session.pickerRequest != null
        session.mediaButtons.forEach { (request, button) ->
            val front = request == REQUEST_FRONT || request == REQUEST_FRONT_VIDEO
            val video = request == REQUEST_FRONT_VIDEO || request == REQUEST_BACK_VIDEO
            button.isSelected = (if (front) session.front else session.back)?.isVideo == video
            button.isEnabled = !busy && mediaKindAllowed(session, front, video)
            button.alpha = if (button.isEnabled) 1f else 0.3f
        }
        session.frontPreview.isEnabled = !busy
        session.backPreview.isEnabled = !busy
        session.frontClearButton?.apply {
            visibility = if (session.front != null) View.VISIBLE else View.GONE
            isEnabled = !busy
            alpha = if (!busy) 1f else 0.4f
        }
        session.backClearButton?.apply {
            visibility = if (session.back != null) View.VISIBLE else View.GONE
            isEnabled = !busy
            alpha = if (!busy) 1f else 0.4f
        }
        session.frontEditBadge?.visibility = if (session.front != null) View.VISIBLE else View.GONE
        session.backEditBadge?.visibility = if (session.back != null) View.VISIBLE else View.GONE
        session.formControls.forEach { it.isEnabled = !session.isSubmitting && session.pendingOfficialPostId == null }
    }

    private fun clearSlot(activity: Activity, session: Session, frontSlot: Boolean) {
        if (sessions[activity] !== session || session.isPreparing || session.isSubmitting ||
            session.pendingOfficialPostId != null || session.pickerRequest != null
        ) {
            return
        }
        val other = if (frontSlot) session.back else session.front
        val preserve = other?.let { setOfNotNull(it.path, it.previewPath, it.sourcePath) }.orEmpty() + session.videoSourcesInUse
        if (frontSlot) {
            session.front?.let { GalleryMediaPreparer.discardPreparedMedia(activity, it, preserve) }
            session.front = null
            session.frontPhotoSource = null
            session.frontPreview.setImageDrawable(TablerIconDrawable(TablerIcon.CAMERA, dp(activity, 40), ExpressiveUi.SECONDARY))
            session.frontPreview.scaleType = ImageView.ScaleType.CENTER
            session.frontLabel.text = session.resources.getString(R.string.befuck_front_missing)
        } else {
            session.back?.let { GalleryMediaPreparer.discardPreparedMedia(activity, it, preserve) }
            session.back = null
            session.backPhotoSource = null
            session.backPreview.setImageDrawable(TablerIconDrawable(TablerIcon.CAMERA, dp(activity, 40), ExpressiveUi.SECONDARY))
            session.backPreview.scaleType = ImageView.ScaleType.CENTER
            session.backLabel.text = session.resources.getString(R.string.befuck_back_missing)
        }
        updatePostAvailability(session)
        session.status.text = session.resources.getString(R.string.befuck_media_local_status)
    }

    internal fun open(activity: Activity, resources: Resources, symbols: ResolvedSymbols?) {
        if (activity.isDestroyed || activity.isFinishing || sessions[activity]?.dialog?.isShowing == true) return
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar)
        val content = FrameLayout(activity).apply { setBackgroundColor(ExpressiveUi.BACKGROUND) }
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(panel, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 12), dp(activity, 12))
        }
        val titles = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ExpressiveUi.text(activity, resources.getString(R.string.befuck_title), 30f, bold = true).apply { letterSpacing = -.03f })
            addView(ExpressiveUi.text(activity, resources.getString(R.string.befuck_gallery_eyebrow), 12f, ExpressiveUi.SECONDARY))
        }
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            ExpressiveUi.iconButton(activity, TablerIcon.SETTINGS_2, resources.getString(R.string.befuck_settings_title)) {
                openSettings(activity, resources)
            },
            LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)),
        )
        header.addView(
            ExpressiveUi.iconButton(activity, TablerIcon.X, resources.getString(R.string.befuck_close)) { dialog.dismiss() },
            LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)),
        )
        panel.addView(header)

        val scroll = ScrollView(activity).apply { isFillViewport = false }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), 0, dp(activity, 16), dp(activity, 24))
        }
        scroll.addView(column)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(ExpressiveUi.heading(activity, resources.getString(R.string.befuck_media_pair), resources.getString(R.string.befuck_intro)))

        val frontPreview = ImageView(activity)
        val backPreview = ImageView(activity)
        val frontLabel = label(activity, resources.getString(R.string.befuck_front_missing))
        val backLabel = label(activity, resources.getString(R.string.befuck_back_missing))
        val mediaButtons = linkedMapOf<Int, Button>()
        val largeText = activity.resources.configuration.fontScale >= 1.5f
        val mediaRow = LinearLayout(activity).apply { orientation = if (largeText) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }

        val clearButtons = arrayOfNulls<View>(2)
        val editBadges = arrayOfNulls<View>(2)

        fun slot(front: Boolean, preview: ImageView, selected: TextView): View {
            val title = resources.getString(if (front) R.string.befuck_download_front else R.string.befuck_download_back)
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = ExpressiveUi.shape(activity)
                setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 12))
            }
            val frame = FrameLayout(activity).apply {
                background = ExpressiveUi.shape(activity, ExpressiveUi.CONTAINER, 22f)
                clipToOutline = true
            }
            preview.apply {
                scaleType = ImageView.ScaleType.CENTER
                setImageDrawable(TablerIconDrawable(TablerIcon.CAMERA, dp(activity, 40), ExpressiveUi.SECONDARY))
                contentDescription = "$title · ${resources.getString(R.string.befuck_crop_title)}"
                isFocusable = true
                setOnClickListener {
                    val session = sessions[activity] ?: return@setOnClickListener
                    if (session.dialog !== dialog || session.isPreparing || session.isSubmitting ||
                        session.pendingOfficialPostId != null || session.pickerRequest != null
                    ) {
                        return@setOnClickListener
                    }
                    val media = if (front) session.front else session.back
                    when {
                        media == null -> {
                            val video = (if (front) session.back else session.front)?.isVideo == true
                            launchPicker(
                                activity,
                                if (front) {
                                    if (video) REQUEST_FRONT_VIDEO else REQUEST_FRONT
                                } else {
                                    if (video) REQUEST_BACK_VIDEO else REQUEST_BACK
                                },
                            )
                        }
                        media.isVideo -> showVideoCrop(activity, session, null, front)
                        else -> showPhotoCrop(
                            activity,
                            session,
                            (if (front) session.frontPhotoSource else session.backPhotoSource) ?: Uri.fromFile(File(media.path)),
                            front,
                        )
                    }
                }
            }
            frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
            frame.addView(
                ExpressiveUi.text(activity, title, 12f, Color.BLACK, true).apply {
                    background = ExpressiveUi.shape(activity, Color.WHITE, 16f)
                    setPadding(dp(activity, 10), dp(activity, 5), dp(activity, 10), dp(activity, 5))
                },
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                    setMargins(dp(activity, 8), dp(activity, 8), dp(activity, 8), 0)
                },
            )
            val clearButton = ImageButton(activity).apply {
                setImageDrawable(TablerIconDrawable(TablerIcon.X, dp(activity, 20), Color.WHITE))
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = resources.getString(R.string.befuck_clear_selection)
                tooltipText = contentDescription
                setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
                background = ExpressiveUi.ripple(activity, 0xB3000000.toInt(), 24f)
                visibility = View.GONE
                setOnClickListener {
                    val activeSession = sessions[activity] ?: return@setOnClickListener
                    if (activeSession.dialog === dialog) clearSlot(activity, activeSession, front)
                }
            }
            frame.addView(
                clearButton,
                FrameLayout.LayoutParams(dp(activity, 48), dp(activity, 48), Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(activity, 4), dp(activity, 4), 0)
                },
            )
            val editBadge = ImageView(activity).apply {
                setImageDrawable(TablerIconDrawable(TablerIcon.CROP, dp(activity, 16), Color.WHITE))
                scaleType = ImageView.ScaleType.CENTER
                background = ExpressiveUi.shape(activity, 0xB3000000.toInt(), 16f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                visibility = View.GONE
            }
            frame.addView(
                editBadge,
                FrameLayout.LayoutParams(dp(activity, 32), dp(activity, 32), Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, dp(activity, 8), dp(activity, 8))
                },
            )
            val slotIndex = if (front) 0 else 1
            clearButtons[slotIndex] = clearButton
            editBadges[slotIndex] = editBadge
            card.addView(frame, LinearLayout.LayoutParams(-1, dp(activity, 190)))
            frame.addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
                val height = minOf(dp(activity, 480), ((right - left) * 4f / 3f).toInt())
                if (height > 0 && view.layoutParams.height != height) {
                    view.layoutParams = view.layoutParams.apply { this.height = height }
                }
            }
            selected.textSize = 12f
            selected.minimumHeight = dp(activity, 52)
            selected.setPadding(dp(activity, 8), dp(activity, 10), dp(activity, 8), dp(activity, 8))
            card.addView(selected, LinearLayout.LayoutParams(-1, -2))

            val choices = LinearLayout(activity)
            listOf(false, true).forEachIndexed { index, video ->
                val request = if (front) {
                    if (video) REQUEST_FRONT_VIDEO else REQUEST_FRONT
                } else {
                    if (video) REQUEST_BACK_VIDEO else REQUEST_BACK
                }
                val choice = resources.getString(if (video) R.string.befuck_pick_video else R.string.befuck_pick_photo)
                val button = ExpressiveUi.button(activity, choice) { launchPicker(activity, request) }.apply {
                    textSize = 12f
                    contentDescription = "$title · $choice"
                    setPadding(dp(activity, 6), dp(activity, 10), dp(activity, 6), dp(activity, 10))
                    val icon = TablerIconDrawable(if (video) TablerIcon.VIDEO else TablerIcon.PHOTO, dp(activity, 20), Color.WHITE)
                    icon.setBounds(0, 0, dp(activity, 20), dp(activity, 20))
                    setCompoundDrawablesRelative(null, icon, null, null)
                    compoundDrawablePadding = dp(activity, 4)
                    background = RippleDrawable(
                        ColorStateList.valueOf(0x33FFFFFF),
                        StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_selected), ExpressiveUi.shape(activity, 0xFF393939.toInt(), 20f, 0xFF858585.toInt()))
                            addState(intArrayOf(), ExpressiveUi.shape(activity, ExpressiveUi.CONTAINER, 20f))
                        },
                        null,
                    )
                }
                mediaButtons[request] = button
                choices.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index == 1) marginStart = dp(activity, 6) })
            }
            card.addView(choices)
            return card
        }

        listOf(slot(false, backPreview, backLabel), slot(true, frontPreview, frontLabel)).forEachIndexed { index, card ->
            mediaRow.addView(
                card,
                (if (largeText) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f)).apply {
                    if (index == 1) {
                        if (largeText) topMargin = dp(activity, 12) else marginStart = dp(activity, 12)
                    }
                },
            )
        }
        column.addView(mediaRow, gapParams(activity, 0, 0, 0, 16))
        column.addView(
            ExpressiveUi.row(activity, resources.getString(R.string.befuck_download_recent), TablerIcon.FOLDER) {
                openDownloadDialog(activity, resources, symbols)
            },
            gapParams(activity, 0, 0, 0, 24),
        )

        fun group(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ExpressiveUi.shape(activity)
            setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16))
            column.addView(this, gapParams(activity, 0, 0, 0, 16))
        }

        val details = group()
        details.addView(sectionLabel(activity, resources.getString(R.string.befuck_caption)))
        val caption = EditText(activity).apply {
            hint = resources.getString(R.string.befuck_caption_hint)
            contentDescription = resources.getString(R.string.befuck_caption)
            setTextColor(Color.WHITE)
            setHintTextColor(ExpressiveUi.SECONDARY)
            textSize = 16f
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = ExpressiveUi.shape(activity, ExpressiveUi.CONTAINER, 20f)
            setPadding(dp(activity, 14), dp(activity, 14), dp(activity, 14), dp(activity, 14))
        }
        details.addView(caption, gapParams(activity, 0, 10, 0, 20))
        details.addView(sectionLabel(activity, resources.getString(R.string.befuck_audience)))

        var selectedAudience = 0
        val audienceRow = LinearLayout(activity).apply { orientation = if (largeText) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val audienceButtons = mutableListOf<Button>()
        fun refreshAudience() {
            audienceButtons.forEachIndexed { index, button ->
                button.isSelected = selectedAudience == index
                button.setTextColor(if (button.isSelected) Color.BLACK else Color.WHITE)
                button.background = ExpressiveUi.ripple(activity, if (button.isSelected) Color.WHITE else ExpressiveUi.CONTAINER, 20f)
            }
        }
        listOf(R.string.befuck_audience_friends, R.string.befuck_audience_fof, R.string.befuck_audience_public).forEachIndexed { index, id ->
            val button = ExpressiveUi.button(activity, resources.getString(id)) {
                selectedAudience = index
                refreshAudience()
            }.apply {
                textSize = 13f
                setPadding(dp(activity, 6), dp(activity, 12), dp(activity, 6), dp(activity, 12))
            }
            audienceButtons += button
            audienceRow.addView(
                button,
                (if (largeText) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f)).apply {
                    if (index > 0) {
                        if (largeText) topMargin = dp(activity, 6) else marginStart = dp(activity, 6)
                    }
                },
            )
        }
        refreshAudience()
        details.addView(audienceRow, gapParams(activity, 0, 10, 0, 0))

        val options = group()
        val retakeRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        retakeRow.addView(sectionLabel(activity, resources.getString(R.string.befuck_retakes)), LinearLayout.LayoutParams(0, -2, 1f))
        val count = ExpressiveUi.text(activity, "0", 24f, bold = true)
        retakeRow.addView(count)
        options.addView(retakeRow)
        val retakes = SeekBar(activity).apply {
            max = 15
            minimumHeight = dp(activity, 48)
            contentDescription = resources.getString(R.string.befuck_retakes)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(ExpressiveUi.OUTLINE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view: SeekBar?, value: Int, fromUser: Boolean) { count.text = value.toString() }
                override fun onStartTrackingTouch(view: SeekBar?) = Unit
                override fun onStopTrackingTouch(view: SeekBar?) = Unit
            })
        }
        options.addView(retakes, LinearLayout.LayoutParams(-1, -2))

        val late = MaterialControls.switch(activity, resources).apply {
            text = resources.getString(R.string.befuck_post_late)
            textSize = 16f
            setTextColor(Color.WHITE)
            minHeight = dp(activity, 56)
            switchPadding = dp(activity, 16)
        }
        options.addView(late)

        val status = label(activity, resources.getString(R.string.befuck_media_local_status)).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val session = Session(
            dialog = dialog,
            resources = resources,
            frontPreview = frontPreview,
            backPreview = backPreview,
            frontLabel = frontLabel,
            backLabel = backLabel,
            caption = caption,
            retakes = retakes,
            late = late,
            status = status,
            frontClearButton = clearButtons[0],
            backClearButton = clearButtons[1],
            frontEditBadge = editBadges[0],
            backEditBadge = editBadges[1],
        )
        val locationRow = ExpressiveUi.row(
            activity,
            resources.getString(R.string.befuck_location_button),
            TablerIcon.MAP_PIN,
            resources.getString(R.string.befuck_location_none),
        ) {
            if (sessions[activity] === session && !session.isSubmitting && session.pendingOfficialPostId == null) {
                BeFakeLocationDialog.show(activity, resources, session.location) { location ->
                    if (sessions[activity] === session && !session.isSubmitting && session.pendingOfficialPostId == null) {
                        session.location = location
                        // Stable labels container owned by ExpressiveUi.row.
                        val row = session.formControls.lastOrNull() as? LinearLayout
                        val labels = row?.getChildAt(1) as? LinearLayout
                        (labels?.getChildAt(1) as? TextView)?.text = location?.let {
                            String.format(Locale.ROOT, "%.5f, %.5f", it.latitude, it.longitude)
                        } ?: resources.getString(R.string.befuck_location_none)
                    }
                }
            }
        }
        column.addView(locationRow)
        session.formControls.addAll(listOf(caption, retakes, late) + audienceButtons + locationRow)
        session.mediaButtons.putAll(mediaButtons)

        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 12))
        }
        footer.addView(status, gapParams(activity, 4, 0, 4, 8))
        val post = MaterialControls.button(activity, resources, resources.getString(R.string.befuck_post_now), true, TablerIcon.UPLOAD) {
            submitPost(
                activity,
                session,
                symbols,
                when (selectedAudience) {
                    1 -> "friend-of-friends"
                    2 -> "public"
                    else -> "friends"
                },
            )
        }
        session.postButton = post
        footer.addView(post, LinearLayout.LayoutParams(-1, -2))
        panel.addView(footer)

        sessions[activity] = session
        updatePostAvailability(session)
        dialog.setOnDismissListener {
            if (sessions[activity] === session) {
                sessions.remove(activity)
                pendingSavedScreens.remove(activity)
            }
            session.photoEditor?.dismiss()
            session.videoEditor?.dismiss()
            BeFakeLocationDialog.onActivityDestroy(activity)
            session.frontPreview.setImageDrawable(null)
            session.backPreview.setImageDrawable(null)
            if (!session.keepMediaUntilUpload) {
                session.front?.let { GalleryMediaPreparer.discardPreparedMedia(activity, it, session.videoSourcesInUse) }
                session.back?.let { GalleryMediaPreparer.discardPreparedMedia(activity, it, session.videoSourcesInUse) }
            }
        }
        BoundedScreen.show(activity, dialog, content, 720)
    }

    private fun submitPost(activity: Activity, session: Session, symbols: ResolvedSymbols?, audience: String) {
        if (sessions[activity] !== session || session.isPreparing || session.isSubmitting || session.pickerRequest != null) return
        val front = session.front ?: return
        val back = session.back ?: return
        if (mediaTypeMismatch(session)) return
        val resources = session.resources
        val request = session.pendingRequest ?: GalleryPostRequest(
            front = front,
            back = back,
            caption = session.caption.text.toString(),
            retakeCount = session.retakes.progress,
            isLate = session.late.isChecked,
            visibility = audience,
            location = session.location,
        )
        val official = (request.front?.isVideo == true) || request.visibility != "friends"
        if (official && (symbols == null || !GalleryPostController.isReady(symbols))) {
            session.status.text = resources.getString(R.string.befuck_official_pipeline_unavailable)
            return
        }
        if (!official && !BeFakeAuthHeaders.hasAuthorization()) {
            session.status.text = resources.getString(R.string.befuck_auth_waiting)
            return
        }
        // The worker, not the dialog, owns source files from the moment submission starts.
        session.keepMediaUntilUpload = true
        session.isSubmitting = true
        session.pendingRequest = request
        session.status.text = resources.getString(R.string.befuck_post_submitting)
        session.postButton?.text = session.status.text
        updatePostAvailability(session)

        val delivered = AtomicBoolean(false)
        val completion = Consumer<Boolean> { success ->
            if (!delivered.compareAndSet(false, true)) return@Consumer
            mainThread.post {
                session.isSubmitting = false
                val active = sessions[activity] === session && !activity.isFinishing && !activity.isDestroyed
                if (success) {
                    session.keepMediaUntilUpload = false
                    (session.pendingRequest ?: request).selectedMedia.forEach { GalleryMediaPreparer.discardPreparedMedia(activity, it) }
                    session.pendingRequest = null
                    session.pendingOfficialPostId = null
                    if (active) {
                        GalleryPostController.refreshHomeFeed(activity, symbols?.versionName ?: dev.tqmane.befuck.runtime.RuntimeKnowledge.versionName)
                        Toast.makeText(
                            activity,
                            resources.getString(if (official) R.string.befuck_post_completed else R.string.befuck_post_accepted),
                            Toast.LENGTH_LONG,
                        ).show()
                        session.dialog.dismiss()
                    }
                } else {
                    session.keepMediaUntilUpload = session.pendingOfficialPostId != null
                    if (!session.keepMediaUntilUpload) {
                        if (!active) (session.pendingRequest ?: request).selectedMedia.forEach {
                            GalleryMediaPreparer.discardPreparedMedia(activity, it)
                        }
                        session.pendingRequest = null
                    }
                    if (active) {
                        session.postButton?.text = resources.getString(R.string.befuck_post_now)
                        session.status.text = resources.getString(R.string.befuck_post_failed)
                        updatePostAvailability(session)
                    }
                }
            }
        }

        fun send(prepared: GalleryPostRequest) {
            session.pendingRequest = prepared
            runCatching {
                GalleryPostController.postNow(
                    activity,
                    requireNotNull(symbols),
                    prepared,
                    session.pendingOfficialPostId,
                    Consumer { id ->
                        mainThread.post {
                            session.pendingOfficialPostId = id
                            session.keepMediaUntilUpload = true
                        }
                    },
                    completion,
                )
            }.onFailure { completion.accept(false) }
        }

        if (official && (request.front?.isVideo == true) && session.pendingOfficialPostId == null) {
            session.status.text = resources.getString(R.string.befuck_video_exporting)
            importer.execute {
                val aligned = runCatching { GalleryMediaPreparer.alignVideoPair(activity, request) }
                mainThread.post {
                    if (sessions[activity] !== session || activity.isDestroyed || activity.isFinishing) {
                        aligned.getOrNull()?.selectedMedia?.forEach { GalleryMediaPreparer.discardPreparedMedia(activity, it) }
                        request.selectedMedia.forEach { GalleryMediaPreparer.discardPreparedMedia(activity, it) }
                        session.keepMediaUntilUpload = false
                        session.isSubmitting = false
                        session.pendingRequest = null
                        return@post
                    }
                    aligned.onSuccess { prepared ->
                        val preserve = prepared.selectedMedia.flatMap { listOfNotNull(it.path, it.previewPath, it.sourcePath) }.toSet()
                        request.selectedMedia.forEach { GalleryMediaPreparer.discardPreparedMedia(activity, it, preserve) }
                        session.front = prepared.front
                        session.back = prepared.back
                        send(prepared)
                    }.onFailure { completion.accept(false) }
                }
            }
        } else if (official) {
            send(request)
        } else {
            runCatching { BeFakeUploadController.postNow(activity, request, completion) }.onFailure { completion.accept(false) }
        }
    }

    internal fun openSettings(activity: Activity, resources: Resources) =
        SettingsDialog.open(activity, resources, importer)

    private fun launchPicker(activity: Activity, requestCode: Int) {
        val session = sessions[activity] ?: return
        if (session.isPreparing || session.isSubmitting || session.pendingOfficialPostId != null || pickerOwners.containsKey(activity)) {
            return
        }
        val front = requestCode == REQUEST_FRONT || requestCode == REQUEST_FRONT_VIDEO
        val video = requestCode == REQUEST_FRONT_VIDEO || requestCode == REQUEST_BACK_VIDEO
        if (requestCode !in setOf(REQUEST_FRONT, REQUEST_BACK, REQUEST_FRONT_VIDEO, REQUEST_BACK_VIDEO) ||
            !mediaKindAllowed(session, front, video)
        ) {
            return
        }
        session.pickerRequest = requestCode
        pickerOwners[activity] = session to requestCode
        updatePostAvailability(session)
        val mime = if (video) "video/*" else "image/*"
        val intents = listOf(
            Intent("android.provider.action.PICK_IMAGES").setType(mime),
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        for (intent in intents) {
            try {
                activity.startActivityForResult(intent, requestCode)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        pickerOwners.remove(activity)
        session.pickerRequest = null
        session.status.text = session.resources.getString(R.string.befuck_photo_picker_unavailable)
        updatePostAvailability(session)
    }

    private fun openDownloadDialog(activity: Activity, resources: Resources, symbols: ResolvedSymbols?) {
        if (pendingSavedScreens.containsKey(activity)) return
        val owner = sessions[activity] ?: return
        if (!owner.dialog.isShowing) return
        if (symbols == null) {
            SavedPostsScreen.show(activity, resources)
            return
        }
        val ticket = Any()
        val before = ArchiveAccounts.snapshot()
        pendingSavedScreens[activity] = ticket
        ArchiveAccounts.refresh(activity, symbols, force = true) { scope ->
            mainThread.post {
                if (pendingSavedScreens[activity] !== ticket) return@post
                pendingSavedScreens.remove(activity)
                if (sessions[activity] === owner && owner.dialog.isShowing && !activity.isDestroyed && !activity.isFinishing &&
                    ArchiveAccounts.snapshot() == scope
                ) {
                    if (scope.uid == null && scope.generation != before.generation) {
                        // Initial API headers can arrive while the first local-user read is pending.
                        if (before.uid == null) openDownloadDialog(activity, resources, symbols)
                        return@post
                    }
                    SavedPostsScreen.show(activity, resources)
                }
            }
        }
    }

    private fun selectedMediaLabel(resources: Resources, media: GalleryMediaFile, front: Boolean): String {
        if (media.isVideo) {
            media.videoEdit?.let { edit ->
                return resources.getString(
                    R.string.befuck_video_selected_range,
                    media.width,
                    media.height,
                    edit.startMs / 1000.0,
                    edit.endMs / 1000.0,
                )
            }
            return resources.getString(
                R.string.befuck_regular_video_selected,
                media.width,
                media.height,
                (media.durationMs ?: 0L) / 1000.0,
            )
        }
        return resources.getString(
            if (front) R.string.befuck_front_selected else R.string.befuck_back_selected,
            media.width,
            media.height,
        )
    }

    private fun label(activity: Activity, text: String) = TextView(activity).apply {
        this.text = text
        textSize = 13f
        setLineSpacing(0f, 1.15f)
        setTextColor(ExpressiveUi.SECONDARY)
    }

    private fun sectionLabel(activity: Activity, text: String) = ExpressiveUi.text(activity, text, 17f, bold = true)

    private fun gapParams(activity: Activity, left: Int, top: Int, right: Int, bottom: Int) =
        LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(activity, left), dp(activity, top), dp(activity, right), dp(activity, bottom))
        }

    private fun dp(activity: Activity, value: Int): Int = ExpressiveUi.dp(activity, value)
}
