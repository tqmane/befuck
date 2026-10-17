package dev.tqmane.befuck.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import dev.tqmane.befuck.R
import dev.tqmane.befuck.download.FeedMediaItem
import dev.tqmane.befuck.download.FeedPostMedia
import dev.tqmane.befuck.download.PostLocation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SavedPostsMapTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private val clock = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), zone)
    private val photo = FeedMediaItem("https://example.invalid/synthetic.jpg", "image/jpeg", 100, 100)
    private fun post(id: String, user: String = "Example A", date: String = "2026-10-16T15:30:00Z", location: PostLocation? = PostLocation(12.0, 24.0, false)) =
        FeedPostMedia(id, null, "Synthetic map fixture", photo, photo, capturedAtMillis = 0, username = user, postedAt = date, location = location)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun tagged(view: View, tag: String) = views(view).single { it.tag == tag }

    @Test fun mapUsesAllOfTheSelectedLocalDayAndAuthorQueryWithoutTimelinePagination() {
        val calendar = SavedCalendarModel(LocalDate.of(2026, 10, 17))
        val all = (0 until 85).map { post("day-$it") } + listOf(post("other-author", "Example B"), post("previous", date = "2026-10-16T14:59:59Z"), post("missing", location = null))
        calendar.replace(all, zone)
        val day = SavedPostsMapModel(calendar.visible)
        assertEquals(86, day.points.size)
        assertEquals(1, day.missingCount)
        assertFalse(day.points.any { it.postId == "previous" || it.postId == "missing" })
        calendar.query = "example b"
        assertEquals(listOf("other-author"), SavedPostsMapModel(calendar.visible).points.map { it.postId })
    }

    @Test fun sameCoordinatePostsRemainIndividuallySelectableAndLayerDuplicatesDoNotRepeatThem() {
        val model = SavedPostsMapModel(listOf(post("a"), post("b"), post("c")))
        assertTrue(model.selectHits(listOf(2, 0, 1, 2, 0, -1, 99)))
        assertEquals("a", model.selected?.postId)
        assertTrue(model.selectHits(listOf(0, 1, 2)))
        assertEquals("b", model.selected?.postId)
        model.selectHits(listOf(2, 1, 0)); assertEquals("c", model.selected?.postId)
        model.selectHits(listOf(0, 2, 1)); assertEquals("a", model.selected?.postId)
        assertFalse(model.selectHits(emptyList()))
        model.move(-1); assertEquals("c", model.selected?.postId)
        model.move(1); assertEquals("a", model.selected?.postId)
    }

    @Test fun missingCoordinatesAreOmittedButAnExplicitOriginAndApproximationArePreserved() {
        val data = mutableListOf(post("missing", location = null), post("origin", location = PostLocation(0.0, 0.0, true)))
        val model = SavedPostsMapModel(data)
        data.clear()
        assertEquals(1, model.points.size)
        assertEquals(1, model.missingCount)
        assertEquals(PostLocation(0.0, 0.0, true), model.selected?.location)
        val empty = SavedPostsMapModel(listOf(post("none", location = null)))
        empty.move(1); assertNull(empty.selected); assertFalse(empty.selectHits(listOf(0)))
    }

    @Test fun calendarEntryKeepsAuthorAndDayCountsAndHidesForAllDates() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val dialog = SavedPostsScreen.show(activity, activity.resources,
            { listOf(post("a"), post("b", "Example B"), post("missing", location = null)) }, Executor { it.run() }, clock,
            previewSource = FixturePreviewSource())
        shadowOf(Looper.getMainLooper()).idle()
        val root = dialog.findViewById<FrameLayout>(android.R.id.content).getChildAt(0)
        assertTrue(tagged(root, "saved-day-map-open").isEnabled)
        assertEquals(activity.getString(R.string.befuck_saved_map_count, 2, 1), (tagged(root, "saved-day-map-summary") as TextView).text.toString())
        (tagged(root, "saved-search") as EditText).setText("example b")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertEquals(activity.getString(R.string.befuck_saved_map_count, 1, 0), (tagged(root, "saved-day-map-summary") as TextView).text.toString())
        tagged(root, "saved-all-tab").performClick()
        assertEquals(View.GONE, tagged(root, "saved-day-map-entry").visibility)
        dialog.dismiss(); controller.pause().stop().destroy()
    }

    @Test fun mapAccountCardNavigationAndDisposalWorkWithoutInitializingGlOrNetwork() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var opened: FeedPostMedia? = null
        var closes = 0
        val approximate = post("a", location = PostLocation(12.0, 24.0, true))
        // Intentionally unattached. This verifies UI state/lifetime, not a GPU or network renderer.
        val view = SavedPostsMapView(activity, activity.resources, "2026/10/17", listOf(approximate, post("b", "Example B")), zone, { true }, { opened = it }, { closes++ })
        assertEquals("Example A", (tagged(view, "saved-map-owner") as TextView).text.toString())
        assertTrue((tagged(view, "saved-map-details") as TextView).text.contains(activity.getString(R.string.befuck_saved_map_approximate)))
        tagged(view, "saved-map-next").performClick()
        assertEquals("Example B", (tagged(view, "saved-map-owner") as TextView).text.toString())
        tagged(view, "saved-map-preview").performClick()
        assertEquals("b", opened?.postId)
        for ((width, height) in listOf(320 to 720, 1200 to 800)) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            assertTrue(tagged(view, "saved-map-area").height > 0)
        }
        assertTrue(views(view).filterIsInstance<TextView>().any { it.text.contains("OpenStreetMap") && it.text.contains("OpenMapTiles") })
        tagged(view, "saved-map-close").performClick(); assertEquals(1, closes)
        view.dispose(); view.dispose(); opened = null
        tagged(view, "saved-map-preview").performClick(); tagged(view, "saved-map-close").performClick()
        assertNull(opened); assertEquals(1, closes)
        controller.pause().stop().destroy()
    }
}
