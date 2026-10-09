package dev.tqmane.befuck.symbols

import dev.tqmane.befuck.runtime.RuntimeKnowledge

object KnownMappings {
    @JvmStatic
    @JvmOverloads
    fun find(name: String?, code: Long = RuntimeKnowledge.versionCode): HostMappings? {
        val mapping = when (code) {
            3597523L -> KnownMappings3970
            3599414L -> KnownMappings3971
            else -> return null
        }
        return mapping.takeIf { it.isKnownVersion(name, code) }
    }

    @JvmStatic
    @JvmOverloads
    fun isSupportedVersion(name: String?, code: Long = RuntimeKnowledge.versionCode) = find(name, code) != null

    @JvmStatic
    fun current(): HostMappings = requireNotNull(find(RuntimeKnowledge.versionName, RuntimeKnowledge.versionCode)) {
        "Unsupported BeReal version: ${RuntimeKnowledge.versionName} (${RuntimeKnowledge.versionCode})"
    }

    // StartupLauncher runs before Application.attach supplies PackageInfo.
    @JvmStatic
    fun protectedRuntime(loader: ClassLoader): HostMappings? = listOf(KnownMappings3970, KnownMappings3971)
        .singleOrNull { mapping -> mapping.lifecycleBindings.firstOrNull()?.isPresent(loader) == true }
}

enum class HostClass {
    RealMojiMenuItem,
    DualViewImageSize,
    HomeFeedItemEmitter,
    EmptyList,
    MyRealMojiState,
    HomeGridTileContainer,
    Unit,
    BeRealImageLoader,
    DualViewInteractions,
    MyRealMojis,
    HomeGridClick,
    RealMojiMenuAction,
    RealMojiMenuEntry,
    SponsoredPostState,
    HomeFeedItem,
    MediaFlipState,
    HomeGridTile,
    DualViewPlayers,
    HomeFeed,
    HomeFeedMapper,
    PostUploadScheduler,
    BtsMedia,
    PullDownGridCard,
    CorePost,
    ResultSuccess,
    GridCardIconAction,
    User,
    ImmutableListFactory,
    PostSender,
    SponsoredPost,
    RealMojis,
    Function0,
    KoinApplicationHolder,
    CoroutineStart,
    PostReactions,
    RealMojiViewerItem,
    PublishedPost,
    DualMediaRenderer,
    CameraCountdown,
    RealMojiSelection,
    Function1,
    DualViewConfig,
    PostFeedState,
    BlurredPostState,
    RegularPostState,
    MediaContainer,
    Function2,
    LocationRepository,
    PostViewState,
    FeedRefreshReason,
    ImmutableList,
    FriendsOfFriendsItemEmitter,
    TimelineBlurredCard,
    CameraFacing,
    HomeFeedPost,
    UnsentPostRepository,
    RealMojiViewModel,
    Flow,
    CreatePostContinuation,
    StableImageLoader,
    UnsentPost,
    HomeFeedContent,
    HomeGridBadge,
    PersistentList,
    FlowOperators,
    CameraViewModel,
    UploadFailure,
    Continuation,
    CurrentUserProvider,
    BeRealMedia,
    FeedBlurOptions,
    GridCardAction,
    ContinuationImpl,
    HomeFeedStateHolder,
    SecondaryCameraOriginParser,
    PagerSnapLayoutInfo,
    CameraOriginParser,
    RealMojiRowState,
    PostRepository,
    HomeGridTileRenderer,
    DualViewData,
    ImageData,
}

enum class HostMethod { ImmutableListCopy, KoinApplication, FlowFirst, SecondaryCameraOrigin, CameraOrigin, KoinResolve }
