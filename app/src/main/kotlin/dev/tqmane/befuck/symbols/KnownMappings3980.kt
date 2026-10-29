package dev.tqmane.befuck.symbols

internal object KnownMappings3980 : HostMappings("3.98.0", 3600852L) {
    override val classes = mapOf(
        HostClass.FeedPostRemoteModel to "qhc",
        HostClass.RealMojiMenuItem to "a0i",
        HostClass.DualViewImageSize to "ye6",
        HostClass.HomeFeedItemEmitter to "wj9",
        HostClass.EmptyList to "cu6",
        HostClass.MyRealMojiState to "u2e",
        HostClass.HomeGridTileContainer to "h59",
        HostClass.Unit to "c8n",
        HostClass.BeRealImageLoader to "ug1",
        HostClass.DualViewInteractions to "ze6",
        HostClass.MyRealMojis to "v2e",
        HostClass.HomeGridClick to "z02",
        HostClass.RealMojiMenuAction to "s5i",
        HostClass.RealMojiMenuEntry to "t5i",
        HostClass.SponsoredPostState to "vci",
        HostClass.HomeFeedItem to "ai7",
        HostClass.MediaFlipState to "ae6",
        HostClass.HomeGridTile to "cwh",
        HostClass.DualViewPlayers to "fg6",
        HostClass.HomeFeed to "pg9",
        HostClass.HomeFeedMapper to "bi7",
        HostClass.PostUploadScheduler to "m04",
        HostClass.BtsMedia to "jfg",
        HostClass.PullDownGridCard to "skh",
        HostClass.CorePost to "w05",
        HostClass.ResultSuccess to "mbm",
        HostClass.GridCardIconAction to "hkh",
        HostClass.User to "hln",
        HostClass.ImmutableListFactory to "z44",
        HostClass.PostSender to "vjk",
        HostClass.SponsoredPost to "odg",
        HostClass.RealMojis to "mig",
        HostClass.Function0 to "uo8",
        HostClass.KoinApplicationHolder to "aua",
        HostClass.CoroutineStart to "hfa",
        HostClass.PostReactions to "qdg",
        HostClass.RealMojiViewerItem to "o5i",
        HostClass.PublishedPost to "w05",
        HostClass.DualMediaRenderer to "jf6",
        HostClass.CameraCountdown to "l27",
        HostClass.RealMojiSelection to "b6i",
        HostClass.Function1 to "yo8",
        HostClass.DualViewConfig to "me6",
        HostClass.PostFeedState to "tdg",
        HostClass.BlurredPostState to "kj7",
        HostClass.RegularPostState to "lj7",
        HostClass.MediaContainer to "k50",
        HostClass.Function2 to "ap8",
        HostClass.LocationRepository to "rbb",
        HostClass.PostViewState to "mj7",
        HostClass.FeedRefreshReason to "csk",
        HostClass.ImmutableList to "pvf",
        HostClass.FriendsOfFriendsItemEmitter to "e38",
        HostClass.TimelineBlurredCard to "l77",
        HostClass.CameraFacing to "mb7",
        HostClass.HomeFeedPost to "nj7",
        HostClass.UnsentPostRepository to "g25",
        HostClass.RealMojiViewModel to "g6i",
        HostClass.Flow to "ez7",
        HostClass.CreatePostContinuation to "kzd",
        HostClass.StableImageLoader to "x1m",
        HostClass.UnsentPost to "u9n",
        HostClass.HomeFeedContent to "pj7",
        HostClass.HomeGridBadge to "svh",
        HostClass.PersistentList to "p1a",
        HostClass.FlowOperators to "del",
        HostClass.CameraViewModel to "rm2",
        HostClass.UploadFailure to "im4",
        HostClass.Continuation to "ku4",
        HostClass.CurrentUserProvider to "ey8",
        HostClass.BeRealMedia to "jh1",
        HostClass.FeedBlurOptions to "si7",
        HostClass.ContinuationImpl to "lu4",
        HostClass.HomeFeedStateHolder to "owi",
        HostClass.SecondaryCameraOriginParser to "vxm",
        HostClass.PagerSnapLayoutInfo to "sz9",
        HostClass.CameraOriginParser to "pzm",
        HostClass.RealMojiRowState to "nnh",
        HostClass.PostRepository to "qzd",
        HostClass.HomeGridTileRenderer to "f59",
        HostClass.DualViewData to "xe6",
        HostClass.ImageData to "apl",
    )

