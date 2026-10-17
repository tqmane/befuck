package dev.tqmane.befuck.symbols

internal object KnownMappings3971 : HostMappings("3.97.1", 3599414L) {
    override val classes = mapOf(
        HostClass.RealMojiMenuItem to "t7i",
        HostClass.DualViewImageSize to "dh6",
        HostClass.HomeFeedItemEmitter to "om9",
        HostClass.EmptyList to "ew6",
        HostClass.MyRealMojiState to "k7e",
        HostClass.HomeGridTileContainer to "k89",
        HostClass.Unit to "whn",
        HostClass.BeRealImageLoader to "bh1",
        HostClass.DualViewInteractions to "eh6",
        HostClass.MyRealMojis to "l7e",
        HostClass.HomeGridClick to "d32",
        HostClass.RealMojiMenuAction to "wdi",
        HostClass.RealMojiMenuEntry to "xdi",
        HostClass.SponsoredPostState to "yki",
        HostClass.HomeFeedItem to "hk7",
        HostClass.MediaFlipState to "dt",
        HostClass.HomeGridTile to "y3i",
        HostClass.DualViewPlayers to "ii6",
        HostClass.HomeFeed to "tj9",
        HostClass.HomeFeedMapper to "ik7",
        HostClass.PostUploadScheduler to "f24",
        HostClass.BtsMedia to "zmg",
        HostClass.PullDownGridCard to "zsh",
        HostClass.CorePost to "j35",
        HostClass.ResultSuccess to "alm",
        HostClass.GridCardIconAction to "avh",
        HostClass.User to "cvn",
        HostClass.ImmutableListFactory to "kd5",
        HostClass.PostSender to "ctk",
        HostClass.SponsoredPost to "clg",
        HostClass.RealMojis to "cqg",
        HostClass.Function0 to "rr8",
        HostClass.KoinApplicationHolder to "wwa",
        HostClass.CoroutineStart to "xha",
        HostClass.PostReactions to "elg",
        HostClass.RealMojiViewerItem to "fei",
        HostClass.PublishedPost to "egg",
        HostClass.DualMediaRenderer to "ph6",
        HostClass.CameraCountdown to "p47",
        HostClass.RealMojiSelection to "gei",
        HostClass.Function1 to "vr8",
        HostClass.DualViewConfig to "rg6",
        HostClass.PostFeedState to "hlg",
        HostClass.BlurredPostState to "rl7",
        HostClass.RegularPostState to "sl7",
        HostClass.MediaContainer to "pn1",
        HostClass.Function2 to "xr8",
        HostClass.LocationRepository to "afb",
        HostClass.PostViewState to "tl7",
        HostClass.FeedRefreshReason to "j1l",
        HostClass.ImmutableList to "k2g",
        HostClass.FriendsOfFriendsItemEmitter to "u58",
        HostClass.TimelineBlurredCard to "t97",
        HostClass.CameraFacing to "ud7",
        HostClass.HomeFeedPost to "ul7",
        HostClass.UnsentPostRepository to "u45",
        HostClass.RealMojiViewModel to "lei",
        HostClass.Flow to "w18",
        HostClass.CreatePostContinuation to "c4e",
        HostClass.StableImageLoader to "mbm",
        HostClass.UnsentPost to "ojn",
        HostClass.HomeFeedContent to "wl7",
        HostClass.HomeGridBadge to "o3i",
        HostClass.PersistentList to "h4a",
        HostClass.FlowOperators to "nbk",
        HostClass.CameraViewModel to "un2",
        HostClass.UploadFailure to "wo4",
        HostClass.Continuation to "xw4",
        HostClass.CurrentUserProvider to "e19",
        HostClass.BeRealMedia to "wh1",
        HostClass.FeedBlurOptions to "zk7",
        HostClass.GridCardAction to "psh",
        HostClass.ContinuationImpl to "yw4",
        HostClass.HomeFeedStateHolder to "q4j",
        HostClass.SecondaryCameraOriginParser to "q7n",
        HostClass.PagerSnapLayoutInfo to "k2a",
        HostClass.CameraOriginParser to "r7n",
        HostClass.RealMojiRowState to "rdi",
        HostClass.PostRepository to "i4e",
        HostClass.HomeGridTileRenderer to "i89",
        HostClass.DualViewData to "ch6",
        HostClass.ImageData to "syl",
    )

    override val methods = mapOf(
        HostMethod.ImmutableListCopy to "N",
        HostMethod.KoinApplication to "B",
        HostMethod.FlowFirst to "s",
        HostMethod.SecondaryCameraOrigin to "O",
        HostMethod.CameraOrigin to "T",
        HostMethod.KoinResolve to "e",
    )


    // Verified SHA-256 of the original Google Play-signed 3.97.1 base APK's DER
    // signing certificate (APK Signing Scheme v2/v3); same signer as 3.97.0.
    override val signingCertificateSha256 = "Lv29c48/2ukB7LlurS17mx7m0s6rkRvfexo9+BLzz8Q="

    // Unlike 3.97.0's VMRunner, 3.97.1 installs PairIP through
    // com.pairip.application.Application.attachBaseContext and its ContentProvider.
    // Both enter this static method before normal application startup.
    override fun repackagedLicenseCheck(loader: ClassLoader): java.lang.reflect.Method? = runCatching {
        Class.forName("com.pairip.licensecheck.LicenseClient", false, loader)
            .getDeclaredMethod("checkLicense", android.content.Context::class.java)
            .takeIf { method ->
                java.lang.reflect.Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE
            }?.apply { isAccessible = true }
    }.getOrNull()

    // BeReal 3.97.1: eg7.c(lp): ko? is the nullable feed-ad selector for both
    // native and MREC clients. Returning null is its own normal no-ad result.
    // Guard the method shape before installing any hook; do not reuse this mapping
    // for other versions or hook the loading coroutine (which must complete).
    override fun resolveFeedAdSelector(loader: ClassLoader): java.lang.reflect.Method? = runCatching {
        val selector = Class.forName("eg7", false, loader)
        val placement = Class.forName("lp", false, loader)
        val ad = Class.forName("ko", false, loader)
        selector.getDeclaredMethod("c", placement).takeIf { method ->
            method.returnType == ad && !java.lang.reflect.Modifier.isStatic(method.modifiers)
        }?.apply { isAccessible = true }
    }.getOrNull()
}
