package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.tqmane.befuck.archive.ArchiveAccounts
import dev.tqmane.befuck.posting.GalleryMediaFile
import dev.tqmane.befuck.symbols.ResolvedSymbols
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenLifecycleTest {
    private fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
    private fun content(dialog: Dialog): View = dialog.findViewById<FrameLayout>(android.R.id.content).getChildAt(0)
    private fun session(activity: Activity): Any {
        val field = BeFuckGalleryUi::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        return (field.get(BeFuckGalleryUi) as Map<*, *>)[activity]!!
    }
    private fun assign(target: Any, field: String, value: Any?) {
        target.javaClass.getDeclaredField(field).apply { isAccessible = true }.set(target, value)
    }
    @Test fun lateAccountResultCannotReopenSavedPostsAfterItsGalleryWasClosed() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        ArchiveAccounts.invalidate()
        var pending: java.util.function.Consumer<String?>? = null
        ArchiveAccounts.resolve(true, 10000, { pending = it })
        // Only a pending current-account read is exercised; no host classes or network are needed.
        val constructor = ResolvedSymbols::class.java.declaredConstructors.single()
        val symbols = constructor.newInstance(*constructor.parameterTypes.map { type ->
            when (type) {
                String::class.java -> "fixture"
                Boolean::class.javaPrimitiveType -> false
                Map::class.java -> emptyMap<String, Any>()
                List::class.java -> emptyList<Any>()
                else -> null
            }
        }.toTypedArray()) as ResolvedSymbols
        try {
            BeFuckGalleryUi.open(activity, activity.resources, symbols)
            val oldDialog = ShadowDialog.getLatestDialog()
            val entry = children(content(oldDialog)).filterIsInstance<TextView>().single {
                it.text.toString() == activity.getString(dev.tqmane.befuck.R.string.befuck_download_recent)
            }.parent.parent as View
            assertTrue(entry.performClick())
            oldDialog.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            BeFuckGalleryUi.open(activity, activity.resources, symbols)
            val replacement = ShadowDialog.getLatestDialog()
            pending!!.accept("current-viewer")
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(replacement, ShadowDialog.getLatestDialog())
            assertTrue(replacement.isShowing)
        } finally {
            BeFuckGalleryUi.onActivityDestroy(activity)
            ArchiveAccounts.invalidate()
            controller.pause().stop().destroy()
        }
    }
    @Test fun latePickerResultFromDismissedGalleryDoesNotOpenEditorInNewGallery() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        BeFuckGalleryUi.open(activity, activity.resources, null)
        val oldDialog = ShadowDialog.getLatestDialog()
        val preview = children(content(oldDialog)).filterIsInstance<ImageView>().first { it.contentDescription?.contains("crop", true) == true }
        assertTrue(preview.performClick())
        val oldSession = session(activity)
        oldDialog.dismiss()
        BeFuckGalleryUi.open(activity, activity.resources, null)
        val newDialog = ShadowDialog.getLatestDialog()
        assertNotSame(oldSession, session(activity))
        BeFuckGalleryUi.onActivityResult(activity, BeFuckGalleryUi.REQUEST_BACK, Activity.RESULT_OK, Intent().setData(Uri.parse("content://synthetic/old-result")))
        assertSame(newDialog, ShadowDialog.getLatestDialog())
        assertTrue(newDialog.isShowing)
        BeFuckGalleryUi.onActivityDestroy(activity)
        controller.pause().stop().destroy()
    }
    @Test fun uploadLeaseSurvivesDismissButUnleasedTemporaryImageIsRemoved() {
        for (leased in listOf(false, true)) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val activity = controller.get()
            BeFuckGalleryUi.open(activity, activity.resources, null)
            val owner = session(activity)
            val directory = File(activity.filesDir, "befuck/gallery").apply { mkdirs() }
            val file = File(directory, "synthetic-$leased.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val media = GalleryMediaFile(uri = Uri.fromFile(file).toString(), path = file.absolutePath, width = 1, height = 1, isVideo = false, mimeType = "image/webp")
            assign(owner, "front", media)
            assign(owner, "keepMediaUntilUpload", leased)
            assign(owner, "isSubmitting", leased)
            BeFuckGalleryUi.onActivityDestroy(activity)
            assertEquals("Lease=$leased", leased, file.exists())
            file.delete()
            controller.pause().stop().destroy()
        }
    }
    @Test fun ownerDestructionClosesAllOwnedWindowsWithoutReopeningOrAffectingOthers() {
        val one = Robolectric.buildActivity(Activity::class.java).setup()
        val two = Robolectric.buildActivity(Activity::class.java).setup()
        val first = Dialog(one.get()); val second = Dialog(one.get()); val unrelated = Dialog(two.get())
        assertTrue(DialogOwners.show(one.get(), first)); assertTrue(DialogOwners.show(one.get(), second)); assertTrue(DialogOwners.show(two.get(), unrelated))
        DialogOwners.destroy(one.get())
        assertFalse(first.isShowing); assertFalse(second.isShowing); assertTrue(unrelated.isShowing)
        assertFalse(DialogOwners.show(one.get(), Dialog(one.get())))
        DialogOwners.destroy(two.get()); one.pause().stop().destroy(); two.pause().stop().destroy()
    }
    @Test fun japaneseCompactAndLargeFontGalleryKeepPrimaryActionVisible() {
        for (scale in listOf(1f, 1.8f)) {
            RuntimeEnvironment.setQualifiers("ja-rJP-w320dp-h760dp-xhdpi")
            RuntimeEnvironment.setFontScale(scale)
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val activity = controller.get()
            BeFuckGalleryUi.open(activity, activity.resources, null)
            val dialog = ShadowDialog.getLatestDialog(); val root = content(dialog)
            val width = 640; val height = 1520
            repeat(4) {
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            val post = children(root).filterIsInstance<Button>().first { it.text.toString() == activity.resources.getString(dev.tqmane.befuck.R.string.befuck_post_now) }
            // getLocationOnScreen() can be zero for an unattached Robolectric View. Use actual ancestor geometry.
            val bounds = Rect(0, 0, post.width, post.height)
            (root as ViewGroup).offsetDescendantRectToMyCoords(post, bounds)
            assertTrue(post.height >= ExpressiveUi.dp(activity, 48))
            assertTrue("Footer clipped at font scale $scale: $bounds", bounds.top >= 0 && bounds.bottom <= height && bounds.left >= 0 && bounds.right <= width)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val file = File("build/reports/ui/gallery-ja-${if (scale == 1f) "compact" else "large-type"}.png").apply { parentFile.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            BeFuckGalleryUi.onActivityDestroy(activity); controller.pause().stop().destroy()
        }
        RuntimeEnvironment.setFontScale(1f)
    }
}