    override val methods = mapOf(
        HostMethod.ImmutableListCopy to "I",
        HostMethod.KoinApplication to "B",
        HostMethod.FlowFirst to "u",
        HostMethod.SecondaryCameraOrigin to "N",
        HostMethod.CameraOrigin to "Q",
        HostMethod.KoinResolve to "e",
        HostMethod.ComposeTraceBegin to "A",
    )

    override val postContentsArity = 6
    override val publishedPostIdField = "a"
    override val gridCardBlurArgument = 13
    override val gridClickBranch = 13
    override val mediaFlipBranch = 2
    override val mediaOrientationArgument = 10
    override fun postMapSymbols(loader: ClassLoader) = PostMapMappings3980.resolve(loader)

    override fun smsRequestConstructor(loader: ClassLoader) = resolveSmsRequestConstructor(loader, "jlj")

    override fun realMojiMenuClick(loader: ClassLoader): java.lang.reflect.Method =
        Class.forName("wif", false, loader).getDeclaredMethod("invoke", Any::class.java)

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
        val owner = Class.forName("wd7", false, loader)
        owner.getDeclaredMethod("c", Class.forName("gp", false, loader)).takeIf {
            it.returnType == Class.forName("fo", false, loader) && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true }
    }.getOrNull()

    // The timeline no longer has the old canBlur argument; media hooks handle its content.
    override fun resolveTimelineBlurredCardComposable(classLoader: ClassLoader): java.lang.reflect.Method? = null

    override fun resolvePullDownGridCardComposable(classLoader: ClassLoader): java.lang.reflect.Method? =
        staticMethod(classLoader, "skh", "b",
            "x1m", "java.lang.String", "java.lang.String", "k50", "xe6",
            "sz9", "uo8", "uo8", "uo8", "yo8",
            "nnh", "hkh", "boolean", "boolean", "uo8",
            "boolean", "uo8", "boolean", "androidx.compose.ui.Modifier", "androidx.compose.runtime.Composer",
            "int",
        )

    override fun resolvePullDownGridMediaComposable(classLoader: ClassLoader): java.lang.reflect.Method? =
        staticMethod(classLoader, "jf6", "a",
            "xe6", "androidx.compose.ui.Modifier", "androidx.compose.runtime.State", "boolean", "me6",
            "fg6", "yo8", "androidx.compose.runtime.State", "androidx.compose.runtime.State", "androidx.compose.runtime.State",
            "androidx.compose.runtime.State", "uo8", "yo8", "uo8", "yo8",
            "uo8", "yo8", "yo8", "boolean", "x1m",
            "yo8", "yo8", "ye6", "boolean", "android.graphics.Bitmap\$Config",
            "ze6", "yo8", "uo8", "boolean", "boolean",
            "boolean", "uo8", "java.lang.String", "androidx.compose.runtime.Composer", "int",
            "int", "int", "int", "int", "int",
        )

    override fun mediaOrientationMethod(loader: ClassLoader): java.lang.reflect.Method? =
        staticMethod(loader, "jf6", "b",
            "xe6", "androidx.compose.ui.Modifier", "fg6", "s32", "yo8",
            "yo8", "uo8", "yo8", "yo8", "yo8",
            "boolean", "x1m", "androidx.compose.runtime.State", "androidx.compose.runtime.State", "androidx.compose.runtime.State",
            "boolean", "yo8", "yo8", "ze6", "me6",
            "androidx.compose.runtime.State", "ye6", "androidx.compose.runtime.State", "boolean", "uo8",
            "uo8", "android.graphics.Bitmap\$Config", "yo8", "uo8", "boolean",
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
