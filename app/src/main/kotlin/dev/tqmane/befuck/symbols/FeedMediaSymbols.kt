package dev.tqmane.befuck.symbols

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.query.matchers.MethodsMatcher
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.function.Consumer

data class FeedMediaSymbols(
    val postModelClass: Class<*>?,
    val postDeserializerMethod: Method?,
    val postIdField: Field?,
    val postTakenAtField: Field?,
    val postCaptionField: Field?,
    val postPrimaryField: Field?,
    val postPrimaryMediaField: Field?,
    val postSecondaryField: Field?,
    val postSecondaryMediaField: Field?,
    val postBtsMediaField: Field?,
    val mediaUrlField: Field?,
    val mediaWidthField: Field?,
    val mediaHeightField: Field?,
    val mediaTypeField: Field?,
    val postFeedCardComposableMethod: Method?,
    val postDataIdField: Field?,
    val postDataIsMineField: Field?,
    val viewStatePostDataField: Field?,
    val viewStateDualMediaField: Field?,
    val viewStateRealSponsoredPostUiStateField: Field?,
    val dualMediaPrimaryField: Field?,
    val dualMediaSecondaryField: Field?,
    val blurredMediaRenderMethod: Method?,
    val blurredOverlayComposableMethod: Method?,
) {
    val canCaptureFeedMedia: Boolean
        get() = postModelClass != null && postDeserializerMethod != null && postIdField != null &&
            postPrimaryField != null && postSecondaryField != null && mediaUrlField != null

    val canCaptureVisibleFeedMedia: Boolean
        get() = postFeedCardComposableMethod != null && postDataIdField != null &&
            postDataIsMineField != null &&
            viewStatePostDataField != null && viewStateDualMediaField != null &&
            dualMediaPrimaryField != null && dualMediaSecondaryField != null

    val canUnblurLocally: Boolean
        get() = blurredMediaRenderMethod != null && blurredOverlayComposableMethod != null
}

/** Resolves feed-media capture and local-only blur presentation hooks from semantic anchors. */
object FeedMediaSymbolResolver {
    private const val DECODER_TYPE = "kotlinx.serialization.encoding.Decoder"
    private const val COMPOSER_TYPE = "androidx.compose.runtime.Composer"
    private const val MODIFIER_TYPE = "androidx.compose.ui.Modifier"

