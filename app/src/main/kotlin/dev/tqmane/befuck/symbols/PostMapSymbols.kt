package dev.tqmane.befuck.symbols

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import dev.tqmane.befuck.posting.LocationData
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** A snapshot of the host's already privacy-filtered display state, never its raw post DTO. */
data class PostMapSnapshot(
    val post: LocationData?,
    val user: LocationData?,
    val approximate: Boolean,
    val bitmap: Bitmap?,
    val locationAllowed: Boolean = false,
)

/** All host reflection is resolved and shape-checked before installing the replacement. */
class PostMapSymbols internal constructor(
    val render: Method,
    private val androidView: Method,
    private val fillModifier: Any,
    val unit: Any,
    private val function1: Class<*>,
    private val readState: Method,
    private val writeState: Method,
    private val back: Method,
    private val fields: Map<String, Field>,
    private val askLocation: (Any) -> Unit,
) {
    private fun field(name: String) = fields.getValue(name)
    private fun state(owner: Any, name: String): Any? = readState.invoke(field(name).get(owner))

    fun snapshot(owner: Any): PostMapSnapshot {
        // Read Compose State HERE, not in the AndroidView update callback: these reads
        // must subscribe the enclosing composition to new locations and loaded photos.
        val ready = state(owner, "state")
        val current = state(owner, "userState")
        val photos = state(owner, "photos")
        val high = state(owner, "toggle") == true
        val allowed = state(owner, "permission") == true
        val isReady = field("post").declaringClass.isInstance(ready)
        val isPhotoReady = field("low").declaringClass.isInstance(photos)
        val bitmap = if (isPhotoReady) {
            (if (high) field("high").get(photos) else null) ?: field("low").get(photos)
        } else null
        return PostMapSnapshot(
            if (isReady) coordinate(field("post").get(ready)) else null,
            coordinate(current) ?: if (isReady) coordinate(field("user").get(ready)) else null,
            isReady && field("approximate").getBoolean(ready),
            (bitmap as? Bitmap)?.takeUnless { it.isRecycled },
            allowed,
        )
    }

    private fun coordinate(value: Any?): LocationData? {
        if (!field("latitude").declaringClass.isInstance(value)) return null
        val lat = field("latitude").getDouble(value)
        val lon = field("longitude").getDouble(value)
        return if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
            LocationData(lat, lon) else null
    }

    fun toggle(owner: Any) {
        val state = field("toggle").get(owner)
        writeState.invoke(state, readState.invoke(state) != true)
    }

    fun close(owner: Any) { back.invoke(field("navigation").get(owner)) }
    fun requestLocationPermission(owner: Any) { askLocation(owner) }

    fun compose(composer: Any, factory: (Context) -> View, update: (View) -> Unit, release: (View) -> Unit) {
        require(androidView.parameterTypes[5].isInstance(composer))
        fun callback(block: (Any?) -> Any?): Any = Proxy.newProxyInstance(function1.classLoader, arrayOf(function1)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> block(args?.getOrNull(0))
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "BeFuckPostMapCallback"
                else -> error("Unexpected Function1 method: ${method.name}")
            }
        }
        androidView.invoke(null, callback { factory(it as Context) }, fillModifier,
            null, // No view reuse across different post-map compositions.
            callback { release(it as View); unit }, callback { update(it as View); unit },
            composer, 0, 0)
    }

    companion object {
        /** Verified against every DEX in BeReal 3.97.1 (3599414). Not used for other versions. */
        internal fun resolve3971(loader: ClassLoader): PostMapSymbols {
            fun type(name: String) = Class.forName(name, false, loader)
            fun member(owner: Class<*>, name: String, expected: Class<*>): Field = owner.getDeclaredField(name).apply {
                require(this.type == expected && !Modifier.isStatic(modifiers))
                isAccessible = true
            }
            fun method(owner: Class<*>, name: String, result: Class<*>, static: Boolean, vararg args: Class<*>): Method =
                owner.getDeclaredMethod(name, *args).apply {
                    require(returnType == result && Modifier.isStatic(modifiers) == static)
                    isAccessible = true
                }
            val content = type("mmg")
            val state = type("androidx.compose.runtime.State")
            val mutable = type("androidx.compose.runtime.MutableState")
            val ready = type("rmg")
            val photos = type("qwb")
            val latLng = type("com.google.android.gms.maps.model.LatLng")
            val modifier = type("androidx.compose.ui.Modifier")
            val function1 = type("vr8")
            require(state.isInterface && mutable.isInterface && function1.isInterface)
            require(type("as8").isAssignableFrom(content))
            val fields = mapOf(
                "state" to member(content, "a", state), "userState" to member(content, "f", state),
                "photos" to member(content, "i", state), "toggle" to member(content, "j", mutable),
                "permission" to member(content, "h", state),
                "navigation" to member(content, "g", type("lbe")),
                "post" to member(ready, "a", latLng), "user" to member(ready, "b", latLng),
                "approximate" to member(ready, "c", Boolean::class.javaPrimitiveType!!),
                "low" to member(photos, "a", Bitmap::class.java), "high" to member(photos, "b", Bitmap::class.java),
                "latitude" to member(latLng, "a", Double::class.javaPrimitiveType!!),
                "longitude" to member(latLng, "b", Double::class.javaPrimitiveType!!),
            )
            val companion = type("androidx.compose.ui.Modifier\$Companion").getDeclaredField("a").get(null)
            // In this APK e=fillMaxSize, f=fillMaxWidth. A width-only modifier yields a zero-height map.
            val fill = method(type("androidx.compose.foundation.layout.SizeKt"), "e", modifier, true,
                modifier, Float::class.javaPrimitiveType!!).invoke(null, companion, 1f)
            // Keep the host's permission launcher/flow. pmg case 3 requests permission;
            // case 2 performs a Google camera update and is deliberately NOT invoked.
            val viewModel = member(content, "e", type("xmg"))
            val scope = method(type("androidx.lifecycle.ViewModelKt"), "a",
                type("androidx.lifecycle.viewmodel.internal.CloseableCoroutineScope"), true, type("androidx.lifecycle.ViewModel"))
            val request = type("pmg").getDeclaredConstructor(type("xmg"), type("xw4"), Int::class.javaPrimitiveType!!)
            val launch = method(type("tx9"), "u", type("vbm"), true, type("p85"), type("c85"), type("s85"), type("xr8"), Int::class.javaPrimitiveType!!)
            return PostMapSymbols(
                method(content, "invoke", Any::class.java, false, Any::class.java, Any::class.java, Any::class.java),
                method(type("androidx.compose.ui.viewinterop.AndroidView_androidKt"), "a", Void.TYPE, true,
                    function1, modifier, function1, function1, function1, type("androidx.compose.runtime.Composer"),
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
                requireNotNull(fill), requireNotNull(type("whn").getDeclaredField("a").get(null)), function1,
                method(state, "getValue", Any::class.java, false),
                method(mutable, "setValue", Void.TYPE, false, Any::class.java),
                method(type("tbe"), "f", Void.TYPE, false), fields,
                { owner ->
                    val vm = viewModel.get(owner)
                    launch.invoke(null, scope.invoke(null, vm), null, null, request.newInstance(vm, null, 3), 3)
                },
            )
        }
    }
}
