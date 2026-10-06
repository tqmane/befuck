package dev.tqmane.befuck.posting

import android.net.Uri
import dev.tqmane.befuck.symbols.ResolvedSymbols
import java.lang.reflect.Modifier
import java.time.Instant
import java.util.ArrayList
import java.util.UUID

object GalleryPostModelRewriter {
    @JvmStatic
    fun createDomainModel(symbols: ResolvedSymbols, request: GalleryPostRequest, isMain: Boolean = true): Any {
        val domainConstructor = requireNotNull(symbols.postDomainModelConstructor) {
            "SendPostDomainModel constructor unresolved"
        }
        val args = arrayOfNulls<Any?>(domainConstructor.parameterTypes.size)
        require(args.size == 28) { "Unexpected SendPostDomainModel constructor arity=${args.size}" }

        val contentsConstructor = requireNotNull(symbols.postContentsConstructor) {
            "PostContents constructor unresolved"
        }
        val mediaConstructor = requireNotNull(symbols.postMediaConstructor) {
            "BeRealMedia constructor unresolved"
        }
        require(symbols.postContentsFieldOrder.size == 5) { "PostContents shape unresolved" }

        fun createMedia(uri: String, width: Int, height: Int, video: Boolean): Any {
            require(width > 0 && height > 0) { "Media dimensions must be positive" }
            val mediaType = enumOrNamedSingleton(mediaConstructor.parameterTypes[3], if (video) "VIDEO" else "IMAGE")
            val mediaClass = mediaConstructor.declaringClass
            val explicitRatioConstructor = mediaClass.declaredConstructors.singleOrNull { constructor ->
                val parameters = constructor.parameterTypes
                parameters.size == 6 && parameters[0] == Int::class.javaPrimitiveType &&
                    parameters[1] == String::class.java && parameters[2] == Int::class.javaPrimitiveType &&
                    parameters[3] == Int::class.javaPrimitiveType && parameters[4] == mediaConstructor.parameterTypes[3] &&
                    parameters[5] == Float::class.javaPrimitiveType
            } ?: error("${mediaClass.name} has no explicit-aspect-ratio constructor")
            explicitRatioConstructor.isAccessible = true
            return explicitRatioConstructor.newInstance(31, uri, height, width, mediaType, width.toFloat() / height)
        }

        fun createVideoPlaceholder(media: GalleryMediaFile): Any {
            val path = requireNotNull(media.previewPath) { "Video preview image is missing" }
            val uri = Uri.fromFile(java.io.File(path)).toString()
            return createMedia(
                uri,
                media.previewWidth ?: media.width,
                media.previewHeight ?: media.height,
                video = false,
            )
        }

        fun createSlot(media: GalleryMediaFile): Pair<Any, Any?> {
            val content = createMedia(media.uri, media.width, media.height, video = media.isVideo)
            val placeholder = if (media.isVideo) createVideoPlaceholder(media) else null
            return content to placeholder
        }
        val back = requireNotNull(request.back) { "The back media is required" }
        val front = requireNotNull(request.front) { "The front media is required" }
        require(back.isVideo == front.isVideo) { "Front and back media must both be photos or both be videos" }
        val (backContent, backPlaceholder) = createSlot(back)
        val (frontContent, frontPlaceholder) = createSlot(front)
        val contentsArgs = arrayOf(backContent, backPlaceholder, frontContent, frontPlaceholder, null)
        val contents = contentsConstructor.newInstance(*contentsArgs)

        val params = domainConstructor.parameterTypes
        args[0] = contents
        args[1] = resolveVisibility(symbols, request.visibility)
        args[2] = null
        args[3] = createCreationDate(params[3])
        args[4] = request.location?.let { createLocation(params[4], it) }
        args[5] = request.retakeCount
        args[6] = enumOrNamedSingleton(params[6], if (request.location == null) "Off" else "Approximate")
        args[7] = 0L
        args[8] = 0L
        args[9] = isMain
        args[10] = request.isLate
        args[11] = enumOrNamedSingleton(params[11], if (request.isLate) "Late" else "OnTime")
        args[12] = false
        args[13] = false
        // BeReal's gallery-video kind represents one video plus a still. Video pairs
        // must use its DualVideo classification so both videos and previews are sent.
        args[14] = !back.isVideo
        args[15] = request.caption.ifBlank { null }
        args[16] = emptyList<Any>()
        args[17] = ArrayList<Any>()
        // Gallery imports have no live camera orientation. Native media-gallery video
        // posts leave cameraFacingSide unset, matching rrk's n1c path in 3.97.0.
        args[18] = null
        args[19] = UUID.randomUUID().toString()
        args[20] = listOf(back, front).filter { it.isVideo }
            .mapNotNull { it.durationMs?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() }
            .maxOrNull()
        args[21] = emptyList<Any>()
        args[22] = null
        args[23] = null
        args[24] = null
        args[25] = null
        args[26] = true
        args[27] = false
        return domainConstructor.newInstance(*args)
    }

