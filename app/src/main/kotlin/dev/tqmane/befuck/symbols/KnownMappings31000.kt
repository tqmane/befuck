package dev.tqmane.befuck.symbols

import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Known symbol mappings for BeReal 3.100.0 (3604882).
 */
internal object KnownMappings31000 : HostMappings("3.100.0", 3604882L) {
    override val classes = mapOf(
        HostClass.RealMojiMenuItem to "t0j",
        HostClass.DualViewImageSize to "ns6",
        HostClass.HomeFeedItemEmitter to "a4a",
        HostClass.EmptyList to "p87",
        HostClass.MyRealMojiState to "mse",
        HostClass.HomeGridTileContainer to "ho9",
        HostClass.Unit to "sfo",
        HostClass.BeRealImageLoader to "yha",
        HostClass.DualViewInteractions to "os6",
        HostClass.MyRealMojis to "nse",
        HostClass.HomeGridClick to "s42",
        HostClass.RealMojiMenuAction to "k6j",
        HostClass.RealMojiMenuEntry to "l6j",
        HostClass.SponsoredPostState to "rdj",
        HostClass.HomeFeedItem to "ay7",
        HostClass.MediaFlipState to "pr6",
        HostClass.HomeGridTile to "vwi",
        HostClass.DualViewPlayers to "ut6",
        HostClass.HomeFeed to "yz9",
        HostClass.HomeFeedMapper to "by7",
        HostClass.PostUploadScheduler to "ob4",
        HostClass.BtsMedia to "ueh",
        HostClass.PullDownGridCard to "kli",
        HostClass.CorePost to "bd5",
        HostClass.ResultSuccess to "hhn",
        HostClass.GridCardIconAction to "ali",
        HostClass.User to "nto",
        HostClass.ImmutableListFactory to "dqc",
        HostClass.PostSender to "cpl",
        HostClass.SponsoredPost to "ych",
        HostClass.RealMojis to "yhh",
        HostClass.Function0 to "p79",
        HostClass.KoinApplicationHolder to "ggb",
        HostClass.CoroutineStart to "h1b",
        HostClass.PostReactions to "adh",
        HostClass.RealMojiViewerItem to "g6j",
        HostClass.PublishedPost to "bd5",
        HostClass.DualMediaRenderer to "ys6",
        HostClass.CameraCountdown to "hi7",
        HostClass.RealMojiSelection to "v6j",
        HostClass.Function1 to "t79",
        HostClass.DualViewConfig to "bs6",
        HostClass.PostFeedState to "ddh",
        HostClass.BlurredPostState to "zch",
        HostClass.RegularPostState to "bdh",
        HostClass.MediaContainer to "xo",
        HostClass.Function2 to "v79",
        HostClass.LocationRepository to "gzb",
        HostClass.PostViewState to "bah",
        HostClass.FeedRefreshReason to "sxl",
        HostClass.ImmutableList to "mug",
        HostClass.FriendsOfFriendsItemEmitter to "yj8",
        HostClass.TimelineBlurredCard to "a18",
        HostClass.CameraFacing to "mr7",
        HostClass.HomeFeedPost to "xx7",
        HostClass.UnsentPostRepository to "we5",
        HostClass.RealMojiViewModel to "b7j",
        HostClass.Flow to "hf8",
        HostClass.CreatePostContinuation to "roe",
        HostClass.StableImageLoader to "e8n",
        HostClass.UnsentPost to "kho",
        HostClass.HomeFeedContent to "pz7",
        HostClass.HomeGridBadge to "mwi",
        HostClass.PersistentList to "xma",
        HostClass.FlowOperators to "ezo",
        HostClass.CameraViewModel to "kt2",
        HostClass.UploadFailure to "sa9",
        HostClass.Continuation to "z55",
        HostClass.CurrentUserProvider to "qte",
        HostClass.BeRealMedia to "cl1",
        HostClass.FeedBlurOptions to "sy7",
        HostClass.ContinuationImpl to "a65",
        HostClass.HomeFeedStateHolder to "cyj",
        HostClass.SecondaryCameraOriginParser to "g5o",
        HostClass.PagerSnapLayoutInfo to "ala",
        HostClass.CameraOriginParser to "a7o",
        HostClass.RealMojiRowState to "goi",
        HostClass.PostRepository to "xoe",
        HostClass.HomeGridTileRenderer to "fo9",
        HostClass.DualViewData to "ms6",
        HostClass.ImageData to "zum",
    )