    fun resolve(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): FeedMediaSymbols {
        val postClassData = findUniqueSemanticClass(
            bridge,
            "PostRemoteModel",
            listOf("PostRemoteModel(id=", ", primary=", ", secondary=", ", btsMedia="),
            logger,
            diagnostics,
        )
        val postClass = loadClass(postClassData, classLoader, "PostRemoteModel", logger, diagnostics)
        val fields = postClass?.let { resolvePostFields(it, logger, diagnostics) }
        val mediaClass = fields?.primary?.type
        val mediaFields = mediaClass?.let { resolveMediaFields(bridge, it, classLoader, logger, diagnostics) }
        val deserializer = if (postClass != null) {
            resolvePostDeserializer(bridge, postClass, classLoader, logger, diagnostics)
        } else {
            null
        }

        val blur = resolveBlurRenderMethods(bridge, classLoader, logger, diagnostics)
        val postDataData = findUniqueSemanticClass(
            bridge,
            "PostDataUiModel",
            listOf("PostDataUiModel(postId=", ", momentId=", ", ownerUid="),
            logger,
            diagnostics,
        )
        val postDataClass = loadClass(postDataData, classLoader, "PostDataUiModel", logger, diagnostics)
        val postDataIdField = if (postDataData != null && postDataClass != null) {
            resolveFirstReadStringField(postDataData, postDataClass, classLoader, logger, diagnostics)
        } else {
            null
        }
        val postDataIsMineField = if (postDataData != null && postDataClass != null) {
            resolveThirdReadBooleanField(postDataData, postDataClass, classLoader, logger, diagnostics)
        } else {
            null
        }
        val viewStatePostDataField = blur.viewStateClass?.let { viewStateClass ->
            uniqueFieldOfType(viewStateClass, postDataClass, "PostFeedListViewState.postData", logger, diagnostics)
        }
        val viewStateDualMediaField = blur.viewStateClass?.let { viewStateClass ->
            uniqueFieldOfType(viewStateClass, blur.dualMediaClass, "PostFeedListViewState.dualMedia", logger, diagnostics)
        }
        val viewStateSponsoredStateField = blur.viewStateClass?.let { viewStateClass ->
            resolveRealSponsoredStateField(bridge, viewStateClass, classLoader, logger, diagnostics)
        }
        val dualMediaFields = if (blur.dualMediaClass != null) {
            resolveDualMediaFields(bridge, blur.dualMediaClass, classLoader, logger, diagnostics)
        } else {
            null to null
        }
        val result = FeedMediaSymbols(
            postModelClass = postClass,
            postDeserializerMethod = deserializer,
            postIdField = fields?.id,
            postTakenAtField = fields?.takenAt,
            postCaptionField = fields?.caption,
            postPrimaryField = fields?.primary,
            postPrimaryMediaField = fields?.primaryMedia,
            postSecondaryField = fields?.secondary,
            postSecondaryMediaField = fields?.secondaryMedia,
            postBtsMediaField = fields?.btsMedia,
            mediaUrlField = mediaFields?.url,
            mediaWidthField = mediaFields?.width,
            mediaHeightField = mediaFields?.height,
            mediaTypeField = mediaFields?.type,
            postFeedCardComposableMethod = blur.feedCardMethod,
            postDataIdField = postDataIdField,
            postDataIsMineField = postDataIsMineField,
            viewStatePostDataField = viewStatePostDataField,
            viewStateDualMediaField = viewStateDualMediaField,
            viewStateRealSponsoredPostUiStateField = viewStateSponsoredStateField,
            dualMediaPrimaryField = dualMediaFields.first,
            dualMediaSecondaryField = dualMediaFields.second,
            blurredMediaRenderMethod = blur.blurredMediaMethod,
            blurredOverlayComposableMethod = blur.blurredOverlayMethod,
        )
        emit(
            logger,
            "[SymbolResolver] Feed media capture=${if (result.canCaptureFeedMedia) "ready" else "disabled"}; visible-model capture=${if (result.canCaptureVisibleFeedMedia) "ready" else "disabled"}; local unblur=${if (result.canUnblurLocally) "ready" else "disabled"}",
        )
        return result
    }

    private data class PostFields(
        val id: Field,
        val takenAt: Field?,
        val caption: Field?,
        val primary: Field,
        val primaryMedia: Field?,
        val secondary: Field,
        val secondaryMedia: Field?,
        val btsMedia: Field?,
    )

    private data class MediaFields(val url: Field, val width: Field?, val height: Field?, val type: Field?)

    private data class BlurResolution(
        val viewStateClass: Class<*>?,
        val dualMediaClass: Class<*>?,
        val feedCardMethod: Method?,
        val blurredMediaMethod: Method?,
        val blurredOverlayMethod: Method?,
    )

    private fun resolvePostFields(
        postClass: Class<*>,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): PostFields? {
        return try {
            val id = declaredField(postClass, "id")
            val takenAt = declaredFieldOrNull(postClass, "takenAt")
            val caption = declaredFieldOrNull(postClass, "caption")
            val primary = declaredField(postClass, "primary")
            val primaryMedia = declaredFieldOrNull(postClass, "primaryMedia")
            val secondary = declaredField(postClass, "secondary")
            val secondaryMedia = declaredFieldOrNull(postClass, "secondaryMedia")
            val btsMedia = declaredFieldOrNull(postClass, "btsMedia")
            require(id.type == String::class.java) { "PostRemoteModel.id is not a String" }
            require(primary.type == secondary.type) { "PostRemoteModel primary/secondary media types differ" }
            listOfNotNull(primaryMedia, secondaryMedia, btsMedia).forEach { field ->
                require(field.type == primary.type) { "PostRemoteModel.${field.name} media type mismatch" }
            }
            emit(logger, "[SymbolResolver] PostRemoteModel semantic fields resolved: id/takenAt/caption/primary/secondary/btsMedia")
            PostFields(id, takenAt, caption, primary, primaryMedia, secondary, secondaryMedia, btsMedia)
        } catch (failure: Throwable) {
            diagnostics += "PostRemoteModel field resolution failed: ${failure.javaClass.simpleName}"
            emit(logger, "[SymbolResolver] Failed to resolve PostRemoteModel media fields: ${failure.javaClass.simpleName}")
            null
        }
    }

