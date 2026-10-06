package dev.tqmane.befuck.symbols

import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Names and full signatures only; native objects and credentials are never serialized. */
internal object SymbolCacheCodec {
    const val SCHEMA = 1
    private val rootKeys = listOf("packageName", "versionName", "sourceIdentity", "dexKitAvailable", "stringFields", "cameraCountdownComposableMethod", "feedMediaSymbols", "timelineBlurredCardComposableMethod", "pullDownGridCardComposableMethod", "pullDownGridMediaComposableMethod", "homeGridPostTileComposableMethod", "homeFeedCanBlurMapperMethod", "homeFeedItemEmitterMethod", "friendsOfFriendsFeedItemEmitterMethod", "postDomainModelClass", "postDomainModelConstructor", "postDomainModelFieldOrder", "postContentsClass", "postContentsConstructor", "postContentsFieldOrder", "postMediaClass", "postMediaConstructor", "postCoreDraftClass", "postCoreDraftConstructor", "sendPostCoroutineConstructor", "sendPostRepositoryInterface", "postUploadWorkerClass", "sendDraftMethod", "friendsVisibility", "friendOfFriendsVisibility", "globalVisibility", "currentUserProviderClass", "currentUserProviderMethod", "currentUserUidField", "cameraViewModelClass", "cameraFacingEnumClass", "cameraBindConcurrentMethod", "cameraOriginParserMethods", "locationRepositoryClass", "locationRequestMethod", "locationClientGetter", "diagnostics")
    private val feedKeys = listOf("postModelClass", "postDeserializerMethod", "postIdField", "postTakenAtField", "postCaptionField", "postPrimaryField", "postPrimaryMediaField", "postSecondaryField", "postSecondaryMediaField", "postBtsMediaField", "mediaUrlField", "mediaWidthField", "mediaHeightField", "mediaTypeField", "postFeedCardComposableMethod", "postDataIdField", "postDataIsMineField", "viewStatePostDataField", "viewStateDualMediaField", "viewStateRealSponsoredPostUiStateField", "dualMediaPrimaryField", "dualMediaSecondaryField", "blurredMediaRenderMethod", "blurredOverlayComposableMethod")

    fun encode(symbols: ResolvedSymbols): JSONObject = record(symbols, rootKeys, "symbols")

    fun decode(json: JSONObject, loader: ClassLoader, identity: String): ResolvedSymbols {
        json.getJSONObject("values").put("sourceIdentity", identity)
        return read(json, loader) as ResolvedSymbols
    }

    private fun record(value: Any, keys: List<String>, kind: String): JSONObject {
        val values = JSONObject()
        keys.forEach { key ->
            val field = value.javaClass.getDeclaredField(key).apply { isAccessible = true }
            values.put(key, write(field.get(value)))
        }
        return JSONObject().put("kind", kind).put("values", values)
    }

    private fun write(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is String, is Boolean, is Number -> value
        is Class<*> -> JSONObject().put("kind", "class").put("name", value.name)
        is Field -> JSONObject().put("kind", "field").put("owner", value.declaringClass.name)
            .put("name", value.name).put("type", value.type.name)
        is Method -> JSONObject().put("kind", "method").put("owner", value.declaringClass.name)
            .put("name", value.name).put("parameters", JSONArray(value.parameterTypes.map { it.name }))
            .put("returns", value.returnType.name)
        is Constructor<*> -> JSONObject().put("kind", "constructor").put("owner", value.declaringClass.name)
            .put("parameters", JSONArray(value.parameterTypes.map { it.name }))
        is List<*> -> JSONArray().apply { value.forEach { put(write(it)) } }
        is Map<*, *> -> JSONObject().put("kind", "map").put("values", JSONObject().apply {
            value.forEach { (key, item) -> put(key as String, write(item)) }
        })
        is ResolvedSymbols -> record(value, rootKeys, "symbols")
        is FeedMediaSymbols -> record(value, feedKeys, "feed")
        is Enum<*> -> JSONObject().put("kind", "enum").put("owner", value.declaringJavaClass.name).put("name", value.name)
        else -> {
            val singleton = value.javaClass.declaredFields.single { field ->
                Modifier.isStatic(field.modifiers) && field.type == value.javaClass &&
                    field.apply { isAccessible = true }.get(null) === value
            }
            JSONObject().put("kind", "singleton").put("owner", singleton.declaringClass.name).put("name", singleton.name)
        }
    }

    private fun type(name: String, loader: ClassLoader): Class<*> = when (name) {
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        "void" -> Void.TYPE
        else -> Class.forName(name, false, loader)
    }

    private fun read(value: Any?, loader: ClassLoader): Any? {
        if (value == null || value === JSONObject.NULL) return null
        if (value is JSONArray) return (0 until value.length()).map { read(value.get(it), loader) }
        if (value !is JSONObject) return value
        val kind = value.getString("kind")
        fun owner() = type(value.getString("owner"), loader)
        fun parameters(): Array<Class<*>> {
            val values = value.getJSONArray("parameters")
            require(values.length() <= 64)
            return Array(values.length()) { type(values.getString(it), loader) }
        }
        return when (kind) {
            "class" -> type(value.getString("name"), loader)
            "field" -> owner().getDeclaredField(value.getString("name")).apply {
                require(type.name == value.getString("type")); isAccessible = true
            }
            "method" -> owner().getDeclaredMethod(value.getString("name"), *parameters()).apply {
                require(returnType.name == value.getString("returns")); isAccessible = true
            }
            "constructor" -> owner().getDeclaredConstructor(*parameters()).apply { isAccessible = true }
            "map" -> value.getJSONObject("values").let { values -> values.keys().asSequence().associateWith { read(values.get(it), loader) } }
            "enum" -> requireNotNull(owner().enumConstants) { "Cached enum owner is not an enum" }
                .single { (it as Enum<*>).name == value.getString("name") }
            "singleton" -> owner().getDeclaredField(value.getString("name")).apply { isAccessible = true }.get(null)
            "symbols", "feed" -> {
                val keys = if (kind == "symbols") rootKeys else feedKeys
                val recordType = if (kind == "symbols") ResolvedSymbols::class.java else FeedMediaSymbols::class.java
                val values = value.getJSONObject("values")
                val args = keys.map { key -> require(values.has(key)); read(values.get(key), loader) }.toTypedArray()
                recordType.declaredConstructors.single { it.parameterCount == keys.size }
                    .apply { isAccessible = true }.newInstance(*args)
            }
            else -> error("Unknown symbol cache value: $kind")
        }
    }
}
