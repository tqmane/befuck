package dev.tqmane.befuck.symbols

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import dev.tqmane.befuck.runtime.RuntimeSymbolCache
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.function.Consumer

/** Resolve only the NPatch post-map surface, independently of the host's other feature mappings. */
object PostMapSymbolResolver {
    private const val CACHE_KEY = "reader:post-map"
    private const val SCHEMA = 1
    private const val STATE = "androidx.compose.runtime.State"
    private const val MUTABLE_STATE = "androidx.compose.runtime.MutableState"
    private const val COMPOSER = "androidx.compose.runtime.Composer"
    private const val MODIFIER = "androidx.compose.ui.Modifier"
    private const val ANDROID_VIEW = "androidx.compose.ui.viewinterop.AndroidView_androidKt"
    private const val SIZE = "androidx.compose.foundation.layout.SizeKt"
    private const val LAT_LNG = "com.google.android.gms.maps.model.LatLng"
    private val renderTags = listOf("postmapscreen_map_view", "postmapscreen_mylocation_button", "postmapscreen_topbar_view")

    @JvmStatic
    @Synchronized
    fun resolve(
        host: Context,
        loader: ClassLoader,
        module: ApplicationInfo,
        versionName: String?,
        versionCode: Long,
        logger: Consumer<String>,
    ): PostMapSymbols? {
        // This check precedes preferences, native loading, and class discovery.
        val installed = runCatching {
            host.packageManager.getApplicationInfo(host.packageName, PackageManager.GET_META_DATA)
        }.getOrNull() ?: return null
        if (installed.metaData?.containsKey("npatch") != true) return null
        KnownMappings.find(versionName, versionCode)?.postMapSymbols(loader)?.let {
            logger.accept("[PostMap] Resolved version-verified display-state and Compose contracts")
            return it
        }
        val settings = host.getSharedPreferences("befuck_runtime", Context.MODE_PRIVATE)
        val identity = RuntimeSymbolCache.identity(host.applicationInfo, module, versionName, versionCode)
        val cached = settings.getString(CACHE_KEY, null)
        // Clear-resolution removes all reader:* keys. The shared force_scan flag
        // belongs to the general resolver and can stay set on an unknown host.
        if (cached != null) {
            val restored = runCatching {
                val json = JSONObject(cached)
                require(json.getInt("schema") == SCHEMA && json.getString("identity") == identity)
                bind(Descriptor.read(json.getJSONObject("members"), loader), loader, versionName, versionCode, logger)
            }.getOrNull()
            if (restored != null) {
                logger.accept("[PostMap] Restored validated post-map signatures")
                return restored
            }
            settings.edit().remove(CACHE_KEY).apply()
        }
        try {
            System.loadLibrary("dexkit")
            // A patched APK may contain only its loader. Inspect the actual host
            // class loader first so NPatch's loaded original DEX files participate.
            val descriptor = runCatching {
                DexKitBridge.create(loader, true).use { bridge ->
                    bridge.setThreadNum(2)
                    scan(bridge, loader)
                }
            }.getOrElse {
                BeRealSymbolResolver.openDexKit(host.applicationInfo.sourceDir).use { bridge ->
                    bridge.setThreadNum(2)
                    scan(bridge, loader)
                }
            }
            val symbols = bind(descriptor, loader, versionName, versionCode, logger)
            settings.edit().putString(CACHE_KEY, JSONObject().put("schema", SCHEMA)
                .put("identity", identity).put("members", descriptor.json()).toString()).apply()
            logger.accept("[PostMap] Resolved map, display-state, photo, Compose and navigation contracts")
            return symbols
        } catch (failure: Throwable) {
            settings.edit().remove(CACHE_KEY).apply()
            // Do not log model values, coordinates, navigation arguments or image URLs.
            logger.accept("[PostMap] Replacement unavailable: unrecognized or ambiguous host contract (${failure.javaClass.simpleName})")
            return null
        }
    }

