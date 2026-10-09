package dev.tqmane.befuck.symbols

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Resolved once by version; implementations contain direct, complete mappings. */
abstract class HostMappings(val versionName: String, val versionCode: Long) {
    abstract val classes: Map<HostClass, String>
    abstract val methods: Map<HostMethod, String>
    open val requiresRuntimeRecovery: Boolean = false
    open val signingCertificateSha256: String? = null
    open val lifecycleDexAsset: String? = null
    open val lifecycleBindings: List<LifecycleBinding> = emptyList()

    fun isKnownVersion(name: String?, code: Long) =
        name == versionName && code == versionCode

    fun className(role: HostClass): String = classes.getValue(role)
    fun methodName(role: HostMethod): String = methods.getValue(role)
    fun type(role: HostClass, loader: ClassLoader): Class<*> = Class.forName(className(role), false, loader)

    open fun emailAnalyticsGuard(loader: ClassLoader): EmailAnalyticsGuard? = null
    open fun authDiagnosticConstructors(loader: ClassLoader): Map<String, java.lang.reflect.Constructor<*>> = emptyMap()
    open fun textFieldFocusGuard(loader: ClassLoader): TextFieldFocusGuard? = null
    open fun smsRequestConstructor(loader: ClassLoader): java.lang.reflect.Constructor<*>? = null
    open fun smsRequestShape(loader: ClassLoader, args: Array<Any?>): String = "unsupportedVersion=true"
    open fun preludeLibraryLoad(loader: ClassLoader): Method? = null
    open fun preludeLibraryInterfaces(loader: ClassLoader): List<Class<*>> = emptyList()
    open fun repackagedStartupChecks(loader: ClassLoader): List<Method> = emptyList()
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

    fun resolveCurrentUserProvider(classLoader: ClassLoader): Pair<Class<*>?, java.lang.reflect.Method?> {
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
            cameraViewModel.getDeclaredMethod(
                "I",
                Class.forName("androidx.lifecycle.LifecycleOwner", false, classLoader),
                facingEnum,
            ).apply { isAccessible = true }
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

    fun resolveTimelineBlurredCardComposable(classLoader: ClassLoader): Method? {
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

    fun resolvePullDownGridCardComposable(classLoader: ClassLoader): Method? {
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

    fun resolvePullDownGridMediaComposable(classLoader: ClassLoader): Method? {
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
                    method.parameterTypes.contentEquals(expected)
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
                    parameters.size == 19 && parameters[0].name == className(HostClass.HomeFeedMapper) && parameters[1].name == className(HostClass.HomeFeedContent) &&
                    parameters[9].name == className(HostClass.FeedBlurOptions) && parameters[18] == Int::class.javaPrimitiveType
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

    private fun isContinuationContract(type: Class<*>): Boolean {
        val methods = type.methods
        return methods.any { it.name == "resumeWith" && it.parameterTypes.contentEquals(arrayOf(Any::class.java)) && it.returnType == Void.TYPE } &&
            methods.any { it.name == "getContext" && it.parameterCount == 0 && it.returnType != Void.TYPE }
    }}

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
