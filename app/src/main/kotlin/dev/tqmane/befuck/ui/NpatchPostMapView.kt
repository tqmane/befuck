package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.tqmane.befuck.R
import dev.tqmane.befuck.archive.ArchiveAccounts
import dev.tqmane.befuck.download.FeedPostMediaCache
import dev.tqmane.befuck.download.PostLocation
import dev.tqmane.befuck.posting.LocationData
import dev.tqmane.befuck.symbols.PostMapSnapshot
import dev.tqmane.befuck.symbols.PostMapSymbols
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.*

/** Replaces only the post-map content; the host still owns navigation and post state. */
object NpatchPostMap {
    @JvmStatic fun compose(symbols: PostMapSymbols, owner: Any, composer: Any, resources: Resources): Any {
        val snapshot = symbols.snapshot(owner)
        captureDisplayedLocation(snapshot)
        symbols.compose(composer, { NpatchPostMapView(it, resources) }, {
            (it as NpatchPostMapView).update(snapshot, { symbols.close(owner) }, { symbols.toggle(owner) }, { symbols.requestLocationPermission(owner) })
        }, { (it as NpatchPostMapView).dispose() })
        return symbols.unit
    }

    /** Replace only the Google map body; the host retains its drag handle and dismiss action. */
    @JvmStatic fun composeSheet(symbols: PostMapSymbols, display: Any, arguments: Array<Any?>, resources: Resources): Any? {
        val snapshot = symbols.snapshotInvocation(display, arguments)
        captureDisplayedLocation(snapshot)
        val composer = requireNotNull(arguments.getOrNull(symbols.composerArgument))
        symbols.compose(composer, { NpatchPostMapView(it, resources, hostSheet = true) }, {
            (it as NpatchPostMapView).update(snapshot, {}, {}, {
                symbols.requestLocationPermissionInvocation(display, arguments)
            })
        }, { (it as NpatchPostMapView).dispose() }, symbols.modifierForInvocation(arguments))
        return null // bfg.c is a void Composable.
    }

    private fun captureDisplayedLocation(snapshot: PostMapSnapshot) {
        val account = ArchiveAccounts.snapshot()
        if (account.uid != null) snapshot.post?.let { location ->
            runCatching {
                FeedPostMediaCache.captureDisplayedLocation(snapshot.postId,
                    PostLocation.create(location.latitude, location.longitude, snapshot.approximate), account)
            }.onFailure { Log.w("BeFuck/Map", "Could not record the displayed post location") }
        }
    }

    // Same 5km radius as the original post map. Only the host's randomized display
    // coordinate is supplied; raw/exact post coordinates must never be substituted.
    internal fun privacyRing(center: LocationData): List<Point> {
        val lat = Math.toRadians(center.latitude)
        val lon = Math.toRadians(center.longitude)
        val distance = 5_000.0 / 6_371_009.0
        val points = (0 until 64).map { index ->
            val bearing = 2 * PI * index / 64
            val y = asin((sin(lat) * cos(distance) + cos(lat) * sin(distance) * cos(bearing)).coerceIn(-1.0, 1.0))
            val x = lon + atan2(sin(bearing) * sin(distance) * cos(lat), cos(distance) - sin(lat) * sin(y))
            // Keep longitudes continuous across the date line instead of drawing a world-spanning polygon.
            Point.fromLngLat(Math.toDegrees(x), Math.toDegrees(y))
        }
        return points + points.first()
    }
}

