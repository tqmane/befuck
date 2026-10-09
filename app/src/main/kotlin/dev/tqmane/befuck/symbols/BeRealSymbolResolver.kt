package dev.tqmane.befuck.symbols

import android.content.pm.ApplicationInfo
import android.util.Log
import dev.tqmane.befuck.runtime.RuntimeKnowledge
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.FieldMatcher
import org.luckypray.dexkit.query.matchers.FieldsMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.matchers.MethodsMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.io.File
import java.util.ArrayList
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/** Resolves stable behavioral anchors once per APK identity, then caches reflection objects per process. */
object BeRealSymbolResolver {
    private const val TAG = "BeFuck/Resolver"
    private val results = ConcurrentHashMap<String, ResolvedSymbols>()
    @Volatile private var nativeLoaded = false

    private data class PostPipelineSymbols(
        val domainClass: Class<*>? = null,
        val domainConstructor: Constructor<*>? = null,
        val domainFields: List<Field> = emptyList(),
        val contentsClass: Class<*>? = null,
        val contentsConstructor: Constructor<*>? = null,
        val contentsFields: List<Field> = emptyList(),
        val mediaClass: Class<*>? = null,
        val mediaConstructor: Constructor<*>? = null,
        val coreDraftClass: Class<*>? = null,
        val coreDraftConstructor: Constructor<*>? = null,
        val sendPostCoroutineConstructor: Constructor<*>? = null,
        val repositoryInterface: Class<*>? = null,
        val uploadWorkerClass: Class<*>? = null,
        val sendDraftMethod: Method? = null,
        val friendsVisibility: Any? = null,
        val friendOfFriendsVisibility: Any? = null,
        val globalVisibility: Any? = null,
        val currentUserProviderClass: Class<*>? = null,
        val currentUserProviderMethod: Method? = null,
        val currentUserUidField: Field? = null,
    )