    private fun scan(bridge: DexKitBridge, loader: ClassLoader): Descriptor {
        fun tagged(vararg strings: String): List<MethodData> = bridge.findMethod(
            FindMethod.create().matcher(MethodMatcher.create().usingStrings(*strings)),
        ).filter { it.usingStrings.containsAll(strings.toList()) }
        fun model(vararg strings: String): Class<*> = tagged(*strings).filter {
            it.name == "toString" && it.paramCount == 0 && it.returnTypeName == String::class.java.name
        }.map { it.getClassInstance(loader) }.distinct().single()
        val renderData = tagged(*renderTags.toTypedArray()).filter {
            it.name == "invoke" && it.paramTypeNames == List(3) { Any::class.java.name } &&
                it.returnTypeName == Any::class.java.name && !Modifier.isStatic(it.modifiers)
        }.single()
        val render = renderData.getMethodInstance(loader).accessible()
        // Require the tagged screen to actually construct this render lambda.
        require(tagged("postmapscreen_view").any { screen ->
            screen.invokes.any { it.isConstructor && it.declaredClassName == render.declaringClass.name }
        })
        val ready = model("Ready(markerLocation=", ", userLocation=", ", showCircle=")
        val photos = model("BitmapReady(lowBitmap=", ", highBitmap=")
        val params = runCatching { model("MapParams(primaryUrl=", ", postId=", ", momentId=", ", isFriend=") }.getOrNull()
        val modifier = type(MODIFIER, loader)
        val fill = tagged("fillMaxSize").filter {
            it.declaredClassName == SIZE && Modifier.isStatic(it.modifiers) &&
                it.returnTypeName == MODIFIER && it.paramTypeNames == listOf(MODIFIER, "float")
        }.single().getMethodInstance(loader).accessible()
        val androidView = androidView(loader, modifier)
        val unit = renderData.usingFields.filter { usage ->
            usage.usingType.isRead() && Modifier.isStatic(usage.field.modifiers)
        }.mapNotNull { usage ->
            runCatching { usage.field.getFieldInstance(loader).accessible() }.getOrNull()
        }.distinct().filter { field ->
            field.type == field.declaringClass && instanceFields(field.type).isEmpty() &&
                runCatching { field.get(null)?.toString() == "kotlin.Unit" }.getOrDefault(false)
        }.single()

        // The map's native close callback invokes a no-argument command on the
        // navigation manager. Prove both its manager role and the callback edge.
        val navigation = tagged("NavigationManager", "Order navigate to: ").map { it.getClassInstance(loader) }.distinct().single()
        val navigationField = instanceFields(render.declaringClass).filter {
            it.type.isInterface && it.type.isAssignableFrom(navigation)
        }.single()
        val callbackClasses = renderData.invokes.filter { it.isConstructor && navigationField.type.name in it.paramTypeNames }
            .mapNotNull { it.declaredClass }.distinctBy { it.name }
        val backCalls = callbackClasses.flatMap { callback ->
            callback.findMethod(FindMethod.create().matcher(MethodMatcher.create().name("invoke").paramCount(0)))
                .flatMap { it.invokes }
        }.filter { call ->
            call.declaredClassName == navigation.name && call.paramCount == 0 && call.returnTypeName == "void" &&
                !Modifier.isStatic(call.modifiers)
        }.distinctBy { it.descriptor }
        val back = backCalls.single().getMethodInstance(loader).accessible()
        return Descriptor(render, androidView, fill, unit, ready, photos, back, params)
    }