    private fun resolveMediaFields(
        bridge: DexKitBridge,
        mediaClass: Class<*>,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): MediaFields? {
        val classData = try {
            bridge.getClassData(mediaClass)
        } catch (_: Throwable) {
            null
        }
        if (classData == null) return null
        val validToString = try {
            classData.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().name("toString").returnType(String::class.java).paramCount(0),
                ),
            ).any { method ->
                method.usingStrings.containsAll(listOf("MediaRemoteModel(url=", ", width=", ", height="))
            }
        } catch (_: Throwable) {
            false
        }
        if (!validToString) {
            emit(logger, "[SymbolResolver] Failed to validate PostRemoteModel media type: ${mediaClass.name}")
            return null
        }
        return try {
            val fields = MediaFields(
                url = declaredField(mediaClass, "url"),
                width = declaredFieldOrNull(mediaClass, "width"),
                height = declaredFieldOrNull(mediaClass, "height"),
                type = declaredFieldOrNull(mediaClass, "mediaType"),
            )
            require(fields.url.type == String::class.java) { "MediaRemoteModel.url is not a String" }
            emit(logger, "[SymbolResolver] MediaRemoteModel fields resolved for ${mediaClass.name}")
            fields
        } catch (failure: Throwable) {
            diagnostics += "MediaRemoteModel field resolution failed: ${failure.javaClass.simpleName}"
            emit(logger, "[SymbolResolver] Failed to resolve MediaRemoteModel fields: ${failure.javaClass.simpleName}")
            null
        }
    }

    private fun resolvePostDeserializer(
        bridge: DexKitBridge,
        postClass: Class<*>,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Method? {
        val candidates = try {
            bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .name("deserialize")
                        .returnType(Any::class.java)
                        .paramCount(1),
                ),
            ).filter { method ->
                method.paramTypeNames.singleOrNull() == DECODER_TYPE &&
                    method.invokes.any { invoke -> invoke.isConstructor && invoke.declaredClassName == postClass.name }
            }.distinctBy { it.descriptor }
        } catch (failure: Throwable) {
            diagnostics += "PostRemoteModel deserializer query failed: ${failure.javaClass.simpleName}"
            emptyList()
        }
        if (candidates.size != 1) {
            val detail = "PostRemoteModel deserializer candidateCount=${candidates.size} candidates=${candidates.map { it.descriptor }}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return null
        }
        return try {
            candidates.single().getMethodInstance(classLoader).apply {
                isAccessible = true
                emit(logger, "[SymbolResolver] PostRemoteModel deserializer resolved: ${declaringClass.name}.$name confidence=high strategy=DexKit(Decoder + constructs semantic PostRemoteModel)")
            }
        } catch (failure: Throwable) {
            diagnostics += "PostRemoteModel deserializer load failed: ${failure.javaClass.simpleName}"
            null
        }
    }

    private fun resolveBlurRenderMethods(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): BlurResolution {
        val viewStateData = findUniqueSemanticClass(
            bridge,
            "PostFeedListViewState",
            listOf("PostFeedListViewState(postData=", ", state=", ", sponsoredUiModel="),
            logger,
            diagnostics,
        )
        val viewStateClass = loadClass(viewStateData, classLoader, "PostFeedListViewState", logger, diagnostics)
            ?: return BlurResolution(null, null, null, null, null)
        val dualMediaData = findUniqueSemanticClass(
            bridge,
            "DualViewData",
            listOf("DualViewData(primary=", ", secondary=", ", type="),
            logger,
            diagnostics,
        )
        val dualMediaClass = loadClass(dualMediaData, classLoader, "DualViewData", logger, diagnostics)
            ?: return BlurResolution(viewStateClass, null, null, null, null)
        val blurViewData = findUniqueSemanticClass(
            bridge,
            "BlurViewState",
            listOf("BlurViewState(title=", ", message=", ", actionButtonText="),
            logger,
            diagnostics,
        )
        val blurViewClass = loadClass(blurViewData, classLoader, "BlurViewState", logger, diagnostics)
            ?: return BlurResolution(viewStateClass, dualMediaClass, null, null, null)

        val renderers = try {
            bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().returnType("void").paramCount(14),
                ),
            ).filter { method ->
                val params = method.paramTypeNames
                params[0] == MODIFIER_TYPE && params[1] == viewStateClass.name &&
                    params[11] == COMPOSER_TYPE && params[12] == "int" && params[13] == "int" &&
                    method.invokes.any { invoke ->
                        val childParams = invoke.paramTypeNames
                        !invoke.isConstructor && invoke.returnTypeName == "void" && childParams.size == 18 &&
                            childParams[0] == MODIFIER_TYPE && childParams[1] == dualMediaClass.name &&
                            childParams[2] == "boolean" && childParams[16] == COMPOSER_TYPE && childParams[17] == "int"
                    } && method.invokes.any { invoke ->
                        val childParams = invoke.paramTypeNames
                        !invoke.isConstructor && invoke.returnTypeName == "void" && childParams.size == 5 &&
                            childParams[0] == MODIFIER_TYPE && childParams[1] == blurViewClass.name &&
                            childParams[3] == COMPOSER_TYPE && childParams[4] == "int"
                    }
            }.distinctBy { it.descriptor }
        } catch (failure: Throwable) {
            diagnostics += "blurred feed renderer query failed: ${failure.javaClass.simpleName}"
            emptyList()
        }
        if (renderers.size != 1) {
            val detail = "blurred feed renderer candidateCount=${renderers.size} candidates=${renderers.map { it.descriptor }}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return BlurResolution(viewStateClass, dualMediaClass, null, null, null)
        }

        val renderer = renderers.single()
        val invokedMethods = renderer.invokes.filter { !it.isConstructor }
        val mediaCandidates = invokedMethods.filter { invoke ->
            val params = invoke.paramTypeNames
            invoke.returnTypeName == "void" && params.size == 18 &&
                params[0] == MODIFIER_TYPE && params[1] == dualMediaClass.name &&
                params[2] == "boolean" && params[16] == COMPOSER_TYPE && params[17] == "int"
        }.distinctBy { it.descriptor }
        val overlayCandidates = invokedMethods.filter { invoke ->
            val params = invoke.paramTypeNames
            invoke.returnTypeName == "void" && params.size == 5 &&
                params[0] == MODIFIER_TYPE && params[1] == blurViewClass.name &&
                params[3] == COMPOSER_TYPE && params[4] == "int"
        }.distinctBy { it.descriptor }
        if (mediaCandidates.size != 1 || overlayCandidates.size != 1) {
            emit(logger, "[SymbolResolver] Failed to resolve local unblur methods: media=${mediaCandidates.size}, overlay=${overlayCandidates.size}")
            diagnostics += "local unblur methods ambiguous: media=${mediaCandidates.size}, overlay=${overlayCandidates.size}"
            return BlurResolution(viewStateClass, dualMediaClass, null, null, null)
        }
        return try {
            val feedCardMethod = renderer.getMethodInstance(classLoader).apply { isAccessible = true }
            val mediaMethod = mediaCandidates.single().getMethodInstance(classLoader).apply { isAccessible = true }
            val overlayMethod = overlayCandidates.single().getMethodInstance(classLoader).apply { isAccessible = true }
            emit(logger, "[SymbolResolver] Feed post card and local unblur methods resolved through PostFeedListViewState composition: card=${feedCardMethod.declaringClass.name}.${feedCardMethod.name}, media=${mediaMethod.declaringClass.name}.${mediaMethod.name}, overlay=${overlayMethod.declaringClass.name}.${overlayMethod.name}")
            BlurResolution(viewStateClass, dualMediaClass, feedCardMethod, mediaMethod, overlayMethod)
        } catch (failure: Throwable) {
            diagnostics += "local unblur method load failed: ${failure.javaClass.simpleName}"
            emit(logger, "[SymbolResolver] Failed to load local unblur methods: ${failure.javaClass.simpleName}")
            BlurResolution(viewStateClass, dualMediaClass, null, null, null)
        }
    }

    private fun findUniqueSemanticClass(
        bridge: DexKitBridge,
        symbol: String,
        anchors: List<String>,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): org.luckypray.dexkit.result.ClassData? {
        val candidates = try {
            val matcher = MethodMatcher.create()
                .name("toString")
                .returnType(String::class.java)
                .paramCount(0)
                .usingStrings(*anchors.toTypedArray())
            bridge.findClass(
                FindClass.create().matcher(
                    ClassMatcher.create().methods(MethodsMatcher.create().add(matcher)),
                ),
            ).filter { data ->
                data.findMethod(FindMethod.create().matcher(matcher)).any { method ->
                    method.usingStrings.containsAll(anchors)
                }
            }.distinctBy { it.descriptor }
        } catch (failure: Throwable) {
            diagnostics += "$symbol query failed: ${failure.javaClass.simpleName}"
            emptyList()
        }
        if (candidates.size != 1) {
            val detail = "$symbol candidateCount=${candidates.size} candidates=${candidates.map { it.name }}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return null
        }
        emit(logger, "[SymbolResolver] $symbol resolved by semantic toString anchors: ${candidates.single().name}")
        return candidates.single()
    }

    private fun loadClass(
        classData: org.luckypray.dexkit.result.ClassData?,
        classLoader: ClassLoader,
        symbol: String,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Class<*>? = try {
        classData?.getInstance(classLoader)
    } catch (failure: Throwable) {
        diagnostics += "$symbol class load failed: ${failure.javaClass.simpleName}"
        emit(logger, "[SymbolResolver] Failed to load $symbol: ${failure.javaClass.simpleName}")
        null
    }

    private fun resolveFirstReadStringField(
        classData: org.luckypray.dexkit.result.ClassData,
        owner: Class<*>,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Field? {
        val toStringData = try {
            classData.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().name("toString").returnType(String::class.java).paramCount(0),
                ),
            ).singleOrNull()
        } catch (_: Throwable) {
            null
        } ?: return null
        val firstStringField = toStringData.usingFields.firstOrNull { usage ->
            usage.usingType.isRead() && usage.field.declaredClassName == owner.name &&
                !Modifier.isStatic(usage.field.modifiers) && usage.field.typeName == String::class.java.name
        }?.field ?: return null
        return try {
            firstStringField.getFieldInstance(classLoader).apply {
                isAccessible = true
                emit(logger, "[SymbolResolver] PostDataUiModel.postId resolved from the first toString field read")
            }
        } catch (failure: Throwable) {
            diagnostics += "PostDataUiModel.postId field load failed: ${failure.javaClass.simpleName}"
            null
        }
    }

    private fun resolveThirdReadBooleanField(
        classData: org.luckypray.dexkit.result.ClassData,
        owner: Class<*>,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Field? {
        val toStringData = try {
            classData.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().name("toString").returnType(String::class.java).paramCount(0),
                ),
            ).singleOrNull()
        } catch (_: Throwable) {
            null
        } ?: return null
        val booleanFields = toStringData.usingFields.filter { usage ->
            usage.usingType.isRead() && usage.field.declaredClassName == owner.name &&
                !Modifier.isStatic(usage.field.modifiers) && usage.field.typeName == "boolean"
        }
        val fieldData = booleanFields.getOrNull(2)?.field ?: run {
            diagnostics += "PostDataUiModel isMyPost field unresolved: booleanToStringReads=${booleanFields.size}"
            emit(logger, "[SymbolResolver] PostDataUiModel isMyPost field unresolved: booleanToStringReads=${booleanFields.size}")
            return null
        }
        return try {
            fieldData.getFieldInstance(classLoader).apply {
                isAccessible = true
                emit(logger, "[SymbolResolver] PostDataUiModel.isMyPost resolved from ordered boolean toString reads")
            }
        } catch (failure: Throwable) {
            diagnostics += "PostDataUiModel isMyPost field load failed: ${failure.javaClass.simpleName}"
            null
        }
    }

    private fun resolveRealSponsoredStateField(
        bridge: DexKitBridge,
        owner: Class<*>,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Field? {
        return try {
            val classData = bridge.getClassData(owner) ?: return null
            val toStringData = classData.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .name("toString")
                        .returnType(String::class.java)
                        .paramCount(0),
                ),
            ).singleOrNull { method ->
                method.usingStrings.any { it.contains("realSponsoredPostUiState=") }
            } ?: return null
            val fields = toStringData.usingFields.filter { usage ->
                usage.usingType.isRead() && usage.field.declaredClassName == owner.name &&
                    !Modifier.isStatic(usage.field.modifiers) && !usage.field.typeName.let { name ->
                        name == "boolean" || name == "int" || name == "long" || name == "float" || name == "double"
                    }
            }
            val fieldData = fields.lastOrNull()?.field ?: return null
            fieldData.getFieldInstance(classLoader).apply {
                isAccessible = true
                emit(logger, "[SymbolResolver] PostFeedListViewState.realSponsoredPostUiState resolved from its ordered toString fields")
            }
        } catch (failure: Throwable) {
            diagnostics += "realSponsoredPostUiState field resolution failed: ${failure.javaClass.simpleName}"
            emit(logger, "[SymbolResolver] realSponsoredPostUiState field resolution failed: ${failure.javaClass.simpleName}")
            null
        }
    }

    private fun uniqueFieldOfType(
        owner: Class<*>,
        fieldType: Class<*>?,
        symbol: String,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Field? {
        if (fieldType == null) return null
        val fields = owner.declaredFields.filter { field ->
            !Modifier.isStatic(field.modifiers) && field.type == fieldType
        }
        if (fields.size != 1) {
            val detail = "$symbol field candidateCount=${fields.size} owner=${owner.name} type=${fieldType.name}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return null
        }
        return fields.single().apply {
            isAccessible = true
            emit(logger, "[SymbolResolver] $symbol field resolved: ${owner.name}.$name confidence=high strategy=unique semantic model field type")
        }
    }

    private fun resolveDualMediaFields(
        bridge: DexKitBridge,
        dualMediaClass: Class<*>,
        classLoader: ClassLoader,
        logger: Consumer<String>,
        diagnostics: MutableList<String>,
    ): Pair<Field?, Field?> {
        val classData = try {
            bridge.getClassData(dualMediaClass)
        } catch (_: Throwable) {
            null
        } ?: return null to null
        val toStringData = try {
            classData.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().name("toString").returnType(String::class.java).paramCount(0),
                ),
            ).singleOrNull()
        } catch (_: Throwable) {
            null
        } ?: return null to null
        val orderedMediaFields = toStringData.usingFields
            .filter { usage ->
                usage.usingType.isRead() && usage.field.declaredClassName == dualMediaClass.name &&
                    !Modifier.isStatic(usage.field.modifiers)
            }
            .map { usage -> usage.field }
            .distinctBy { it.descriptor }
            .take(2)
        if (orderedMediaFields.size != 2) {
            val detail = "DualViewData primary/secondary fields candidateCount=${orderedMediaFields.size}"
            diagnostics += detail
            emit(logger, "[SymbolResolver] $detail")
            return null to null
        }
        return try {
            val primary = orderedMediaFields[0].getFieldInstance(classLoader).apply { isAccessible = true }
            val secondary = orderedMediaFields[1].getFieldInstance(classLoader).apply { isAccessible = true }
            if (primary.type != secondary.type) error("DualViewData media field types differ")
            emit(logger, "[SymbolResolver] DualViewData primary/secondary fields resolved from toString read order")
            primary to secondary
        } catch (failure: Throwable) {
            diagnostics += "DualViewData media fields load failed: ${failure.javaClass.simpleName}"
            emit(logger, "[SymbolResolver] Failed to resolve DualViewData media fields: ${failure.javaClass.simpleName}")
            null to null
        }
    }

    private fun declaredField(owner: Class<*>, name: String): Field =
        owner.getDeclaredField(name).apply { isAccessible = true }

    private fun declaredFieldOrNull(owner: Class<*>, name: String): Field? =
        runCatching { declaredField(owner, name) }.getOrNull()

    private fun emit(logger: Consumer<String>, message: String) {
        runCatching { logger.accept(message) }
    }
}
