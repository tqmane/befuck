package dev.tqmane.befuck.symbols

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Explicit 3.97.0-only fallback symbols; production queries run through DexKit first. */
internal object KnownMappings3970 {
    const val VERSION_NAME = "3.97.0"
    const val VERSION_CODE = 3597523L
    @JvmStatic
    @JvmOverloads
    fun isKnownVersion(name: String?, code: Long = dev.tqmane.befuck.runtime.RuntimeKnowledge.versionCode) =
        name == VERSION_NAME && code == VERSION_CODE
    const val KOIN_RESOLVER_METHOD_NAME = "e"
    const val MAPS_CAMERA_IDLE_DESCRIPTOR = "com.google.android.gms.maps.internal.IOnCameraIdleListener"
    // SHA-256 of the signing certificate verified on the original Play base APK.
    const val SIGNING_CERTIFICATE_SHA256 = "Lv29c48/2ukB7LlurS17mx7m0s6rkRvfexo9+BLzz8Q="

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

    @JvmStatic
    fun emailAnalyticsGuard(loader: ClassLoader, name: String?, code: Long): EmailAnalyticsGuard? {
        if (!isKnownVersion(name, code)) return null
        val step = Class.forName("a2", false, loader)
        val origin = Class.forName("w1", false, loader)
        val source = Class.forName("k2", false, loader)
        val base = Class.forName("s7", false, loader)
        val event = Class.forName("zb0", false, loader)
        require(step.isEnum)
        fun field(owner: Class<*>, name: String, type: Class<*>, static: Boolean) =
            owner.getDeclaredField(name).apply {
                require(this.type == type && Modifier.isStatic(modifiers) == static)
                isAccessible = true
            }
        val viewed = Class.forName("c2", false, loader)
        val completed = Class.forName("b2", false, loader)
        require(viewed.superclass == base && completed.superclass == base && event.isAssignableFrom(base))
        val method = Class.forName("dc0", false, loader).getDeclaredMethod("b", event).apply {
            require(returnType == Void.TYPE && !Modifier.isStatic(modifiers))
        }
        return EmailAnalyticsGuard(mapOf(
            viewed.getDeclaredConstructor(step, origin, source) to listOf(
                field(viewed, "d", step, false), field(viewed, "e", origin, false), field(viewed, "f", source, false)),
            completed.getDeclaredConstructor(step, origin, String::class.java, Integer::class.java) to listOf(
                field(completed, "d", step, false), field(completed, "e", origin, false),
                field(completed, "f", String::class.java, false), field(completed, "g", Integer::class.java, false))),
            base.getDeclaredConstructor(Int::class.javaPrimitiveType, List::class.java, String::class.java),
            method, mapOf(viewed to "Onboarding step viewed", completed to "Onboarding step completed"),
            field(step, "a", String::class.java, false),
            step.enumConstants.single { (it as Enum<*>).name == "EMAIL_ADDRESS" })
    }

    @JvmStatic
    fun authDiagnosticConstructors(loader: ClassLoader, name: String?, code: Long): Map<String, java.lang.reflect.Constructor<*>> {
        if (!isKnownVersion(name, code)) return emptyMap()
        return mapOf(
            "request_failure" to Class.forName("r3", false, loader)
                .getDeclaredConstructor(String::class.java, String::class.java),
            "challenge_token" to Class.forName("dj0", false, loader)
                .getDeclaredConstructor(String::class.java, String::class.java),
            "recaptcha_initialization" to Class.forName("c0", false, loader)
                .getDeclaredConstructor(Integer::class.java, String::class.java),
        )
    }

    @JvmStatic
    fun preludeLibraryLoad(loader: ClassLoader, name: String?, code: Long): Method? {
        if (!isKnownVersion(name, code)) return null
        val library = Class.forName("com.sun.jna.Library", false, loader)
        return Class.forName("com.sun.jna.Native", false, loader)
            .getDeclaredMethod("load", String::class.java, Class::class.java)
            .apply { require(Modifier.isStatic(modifiers) && returnType == library) }
    }

    @JvmStatic
    fun preludeLibraryInterfaces(loader: ClassLoader, name: String?, code: Long): List<Class<*>> {
        if (!isKnownVersion(name, code)) return emptyList()
        val library = Class.forName("com.sun.jna.Library", false, loader)
        return listOf("so.prelude.android.sdk.IntegrityCheckingUniffiLib", "so.prelude.android.sdk.UniffiLib")
            .map { Class.forName(it, false, loader).apply { require(isInterface && library.isAssignableFrom(this)) } }
    }