    private fun bind(
        descriptor: Descriptor,
        loader: ClassLoader,
        versionName: String?,
        versionCode: Long,
        logger: Consumer<String>,
    ): PostMapSymbols {
        val state = type(STATE, loader)
        val mutable = type(MUTABLE_STATE, loader)
        val modifier = type(MODIFIER, loader)
        val content = descriptor.render.declaringClass
        require(!Modifier.isStatic(descriptor.render.modifiers) && descriptor.render.name == "invoke" &&
            descriptor.render.returnType == Any::class.java && descriptor.render.parameterTypes.contentEquals(Array(3) { Any::class.java }))
        require(state.isInterface && mutable.isInterface && state.isAssignableFrom(mutable))
        require(descriptor.androidView == androidView(loader, modifier))
        val function1 = descriptor.androidView.parameterTypes[0]
        require(function1.isInterface)
        require(function1.methods.count { it.name == "invoke" && it.parameterTypes.contentEquals(arrayOf(Any::class.java)) &&
            it.returnType == Any::class.java } == 1)
        val read = state.getMethod("getValue").accessible().also { require(it.returnType == Any::class.java) }
        val write = mutable.getMethod("setValue", Any::class.java).accessible().also { require(it.returnType == Void.TYPE) }
        val captures = instanceFields(content)
        val stateFields = captures.filter { state.isAssignableFrom(it.type) }
        require(stateFields.size in 4..8)
        val toggle = stateFields.filter { mutable.isAssignableFrom(it.type) }.single()
        val viewModelBase = type("androidx.lifecycle.ViewModel", loader)
        val viewModel = captures.filter { viewModelBase.isAssignableFrom(it.type) }.single()
        val navigation = captures.filter { it.type.isInterface && it.type.isAssignableFrom(descriptor.back.declaringClass) }.single()
        require(descriptor.back.parameterCount == 0 && descriptor.back.returnType == Void.TYPE && !Modifier.isStatic(descriptor.back.modifiers))
        require(descriptor.back.declaringClass.declaredMethods.count {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == Void.TYPE
        } == 1)
        val fields = probeDisplayModels(descriptor.ready, descriptor.photos, type(LAT_LNG, loader)).toMutableMap()
        fields["toggle"] = toggle
        fields["navigation"] = navigation
        val companionType = type("androidx.compose.ui.Modifier\$Companion", loader)
        val companion = companionType.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type == companionType }
            .single().accessible().get(null)
        require(descriptor.fill.declaringClass.name == SIZE && Modifier.isStatic(descriptor.fill.modifiers) &&
            descriptor.fill.returnType == modifier && descriptor.fill.parameterTypes.contentEquals(arrayOf(modifier, java.lang.Float.TYPE)))
        val fill = requireNotNull(descriptor.fill.invoke(null, companion, 1f))
        require(modifier.isInstance(fill) && fill.javaClass.name == "androidx.compose.foundation.layout.FillElement")
        // A method with the same signature can be fillMaxWidth or fillMaxHeight.
        // The actual returned element must identify itself as fillMaxSize.
        require(instanceFields(fill.javaClass).filter { it.type == String::class.java }.single().get(fill) == "fillMaxSize")
        require(Modifier.isStatic(descriptor.unit.modifiers) && descriptor.unit.type == descriptor.unit.declaringClass)
        val unit = requireNotNull(descriptor.unit.get(null)).also { require(it.toString() == "kotlin.Unit") }
        val postId = descriptor.params?.let { params -> runCatching { postIdReader(params, viewModel) }.getOrNull() }
        val permission = PostMapPermissionActions.resolve(loader, versionName, versionCode, viewModel)
        if (postId == null) logger.accept("[PostMap] Post identity unavailable; display-state archival capture disabled")
        if (permission == null) logger.accept("[PostMap] Native permission action unavailable for this host; existing location permission remains usable")
        return PostMapSymbols(descriptor.render, descriptor.androidView, fill, unit, function1, read, write,
            descriptor.back, fields, permission ?: {}, stateFields, postId, permission != null)
    }

    /** Probe only fresh, disposable DTOs. No application-owned state or photo is modified. */
    internal fun probeDisplayModels(ready: Class<*>, photos: Class<*>, latLng: Class<*>): Map<String, Field> {
        require(ready.superclass == Any::class.java && photos.superclass == Any::class.java)
        val doubles = instanceFields(latLng).filter { it.type == java.lang.Double.TYPE }
        require(doubles.size == 2)
        val coordinateConstructor = latLng.getDeclaredConstructor(java.lang.Double.TYPE, java.lang.Double.TYPE).apply { isAccessible = true }
        val first = coordinateConstructor.newInstance(12.25, 34.5)
        val second = coordinateConstructor.newInstance(-23.75, -45.125)
        val latitude = doubles.single { it.getDouble(first) == 12.25 && it.getDouble(second) == -23.75 }
        val longitude = doubles.single { it.getDouble(first) == 34.5 && it.getDouble(second) == -45.125 }
        val readyFields = instanceFields(ready)
        require(readyFields.size == 3 && readyFields.count { it.type == latLng } == 2 &&
            readyFields.count { it.type == java.lang.Boolean.TYPE } == 1)
        val readyProbe = ready.getDeclaredConstructor(latLng, latLng, java.lang.Boolean.TYPE).apply { isAccessible = true }
            .newInstance(first, second, true)
        val post = fieldForLabel(readyProbe, readyFields.filter { it.type == latLng }, "Ready(markerLocation=", ", userLocation=")
        val user = fieldForLabel(readyProbe, readyFields.filter { it.type == latLng }, ", userLocation=", ", showCircle=")
        require(post != user && readyProbe.toString().endsWith(", showCircle=true)"))
        val approximate = readyFields.single { it.type == java.lang.Boolean.TYPE }
        require(approximate.getBoolean(readyProbe))
        val photoFields = instanceFields(photos)
        require(photoFields.size == 2 && photoFields.all { it.type == Bitmap::class.java })
        val lowProbe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val highProbe = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            require(lowProbe.toString() != highProbe.toString())
            val photoProbe = photos.getDeclaredConstructor(Bitmap::class.java, Bitmap::class.java).apply { isAccessible = true }
                .newInstance(lowProbe, highProbe)
            val low = fieldForLabel(photoProbe, photoFields, "BitmapReady(lowBitmap=", ", highBitmap=")
            val high = fieldForLabel(photoProbe, photoFields, ", highBitmap=", ")")
            require(low != high)
            return mapOf("post" to post, "user" to user, "approximate" to approximate, "low" to low, "high" to high,
                "latitude" to latitude, "longitude" to longitude)
        } finally {
            lowProbe.recycle()
            highProbe.recycle()
        }
    }

    private fun fieldForLabel(probe: Any, fields: List<Field>, label: String, end: String): Field {
        val text = probe.toString()
        require(text.indexOf(label) >= 0 && text.indexOf(label) == text.lastIndexOf(label))
        val begin = text.indexOf(label) + label.length
        val finish = if (end == ")") text.length - 1 else text.indexOf(end, begin)
        require(finish >= begin && (end != ")" || text.endsWith(end)))
        val value = text.substring(begin, finish)
        return fields.filter { it.get(probe)?.toString() == value }.single()
    }

    private fun postIdReader(params: Class<*>, viewModel: Field): (Any) -> String? {
        require(params.superclass == Any::class.java)
        val members = instanceFields(params)
        // MapParams is a data-only value. Distinct constructor strings let its
        // labels prove the post ID field independently of argument/field order.
        val constructor = params.declaredConstructors.filter { it.parameterCount == members.size && it.parameterCount in 4..20 }
            .single().apply { isAccessible = true }
        val arguments = constructor.parameterTypes.mapIndexed { index, type -> when {
            type == String::class.java -> "BeFuckMapProbe$index"
            type == java.lang.Boolean.TYPE -> false
            type == java.lang.Double.TYPE -> index.toDouble()
            type.isEnum -> type.enumConstants?.firstOrNull()
            else -> error("Unsupported MapParams constructor contract")
        } }.toTypedArray()
        val probe = constructor.newInstance(*arguments)
        val id = fieldForLabel(probe, members.filter { it.type == String::class.java }, ", postId=", ", momentId=")
        val readers = instanceFields(viewModel.type).mapNotNull { field ->
            val getter = field.type.methods.filter { it.name == "getValue" && it.parameterCount == 0 && it.returnType == Any::class.java }
                .distinctBy { it.toGenericString() }.singleOrNull()
            val initialized = field.type.methods.singleOrNull {
                it.name == "isInitialized" && it.parameterCount == 0 && it.returnType == java.lang.Boolean.TYPE
            }
            if (getter != null && initialized != null) Triple(field, getter.accessible(), initialized.accessible()) else null
        }
        require(readers.isNotEmpty() && readers.size <= 4)
        return { owner ->
            val vm = viewModel.get(owner)
            // The host initializes its navigation Lazy before it creates Ready.
            // Never force an unrelated Lazy or evaluate a route ahead of the host.
            val values = readers.mapNotNull { (field, getter, initialized) -> runCatching {
                val lazy = field.get(vm)
                if (lazy != null && initialized.invoke(lazy) == true) getter.invoke(lazy) else null
            }.getOrNull() }
                .filter(params::isInstance)
            values.singleOrNull()?.let { id.get(it) as? String }?.takeIf { it.isNotBlank() && it.length <= 512 }
        }
    }

    internal fun androidView(loader: ClassLoader, modifier: Class<*>): Method = type(ANDROID_VIEW, loader).declaredMethods.filter { method ->
        val p = method.parameterTypes
        Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE && p.size == 8 &&
            p[0].isInterface && p[1] == modifier && p[2] == p[0] && p[3] == p[0] && p[4] == p[0] &&
            p[5].name == COMPOSER && p[6] == Integer.TYPE && p[7] == Integer.TYPE
    }.single().accessible()

    private fun instanceFields(owner: Class<*>): List<Field> = owner.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.accessible() }
    private fun Field.accessible(): Field = apply { isAccessible = true }
    private fun Method.accessible(): Method = apply { isAccessible = true }
    private fun type(name: String, loader: ClassLoader): Class<*> = when (name) {
        "void" -> Void.TYPE
        "boolean" -> java.lang.Boolean.TYPE
        "int" -> Integer.TYPE
        "float" -> java.lang.Float.TYPE
        "double" -> java.lang.Double.TYPE
        else -> Class.forName(name, false, loader)
    }

    /** Only full member signatures and type names are persisted, never runtime values. */
    private data class Descriptor(
        val render: Method, val androidView: Method, val fill: Method, val unit: Field,
        val ready: Class<*>, val photos: Class<*>, val back: Method, val params: Class<*>?,
    ) {
        fun json(): JSONObject = JSONObject().put("render", methodJson(render)).put("androidView", methodJson(androidView))
            .put("fill", methodJson(fill)).put("back", methodJson(back))
            .put("unit", JSONObject().put("owner", unit.declaringClass.name).put("name", unit.name).put("type", unit.type.name))
            .put("ready", ready.name).put("photos", photos.name).put("params", params?.name ?: JSONObject.NULL)
        companion object {
            private fun methodJson(method: Method) = JSONObject().put("owner", method.declaringClass.name).put("name", method.name)
                .put("parameters", JSONArray(method.parameterTypes.map { it.name })).put("returns", method.returnType.name)
                .put("static", Modifier.isStatic(method.modifiers))
            fun read(json: JSONObject, loader: ClassLoader): Descriptor {
                fun method(key: String): Method {
                    val data = json.getJSONObject(key)
                    val names = data.getJSONArray("parameters")
                    require(names.length() <= 16)
                    return type(data.getString("owner"), loader).getDeclaredMethod(data.getString("name"),
                        *Array(names.length()) { type(names.getString(it), loader) }).accessible().also {
                        require(it.returnType.name == data.getString("returns") && Modifier.isStatic(it.modifiers) == data.getBoolean("static"))
                    }
                }
                val data = json.getJSONObject("unit")
                val unit = type(data.getString("owner"), loader).getDeclaredField(data.getString("name")).accessible()
                require(unit.type.name == data.getString("type"))
                return Descriptor(method("render"), method("androidView"), method("fill"), unit,
                    type(json.getString("ready"), loader), type(json.getString("photos"), loader), method("back"),
                    if (json.isNull("params")) null else type(json.getString("params"), loader))
            }
        }
    }
}
