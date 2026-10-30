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
        val latitude = field(latLng, "a", Double::class.javaPrimitiveType!!)
        val longitude = field(latLng, "b", Double::class.javaPrimitiveType!!)
        val avatar = field(precise, "c", type("androidx.compose.ui.graphics.ImageBitmap"))
        val imageBitmap = type("androidx.compose.ui.graphics.AndroidImageBitmap")
        val bitmap = field(imageBitmap, "a", Bitmap::class.java)
        // bfg.c is the map body inside the host's ModalBottomSheet. Hooking c3a.invoke
        // instead would replace the sheet itself, losing its drag and dismissal behavior.
        val modifier = type("androidx.compose.ui.Modifier")
        val function0 = type("uo8")
        val render = type("bfg").getDeclaredMethod("c", modifier, display,
            Boolean::class.javaPrimitiveType, function0, type("androidx.compose.foundation.layout.PaddingValues"),
            type("androidx.compose.runtime.Composer"), Int::class.javaPrimitiveType)
        require(Modifier.isStatic(render.modifiers) && render.returnType == Void.TYPE)
        val askInvoke = function0.getMethod("invoke").apply {
            require(parameterCount == 0 && returnType == Any::class.java)
        }
        val closeInvoke = type("l9e").getDeclaredMethod("invoke")
        val companion = type("androidx.compose.ui.Modifier\$Companion").getDeclaredField("a").get(null)
        val fillMaxSize = type("androidx.compose.foundation.layout.SizeKt")
            .getDeclaredMethod("e", modifier, Float::class.javaPrimitiveType)
        val fill = fillMaxSize.invoke(null, companion, 1f)
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
            override val composerArgument = 5
            override val inHostSheet = true
            override fun matches(owner: Any) = display.isInstance(owner)
            override fun snapshot(owner: Any) = snapshot(owner, false)
            override fun snapshotInvocation(owner: Any, arguments: Array<Any?>): PostMapSnapshot {
                require(arguments.size == 7 && arguments[1] === owner && arguments[2] is Boolean)
                return snapshot(owner, arguments[2] == true)
            }
            override fun modifierForInvocation(arguments: Array<Any?>): Any {
                // Preserve the sheet's own width, height and inset modifiers.
                require(modifier.isInstance(arguments[0]))
                return requireNotNull(fillMaxSize.invoke(null, arguments[0], 1f))
            }
            // The host sheet keeps the close button; the 3.98.0 marker has one avatar.
            override fun toggle(owner: Any) = Unit
            override fun close(owner: Any) = Unit
            override fun requestLocationPermissionInvocation(owner: Any, arguments: Array<Any?>) {
                val callback = arguments.getOrNull(3)?.takeIf(function0::isInstance) ?: return
                askInvoke.invoke(callback)
            }
        }
    }.getOrNull()
}
