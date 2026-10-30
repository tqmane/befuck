package dev.tqmane.befuck.symbols

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import dev.tqmane.befuck.posting.LocationData
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** A snapshot of the host's already privacy-filtered display state, never its raw post DTO. */
data class PostMapSnapshot(
    val post: LocationData?,
    val user: LocationData?,
    val approximate: Boolean,
    val bitmap: Bitmap?,
    val locationAllowed: Boolean = false,
    val postId: String? = null,
    val canRequestLocation: Boolean = true,
)

/** All host reflection is resolved and shape-checked before installing the replacement. */
open class PostMapSymbols internal constructor(
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
    private val dynamicStates: List<Field> = emptyList(),
    private val postIdReader: ((Any) -> String?)? = null,
    private val permissionActionAvailable: Boolean = true,
) {
    open val composerArgument: Int = 1
    open val inHostSheet: Boolean = false
    open fun matches(owner: Any): Boolean = true
    open fun snapshotInvocation(owner: Any, arguments: Array<Any?>): PostMapSnapshot = snapshot(owner)
    open fun requestLocationPermissionInvocation(owner: Any, arguments: Array<Any?>) = requestLocationPermission(owner)
    open fun modifierForInvocation(arguments: Array<Any?>): Any = fillModifier
    private fun field(name: String) = fields.getValue(name)
    private fun state(owner: Any, name: String): Any? = readState.invoke(field(name).get(owner))

    open fun snapshot(owner: Any): PostMapSnapshot {
        // Read Compose State HERE, not in the AndroidView update callback: these reads
        // must subscribe the enclosing composition to new locations and loaded photos.
        // R8 can reorder/name lambda captures. Their current value types identify
        // these roles; null means loading, never a license to guess another field.
        // Read every State even while null so every transition subscribes Compose.
        val values = dynamicStates.associateWith { readState.invoke(it.get(owner)) }
        fun onlyValue(type: Class<*>): Any? = values.values.filter(type::isInstance).singleOrNull()
        val ready = if (dynamicStates.isEmpty()) state(owner, "state") else onlyValue(field("post").declaringClass)
        val current = if (dynamicStates.isEmpty()) state(owner, "userState") else onlyValue(field("latitude").declaringClass)
        val photos = if (dynamicStates.isEmpty()) state(owner, "photos") else onlyValue(field("low").declaringClass)
        val high = (if (dynamicStates.isEmpty()) state(owner, "toggle") else values[field("toggle")]) == true
        val allowed = if (dynamicStates.isEmpty()) state(owner, "permission") == true else
            values.filterKeys { it != field("toggle") }.values.filterIsInstance<Boolean>().singleOrNull() == true
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
            runCatching { postIdReader?.invoke(owner) }.getOrNull(),
            permissionActionAvailable,
        )
    }

    private fun coordinate(value: Any?): LocationData? {
        if (!field("latitude").declaringClass.isInstance(value)) return null
        val lat = field("latitude").getDouble(value)
        val lon = field("longitude").getDouble(value)
        return if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
            (lat != 0.0 || lon != 0.0))
            LocationData(lat, lon) else null
    }

    open fun toggle(owner: Any) {
        val state = field("toggle").get(owner)
        val selected = readState.invoke(state)
        if (selected is Boolean) writeState.invoke(state, !selected)
    }

    open fun close(owner: Any) { back.invoke(field("navigation").get(owner)) }
    open fun requestLocationPermission(owner: Any) { if (permissionActionAvailable) askLocation(owner) }

    fun compose(composer: Any, factory: (Context) -> View, update: (View) -> Unit, release: (View) -> Unit,
                modifier: Any = fillModifier) {
        require(androidView.parameterTypes[5].isInstance(composer) && androidView.parameterTypes[1].isInstance(modifier))
        fun callback(block: (Any?) -> Any?): Any = Proxy.newProxyInstance(function1.classLoader, arrayOf(function1)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> block(args?.getOrNull(0))
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "BeFuckPostMapCallback"
                else -> error("Unexpected Function1 method: ${method.name}")
            }
        }
        androidView.invoke(null, callback { factory(it as Context) }, modifier,
            null, // No view reuse across different post-map compositions.
            callback { release(it as View); unit }, callback { update(it as View); unit },
            composer, 0, 0)
    }

}
