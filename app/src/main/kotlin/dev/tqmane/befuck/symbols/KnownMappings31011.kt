package dev.tqmane.befuck.symbols

import java.lang.reflect.Constructor
import java.lang.reflect.Method

internal object KnownMappings31011 : HostMappings("3.101.1", 3606462L) {
    override val classes = mapOf(
        HostClass.RealMojiMenuItem to "y6j",
        HostClass.DualViewImageSize to "xw6",
        HostClass.HomeFeedItemEmitter to "b9a",
        HostClass.EmptyList to "bd7",
        HostClass.MyRealMojiState to "aye",
        HostClass.HomeGridTileContainer to "dt9",
        HostClass.Unit to "zmo",
        HostClass.BeRealImageLoader to "yma",
        HostClass.DualViewInteractions to "yw6",
        HostClass.MyRealMojis to "bye",
        HostClass.HomeGridClick to "z42",
        HostClass.RealMojiMenuAction to "qcj",
        HostClass.RealMojiMenuEntry to "rcj",
        HostClass.SponsoredPostState to "yjj",
        HostClass.HomeFeedItem to "q28",
        HostClass.MediaFlipState to "yv6",
        HostClass.HomeGridTile to "b3j",
        HostClass.DualViewPlayers to "ey6",
        HostClass.HomeFeed to "a5a",
        HostClass.HomeFeedMapper to "r28",
        HostClass.PostUploadScheduler to "lc4",
        HostClass.BtsMedia to "skh",
        HostClass.PullDownGridCard to "nri",
        HostClass.CorePost to "ch5",
        HostClass.ResultSuccess to "mon",
        HostClass.GridCardIconAction to "cri",
        HostClass.User to "y0p",
        HostClass.ImmutableListFactory to "rgd",
        HostClass.PostSender to "uvl",
        HostClass.SponsoredPost to "xih",
        HostClass.RealMojis to "xnh",
        HostClass.Function0 to "jc9",
        HostClass.KoinApplicationHolder to "dlb",
        HostClass.CoroutineStart to "b6b",
        HostClass.PostReactions to "zih",
        HostClass.RealMojiViewerItem to "mcj",
        HostClass.PublishedPost to "ch5",
        HostClass.DualMediaRenderer to "ix6",
        HostClass.CameraCountdown to "wm7",
        HostClass.RealMojiSelection to "bdj",
        HostClass.Function1 to "nc9",
        HostClass.DualViewConfig to "lw6",
        HostClass.PostFeedState to "cjh",
        HostClass.BlurredPostState to "a48",
        HostClass.RegularPostState to "b48",
        HostClass.MediaContainer to "j80",
        HostClass.Function2 to "pc9",
        HostClass.LocationRepository to "i4c",
        HostClass.PostViewState to "c48",
        HostClass.FeedRefreshReason to "o4m",
        HostClass.ImmutableList to "l0h",
        HostClass.FriendsOfFriendsItemEmitter to "no8",
        HostClass.TimelineBlurredCard to "q58",
        HostClass.CameraFacing to "bw7",
        HostClass.HomeFeedPost to "d48",
        HostClass.UnsentPostRepository to "xi5",
        HostClass.RealMojiViewModel to "hdj",
        HostClass.Flow to "xj8",
        HostClass.CreatePostContinuation to "gue",
        HostClass.StableImageLoader to "cfn",
        HostClass.UnsentPost to "roo",
        HostClass.HomeFeedContent to "f48",
        HostClass.HomeGridBadge to "s2j",
        HostClass.PersistentList to "vra",
        HostClass.FlowOperators to "q6p",
        HostClass.CameraViewModel to "du2",
        HostClass.UploadFailure to "mf9",
        HostClass.Continuation to "z95",
        HostClass.CurrentUserProvider to "fze",
        HostClass.BeRealMedia to "il1",
        HostClass.FeedBlurOptions to "i38",
        HostClass.ContinuationImpl to "aa5",
        HostClass.HomeFeedStateHolder to "p4k",
        HostClass.SecondaryCameraOriginParser to "heo",
        HostClass.PagerSnapLayoutInfo to "zpa",
        HostClass.CameraOriginParser to "ieo",
        HostClass.RealMojiRowState to "jui",
        HostClass.PostRepository to "mue",
        HostClass.HomeGridTileRenderer to "bt9",
        HostClass.DualViewData to "ww6",
        HostClass.ImageData to "a2n",
    )

