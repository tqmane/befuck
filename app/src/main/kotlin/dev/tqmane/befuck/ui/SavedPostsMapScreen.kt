package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.tqmane.befuck.R
import dev.tqmane.befuck.download.FeedPostMedia
import dev.tqmane.befuck.download.PostLocation
import dev.tqmane.befuck.download.PostMediaMetadata
import dev.tqmane.befuck.posting.LocationData
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object SavedPostsMapScreen {
    fun show(activity: Activity, resources: Resources, title: String, posts: List<FeedPostMedia>, zone: ZoneId,
             guard: () -> Boolean, onOpen: (FeedPostMedia) -> Unit, onClose: () -> Unit): Dialog {
        var view: SavedPostsMapView? = null
        var closed = false
        fun finish() {
            if (closed) return
            closed = true
            view?.dispose()
            onClose()
        }
        val dialog = object : Dialog(activity, android.R.style.Theme_Black_NoTitleBar) {
            override fun dismiss() { finish(); super.dismiss() }
        }
        val content = SavedPostsMapView(activity, resources, title, posts, zone, { !closed && guard() }, onOpen, { dialog.dismiss() })
        view = content
        dialog.setOnDismissListener { finish() }
        BoundedScreen.show(activity, dialog, content)
        if (!dialog.isShowing) finish()
        return dialog
    }
}

