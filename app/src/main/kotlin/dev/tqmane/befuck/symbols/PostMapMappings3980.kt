package dev.tqmane.befuck.symbols

import android.graphics.Bitmap
import dev.tqmane.befuck.download.PostLocation
import dev.tqmane.befuck.posting.LocationData
import java.lang.reflect.Modifier

/** 3.98.0's screen exposes a privacy-filtered Precise/Approximate marker, not Ready/BitmapReady. */
internal object PostMapMappings3980 {
    fun resolve(loader: ClassLoader): PostMapSymbols? = runCatching {
        fun type(name: String) = Class.forName(name, false, loader)
        fun field(owner: Class<*>, name: String, expected: Class<*>) = owner.getDeclaredField(name).apply {
            require(this.type == expected && !Modifier.isStatic(modifiers))
            isAccessible = true
        }
        val content = type("c3a")
        val vm = type("hfg")
        require(type("androidx.lifecycle.ViewModel").isAssignableFrom(vm))
        val branch = field(content, "a", Int::class.javaPrimitiveType!!)
        val viewModel = field(content, "b", Any::class.java)
        val displayState = field(content, "d", Any::class.java)
        val permissionState = field(content, "e", Any::class.java)
        val state = type("androidx.compose.runtime.State")
        val read = state.getMethod("getValue").apply { require(returnType == Any::class.java) }
        val write = type("androidx.compose.runtime.MutableState").getMethod("setValue", Any::class.java)
        val display = type("ffg")
        val marker = type("efg")
        val approximate = type("cfg")
        val precise = type("dfg")
        require(marker.isInterface && marker.isAssignableFrom(approximate) && marker.isAssignableFrom(precise))
        val postId = field(display, "a", String::class.java)
        val displayMarker = field(display, "c", marker)
        val latLng = type("com.google.android.gms.maps.model.LatLng")
        val location = marker.getMethod("getLocation").apply { require(returnType == latLng && parameterCount == 0) }
        val latitude = field(latLng, "latitude", Double::class.javaPrimitiveType!!)
        val longitude = field(latLng, "longitude", Double::class.javaPrimitiveType!!)
        val avatar = field(precise, "c", type("androidx.compose.ui.graphics.ImageBitmap"))
        val imageBitmap = type("androidx.compose.ui.graphics.AndroidImageBitmap")
        val bitmap = field(imageBitmap, "a", Bitmap::class.java)
        val close = type("l9e").getDeclaredConstructor(Any::class.java, Int::class.javaPrimitiveType)
        val closeInvoke = type("l9e").getDeclaredMethod("invoke")
        val ask = type("uag").getDeclaredConstructor(Int::class.javaPrimitiveType, Any::class.java, Class::class.java,
            String::class.java, String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val askInvoke = type("uag").getDeclaredMethod("invoke")
        val render = content.getDeclaredMethod("invoke", Any::class.java, Any::class.java)
        require(render.returnType == Any::class.java && !Modifier.isStatic(render.modifiers))
        val modifier = type("androidx.compose.ui.Modifier")
        val companion = type("androidx.compose.ui.Modifier\$Companion").getDeclaredField("a").get(null)
        val fill = type("androidx.compose.foundation.layout.SizeKt")
            .getDeclaredMethod("e", modifier, Float::class.javaPrimitiveType).invoke(null, companion, 1f)
        require(modifier.isInstance(fill) && fill.javaClass.name == "androidx.compose.foundation.layout.FillElement")
        require(fill.javaClass.declaredFields.filter { it.type == String::class.java }.single()
            .apply { isAccessible = true }.get(fill) == "fillMaxSize")
        val unit = type("c8n").getDeclaredField("a").get(null)
        require(unit.toString() == "kotlin.Unit")

        fun snapshot(value: Any?, allowed: Boolean): PostMapSnapshot {
            if (!display.isInstance(value)) return PostMapSnapshot(null, null, false, null, allowed)
            val displayedMarker = displayMarker.get(value)
            // Read only the point the native map displays. Never use PostMapDestination's raw coordinates.
            val isApproximate = approximate.isInstance(displayedMarker)
            val isPrecise = precise.isInstance(displayedMarker)
            val point = if (isApproximate || isPrecise) location.invoke(displayedMarker) else null
            val post = if (latLng.isInstance(point)) PostLocation.create(latitude.getDouble(point),
                longitude.getDouble(point), isApproximate)?.let { LocationData(it.latitude, it.longitude) } else null
            val photo = if (isPrecise) avatar.get(displayedMarker) else null
            val image = if (imageBitmap.isInstance(photo)) (bitmap.get(photo) as Bitmap).takeUnless { it.isRecycled } else null
            return PostMapSnapshot(post, null, isApproximate, image, allowed,
                (postId.get(value) as? String)?.takeIf { it.isNotBlank() && it.length <= 512 })
        }
        // Calibrate data-only constructors against a synthetic approximate marker before hooking anything.
        val pointProbe = latLng.getDeclaredConstructor(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
            .newInstance(1.25, 2.5)
        val markerProbe = approximate.getDeclaredConstructor(latLng).newInstance(pointProbe)
        val displayProbe = display.getDeclaredConstructor(String::class.java, String::class.java, marker)
            .newInstance("befuck-map-probe", "", markerProbe)
        val probe = snapshot(displayProbe, false)
        require(probe.approximate && probe.post == LocationData(1.25, 2.5) && probe.bitmap == null && !probe.locationAllowed)

        object : PostMapSymbols(render, PostMapSymbolResolver.androidView(loader, modifier), fill, unit, type("yo8"),
            read, write, closeInvoke, emptyMap(), {}) {
            override val composerArgument = 0
            override fun matches(owner: Any): Boolean = content.isInstance(owner) && branch.getInt(owner) == 22 &&
                vm.isInstance(viewModel.get(owner)) && state.isInstance(displayState.get(owner)) && state.isInstance(permissionState.get(owner))
            override fun snapshot(owner: Any) = snapshot(read.invoke(displayState.get(owner)), read.invoke(permissionState.get(owner)) == true)
            // This version has one avatar bitmap, rather than two photo resolutions.
            override fun toggle(owner: Any) = Unit
            override fun close(owner: Any) { closeInvoke.invoke(close.newInstance(viewModel.get(owner), 16)) }
            override fun requestLocationPermission(owner: Any) {
                askInvoke.invoke(ask.newInstance(0, viewModel.get(owner), vm, "onAskPermissionClick", "onAskPermissionClick()V", 0, 2))
            }
        }
    }.getOrNull()
}