    override val methods = mapOf(
        HostMethod.ImmutableListCopy to "I",
        HostMethod.KoinApplication to "B",
        HostMethod.FlowFirst to "u",
        HostMethod.SecondaryCameraOrigin to "p",
        HostMethod.CameraOrigin to "m",
        HostMethod.KoinResolve to "e",
        HostMethod.ComposeTraceBegin to "A",
    )

    override val postContentsArity = 6
    override val publishedPostIdField = "a"
    override val gridCardBlurArgument = 13
    override val gridClickBranch = 13
    override val mediaFlipBranch = 2
    override val mediaOrientationArgument = 10
    override fun postMapSymbols(loader: ClassLoader) = PostMapMappings31000.resolve(loader)

    override fun smsRequestConstructor(loader: ClassLoader): Constructor<*>? =
        resolveSmsRequestConstructor(loader, "mok")

    override fun realMojiMenuClick(loader: ClassLoader): Method =
        Class.forName("igg", false, loader).getDeclaredMethod("invoke", Any::class.java)

    override fun realMojiMenuItem(loader: ClassLoader, label: String, icon: Int?): Any =
        realMojiMenuItemWithIconBeforeAccessibility(loader, label, icon)

    override fun repackagedLicenseCheck(loader: ClassLoader): Method? =
        resolvePairIpLicenseCheck(loader)

    override fun resolveFeedAdSelector(loader: ClassLoader): Method? =
        resolveFeedAdSelector(loader, "yt7", "d", "nq", "lp")

    override fun resolveNativeAdComposable(loader: ClassLoader): Method? = staticVoidMethod(
        loader,
        "q00",
        "a",
        "wue",
        "p79",
        "p79",
        "p79",
        "p79",
        "p79",
        "androidx.compose.ui.Modifier",
        "androidx.compose.ui.graphics.Shape",
        "androidx.compose.runtime.internal.ComposableLambdaImpl",
        "androidx.compose.runtime.Composer",
        "int",
        "int",
    )

    override fun resolveTimelineBlurredCardComposable(classLoader: ClassLoader): Method? = null

    override fun resolvePullDownGridCardComposable(classLoader: ClassLoader): Method? = staticVoidMethod(
        classLoader,
        "kli",
        "b",
        "e8n",
        "java.lang.String",
        "java.lang.String",
        "xo",
        "ms6",
        "ala",
        "p79",
        "p79",
        "p79",
        "t79",
        "goi",
        "ali",
        "boolean",
        "boolean",
        "p79",
        "boolean",
        "p79",
        "boolean",
        "androidx.compose.ui.Modifier",
        "androidx.compose.runtime.Composer",
        "int",
    )

    override fun resolvePullDownGridMediaComposable(classLoader: ClassLoader): Method? = staticVoidMethod(
        classLoader,
        "ys6",
        "a",
        "ms6",
        "androidx.compose.ui.Modifier",
        "androidx.compose.runtime.State",
        "boolean",
        "bs6",
        "ut6",
        "t79",
        "androidx.compose.runtime.State",
        "androidx.compose.runtime.State",
        "androidx.compose.runtime.State",
        "androidx.compose.runtime.State",
        "p79",
        "t79",
        "p79",
        "t79",
        "p79",
        "t79",
        "t79",
        "boolean",
        "e8n",
        "t79",
        "t79",
        "ns6",
        "boolean",
        "android.graphics.Bitmap\$Config",
        "os6",
        "t79",
        "p79",
        "boolean",
        "boolean",
        "boolean",
        "p79",
        "java.lang.String",
        "androidx.compose.runtime.Composer",
        "int",
        "int",
        "int",
        "int",
        "int",
        "int",
    )