    @JvmStatic
    fun resolve(
        applicationInfo: ApplicationInfo,
        classLoader: ClassLoader,
        versionName: String?,
        versionCode: Long,
        logger: Consumer<String>,
    ): ResolvedSymbols {
        val apk = File(applicationInfo.sourceDir)
        val identity = listOf(
            applicationInfo.packageName,
            versionCode.toString(),
            versionName ?: "unknown",
            apk.absolutePath,
            apk.length().toString(),
            apk.lastModified().toString(),
        ).joinToString("|")
        results[identity]?.let {
            emit(logger, "[SymbolResolver] Cache hit package=${applicationInfo.packageName} version=$versionName identity=${apk.name}")
            return it
        }

        synchronized(results) {
            results[identity]?.let { return it }
            val fromPreset = RuntimeKnowledge.loadSymbols() == null
            val stored = RuntimeKnowledge.loadSymbols()
                ?: RuntimeKnowledge.preset().takeIf { RuntimeKnowledge.shouldUsePreset() }
            if (stored != null) {
                try {
                    val saved = JSONObject(stored)
                    require(saved.getInt("schema") == SymbolCacheCodec.SCHEMA && saved.getLong("version_code") == versionCode &&
                        saved.getString("package") == applicationInfo.packageName)
                    val resolved = SymbolCacheCodec.decode(saved.getJSONObject("symbols"), classLoader, identity)
                    results[identity] = resolved
                    RuntimeKnowledge.saveSymbols(stored)
                    emit(logger, "[SymbolResolver] Restored ${resolved.resolvedStringSymbols().size} string symbols and native member signatures from ${if (fromPreset) "bundled preset" else "SharedPreferences"}; versionCode=$versionCode; DexKit scan skipped")
                    return resolved
                } catch (failure: Throwable) {
                    RuntimeKnowledge.clearSymbols()
                    emit(logger, "[SymbolResolver] Stored signatures are invalid; rescanning with DexKit: ${failure.javaClass.simpleName}")
                }
            }
            val diagnostics = mutableListOf<String>()
            val fields = linkedMapOf<String, Field>()
            var cameraCountdownComposable: Method? = null
            var feedMediaSymbols: FeedMediaSymbols? = null
            var timelineBlurredCardComposable: Method? = null
            var pullDownGridCardComposable: Method? = null
            var pullDownGridMediaComposable: Method? = null
            var homeGridPostTileComposable: Method? = null
            var homeFeedCanBlurMapper: Method? = null
            var homeFeedItemEmitter: Method? = null
            var friendsOfFriendsFeedItemEmitter: Method? = null
            var cameraViewModel: Class<*>? = null
            var cameraFacing: Class<*>? = null
            var cameraBind: Method? = null
            var cameraRouteParsers: List<Method> = emptyList()
            var locationRepository: Class<*>? = null
            var locationRequest: Method? = null
            var locationClientGetter: Method? = null
            var postPipeline = PostPipelineSymbols()
            var dexKitAvailable = false
            var bridge: DexKitBridge? = null

            try {
                bridge = openDexKit(apk.absolutePath)
                bridge.setThreadNum(2)
                dexKitAvailable = true
                val dexCount = bridge.getDexNum()
                emit(logger, "[SymbolResolver] Opened $dexCount dex files for version=$versionName")
                if (KnownMappings3971.isKnownVersion(versionName, versionCode) &&
                    dexCount != KnownMappings3971.EXPECTED_DEX_COUNT
                ) {
                    val message = "3.97.1 DEX inventory mismatch: expected=${KnownMappings3971.EXPECTED_DEX_COUNT} actual=$dexCount; revalidate all symbol resolutions"
                    diagnostics += message
                    emit(logger, "[SymbolResolver] $message")
                }

                resolveRecapSemanticsField(bridge, classLoader, logger, diagnostics)
                    ?.let { fields["recapSemantics"] = it }
                val cameraCountdown = resolveCameraCountdown(bridge, classLoader, logger, diagnostics)
                cameraCountdownComposable = cameraCountdown.first
                cameraCountdown.second?.let { fields["cameraCountdownInitialText"] = it }
                resolveReaderAnchoredField(
                    bridge,
                    classLoader,
                    "crashlyticsFidPrefix",
                    logger,
                    diagnostics,
                    MethodMatcher.create().usingEqStrings(
                        "Determining Crashlytics installation ID...",
                        "Cached Firebase Installation ID: ",
                        "Install IDs: ",
                    ),
                )?.let { fields["crashlyticsFidPrefix"] = it }
                resolveReaderAnchoredField(
                    bridge,
                    classLoader,
                    "crashlyticsProcessName",
                    logger,
                    diagnostics,
                    MethodMatcher.create().name("<clinit>").usingEqStrings("pid", "importance", "defaultProcess"),
                )?.let { fields["crashlyticsProcessName"] = it }
                resolveReaderAnchoredField(
                    bridge,
                    classLoader,
                    "glanceCharset",
                    logger,
                    diagnostics,
                    MethodMatcher.create()
                        .declaredClass("androidx.glance.appwidget.protobuf.Internal")
                        .name("<clinit>"),
                )?.let { fields["glanceCharset"] = it }
                resolveReaderAnchoredField(
                    bridge,
                    classLoader,
                    "androidIdSettingName",
                    logger,
                    diagnostics,
                    MethodMatcher.create()
                        .declaredClass("com.google.android.gms.internal.consent_sdk.zzct")
                        .usingEqStrings("emulator"),
                    excludeDeclaredClass = "com.google.android.gms.internal.consent_sdk.zzct",
                )?.let { fields["androidIdSettingName"] = it }
                resolveReaderAnchoredField(
                    bridge,
                    classLoader,
                    "ktorDefaultUserAgent",
                    logger,
                    diagnostics,
                    MethodMatcher.create()
                        .returnType("void")
                        .paramCount(3)
                        .usingEqStrings("User-Agent", "Content-Type", "Content-Length"),
                )?.let { fields["ktorDefaultUserAgent"] = it }
                resolveReaderAnchoredField(
                    bridge,
                    classLoader,
                    "sourcepointUsNatSampleRate",
                    logger,
                    diagnostics,
                    MethodMatcher.create()
                        .declaredClass("com.sourcepoint.mobile_core.models.consents.State\$USNatState\$UsNatMetaData\$\$serializer")
                        .name("<clinit>"),
                )?.let { fields["sourcepointUsNatSampleRate"] = it }

                cameraFacing = resolveCameraFacingEnum(bridge, classLoader, logger, diagnostics)
                cameraViewModel = resolveCameraViewModel(bridge, classLoader, cameraFacing, logger, diagnostics)
                cameraBind = resolveCameraBindMethod(bridge, classLoader, cameraViewModel, cameraFacing, logger, diagnostics)
                cameraRouteParsers = resolveCameraOriginParsers(bridge, classLoader, logger, diagnostics)

                val locationPair = resolveLocationRepository(bridge, classLoader, logger, diagnostics)
                locationRepository = locationPair.first
                locationRequest = locationPair.second
                locationClientGetter = resolveLocationClientGetter(bridge, classLoader, locationRepository, logger, diagnostics)

                postPipeline = resolvePostPipeline(bridge, classLoader, logger, diagnostics)
                feedMediaSymbols = FeedMediaSymbolResolver.resolve(bridge, classLoader, logger, diagnostics)
            } catch (failure: Throwable) {
                diagnostics += "DexKit initialization/query failed: ${failure.javaClass.simpleName}: ${failure.message}"
                emit(logger, "[SymbolResolver] DexKit unavailable: ${failure.javaClass.simpleName}: ${failure.message}")
            } finally {
                try {
                    bridge?.close()
                } catch (failure: Throwable) {
                    emit(logger, "[SymbolResolver] DexKit close failed: ${failure.javaClass.simpleName}")
                }
            }

            val useKnownFallback = KnownMappings3970.isKnownVersion(versionName)
            if (useKnownFallback) {
                fallbackField(fields, classLoader, versionName, "recapSemantics", logger)
                fallbackField(fields, classLoader, versionName, "cameraCountdownInitialText", logger)
                fallbackField(fields, classLoader, versionName, "crashlyticsFidPrefix", logger)
                fallbackField(fields, classLoader, versionName, "glanceCharset", logger)
                fallbackField(fields, classLoader, versionName, "androidIdSettingName", logger)
                fallbackField(fields, classLoader, versionName, "crashlyticsProcessName", logger)
                fallbackField(fields, classLoader, versionName, "ktorDefaultUserAgent", logger)
                fallbackField(fields, classLoader, versionName, "sourcepointUsNatSampleRate", logger)

                cameraCountdownComposable = cameraCountdownComposable ?: fallbackMethod(
                    "cameraCountdownComposable",
                    versionName,
                    KnownMappings3970.resolveCameraCountdownComposable(classLoader, versionName),
                    logger,
                )
                timelineBlurredCardComposable = fallbackMethod(
                    "timelineBlurredCardComposable",
                    versionName,
                    KnownMappings3970.resolveTimelineBlurredCardComposable(classLoader, versionName),
                    logger,
                )
                pullDownGridCardComposable = fallbackMethod(
                    "pullDownGridCardComposable",
                    versionName,
                    KnownMappings3970.resolvePullDownGridCardComposable(classLoader, versionName),
                    logger,
                )
                pullDownGridMediaComposable = fallbackMethod(
                    "pullDownGridMediaComposable",
                    versionName,
                    KnownMappings3970.resolvePullDownGridMediaComposable(classLoader, versionName),
                    logger,
                )
                homeGridPostTileComposable = fallbackMethod(
                    "homeGridPostTileComposable",
                    versionName,
                    KnownMappings3970.resolveHomeGridPostTileComposable(classLoader, versionName),
                    logger,
                )
                homeFeedCanBlurMapper = fallbackMethod(
                    "homeFeedCanBlurMapper",
                    versionName,
                    KnownMappings3970.resolveHomeFeedCanBlurMapper(classLoader, versionName),
                    logger,
                )
                homeFeedItemEmitter = fallbackMethod(
                    "homeFeedItemEmitter",
                    versionName,
                    KnownMappings3970.resolveHomeFeedItemEmitter(classLoader, versionName),
                    logger,
                )
                friendsOfFriendsFeedItemEmitter = fallbackMethod(
                    "friendsOfFriendsFeedItemEmitter",
                    versionName,
                    KnownMappings3970.resolveFriendsOfFriendsFeedItemEmitter(classLoader, versionName),
                    logger,
                )

                cameraFacing = cameraFacing ?: fallbackClass(
                    "cameraFacing", versionName, KnownMappings3970.resolveCameraFacingEnum(classLoader, versionName), logger
                )
                cameraViewModel = cameraViewModel ?: fallbackClass(
                    "cameraViewModel", versionName, KnownMappings3970.resolveCameraViewModel(classLoader, versionName), logger
                )
                cameraBind = cameraBind ?: fallbackMethod(
                    "cameraBindConcurrent",
                    versionName,
                    KnownMappings3970.resolveCameraBindMethod(classLoader, versionName, cameraViewModel, cameraFacing),
                    logger,
                )
                if (cameraRouteParsers.isEmpty()) {
                    cameraRouteParsers = KnownMappings3970.resolveCameraOriginParsers(classLoader, versionName)
                    if (cameraRouteParsers.isNotEmpty()) {
                        emit(logger, "[SymbolResolver] cameraOriginParsers resolved: ${cameraRouteParsers.map { it.declaringClass.name + "." + it.name }} confidence=version-mapped strategy=KnownMappings3970")
                    } else {
                        emit(logger, "[SymbolResolver] Failed to resolve cameraOriginParsers reason=no valid parser set")
                    }
                }
                locationRepository = locationRepository ?: fallbackClass(
                    "locationRepository", versionName,
                    KnownMappings3970.resolveLocationRepository(classLoader, versionName), logger
                )
                locationRequest = locationRequest ?: fallbackMethod(
                    "locationRequest", versionName,
                    KnownMappings3970.resolveLocationRequestMethod(classLoader, versionName, locationRepository), logger
                )
                locationClientGetter = locationClientGetter ?: fallbackMethod(
                    "locationClientGetter", versionName,
                    KnownMappings3970.resolveLocationClientGetter(versionName, locationRepository), logger
                )
            }

            val resolved = ResolvedSymbols(
                applicationInfo.packageName,
                versionName,
                identity,
                dexKitAvailable,
                fields.toMap(),
                cameraCountdownComposable,
                feedMediaSymbols,
                timelineBlurredCardComposable,
                pullDownGridCardComposable,
                pullDownGridMediaComposable,
                homeGridPostTileComposable,
                homeFeedCanBlurMapper,
                homeFeedItemEmitter,
                friendsOfFriendsFeedItemEmitter,
                postPipeline.domainClass,
                postPipeline.domainConstructor,
                postPipeline.domainFields,
                postPipeline.contentsClass,
                postPipeline.contentsConstructor,
                postPipeline.contentsFields,
                postPipeline.mediaClass,
                postPipeline.mediaConstructor,
                postPipeline.coreDraftClass,
                postPipeline.coreDraftConstructor,
                postPipeline.sendPostCoroutineConstructor,
                postPipeline.repositoryInterface,
                postPipeline.uploadWorkerClass,
                postPipeline.sendDraftMethod,
                postPipeline.friendsVisibility,
                postPipeline.friendOfFriendsVisibility,
                postPipeline.globalVisibility,
                postPipeline.currentUserProviderClass,
                postPipeline.currentUserProviderMethod,
                postPipeline.currentUserUidField,
                cameraViewModel,
                cameraFacing,
                cameraBind,
                cameraRouteParsers,
                locationRepository,
                locationRequest,
                locationClientGetter,
                diagnostics.toList(),
            )
            results[identity] = resolved
            runCatching {
                val saved = JSONObject().put("schema", SymbolCacheCodec.SCHEMA).put("version_code", versionCode)
                    .put("package", applicationInfo.packageName).put("symbols", SymbolCacheCodec.encode(resolved))
                RuntimeKnowledge.saveSymbols(saved.toString())
                emit(logger, "[SymbolResolver] Persisted native member signatures for versionCode=$versionCode")
            }.onFailure { emit(logger, "[SymbolResolver] Could not persist symbols: ${it.javaClass.simpleName}: ${it.message}") }
            emit(logger, "[SymbolResolver] complete version=$versionName dexKit=$dexKitAvailable fields=${fields.keys} camera=${cameraViewModel?.name ?: "disabled"} location=${locationRepository?.name ?: "disabled"}")
            return resolved
        }
    }