internal class NpatchPostMapView(context: Context, private val moduleResources: Resources, private val hostSheet: Boolean = false) : LinearLayout(context) {
    private val activity = findActivity(context)
    private val mapContainer = FrameLayout(context)
    private val status = ExpressiveUi.text(context, "", 13f, ExpressiveUi.SECONDARY)
    private val retry = ExpressiveUi.button(context, text(R.string.befuck_location_map_retry), icon = TablerIcon.REFRESH) {
        if (map == null) { destroyRenderer(); createMap() } else loadStyle()
    }
    private val current = ExpressiveUi.iconButton(context, TablerIcon.CURRENT_LOCATION, text(R.string.befuck_post_map_current)) {
        val user = snapshot?.user
        if (user != null) map?.animateCamera(CameraUpdateFactory.newLatLngZoom(user.latLng(), 14.0))
        else if (snapshot?.locationAllowed == false) {
            if (snapshot?.canRequestLocation == true) {
                runCatching { askLocation?.invoke() }.onFailure { Log.w("BeFuck/Map", "Location permission action unavailable") }
            } else status.text = text(R.string.befuck_post_map_permission_settings)
        } else status.text = text(R.string.befuck_post_map_no_current)
    }
    private val photo = ExpressiveUi.iconButton(context, TablerIcon.MAP_PIN, text(R.string.befuck_post_map_photo)) { togglePhoto() }
    private var snapshot: PostMapSnapshot? = null
    private var close: (() -> Unit)? = null
    private var toggle: (() -> Unit)? = null
    private var askLocation: (() -> Unit)? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var pinBitmap: Bitmap? = null // Borrowed host bitmap identity only; never recycled here.
    private var pinGeneration = -1
    private var cameraPost: LocationData? = null
    private var cameraApproximate = false
    private var styleGeneration = 0
    private var started = false
    private var resumed = false
    private var registered = false
    private var disposed = false
    private var failed = false
    private var pendingFit: Runnable? = null
    private val loadingTimeout = Runnable { if (!disposed && style == null) showFailure() }
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(a: Activity) { if (a === activity && isAttachedToWindow) startMap(false) }
        override fun onActivityResumed(a: Activity) { if (a === activity && isAttachedToWindow) startMap(true) }
        override fun onActivityPaused(a: Activity) { if (a === activity) pauseMap() }
        override fun onActivityStopped(a: Activity) { if (a === activity) stopMap() }
        override fun onActivityDestroyed(a: Activity) { if (a === activity) dispose() }
        override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
        override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(ExpressiveUi.BACKGROUND)
        if (hostSheet) {
            // The native sheet supplies the close button. Keep our map actions off it.
            val actions = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(ExpressiveUi.BACKGROUND)
                addView(photo, LayoutParams(dp(48), dp(48)))
                addView(current, LayoutParams(dp(48), dp(48)))
            }
            mapContainer.addView(actions, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                marginStart = dp(12)
                topMargin = dp(12)
            })
        } else {
            val header = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
                addView(ExpressiveUi.iconButton(context, TablerIcon.X, text(R.string.befuck_close)) { close?.invoke() }, LayoutParams(dp(48), dp(48)))
                addView(ExpressiveUi.text(context, text(R.string.befuck_post_map_title), 20f, bold = true), LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                addView(photo, LayoutParams(dp(48), dp(48)))
                addView(current, LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
            }
            addView(header, LayoutParams(-1, -2))
        }
        status.setPadding(dp(16), dp(4), dp(16), dp(8))
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(status, LayoutParams(-1, -2))
        addView(mapContainer, LayoutParams(-1, 0, 1f))
        mapContainer.addView(retry, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        retry.visibility = GONE
        // Attribution remains visible even when style loading fails.
        addView(ExpressiveUi.text(context, "OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors", 11f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LayoutParams(-1, -2))
        // The host sheet has already applied its own window insets.
        if (!hostSheet) {
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { _, insets ->
                setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        updateControls()
    }

    fun update(value: PostMapSnapshot, onClose: () -> Unit, onToggle: () -> Unit, onLocationPermission: () -> Unit) {
        if (disposed) return
        snapshot = value
        close = onClose
        toggle = onToggle
        askLocation = onLocationPermission
        updateControls()
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (disposed) return
        if (!registered) { activity?.application?.registerActivityLifecycleCallbacks(lifecycle); registered = activity != null }
        if (mapView == null) createMap()
        startMap(true)
        render()
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        pendingFit?.let(::removeCallbacks); pendingFit = null
        stopMap()
        unregister()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && !disposed) render()
    }

    private fun createMap() {
        if (disposed || mapView != null) return
        failed = false
        retry.visibility = GONE
        updateControls()
        // MapLibre keeps an application Context globally. Never hand it an Activity.
        val appContext = object : ContextWrapper(ModuleUiContext(context.applicationContext, moduleResources)) {
            override fun getApplicationContext(): Context = this
        }
        val renderContext = object : ContextWrapper(ModuleUiContext(context, moduleResources)) {
            override fun getApplicationContext(): Context = appContext
        }
        var created: MapView? = null
        try {
            MapLibre.getInstance(appContext)
            created = MapView(renderContext)
            mapView = created
            created.onCreate(null)
            mapContainer.addView(created, 0, FrameLayout.LayoutParams(-1, -1))
            removeCallbacks(loadingTimeout); postDelayed(loadingTimeout, 20_000)
            created.addOnDidFailLoadingMapListener { if (!disposed && mapView === created) showFailure() }
            created.getMapAsync { ready ->
                if (!disposed && mapView === created) {
                    map = ready
                    ready.addOnMapClickListener { point ->
                        if (disposed || map !== ready) return@addOnMapClickListener false
                        val pixel = ready.projection.toScreenLocation(point)
                        if (ready.queryRenderedFeatures(pixel, "bf-post-pin", "bf-post-dot").isNotEmpty()) {
                            togglePhoto(); true
                        } else false
                    }
                    loadStyle()
                }
            }
            if (isAttachedToWindow) startMap(true)
        } catch (failure: Throwable) {
            runCatching { created?.onDestroy() }
            created?.let(mapContainer::removeView)
            mapView = null; map = null
            started = false; resumed = false
            Log.w("BeFuck/Map", "Map initialization failed: ${failure.javaClass.simpleName}")
            showFailure()
        }
    }

    private fun loadStyle() {
        val target = map ?: return
        val generation = ++styleGeneration
        style = null; failed = false; pinBitmap = null; pinGeneration = -1
        retry.visibility = GONE
        updateControls()
        removeCallbacks(loadingTimeout); postDelayed(loadingTimeout, 20_000)
        try {
            target.setStyle("https://tiles.openfreemap.org/styles/liberty") { loaded ->
                if (disposed || generation != styleGeneration) return@setStyle
                removeCallbacks(loadingTimeout)
                try {
                    target.uiSettings.isAttributionEnabled = true
                    loaded.addSource(GeoJsonSource("bf-post", emptyFeatures()))
                    loaded.addSource(GeoJsonSource("bf-user", emptyFeatures()))
                    loaded.addSource(GeoJsonSource("bf-area", emptyFeatures()))
                    loaded.addLayer(FillLayer("bf-area-fill", "bf-area").withProperties(fillColor("#0080FF"), fillOpacity(.2f)))
                    loaded.addLayer(CircleLayer("bf-user-dot", "bf-user").withProperties(circleRadius(7f), circleColor("#1976D2"), circleStrokeWidth(2f), circleStrokeColor("#FFFFFF")))
                    loaded.addLayer(CircleLayer("bf-post-dot", "bf-post").withProperties(circleRadius(8f), circleColor("#111111"), circleStrokeWidth(3f), circleStrokeColor("#FFFFFF")))
                    loaded.addLayer(SymbolLayer("bf-post-pin", "bf-post").withProperties(iconImage("bf-photo"), iconAnchor("bottom"), iconAllowOverlap(true), iconIgnorePlacement(true), visibility(Property.NONE)))
                    style = loaded; failed = false
                    retry.visibility = GONE
                    render(); updateControls()
                } catch (failure: Throwable) {
                    Log.w("BeFuck/Map", "Map style setup failed: ${failure.javaClass.simpleName}")
                    showFailure()
                }
            }
        } catch (failure: Throwable) {
            Log.w("BeFuck/Map", "Map style request failed: ${failure.javaClass.simpleName}")
            showFailure()
        }
    }

    private fun render() {
        val loaded = style ?: return
        val data = snapshot ?: return
        try {
            loaded.getSourceAs<GeoJsonSource>("bf-post")?.let { source ->
                if (data.post == null) source.setGeoJson(emptyFeatures()) else source.setGeoJson(data.post.point())
            }
            loaded.getSourceAs<GeoJsonSource>("bf-user")?.let { source ->
                if (data.user == null) source.setGeoJson(emptyFeatures()) else source.setGeoJson(data.user.point())
            }
            loaded.getSourceAs<GeoJsonSource>("bf-area")?.let { source ->
                if (data.post != null && data.approximate) source.setGeoJson(Polygon.fromLngLats(listOf(NpatchPostMap.privacyRing(data.post))))
                else source.setGeoJson(emptyFeatures())
            }
            val bitmap = data.bitmap?.takeUnless { it.isRecycled }
            val generation = bitmap?.generationId ?: -1
            if (bitmap !== pinBitmap || generation != pinGeneration) {
                if (bitmap != null) {
                    val pin = photoPin(bitmap)
                    try { loaded.addImage("bf-photo", pin) } finally { pin.recycle() }
                } else loaded.removeImage("bf-photo")
                pinBitmap = bitmap; pinGeneration = generation
            }
            loaded.getLayerAs<SymbolLayer>("bf-post-pin")?.setProperties(visibility(if (bitmap == null) Property.NONE else Property.VISIBLE))
            // Recomposition, bitmap arrival and GPS updates must not undo manual panning.
            if (data.post != null && (cameraPost != data.post || cameraApproximate != data.approximate)) {
                val expected = data.post
                pendingFit?.let(::removeCallbacks)
                pendingFit = Runnable {
                    if (!disposed && snapshot?.post == expected && width > 0 && mapContainer.height > 0) {
                        fitPost(snapshot ?: data)
                        cameraPost = expected; cameraApproximate = data.approximate
                    }
                    pendingFit = null
                }.also(::post)
            }
        } catch (failure: Throwable) {
            Log.w("BeFuck/Map", "Map update failed: ${failure.javaClass.simpleName}")
            showFailure()
        }
    }

    private fun fitPost(data: PostMapSnapshot) {
        val post = data.post ?: return
        val user = data.user
        if (user != null && user != post) {
            map?.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().include(post.latLng()).include(user.latLng()).build(), dp(64)))
        } else map?.moveCamera(CameraUpdateFactory.newLatLngZoom(post.latLng(), if (data.approximate) 11.5 else 14.0))
    }

    private fun togglePhoto() {
        if (disposed) return
        runCatching { toggle?.invoke() }.onFailure { Log.w("BeFuck/Map", "Photo toggle unavailable") }
        snapshot?.post?.let { map?.animateCamera(CameraUpdateFactory.newLatLng(it.latLng())) }
    }

    private fun photoPin(source: Bitmap): Bitmap {
        val width = dp(72).coerceIn(72, 256)
        val height = (width * source.height.toFloat() / source.width).toInt().coerceIn(width / 2, width * 2)
        // The host bitmap already contains the photo border and transparent padding.
        // Preserve its alpha instead of painting that transparent area opaque white.
        val pin = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        pin.density = context.resources.displayMetrics.densityDpi
        val canvas = Canvas(pin)
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(source, Rect(0, 0, source.width, source.height), Rect(0, 0, width, height), paint)
            return pin
        } catch (failure: Throwable) {
            pin.recycle()
            throw failure
        }
    }

    private fun updateControls() {
        status.visibility = if (hostSheet && !failed && style != null && snapshot?.post != null) GONE else VISIBLE
        current.isEnabled = !disposed && snapshot != null
        photo.isEnabled = snapshot?.bitmap != null
        photo.alpha = if (photo.isEnabled) 1f else .4f
        status.text = when {
            failed -> text(R.string.befuck_location_map_failed)
            style == null -> text(R.string.befuck_location_map_loading)
            snapshot?.post == null -> text(R.string.befuck_post_map_waiting)
            snapshot?.approximate == true -> text(R.string.befuck_post_map_approximate)
            else -> text(R.string.befuck_post_map_hint)
        }
    }

    private fun showFailure() { failed = true; removeCallbacks(loadingTimeout); retry.visibility = VISIBLE; updateControls() }
    private fun startMap(resume: Boolean) {
        if (disposed || mapView == null) return
        runCatching {
            if (!started) { mapView?.onStart(); started = true }
            if (resume && !resumed) { mapView?.onResume(); resumed = true }
        }.onFailure { showFailure() }
    }
    private fun pauseMap() { if (resumed) { resumed = false; runCatching { mapView?.onPause() } } }
    private fun stopMap() { pauseMap(); if (started) { started = false; runCatching { mapView?.onStop() } } }
    private fun unregister() { if (registered) { registered = false; activity?.application?.unregisterActivityLifecycleCallbacks(lifecycle) } }

    private fun destroyRenderer() {
        styleGeneration++
        removeCallbacks(loadingTimeout)
        pendingFit?.let(::removeCallbacks); pendingFit = null
        stopMap()
        runCatching { mapView?.onDestroy() }
        mapView?.let(mapContainer::removeView)
        mapView = null; map = null; style = null; pinBitmap = null
        cameraPost = null
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        destroyRenderer(); unregister()
        snapshot = null; close = null; toggle = null; askLocation = null
    }

    private fun text(id: Int) = moduleResources.getString(id)
    private fun dp(value: Int) = ExpressiveUi.dp(context, value)
    private fun LocationData.latLng() = LatLng(latitude, longitude)
    private fun LocationData.point() = Point.fromLngLat(longitude, latitude)
    private fun emptyFeatures() = FeatureCollection.fromFeatures(emptyArray())
    private fun findActivity(context: Context): Activity? {
        var current = context
        val seen = HashSet<Context>()
        while (seen.add(current)) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }
}
