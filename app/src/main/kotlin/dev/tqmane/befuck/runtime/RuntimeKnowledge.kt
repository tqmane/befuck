package dev.tqmane.befuck.runtime

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.tqmane.befuck.symbols.BeRealSymbolResolver
import dev.tqmane.befuck.symbols.KnownMappings3970
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.time.Instant
import java.util.concurrent.Executors

/** Version-scoped local knowledge. No media, auth headers, or arbitrary fallback strings. */
object RuntimeKnowledge {
    private const val TAG = "BeFuck/Recovery"
    private const val REPAIR = "repair:"
    private const val READERS = "reader:"
    private const val MANUAL = "manual:"
    private const val INFO = "repair_info:"
    private const val MAX_LOG_BYTES = 512L * 1024L
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "BeFuckRuntimeLog") }
    private val seenEvents = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val analyzing = ThreadLocal<Boolean>()
    private var context: Context? = null
    private var loader: ClassLoader? = null
    private var prefs: SharedPreferences? = null
    private var presetAssets: android.content.res.AssetManager? = null
    @get:JvmStatic var versionCode: Long = -1L
        private set
    @get:JvmStatic var versionName: String? = null
        private set

    @JvmStatic
    @Synchronized
    fun initialize(host: Context, classLoader: ClassLoader, name: String?, code: Long) {
        require(code >= 0L) { "Cannot scope runtime knowledge without a version code" }
        context = host.applicationContext ?: host
        loader = classLoader
        versionName = name
        versionCode = code
        val settings = host.getSharedPreferences("befuck_runtime", Context.MODE_PRIVATE)
        prefs = settings
        val previous = settings.getLong("version_code", -1L)
        if (previous != code) {
            val edit = settings.edit()
            settings.all.keys.filter { it.startsWith(REPAIR) || it.startsWith(READERS) || it.startsWith(MANUAL) || it.startsWith(INFO) || it == "symbol_json" }
                .forEach(edit::remove)
            edit.putLong("version_code", code).putString("version_name", name).commit()
            Log.i(TAG, "Invalidated runtime caches for versionCode=$previous -> $code")
            event("version_changed", JSONObject().put("previous", previous).put("current", code))
        }
        backfillRepairInfo()
        applySavedRepairs()
        KnownMappings3970.missingStringRepairs(classLoader, name).forEach { (field, value) ->
            rememberRepair(field, value, "preset:${KnownMappings3970.VERSION_CODE}")
            if (field.get(null) == null) field.set(null, value)
        }
    }

    fun settings(): SharedPreferences = requireNotNull(prefs) { "Runtime settings are unavailable" }
    @JvmStatic fun enabled(key: String): Boolean = prefs?.getBoolean(key, true) ?: true
    fun setEnabled(key: String, enabled: Boolean) { settings().edit().putBoolean(key, enabled).apply() }
    fun logFile(): File? = context?.let { File(it.filesDir, "befuck/runtime/null-$versionCode.jsonl") }

    fun loadSymbols(): String? = if (settings().getBoolean("force_scan", false)) null else settings().getString("symbol_json", null)
    fun saveSymbols(json: String) { settings().edit().putString("symbol_json", json).remove("force_scan").commit() }
    fun shouldUsePreset(): Boolean = enabled("prefer_preset") && !settings().getBoolean("force_scan", false)
    @JvmStatic fun setPresetAssets(assets: android.content.res.AssetManager?) { presetAssets = assets }
    fun preset(): String? = presetAssets?.let { assets ->
        runCatching { assets.open("befuck/symbols-$versionCode.json").bufferedReader().use { it.readText() } }.getOrNull()
    }

    fun clearSymbols() {
        val edit = settings().edit().remove("symbol_json").putBoolean("force_scan", true)
        settings().all.keys.filter { it.startsWith(READERS) }.forEach(edit::remove)
        edit.commit()
        BeRealSymbolResolver.clearCache()
        event("symbols_cleared", JSONObject())
    }

    fun clearRepairs() {
        val edit = settings().edit()
        settings().all.keys.filter { it.startsWith(REPAIR) || it.startsWith(MANUAL) || it.startsWith(INFO) }.forEach(edit::remove)
        edit.commit()
        event("repairs_cleared", JSONObject())
    }

    fun clearPreferences() {
        settings().edit().clear().putLong("version_code", versionCode).putString("version_name", versionName)
            .putBoolean("force_scan", true).commit()
        BeRealSymbolResolver.clearCache()
        event("preferences_cleared", JSONObject())
    }

    fun repairs(): Map<String, String> = settings().all.filterKeys { it.startsWith(REPAIR) }
        .mapKeys { it.key.removePrefix(REPAIR) }.mapValues { it.value as String }

    data class RepairRule(val key: String, val value: String, val source: String, val reason: String, val updatedAt: String?)

    fun repairRules(): List<RepairRule> = repairs().map { (key, value) ->
        val info = settings().getString(INFO + key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        RepairRule(key, value, if (settings().getBoolean(MANUAL + key, false)) "manual" else info?.optString("source", "saved") ?: "saved",
            info?.optString("reason").orEmpty(), info?.optString("at")?.takeIf { it.isNotBlank() })
    }.sortedWith(compareByDescending<RepairRule> { it.updatedAt.orEmpty() }.thenBy { it.key })

    private fun repairInfo(reason: String, at: String): String = JSONObject()
        .put("source", when { reason == "settings" -> "manual"; reason.startsWith("DexKit:") -> "learned"; else -> "preset" })
        .put("reason", reason).put("at", at).toString()

    /** Migrate existing rules from their JSONL records when provenance was not stored yet. */
    private fun backfillRepairInfo() {
        val missing = repairs().filterKeys { !settings().contains(INFO + it) }
        if (missing.isEmpty()) return
        val edit = settings().edit()
        logFile()?.takeIf { it.isFile }?.useLines { lines ->
            lines.forEach { line ->
                runCatching { JSONObject(line) }.getOrNull()?.takeIf {
                    it.optString("event") == "repair_learned" && it.optLong("versionCode") == versionCode
                }?.let { record ->
                    val details = record.optJSONObject("details") ?: return@let
                    val key = details.optString("field")
                    if (missing[key] == details.optString("value")) {
                        edit.putString(INFO + key, repairInfo(details.optString("reason"), record.optString("at")))
                    }
                }
            }
        }
        edit.commit()
    }

    @JvmStatic
    fun rememberRepair(field: Field, value: String, reason: String) {
        if (prefs == null || !Modifier.isStatic(field.modifiers) || field.type != String::class.java) return
        val key = "${field.declaringClass.name}#${field.name}"
        if (reason != "settings" && settings().getBoolean(MANUAL + key, false)) return
        if (settings().getString(REPAIR + key, null) != value || !settings().contains(INFO + key) || reason == "settings") {
            settings().edit().putString(REPAIR + key, value)
                .putString(INFO + key, repairInfo(reason, Instant.now().toString())).commit()
            event("repair_learned", JSONObject().put("field", key).put("value", value).put("reason", reason))
        }
    }

    fun registerRepair(owner: String, name: String, value: String) {
        require(owner.isNotBlank() && name.isNotBlank() && value.isNotEmpty())
        val field = Class.forName(owner, false, requireNotNull(loader)).getDeclaredField(name).apply { isAccessible = true }
        require(Modifier.isStatic(field.modifiers) && !Modifier.isFinal(field.modifiers) && field.type == String::class.java) { "Only mutable static String fields can be repaired" }
        // Saving an explicit manual edit updates the current field as well as the next-start rule.
        field.set(null, value)
        rememberRepair(field, value, "settings")
        settings().edit().putBoolean(MANUAL + "$owner#$name", true).commit()
        event("repair_registered", JSONObject().put("field", "$owner#$name"))
    }

    @JvmStatic
    fun applySavedRepairs(): Int {
        if (prefs == null || !enabled("auto_repair")) return 0
        var count = 0
        repairs().forEach { (key, value) ->
            runCatching {
                val field = field(key)
                if (field.get(null) == null) { field.set(null, value); count++ }
            }.onFailure { event("repair_unresolved", JSONObject().put("field", key).put("error", it.javaClass.simpleName)) }
        }
        if (count > 0) Log.i(TAG, "Restored $count saved null String fields for versionCode=$versionCode")
        return count
    }

    /** Invoked only at a null argument; known values or a unique APK analytics key are required. */
    @JvmStatic
    @JvmOverloads
    fun recoverArgument(operation: String, origin: Array<StackTraceElement>? = null): String? {
        if (prefs == null || analyzing.get() == true) return null
        analyzing.set(true)
        try {
            val stack = (origin ?: Throwable().stackTrace).filter {
                !it.className.startsWith("dev.tqmane.") && !it.className.startsWith("java.") &&
                    !it.className.startsWith("android.") && !it.className.contains("HookBridge")
            }.take(12)
            var chosen: String? = null
            val nullFields = linkedMapOf<String, Field>()
            for (frame in stack) {
                readerFields(frame).forEach { key ->
                    runCatching { field(key) }.getOrNull()?.takeIf { runCatching { it.get(null) == null }.getOrDefault(false) }
                        ?.let { nullFields[key] = it }
                }
                val known = nullFields.mapNotNull { (key, field) ->
                    settings().getString(REPAIR + key, null)
                        ?.takeIf { operation != "binder_descriptor" || RepairInference.isBinderDescriptor(it) }
                        ?.let { field to it }
                }
                if (known.size == 1) { chosen = known.single().second; break }
                if (operation == "analytics_key" && nullFields.size == 1) {
                    inferAnalyticsKey(frame)?.let { inferred ->
                        rememberRepair(nullFields.values.single(), inferred, "DexKit: unique missing toString/constructor key")
                        chosen = inferred
                    }
                    if (chosen != null) break
                }
            }
            event("null_argument", JSONObject().put("operation", operation).put("fields", JSONArray(nullFields.keys.toList()))
                .put("stack", JSONArray(stack.map { "${it.className}#${it.methodName}" })).put("repairable", chosen != null)
                .put("applied", chosen != null && enabled("auto_repair")))
            if (!enabled("auto_repair")) return null
            chosen?.let { value ->
                nullFields.forEach { (key, field) ->
                    if (settings().getString(REPAIR + key, null) == value) field.set(null, value)
                }
                Log.i(TAG, "Recovered $operation without restarting the app")
            }
            return chosen
        } catch (failure: Throwable) {
            event("recovery_failed", JSONObject().put("operation", operation).put("error", failure.javaClass.simpleName))
            return null
        } finally { analyzing.remove() }
    }

    private fun field(key: String): Field {
        val owner = key.substringBeforeLast('#')
        val name = key.substringAfterLast('#')
        val field = Class.forName(owner, false, requireNotNull(loader)).getDeclaredField(name).apply { isAccessible = true }
        require(Modifier.isStatic(field.modifiers) && field.type == String::class.java)
        return field
    }

    private fun readerFields(frame: StackTraceElement): List<String> {
        val key = READERS + frame.className + "#" + frame.methodName
        settings().getString(key, null)?.let { cached ->
            val values = JSONArray(cached)
            return (0 until values.length()).map(values::getString)
        }
        val host = context ?: return emptyList()
        val readers = BeRealSymbolResolver.openDexKit(host.applicationInfo.sourceDir).use { bridge ->
            bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().declaredClass(frame.className).name(frame.methodName)))
                .flatMap { it.usingFields }.filter {
                    it.usingType.isRead() && Modifier.isStatic(it.field.modifiers) && it.field.typeName == String::class.java.name
                }.map { "${it.field.declaredClassName}#${it.field.name}" }.distinct()
        }
        settings().edit().putString(key, JSONArray(readers).toString()).apply()
        return readers
    }

    private fun inferAnalyticsKey(frame: StackTraceElement): String? {
        if (frame.methodName != "<init>") return null
        val host = context ?: return null
        return BeRealSymbolResolver.openDexKit(host.applicationInfo.sourceDir).use { bridge ->
            val methods = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().declaredClass(frame.className)))
            RepairInference.uniqueMissingKey(methods.filter { it.name == "<init>" }.flatMap { it.usingStrings },
                methods.filter { it.name == "toString" }.flatMap { it.usingStrings }, 1)
        }
    }

    /** A local constructor check only: nothing is posted or sent to analytics. */
    fun verifyRecovery(): Boolean {
        require(KnownMappings3970.isKnownVersion(versionName) && enabled("auto_repair"))
        val key = "androidx.credentials.gZ.MqonvtnPZU#rjWJOvNnlBpCE"
        val field = field(key)
        val original = field.get(null)
        val saved = settings().getString(REPAIR + key, null)
        val savedInfo = settings().getString(INFO + key, null)
        val savedManual = settings().getBoolean(MANUAL + key, false)
        val nativeLoadedBefore = BeRealSymbolResolver.isDexKitLoaded()
        val readersCached = settings().contains(READERS + "w6#<init>")
        settings().edit().remove(REPAIR + key).remove(INFO + key).remove(MANUAL + key).commit()
        try {
            field.set(null, null)
            val eventType = Class.forName("w6", false, requireNotNull(loader))
            eventType.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .newInstance("befuck-recovery-check", 0, 0, 0, false)
            val analyticsRepaired = field.get(null) == "recursionDepth" && settings().getString(REPAIR + key, null) == "recursionDepth"
            val binderPassed = verifyBinderRecovery()
            val repaired = analyticsRepaired && binderPassed
            event("self_check", JSONObject().put("passed", repaired).put("field", key)
                .put("nativeLoadedBefore", nativeLoadedBefore).put("readersCached", readersCached).put("binderPassed", binderPassed))
            return repaired
        } finally {
            field.set(null, original)
            if (saved != null) settings().edit().putString(REPAIR + key, saved).putString(INFO + key, savedInfo)
                .putBoolean(MANUAL + key, savedManual).commit()
        }
    }

    /** Exercise the null at Binder.attachInterface, while preserving Parcel's token enforcement. */
    private fun verifyBinderRecovery(): Boolean {
        val classLoader = requireNotNull(loader)
        val descriptorField = requireNotNull(KnownMappings3970.resolveStringField(classLoader, versionName, "mapsCameraIdleDescriptor"))
        val original = descriptorField.get(null)
        try {
            descriptorField.set(null, null)
            val callback = KnownMappings3970.newCameraIdleCallback(classLoader, versionName)
            val descriptor = KnownMappings3970.MAPS_CAMERA_IDLE_DESCRIPTOR
            check(callback.interfaceDescriptor == descriptor)
            val transact = callback.javaClass.getMethod("onTransact", Int::class.javaPrimitiveType,
                android.os.Parcel::class.java, android.os.Parcel::class.java, Int::class.javaPrimitiveType)
            fun invokeToken(token: String) {
                val data = android.os.Parcel.obtain()
                val reply = android.os.Parcel.obtain()
                try {
                    data.writeInterfaceToken(token)
                    data.setDataPosition(0)
                    // Only code 1 invokes the camera callback; code 2 validates the token and returns false.
                    transact.invoke(callback, 2, data, reply, 0)
                } finally { data.recycle(); reply.recycle() }
            }
            invokeToken(descriptor)
            val rejected = runCatching { invokeToken("befuck.invalid.descriptor") }.exceptionOrNull()
            check((rejected as? java.lang.reflect.InvocationTargetException)?.cause is SecurityException)
            return true
        } finally { descriptorField.set(null, original) }
    }

    @JvmStatic
    fun recordFailure(failure: Throwable) {
        if (prefs == null) return
        val trace = Log.getStackTraceString(failure).replace(Regex("https?://[^\\s)]+"), "<url>")
            .replace(Regex("(?i)Bearer [A-Za-z0-9._~-]+"), "Bearer <redacted>").take(8192)
        event("exception", JSONObject().put("type", failure.javaClass.name).put("stack", trace))
        if (failure is NullPointerException) recoverArgument("uncaught_null", failure.stackTrace)
    }

    @JvmStatic
    fun flushDiagnostics() {
        if (Thread.currentThread().name == "BeFuckRuntimeLog") return
        runCatching { writer.submit {}.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    @JvmStatic
    fun event(kind: String, details: JSONObject) {
        if (!enabled("diagnostics")) return
        val target = logFile() ?: return
        val fingerprint = "$versionCode|$kind|$details"
        if (kind != "self_check" && !seenEvents.add(fingerprint)) return
        val line = JSONObject().put("at", Instant.now().toString()).put("versionCode", versionCode)
            .put("versionName", versionName).put("event", kind).put("details", details).toString()
        writer.execute {
            runCatching {
                target.parentFile?.mkdirs()
                if (target.length() > MAX_LOG_BYTES) {
                    val archive = File(target.parentFile, target.nameWithoutExtension + "-previous.jsonl")
                    archive.delete()
                    target.renameTo(archive)
                }
                target.appendText(line + "\n", Charsets.UTF_8)
            }.onFailure { Log.w(TAG, "Could not append the runtime JSONL", it) }
        }
    }
}
