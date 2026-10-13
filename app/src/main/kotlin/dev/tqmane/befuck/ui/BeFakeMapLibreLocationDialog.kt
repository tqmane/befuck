package dev.tqmane.befuck.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.tqmane.befuck.R
import dev.tqmane.befuck.posting.LocationData
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
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** A full-screen map workspace. Late search/GPS proposals never overwrite a newer manual selection. */
object BeFakeLocationDialog {
    private const val LOCATION_PERMISSION_REQUEST = 0x4B35
    private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    private const val SOURCE = "befake-selected-location"
    private const val ICON = "befake-destination-pin"
    private const val TIMEOUT_MS = 15_000L
    private val main = Handler(Looper.getMainLooper())
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val sessions = WeakHashMap<Activity, MapSession>()
    private class MapSession(val activity: Activity, val resources: Resources, val dialog: Dialog, val mapView: MapView,
        val coordinates: TextView, val status: TextView, val results: LinearLayout, val resultsScroll: ScrollView, initial: LocationData?) {
        val state = LocationSelectionState(initial)
        var map: MapLibreMap? = null
        var source: GeoJsonSource? = null
        var started = false; var resumed = false; var destroyed = false
        var locationListener: LocationListener? = null
        var locationCancellation: CancellationSignal? = null
        var locationTimeout: Runnable? = null
        var permissionTicket: Long? = null
        var search: GeocodingRequest? = null
        var searchFuture: Future<*>? = null
    }
    @JvmStatic
    fun show(activity: Activity, resources: Resources, currentLocation: LocationData?, onConfirmed: (LocationData?) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        sessions[activity]?.dialog?.dismiss()
        // MapLibre retains applicationContext. It must not retain an Activity wrapper.
        val applicationResources = object : ContextWrapper(activity.applicationContext) {
            override fun getResources() = resources
            override fun getAssets() = resources.assets
            override fun getApplicationContext(): Context = this
        }
        val mapContext = object : ContextWrapper(activity) {
            override fun getResources() = resources
            override fun getAssets() = resources.assets
            override fun getApplicationContext(): Context = applicationResources
        }
        val mapView = runCatching {
            MapLibre.getInstance(applicationResources)
            MapView(mapContext).also { view ->
                try { view.onCreate(null) } catch (error: Throwable) { runCatching { view.onDestroy() }; throw error }
            }
        }.getOrElse {
            Toast.makeText(activity, resources.getString(R.string.befuck_location_map_failed), Toast.LENGTH_LONG).show()
            return
        }
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ExpressiveUi.BACKGROUND) }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(activity,16), dp(activity,10), dp(activity,16), dp(activity,10))
        }
        header.addView(ExpressiveUi.iconButton(activity, TablerIcon.X, resources.getString(R.string.befuck_close)) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(activity,48), dp(activity,48)))
        header.addView(ExpressiveUi.text(activity, resources.getString(R.string.befuck_location_title), 22f, bold=true), LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(activity,16) })
        header.addView(ExpressiveUi.iconButton(activity, TablerIcon.SETTINGS_2, resources.getString(R.string.befuck_location_provider)) { showProviderSettings(activity, resources) }, LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)))
        root.addView(header)
        val searchRow = LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(activity,16),0,dp(activity,16),dp(activity,12)) }
        val input = EditText(activity).apply {
            hint=resources.getString(R.string.befuck_location_search_hint); contentDescription=hint
            setTextColor(Color.WHITE); setHintTextColor(ExpressiveUi.SECONDARY); textSize=16f
            setSingleLine(true); imeOptions=EditorInfo.IME_ACTION_SEARCH
            background=ExpressiveUi.shape(activity,ExpressiveUi.CONTAINER); minimumHeight=dp(activity,52)
            setPadding(dp(activity,20),dp(activity,12),dp(activity,12),dp(activity,12))
            filters=arrayOf(android.text.InputFilter.LengthFilter(300))
        }
        searchRow.addView(input, LinearLayout.LayoutParams(0,-2,1f))
        val searchButton = ExpressiveUi.iconButton(activity, TablerIcon.SEARCH, resources.getString(R.string.befuck_location_search)) {}
        searchRow.addView(searchButton,LinearLayout.LayoutParams(dp(activity,52),dp(activity,52)).apply { marginStart=dp(activity,8) })
        root.addView(searchRow)
        val status=ExpressiveUi.text(activity,resources.getString(R.string.befuck_location_map_loading),13f,ExpressiveUi.SECONDARY).apply {
            setPadding(dp(activity,20),0,dp(activity,20),dp(activity,10)); accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)
        val results=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(activity,16),0,dp(activity,16),dp(activity,8)) }
        val resultsScroll=ScrollView(activity).apply { addView(results); visibility=View.GONE }
        root.addView(resultsScroll,LinearLayout.LayoutParams(-1,dp(activity,176)))
        val mapContainer=FrameLayout(activity).apply { background=ExpressiveUi.shape(activity); clipToOutline=true }
        mapContainer.addView(mapView,FrameLayout.LayoutParams(-1,-1))
        root.addView(mapContainer,LinearLayout.LayoutParams(-1,0,1f).apply { marginStart=dp(activity,8); marginEnd=dp(activity,8) })
        val bottom=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(activity,16),dp(activity,12),dp(activity,16),dp(activity,12)) }
        val selected=LinearLayout(activity).apply {
            gravity=Gravity.CENTER_VERTICAL; background=ExpressiveUi.shape(activity); setPadding(dp(activity,16),dp(activity,12),dp(activity,8),dp(activity,12))
        }
        val labels=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        labels.addView(ExpressiveUi.text(activity,resources.getString(R.string.befuck_location_selected_label),12f,ExpressiveUi.SECONDARY))
        val coordinates=ExpressiveUi.text(activity,"",15f,bold=true).apply { setPadding(0,dp(activity,4),0,0) }
        labels.addView(coordinates); selected.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        val session=MapSession(activity,resources,dialog,mapView,coordinates,status,results,resultsScroll,currentLocation)
        sessions[activity]=session
        selected.addView(ExpressiveUi.iconButton(activity,TablerIcon.TRASH,resources.getString(R.string.befuck_location_clear)) {
            cancelPending(session); session.state.select(null); renderSelection(session); status.text=resources.getString(R.string.befuck_location_map_hint)
        },LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)))
        bottom.addView(selected)
        bottom.addView(ExpressiveUi.button(activity,resources.getString(R.string.befuck_location_confirm),true,TablerIcon.CHECK) {
            if (isCurrent(session)) { val location=session.state.selection; dialog.dismiss(); onConfirmed(location) }
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(activity,10) })
        root.addView(bottom)
        mapContainer.addView(ExpressiveUi.iconButton(activity,TablerIcon.CURRENT_LOCATION,resources.getString(R.string.befuck_location_current)) {
            cancelPending(session); val ticket=session.state.begin()
            if (hasLocationPermission(activity)) requestCurrentLocation(session,ticket)
            else {
                session.permissionTicket=ticket
                runCatching { activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),LOCATION_PERMISSION_REQUEST) }
                    .onFailure { session.permissionTicket=null; status.text=resources.getString(R.string.befuck_location_permission_denied) }
            }
        },FrameLayout.LayoutParams(dp(activity,56),dp(activity,56),Gravity.END or Gravity.BOTTOM).apply { marginEnd=dp(activity,12); bottomMargin=dp(activity,42) })
        mapContainer.addView(ExpressiveUi.text(activity,"© OpenStreetMap · OpenFreeMap",11f).apply {
            background=ExpressiveUi.shape(activity,0xDD000000.toInt(),12f); setPadding(dp(activity,8),dp(activity,6),dp(activity,8),dp(activity,6))
        },FrameLayout.LayoutParams(-2,-2,Gravity.START or Gravity.BOTTOM).apply { marginStart=dp(activity,8); bottomMargin=dp(activity,8) })
        val retry=ExpressiveUi.button(activity,resources.getString(R.string.befuck_location_map_retry),icon=TablerIcon.REFRESH) { session.map?.let { loadStyle(session,it) } }.apply { visibility=View.GONE }
        mapContainer.addView(retry,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        mapView.addOnDidFailLoadingMapListener {
            if (isCurrent(session)) { session.source=null; status.text=resources.getString(R.string.befuck_location_map_failed); retry.visibility=View.VISIBLE }
        }
        mapView.addOnDidFinishLoadingStyleListener { if (isCurrent(session)) retry.visibility=View.GONE }
        mapView.getMapAsync { map ->
            if (!isCurrent(session)) return@getMapAsync
            session.map=map
            map.addOnMapClickListener { point ->
                if (isCurrent(session)) {
                    cancelPending(session)
                    if (session.state.select(LocationData(point.latitude,point.longitude))) { renderSelection(session); status.text=resources.getString(R.string.befuck_location_map_hint) }
                }; true
            }
            loadStyle(session,map)
        }
        fun submitSearch() {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken,0)
            input.clearFocus(); search(session,input.text.toString().trim())
        }
        searchButton.setOnClickListener { submitSearch() }
        input.setOnEditorActionListener { _,action,_ -> if (action==EditorInfo.IME_ACTION_SEARCH) { submitSearch(); true } else false }
        input.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { cancelPending(session); status.text=resources.getString(R.string.befuck_location_map_hint) }
            override fun afterTextChanged(s:Editable?)=Unit
        })
        renderSelection(session)
        dialog.setOnShowListener { startMapLifecycle(session) }
        dialog.setOnDismissListener { if (sessions[activity]===session) sessions.remove(activity); destroy(session) }
        runCatching { ExpressiveUi.showFullScreen(dialog,root) }.onFailure { if (sessions[activity]===session) sessions.remove(activity); destroy(session) }
    }
    private fun isCurrent(session:MapSession)=!session.destroyed && sessions[session.activity]===session && !session.activity.isFinishing && !session.activity.isDestroyed
    private fun loadStyle(session:MapSession,map:MapLibreMap) {
        session.source=null; session.status.text=session.resources.getString(R.string.befuck_location_map_loading)
        map.setStyle(STYLE_URL) { style:Style ->
            if (!isCurrent(session)) return@setStyle
            map.uiSettings.isAttributionEnabled=true; map.uiSettings.isCompassEnabled=true
            val source=GeoJsonSource(SOURCE,FeatureCollection.fromFeatures(emptyArray())); style.addSource(source)
            val pin=Bitmap.createBitmap(64,72,Bitmap.Config.ARGB_8888)
            Canvas(pin).also { canvas ->
                canvas.drawCircle(32f,32f,30f,android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK })
                TablerIconDrawable(TablerIcon.MAP_PIN,48,Color.WHITE).apply { setBounds(8,7,56,55); draw(canvas) }
            }
            style.addImage(ICON,pin); pin.recycle()
            style.addLayer(SymbolLayer("$SOURCE-layer",SOURCE).withProperties(PropertyFactory.iconImage(ICON),PropertyFactory.iconAnchor("bottom"),PropertyFactory.iconAllowOverlap(true),PropertyFactory.iconIgnorePlacement(true)))
            session.source=source
            val target=session.state.selection?:LocationData(35.681236,139.767125)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(target.latitude,target.longitude),14.0))
            renderSelection(session); session.status.text=session.resources.getString(R.string.befuck_location_map_hint)
        }
    }
    private fun renderSelection(session:MapSession) {
        val location=session.state.selection
        session.coordinates.text=location?.let { session.resources.getString(R.string.befuck_location_coordinates,it.latitude,it.longitude) }?:session.resources.getString(R.string.befuck_location_none)
        if (location==null) session.source?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        else session.source?.setGeoJson(Point.fromLngLat(location.longitude,location.latitude))
    }
    private fun choose(session:MapSession,location:LocationData) {
        cancelPending(session); if (!session.state.select(location)) return
        renderSelection(session); session.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude,location.longitude),14.0))
        session.status.text=session.resources.getString(R.string.befuck_location_map_hint)
    }
    private fun search(session:MapSession,query:String) {
        cancelPending(session); if (query.isBlank()) return
        val ticket=session.state.begin()
        val language=session.resources.configuration.locales[0]?.toLanguageTag()?:Locale.getDefault().toLanguageTag()
        val endpoint=session.activity.getSharedPreferences("befuck_map",Context.MODE_PRIVATE).getString(GeocodingRequest.ENDPOINT_PREFERENCE,GeocodingRequest.DEFAULT_ENDPOINT)?:GeocodingRequest.DEFAULT_ENDPOINT
        val request=GeocodingRequest(query,language,endpoint); session.search=request
        session.status.text=session.resources.getString(R.string.befuck_location_searching)
        session.searchFuture=searchExecutor.submit {
            val result=runCatching { request.execute() }
            main.post {
                if (!isCurrent(session)||!session.state.accepts(ticket)) return@post
                session.search=null; session.searchFuture=null
                result.onSuccess { places ->
                    session.results.removeAllViews()
                    session.status.text=session.resources.getString(if (places.isEmpty()) R.string.befuck_location_search_failed else R.string.befuck_location_search_results)
                    places.forEach { place -> session.results.addView(ExpressiveUi.row(session.activity,place.label,TablerIcon.MAP_PIN) { choose(session,place.location) },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(session.activity,4) }) }
                    session.resultsScroll.visibility=if (places.isEmpty()) View.GONE else View.VISIBLE
                }.onFailure { session.status.text=session.resources.getString(R.string.befuck_location_search_error) }
            }
        }
    }
    private fun showProviderSettings(activity:Activity,resources:Resources) {
        val prefs=activity.getSharedPreferences("befuck_map",Context.MODE_PRIVATE)
        val input=EditText(activity).apply {
            setText(prefs.getString(GeocodingRequest.ENDPOINT_PREFERENCE,GeocodingRequest.DEFAULT_ENDPOINT)); setTextColor(Color.WHITE)
            textSize=14f; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            background=ExpressiveUi.shape(activity,ExpressiveUi.CONTAINER,20f)
            setPadding(dp(activity,16),dp(activity,12),dp(activity,16),dp(activity,12))
        }
        ExpressiveDialogs.form(activity,resources.getString(R.string.befuck_location_provider),resources.getString(R.string.befuck_location_provider_hint),input,
            resources.getString(R.string.befuck_crop_done),resources.getString(R.string.befuck_close)) {
            val value=input.text.toString().trim()
            if (GeocodingRequest.validEndpoint(value)) { prefs.edit().putString(GeocodingRequest.ENDPOINT_PREFERENCE,value).apply(); true }
            else { input.error=resources.getString(R.string.befuck_location_provider_invalid); false }
        }
    }
    @JvmStatic fun onRequestPermissionsResult(activity:Activity,requestCode:Int,@Suppress("UNUSED_PARAMETER") grantResults:IntArray):Boolean {
        if (requestCode!=LOCATION_PERMISSION_REQUEST) return false
        val session=sessions[activity]?:return true
        val ticket=session.permissionTicket?:return true; session.permissionTicket=null
        if (!session.state.accepts(ticket)) return true
        if (hasLocationPermission(activity)) requestCurrentLocation(session,ticket)
        else session.status.text=session.resources.getString(R.string.befuck_location_permission_denied)
        return true
    }
    private fun hasLocationPermission(context:Context)=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
    @SuppressLint("MissingPermission")
    private fun requestCurrentLocation(session:MapSession,ticket:Long) {
        if (!isCurrent(session)||!session.state.accepts(ticket)) return
        val activity=session.activity
        val manager=activity.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val provider=manager?.let { service -> listOf(LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER).firstOrNull { name -> runCatching { service.isProviderEnabled(name) }.getOrDefault(false) } }
        if (manager==null||provider==null||!hasLocationPermission(activity)) { session.status.text=session.resources.getString(R.string.befuck_location_unavailable); return }
        cancelLocation(session); session.status.text=session.resources.getString(R.string.befuck_location_locating)
        fun complete(location:Location?) {
            if (!isCurrent(session)||!session.state.accepts(ticket)) return
            cancelLocation(session)
            if (location!=null && LocationSelectionState.isValid(LocationData(location.latitude,location.longitude))) choose(session,LocationData(location.latitude,location.longitude))
            else { session.state.invalidate(); session.status.text=session.resources.getString(R.string.befuck_location_unavailable) }
        }
        session.locationTimeout=Runnable { complete(null) }.also { main.postDelayed(it,TIMEOUT_MS) }
        if (Build.VERSION.SDK_INT>=30) {
            val signal=CancellationSignal(); session.locationCancellation=signal
            runCatching { manager.getCurrentLocation(provider,signal,activity.mainExecutor) { complete(it) } }.onFailure { complete(null) }
        } else {
            val listener=object:LocationListener {
                override fun onLocationChanged(location:Location) { complete(location) }
                @Deprecated("Deprecated in Android") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?)=Unit
                override fun onProviderEnabled(provider:String)=Unit
                override fun onProviderDisabled(provider:String) { complete(null) }
            }
            session.locationListener=listener
            runCatching { manager.requestSingleUpdate(provider,listener,Looper.getMainLooper()) }.onFailure { complete(null) }
        }
    }
    private fun cancelLocation(session:MapSession) {
        session.locationTimeout?.let(main::removeCallbacks); session.locationTimeout=null
        session.locationCancellation?.cancel(); session.locationCancellation=null
        session.locationListener?.let { listener -> runCatching { (session.activity.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(listener) } }
        session.locationListener=null
    }
    private fun cancelPending(session:MapSession) {
        session.state.invalidate(); session.permissionTicket=null; cancelLocation(session)
        session.search?.cancel(); session.search=null; session.searchFuture?.cancel(true); session.searchFuture=null
        session.results.removeAllViews(); session.resultsScroll.visibility=View.GONE
    }
    @JvmStatic fun onActivityPause(activity:Activity) { sessions[activity]?.let { session ->
        if (session.permissionTicket==null) cancelPending(session) else cancelLocation(session)
        pauseMapLifecycle(session)
    } }
    @JvmStatic fun onActivityResume(activity:Activity) { sessions[activity]?.takeIf { it.dialog.isShowing }?.let(::startMapLifecycle) }
    @JvmStatic fun onActivityDestroy(activity:Activity) { sessions[activity]?.dialog?.dismiss() }
    private fun startMapLifecycle(session:MapSession) {
        if (session.destroyed) return
        if (!session.started) { session.mapView.onStart(); session.started=true }
        if (!session.resumed) { session.mapView.onResume(); session.resumed=true }
    }
    private fun pauseMapLifecycle(session:MapSession) {
        if (session.resumed) { session.mapView.onPause(); session.resumed=false }
        if (session.started) { session.mapView.onStop(); session.started=false }
    }
    private fun destroy(session:MapSession) {
        if (session.destroyed) return
        session.destroyed=true; cancelPending(session); session.state.close(); pauseMapLifecycle(session)
        runCatching { session.mapView.onDestroy() }; session.source=null; session.map=null
    }
    private fun dp(context:Context,value:Int)=ExpressiveUi.dp(context,value)
}
