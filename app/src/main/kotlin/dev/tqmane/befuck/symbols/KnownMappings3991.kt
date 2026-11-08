package dev.tqmane.befuck.symbols

internal object KnownMappings3991 : HostMappings("3.99.1", 3603560L) {
    override val classes = mapOf(
        HostClass.RealMojiMenuItem to "lwi",
        HostClass.DualViewImageSize to "cq6",
        HostClass.HomeFeedItemEmitter to "j0a",
        HostClass.EmptyList to "g67",
        HostClass.MyRealMojiState to "loe",
        HostClass.HomeGridTileContainer to "ok9",
        HostClass.Unit to "fao",
        HostClass.BeRealImageLoader to "oj1",
        HostClass.DualViewInteractions to "dq6",
        HostClass.MyRealMojis to "moe",
        HostClass.HomeGridClick to "z32",
        HostClass.RealMojiMenuAction to "z1j",
        HostClass.RealMojiMenuEntry to "a2j",
        HostClass.SponsoredPostState to "j9j",
        HostClass.HomeFeedItem to "ru7",
        HostClass.MediaFlipState to "ep6",
        HostClass.HomeGridTile to "nsi",
        HostClass.DualViewPlayers to "jr6",
        HostClass.HomeFeed to "fw9",
        HostClass.HomeFeedMapper to "su7",
        HostClass.PostUploadScheduler to "i94",
        HostClass.BtsMedia to "hah",
        HostClass.PullDownGridCard to "ygi",
        HostClass.CorePost to "va5",
        HostClass.ResultSuccess to "kcn",
        HostClass.GridCardIconAction to "ogi",
        HostClass.User to "tno",
        HostClass.ImmutableListFactory to "jf8",
        HostClass.PostSender to "ajl",
        HostClass.SponsoredPost to "m8h",
        HostClass.RealMojis to "odh",
        HostClass.Function0 to "a49",
        HostClass.KoinApplicationHolder to "fcb",
        HostClass.CoroutineStart to "gxa",
        HostClass.PostReactions to "o8h",
        HostClass.RealMojiViewerItem to "v1j",
        HostClass.PublishedPost to "va5",
        HostClass.DualMediaRenderer to "nq6",
        HostClass.CameraCountdown to "ye7",
        HostClass.RealMojiSelection to "n2j",
        HostClass.Function1 to "e49",
        HostClass.DualViewConfig to "qp6",
        HostClass.PostFeedState to "r8h",
        HostClass.BlurredPostState to "bw7",
        HostClass.RegularPostState to "cw7",
        HostClass.MediaContainer to "z80",
        HostClass.Function2 to "g49",
        HostClass.LocationRepository to "pvb",
        HostClass.PostViewState to "dw7",
        HostClass.FeedRefreshReason to "nrl",
        HostClass.ImmutableList to "aqg",
        HostClass.FriendsOfFriendsItemEmitter to "sg8",
        HostClass.TimelineBlurredCard to "yj7",
        HostClass.CameraFacing to "ao7",
        HostClass.HomeFeedPost to "ew7",
        HostClass.UnsentPostRepository to "pc5",
        HostClass.RealMojiViewModel to "t2j",
        HostClass.Flow to "zb8",
        HostClass.CreatePostContinuation to "ske",
        HostClass.StableImageLoader to "n2n",
        HostClass.UnsentPost to "ybo",
        HostClass.HomeFeedContent to "gw7",
        HostClass.HomeGridBadge to "dsi",
        HostClass.PersistentList to "zia",
        HostClass.FlowOperators to "rzn",
        HostClass.CameraViewModel to "fr2",
        HostClass.UploadFailure to "iv4",
        HostClass.Continuation to "t35",
        HostClass.CurrentUserProvider to "jd9",
        HostClass.BeRealMedia to "dk1",
        HostClass.FeedBlurOptions to "jv7",
        HostClass.ContinuationImpl to "u35",
        HostClass.HomeFeedStateHolder to "ptj",
        HostClass.SecondaryCameraOriginParser to "hqo",
        HostClass.PagerSnapLayoutInfo to "dha",
        HostClass.CameraOriginParser to "r6o",
        HostClass.RealMojiRowState to "vji",
        HostClass.PostRepository to "yke",
        HostClass.HomeGridTileRenderer to "mk9",
        HostClass.DualViewData to "bq6",
        HostClass.ImageData to "ipm",
    )

    override val methods = mapOf(
        HostMethod.ImmutableListCopy to "D",
        HostMethod.KoinApplication to "B",
        HostMethod.FlowFirst to "T",
        HostMethod.SecondaryCameraOrigin to "l",
        HostMethod.CameraOrigin to "W",
        HostMethod.KoinResolve to "e",
        HostMethod.ComposeTraceBegin to "A",
    )