    @JvmStatic
    fun repackagedStartupChecks(loader: ClassLoader, name: String?, code: Long): List<Method> {
        if (!isKnownVersion(name, code)) return emptyList()
        return listOf(
            "com.pairip.licensecheck.LicenseClient" to "checkLicense",
        ).map { (owner, method) ->
            Class.forName(owner, false, loader)
                .getDeclaredMethod(method, android.content.Context::class.java)
                .apply { require(Modifier.isStatic(modifiers) && returnType == Void.TYPE) }
        }
    }

    @JvmStatic
    fun composeRuntimeMethods(loader: ClassLoader, versionName: String?): Map<String, Method> {
        if (!isKnownVersion(versionName)) return emptyMap()
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
                    .getDeclaredMethod("a", Any::class.java, Class.forName("ns8", false, loader), composer)
                    .apply { require(returnType == Void.TYPE && Modifier.isStatic(modifiers)); isAccessible = true },
            )
        }.getOrDefault(emptyMap())
    }

    private val stringFieldMappings = mapOf(
        "vungleAnalyticsTopic" to Pair("com.vungle.ads.internal.ST.IRpbunJxa", "STKzZyzJVBhw"),
        "safeDkAnalyticsTopic" to Pair("com.safedk.android.a.vjX.lUFzKIsaaZRNy", "arsx"),
        "roomBtsSignedUrl" to Pair("com.bytedance.sdk.openadsdk.component.vy.JU.yYINyKZpfL", "VCyo"),
        "roomIdColumn" to Pair("com.iab.omid.library.applovin.adsession.media.xOo.zYCQVUIuKPfxwZ", "MRvcN"),
        "roomSecondaryThumbnailHeight" to Pair("com.iab.omid.library.applovin.adsession.media.xOo.zYCQVUIuKPfxwZ", "wGlGUsFj"),
        "roomMusicPreview" to Pair("com.google.firebase.crashlytics.yhxg.ZXgeV", "FYJMQ"),
        "roomConversationCreatedAt" to Pair("androidx.arch.core.internal.LSIM.KAqdjhmN", "hAUc"),
        "roomUserProfileQuery" to Pair("com.applovin.mediation.adapters.googleadmanager.gkls.musXWZnlt", "AYlf"),
        "chatMessageViewTag" to Pair("com.moloco.sdk.internal.client_metrics_data.dcLb.gvCIexqtCeo", "HYGFD"),
        "notificationRealMojiPrefix" to Pair("androidx.transition.lG.taPxUPuw", "qKkC"),
        "deepLinkSeparator" to Pair("androidx.credentials.gZ.MqonvtnPZU", "GiMzDN"),
        "databaseRequestedState" to Pair("com.iab.omid.library.pubmatic.ac.PiCGar", "ENEUGjHKnBxxlYd"),
        "cameraStateInvariantMessage" to Pair("com.iab.omid.library.pubmatic.ac.PiCGar", "bAHEXeqPlRpBzwT"),
        "cameraDefaultFragmentShader" to Pair("com.google.firebase.crashlytics.yhxg.ZXgeV", "pKKzQaQtzlSwKG"),
        "cameraShaderCompilePrefix" to Pair("com.inmobi.ads.core.uYC.mGRrumjRBbsrfS", "PaLKEG"),
        "cameraRotationError" to Pair("androidx.compose.foundation.text.input.internal.ZDPh.NhqDXGO", "dRv"),
        "previewSurfaceRequestMessage" to Pair("com.apm.insight.e.uh.thXpHaHWWbSj", "mbDTg"),
        "composeSemanticsTrace" to Pair("androidx.credentials.gZ.MqonvtnPZU", "sldKPsd"),
        "cameraPostAnalyticsValue" to Pair("net.pubnative.lite.sdk.utils.json.bp.yXJk", "mXnp"),
        "recapSemantics" to Pair("okhttp3.internal.connection.udV.ewVWKVT", "xjq"),
        "cameraCountdownInitialText" to Pair("okhttp3.internal.connection.udV.ewVWKVT", "PLXlOOMCGp"),
        "crashlyticsFidPrefix" to Pair("androidx.credentials.gZ.MqonvtnPZU", "dwQiBYgKD"),
        "glanceCharset" to Pair("com.iab.omid.library.applovin.adsession.media.xOo.zYCQVUIuKPfxwZ", "vqwT"),
        "androidIdSettingName" to Pair("com.pgl.ssdk.tw.UlZgpVqmHCioxr", "gMwjdPQYDjcRHgy"),
        "crashlyticsProcessName" to Pair("com.pgl.ssdk.tw.UlZgpVqmHCioxr", "ehIVmsGFi"),
        "ktorDefaultUserAgent" to Pair("androidx.media3.extractor.text.pgs.wtco.kKFOp", "fZgFGgPb"),
        "sourcepointUsNatSampleRate" to Pair("bereal.app.features.sharing.ui.VrJ.usGKIW", "HAFqVkV"),
        "mapsCameraIdleDescriptor" to Pair("com.yoti.mobile.android.liveness.zoom.view.navigation.sjLd.aExjrwjFHmK", "FSmVbqmP"),
    )

    // Confirmed constants for this APK; dynamic countdown values are recovered separately.
    private val runtimeStringValues = mapOf(
        "vungleAnalyticsTopic" to "__unresolved_pairip_topic_vungle__",
        "safeDkAnalyticsTopic" to "__unresolved_pairip_topic_safedk__",
        "roomBtsSignedUrl" to "btsSignedUrl",
        "roomIdColumn" to "id",
        "roomSecondaryThumbnailHeight" to "secondary_thumbnail_height",
        "roomMusicPreview" to "music_preview",
        "roomConversationCreatedAt" to "createdAt",
        "roomUserProfileQuery" to "SELECT * FROM `UserProfileEntity` WHERE `userId` IN (",
        "chatMessageViewTag" to "chat_message_",
        "recapSemantics" to "allrecapscreen_recap",
        "crashlyticsFidPrefix" to "Fetched Firebase Installation ID: ",
        "glanceCharset" to "ISO-8859-1",
        "androidIdSettingName" to "android_id",
        "crashlyticsProcessName" to "processName",
        "ktorDefaultUserAgent" to "Ktor http-client",
        "sourcepointUsNatSampleRate" to "sampleRate",
        "notificationRealMojiPrefix" to "realmoji",
        "deepLinkSeparator" to "://",
        "databaseRequestedState" to "REQUESTED",
        "cameraStateInvariantMessage" to "Camera2CameraImpl state invariant failed: ",
        "cameraDefaultFragmentShader" to "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nvarying vec2 vTextureCoord;\nuniform samplerExternalOES sTexture;\nuniform float uAlphaScale;\nvoid main() {\n    vec4 src = texture2D(sTexture, vTextureCoord);\n    gl_FragColor = vec4(src.rgb, src.a * uAlphaScale);\n}\n",
        "cameraShaderCompilePrefix" to "glCreateShader type=",
        "cameraRotationError" to "Invalid rotation degrees: ",
        "previewSurfaceRequestMessage" to "Surface requested by Preview.",
        "composeSemanticsTrace" to "Compose:semantics:sendSemanticsPropertyChangeEvents",
        "cameraPostAnalyticsValue" to "post",
    )

    @JvmStatic
    fun runtimeStringRepairs(classLoader: ClassLoader, versionName: String?, symbols: ResolvedSymbols?): Map<Field, String> {
        if (!isKnownVersion(versionName)) return emptyMap()
        return runtimeStringValues.mapNotNull { (symbol, value) ->
            (symbols?.stringField(symbol) ?: resolveStringField(classLoader, versionName, symbol))?.let { it to value }
        }.toMap()
    }

    fun missingStringRepairs(classLoader: ClassLoader, versionName: String?): Map<Field, String> =
        resolveStringField(classLoader, versionName, "mapsCameraIdleDescriptor")?.let {
            mapOf(it to MAPS_CAMERA_IDLE_DESCRIPTOR)
        } ?: emptyMap()

    /** Local Binder test fixture; never calls a map callback or a remote service. */
    fun newCameraIdleCallback(classLoader: ClassLoader, versionName: String?): android.os.Binder {
        require(isKnownVersion(versionName))
        return Class.forName("com.google.android.gms.maps.w", false, classLoader)
            .getDeclaredConstructor(Class.forName("nvb", false, classLoader)).newInstance(null) as android.os.Binder
    }

    fun resolveStringField(classLoader: ClassLoader, versionName: String?, symbol: String): Field? {
        if (!isKnownVersion(versionName)) return null
        val mapping = stringFieldMappings[symbol] ?: return null
        return try {
            Class.forName(mapping.first, false, classLoader)
                .getDeclaredField(mapping.second)
                .takeIf { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                ?.apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveCameraViewModel(classLoader: ClassLoader, versionName: String?): Class<*>? =
        loadKnown(classLoader, versionName, "uo2")

    fun resolveCameraFacingEnum(classLoader: ClassLoader, versionName: String?): Class<*>? =
        loadKnown(classLoader, versionName, "re7")

    fun resolveCameraCountdownComposable(classLoader: ClassLoader, versionName: String?): java.lang.reflect.Method? {
        if (!isKnownVersion(versionName)) return null
        return try {
            Class.forName("n57", false, classLoader)
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

    fun resolveLocationRepository(classLoader: ClassLoader, versionName: String?): Class<*>? =
        loadKnown(classLoader, versionName, "qfb")

    fun resolveCurrentUserProvider(classLoader: ClassLoader, versionName: String?): Pair<Class<*>?, java.lang.reflect.Method?> {
        if (!isKnownVersion(versionName)) return null to null
        return try {
            val provider = Class.forName("w19", false, classLoader)
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

    fun resolveKoinApplication(classLoader: ClassLoader, versionName: String?): Any? {
        if (!isKnownVersion(versionName)) return null
        return try {
            Class.forName("kxa", false, classLoader)
                .getDeclaredMethod("A")
                .apply { isAccessible = true }
                .invoke(null)
        } catch (_: Throwable) {
            null
        }
    }

    fun resolvePostUploadSchedulerClass(classLoader: ClassLoader, versionName: String?): Class<*>? =
        loadKnown(classLoader, versionName, "g34")

    fun resolvePostUploadSchedulerMethod(
        classLoader: ClassLoader,
        versionName: String?,
        schedulerClass: Class<*>?,
    ): java.lang.reflect.Method? {
        if (!isKnownVersion(versionName) || schedulerClass == null) return null
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

    fun resolveCameraOriginParsers(classLoader: ClassLoader, versionName: String?): List<java.lang.reflect.Method> {
        if (!isKnownVersion(versionName)) return emptyList()
        val mappings = listOf("y6n" to "S", "x6n" to "N")
        return mappings.mapNotNull { (className, methodName) ->
            try {
                Class.forName(className, false, classLoader)
                    .getDeclaredMethod(methodName, android.os.Bundle::class.java)
                    .apply { isAccessible = true }
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun resolveCameraBindMethod(
        classLoader: ClassLoader,
        versionName: String?,
        cameraViewModel: Class<*>?,
        facingEnum: Class<*>?,
    ): java.lang.reflect.Method? {
        if (!isKnownVersion(versionName) || cameraViewModel == null || facingEnum == null) return null
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

    fun resolveLocationRequestMethod(classLoader: ClassLoader, versionName: String?, repository: Class<*>?): java.lang.reflect.Method? {
        if (!isKnownVersion(versionName) || repository == null) return null
        return try {
            repository.getDeclaredMethod("b", Boolean::class.javaPrimitiveType!!, Class.forName("wx4", false, classLoader))
                .apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveLocationClientGetter(versionName: String?, repository: Class<*>?): java.lang.reflect.Method? {
        if (!isKnownVersion(versionName) || repository == null) return null
        return try {
            repository.getDeclaredMethod("a").apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveTimelineBlurredCardComposable(classLoader: ClassLoader, versionName: String?): Method? {
        if (!isKnownVersion(versionName)) return null
        return try {
            val composer = Class.forName("androidx.compose.runtime.Composer", false, classLoader)
            val modifier = Class.forName("androidx.compose.ui.Modifier", false, classLoader)
            val pager = Class.forName("androidx.compose.foundation.pager.PagerState", false, classLoader)
            val lambda = Class.forName("androidx.compose.runtime.internal.ComposableLambdaImpl", false, classLoader)
            val expected = arrayOf(
                modifier,
                Class.forName("y2a", false, classLoader),
                Class.forName("r1g", false, classLoader),
                pager,
                lambda,
                Boolean::class.javaPrimitiveType!!,
                Class.forName("js8", false, classLoader),
                composer,
                Int::class.javaPrimitiveType!!,
            )
            val candidates = Class.forName("ra7", false, classLoader).declaredMethods.filter { method ->
                Modifier.isStatic(method.modifiers) && method.name == "a" && method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(expected)
            }
            candidates.singleOrNull()?.apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolvePullDownGridCardComposable(classLoader: ClassLoader, versionName: String?): Method? {
        if (!isKnownVersion(versionName)) return null
        return try {
            val expected = arrayOf(
                Class.forName("bi1", false, classLoader),
                String::class.java,
                String::class.java,
                Class.forName("po1", false, classLoader),
                Class.forName("zh6", false, classLoader),
                Class.forName("y2a", false, classLoader),
                Boolean::class.javaPrimitiveType!!,
                Class.forName("js8", false, classLoader),
                Class.forName("js8", false, classLoader),
                Class.forName("js8", false, classLoader),
                Class.forName("ps8", false, classLoader),
                Class.forName("ns8", false, classLoader),
                Class.forName("huh", false, classLoader),
                Class.forName("wrh", false, classLoader),
                Class.forName("yci", false, classLoader),
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Class.forName("js8", false, classLoader),
                Boolean::class.javaPrimitiveType!!,
                Class.forName("androidx.compose.runtime.Composer", false, classLoader),
                Int::class.javaPrimitiveType!!,
            )
            Class.forName("gsh", false, classLoader).declaredMethods.singleOrNull { method ->
                Modifier.isStatic(method.modifiers) && method.name == "a" && method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(expected)
            }?.apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolvePullDownGridMediaComposable(classLoader: ClassLoader, versionName: String?): Method? {
        if (!isKnownVersion(versionName)) return null
        return try {
            val modifier = Class.forName("androidx.compose.ui.Modifier", false, classLoader)
            val state = Class.forName("androidx.compose.runtime.State", false, classLoader)
            val function1 = Class.forName("ns8", false, classLoader)
            val function0 = Class.forName("js8", false, classLoader)
            val bitmapConfig = Class.forName("android.graphics.Bitmap\$Config", false, classLoader)
            val expected = arrayOf(
                Class.forName("zh6", false, classLoader),
                modifier,
                state,
                Boolean::class.javaPrimitiveType!!,
                Class.forName("oh6", false, classLoader),
                Class.forName("fj6", false, classLoader),
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
                Class.forName("tam", false, classLoader),
                function1,
                function1,
                Class.forName("ai6", false, classLoader),
                Boolean::class.javaPrimitiveType!!,
                bitmapConfig,
                Class.forName("bi6", false, classLoader),
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
            Class.forName("mi6", false, classLoader).declaredMethods.singleOrNull { method ->
                Modifier.isStatic(method.modifiers) && method.name == "a" && method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(expected)
            }?.apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveHomeGridPostTileComposable(classLoader: ClassLoader, versionName: String?): Method? {
        if (!isKnownVersion(versionName)) return null
        return try {
            val expected = arrayOf(
                Class.forName("f3i", false, classLoader),
                Class.forName("js8", false, classLoader),
                Class.forName("tam", false, classLoader),
                Class.forName("androidx.compose.runtime.Composer", false, classLoader),
                Int::class.javaPrimitiveType!!,
            )
            Class.forName("z89", false, classLoader).declaredMethods.singleOrNull { method ->
                Modifier.isStatic(method.modifiers) && method.name == "b" && method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(expected)
            }?.apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveHomeFeedCanBlurMapper(classLoader: ClassLoader, versionName: String?): Method? {
        if (!isKnownVersion(versionName)) return null
        return try {
            val candidates = Class.forName("fl7", false, classLoader).declaredMethods.filter { method ->
                val parameters = method.parameterTypes
                Modifier.isStatic(method.modifiers) && method.name == "a" && method.returnType.name == "el7" &&
                    parameters.size == 19 && parameters[0].name == "fl7" && parameters[1].name == "tm7" &&
                    parameters[9].name == "wl7" && parameters[18] == Int::class.javaPrimitiveType
            }
            candidates.singleOrNull()?.apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveHomeFeedItemEmitter(classLoader: ClassLoader, versionName: String?): Method? {
        if (!isKnownVersion(versionName)) return null
        return try {
            Class.forName("an9", false, classLoader)
                .getDeclaredMethod("emit", Any::class.java, Class.forName("vx4", false, classLoader))
                .apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveFriendsOfFriendsFeedItemEmitter(classLoader: ClassLoader, versionName: String?): Method? {
        if (!isKnownVersion(versionName)) return null
        return try {
            Class.forName("r68", false, classLoader)
                .getDeclaredMethod("emit", Any::class.java, Class.forName("vx4", false, classLoader))
                .apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadKnown(classLoader: ClassLoader, versionName: String?, name: String): Class<*>? {
        if (!isKnownVersion(versionName)) return null
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
    }
}
