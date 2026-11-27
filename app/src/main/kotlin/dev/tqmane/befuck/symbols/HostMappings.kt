package dev.tqmane.befuck.symbols

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Resolved once by version; implementations contain direct, complete mappings. */
abstract class HostMappings(val versionName: String, val versionCode: Long) {
    abstract val classes: Map<HostClass, String>
    abstract val methods: Map<HostMethod, String>
    open val requiresRuntimeRecovery: Boolean = false
    open val supportsSmsAuth: Boolean = true
    open val signingCertificateSha256: String? = null
    open val lifecycleDexAsset: String? = null
    open val lifecycleBindings: List<LifecycleBinding> = emptyList()
    open val postContentsArity: Int = 5
    open val publishedPostIdField: String = "id"
    open val gridCardBlurArgument: Int = 16
    open val gridCardClickReplacement: Pair<Int, Int>? = 8 to 7
    open val gridClickBranch: Int = 12
    open val mediaFlipBranch: Int = 27
    open val mediaOrientationArgument: Int = 9
    open fun postMapSymbols(loader: ClassLoader): PostMapSymbols? = null

    open fun realMojiMenuClick(loader: ClassLoader): Method =
        type(HostClass.RealMojiViewModel, loader).getDeclaredMethod("G", type(HostClass.RealMojiMenuAction, loader))

    open fun realMojiMenuItem(loader: ClassLoader, label: String, icon: Int?): Any =
        type(HostClass.RealMojiMenuItem, loader)
            .getDeclaredConstructor(String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType, Int::class.javaObjectType)
            .newInstance(label, null, label, false, icon)

    protected fun realMojiMenuItemWithIconBeforeAccessibility(loader: ClassLoader, label: String, icon: Int?): Any =
        type(HostClass.RealMojiMenuItem, loader)
            .getDeclaredConstructor(String::class.java, String::class.java, Int::class.javaObjectType, String::class.java, Boolean::class.javaPrimitiveType)
            .newInstance(label, null, icon, label, false)

    open fun realMojiMenuContract(loader: ClassLoader): RealMojiMenuContract {
        val actionClass = type(HostClass.RealMojiMenuAction, loader)
        val itemClass = type(HostClass.RealMojiMenuItem, loader)
        val entryClass = type(HostClass.RealMojiMenuEntry, loader)
        val selectedClass = type(HostClass.RealMojiSelection, loader)
        val viewerItemClass = type(HostClass.RealMojiViewerItem, loader)
        val listClass = type(HostClass.PersistentList, loader)
        val clicked = realMojiMenuClick(loader)
        return RealMojiMenuContract(
            actionClass = actionClass,
            selectedConstructor = selectedClass.getDeclaredConstructor(viewerItemClass, String::class.java, listClass, Int::class.javaPrimitiveType),
            entryConstructor = entryClass.getDeclaredConstructor(actionClass, itemClass, String::class.java),
            immutableListCopy = type(HostClass.ImmutableListFactory, loader)
                .getDeclaredMethod(methodName(HostMethod.ImmutableListCopy), Iterable::class.java),
            clickedMethod = clicked,
            clickedResult = if (clicked.returnType == Void.TYPE) null else kotlinUnit(loader),
            viewerModelField = viewerItemClass.getDeclaredField("a").apply { isAccessible = true },
            entryTagField = entryClass.getDeclaredField("c").apply { isAccessible = true },
        )
    }

    open fun kotlinUnit(loader: ClassLoader): Any =
        requireNotNull(type(HostClass.Unit, loader).getDeclaredField("a").apply { isAccessible = true }.get(null))

    open fun uploadWorkerClass(loader: ClassLoader): Class<*> =
        Class.forName("bereal.app.data.post.repository.mypost.worker.UploadUnsentPostWorker", false, loader)

    open fun uploadWorkerDiagnosticMethods(loader: ClassLoader): List<Method> =
        uploadWorkerClass(loader).declaredMethods.filter { method ->
            val p = method.parameterTypes
            (method.name == "g" || method.name == "i") && p.size == 3 && p[0] == String::class.java &&
                Throwable::class.java.isAssignableFrom(p[1]) && Map::class.java.isAssignableFrom(p[2])
        }

    open fun uploadWorkerStepMethod(loader: ClassLoader): Method? =
        uploadWorkerClass(loader).declaredMethods.firstOrNull { method ->
            val p = method.parameterTypes
            method.name == "j" && method.returnType == Any::class.java && p.size == 3 &&
                p[0] == Int::class.javaPrimitiveType && p[1] == String::class.java
        }

