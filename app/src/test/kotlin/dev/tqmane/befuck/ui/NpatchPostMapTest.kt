package dev.tqmane.befuck.ui

import android.app.Activity
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.FrameLayout
import android.widget.TextView
import dev.tqmane.befuck.R
import dev.tqmane.befuck.posting.LocationData
import dev.tqmane.befuck.symbols.PostMapSymbolResolver
import dev.tqmane.befuck.symbols.PostMapSnapshot
import dev.tqmane.befuck.symbols.PostMapSymbols
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NpatchPostMapTest {
    // Synthetic host state, not copies of BeReal code or real account/location data.
    class Cell(private var value: Any?) {
        var reads = 0
        fun getValue(): Any? { reads++; return value }
        fun setValue(value: Any?) { this.value = value }
    }
    class Coordinate(@JvmField val latitude: Double, @JvmField val longitude: Double)
    class Ready(@JvmField val post: Coordinate?, @JvmField val user: Coordinate?, @JvmField val approximate: Boolean)
    class Photos(@JvmField val low: Bitmap?, @JvmField val high: Bitmap?)
    class Navigation { var closes = 0; fun close() { closes++ } }
    class Owner {
        @JvmField val state = Cell(null)
        @JvmField val userState = Cell(null)
        @JvmField val photos = Cell(null)
        @JvmField val toggle = Cell(false)
        @JvmField val permission = Cell(false)
        @JvmField val navigation = Navigation()
        var permissionRequests = 0
        fun invoke(a: Any?, b: Any?, c: Any?): Any = Unit
    }
    fun interface Callback { fun invoke(value: Any?): Any? }
    class FakeComposer {
        lateinit var factory: Callback
        lateinit var update: Callback
        lateinit var release: Callback
        var reset: Callback? = null
        var modifier: Any? = null
    }
    companion object {
        @JvmStatic fun androidView(factory: Callback, modifier: Any, reset: Callback?, release: Callback,
                                   update: Callback, composer: FakeComposer, flags: Int, mask: Int) {
            assertEquals(0, flags); assertEquals(0, mask)
            composer.factory = factory; composer.modifier = modifier; composer.reset = reset
            composer.update = update; composer.release = release
        }
    }

    private fun binding(): PostMapSymbols {
        val fields = listOf("state", "userState", "photos", "toggle", "permission", "navigation")
            .associateWith { Owner::class.java.getField(it) }.toMutableMap()
        for (name in listOf("post", "user", "approximate")) fields[name] = Ready::class.java.getField(name)
        for (name in listOf("low", "high")) fields[name] = Photos::class.java.getField(name)
        for (name in listOf("latitude", "longitude")) fields[name] = Coordinate::class.java.getField(name)
        return PostMapSymbols(
            Owner::class.java.getMethod("invoke", Any::class.java, Any::class.java, Any::class.java),
            NpatchPostMapTest::class.java.getMethod("androidView", Callback::class.java, Any::class.java,
                Callback::class.java, Callback::class.java, Callback::class.java, FakeComposer::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            "fillMaxSize", Unit, Callback::class.java,
            Cell::class.java.getMethod("getValue"), Cell::class.java.getMethod("setValue", Any::class.java),
            Navigation::class.java.getMethod("close"), fields, { (it as Owner).permissionRequests++ },
        )
    }

    @Test fun displayStateIsReadDuringCompositionAndNeverReplacedWithExactOrDefaultCoordinates() {
        val binding = binding(); val owner = Owner()
        val initial = binding.snapshot(owner)
        assertNull(initial.post); assertNull(initial.user); assertNull(initial.bitmap)
        assertEquals(listOf(1, 1, 1, 1, 1), listOf(owner.state.reads, owner.userState.reads, owner.photos.reads, owner.toggle.reads, owner.permission.reads))
        owner.state.setValue(Ready(Coordinate(12.25, 24.5), Coordinate(12.5, 24.75), true))
        owner.permission.setValue(true)
        val ready = binding.snapshot(owner)
        assertEquals(LocationData(12.25, 24.5), ready.post)
        assertEquals(LocationData(12.5, 24.75), ready.user)
        assertTrue(ready.approximate); assertTrue(ready.locationAllowed)
        owner.userState.setValue(Coordinate(13.0, 25.0))
        assertEquals(LocationData(13.0, 25.0), binding.snapshot(owner).user)
        owner.state.setValue(null)
        assertNull(binding.snapshot(owner).post)
    }

    @Test fun invalidCoordinatesAndRecycledPhotosAreRejected() {
        val binding = binding(); val owner = Owner()
        for (coordinate in listOf(Coordinate(Double.NaN, 0.0), Coordinate(91.0, 0.0), Coordinate(0.0, 181.0), Coordinate(0.0, Double.POSITIVE_INFINITY))) {
            owner.state.setValue(Ready(coordinate, coordinate, true)); owner.userState.setValue(coordinate)
            assertNull(binding.snapshot(owner).post); assertNull(binding.snapshot(owner).user)
        }
        val photo = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        owner.photos.setValue(Photos(photo, null)); photo.recycle()
        assertNull(binding.snapshot(owner).bitmap)
    }

    @Test fun photoToggleBackAndPermissionUseHostStateWithoutMutatingThePost() {
        val binding = binding(); val owner = Owner()
        val low = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val high = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val ready = Ready(Coordinate(1.0, 2.0), null, false)
        owner.state.setValue(ready); owner.photos.setValue(Photos(low, high))
        assertSame(low, binding.snapshot(owner).bitmap)
        binding.toggle(owner); assertSame(high, binding.snapshot(owner).bitmap)
        binding.toggle(owner); assertSame(low, binding.snapshot(owner).bitmap)
        binding.close(owner); binding.requestLocationPermission(owner)
        assertEquals(1, owner.navigation.closes); assertEquals(1, owner.permissionRequests)
        assertSame(ready, owner.state.getValue())
        assertFalse(low.isRecycled); assertFalse(high.isRecycled)
    }

    @Test fun composeBridgeUsesFullSizeUpdateAndReleaseWithNoReuseAndIdentitySafeCallbacks() {
        val binding = binding(); val composer = FakeComposer()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = View(activity)
        var updated = 0; var released = 0
        binding.compose(composer, { view }, { assertSame(view, it); updated++ }, { assertSame(view, it); released++ })
        assertEquals("fillMaxSize", composer.modifier); assertNull(composer.reset)
        assertSame(view, composer.factory.invoke(activity))
        assertTrue(composer.factory == composer.factory)
        assertFalse(composer.factory == composer.release)
        assertEquals(System.identityHashCode(composer.factory), composer.factory.hashCode())
        assertSame(Unit, composer.update.invoke(view)); assertEquals(1, updated)
        assertSame(Unit, composer.release.invoke(view)); assertEquals(1, released)
    }

    @Test fun approximateAreaKeepsFiveKilometerRadiusAndDoesNotSplitAtTheDateLine() {
        for (center in listOf(LocationData(0.0, 0.0), LocationData(45.0, 10.0), LocationData(-60.0, -179.99), LocationData(15.0, 179.99))) {
            val ring = NpatchPostMap.privacyRing(center)
            assertEquals(65, ring.size); assertEquals(ring.first(), ring.last())
            for (point in ring) {
                val lat1 = Math.toRadians(center.latitude); val lat2 = Math.toRadians(point.latitude())
                val dlat = lat2 - lat1; val dlon = Math.toRadians(point.longitude() - center.longitude)
                val distance = 2 * 6_371_009.0 * asin(sqrt(sin(dlat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dlon / 2).pow(2)))
                assertEquals(5_000.0, distance, .01)
            }
            assertTrue(ring.zipWithNext().all { abs(it.first.longitude() - it.second.longitude()) < 180 })
        }
    }

    @Test fun ordinaryInstallationSkipsResolutionBeforeReadingPreferencesOrHostClasses() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = object : ContextWrapper(activity) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                error("An ordinary installation must not initialize the NPatch resolver cache")
        }
        val untouchedLoader = object : ClassLoader(null) {
            override fun loadClass(name: String): Class<*> =
                error("An ordinary installation must not resolve NPatch host classes")
        }
        assertNull(PostMapSymbolResolver.resolve(host, untouchedLoader, ApplicationInfo(), "future", 1L) {
            error("An ordinary installation must not enter map resolution")
        })
    }

    @Test fun controlsRemainUsableWithoutGlAndReleaseClearsBorrowedState() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        // Deliberately unattached: no GPU or network initialization is claimed by this JVM test.
        val view = NpatchPostMapView(activity, activity.resources)
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        var closed = 0; var toggled = 0; var permissions = 0
        val data = PostMapSnapshot(LocationData(2.0, 3.0), null, true, image)
        view.update(data, { closed++ }, { toggled++ }, { permissions++ })
        for ((width, height) in listOf(320 to 720, 1200 to 800)) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val mapArea = (0 until view.childCount).map(view::getChildAt).filterIsInstance<FrameLayout>().single()
            assertTrue("Map must occupy remaining height", mapArea.height > 0)
            assertTrue(mapArea.bottom <= height)
        }
        fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
        fun button(id: Int) = descendants(view).filterIsInstance<ImageButton>().single { it.contentDescription == activity.getString(id) }
        button(R.string.befuck_close).performClick()
        button(R.string.befuck_post_map_photo).performClick()
        button(R.string.befuck_post_map_current).performClick()
        assertEquals(1, closed); assertEquals(1, toggled); assertEquals(1, permissions)
        assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.contains("OpenStreetMap") && it.text.contains("OpenMapTiles") })
        view.dispose(); view.dispose()
        view.update(data, { closed++ }, { toggled++ }, { permissions++ })
        button(R.string.befuck_close).performClick(); button(R.string.befuck_post_map_photo).performClick()
        assertEquals(1, closed); assertEquals(1, toggled)
        assertFalse(image.isRecycled)
        controller.pause().stop().destroy()
    }
}