    @JvmStatic fun clearCache() { results.clear() }
    internal fun isDexKitLoaded(): Boolean = nativeLoaded
    /** Opening a bridge must also work after symbols and reader fields were restored from cache. */
    @JvmStatic fun openDexKit(apkPath: String): DexKitBridge {
        loadNativeLibrary()
        return DexKitBridge.create(apkPath)
    }

    private fun loadNativeLibrary() {
        if (nativeLoaded) return
        synchronized(this) {
            if (!nativeLoaded) {
                System.loadLibrary("dexkit")
                nativeLoaded = true
            }
        }
    }

    private fun resolveReaderAnchoredField(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        symbol: String,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
        reader: MethodMatcher,
        excludeDeclaredClass: String? = null,
    ): Field? {
        val candidates = try {
            val readers = MethodsMatcher.create().add(reader)
            val fieldMatcher = FieldMatcher.create()
                .type(String::class.java)
                .modifiers(Modifier.STATIC)
                .readMethods(readers)
            if (excludeDeclaredClass != null) {
                fieldMatcher.not(FieldMatcher.create().declaredClass(excludeDeclaredClass))
            }
            bridge.findField(
                FindField.create().matcher(fieldMatcher),
            )
        } catch (_: Throwable) {
            return null
        }
        return uniqueField(candidates.toList(), classLoader, symbol, logger, diagnostics)
    }