    override val methods = mapOf(
        HostMethod.ImmutableListCopy to "D",
        HostMethod.KoinApplication to "B",
        HostMethod.FlowFirst to "r",
        HostMethod.SecondaryCameraOrigin to "w",
        HostMethod.CameraOrigin to "n",
        HostMethod.KoinResolve to "e",
        HostMethod.ComposeTraceBegin to "A",
    )

    override val postContentsArity = 6
    override val publishedPostIdField = "a"
    override val gridCardBlurArgument = 13
    override val gridCardClickReplacement: Pair<Int, Int>? = null
    override val gridClickBranch = 13
    override val mediaFlipBranch = 2
    override val mediaOrientationArgument = 10

    override fun postMapSymbols(loader: ClassLoader) = PostMapMappings31011.resolve(loader)

    override fun smsRequestConstructor(loader: ClassLoader): Constructor<*>? =
        resolveSmsRequestConstructor(loader, "uuk")

    override fun realMojiMenuClick(loader: ClassLoader): Method =
        Class.forName("scg", false, loader).getDeclaredMethod("invoke", Any::class.java)

    override fun realMojiMenuItem(loader: ClassLoader, label: String, icon: Int?): Any =
        realMojiMenuItemWithIconBeforeAccessibility(loader, label, icon)

    override fun repackagedLicenseCheck(loader: ClassLoader): Method? =
        resolvePairIpLicenseCheck(loader)

    override fun resolveFeedAdSelector(loader: ClassLoader): Method? =
        resolveFeedAdSelector(loader, "oy7", "d", "lq", "jp")

    override fun resolveNativeAdComposable(loader: ClassLoader): Method? = staticVoidMethod(
        loader,
        "x00",
        "a",
        "n0f",
        "jc9",
        "jc9",
        "jc9",
        "jc9",
        "jc9",
        "androidx.compose.ui.Modifier",
        "androidx.compose.ui.graphics.Shape",
        "androidx.compose.runtime.internal.ComposableLambdaImpl",
        "androidx.compose.runtime.Composer",
        "int",
        "int",
    )

    // The timeline no longer has the old canBlur argument; media hooks handle its content.
    override fun resolveTimelineBlurredCardComposable(classLoader: ClassLoader): Method? = null

    override fun resolvePullDownGridCardComposable(classLoader: ClassLoader): Method? = staticVoidMethod(
        classLoader,
        "nri",
        "b",
        "cfn",
        "java.lang.String",
        "java.lang.String",
        "j80",
        "ww6",
        "zpa",
        "jc9",
        "jc9",
        "jc9",
        "nc9",
        "jui",
        "cri",
        "boolean",
        "boolean",
        "jc9",
        "boolean",
        "jc9",
        "boolean",
        "androidx.compose.ui.Modifier",
        "androidx.compose.runtime.Composer",
        "int",
    )

    override fun resolvePullDownGridMediaComposable(classLoader: ClassLoader): Method? = staticVoidMethod(
        classLoader,
        "ix6",
        "a",
        "ww6",
        "androidx.compose.ui.Modifier",
        "androidx.compose.runtime.State",
        "boolean",
        "lw6",
        "ey6",
        "nc9",
        "androidx.compose.runtime.State",
        "androidx.compose.runtime.State",
        "androidx.compose.runtime.State",
        "androidx.compose.runtime.State",
        "jc9",
        "nc9",
        "jc9",
        "nc9",
        "jc9",
        "nc9",
        "nc9",
        "boolean",
        "cfn",
        "nc9",
        "nc9",
        "xw6",
        "boolean",
        "android.graphics.Bitmap\$Config",
        "yw6",
        "nc9",
        "jc9",
        "boolean",
        "boolean",
        "boolean",
        "jc9",
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
        "ix6",
        "b",
        "ww6",
        "androidx.compose.ui.Modifier",
        "ey6",
        "e82",
        "nc9",
        "nc9",
        "jc9",
        "nc9",
        "nc9",
        "nc9",
        "boolean",
        "cfn",
        "androidx.compose.runtime.State",
        "androidx.compose.runtime.State",
        "androidx.compose.runtime.State",
        "boolean",
        "nc9",
        "nc9",
        "yw6",
        "lw6",
        "androidx.compose.runtime.State",
        "xw6",
        "androidx.compose.runtime.State",
        "boolean",
        "jc9",
        "jc9",
        "android.graphics.Bitmap\$Config",
        "nc9",
        "jc9",
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
}
