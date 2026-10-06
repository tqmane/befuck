package dev.tqmane.befuck.symbols

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Constructor

class ResolvedSymbols internal constructor(
    val packageName: String,
    val versionName: String?,
    val sourceIdentity: String,
    val dexKitAvailable: Boolean,
    private val stringFields: Map<String, Field>,
    val cameraCountdownComposableMethod: Method?,
    val feedMediaSymbols: FeedMediaSymbols?,
    val timelineBlurredCardComposableMethod: Method?,
    val pullDownGridCardComposableMethod: Method?,
    val pullDownGridMediaComposableMethod: Method?,
    val homeGridPostTileComposableMethod: Method?,
    val homeFeedCanBlurMapperMethod: Method?,
    val homeFeedItemEmitterMethod: Method?,
    val friendsOfFriendsFeedItemEmitterMethod: Method?,
    val postDomainModelClass: Class<*>?,
    val postDomainModelConstructor: Constructor<*>?,
    val postDomainModelFieldOrder: List<Field>,
    val postContentsClass: Class<*>?,
    val postContentsConstructor: Constructor<*>?,
    val postContentsFieldOrder: List<Field>,
    val postMediaClass: Class<*>?,
    val postMediaConstructor: Constructor<*>?,
    val postCoreDraftClass: Class<*>?,
    val postCoreDraftConstructor: Constructor<*>?,
    val sendPostCoroutineConstructor: Constructor<*>?,
    val sendPostRepositoryInterface: Class<*>?,
    val postUploadWorkerClass: Class<*>?,
    val sendDraftMethod: Method?,
    val friendsVisibility: Any?,
    val friendOfFriendsVisibility: Any?,
    val globalVisibility: Any?,
    val currentUserProviderClass: Class<*>?,
    val currentUserProviderMethod: Method?,
    val currentUserUidField: Field?,
    val cameraViewModelClass: Class<*>?,
    val cameraFacingEnumClass: Class<*>?,
    val cameraBindConcurrentMethod: Method?,
    val cameraOriginParserMethods: List<Method>,
    val locationRepositoryClass: Class<*>?,
    val locationRequestMethod: Method?,
    val locationClientGetter: Method?,
    val diagnostics: List<String>,
) {
    fun stringField(symbol: String): Field? = stringFields[symbol]

    fun resolvedStringSymbols(): Set<String> = stringFields.keys
}
