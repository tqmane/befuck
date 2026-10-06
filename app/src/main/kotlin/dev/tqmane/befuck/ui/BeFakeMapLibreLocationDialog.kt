package dev.tqmane.befuck.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.tqmane.befuck.R
import dev.tqmane.befuck.posting.LocationData
import org.json.JSONArray
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Native location picker using MapLibre Native and OpenFreeMap's Liberty style. */
object BeFakeLocationDialog {
    private const val DEFAULT_LATITUDE = 35.681236
    private const val DEFAULT_LONGITUDE = 139.767125
    private const val LOCATION_PERMISSION_REQUEST = 0x4B35
    private const val OPENFREEMAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
    private const val SELECTED_LOCATION_SOURCE = "befake-selected-location"
    private const val SELECTED_LOCATION_LAYER = "befake-selected-location-pin"
    private const val SELECTED_LOCATION_ICON = "befake-destination-pin"
    private const val LOCATION_TIMEOUT_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val sessions = WeakHashMap<Activity, MapSession>()

    private class MapSession(
        val activity: Activity,
        val dialog: Dialog,
        val mapView: MapView,
        val status: TextView,
        val resources: android.content.res.Resources,
        var selectedLocation: LocationData?,
    ) {
        var map: MapLibreMap? = null
        var locationSource: GeoJsonSource? = null
        var userInteracted = false
        var started = false
        var resumed = false
        var destroyed = false
        var locationListener: LocationListener? = null
        var locationTimeout: Runnable? = null
    }