    override val postContentsArity = 6
    override val publishedPostIdField = "a"
    override val gridCardBlurArgument = 13
    override val gridClickBranch = 13
    override val mediaFlipBranch = 2
    override val mediaOrientationArgument = 10
    override fun postMapSymbols(loader: ClassLoader) = PostMapMappings3991.resolve(loader)

    override fun smsRequestConstructor(loader: ClassLoader) = resolveSmsRequestConstructor(loader, "tjk")

    override fun realMojiMenuClick(loader: ClassLoader): java.lang.reflect.Method =
        Class.forName("tdg", false, loader).getDeclaredMethod("invoke", Any::class.java)

    override fun realMojiMenuItem(loader: ClassLoader, label: String, icon: Int?): Any =
        type(HostClass.RealMojiMenuItem, loader)
            .getDeclaredConstructor(String::class.java, String::class.java, Int::class.javaObjectType, String::class.java, Boolean::class.javaPrimitiveType)
            .newInstance(label, null, icon, label, false)

    override fun repackagedLicenseCheck(loader: ClassLoader): java.lang.reflect.Method? = runCatching {
        Class.forName("com.pairip.licensecheck.LicenseClient", false, loader)
            .getDeclaredMethod("checkLicense", android.content.Context::class.java)
            .takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE }
            ?.apply { isAccessible = true }
    }.getOrNull()

    override fun resolveFeedAdSelector(loader: ClassLoader): java.lang.reflect.Method? = runCatching {
        val owner = Class.forName("nq7", false, loader)
        owner.getDeclaredMethod("d", Class.forName("pq", false, loader)).takeIf {
            it.returnType == Class.forName("np", false, loader) && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true }
    }.getOrNull()

    override fun resolveNativeAdComposable(loader: ClassLoader): java.lang.reflect.Method? =
        staticMethod(loader, "h00", "a", "sqe", "a49", "a49", "a49", "a49", "a49",
            "androidx.compose.ui.Modifier", "androidx.compose.ui.graphics.Shape",
            "androidx.compose.runtime.internal.ComposableLambdaImpl", "androidx.compose.runtime.Composer", "int", "int")

    // The timeline no longer has the old canBlur argument; media hooks handle its content.
    override fun resolveTimelineBlurredCardComposable(classLoader: ClassLoader): java.lang.reflect.Method? = null

    override fun resolvePullDownGridCardComposable(classLoader: ClassLoader): java.lang.reflect.Method? =
        staticMethod(classLoader, "ygi", "b",
            "n2n", "java.lang.String", "java.lang.String", "z80", "bq6",
            "dha", "a49", "a49", "a49", "e49",
            "vji", "ogi", "boolean", "boolean", "a49",
            "boolean", "a49", "boolean", "androidx.compose.ui.Modifier", "androidx.compose.runtime.Composer",
            "int",
        )

    override fun resolvePullDownGridMediaComposable(classLoader: ClassLoader): java.lang.reflect.Method? =
        staticMethod(classLoader, "nq6", "a",
            "bq6", "androidx.compose.ui.Modifier", "androidx.compose.runtime.State", "boolean", "qp6",
            "jr6", "e49", "androidx.compose.runtime.State", "androidx.compose.runtime.State", "androidx.compose.runtime.State",
            "androidx.compose.runtime.State", "a49", "e49", "a49", "e49",
            "a49", "e49", "e49", "boolean", "n2n",
            "e49", "e49", "cq6", "boolean", "android.graphics.Bitmap\$Config",
            "dq6", "e49", "a49", "boolean", "boolean",
            "boolean", "a49", "java.lang.String", "androidx.compose.runtime.Composer", "int",
            "int", "int", "int", "int", "int",
        )

    override fun mediaOrientationMethod(loader: ClassLoader): java.lang.reflect.Method? =
        staticMethod(loader, "nq6", "b",
            "bq6", "androidx.compose.ui.Modifier", "jr6", "x62", "e49",
            "e49", "a49", "e49", "e49", "e49",
            "boolean", "n2n", "androidx.compose.runtime.State", "androidx.compose.runtime.State", "androidx.compose.runtime.State",
            "boolean", "e49", "e49", "dq6", "qp6",
            "androidx.compose.runtime.State", "cq6", "androidx.compose.runtime.State", "boolean", "a49",
            "a49", "android.graphics.Bitmap\$Config", "e49", "a49", "boolean",
            "boolean", "java.lang.String", "androidx.compose.runtime.Composer", "int", "int",
            "int", "int",
        )

    private fun staticMethod(loader: ClassLoader, owner: String, name: String, vararg parameters: String): java.lang.reflect.Method? = runCatching {
        fun type(name: String): Class<*> = when (name) {
            "boolean" -> Boolean::class.javaPrimitiveType!!
            "int" -> Int::class.javaPrimitiveType!!
            else -> Class.forName(name, false, loader)
        }
        type(owner).getDeclaredMethod(name, *parameters.map(::type).toTypedArray()).takeIf {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE
        }?.apply { isAccessible = true }
    }.getOrNull()
}