    private fun resolveRecapSemanticsField(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Field? {
        return try {
            val tagReaders = bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .returnType("void")
                        .usingEqStrings("allrecapscreen_recap"),
                ),
            )
            val fields = tagReaders
                .flatMap { it.callers }
                .filter { caller ->
                    val params = caller.paramTypeNames
                    params.contains("androidx.compose.ui.Modifier") &&
                        params.contains("androidx.compose.runtime.Composer")
                }
                .flatMap { it.usingFields }
                .map { it.field }
                .filter { it.typeName == String::class.java.name && Modifier.isStatic(it.modifiers) }
                .distinctBy { it.descriptor }
            uniqueField(fields, classLoader, "recapSemantics", logger, diagnostics)
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveCameraCountdown(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Pair<Method?, Field?> {
        return try {
            val androidViewInvoke = MethodMatcher.create()
                .declaredClass("androidx.compose.ui.viewinterop.AndroidView_androidKt")
            val methodMatcher = MethodMatcher.create()
                .modifiers(Modifier.STATIC)
                .returnType("void")
                .paramTypes(
                    "float",
                    "int",
                    "long",
                    "androidx.compose.runtime.Composer",
                    "androidx.compose.ui.Modifier",
                    "java.lang.String",
                )
                .invokeMethods(MethodsMatcher.create().add(androidViewInvoke).matchType(MatchType.Contains))
            val methods = bridge.findMethod(FindMethod.create().matcher(methodMatcher))
                .distinctBy { it.descriptor }
            if (methods.size != 1) {
                val detail = "cameraCountdownComposable candidateCount=${methods.size} candidates=${methods.map { it.descriptor }}"
                diagnostics += detail
                emit(logger, "[SymbolResolver] $detail")
                return null to null
            }
            val methodData = methods.single()
            val method = methodData.getMethodInstance(classLoader).apply { isAccessible = true }
            val fields = methodData.usingFields
                .map { it.field }
                .filter { it.typeName == String::class.java.name && Modifier.isStatic(it.modifiers) }
                .distinctBy { it.descriptor }
            val field = uniqueField(fields, classLoader, "cameraCountdownInitialText", logger, diagnostics)
            if (field == null) {
                emit(logger, "[SymbolResolver] Failed to resolve cameraCountdownInitialText reason=no unique static String field read by the countdown AndroidView composable")
            } else {
                emit(logger, "[SymbolResolver] cameraCountdownComposable resolved: ${method.declaringClass.name}.${method.name}${methodData.descriptor} confidence=high strategy=DexKit(static+signature+AndroidView call)")
            }
            method to field
        } catch (_: Throwable) {
            null to null
        }
    }

    private fun uniqueField(
        candidates: List<FieldData>,
        classLoader: ClassLoader,
        symbol: String,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Field? {
        val unique = candidates.distinctBy { it.descriptor }
        if (unique.size != 1) {
            val detail = "$symbol candidateCount=${unique.size} candidates=${unique.map { it.descriptor }}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return null
        }
        return try {
            unique.single().getFieldInstance(classLoader).apply { isAccessible = true }
                .also { emit(logger, "[SymbolResolver] $symbol resolved: ${it.declaringClass.name}.${it.name} confidence=high strategy=DexKit(field-reader structure)") }
        } catch (_: Throwable) {
            null
        }
    }

    private fun fallbackClass(
        symbol: String,
        versionName: String?,
        candidate: Class<*>?,
        logger: Consumer<String>,
    ): Class<*>? {
        if (candidate != null) {
            emit(logger, "[SymbolResolver] $symbol resolved: class=${candidate.name} confidence=version-mapped strategy=KnownMappings3970")
        } else if (versionName == KnownMappings3970.VERSION_NAME) {
            emit(logger, "[SymbolResolver] Failed to resolve $symbol reason=known mapping unavailable")
        }
        return candidate
    }

    private fun fallbackMethod(
        symbol: String,
        versionName: String?,
        candidate: Method?,
        logger: Consumer<String>,
    ): Method? {
        if (candidate != null) {
            emit(logger, "[SymbolResolver] $symbol resolved: ${candidate.declaringClass.name}.${candidate.name} confidence=version-mapped strategy=KnownMappings3970")
        } else if (versionName == KnownMappings3970.VERSION_NAME) {
            emit(logger, "[SymbolResolver] Failed to resolve $symbol reason=known mapping unavailable")
        }
        return candidate
    }

    private fun resolveCameraFacingEnum(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Class<*>? {
        val candidates = try {
            bridge.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create()
                        .superClass(Enum::class.java.name)
                        .usingStrings("back", "front"),
                ),
            )
        } catch (failure: Throwable) {
            diagnostics += "cameraFacing query failed: ${failure.javaClass.simpleName}"
            return null
        }
        val validated = candidates.mapNotNull { data ->
            try {
                val clazz = data.getInstance(classLoader)
                val names = clazz.enumConstants?.map { (it as Enum<*>).name }?.toSet()
                if (clazz.isEnum && names == setOf("Back", "Front")) clazz else null
            } catch (_: Throwable) {
                null
            }
        }.distinctBy { it.name }
        return uniqueClass(validated, "cameraFacing", logger, diagnostics, candidates.map { it.name })
    }

    private fun resolveCameraViewModel(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        facingEnum: Class<*>?,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Class<*>? {
        if (facingEnum == null) return null
        return try {
            val cameraFields = FieldsMatcher.create()
                .addForType("androidx.camera.core.Preview")
                .addForType("androidx.camera.core.Camera")
                .addForType("androidx.camera.lifecycle.ProcessCameraProvider")
            val bindMethod = MethodMatcher.create()
                .returnType("void")
                .paramTypes("androidx.lifecycle.LifecycleOwner", facingEnum.name)
            val candidates = bridge.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create()
                        .superClass("androidx.lifecycle.ViewModel")
                .fields(cameraFields)
                        .methods(MethodsMatcher.create().add(bindMethod)),
                ),
            )
            val validated = candidates.mapNotNull { data ->
                try {
                    val clazz = data.getInstance(classLoader)
                    val fieldTypes = data.fields.map { it.typeName }.toSet()
                    val hasExpectedFields = fieldTypes.contains("androidx.camera.core.Preview") &&
                        fieldTypes.contains("androidx.camera.core.Camera") &&
                        fieldTypes.contains("androidx.camera.lifecycle.ProcessCameraProvider")
                    val bindMethods = data.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create()
                                .returnType("void")
                                .paramTypes("androidx.lifecycle.LifecycleOwner", facingEnum.name),
                        ),
                    )
                    if (hasExpectedFields && bindMethods.size == 1) clazz else null
                } catch (_: Throwable) {
                    null
                }
            }.distinctBy { it.name }
            uniqueClass(validated, "cameraViewModel", logger, diagnostics, candidates.map { it.name })
        } catch (failure: Throwable) {
            diagnostics += "cameraViewModel query failed: ${failure.javaClass.simpleName}"
            null
        }
    }

    private fun resolveCameraBindMethod(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        cameraViewModel: Class<*>?,
        facingEnum: Class<*>?,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Method? {
        if (cameraViewModel == null || facingEnum == null) return null
        val candidates = try {
            bridge.getClassData(cameraViewModel)?.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .returnType("void")
                        .paramTypes("androidx.lifecycle.LifecycleOwner", facingEnum.name),
                ),
            )?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        val unique = candidates.distinctBy { it.descriptor }
        if (unique.size != 1) {
            val detail = "cameraBindConcurrent candidateCount=${unique.size} candidates=${unique.map { it.descriptor }}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return null
        }
        return try {
            unique.single().getMethodInstance(classLoader).apply {
                isAccessible = true
                emit(logger, "[SymbolResolver] cameraBindConcurrent resolved: ${declaringClass.name}.$name${unique.single().descriptor} confidence=high strategy=DexKit(parameter signature)")
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveCameraOriginParsers(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): List<Method> {
        val candidates = try {
            bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .paramTypes("android.os.Bundle")
                        .usingEqStrings("origin"),
                ),
            )
        } catch (failure: Throwable) {
            diagnostics += "cameraOriginParsers query failed: ${failure.javaClass.simpleName}"
            return emptyList()
        }
        val validated = candidates.filter { method ->
            method.returnTypeName != "void" &&
                method.usingStrings.contains("origin") &&
                method.usingStrings.none { it in setOf("momentStartsAt", "captureId") } &&
                method.invokes.any { invoke ->
                    if (invoke.methodName != "valueOf") {
                        false
                    } else {
                        try {
                            invoke.declaredClass?.getInstance(classLoader)?.isEnum == true
                        } catch (_: Throwable) {
                            false
                        }
                    }
                }
        }.distinctBy { it.descriptor }
        if (validated.isEmpty() || validated.size > 1) {
            val detail = "cameraOriginParsers candidateCount=${validated.size} candidates=${validated.map { it.descriptor }}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return emptyList()
        }
        val methodData = validated.single()
        return try {
            val method = methodData.getMethodInstance(classLoader).apply { isAccessible = true }
            emit(logger, "[SymbolResolver] cameraOriginParser resolved: ${method.declaringClass.name}.${method.name}${methodData.descriptor} confidence=high strategy=DexKit(Bundle+origin+enum call)")
            listOf(method)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun resolveLocationRepository(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Pair<Class<*>?, Method?> {
        return try {
            val fusedCurrent = MethodMatcher.create()
                .declaredClass("com.google.android.gms.location.FusedLocationProviderClient")
                .name("getCurrentLocation")
            val fusedLast = MethodMatcher.create()
                .declaredClass("com.google.android.gms.location.FusedLocationProviderClient")
                .name("getLastLocation")
            val calls = MethodsMatcher.create()
                .add(fusedCurrent)
                .add(fusedLast)
                .matchType(MatchType.Contains)
            val candidates = bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .returnType("java.lang.Object")
                        .paramCount(2)
                        .invokeMethods(calls),
                ),
            ).filter { it.paramTypeNames.firstOrNull() == "boolean" }
            val unique = candidates.distinctBy { it.descriptor }
            if (unique.size != 1) {
                val detail = "locationRequest candidateCount=${unique.size} candidates=${unique.map { it.descriptor }}"
                diagnostics += detail
                emit(logger, "[SymbolResolver] $detail")
                null to null
            } else {
                val methodData = unique.single()
                val clazz = methodData.declaredClass?.getInstance(classLoader) ?: return null to null
                val method = methodData.getMethodInstance(classLoader).apply { isAccessible = true }
                emit(logger, "[SymbolResolver] LocationRepository resolved: class=${clazz.name} method=${methodData.methodSign} confidence=high strategy=DexKit(invokes FusedLocationProviderClient current+last)")
                clazz to method
            }
        } catch (failure: Throwable) {
            diagnostics += "locationRepository query failed: ${failure.javaClass.simpleName}"
            null to null
        }
    }

    private fun resolveLocationClientGetter(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        repository: Class<*>?,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Method? {
        if (repository == null) return null
        val candidates = try {
            bridge.getClassData(repository)?.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .returnType("com.google.android.gms.location.FusedLocationProviderClient")
                        .paramCount(0),
                ),
            )?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
        val unique = candidates.distinctBy { it.descriptor }
        if (unique.size != 1) {
            val detail = "locationClientGetter candidateCount=${unique.size} candidates=${unique.map { it.descriptor }}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return null
        }
        return try {
            unique.single().getMethodInstance(classLoader).apply {
                isAccessible = true
                emit(logger, "[SymbolResolver] locationClientGetter resolved: ${declaringClass.name}.$name${unique.single().descriptor} confidence=high strategy=DexKit(return type)")
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolvePostPipeline(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): PostPipelineSymbols {
        val domainData = findUniqueClassWithToString(
            bridge,
            "SendPostDomainModel",
            listOf("SendPostDomainModel(contents=", ", retakeCounter=", ", isLate=", ", caption="),
            28,
            logger,
            diagnostics,
        ) ?: return PostPipelineSymbols()

        val domainClass = try {
            domainData.getInstance(classLoader)
        } catch (failure: Throwable) {
            diagnostics += "postDomainModel class loading failed: ${failure.javaClass.simpleName}"
            emit(logger, "[SymbolResolver] Failed to resolve SendPostDomainModel reason=class load failed: ${failure.javaClass.simpleName}")
            return PostPipelineSymbols()
        }
        uniqueToStringMethod(domainData, "SendPostDomainModel", logger, diagnostics)
            ?: return PostPipelineSymbols()
        val domainConstructorData = uniqueConstructorMethodData(domainData, 28, "SendPostDomainModel", logger, diagnostics)
            ?: return PostPipelineSymbols(domainClass = domainClass)
        val domainConstructor = try {
            domainConstructorData.getConstructorInstance(classLoader).apply { isAccessible = true }
        } catch (failure: Throwable) {
            emit(logger, "[SymbolResolver] Failed to resolve SendPostDomainModel constructor reason=${failure.javaClass.simpleName}")
            return PostPipelineSymbols(domainClass = domainClass)
        }
        val domainParams = domainConstructor.parameterTypes
        if (domainParams[5] != Int::class.javaPrimitiveType ||
            domainParams[7] != Long::class.javaPrimitiveType ||
            domainParams[8] != Long::class.javaPrimitiveType ||
            domainParams[10] != Boolean::class.javaPrimitiveType ||
            domainParams[15] != String::class.java ||
            domainParams[17] != ArrayList::class.java) {
            emit(logger, "[SymbolResolver] Failed to validate SendPostDomainModel constructor reason=parameter shape mismatch")
            return PostPipelineSymbols(domainClass = domainClass, domainConstructor = domainConstructor)
        }
        val domainFields = writeFieldOrder(domainConstructorData, domainClass, 28, "SendPostDomainModel", classLoader, logger, diagnostics)
            ?: return PostPipelineSymbols(domainClass = domainClass, domainConstructor = domainConstructor)

        val contentsClass = try {
            domainConstructor.parameterTypes[0]
        } catch (_: Throwable) {
            return PostPipelineSymbols(domainClass = domainClass, domainConstructor = domainConstructor, domainFields = domainFields)
        }
        val contentsData = bridge.getClassData(contentsClass)
        if (contentsData == null) {
            emit(logger, "[SymbolResolver] Failed to resolve PostContents reason=class metadata unavailable")
            return PostPipelineSymbols(domainClass = domainClass, domainConstructor = domainConstructor, domainFields = domainFields)
        }
        uniqueToStringMethod(contentsData, "PostContents", logger, diagnostics)
            ?: return PostPipelineSymbols(domainClass = domainClass, domainConstructor = domainConstructor, domainFields = domainFields, contentsClass = contentsClass)
        val contentsConstructorData = uniqueConstructorMethodData(contentsData, 5, "PostContents", logger, diagnostics)
            ?: return PostPipelineSymbols(domainClass = domainClass, domainConstructor = domainConstructor, domainFields = domainFields, contentsClass = contentsClass)
        val contentsConstructor = try {
            contentsConstructorData.getConstructorInstance(classLoader).apply { isAccessible = true }
        } catch (failure: Throwable) {
            emit(logger, "[SymbolResolver] Failed to resolve PostContents constructor reason=${failure.javaClass.simpleName}")
            return PostPipelineSymbols(domainClass = domainClass, domainConstructor = domainConstructor, domainFields = domainFields, contentsClass = contentsClass)
        }
        val contentsFields = writeFieldOrder(contentsConstructorData, contentsClass, 5, "PostContents", classLoader, logger, diagnostics)
            ?: return PostPipelineSymbols(domainClass = domainClass, domainConstructor = domainConstructor, domainFields = domainFields, contentsClass = contentsClass, contentsConstructor = contentsConstructor)

        val mediaClass = contentsConstructor.parameterTypes[0]
        val mediaData = bridge.getClassData(mediaClass)
        if (mediaData == null) {
            emit(logger, "[SymbolResolver] Failed to resolve BeRealMedia reason=class metadata unavailable")
            return PostPipelineSymbols(domainClass, domainConstructor, domainFields, contentsClass, contentsConstructor, contentsFields)
        }
        uniqueToStringMethod(mediaData, "BeRealMedia", logger, diagnostics)
            ?: return PostPipelineSymbols(domainClass, domainConstructor, domainFields, contentsClass, contentsConstructor, contentsFields, mediaClass)
        val mediaConstructorData = uniqueConstructorMethodData(mediaData, 4, "BeRealMedia", logger, diagnostics)
        val mediaConstructor = try {
            mediaConstructorData?.getConstructorInstance(classLoader)?.apply { isAccessible = true }
        } catch (failure: Throwable) {
            emit(logger, "[SymbolResolver] Failed to resolve BeRealMedia constructor reason=${failure.javaClass.simpleName}")
            null
        }
        if (mediaConstructor != null && (
                mediaConstructor.parameterTypes[0] != String::class.java ||
                    mediaConstructor.parameterTypes[1] != Int::class.javaPrimitiveType ||
                    mediaConstructor.parameterTypes[2] != Int::class.javaPrimitiveType ||
                    !mediaConstructor.parameterTypes[3].isEnum)) {
            emit(logger, "[SymbolResolver] Failed to validate BeRealMedia constructor reason=parameter shape mismatch")
        }

        val draftData = findUniqueClassWithToString(
            bridge,
            "CorePostDraft",
            listOf("CorePostDraft(id=", ", retakeCounter=", ", isLate="),
            null,
            logger,
            diagnostics,
        )
        val coreDraftClass = draftData?.let { data ->
            try {
                data.getInstance(classLoader)
            } catch (failure: Throwable) {
                diagnostics += "CorePostDraft class loading failed: ${failure.javaClass.simpleName}"
                null
            }
        }
        val coreDraftConstructorData = draftData?.let {
            uniqueConstructorMethodData(it, 28, "CorePostDraft", logger, diagnostics)
        }
        val coreDraftConstructor = try {
            coreDraftConstructorData?.getConstructorInstance(classLoader)?.apply { isAccessible = true }
        } catch (failure: Throwable) {
            emit(logger, "[SymbolResolver] Failed to resolve CorePostDraft constructor reason=${failure.javaClass.simpleName}")
            null
        }
        if (coreDraftConstructor != null) {
            val draftParams = coreDraftConstructor.parameterTypes
            val domainParams = domainConstructor.parameterTypes
            val mirroredDomainParams = (0..24).all { index ->
                val draftType = draftParams[index + 1]
                val domainType = domainParams[index]
                draftType == domainType || draftType.isAssignableFrom(domainType)
            }
            val validDraftShape = draftParams.size == 28 &&
                draftParams[0] == String::class.java &&
                mirroredDomainParams &&
                draftParams[26] == java.lang.Boolean::class.java &&
                draftParams[27] == java.lang.Boolean::class.java
            if (!validDraftShape) {
                emit(logger, "[SymbolResolver] Failed to validate CorePostDraft constructor reason=domain-copy shape mismatch")
            } else {
                emit(logger, "[SymbolResolver] CorePostDraft constructor resolved: ${coreDraftClass?.name}(${draftParams.joinToString { it.simpleName }}) confidence=high strategy=validated DomainModel copy shape")
            }
        }
        val draftClassName = coreDraftClass?.name
        val sendCandidates = try {
            val shapeCandidates = bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .returnType("java.lang.Object")
                        .paramCount(3),
                ),
            )
            val typedCandidates = shapeCandidates.filter { candidate ->
                candidate.paramTypeNames[0] == domainClass.name &&
                    candidate.paramTypeNames[1] == String::class.java.name
            }
            val continuationCandidates = typedCandidates.filter { candidate ->
                candidate.paramTypeNames.getOrNull(2)?.let { continuationName ->
                    try {
                        val continuationClass = Class.forName(continuationName, false, classLoader)
                        isContinuationContract(continuationClass)
                    } catch (_: Throwable) {
                        false
                    }
                } == true
            }
            val draftCandidates = continuationCandidates.filter { candidate ->
                draftClassName == null || candidate.invokes.any { it.isConstructor && it.declaredClassName == draftClassName }
            }.distinctBy { it.descriptor }
            if (draftCandidates.isEmpty()) {
                emit(logger, "[SymbolResolver] SendPost candidate stages: object/3-arg=${shapeCandidates.size}, model+String=${typedCandidates.size}, continuation-contract=${continuationCandidates.size}, CorePostDraft constructor=${draftCandidates.size}")
            }
            draftCandidates
        } catch (failure: Throwable) {
            diagnostics += "sendDraft query failed: ${failure.javaClass.simpleName}"
            emptyList()
        }
        val sendCandidateData = sendCandidates.singleOrNull()
        val repositoryInterface = try {
            sendCandidateData?.declaredClass?.getInstance(classLoader)?.declaredConstructors
                ?.singleOrNull { it.parameterCount == 1 }
                ?.parameterTypes
                ?.singleOrNull()
        } catch (_: Throwable) {
            null
        }
        val sendDraftMethod = if (sendCandidates.size == 1) {
            try {
                sendCandidates.single().getMethodInstance(classLoader).apply {
                    isAccessible = true
                    emit(logger, "[SymbolResolver] SendPost draft entry resolved: ${declaringClass.name}.$name${sendCandidates.single().descriptor} confidence=high strategy=DexKit(domain+String+Continuation+CorePostDraft construction)")
                }
            } catch (failure: Throwable) {
                diagnostics += "sendDraft method loading failed: ${failure.javaClass.simpleName}"
                null
            }
        } else {
            val detail = "sendDraft candidateCount=${sendCandidates.size} candidates=${sendCandidates.map { it.descriptor }}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            null
        }

        val sendPostCoroutineConstructor = resolveSendPostCoroutineConstructor(
            sendCandidateData,
            coreDraftClass,
            repositoryInterface,
            classLoader,
            logger,
            diagnostics,
        )
        val uploadWorkerClass = resolveUploadWorkerClass(bridge, classLoader, logger, diagnostics)
        val friendsVisibility = resolveFriendsVisibility(
            bridge,
            domainConstructor.parameterTypes[1],
            classLoader,
            logger,
            diagnostics,
        )
        val friendOfFriendsVisibility = resolvePostVisibility(
            bridge,
            domainConstructor.parameterTypes[1],
            "FriendOfFriends",
            classLoader,
            logger,
            diagnostics,
        )
        val globalVisibility = resolvePostVisibility(
            bridge,
            domainConstructor.parameterTypes[1],
            "Global",
            classLoader,
            logger,
            diagnostics,
        )
        val currentUserUidField = resolveCurrentUserUidField(bridge, classLoader, logger, diagnostics)
        val currentUserProvider = KnownMappings3970.resolveCurrentUserProvider(classLoader, KnownMappings3970.VERSION_NAME)
        if (currentUserProvider.first != null && currentUserProvider.second != null) {
            emit(logger, "[SymbolResolver] CurrentUser provider resolved: ${currentUserProvider.first!!.name}.${currentUserProvider.second!!.name} confidence=version-mapped strategy=KnownMappings3970")
        } else {
            emit(logger, "[SymbolResolver] Failed to resolve CurrentUser provider reason=known-version mapping unavailable")
        }

        return PostPipelineSymbols(
            domainClass = domainClass,
            domainConstructor = domainConstructor,
            domainFields = domainFields,
            contentsClass = contentsClass,
            contentsConstructor = contentsConstructor,
            contentsFields = contentsFields,
            mediaClass = mediaClass,
            mediaConstructor = mediaConstructor,
            coreDraftClass = coreDraftClass,
            coreDraftConstructor = coreDraftConstructor,
            sendPostCoroutineConstructor = sendPostCoroutineConstructor,
            repositoryInterface = repositoryInterface,
            uploadWorkerClass = uploadWorkerClass,
            sendDraftMethod = sendDraftMethod,
            friendsVisibility = friendsVisibility,
            friendOfFriendsVisibility = friendOfFriendsVisibility,
            globalVisibility = globalVisibility,
            currentUserProviderClass = currentUserProvider.first,
            currentUserProviderMethod = currentUserProvider.second,
            currentUserUidField = currentUserUidField,
        )
    }

    private fun isContinuationContract(type: Class<*>): Boolean {
        val methods = type.methods.toMutableList()
        var current: Class<*>? = type.superclass
        while (current != null) {
            methods += current.declaredMethods
            current = current.superclass
        }
        val hasResumeWith = methods.any { method ->
            method.name == "resumeWith" &&
                method.parameterTypes.contentEquals(arrayOf(Any::class.java)) &&
                method.returnType == Void.TYPE
        }
        val hasContextGetter = methods.any { method ->
            method.name == "getContext" && method.parameterCount == 0 && method.returnType != Void.TYPE
        }
        return hasResumeWith && hasContextGetter
    }

    private fun resolveSendPostCoroutineConstructor(
        sendMethodData: MethodData?,
        coreDraftClass: Class<*>?,
        repositoryInterface: Class<*>?,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Constructor<*>? {
        if (sendMethodData == null || coreDraftClass == null || repositoryInterface == null) return null
        val candidates = sendMethodData.invokes.filter { invoke ->
            if (!invoke.isConstructor || invoke.paramCount != 4) return@filter false
            val params = invoke.paramTypeNames
            if (params[1] != coreDraftClass.name || params[2] != String::class.java.name) return@filter false
            try {
                val repositoryType = Class.forName(params[0], false, classLoader)
                val continuationType = Class.forName(params[3], false, classLoader)
                repositoryInterface.isAssignableFrom(repositoryType) && isContinuationContract(continuationType)
            } catch (_: Throwable) {
                false
            }
        }.distinctBy { it.descriptor }
        if (candidates.size != 1) {
            val detail = "officialPostCoroutine candidateCount=${candidates.size} candidates=${candidates.map { it.descriptor }}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return null
        }
        return try {
            candidates.single().getConstructorInstance(classLoader).apply {
                isAccessible = true
                emit(logger, "[SymbolResolver] Official draft coroutine constructor resolved: ${declaringClass.name}(${parameterTypes.joinToString { it.simpleName }}) confidence=high strategy=DexKit(invoked constructor + repository + CorePostDraft + Continuation)")
            }
        } catch (failure: Throwable) {
            diagnostics += "officialPostCoroutine constructor load failed: ${failure.javaClass.simpleName}"
            null
        }
    }

    private fun resolveFriendsVisibility(
        bridge: DexKitBridge,
        visibilityType: Class<*>,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Any? = resolvePostVisibility(bridge, visibilityType, "Friends", classLoader, logger, diagnostics)

    private fun resolvePostVisibility(
        bridge: DexKitBridge,
        visibilityType: Class<*>,
        expectedName: String,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Any? {
        val candidates = try {
            bridge.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create()
                        .superClass(visibilityType.name)
                        .methods(
                            MethodsMatcher.create().add(
                                MethodMatcher.create()
                                    .name("toString")
                                    .returnType(String::class.java)
                                    .paramCount(0)
                                    .usingEqStrings(expectedName),
                            ),
                        ),
                ),
            )
        } catch (failure: Throwable) {
            diagnostics += "postVisibility.$expectedName query failed: ${failure.javaClass.simpleName}"
            emptyList()
        }
        val validated = candidates.distinctBy { it.descriptor }
        if (validated.size != 1) {
            val detail = "postVisibility.$expectedName candidateCount=${validated.size} candidates=${validated.map { it.name }}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return null
        }
        return try {
            val clazz = validated.single().getInstance(classLoader)
            val singletonFields = clazz.declaredFields.filter { field ->
                Modifier.isStatic(field.modifiers) && visibilityType.isAssignableFrom(field.type)
            }
            singletonFields.mapNotNull { field ->
                field.isAccessible = true
                field.get(null)
            }.firstOrNull { it.toString() == expectedName }
                ?.also { emit(logger, "[SymbolResolver] postVisibility.$expectedName resolved: class=${it.javaClass.name} confidence=high strategy=DexKit(toString semantic value + static singleton)") }
                ?: run {
                    emit(logger, "[SymbolResolver] Failed to resolve postVisibility.$expectedName reason=no static singleton value")
                    null
                }
        } catch (failure: Throwable) {
            diagnostics += "postVisibility.$expectedName value loading failed: ${failure.javaClass.simpleName}"
            emit(logger, "[SymbolResolver] Failed to resolve postVisibility.$expectedName reason=${failure.javaClass.simpleName}")
            null
        }
    }

    private fun resolveCurrentUserUidField(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Field? {
        val userClassData = findUniqueClassWithToString(
            bridge,
            "CurrentUser",
            listOf("User(uid=", ", userName="),
            null,
            logger,
            diagnostics,
        ) ?: return null
        val userClass = try {
            userClassData.getInstance(classLoader)
        } catch (failure: Throwable) {
            diagnostics += "CurrentUser load failed: ${failure.javaClass.simpleName}"
            return null
        }
        val toStringData = uniqueToStringMethod(userClassData, "CurrentUser", logger, diagnostics) ?: return null
        val firstField = toStringData.usingFields
            .firstOrNull { it.usingType.isRead() && it.field.declaredClassName == userClass.name && !Modifier.isStatic(it.field.modifiers) }
            ?.field
        if (firstField == null || firstField.typeName != String::class.java.name) {
            emit(logger, "[SymbolResolver] Failed to resolve CurrentUser.uid reason=first semantic toString field was not String")
            return null
        }
        return try {
            firstField.getFieldInstance(classLoader).apply {
                isAccessible = true
                emit(logger, "[SymbolResolver] CurrentUser.uid resolved: ${declaringClass.name}.$name confidence=high strategy=DexKit(User(uid=... toString first field)")
            }
        } catch (failure: Throwable) {
            diagnostics += "CurrentUser uid field load failed: ${failure.javaClass.simpleName}"
            null
        }
    }

    private fun resolveUploadWorkerClass(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Class<*>? {
        val candidates = try {
            val workerMethod = MethodMatcher.create().usingEqStrings("key_post_id")
            bridge.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create()
                        .superClass("androidx.work.CoroutineWorker")
                        .methods(MethodsMatcher.create().add(workerMethod)),
                ),
            ).filter { data ->
                val workerClass = runCatching { data.getInstance(classLoader) }.getOrNull()
                workerClass != null && workerClass.declaredConstructors.any { constructor ->
                    constructor.parameterTypes.contentEquals(
                        arrayOf(
                            android.content.Context::class.java,
                            Class.forName("androidx.work.WorkerParameters", false, classLoader),
                        ),
                    )
                } && data.methods.any { "key_post_id" in it.usingStrings }
            }.distinctBy { it.descriptor }
        } catch (failure: Throwable) {
            diagnostics += "post upload worker query failed: ${failure.javaClass.simpleName}"
            emit(logger, "[SymbolResolver] Failed to resolve UploadUnsentPostWorker reason=query failed: ${failure.javaClass.simpleName}")
            return null
        }
        if (candidates.size != 1) {
            val detail = "UploadUnsentPostWorker candidateCount=${candidates.size} candidates=${candidates.map { it.name }}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return null
        }
        return try {
            candidates.single().getInstance(classLoader).also {
                emit(logger, "[SymbolResolver] UploadUnsentPostWorker resolved: class=${it.name} confidence=high strategy=DexKit(CoroutineWorker+key_post_id+Context/WorkerParameters constructor)")
            }
        } catch (failure: Throwable) {
            diagnostics += "post upload worker class loading failed: ${failure.javaClass.simpleName}"
            null
        }
    }

    private fun findUniqueClassWithToString(
        bridge: DexKitBridge,
        symbol: String,
        anchors: List<String>,
        expectedFieldCount: Int?,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): ClassData? {
        val candidates = try {
            val toStringMatcher = MethodMatcher.create()
                .name("toString")
                .returnType(String::class.java)
                .paramCount(0)
                .usingStrings(*anchors.toTypedArray())
            val classMatcher = ClassMatcher.create()
                .methods(MethodsMatcher.create().add(toStringMatcher))
            val results = bridge.findClass(FindClass.create().matcher(classMatcher))
                .filter { data ->
                    val method = data.findMethod(FindMethod.create().matcher(toStringMatcher)).singleOrNull()
                    method != null && method.usingStrings.containsAll(anchors) &&
                        (expectedFieldCount == null || data.fields.size == expectedFieldCount)
                }
            results.distinctBy { it.descriptor }
        } catch (failure: Throwable) {
            diagnostics += "$symbol class query failed: ${failure.javaClass.simpleName}"
            emit(logger, "[SymbolResolver] Failed to resolve $symbol reason=query failed: ${failure.javaClass.simpleName}")
            return null
        }
        if (candidates.size != 1) {
            val detail = "$symbol candidateCount=${candidates.size} candidates=${candidates.map { it.name }}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return null
        }
        emit(logger, "[SymbolResolver] $symbol resolved: class=${candidates.single().name} confidence=high strategy=DexKit(toString semantic anchors${expectedFieldCount?.let { "+$it fields" } ?: ""})")
        return candidates.single()
    }

    private fun uniqueToStringMethod(
        classData: ClassData,
        symbol: String,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): MethodData? {
        val candidates = try {
            classData.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().name("toString").returnType(String::class.java).paramCount(0),
                ),
            ).toList()
        } catch (_: Throwable) {
            emptyList()
        }
        val unique = candidates.distinctBy { it.descriptor }
        if (unique.size != 1) {
            val detail = "$symbol toString candidateCount=${unique.size} candidates=${unique.map { it.descriptor }}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return null
        }
        return unique.single()
    }

    private fun uniqueConstructorMethodData(
        classData: ClassData,
        parameterCount: Int,
        symbol: String,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): MethodData? {
        val candidates = try {
            classData.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().name("<init>").paramCount(parameterCount),
                ),
            ).filter { it.isConstructor }
        } catch (_: Throwable) {
            emptyList()
        }
        val unique = candidates.distinctBy { it.descriptor }
        if (unique.size != 1) {
            val detail = "$symbol constructor candidateCount=${unique.size} candidates=${unique.map { it.descriptor }}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return null
        }
        emit(logger, "[SymbolResolver] $symbol constructor signature resolved: ${unique.single().descriptor} confidence=high strategy=DexKit(parameter count + owner class)")
        return unique.single()
    }

    private fun writeFieldOrder(
        methodData: MethodData,
        owner: Class<*>,
        expectedCount: Int,
        symbol: String,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): List<Field>? {
        val fields = methodData.usingFields
            .filter { it.usingType.isWrite() && it.field.declaredClassName == owner.name && !Modifier.isStatic(it.field.modifiers) }
            .map { it.field }
            .distinctBy { it.descriptor }
        if (fields.size != expectedCount) {
            val detail = "$symbol field-order count=${fields.size} expected=$expectedCount fields=${fields.map { it.descriptor }}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return null
        }
        return try {
            fields.map { it.getFieldInstance(classLoader).apply { isAccessible = true } }
                .also { resolved ->
                    emit(logger, "[SymbolResolver] $symbol field order resolved: ${resolved.joinToString { it.name }} confidence=high strategy=DexKit(constructor write order)")
                }
        } catch (failure: Throwable) {
            diagnostics += "$symbol field loading failed: ${failure.javaClass.simpleName}"
            null
        }
    }

    private fun fallbackField(
        fields: MutableMap<String, Field>,
        classLoader: ClassLoader,
        versionName: String?,
        symbol: String,
        logger: Consumer<String>,
    ) {
        if (fields.containsKey(symbol)) return
        val field = KnownMappings3970.resolveStringField(classLoader, versionName, symbol)
        if (field != null) {
            fields[symbol] = field
            emit(logger, "[SymbolResolver] $symbol resolved: ${field.declaringClass.name}.${field.name} confidence=version-mapped strategy=KnownMappings3970")
        } else {
            emit(logger, "[SymbolResolver] Failed to resolve $symbol reason=no unique DexKit candidate and no applicable version mapping")
        }
    }

    private fun uniqueClass(
        candidates: List<Class<*>>,
        symbol: String,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
        descriptors: List<String>,
    ): Class<*>? {
        val unique = candidates.distinctBy { it.name }
        if (unique.size != 1) {
            val detail = "$symbol candidateCount=${unique.size} candidates=$descriptors"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return null
        }
        val clazz = unique.single()
        emit(logger, "[SymbolResolver] $symbol resolved: class=${clazz.name} confidence=high strategy=DexKit(structure)")
        return clazz
    }

    private fun emit(logger: Consumer<String>, message: String) {
        Log.i(TAG, message)
        try {
            logger.accept(message)
        } catch (_: Throwable) {
            // Logging must not change host behavior.
        }
    }
}