    @SuppressLint("MissingPermission")
    @JvmStatic
    fun show(
        activity: Activity,
        resources: android.content.res.Resources,
        currentLocation: LocationData?,
        onConfirmed: (LocationData?) -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val mapContext = object : ContextWrapper(activity) {
            override fun getResources(): android.content.res.Resources = resources
            override fun getApplicationContext(): Context = this
        }
        runCatching { MapLibre.getInstance(mapContext) }
            .onFailure {
                Toast.makeText(activity, R.string.befuck_location_map_failed, Toast.LENGTH_LONG).show()
                return
            }

        val initialLocation = currentLocation ?: lastKnownLocation(activity)
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F0F12.toInt())
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val toolbar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
            setBackgroundColor(0xFF1B1B1F.toInt())
        }
        val cancel = TextView(activity).apply {
            text = resources.getString(R.string.befuck_close)
            setTextColor(0xFF8E8E93.toInt())
            textSize = 16f
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
            setOnClickListener { dialog.dismiss() }
        }
        toolbar.addView(cancel)
        val title = TextView(activity).apply {
            text = resources.getString(R.string.befuck_location_title)
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val done = TextView(activity).apply {
            text = resources.getString(R.string.befuck_crop_done)
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
            setOnClickListener {
                val location = sessions[activity]?.selectedLocation
                dialog.dismiss()
                onConfirmed(location)
            }
        }
        toolbar.addView(done)
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        val searchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8))
            setBackgroundColor(0xFF161619.toInt())
        }
        val searchInput = EditText(activity).apply {
            hint = resources.getString(R.string.befuck_location_search_hint)
            setHintTextColor(0xFF8E8E93.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            background = rounded(activity, 0xFF242429.toInt(), 8f)
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
        val searchButton = Button(activity).apply {
            text = "Go"
            setTextColor(Color.WHITE)
            textSize = 14f
            background = rounded(activity, 0xFF3A3A40.toInt(), 8f)
        }
        searchRow.addView(searchButton, LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 38)).apply {
            marginStart = dp(activity, 8)
        })
        root.addView(searchRow, LinearLayout.LayoutParams(-1, -2))

        val coordStatus = TextView(activity).apply {
            setTextColor(0xFF8E8E93.toInt())
            textSize = 12f
            setPadding(dp(activity, 16), dp(activity, 4), dp(activity, 16), dp(activity, 4))
            text = initialLocation?.let(::formatLocation) ?: resources.getString(R.string.befuck_location_none)
        }
        root.addView(coordStatus, LinearLayout.LayoutParams(-1, -2))

        val mapView = MapView(mapContext)
        mapView.onCreate(null)
        val mapContainer = FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
        }
        mapContainer.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        val currentLocationButton = Button(activity).apply {
            text = "◎"
            textSize = 23f
            setTextColor(Color.WHITE)
            contentDescription = resources.getString(R.string.befuck_location_current)
            background = rounded(activity, 0xE6222226.toInt(), 12f)
            setOnClickListener {
                val session = sessions[activity] ?: return@setOnClickListener
                session.userInteracted = true
                if (hasLocationPermission(activity)) requestCurrentLocation(activity, session, onlyIfUnchanged = false)
                else requestLocationPermission(activity)
            }
        }
        mapContainer.addView(currentLocationButton, FrameLayout.LayoutParams(dp(activity, 48), dp(activity, 48), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(activity, 14)
            bottomMargin = dp(activity, 42)
        })
        val attribution = TextView(activity).apply {
            text = "© OpenStreetMap contributors · OpenFreeMap"
            textSize = 10f
            setTextColor(0xFFE8E8EA.toInt())
            setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 4))
            background = rounded(activity, 0xB3000000.toInt(), 5f)
        }
        mapContainer.addView(attribution, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.BOTTOM).apply {
            marginStart = dp(activity, 8)
            bottomMargin = dp(activity, 8)
        })
        root.addView(mapContainer, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
            setBackgroundColor(0xFF1B1B1F.toInt())
        }
        val clearButton = Button(activity).apply {
            text = resources.getString(R.string.befuck_location_clear)
            setTextColor(0xFFFF453A.toInt())
            textSize = 14f
            background = rounded(activity, 0xFF29292E.toInt(), 10f)
            setOnClickListener {
                val session = sessions[activity] ?: return@setOnClickListener
                session.userInteracted = true
                session.selectedLocation = null
                session.status.text = resources.getString(R.string.befuck_location_none)
                session.locationSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            }
        }
        bottomBar.addView(clearButton, LinearLayout.LayoutParams(dp(activity, 160), dp(activity, 44)))
        root.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))

        val session = MapSession(activity, dialog, mapView, coordStatus, resources, initialLocation)
        sessions[activity] = session
        searchButton.setOnClickListener {
            search(activity, session, searchInput.text?.toString()?.trim().orEmpty())
        }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(activity, session, searchInput.text?.toString()?.trim().orEmpty())
                true
            } else false
        }

        mapView.getMapAsync { map ->
            if (sessions[activity] !== session || session.destroyed) return@getMapAsync
            session.map = map
            map.setStyle(OPENFREEMAP_STYLE) { style: Style ->
                if (sessions[activity] !== session || session.destroyed) return@setStyle
                map.uiSettings.isAttributionEnabled = true
                map.uiSettings.isCompassEnabled = true
                val locationSource = GeoJsonSource(
                    SELECTED_LOCATION_SOURCE,
                    FeatureCollection.fromFeatures(emptyArray()),
                )
                style.addSource(locationSource)
                style.addImage(SELECTED_LOCATION_ICON, destinationPinBitmap())
                style.addLayer(
                    SymbolLayer(SELECTED_LOCATION_LAYER, SELECTED_LOCATION_SOURCE).withProperties(
                        PropertyFactory.iconImage(SELECTED_LOCATION_ICON),
                        PropertyFactory.iconAnchor("bottom"),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconSize(0.8f),
                    ),
                )
                session.locationSource = locationSource
                map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                    .target(initialLocation?.let { LatLng(it.latitude, it.longitude) }
                        ?: LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE))
                    .zoom(14.0)
                    .build()
                map.addOnMapClickListener { point ->
                    chooseLocation(session, LocationData(point.latitude, point.longitude), moveCamera = false)
                    true
                }
                session.selectedLocation?.let { updateLocationFeature(session, it) }
            }
        }

        dialog.setOnShowListener {
            startMapLifecycle(session)
            if (currentLocation == null) {
                if (hasLocationPermission(activity)) requestCurrentLocation(activity, session, onlyIfUnchanged = true)
                else requestLocationPermission(activity)
            }
        }
        dialog.setOnDismissListener {
            if (sessions[activity] === session) sessions.remove(activity)
            destroyMapSession(session)
        }
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setDimAmount(0f)
        }
    }

    @JvmStatic
    fun onRequestPermissionsResult(activity: Activity, requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != LOCATION_PERMISSION_REQUEST) return false
        val session = sessions[activity] ?: return true
        if (hasLocationPermission(activity) || grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            requestCurrentLocation(activity, session, onlyIfUnchanged = true)
        }
        return true
    }

    @JvmStatic
    fun onActivityPause(activity: Activity) {
        sessions[activity]?.let(::pauseMapLifecycle)
    }

    @JvmStatic
    fun onActivityResume(activity: Activity) {
        sessions[activity]?.takeIf { it.dialog.isShowing }?.let(::startMapLifecycle)
    }

    @JvmStatic
    fun onActivityDestroy(activity: Activity) {
        sessions[activity]?.dialog?.dismiss()
    }

    private fun chooseLocation(session: MapSession, location: LocationData, moveCamera: Boolean) {
        session.userInteracted = true
        session.selectedLocation = location
        session.status.text = formatLocation(location)
        if (moveCamera) {
            session.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 14.0))
        }
        updateLocationFeature(session, location)
    }

    private fun updateLocationFeature(session: MapSession, location: LocationData) {
        session.locationSource?.setGeoJson(Point.fromLngLat(location.longitude, location.latitude))
    }

    private fun search(activity: Activity, session: MapSession, query: String) {
        if (query.isBlank()) return
        searchExecutor.execute {
            val result = runCatching {
                val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
                val connection = URL("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=$encoded")
                    .openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", "${activity.packageName}/BeFakeLocation")
                    val status = connection.responseCode
                    if (status !in 200..299) throw IOException("Geocoding returned HTTP $status")
                    val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val entries = JSONArray(body)
                    if (entries.length() == 0) null else {
                        val first = entries.getJSONObject(0)
                        LocationData(first.getString("lat").toDouble(), first.getString("lon").toDouble())
                    }
                } finally {
                    connection.disconnect()
                }
            }
            mainHandler.post {
                if (sessions[activity] !== session || session.destroyed) return@post
                result.onSuccess { location ->
                    if (location == null) Toast.makeText(activity, R.string.befuck_location_search_failed, Toast.LENGTH_SHORT).show()
                    else chooseLocation(session, location, moveCamera = true)
                }.onFailure {
                    Toast.makeText(activity, R.string.befuck_location_search_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requestLocationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                activity.requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    LOCATION_PERMISSION_REQUEST,
                )
            }
        }
    }

    private fun hasLocationPermission(activity: Activity): Boolean =
        activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(activity: Activity): LocationData? {
        if (!hasLocationPermission(activity)) return null
        val manager = activity.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { LocationData(it.latitude, it.longitude) }
    }

    @SuppressLint("MissingPermission")
    private fun requestCurrentLocation(activity: Activity, session: MapSession, onlyIfUnchanged: Boolean) {
        if (!hasLocationPermission(activity)) {
            requestLocationPermission(activity)
            return
        }
        val manager = activity.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val provider = runCatching {
            manager.getBestProvider(Criteria().apply { accuracy = Criteria.ACCURACY_COARSE }, true)
        }.getOrNull() ?: listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .firstOrNull { name -> runCatching { manager.isProviderEnabled(name) }.getOrDefault(false) }
            ?: return

        val applyLocation: (Location?) -> Unit = { location ->
            if (location != null && sessions[activity] === session && !session.destroyed &&
                (!onlyIfUnchanged || !session.userInteracted)
            ) {
                chooseLocation(session, LocationData(location.latitude, location.longitude), moveCamera = true)
                if (onlyIfUnchanged) session.userInteracted = false
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                manager.getCurrentLocation(provider, null, activity.mainExecutor) { location -> applyLocation(location) }
            }.onFailure { applyLocation(lastKnownLocation(activity)?.asLocation()) }
            return
        }

        val finished = AtomicBoolean(false)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!finished.compareAndSet(false, true)) return
                runCatching { manager.removeUpdates(this) }
                session.locationTimeout?.let(mainHandler::removeCallbacks)
                session.locationListener = null
                applyLocation(location)
            }

            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        session.locationListener?.let { old -> runCatching { manager.removeUpdates(old) } }
        session.locationListener = listener
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                runCatching { manager.removeUpdates(listener) }
                session.locationListener = null
                applyLocation(lastKnownLocation(activity)?.asLocation())
            }
        }
        session.locationTimeout = timeout
        mainHandler.postDelayed(timeout, LOCATION_TIMEOUT_MS)
        runCatching { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
            .onFailure { timeout.run() }
    }

    private fun LocationData.asLocation(): Location = Location("BeFake").apply {
        latitude = this@asLocation.latitude
        longitude = this@asLocation.longitude
    }

    private fun startMapLifecycle(session: MapSession) {
        if (session.destroyed) return
        if (!session.started) {
            session.mapView.onStart()
            session.started = true
        }
        if (!session.resumed) {
            session.mapView.onResume()
            session.resumed = true
        }
    }

    private fun pauseMapLifecycle(session: MapSession) {
        if (session.resumed) {
            session.mapView.onPause()
            session.resumed = false
        }
        if (session.started) {
            session.mapView.onStop()
            session.started = false
        }
    }

    private fun destroyMapSession(session: MapSession) {
        if (session.destroyed) return
        session.destroyed = true
        val manager = session.activity.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        session.locationListener?.let { listener -> runCatching { manager?.removeUpdates(listener) } }
        session.locationTimeout?.let(mainHandler::removeCallbacks)
        session.locationListener = null
        session.locationTimeout = null
        pauseMapLifecycle(session)
        session.mapView.onLowMemory()
        session.mapView.onDestroy()
    }

    private fun formatLocation(location: LocationData): String =
        String.format(Locale.US, "Lat: %.5f, Lng: %.5f", location.latitude, location.longitude)

    private fun destinationPinBitmap(): Bitmap = Bitmap.createBitmap(64, 80, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val pin = Path().apply {
            moveTo(32f, 78f)
            cubicTo(27f, 68f, 4f, 43f, 4f, 28f)
            cubicTo(4f, 12f, 16f, 2f, 32f, 2f)
            cubicTo(48f, 2f, 60f, 12f, 60f, 28f)
            cubicTo(60f, 43f, 37f, 68f, 32f, 78f)
            close()
        }
        canvas.drawPath(pin, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 55, 65) })
        canvas.drawCircle(32f, 28f, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun rounded(context: Context, color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * context.resources.displayMetrics.density
        }
}