    private fun resolveVisibility(symbols: ResolvedSymbols, visibility: String): Any = when (visibility) {
        "friends" -> requireNotNull(symbols.friendsVisibility) { "Friends visibility value unresolved" }
        "friend-of-friends" -> requireNotNull(symbols.friendOfFriendsVisibility) {
            "Friends-of-friends visibility value unresolved"
        }
        "public" -> requireNotNull(symbols.globalVisibility) { "Global visibility value unresolved" }
        else -> error("Unsupported BeReal post visibility: $visibility")
    }

    @JvmStatic
    fun createCorePostDraft(domainModel: Any, symbols: ResolvedSymbols, postId: String): CorePostDraftRecord {
        val domainFields = symbols.postDomainModelFieldOrder
        require(domainFields.size == 28) { "SendPostDomainModel field order unresolved" }
        val domainValues = Array<Any?>(domainFields.size) { index -> domainFields[index].get(domainModel) }
        val constructor = requireNotNull(symbols.postCoreDraftConstructor) {
            "CorePostDraft constructor unresolved"
        }
        require(constructor.parameterTypes.size == 28) {
            "Unexpected CorePostDraft constructor arity=${constructor.parameterTypes.size}"
        }

        // The official mapper's constructor copies all SendPostDomainModel fields except scheduledInfo,
        // boxing the final comments/commercial flags in CorePostDraft.
        val args = arrayOfNulls<Any?>(28)
        args[0] = postId
        for (index in 0..24) args[index + 1] = domainValues[index]
        args[26] = java.lang.Boolean.valueOf(domainValues[26] as Boolean)
        args[27] = java.lang.Boolean.valueOf(domainValues[27] as Boolean)
        return CorePostDraftRecord(constructor.newInstance(*args), postId)
    }

    private fun createCreationDate(type: Class<*>): Any {
        val constructor = type.declaredConstructors.singleOrNull { ctor ->
            ctor.parameterTypes.contentEquals(arrayOf(Instant::class.java))
        } ?: error("${type.name} has no Instant-backed creation-date constructor")
        constructor.isAccessible = true
        return constructor.newInstance(Instant.now())
    }

    private fun createLocation(type: Class<*>, location: LocationData): Any {
        val coordinateType = Double::class.javaPrimitiveType
        val constructor = type.declaredConstructors.singleOrNull { ctor ->
            ctor.parameterTypes.contentEquals(arrayOf(coordinateType, coordinateType))
        } ?: error("${type.name} has no longitude/latitude constructor")
        constructor.isAccessible = true
        return constructor.newInstance(location.longitude, location.latitude)
    }

    private fun enumOrNamedSingleton(type: Class<*>, name: String): Any {
        if (type.isEnum) {
            val values = type.enumConstants ?: error("${type.name} has no enum values")
            return values.firstOrNull { (it as Enum<*>).name == name }
                ?: error("${type.name} has no enum constant $name")
        }

        val arrayMethods = type.declaredMethods.filter { method ->
            Modifier.isStatic(method.modifiers) && method.parameterCount == 0 &&
                method.returnType.isArray && method.returnType.componentType == type
        }
        for (method in arrayMethods) {
            method.isAccessible = true
            val values = method.invoke(null) as? Array<*> ?: continue
            values.firstOrNull { value -> value != null && value.toString() == name }?.let { return it }
        }

        val singletonFields = type.declaredFields.filter { field ->
            Modifier.isStatic(field.modifiers) && type.isAssignableFrom(field.type)
        }
        for (field in singletonFields) {
            field.isAccessible = true
            val value = field.get(null)
            if (value != null && value.toString() == name) return value
        }
        error("${type.name} has no value named $name")
    }
}

data class CorePostDraftRecord(val model: Any, val id: String)