    open fun officialPostObservationContract(loader: ClassLoader): OfficialPostObservationContract {
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
                m.name == "d" && m.parameterCount == 5 && m.parameterTypes[0] == corePost
            },
            unsentSaveMethods = unsentRepository.declaredMethods.filter { m ->
                m.name == "p" && m.parameterCount == 2 && m.parameterTypes[0] == unsentPost
            },
        )
    }

    open fun homeGridTileContract(loader: ClassLoader): HomeGridTileContract {
        val tileClass = type(HostClass.HomeGridTile, loader)
        val imageDataClass = type(HostClass.ImageData, loader)
        val badgeClass = type(HostClass.HomeGridBadge, loader)
        val fields = Array(7) { index ->
            tileClass.getDeclaredField(('a' + index).toString()).apply { isAccessible = true }
        }
        val constructor = tileClass.getDeclaredConstructor(
            String::class.java, imageDataClass, String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, badgeClass,
        ).apply { isAccessible = true }
        return HomeGridTileContract(tileClass, fields, fields[5], constructor)
    }

    open fun homeGridClickContract(loader: ClassLoader): HomeGridClickContract {
        val clickLambda = type(HostClass.HomeGridClick, loader)
        val tileContainer = type(HostClass.HomeGridTileContainer, loader)
        val tileModel = type(HostClass.HomeGridTile, loader)
        val callback = type(HostClass.Function2, loader)
        return HomeGridClickContract(
            tileContainerClass = tileContainer,
            tileModelClass = tileModel,
            branchField = clickLambda.getDeclaredField("a").apply { isAccessible = true },
            callbackField = clickLambda.getDeclaredField("b").apply { isAccessible = true },
            tileContainerField = clickLambda.getDeclaredField("c").apply { isAccessible = true },
            tileModelField = tileContainer.getDeclaredField("b").apply { isAccessible = true },
            postIdField = tileModel.getDeclaredField("a").apply { isAccessible = true },
            callbackInvoke = callback.getDeclaredMethod("invoke", Any::class.java, Any::class.java).apply { isAccessible = true },
            clickInvoke = clickLambda.getDeclaredMethod("invoke").apply { isAccessible = true },
        )
    }

    open fun feedBlurOptionsContract(loader: ClassLoader): FeedBlurOptionsContract {
        val optionsClass = type(HostClass.FeedBlurOptions, loader)
        val fields = Array(8) { i ->
            optionsClass.getDeclaredField(('a' + i).toString()).apply { isAccessible = true }
        }
        val params = Array<Class<*>>(8) { Boolean::class.javaPrimitiveType!! }
        val constructor = optionsClass.getDeclaredConstructor(*params).apply { isAccessible = true }
        return FeedBlurOptionsContract(optionsClass, fields, constructor)
    }

    open fun postReactionsConstructors(loader: ClassLoader): List<java.lang.reflect.Constructor<*>> =
        type(HostClass.PostReactions, loader).declaredConstructors.map { it.apply { isAccessible = true } }

    open fun feedPostBlurSwitchInvoke(loader: ClassLoader): Method? = null

    open fun feedBlurModelRewriter(loader: ClassLoader): FeedBlurModelRewriter? {
        val postClass = type(HostClass.HomeFeedPost, loader)
        val blurredStateClass = type(HostClass.BlurredPostState, loader)
        val regularStateClass = type(HostClass.RegularPostState, loader)
        val myRealmojisClass = type(HostClass.MyRealMojis, loader)
        val realmojisClass = type(HostClass.RealMojis, loader)
        val emptyListClass = type(HostClass.EmptyList, loader)
        val stateField = postClass.getDeclaredField("f").apply { isAccessible = true }
        val postFields = Array(15) { i ->
            postClass.getDeclaredField(('a' + i).toString()).apply { isAccessible = true }
        }
        val corePostName = className(HostClass.CorePost)
        val postViewStateName = className(HostClass.PostViewState)
        val postConstructor = postClass.declaredConstructors.firstOrNull { candidate ->
            val p = candidate.parameterTypes
            p.size == 15 && p[0] == String::class.java && p[1] == Boolean::class.javaPrimitiveType &&
                p[2].name == corePostName && p[5].name == postViewStateName
        }?.apply { isAccessible = true } ?: throw NoSuchMethodException("HomeFeedPost full constructor")
        val emptyList = emptyListClass.getDeclaredField("a").apply { isAccessible = true }.get(null)
        val myRealmojis = myRealmojisClass.getDeclaredConstructor(List::class.java, type(HostClass.MyRealMojiState, loader))
            .apply { isAccessible = true }.newInstance(emptyList, null)
        val realmojis = realmojisClass.getDeclaredConstructor(Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, List::class.java)
            .apply { isAccessible = true }.newInstance(false, 0, 0, emptyList)
        val regularState = regularStateClass.getDeclaredConstructor(myRealmojisClass, realmojisClass)
            .apply { isAccessible = true }.newInstance(myRealmojis, realmojis)
        return FeedBlurModelRewriter(postClass, blurredStateClass, stateField, postFields, postConstructor, regularState)
    }

    open fun mediaFlipContract(loader: ClassLoader): MediaFlipContract {
        val flipLambda = type(HostClass.MediaFlipState, loader)
        val mutableStateClass = Class.forName("androidx.compose.runtime.MutableState", false, loader)
        val constructor = flipLambda.declaredConstructors.firstOrNull { c ->
            val p = c.parameterTypes
            p.size == 2 && p[0] == mutableStateClass && p[1] == Int::class.javaPrimitiveType
        } ?: throw NoSuchMethodException("MediaFlipState(MutableState, int)")
        return MediaFlipContract(
            constructor = constructor,
            kindField = flipLambda.getDeclaredField("a").apply { isAccessible = true },
            stateField = flipLambda.getDeclaredField("b").apply { isAccessible = true },
            invokeMethod = flipLambda.getDeclaredMethod("invoke").apply { isAccessible = true },
        )
    }

    open fun mediaOrientationMethod(loader: ClassLoader): Method? =
        type(HostClass.DualMediaRenderer, loader).declaredMethods.singleOrNull { method ->
            val p = method.parameterTypes
            Modifier.isStatic(method.modifiers) && method.name == "c" && method.returnType == Void.TYPE && p.size == 34 &&
                p[0] == type(HostClass.DualViewData, loader) && p[9] == Boolean::class.javaPrimitiveType &&
                p[30].name == "androidx.compose.runtime.Composer" && p.drop(31).all { it == Int::class.javaPrimitiveType }
        }

    fun isKnownVersion(name: String?, code: Long) =
        name == versionName && code == versionCode

    fun className(role: HostClass): String = classes.getValue(role)
    fun methodName(role: HostMethod): String = methods.getValue(role)
    fun type(role: HostClass, loader: ClassLoader): Class<*> = Class.forName(className(role), false, loader)

    /** Force the host repository to fetch, rather than returning its cached feed. */
    fun refreshFeedFlow(stateHolder: Any): Any {
        val source = requireNotNull(stateHolder.javaClass.getDeclaredField("b")
            .apply { isAccessible = true }.get(stateHolder)) { "Feed source unavailable" }
        val fetch = source.javaClass.declaredMethods.single { method ->
            method.name == "a" && method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        }.apply { isAccessible = true }
        return requireNotNull(fetch.invoke(source, true)) { "Feed force-fetch returned null" }
    }

    open fun emailAnalyticsGuard(loader: ClassLoader): EmailAnalyticsGuard? = null
    open fun authDiagnosticConstructors(loader: ClassLoader): Map<String, java.lang.reflect.Constructor<*>> = emptyMap()
    open fun textFieldFocusGuard(loader: ClassLoader): TextFieldFocusGuard? = null
    open fun smsRequestConstructor(loader: ClassLoader): java.lang.reflect.Constructor<*>? = null
    open fun smsRequestShape(loader: ClassLoader, args: Array<Any?>): String {
        if (args.size != 4) return "unexpectedArguments=true"
        val tokens = args[3] as? List<*> ?: return "tokenListPresent=false"
        var recaptcha = 0
        var prelude = 0
        var unknown = 0
        var missingTokens = 0
        var invalidEntries = 0
        for (entry in tokens) {
            if (entry == null) {
                invalidEntries++
                continue
            }
            val entryClass = entry.javaClass
            val tokenField = runCatching { entryClass.getDeclaredField("token").apply { isAccessible = true } }.getOrNull()
            val idField = runCatching { entryClass.getDeclaredField("identifier").apply { isAccessible = true } }.getOrNull()
            if (tokenField == null || idField == null) {
                invalidEntries++
                continue
            }
            if ((tokenField.get(entry) as? String).isNullOrBlank()) missingTokens++
            when (idField.get(entry)) {
                "RE" -> recaptcha++
                "PR" -> prelude++
                else -> unknown++
            }
        }
        return "tokens=${tokens.size}; recaptcha=$recaptcha; prelude=$prelude; unknown=$unknown; missing=$missingTokens; invalid=$invalidEntries"
    }

    protected fun resolveSmsRequestConstructor(loader: ClassLoader, className: String): java.lang.reflect.Constructor<*>? = runCatching {
        val requestClass = Class.forName(className, false, loader)
        for ((fieldName, type) in mapOf(
            "deviceId" to String::class.java, "stableDeviceId" to String::class.java,
            "phoneNumber" to String::class.java, "tokens" to List::class.java,
        )) {
            val field = requestClass.getDeclaredField(fieldName)
            require(field.type == type && !Modifier.isStatic(field.modifiers))
        }
        requestClass.declaredConstructors.firstOrNull { ctor ->
            ctor.parameterCount == 4 &&
                ctor.parameterTypes[0] == String::class.java &&
                ctor.parameterTypes[1] == String::class.java &&
                ctor.parameterTypes[2] == String::class.java &&
                List::class.java.isAssignableFrom(ctor.parameterTypes[3])
        }?.apply { isAccessible = true }
    }.getOrNull()
    open fun preludeLibraryLoad(loader: ClassLoader): Method? = null
    open fun preludeLibraryInterfaces(loader: ClassLoader): List<Class<*>> = emptyList()
    open fun repackagedStartupChecks(loader: ClassLoader): List<Method> = emptyList()
    /** PairIP's early installer/license entrypoint; only version-verified mappings may expose it. */
    open fun repackagedLicenseCheck(loader: ClassLoader): Method? = null
    open fun runtimeStringRepairs(classLoader: ClassLoader): Map<Field, String> = emptyMap()
    open fun missingStringRepairs(classLoader: ClassLoader): Map<Field, String> = emptyMap()
    open fun newCameraIdleCallback(classLoader: ClassLoader): android.os.Binder = error("No VM recovery for this host")
    open fun resolveStringField(classLoader: ClassLoader, symbol: String): Field? = null
    open fun bootstrapStringRepairs(loader: ClassLoader): Map<Field, String> = emptyMap()
    open fun analyticsPropertyMethod(loader: ClassLoader): Method? = null
    open fun analyticsIdentifyMethod(loader: ClassLoader): Method? = null
    open fun protobufSchemaMethod(loader: ClassLoader): Method? = null
    open fun protobufFieldLookupMethod(loader: ClassLoader): Method? = null
    open fun uploadStringRepairs(loader: ClassLoader): Map<Field, String> = emptyMap()
    open fun recoveryProbe(loader: ClassLoader): Pair<Field, java.lang.reflect.Constructor<*>>? = null
    /** A nullable host advertisement-slot selector, available only when verified for the APK version. */
    open fun resolveFeedAdSelector(loader: ClassLoader): Method? = null
    open fun resolveNativeAdComposable(loader: ClassLoader): Method? = null

    protected fun staticVoidMethod(loader: ClassLoader, owner: String, name: String, vararg parameters: String): Method? = runCatching {
        fun resolveType(typeName: String): Class<*> = when (typeName) {
            "boolean" -> Boolean::class.javaPrimitiveType!!
            "int" -> Int::class.javaPrimitiveType!!
            else -> Class.forName(typeName, false, loader)
        }
        resolveType(owner).getDeclaredMethod(name, *parameters.map(::resolveType).toTypedArray()).takeIf {
            Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE
        }?.apply { isAccessible = true }
    }.getOrNull()

    protected fun resolvePairIpLicenseCheck(loader: ClassLoader): Method? = runCatching {
        Class.forName("com.pairip.licensecheck.LicenseClient", false, loader)
            .getDeclaredMethod("checkLicense", android.content.Context::class.java)
            .takeIf { Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE }
            ?.apply { isAccessible = true }
    }.getOrNull()

    protected fun resolveFeedAdSelector(
        loader: ClassLoader,
        ownerClass: String,
        methodName: String,
        placementClass: String,
        adClass: String,
    ): Method? = runCatching {
        val owner = Class.forName(ownerClass, false, loader)
        val placement = Class.forName(placementClass, false, loader)
        val ad = Class.forName(adClass, false, loader)
        owner.getDeclaredMethod(methodName, placement).takeIf {
            it.returnType == ad && !Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true }
    }.getOrNull()

    companion object {
        @JvmStatic
        fun boundedFocusMapping(original: Any, transform: Method, layoutLength: Int): Any {
            require(layoutLength >= 0)
            return java.lang.reflect.Proxy.newProxyInstance(transform.declaringClass.classLoader,
                arrayOf(transform.declaringClass)) { _, method, args ->
                val result = method.invoke(original, *(args ?: emptyArray()))
                // The editing value can be newer than the layout during OTP focus changes.
                if (method == transform) (result as Int).coerceIn(0, layoutLength) else result
            }
        }

        internal fun isContinuationContract(type: Class<*>): Boolean {
            val methods = generateSequence<Class<*>>(type) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .toList() + type.methods
            return methods.any {
                it.name == "resumeWith" && it.parameterTypes.contentEquals(arrayOf(Any::class.java)) && it.returnType == Void.TYPE
            } && methods.any {
                it.name == "getContext" && it.parameterCount == 0 && it.returnType != Void.TYPE
            }
        }

        const val MAPS_CAMERA_IDLE_DESCRIPTOR = "com.google.android.gms.maps.internal.IOnCameraIdleListener"
    }

    // Shared SDK roots in both supported APKs. Match superclasses too: e.g. Google's
    // AdManagerAdView extends BaseAdView and vendor adapters subclass MediaView.
    private val adViewClassNames = setOf(
        "bereal.app.features.ads.ui.natives.view.MaxNativeAdViewContainer",
        "com.applovin.adview.AppLovinAdView",
        "com.applovin.mediation.ads.MaxAdView",
        "com.applovin.mediation.nativeAds.MaxNativeAdView",
        "com.applovin.impl.sdk.nativeAd.AppLovinMediaView",
        "com.google.android.gms.ads.BaseAdView",
        "com.google.android.gms.ads.nativead.NativeAdView",
        "com.google.android.gms.ads.nativead.MediaView",
        "com.google.android.gms.ads.formats.MediaView",
        "com.bytedance.sdk.openadsdk.api.nativeAd.PAGMediaView",
        "com.bytedance.sdk.openadsdk.adapter.MediaView",
        "com.pubmatic.sdk.nativead.POBNativeAdView",
        "com.pubmatic.sdk.nativead.views.POBNativeTemplateView",
        "com.pubmatic.sdk.openwrap.banner.POBBannerView",
        "com.pubmatic.sdk.appopenad.ui.POBAppOpenAdViewContainer",
        "com.pubmatic.sdk.webrendering.ui.POBAdViewContainer",
        "com.pubmatic.sdk.webrendering.ui.POBMraidViewContainer",
        "com.pubmatic.sdk.video.player.POBVideoPlayerView",
        "com.pubmatic.sdk.video.player.POBVastHTMLView",
        "com.pubmatic.sdk.video.player.POBMraidEndCardView",
        "com.pubmatic.sdk.common.view.POBWebView",
        "com.vungle.ads.BannerView",
        "com.vungle.ads.VungleBannerView",
        "com.vungle.ads.internal.ui.view.MediaView",
        "net.pubnative.lite.sdk.views.HyBidAdView",
        "net.pubnative.lite.sdk.vpaid.VideoAdView",
    )

    fun isAdViewClass(type: Class<*>): Boolean {
        return generateSequence(type) { it.superclass }.any { it.name in adViewClassNames }
    }

    fun sponsoredFeedFields(loader: ClassLoader): List<Field> {
        val owner = Class.forName(className(HostClass.PostFeedState), false, loader)
        // e = sponsoredUiModel; h = realSponsoredPostUiState. The latter can be
        // absent before the sponsored card finishes loading; e already marks it.
        return listOf("e" to HostClass.SponsoredPost, "h" to HostClass.SponsoredPostState).map { (fieldName, typeName) ->
            owner.getDeclaredField(fieldName).apply {
                require(type == Class.forName(className(typeName), false, loader) && !Modifier.isStatic(modifiers))
                isAccessible = true
            }
        }
    }

    class EmailAnalyticsGuard(
        val constructors: Map<java.lang.reflect.Constructor<*>, List<Field>>,
        val baseConstructor: java.lang.reflect.Constructor<*>,
        val emit: Method,
        val eventNames: Map<Class<*>, String>,
        private val label: Field,
        private val emailStep: Any,
    ) {
        fun hasMissingStep(step: Any?): Boolean = step === emailStep && label.get(emailStep) == null

        fun shouldOmit(event: Any?): Boolean {
            if (event == null) return false
            val fields = constructors.entries.singleOrNull { it.key.declaringClass == event.javaClass }?.value
                ?: return false
            return hasMissingStep(fields.first().get(event))
        }
    }

    class TextFieldFocusGuard(
        val notify: Method,
        private val layoutInput: Field,
        private val text: Field,
        private val originalToTransformed: Method,
    ) {
        fun arguments(args: Array<Any?>): Array<Any?> {
            if (args[5] != true) return args
            val length = (text.get(layoutInput.get(args[2])) as CharSequence).length
            return args.copyOf().apply {
                this[6] = boundedFocusMapping(requireNotNull(args[6]), originalToTransformed, length)
            }
        }
    }


    // g01.invokeSuspend converts an empty token list to null before constructing mtj.
    // Keep the JSON array shape used by the request-code endpoint's reference client.
    // Never discard or replace an existing challenge token.
    fun smsRequestArguments(args: Array<Any?>): Array<Any?> {
        require(args.size == 4)
        if (args[3] != null) return args
        return args.copyOf().apply { this[3] = arrayListOf<Any>() }
    }

    fun composeRuntimeMethods(loader: ClassLoader): Map<String, Method> {
        return runCatching {
            val composer = Class.forName("androidx.compose.runtime.Composer", false, loader)
            val implementation = Class.forName("androidx.compose.runtime.GapComposer", false, loader)
            val scope = Class.forName("androidx.compose.runtime.ScopeUpdateScope", false, loader)
            fun method(name: String, result: Class<*>, vararg parameters: Class<*>) =
                implementation.getDeclaredMethod(name, *parameters).apply {
                    require(returnType == result && !Modifier.isStatic(modifiers))
                    isAccessible = true
                }
            mapOf(
                "start" to method("A", composer, Int::class.javaPrimitiveType!!),
                "execute" to method("H", Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
                "end" to method("C", scope),
                "replaceStart" to method("r", Void.TYPE, Int::class.javaPrimitiveType!!),
                "replaceEnd" to method("o", Void.TYPE),
                "effect" to Class.forName("androidx.compose.runtime.EffectsKt", false, loader)
                    .getDeclaredMethod("a", Any::class.java, Class.forName(className(HostClass.Function1), false, loader), composer)
                    .apply { require(returnType == Void.TYPE && Modifier.isStatic(modifiers)); isAccessible = true },
            )
        }.getOrDefault(emptyMap())
    }

    fun resolveCameraViewModel(classLoader: ClassLoader): Class<*>? =
        loadKnown(classLoader, className(HostClass.CameraViewModel))

    fun resolveCameraFacingEnum(classLoader: ClassLoader): Class<*>? =
        loadKnown(classLoader, className(HostClass.CameraFacing))

    fun resolveCameraCountdownComposable(classLoader: ClassLoader): java.lang.reflect.Method? {
        return try {
            Class.forName(className(HostClass.CameraCountdown), false, classLoader)
                .getDeclaredMethod(
                    "a",
                    Float::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Class.forName("androidx.compose.runtime.Composer", false, classLoader),
                    Class.forName("androidx.compose.ui.Modifier", false, classLoader),
                    String::class.java,
                )
                .apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveLocationRepository(classLoader: ClassLoader): Class<*>? =
        loadKnown(classLoader, className(HostClass.LocationRepository))

    open fun resolveCurrentUserProvider(classLoader: ClassLoader): Pair<Class<*>?, java.lang.reflect.Method?> {
        return try {
            val provider = Class.forName(className(HostClass.CurrentUserProvider), false, classLoader)
            val candidates = provider.declaredMethods.filter { method ->
                method.returnType == Any::class.java && method.parameterCount == 1 &&
                    isContinuationContract(method.parameterTypes[0])
            }
            if (candidates.size == 1) {
                provider to candidates.single().apply { isAccessible = true }
            } else {
                provider to null
            }
        } catch (_: Throwable) {
            null to null
        }
    }

    fun resolveKoinApplication(classLoader: ClassLoader): Any? {
        return try {
            Class.forName(className(HostClass.KoinApplicationHolder), false, classLoader)
                .getDeclaredMethod(methodName(HostMethod.KoinApplication))
                .apply { isAccessible = true }
                .invoke(null)
        } catch (_: Throwable) {
            null
        }
    }

    fun resolvePostUploadSchedulerClass(classLoader: ClassLoader): Class<*>? =
        loadKnown(classLoader, className(HostClass.PostUploadScheduler))

    fun resolvePostUploadSchedulerMethod(classLoader: ClassLoader, schedulerClass: Class<*>?): java.lang.reflect.Method? {
        if (schedulerClass == null) return null
        return try {
            val candidates = schedulerClass.declaredMethods.filter { method ->
                method.returnType == Any::class.java && method.parameterCount == 1 &&
                    isContinuationContract(method.parameterTypes[0])
            }
            candidates.singleOrNull()?.apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveCameraOriginParsers(classLoader: ClassLoader): List<java.lang.reflect.Method> {
        val mappings = listOf(HostClass.CameraOriginParser to HostMethod.CameraOrigin,
            HostClass.SecondaryCameraOriginParser to HostMethod.SecondaryCameraOrigin)
        return mappings.mapNotNull { (owner, member) ->
            try {
                Class.forName(className(owner), false, classLoader)
                    .getDeclaredMethod(methodName(member), android.os.Bundle::class.java)
                    .apply { isAccessible = true }
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun resolveCameraBindMethod(classLoader: ClassLoader, cameraViewModel: Class<*>?, facingEnum: Class<*>?): java.lang.reflect.Method? {
        if (cameraViewModel == null || facingEnum == null) return null
        return try {
            val parameters = arrayOf(Class.forName("androidx.lifecycle.LifecycleOwner", false, classLoader), facingEnum)
            cameraViewModel.declaredMethods.singleOrNull {
                it.parameterTypes.contentEquals(parameters) && it.returnType == Void.TYPE
            }?.apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveLocationRequestMethod(classLoader: ClassLoader, repository: Class<*>?): java.lang.reflect.Method? {
        if (repository == null) return null
        return try {
            repository.getDeclaredMethod("b", Boolean::class.javaPrimitiveType!!, Class.forName(className(HostClass.ContinuationImpl), false, classLoader))
                .apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveLocationClientGetter(repository: Class<*>?): java.lang.reflect.Method? {
        if (repository == null) return null
        return try {
            repository.getDeclaredMethod("a").apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    open fun resolveTimelineBlurredCardComposable(classLoader: ClassLoader): Method? {
        return try {
            val composer = Class.forName("androidx.compose.runtime.Composer", false, classLoader)
            val modifier = Class.forName("androidx.compose.ui.Modifier", false, classLoader)
            val pager = Class.forName("androidx.compose.foundation.pager.PagerState", false, classLoader)
            val lambda = Class.forName("androidx.compose.runtime.internal.ComposableLambdaImpl", false, classLoader)
            val expected = arrayOf(
                modifier,
                Class.forName(className(HostClass.PagerSnapLayoutInfo), false, classLoader),
                Class.forName(className(HostClass.ImmutableList), false, classLoader),
                pager,
                lambda,
                Boolean::class.javaPrimitiveType!!,
                Class.forName(className(HostClass.Function0), false, classLoader),
                composer,
                Int::class.javaPrimitiveType!!,
            )
            val candidates = Class.forName(className(HostClass.TimelineBlurredCard), false, classLoader).declaredMethods.filter { method ->
                Modifier.isStatic(method.modifiers) && method.name == "a" && method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(expected)
            }
            candidates.singleOrNull()?.apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    open fun resolvePullDownGridCardComposable(classLoader: ClassLoader): Method? {
        return try {
            val expected = arrayOf(
                Class.forName(className(HostClass.BeRealImageLoader), false, classLoader),
                String::class.java,
                String::class.java,
                Class.forName(className(HostClass.MediaContainer), false, classLoader),
                Class.forName(className(HostClass.DualViewData), false, classLoader),
                Class.forName(className(HostClass.PagerSnapLayoutInfo), false, classLoader),
                Boolean::class.javaPrimitiveType!!,
                Class.forName(className(HostClass.Function0), false, classLoader),
                Class.forName(className(HostClass.Function0), false, classLoader),
                Class.forName(className(HostClass.Function0), false, classLoader),
                Class.forName(className(HostClass.Function2), false, classLoader),
                Class.forName(className(HostClass.Function1), false, classLoader),
                Class.forName(className(HostClass.GridCardIconAction), false, classLoader),
                Class.forName(className(HostClass.GridCardAction), false, classLoader),
                Class.forName(className(HostClass.RealMojiRowState), false, classLoader),
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Class.forName(className(HostClass.Function0), false, classLoader),
                Boolean::class.javaPrimitiveType!!,
                Class.forName("androidx.compose.runtime.Composer", false, classLoader),
                Int::class.javaPrimitiveType!!,
            )
            Class.forName(className(HostClass.PullDownGridCard), false, classLoader).declaredMethods.singleOrNull { method ->
                Modifier.isStatic(method.modifiers) && method.name == "a" && method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(expected)
            }?.apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    open fun resolvePullDownGridMediaComposable(classLoader: ClassLoader): Method? {
        return try {
            val modifier = Class.forName("androidx.compose.ui.Modifier", false, classLoader)
            val state = Class.forName("androidx.compose.runtime.State", false, classLoader)
            val function1 = Class.forName(className(HostClass.Function1), false, classLoader)
            val function0 = Class.forName(className(HostClass.Function0), false, classLoader)
            val bitmapConfig = Class.forName("android.graphics.Bitmap\$Config", false, classLoader)
            val expected = arrayOf(
                Class.forName(className(HostClass.DualViewData), false, classLoader),
                modifier,
                state,
                Boolean::class.javaPrimitiveType!!,
                Class.forName(className(HostClass.DualViewConfig), false, classLoader),
                Class.forName(className(HostClass.DualViewPlayers), false, classLoader),
                function1,
                state,
                state,
                state,
                state,
                function0,
                function1,
                function0,
                function1,
                function0,
                function1,
                function1,
                Boolean::class.javaPrimitiveType!!,
                Class.forName(className(HostClass.StableImageLoader), false, classLoader),
                function1,
                function1,
                Class.forName(className(HostClass.DualViewImageSize), false, classLoader),
                Boolean::class.javaPrimitiveType!!,
                bitmapConfig,
                Class.forName(className(HostClass.DualViewInteractions), false, classLoader),
                function1,
                function0,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                function0,
                Class.forName("androidx.compose.runtime.Composer", false, classLoader),
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
            Class.forName(className(HostClass.DualMediaRenderer), false, classLoader).declaredMethods.singleOrNull { method ->
                Modifier.isStatic(method.modifiers) && method.name == "a" && method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(expected)
            }?.apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveHomeGridPostTileComposable(classLoader: ClassLoader): Method? {
        return try {
            val expected = arrayOf(
                Class.forName(className(HostClass.HomeGridTile), false, classLoader),
                Class.forName(className(HostClass.Function0), false, classLoader),
                Class.forName(className(HostClass.StableImageLoader), false, classLoader),
                Class.forName("androidx.compose.runtime.Composer", false, classLoader),
                Int::class.javaPrimitiveType!!,
            )
            Class.forName(className(HostClass.HomeGridTileRenderer), false, classLoader).declaredMethods.singleOrNull { method ->
                Modifier.isStatic(method.modifiers) && method.name == "b" && method.returnType == Void.TYPE &&
                    (method.parameterTypes.contentEquals(expected) ||
                        method.parameterTypes.contentEquals(expected.take(3).toTypedArray() + String::class.java + expected.drop(3)))
            }?.apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveHomeFeedCanBlurMapper(classLoader: ClassLoader): Method? {
        return try {
            val candidates = Class.forName(className(HostClass.HomeFeedMapper), false, classLoader).declaredMethods.filter { method ->
                val parameters = method.parameterTypes
                Modifier.isStatic(method.modifiers) && method.name == "a" && method.returnType.name == className(HostClass.HomeFeedItem) &&
                    parameters.size in 19..20 && parameters[0].name == className(HostClass.HomeFeedMapper) && parameters[1].name == className(HostClass.HomeFeedContent) &&
                    parameters.count { it.name == className(HostClass.FeedBlurOptions) } == 1 && parameters.last() == Int::class.javaPrimitiveType
            }
            candidates.singleOrNull()?.apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveHomeFeedItemEmitter(classLoader: ClassLoader): Method? {
        return try {
            Class.forName(className(HostClass.HomeFeedItemEmitter), false, classLoader)
                .getDeclaredMethod("emit", Any::class.java, Class.forName(className(HostClass.Continuation), false, classLoader))
                .apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveFriendsOfFriendsFeedItemEmitter(classLoader: ClassLoader): Method? {
        return try {
            Class.forName(className(HostClass.FriendsOfFriendsItemEmitter), false, classLoader)
                .getDeclaredMethod("emit", Any::class.java, Class.forName(className(HostClass.Continuation), false, classLoader))
                .apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadKnown(classLoader: ClassLoader, name: String): Class<*>? {
        return try {
            Class.forName(name, false, classLoader)
        } catch (_: Throwable) {
            null
        }
    }
}

class RealMojiMenuContract(
    val actionClass: Class<*>,
    val selectedConstructor: java.lang.reflect.Constructor<*>,
    val entryConstructor: java.lang.reflect.Constructor<*>,
    val immutableListCopy: Method,
    val clickedMethod: Method,
    val clickedResult: Any?,
    val viewerModelField: Field,
    val entryTagField: Field,
)

class OfficialPostObservationContract(
    val currentUserClass: Class<*>,
    val mediaModelClass: Class<*>,
    val mediaAspectRatioField: Field,
    val createContinuationClass: Class<*>,
    val pendingCoreField: Field,
    val publishMethods: List<Method>,
    val unsentSaveMethods: List<Method>,
)

class HomeGridTileContract(
    val tileClass: Class<*>,
    val fields: Array<Field>,
    val blurredField: Field,
    val constructor: java.lang.reflect.Constructor<*>,
) {
    fun unblur(tile: Any): Any {
        val copied = Array<Any?>(fields.size) { index -> fields[index].get(tile) }
        copied[5] = false
        return constructor.newInstance(*copied)
    }
}

class HomeGridClickContract(
    val tileContainerClass: Class<*>,
    val tileModelClass: Class<*>,
    val branchField: Field,
    val callbackField: Field,
    val tileContainerField: Field,
    val tileModelField: Field,
    val postIdField: Field,
    val callbackInvoke: Method,
    val clickInvoke: Method,
)

class FeedBlurOptionsContract(
    val optionsClass: Class<*>,
    private val fields: Array<Field>,
    val constructor: java.lang.reflect.Constructor<*>,
) {
    fun withBlurDisabled(options: Any): Any {
        if (!optionsClass.isInstance(options)) return options
        val values = Array<Any?>(fields.size) { i -> fields[i].getBoolean(options) }
        if (values[1] == true && values[2] == true && values[3] == false) return options
        values[1] = true
        values[2] = true
        values[3] = false
        return constructor.newInstance(*values)
    }
}

class FeedBlurModelRewriter(
    val postClass: Class<*>,
    val blurredStateClass: Class<*>,
    private val stateField: Field,
    private val postFields: Array<Field>,
    val postConstructor: java.lang.reflect.Constructor<*>,
    val regularState: Any,
) {
    fun regularize(post: Any?): Any? {
        if (post == null || !postClass.isInstance(post) || !blurredStateClass.isInstance(stateField.get(post))) {
            return post
        }
        val values = Array<Any?>(postFields.size) { i -> postFields[i].get(post) }
        values[5] = regularState
        return postConstructor.newInstance(*values)
    }
}

class MediaFlipContract(
    val constructor: java.lang.reflect.Constructor<*>,
    val kindField: Field,
    val stateField: Field,
    val invokeMethod: Method,
)

/** A version-owned binding into its captured lifecycle DEX. */
class LifecycleBinding(
    private val owner: String,
    private val fieldName: String,
    private val implementation: String,
    private val methodName: String,
    private val parameters: List<String>,
) {
    private fun field(loader: ClassLoader): Field = Class.forName(owner, false, loader)
        .getDeclaredField(fieldName).apply {
            require(type == Method::class.java && Modifier.isStatic(modifiers))
            isAccessible = true
        }

    fun isPresent(loader: ClassLoader): Boolean = runCatching { field(loader) }.isSuccess

    fun install(loader: ClassLoader, generated: ClassLoader) {
        val method = Class.forName(implementation, false, generated).getDeclaredMethod(methodName,
            *parameters.map { Class.forName(it, false, loader) }.toTypedArray())
        require(Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE)
        field(loader).set(null, method)
    }
}