/** Reads only saved post coordinates. No device location source or location permission is used. */
internal class SavedPostsMapView(private val activity: Activity, private val labels: Resources, title: String,
    posts: List<FeedPostMedia>, private val zone: ZoneId, private val guard: () -> Boolean,
    private var openPost: ((FeedPostMedia) -> Unit)?, private var closeScreen: (() -> Unit)?) : LinearLayout(activity) {
    private val model = SavedPostsMapModel(posts)
    private val locale = labels.configuration.locales[0] ?: Locale.getDefault()
    private val mapArea = FrameLayout(activity).apply { tag = "saved-map-area" }
    private val status = ExpressiveUi.text(activity, "", 13f, ExpressiveUi.SECONDARY)
    private val owner = ExpressiveUi.text(activity, "", 18f, bold = true).apply { tag = "saved-map-owner"; maxLines = 2 }
    private val details = ExpressiveUi.text(activity, "", 13f, ExpressiveUi.SECONDARY).apply { tag = "saved-map-details" }
    private val caption = ExpressiveUi.text(activity, "", 14f).apply { maxLines = 2 }
    private val position = ExpressiveUi.text(activity, "", 13f, ExpressiveUi.SECONDARY).apply { gravity = Gravity.CENTER }
    private val retry = ExpressiveUi.button(activity, text(R.string.befuck_location_map_retry), icon = TablerIcon.REFRESH) { releaseRenderer(); createRenderer() }
    private val preview = ExpressiveUi.button(activity, text(R.string.befuck_saved_map_preview), true) {
        if (valid()) model.selected?.let { openPost?.invoke(it) }
    }.apply { tag = "saved-map-preview" }
    private val previous = ExpressiveUi.iconButton(activity, TablerIcon.CHEVRON_LEFT, text(R.string.befuck_saved_map_previous)) { selectNext(-1) }
    private val next = ExpressiveUi.iconButton(activity, TablerIcon.CHEVRON_RIGHT, text(R.string.befuck_saved_map_next)) { selectNext(1) }
    private var renderer: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var source: PreviewSource? = null
    private val photoRequests = LinkedHashMap<Int, PreviewCancel>()
    private val photoReady = HashSet<Int>()
    private val photoFailed = HashSet<Int>()
    private var wantedPhotos: Set<Int> = emptySet()
    private var savedCamera: CameraPosition? = null
    private var fitted = false
    private var rendererGeneration = 0
    private var registered = false
    private var started = false
    private var resumed = false
    private var paused = false
    private var focused = true
    private var disposed = false
    private var failed = false
    private val loadingTimeout = Runnable { if (valid() && style == null) showFailure() }
    private val refreshPhotos = Runnable { updatePhotos() }
    private val fitAfterLayout = Runnable { if (!fitted) fitAll() }
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(value: Activity) { if (value === activity) { paused = true; releaseRenderer() } }
        override fun onActivityResumed(value: Activity) { if (value === activity) { paused = false; createRenderer() } }
        override fun onActivityStopped(value: Activity) { if (value === activity) releaseRenderer() }
        override fun onActivityDestroyed(value: Activity) { if (value === activity) { closeScreen?.invoke(); dispose() } }
        override fun onActivityCreated(value: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(value: Activity) = Unit
        override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
    }

    init {
        orientation = VERTICAL
        tag = "saved-map-screen"
        setBackgroundColor(ExpressiveUi.BACKGROUND)
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(8), dp(8)) }
        val heading = LinearLayout(activity).apply {
            orientation = VERTICAL
            addView(ExpressiveUi.text(activity, text(R.string.befuck_saved_map_title), 22f, bold = true))
            addView(ExpressiveUi.text(activity, title, 13f, ExpressiveUi.SECONDARY))
        }
        header.addView(heading, LayoutParams(0, -2, 1f))
        header.addView(ExpressiveUi.iconButton(activity, TablerIcon.MAP_PIN, text(R.string.befuck_saved_map_fit)) { savedCamera = null; fitted = false; fitAll() }, LayoutParams(dp(48), dp(48)))
        header.addView(ExpressiveUi.iconButton(activity, TablerIcon.X, text(R.string.befuck_close)) { closeScreen?.invoke() }.apply { tag = "saved-map-close" }, LayoutParams(dp(48), dp(48)))
        addView(header)
        addView(ExpressiveUi.text(activity, labels.getString(R.string.befuck_saved_map_count, model.points.size, model.missingCount), 13f, ExpressiveUi.SECONDARY).apply {
            tag = "saved-map-count"; setPadding(dp(16), 0, dp(16), dp(4))
        }, LayoutParams(-1, -2))
        status.setPadding(dp(16), 0, dp(16), dp(8))
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(status, LayoutParams(-1, -2))
        addView(mapArea, LayoutParams(-1, 0, 1f))
        mapArea.addView(retry, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        retry.visibility = GONE
        val selection = LinearLayout(activity).apply {
            orientation = VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); background = ExpressiveUi.shape(activity)
        }
        val identity = LinearLayout(activity).apply { orientation = VERTICAL; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        identity.addView(owner)
        identity.addView(details, LayoutParams(-1, -2).apply { topMargin = dp(3) })
        identity.addView(caption, LayoutParams(-1, -2).apply { topMargin = dp(6) })
        selection.addView(identity)
        val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        previous.tag = "saved-map-previous"; next.tag = "saved-map-next"
        actions.addView(previous, LayoutParams(dp(48), dp(48)))
        actions.addView(preview, LayoutParams(0, -2, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
        actions.addView(next, LayoutParams(dp(48), dp(48)))
        selection.addView(actions, LayoutParams(-1, -2).apply { topMargin = dp(10) })
        selection.addView(position)
        // Large fonts and landscape must leave usable map space; the selected card can scroll.
        val selectionScroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) resources.displayMetrics.heightPixels else MeasureSpec.getSize(heightMeasureSpec)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((available * .42f).toInt().coerceAtLeast(dp(72)), MeasureSpec.AT_MOST))
            }
        }.apply { tag = "saved-map-selection"; addView(selection) }
        addView(selectionScroll, LayoutParams(-1, -2).apply { topMargin = dp(8); marginStart = dp(8); marginEnd = dp(8) })
        addView(ExpressiveUi.text(activity, "OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors", 11f).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(6))
        }, LayoutParams(-1, -2))
        updateSelection()
        updateStatus()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (disposed) return
        activity.application.registerActivityLifecycleCallbacks(lifecycle)
        registered = true
        createRenderer()
    }

    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        focused = hasWindowFocus
        if (hasWindowFocus) createRenderer() else releaseRenderer()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (disposed) return
        removeCallbacks(fitAfterLayout); post(fitAfterLayout)
        removeCallbacks(refreshPhotos); post(refreshPhotos)
    }

    private fun valid() = !disposed && !paused && !activity.isDestroyed && !activity.isFinishing && guard()
    private fun renderable() = valid() && focused && isAttachedToWindow

    private fun createRenderer() {
        if (!renderable() || renderer != null || model.points.isEmpty()) return
        val appContext = object : ContextWrapper(ModuleUiContext(activity.applicationContext, labels)) {
            override fun getApplicationContext(): Context = this
        }
        val viewContext = object : ContextWrapper(ModuleUiContext(activity, labels)) {
            override fun getApplicationContext(): Context = appContext
        }
        val generation = ++rendererGeneration
        try {
            failed = false; retry.visibility = GONE; updateStatus()
            MapLibre.getInstance(appContext)
            val created = MapView(viewContext)
            renderer = created
            created.onCreate(null)
            mapArea.addView(created, 0, FrameLayout.LayoutParams(-1, -1))
            created.onStart(); started = true
            created.onResume(); resumed = true
            postDelayed(loadingTimeout, 20_000)
            created.addOnDidFailLoadingMapListener { if (renderable() && generation == rendererGeneration) showFailure() }
            created.getMapAsync { ready ->
                if (!renderable() || generation != rendererGeneration) return@getMapAsync
                map = ready
                ready.addOnCameraIdleListener { updatePhotos() }
                ready.addOnMapClickListener { coordinate ->
                    if (!renderable() || map !== ready) return@addOnMapClickListener false
                    val pixel = ready.projection.toScreenLocation(coordinate)
                    val hitArea = RectF(pixel.x - dp(12), pixel.y - dp(12), pixel.x + dp(12), pixel.y + dp(12))
                    val hits = runCatching { ready.queryRenderedFeatures(hitArea, PHOTO_LAYER, POINT_LAYER).mapNotNull(::featureIndex) }.getOrDefault(emptyList())
                    if (model.selectHits(hits)) { updateSelection(); updatePhotos(); true } else false
                }
                ready.setStyle("https://tiles.openfreemap.org/styles/liberty") { loaded ->
                    if (!renderable() || generation != rendererGeneration) return@setStyle
                    try {
                        ready.uiSettings.isAttributionEnabled = true
                        loaded.addSource(GeoJsonSource(POINT_SOURCE, features()))
                        loaded.addSource(GeoJsonSource(SELECTED_SOURCE, emptyFeatures()))
                        loaded.addSource(GeoJsonSource(AREA_SOURCE, emptyFeatures()))
                        loaded.addLayer(FillLayer("bf-saved-area-fill", AREA_SOURCE).withProperties(fillColor("#0080FF"), fillOpacity(.15f)))
                        loaded.addLayer(CircleLayer(POINT_LAYER, POINT_SOURCE).withProperties(circleRadius(6f), circleColor("#202020"), circleStrokeWidth(2f), circleStrokeColor("#FFFFFF")))
                        loaded.addLayer(CircleLayer("bf-saved-selected-dot", SELECTED_SOURCE).withProperties(circleRadius(10f), circleColor("#1976D2"), circleStrokeWidth(2f), circleStrokeColor("#FFFFFF")))
                        loaded.addLayer(SymbolLayer(PHOTO_LAYER, POINT_SOURCE).withProperties(iconImage(get("photo")), iconAnchor("bottom"), iconAllowOverlap(true), iconIgnorePlacement(true)))
                        style = loaded
                        source = NetworkPreviewSource(activity, guard = { previewCheck(renderable()) })
                        failed = false; retry.visibility = GONE; removeCallbacks(loadingTimeout)
                        updateStatus(); updateSelection()
                        savedCamera?.let { ready.moveCamera(CameraUpdateFactory.newCameraPosition(it)); fitted = true }
                        if (!fitted) fitAll()
                        post(refreshPhotos)
                    } catch (failure: Throwable) { mapFailure("Saved map style", failure) }
                }
            }
        } catch (failure: Throwable) {
            releaseRenderer()
            mapFailure("Saved map initialization", failure)
        }
    }

    private fun features() = FeatureCollection.fromFeatures(model.points.mapIndexed { index, post ->
        Feature.fromGeometry(post.location!!.point()).apply { addNumberProperty("index", index); addStringProperty("photo", imageName(index)) }
    })

    private fun featureIndex(feature: Feature): Int? = runCatching { feature.getNumberProperty("index").toInt() }.getOrNull()?.takeIf { it in model.points.indices }

    private fun fitAll() {
        val target = map ?: return
        if (style == null || !renderable() || mapArea.width == 0 || mapArea.height == 0) return
        runCatching {
            val locations = model.points.mapNotNull { it.location }.distinctBy { it.latitude to it.longitude }
            if (locations.size == 1) {
                val point = locations.single()
                target.moveCamera(CameraUpdateFactory.newLatLngZoom(point.latLng(), if (model.points.any { it.location?.approximate == true }) 11.5 else 14.0))
            } else if (locations.isNotEmpty()) {
                val bounds = LatLngBounds.Builder().also { builder -> locations.forEach { builder.include(it.latLng()) } }.build()
                val padding = minOf(dp(64), minOf(mapArea.width, mapArea.height) / 4)
                target.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
            }
            fitted = true
        }.onFailure { mapFailure("Saved map camera", it) }
    }

    private fun selectNext(delta: Int) {
        if (!valid()) return
        model.move(delta); updateSelection()
        model.selected?.location?.let { location ->
            map?.let { target ->
                runCatching { target.animateCamera(CameraUpdateFactory.newLatLngZoom(location.latLng(), maxOf(target.cameraPosition.zoom, if (location.approximate) 11.5 else 14.0))) }
            }
        }
        updatePhotos()
    }

    private fun updateSelection() {
        val post = model.selected
        owner.text = post?.username?.takeIf(String::isNotBlank) ?: post?.ownerUid?.takeIf(String::isNotBlank) ?: text(R.string.befuck_cache_unknown_user)
        val time = post?.let(PostMediaMetadata::timestamp)?.let { DateTimeFormatter.ofPattern("HH:mm", locale).withZone(zone).format(Instant.ofEpochMilli(it)) }
            ?: text(R.string.befuck_cache_unknown_date)
        details.text = if (post?.location?.approximate == true) "$time · ${text(R.string.befuck_saved_map_approximate)}" else time
        caption.text = post?.caption.orEmpty()
        caption.visibility = if (caption.text.isBlank()) GONE else VISIBLE
        position.text = if (post == null) "" else labels.getString(R.string.befuck_saved_map_position, model.selectedIndex + 1, model.points.size)
        previous.isEnabled = model.points.size > 1 && valid(); next.isEnabled = previous.isEnabled
        preview.isEnabled = valid() && post != null && (post.primary != null || post.secondary != null)
        val location = post?.location
        runCatching {
            style?.getSourceAs<GeoJsonSource>(SELECTED_SOURCE)?.let { if (location == null) it.setGeoJson(emptyFeatures()) else it.setGeoJson(location.point()) }
            style?.getSourceAs<GeoJsonSource>(AREA_SOURCE)?.let {
                if (location?.approximate == true) it.setGeoJson(Polygon.fromLngLats(listOf(NpatchPostMap.privacyRing(LocationData(location.latitude, location.longitude)))))
                else it.setGeoJson(emptyFeatures())
            }
        }.onFailure { mapFailure("Saved map selection", it) }
    }

    /** Every post stays in GeoJSON; only viewport photos occupy the bounded texture/cache budget. */
    private fun updatePhotos() {
        val target = map ?: return
        val loaded = style ?: return
        val previews = source ?: return
        if (!renderable() || mapArea.width == 0 || mapArea.height == 0) return
        val visible = runCatching { target.queryRenderedFeatures(RectF(0f, 0f, mapArea.width.toFloat(), mapArea.height.toFloat()), POINT_LAYER).mapNotNull(::featureIndex).distinct().sorted() }
            .getOrElse { return }
        wantedPhotos = (listOfNotNull(model.selectedIndex.takeIf { it >= 0 }) + visible).distinct().take(PHOTO_LIMIT).toSet()
        photoRequests.keys.filter { it !in wantedPhotos }.forEach { photoRequests.remove(it)?.cancel() }
        photoReady.filter { it !in wantedPhotos }.forEach { runCatching { loaded.removeImage(imageName(it)) }; photoReady.remove(it) }
        photoFailed.retainAll(wantedPhotos)
        val generation = rendererGeneration
        wantedPhotos.filter { it !in photoRequests && it !in photoReady && it !in photoFailed }.forEach { index ->
            val post = model.points[index]
            val media = post.primary ?: post.secondary ?: return@forEach
            photoRequests[index] = previews.image(media, dp(60).coerceIn(60, 192)) { result ->
                if (!renderable() || generation != rendererGeneration || index !in wantedPhotos) return@image
                photoRequests.remove(index)
                result.onSuccess { bitmap ->
                    if (!bitmap.isRecycled) runCatching {
                        val pin = photoPin(bitmap)
                        try { loaded.addImage(imageName(index), pin); photoReady.add(index) } finally { pin.recycle() }
                    }.onFailure { photoFailed.add(index) }
                }.onFailure { photoFailed.add(index) }
            }
        }
    }

    private fun photoPin(source: Bitmap): Bitmap {
        val width = dp(60).coerceIn(60, 192)
        val tail = (width / 8).coerceAtLeast(6)
        val stroke = (width / 30f).coerceAtLeast(2f)
        val radius = width / 7f
        val result = Bitmap.createBitmap(width, width + tail, Bitmap.Config.ARGB_8888)
        result.density = resources.displayMetrics.densityDpi
        try {
            val canvas = Canvas(result)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
            val arrow = Path().apply { moveTo(width * .36f, width - stroke); lineTo(width * .5f, (width + tail).toFloat()); lineTo(width * .64f, width - stroke); close() }
            canvas.drawPath(arrow, paint)
            val outer = RectF(0f, 0f, width.toFloat(), width.toFloat())
            canvas.drawRoundRect(outer, radius, radius, paint)
            val inner = RectF(stroke, stroke, width - stroke, width - stroke)
            val edge = minOf(source.width, source.height)
            val left = (source.width - edge) / 2; val top = (source.height - edge) / 2
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(inner, radius - stroke, radius - stroke, Path.Direction.CW) })
            canvas.drawBitmap(source, Rect(left, top, left + edge, top + edge), inner, paint)
            canvas.restore()
            return result
        } catch (failure: Throwable) { result.recycle(); throw failure }
    }

    private fun updateStatus() {
        status.text = when {
            model.points.isEmpty() -> text(R.string.befuck_saved_map_empty)
            failed -> text(R.string.befuck_location_map_failed)
            style == null -> text(R.string.befuck_location_map_loading)
            else -> text(R.string.befuck_saved_map_hint)
        }
    }

    private fun mapFailure(stage: String, failure: Throwable) {
        Log.w("BeFuck/Map", "$stage failed: ${failure.javaClass.simpleName}")
        showFailure()
    }

    private fun showFailure() { failed = true; removeCallbacks(loadingTimeout); retry.visibility = VISIBLE; updateStatus() }

    private fun releaseRenderer() {
        rendererGeneration++
        removeCallbacks(loadingTimeout); removeCallbacks(refreshPhotos); removeCallbacks(fitAfterLayout)
        if (fitted) savedCamera = runCatching { map?.cameraPosition }.getOrNull() ?: savedCamera
        photoRequests.values.toList().forEach { it.cancel() }; photoRequests.clear()
        source?.close(); source = null
        photoReady.clear(); photoFailed.clear(); wantedPhotos = emptySet()
        if (resumed) { runCatching { renderer?.onPause() }; resumed = false }
        if (started) { runCatching { renderer?.onStop() }; started = false }
        runCatching { renderer?.onDestroy() }
        renderer?.let(mapArea::removeView)
        renderer = null; map = null; style = null; fitted = false
    }

    fun dispose() {
        if (disposed) return
        disposed = true; releaseRenderer()
        if (registered) { activity.application.unregisterActivityLifecycleCallbacks(lifecycle); registered = false }
        openPost = null; closeScreen = null; savedCamera = null
    }

    private fun text(id: Int) = labels.getString(id)
    private fun dp(value: Int) = ExpressiveUi.dp(activity, value)
    private fun PostLocation.latLng() = LatLng(latitude, longitude)
    private fun PostLocation.point() = Point.fromLngLat(longitude, latitude)
    private fun emptyFeatures() = FeatureCollection.fromFeatures(emptyArray())
    private fun imageName(index: Int) = "bf-saved-photo-$index"

    companion object {
        private const val PHOTO_LIMIT = 64
        private const val POINT_SOURCE = "bf-saved-points"
        private const val SELECTED_SOURCE = "bf-saved-selected"
        private const val AREA_SOURCE = "bf-saved-area"
        private const val POINT_LAYER = "bf-saved-dots"
        private const val PHOTO_LAYER = "bf-saved-photos"
    }
}