    override fun mediaOrientationMethod(loader: ClassLoader): Method? = staticVoidMethod(
        loader,
        "ys6",
        "b",
        "ms6",
        "androidx.compose.ui.Modifier",
        "ut6",
        "s72",
        "t79",
        "t79",
        "p79",
        "t79",
        "t79",
        "t79",
        "boolean",
        "e8n",
        "androidx.compose.runtime.State",
        "androidx.compose.runtime.State",
        "androidx.compose.runtime.State",
        "boolean",
        "t79",
        "t79",
        "os6",
        "bs6",
        "androidx.compose.runtime.State",
        "ns6",
        "androidx.compose.runtime.State",
        "boolean",
        "p79",
        "p79",
        "android.graphics.Bitmap\$Config",
        "t79",
        "p79",
        "boolean",
        "boolean",
        "java.lang.String",
        "androidx.compose.runtime.Composer",
        "int",
        "int",
        "int",
        "int",
    )

    override fun uploadWorkerDiagnosticMethods(loader: ClassLoader): List<Method> =
        uploadWorkerClass(loader).declaredMethods.filter { method ->
            val p = method.parameterTypes
            (method.name == "f" || method.name == "g" || method.name == "i") &&
                ((p.size == 3 && p[0] == String::class.java && Throwable::class.java.isAssignableFrom(p[1]) &&
                    Map::class.java.isAssignableFrom(p[2])) ||
                    (p.size == 2 && p[0] == String::class.java && Throwable::class.java.isAssignableFrom(p[1])))
        }.onEach { it.isAccessible = true }

    override fun officialPostObservationContract(loader: ClassLoader): OfficialPostObservationContract {
        val corePost = type(HostClass.CorePost, loader)
        val mediaModel = type(HostClass.BeRealMedia, loader)
        val createContinuation = type(HostClass.CreatePostContinuation, loader)
        val postRepository = type(HostClass.PostRepository, loader)
        val unsentRepository = type(HostClass.UnsentPostRepository, loader)
        val unsentPost = type(HostClass.UnsentPost, loader)
        return OfficialPostObservationContract(
            currentUserClass = type(HostClass.User, loader),
            mediaModelClass = mediaModel,
            mediaAspectRatioField = mediaModel.getDeclaredField("aspectRatio").apply { isAccessible = true },
            createContinuationClass = createContinuation,
            pendingCoreField = createContinuation.getDeclaredField("r").apply { isAccessible = true },
            publishMethods = postRepository.declaredMethods.filter { m ->
                m.name == "e" && m.parameterCount == 5 && m.parameterTypes[0] == corePost
            },
            unsentSaveMethods = unsentRepository.declaredMethods.filter { m ->
                m.name == "q" && m.parameterCount == 2 && m.parameterTypes[0] == unsentPost
            },
        )
    }

    override fun resolveCurrentUserProvider(classLoader: ClassLoader): Pair<Class<*>?, Method?> {
        return try {
            val provider = Class.forName(className(HostClass.CurrentUserProvider), false, classLoader)
            val method = provider.declaredMethods.singleOrNull { candidate ->
                candidate.name == "h" &&
                    candidate.returnType == Any::class.java &&
                    candidate.parameterCount == 1 &&
                    isContinuationContract(candidate.parameterTypes[0])
            }?.apply { isAccessible = true }
            provider to method
        } catch (_: Throwable) {
            null to null
        }
    }

    override fun feedBlurModelRewriter(loader: ClassLoader): FeedBlurModelRewriter? = null
}
