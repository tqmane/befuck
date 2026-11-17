package dev.tqmane.befuck.hooks

import android.content.Context
import android.view.View
import dev.tqmane.befuck.archive.ArchiveAccounts
import dev.tqmane.befuck.download.FeedPostMedia
import dev.tqmane.befuck.download.FeedPostMediaCache
import dev.tqmane.befuck.download.MapLocationPrefetcher
import dev.tqmane.befuck.posting.GalleryPostController
import dev.tqmane.befuck.runtime.ComposeHookScope
import dev.tqmane.befuck.symbols.FeedMediaSymbols
import dev.tqmane.befuck.symbols.HostClass
import dev.tqmane.befuck.symbols.KnownMappings
import dev.tqmane.befuck.symbols.ResolvedSymbols
import dev.tqmane.befuck.ui.BeFuckGalleryUi
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** Feed media/location capture, local unblur, inline/detail download overlays, and grid routing hooks. */
internal class FeedMediaHooks(private val host: HookHost) {
    private val homeFeedModelMapping = ThreadLocal<Boolean>()
    private val friendsOfFriendsModelMapping = ThreadLocal<Boolean>()
    private val inlineMediaComposition = ThreadLocal<Boolean>()

    private class ComposeDownloadOverlaySymbols(
        val function1Class: Class<*>,
        val androidViewComposable: Method,
        val zIndexModifier: Method,
        val fillMaxSizeModifier: Any,
        val fillMaxSizeTopmostModifier: Any,
        val kotlinUnit: Any,
    )

    fun installComposeInjectionScope(loader: ClassLoader) {
        val mappings = host.mappings ?: return
        val methods = mappings.composeRuntimeMethods(loader)
        if (methods.isEmpty()) {
            host.info("Compose download additions disabled: runtime signatures unresolved")
            return
        }
        try {
            host.hook(methods.getValue("start")).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val composer = chain.proceed()
                    ComposeHookScope.started(composer)
                    composer
                }
            host.hook(methods.getValue("execute")).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val execute = chain.proceed()
                    ComposeHookScope.executed(chain.thisObject, execute == true)
                    execute
                }
            host.hook(methods.getValue("end")).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    ComposeHookScope.ending(chain.thisObject)
                    val scope = chain.proceed()
                    ComposeHookScope.ended(chain.thisObject)
                    scope
                }
            host.composeInjectionReady = true
            host.info("Installed restart-scope Compose additions with paused/skipped composition tracking")
        } catch (failure: Throwable) {
            host.error("Could not install Compose download scope tracking", failure)
        }
    }

    fun installFeedMediaCaptureAndUnblur(classLoader: ClassLoader, symbols: ResolvedSymbols?) {
        val media = symbols?.feedMediaSymbols
        host.info(
            "Installing feed media hooks; capture=${media != null && media.canCaptureFeedMedia}, " +
                "unblur=${media != null && media.canUnblurLocally}",
        )
        if (media == null) {
            host.info("[SymbolResolver] Feed media capture and local unblur disabled: media symbols unresolved")
            return
        }

        installFriendLocationCapture(media)
        installPostDataIdentityCapture(media)
        val semanticCoreHooked = installCorePostModelCapture(media, symbols.versionName)
        if (!semanticCoreHooked && KnownMappings.isSupportedVersion(symbols.versionName)) {
            installLegacyCorePostCapture(classLoader, media)
        }

        if (KnownMappings.isSupportedVersion(symbols.versionName)) {
            installOfficialPostAndMediaObservation(classLoader)
        }

        media.mapPlaceholderLocationSymbols?.let { mapSymbols ->
            try {
                for (constructor in mapSymbols.constructors) {
                    host.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept { chain ->
                            val result = chain.proceed()
                            FeedPostMediaCache.captureMapPlaceholder(chain.thisObject, media)
                            result
                        }
                }
                host.info("Capturing post locations authorized by the host's map friendship state")
                MapLocationPrefetcher.configure(mapSymbols, media.corePostLocationSymbols)
                if (mapSymbols.fetchMethod != null) {
                    host.info("Background location prefetch armed through the host process container")
                }
            } catch (failure: Throwable) {
                host.error("Could not capture map location metadata", failure)
            }
        }

        installFeedCardHook(classLoader, media)
        installPostDeserializerHook(media)
        installRenderedMediaHooks(classLoader, symbols, media)
    }

    private fun installFriendLocationCapture(media: FeedMediaSymbols) {
        val graph = media.friendGraphSymbols ?: return
        try {
            host.hook(graph.query).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val scope = ArchiveAccounts.snapshot()
                    val observation = try {
                        graph.observe(chain.getArg(0), scope.uid)
                    } catch (_: Throwable) {
                        return@intercept chain.proceed()
                    } ?: return@intercept chain.proceed()
                    val args = chain.args.toTypedArray()
                    args[0] = observation.connection
                    val token = FeedPostMediaCache.beginFriendSnapshot()
                    // The observer forwards the existing query; its original result and
                    // exceptions stay with the host. Only a proven complete Friends set
                    // can authorize exact coordinates in subsequent archive captures.
                    val result = chain.proceed(args)
                    try {
                        observation.friendUids(result)?.let { friends ->
                            FeedPostMediaCache.captureObservedFriendUids(
                                observation.observedViewerUid,
                                scope.uid,
                                scope.generation,
                                token,
                                friends,
                            )
                        }
                    } catch (_: Throwable) {
                        host.info("Skipped an unavailable friend-location observation")
                    }
                    result
                }
            host.info("Observing the host's complete Friends graph for saved post locations")
        } catch (failure: Throwable) {
            host.error("Could not observe friend location visibility", failure)
        }
    }

    /**
     * Observe actual host PostDataUiModel construction, including offscreen feed posts.
     * Only isMyPost=true with its validated ownerUid may establish viewer identity.
     */
    private fun installPostDataIdentityCapture(media: FeedMediaSymbols) {
        val id = media.postDataIdField
        val owner = media.postDataOwnerUidField
        val own = media.postDataIsMineField
        if (id == null || owner == null || own == null ||
            id.declaringClass != owner.declaringClass ||
            id.declaringClass != own.declaringClass
        ) {
            return
        }
        try {
            val constructors = id.declaringClass.declaredConstructors
            if (constructors.isEmpty() || constructors.size > 8) return
            for (constructor in constructors) {
                if (constructor.isSynthetic) continue
                host.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            FeedPostMediaCache.capturePostDataIdentity(chain.thisObject, media)
                        } catch (failure: Throwable) {
                            host.info("Skipped host post-data identity observation: ${failure.javaClass.simpleName}")
                        }
                        result
                    }
            }
            host.info("Watching host PostDataUiModel identities for account-scoped feed location capture")
        } catch (failure: Throwable) {
            host.info("PostDataUiModel identity observation unavailable: ${failure.javaClass.simpleName}")
        }
    }

    /** Run independently of optional version-mapped User/media models. */
    private fun installCorePostModelCapture(media: FeedMediaSymbols, versionName: String?): Boolean {
        val reader = media.corePostLocationSymbols ?: return false
        return try {
            val constructors = reader.constructors
            if (constructors.isEmpty() || constructors.size > 8) return false
            for (constructor in constructors) {
                host.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            FeedPostMediaCache.captureCorePostModel(chain.thisObject, reader)
                            if (KnownMappings.isSupportedVersion(versionName)) {
                                FeedPostMediaCache.captureCorePost(chain.thisObject, media)
                            }
                        } catch (failure: Throwable) {
                            host.info("Skipped CorePost observation: ${failure.javaClass.simpleName}")
                        }
                        result
                    }
            }
            host.info("Installed CorePost location observations for feed-loaded models")
            true
        } catch (failure: Throwable) {
            host.info("CorePost observation unavailable: ${failure.javaClass.simpleName}")
            false
        }
    }

    private fun installLegacyCorePostCapture(loader: ClassLoader, media: FeedMediaSymbols) {
        val mappings = host.mappings ?: return
        try {
            val core = mappings.type(HostClass.CorePost, loader)
            for (constructor in core.declaredConstructors) {
                if (constructor.parameterCount != 28) continue
                host.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val result = chain.proceed()
                        FeedPostMediaCache.captureCorePost(chain.thisObject, media)
                        result
                    }
            }
            host.info("Installed legacy feed-model capture without requiring User/media hooks")
        } catch (failure: Throwable) {
            host.info("Legacy CorePost capture unavailable: ${failure.javaClass.simpleName}")
        }
    }

    private fun installOfficialPostAndMediaObservation(classLoader: ClassLoader) {
        val mappings = host.mappings ?: return
        try {
            val contract = mappings.officialPostObservationContract(classLoader)
            for (constructor in contract.mediaModelClass.declaredConstructors) {
                if (constructor.parameterCount != 4 || constructor.parameterTypes[0] != String::class.java) continue
                host.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val result = chain.proceed()
                        val height = chain.getArg(1) as Int
                        val width = chain.getArg(2) as Int
                        if (height > 0 && width > 0) {
                            contract.mediaAspectRatioField.setFloat(chain.thisObject, width.toFloat() / height)
                        }
                        result
                    }
            }
            for (constructor in contract.currentUserClass.declaredConstructors) {
                host.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val result = chain.proceed()
                        FeedPostMediaCache.captureCurrentUser(chain.thisObject)
                        result
                    }
            }
            // CorePost constructors have already been observed independently above.
            for (method in contract.publishMethods) {
                host.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        var core = chain.getArg(0)
                        val continuation = chain.getArg(4)
                        if (core == null && contract.createContinuationClass.isInstance(continuation)) {
                            core = contract.pendingCoreField.get(continuation)
                        }
                        val result = chain.proceed()
                        GalleryPostController.noteOfficialPostPublished(core, result)
                        result
                    }
            }
            for (method in contract.unsentSaveMethods) {
                host.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        GalleryPostController.noteOfficialPostSaved(chain.getArg(0))
                        chain.proceed()
                    }
            }
        } catch (error: Throwable) {
            host.error("Could not capture CorePost metadata", error)
        }
    }

    private fun installFeedCardHook(classLoader: ClassLoader, media: FeedMediaSymbols) {
        val mappings = host.mappings
        val hiddenSponsoredModifier = try {
            host.createZeroSizeComposeModifier(classLoader)
        } catch (failure: Throwable) {
            host.error("Could not resolve sponsored-card size modifier", failure)
            null
        }
        val sponsoredFields = ArrayList<Field>()
        media.viewStateRealSponsoredPostUiStateField?.let(sponsoredFields::add)
        try {
            if (mappings != null) {
                for (field in mappings.sponsoredFeedFields(classLoader)) {
                    if (field !in sponsoredFields) sponsoredFields.add(field)
                }
            }
        } catch (failure: Throwable) {
            host.error("Could not resolve the sponsored feed model fields", failure)
        }

        val feedCardMethod = media.postFeedCardComposableMethod ?: return
        try {
            host.hook(feedCardMethod)
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val args = chain.args.toTypedArray()
                    if (args.size > 1 && args[1] != null && hiddenSponsoredModifier != null) {
                        try {
                            val sponsored = sponsoredFields.any { field ->
                                field.declaringClass.isInstance(args[1]) && field.get(args[1]) != null
                            }
                            if (sponsored && feedCardMethod.parameterTypes[0].isInstance(hiddenSponsoredModifier)) {
                                host.logOnce(
                                    "sponsoredFeedPostSuppression",
                                    "Hid the sponsored feed card with a clipped zero-size modifier while preserving its composition",
                                )
                                args[0] = hiddenSponsoredModifier
                            }
                        } catch (_: Throwable) {
                            host.info("Sponsored feed-card state could not be read; leaving the card unchanged")
                        }
                    }
                    FeedPostMediaCache.beginVisiblePostComposition(args.getOrNull(1), media)
                    try {
                        val result = chain.proceed(args)
                        if (args.size > 1 && FeedPostMediaCache.captureVisibleFeedState(args[1], media)) {
                            host.logOnce(
                                "visibleFeedMediaCapture",
                                "Captured loaded primary/secondary/BTS media from the visible feed card state",
                            )
                        }
                        result
                    } finally {
                        FeedPostMediaCache.endVisiblePostComposition()
                    }
                }
            host.info("Hooked resolved feed-card composition for media indexing from local/remote models")
        } catch (error: Throwable) {
            host.error("Could not hook feed-card media indexing", error)
        }
    }

    private fun installPostDeserializerHook(media: FeedMediaSymbols) {
        val deserializer = media.postDeserializerMethod
        val postModel = media.postModelClass
        if (deserializer == null || postModel == null) {
            host.info("[SymbolResolver] PostRemoteModel media capture disabled: deserializer or model class unresolved")
            return
        }
        try {
            host.hook(deserializer)
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    host.logOnce(
                        "feedMediaDeserializerInvoked",
                        "PostRemoteModel deserializer invoked; returnedType=${result?.javaClass?.name ?: "null"}, " +
                            "expectedModel=${postModel.name}",
                    )
                    if (result != null && postModel.isInstance(result)) {
                        val captured = FeedPostMediaCache.capture(result, media)
                        if (captured) {
                            host.logOnce(
                                "feedMediaCapture",
                                "Captured feed post media URLs in the bounded in-memory download index",
                            )
                        } else {
                            host.logOnce(
                                "feedMediaCaptureFailed",
                                "PostRemoteModel parsed, but its media URLs did not match the validated capture shape",
                            )
                        }
                    }
                    result
                }
            host.info("Hooked the resolved PostRemoteModel deserializer for image/BTS download indexing")
        } catch (error: Throwable) {
            host.error("Could not hook PostRemoteModel media capture", error)
        }
    }

    private fun installRenderedMediaHooks(
        classLoader: ClassLoader,
        symbols: ResolvedSymbols,
        media: FeedMediaSymbols,
    ) {
        val mappings = host.mappings
        val renderMedia = media.blurredMediaRenderMethod
        if (renderMedia != null) {
            try {
                val overlaySymbols = resolveComposeDownloadOverlaySymbols(classLoader)
                val composerType = Class.forName("androidx.compose.runtime.Composer", false, classLoader)
                val feedComposerIndex = renderMedia.parameterTypes.indexOf(composerType)
                if (feedComposerIndex < 0) throw NoSuchMethodException("Feed media Composer parameter")
                host.hook(renderMedia)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val args = chain.args.toTypedArray()
                        if (FeedPostMediaCache.hasPendingGridPostForDetail()) {
                            host.logOnce("pendingDetailPmgSeen", "Selected-grid detail entered the pmg.a download hook")
                        }
                        if (args.size > 2 && args[2] == true) {
                            args[2] = false
                            host.logOnce("localUnblur", "Disabled BeReal feed media blur at its local rendering boundary")
                        }
                        val composer = args[feedComposerIndex]
                        var frame: ComposeHookScope.Frame? = null
                        if (host.composeInjectionReady && overlaySymbols != null && composer != null) {
                            frame = ComposeHookScope.push(composer) {
                                if (FeedPostMediaCache.hasPendingGridPostForDetail()) return@push
                                val post = FeedPostMediaCache.postForDualMedia(args[1]) ?: return@push
                                try {
                                    injectInlineDownloadOverlay(composer, classLoader, overlaySymbols, null, post, false)
                                } catch (failure: Throwable) {
                                    throw IllegalStateException("Could not compose the feed download control", failure)
                                }
                            }
                        }
                        val previousInline = inlineMediaComposition.get()
                        val ownsDownload = frame != null && !FeedPostMediaCache.hasPendingGridPostForDetail()
                        if (ownsDownload) inlineMediaComposition.set(true)
                        try {
                            chain.proceed(args)
                        } finally {
                            if (ownsDownload) {
                                if (previousInline == null) inlineMediaComposition.remove() else inlineMediaComposition.set(previousInline)
                            }
                            frame?.let(ComposeHookScope::pop)
                        }
                    }
                host.info("Hooked resolved feed-media renderer for local-only unblur and per-post downloads")
                installCurrentPostMediaOrientationHook(classLoader)
            } catch (error: Throwable) {
                host.error("Could not hook local feed-media unblur renderer", error)
            }
        }

        val blurredOverlay = media.blurredOverlayComposableMethod
        if (blurredOverlay != null) {
            try {
                val zeroSizeModifier = host.createZeroSizeComposeModifier(classLoader)
                host.hook(blurredOverlay)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val args = chain.args.toTypedArray()
                        if (args.isNotEmpty()) {
                            args[0] = zeroSizeModifier
                            chain.proceed(args)
                        } else {
                            chain.proceed()
                        }
                    }
                host.info("Hooked the resolved gated-post Compose overlay with a zero-size local modifier; composition and server post state are unchanged")
            } catch (error: Throwable) {
                host.error("Could not hide the gated-post overlay locally", error)
            }
        }

        val timelineBlurredCard = symbols.timelineBlurredCardComposableMethod
        if (timelineBlurredCard != null) {
            try {
                host.hook(timelineBlurredCard)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val args = chain.args.toTypedArray()
                        if (args.size > 5 && args[5] == true) {
                            args[5] = false
                            host.logOnce(
                                "timelineCellUnblur",
                                "Disabled the blurred timeline CTA at its local Compose rendering boundary; the camera action is no longer composed",
                            )
                            chain.proceed(args)
                        } else {
                            chain.proceed()
                        }
                    }
                host.info("Hooked the version-mapped timeline cell to remove its local blur gate and camera CTA")
            } catch (error: Throwable) {
                host.error("Could not hook the local timeline blur CTA", error)
            }
        }

        val gridCard = symbols.pullDownGridCardComposableMethod
        if (gridCard != null && mappings != null) {
            try {
                val blurArgIndex = mappings.gridCardBlurArgument
                host.hook(gridCard)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val args = chain.args.toTypedArray()
                        val gridDetailPostId = FeedPostMediaCache.beginGridDetailComposition(args.getOrNull(1) as? String)
                        if (gridDetailPostId != null) {
                            host.logOnce("detailGridComposerBound", "Bound gsh.a for the selected grid post's full-screen media composition")
                        }
                        if (FeedPostMediaCache.hasPendingGridPostForDetail()) {
                            host.logOnce("pendingDetailGshSeen", "Selected-grid detail entered the gsh.a card hook")
                        }
                        host.logOnce("pullDownGridCardSeen", "gsh.a card renderer invoked; blur-gate argument index 16=${args[blurArgIndex]}")
                        try {
                            var modified = false
                            if (args.size > 8 && args[7] != null && args[8] !== args[7]) {
                                args[8] = args[7]
                                modified = true
                                host.logOnce("pullDownGridDetailsClick", "Bound gsh.a tile clicks to the details callback instead of the camera callback")
                            }
                            if (args[blurArgIndex] == true) {
                                args[blurArgIndex] = false
                                modified = true
                                host.logOnce("pullDownGridCardUnblur", "Disabled the gsh.a card blur gate at its local Compose rendering boundary")
                            }
                            if (modified) chain.proceed(args) else chain.proceed()
                        } finally {
                            FeedPostMediaCache.endGridDetailComposition()
                        }
                    }
                host.info("Hooked the version-mapped gsh.a card renderer for local-only unblur")
            } catch (error: Throwable) {
                host.error("Could not hook the gsh.a card renderer", error)
            }
        }

        val gridMedia = symbols.pullDownGridMediaComposableMethod
        if (gridMedia != null && mappings != null) {
            try {
                val composerType = Class.forName("androidx.compose.runtime.Composer", false, classLoader)
                val functionType = mappings.type(HostClass.Function1, classLoader)
                val disposableType = Class.forName("androidx.compose.runtime.DisposableEffectResult", false, classLoader)
                val runtimeMethods = mappings.composeRuntimeMethods(classLoader)
                if (!host.composeInjectionReady || runtimeMethods.isEmpty()) throw NoSuchMethodException("Compose restart-scope methods")
                val beginGroup = runtimeMethods.getValue("replaceStart")
                val endGroup = runtimeMethods.getValue("replaceEnd")
                val effect = runtimeMethods.getValue("effect")
                val overlaySymbols = resolveComposeDownloadOverlaySymbols(classLoader)
                    ?: throw NoSuchMethodException("Detail download Compose symbols")
                val detailComposerIndex = gridMedia.parameterTypes.indexOf(composerType)
                if (detailComposerIndex < 0) throw NoSuchMethodException("Detail media Composer parameter")
                host.hook(gridMedia)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val args = chain.args.toTypedArray()
                        if (FeedPostMediaCache.hasPendingGridPostForDetail()) {
                            host.logOnce("pendingDetailMi6Seen", "Selected-grid detail entered the mi6.a media hook")
                        }
                        host.logOnce(
                            "pullDownGridMediaSeen",
                            "mi6.a media renderer invoked; isBlurred argument index 3=${if (args.size > 3) args[3] else "missing"}",
                        )
                        val detailPost = FeedPostMediaCache.composingGridDetailPost()
                            ?: args.getOrNull(0)?.let(FeedPostMediaCache::postForDualMedia)
                            ?: args.getOrNull(2)?.let(FeedPostMediaCache::postForDualMedia)
                        val composer = args[detailComposerIndex]
                        if (args.size > 3 && args[3] == true) {
                            args[3] = false
                            host.logOnce("pullDownGridMediaUnblur", "Disabled the mi6.a isBlurred gate at its local rendering boundary")
                        }
                        val hasInlineDownload = inlineMediaComposition.get() == true
                        val frame = ComposeHookScope.push(composer) {
                            try {
                                beginGroup.invoke(composer, 0x42524644)
                                try {
                                    if (!hasInlineDownload && detailPost is FeedPostMedia) {
                                        val callback = Proxy.newProxyInstance(
                                            classLoader,
                                            arrayOf(functionType),
                                            composeFunctionHandler(overlaySymbols) {
                                                val binding = BeFuckGalleryUi.showDetailDownloadButton(detailPost)
                                                Proxy.newProxyInstance(classLoader, arrayOf(disposableType)) { proxy, method, values ->
                                                    when (method.name) {
                                                        "dispose" -> {
                                                            BeFuckGalleryUi.disposeDetailDownloadButton(binding)
                                                            null
                                                        }
                                                        "hashCode" -> System.identityHashCode(proxy)
                                                        "equals" -> values != null && values.size == 1 && values[0] === proxy
                                                        else -> "BeFuckDetailDownloadLifetime"
                                                    }
                                                }
                                            },
                                        )
                                        effect.invoke(null, detailPost, callback, composer)
                                    }
                                } finally {
                                    endGroup.invoke(composer)
                                }
                            } catch (failure: Throwable) {
                                throw IllegalStateException("Could not compose the detail download lifetime", failure)
                            }
                        }
                        try {
                            chain.proceed(args)
                        } finally {
                            ComposeHookScope.pop(frame)
                        }
                    }
                host.info("Hooked the version-mapped mi6.a media renderer for local-only unblur")
            } catch (error: Throwable) {
                host.error("Could not hook the mi6.a media renderer", error)
            }
        }
    }

    fun installHomeGridPostTileUnblurHook(classLoader: ClassLoader, symbols: ResolvedSymbols?) {
        val mappings = host.mappings ?: return
        val tileComposable = symbols?.homeGridPostTileComposableMethod
        if (tileComposable == null) {
            host.info("Home-grid post unblur disabled: the version-mapped f3i tile composable was unresolved")
            return
        }
        try {
            val contract = mappings.homeGridTileContract(classLoader)
            host.hook(tileComposable)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val args = chain.args.toTypedArray()
                    val tile = args.getOrNull(0)
                    if (tile != null && contract.tileClass.isInstance(tile)) {
                        val wasBlurred = contract.blurredField.getBoolean(tile)
                        host.logOnce("homeGridTileSeen", "Home-grid z89.b invoked; f3i.isBlurred=$wasBlurred")
                        if (wasBlurred) {
                            args[0] = contract.unblur(tile)
                            host.logOnce(
                                "homeGridTileUnblur",
                                "Disabled the 3-column home-grid post tile blur at its local Compose rendering boundary",
                            )
                            return@intercept chain.proceed(args)
                        }
                    }
                    chain.proceed()
                }
            host.info("Hooked the version-mapped home-grid f3i tile renderer for local-only unblur")
            installHomeGridDetailsClickHook(classLoader)
        } catch (error: Throwable) {
            host.error("Could not hook the home-grid post tile renderer", error)
        }
    }

    private fun installHomeGridDetailsClickHook(classLoader: ClassLoader) {
        val mappings = host.mappings ?: return
        try {
            val contract = mappings.homeGridClickContract(classLoader)
            host.hook(contract.clickInvoke)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val clickLambda = chain.thisObject
                    if (clickLambda != null && contract.branchField.getInt(clickLambda) == mappings.gridClickBranch) {
                        val tileContainer = contract.tileContainerField.get(clickLambda)
                        val tileModel = if (contract.tileContainerClass.isInstance(tileContainer)) {
                            contract.tileModelField.get(tileContainer)
                        } else {
                            null
                        }
                        if (contract.tileModelClass.isInstance(tileModel)) {
                            val callback = contract.callbackField.get(clickLambda)
                            val postId = contract.postIdField.get(tileModel) as? String
                            if (postId != null) {
                                FeedPostMediaCache.noteGridPostSelected(postId)
                                host.logOnce(
                                    "homeGridDetailSelection",
                                    "Bound the selected home-grid post ID to its subsequent detail media composition",
                                )
                            }
                            if (callback != null && postId != null) {
                                host.logOnce(
                                    "homeGridDetailsClick",
                                    "Changed home-grid tile click to OnDetailsClicked with isBlurred=false",
                                )
                                try {
                                    return@intercept contract.callbackInvoke.invoke(callback, postId, false)
                                } catch (error: InvocationTargetException) {
                                    throw (error.cause ?: error)
                                }
                            }
                        }
                    }
                    chain.proceed()
                }
            host.info("Hooked the home-grid tile click event to route locally unblurred posts to Details, not Camera")
        } catch (error: Throwable) {
            host.error("Could not hook the home-grid tile details click route", error)
        }
    }

    fun installFeedOptionsCanBlurHooks(classLoader: ClassLoader, symbols: ResolvedSymbols?) {
        val mappings = host.mappings ?: return
        val homeEmitter = symbols?.homeFeedItemEmitterMethod
        val friendsOfFriendsEmitter = symbols?.friendsOfFriendsFeedItemEmitterMethod
        val mapper = symbols?.homeFeedCanBlurMapperMethod
        if ((homeEmitter == null && friendsOfFriendsEmitter == null) || mapper == null) {
            host.info("Feed unblur model hook disabled: feed emitter or canBlur mapper unresolved")
            return
        }
        try {
            val optionsContract = mappings.feedBlurOptionsContract(classLoader)
            val optionsArgument = mapper.parameterTypes.indexOf(optionsContract.optionsClass)
            val blurModelRewriter = try {
                mappings.feedBlurModelRewriter(classLoader)?.also {
                    host.info("Resolved local om7-to-pm7 feed state rewriter")
                }
            } catch (error: Throwable) {
                host.error("Could not resolve local om7-to-pm7 feed state rewriter", error)
                null
            }

            try {
                host.hook(optionsContract.constructor)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val args = chain.args.toTypedArray()
                        if (args.size >= 4) {
                            args[1] = true  // allowedToSendMessage
                            args[2] = true  // allowedToReactWithRealMoji
                            args[3] = false // canBlur
                            host.logOnce(
                                "universalOptionsUnblurred",
                                "Universally enabled allowedToSendMessage/allowedToReactWithRealMoji and disabled canBlur on FeedOptions",
                            )
                            chain.proceed(args)
                        } else {
                            chain.proceed()
                        }
                    }
                host.info("Hooked FeedOptions constructor for universal unblur and interactions")
            } catch (error: Throwable) {
                host.error("Could not hook FeedOptions constructor", error)
            }

            try {
                for (ctor in mappings.postReactionsConstructors(classLoader)) {
                    host.hook(ctor)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept { chain ->
                            val args = chain.args.toTypedArray()
                            if (args.size >= 2) {
                                args[0] = true // canSendMessage
                                args[1] = true // canReactWithRealMoji
                                host.logOnce(
                                    "universalReactionsEnabled",
                                    "Universally enabled canSendMessage and canReactWithRealMoji on ReactionsUiState (lkg)",
                                )
                                chain.proceed(args)
                            } else {
                                chain.proceed()
                            }
                        }
                }
                host.info("Hooked ReactionsUiState (lkg) constructor for universal interactions")
            } catch (error: Throwable) {
                host.error("Could not hook ReactionsUiState (lkg) constructor", error)
            }

            if (blurModelRewriter != null) {
                try {
                    host.hook(blurModelRewriter.postConstructor)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept { chain ->
                            val args = chain.args.toTypedArray()
                            if (args.size > 5 && blurModelRewriter.blurredStateClass.isInstance(args[5])) {
                                args[5] = blurModelRewriter.regularState
                                host.logOnce(
                                    "universalPostStateRegularized",
                                    "Universally regularized rm7 post constructor state from om7 to pm7",
                                )
                                chain.proceed(args)
                            } else {
                                chain.proceed()
                            }
                        }
                    host.info("Hooked rm7 post constructor for universal om7-to-pm7 state regularization")
                } catch (error: Throwable) {
                    host.error("Could not hook rm7 post constructor", error)
                }
            }

            hookFeedModelMappingScope(homeEmitter, homeFeedModelMapping, "HomeFeed")
            hookFeedModelMappingScope(friendsOfFriendsEmitter, friendsOfFriendsModelMapping, "FriendsOfFriends")

            host.hook(mapper)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val args = chain.args.toTypedArray()
                    val inHomeFeed = homeFeedModelMapping.get() == true
                    val inFriendsOfFriends = friendsOfFriendsModelMapping.get() == true
                    val scopeTag = when {
                        inFriendsOfFriends -> "FriendsOfFriends"
                        inHomeFeed -> "HomeFeed"
                        else -> "other"
                    }
                    var changed = false
                    if (args.size > 1 && blurModelRewriter != null) {
                        try {
                            val regularPost = blurModelRewriter.regularize(args[1])
                            if (regularPost !== args[1]) {
                                args[1] = regularPost
                                changed = true
                                val feedName = if (scopeTag == "other") "other feed route" else scopeTag
                                host.logOnce(
                                    "blurredStateRegularized:$scopeTag",
                                    "Mapped $feedName om7/Blurred post state to a local pm7/Regular state with empty reaction data",
                                )
                            }
                        } catch (error: Throwable) {
                            host.error("Could not regularize a local blurred feed-post model", error)
                        }
                    }
                    if (optionsArgument >= 0 && args.size > optionsArgument &&
                        optionsContract.optionsClass.isInstance(args[optionsArgument])
                    ) {
                        val feedName = if (scopeTag == "other") "detail/other feed" else scopeTag
                        try {
                            val options = requireNotNull(args[optionsArgument])
                            val unblurredOptions = optionsContract.withBlurDisabled(options)
                            if (unblurredOptions !== options) {
                                args[optionsArgument] = unblurredOptions
                                changed = true
                                host.logOnce(
                                    "canBlurDisabled:$scopeTag",
                                    "Disabled FeedOptions.canBlur while mapping $feedName items; post/reaction data remains intact",
                                )
                                return@intercept chain.proceed(args)
                            }
                        } catch (error: Throwable) {
                            if (host.markOnce("canBlurError:$scopeTag")) {
                                host.error("Could not copy $feedName FeedOptions with canBlur=false", error)
                            }
                        }
                    }
                    if (changed) chain.proceed(args) else chain.proceed()
                }
            host.info("Hooked version-mapped HomeFeed and FriendsOfFriends mappers for local canBlur=false and blurred-state normalization")
        } catch (error: Throwable) {
            host.error("Could not install the local feed canBlur model hooks", error)
        }
    }

    private fun hookFeedModelMappingScope(emitter: Method?, scope: ThreadLocal<Boolean>, feedName: String) {
        if (emitter == null) return
        try {
            host.hook(emitter)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val previous = scope.get()
                    scope.set(true)
                    try {
                        chain.proceed()
                    } finally {
                        if (previous == null) scope.remove() else scope.set(previous)
                    }
                }
            host.info("Hooked $feedName item emission to scope its canBlur mapping change")
        } catch (error: Throwable) {
            host.error("Could not hook $feedName item emission", error)
        }
    }

    private fun resolveComposeDownloadOverlaySymbols(classLoader: ClassLoader): ComposeDownloadOverlaySymbols? {
        val mappings = host.mappings ?: return null
        return try {
            val modifierClass = Class.forName("androidx.compose.ui.Modifier", false, classLoader)
            val composerClass = Class.forName("androidx.compose.runtime.Composer", false, classLoader)
            val function1Class = mappings.type(HostClass.Function1, classLoader)
            val androidViewClass = Class.forName("androidx.compose.ui.viewinterop.AndroidView_androidKt", false, classLoader)
            val androidViewComposable = androidViewClass.declaredMethods.firstOrNull { method ->
                val p = method.parameterTypes
                Modifier.isStatic(method.modifiers) &&
                    method.name == "a" &&
                    method.returnType == Void.TYPE &&
                    p.size == 8 &&
                    p[0] == function1Class && p[1] == modifierClass &&
                    p[2] == function1Class && p[3] == function1Class &&
                    p[4] == function1Class && p[5] == composerClass &&
                    p[6] == Int::class.javaPrimitiveType && p[7] == Int::class.javaPrimitiveType
            }?.apply { isAccessible = true }
                ?: throw NoSuchMethodException("Compose AndroidView composable signature")

            val companionClass = Class.forName("androidx.compose.ui.Modifier\$Companion", false, classLoader)
            val baseModifier = companionClass.getDeclaredField("a").apply { isAccessible = true }.get(null)
            val sizeKt = Class.forName("androidx.compose.foundation.layout.SizeKt", false, classLoader)
            val fillMaxSize = sizeKt.declaredMethods.firstOrNull { method ->
                val p = method.parameterTypes
                Modifier.isStatic(method.modifiers) &&
                    method.name == "f" &&
                    method.returnType == modifierClass &&
                    p.size == 2 && p[0] == modifierClass && p[1] == Float::class.javaPrimitiveType
            }?.apply { isAccessible = true }
                ?: throw NoSuchMethodException("Compose Modifier.fillMaxSize")
            val fillMaxSizeModifier = requireNotNull(fillMaxSize.invoke(null, baseModifier, 1.0f))

            val zIndexClass = Class.forName("androidx.compose.ui.ZIndexModifierKt", false, classLoader)
            val zIndex = zIndexClass.getDeclaredMethod("a", modifierClass, Float::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val fillMaxSizeTopmostModifier = requireNotNull(zIndex.invoke(null, fillMaxSizeModifier, Float.MAX_VALUE))
            val kotlinUnit = mappings.kotlinUnit(classLoader)

            ComposeDownloadOverlaySymbols(
                function1Class = function1Class,
                androidViewComposable = androidViewComposable,
                zIndexModifier = zIndex,
                fillMaxSizeModifier = fillMaxSizeModifier,
                fillMaxSizeTopmostModifier = fillMaxSizeTopmostModifier,
                kotlinUnit = kotlinUnit,
            )
        } catch (failure: Throwable) {
            host.error("Compose AndroidView injection is unavailable; per-post download buttons are disabled", failure)
            null
        }
    }

    private fun injectInlineDownloadOverlay(
        composer: Any?,
        classLoader: ClassLoader,
        symbols: ComposeDownloadOverlaySymbols?,
        overlayModifier: Any?,
        post: Any,
        isDetail: Boolean,
    ) {
        if (composer == null || symbols == null || !symbols.androidViewComposable.parameterTypes[5].isInstance(composer)) return
        val feedPost = post as FeedPostMedia
        val factory = Proxy.newProxyInstance(
            classLoader,
            arrayOf(symbols.function1Class),
            composeFunctionHandler(symbols) { context ->
                val resources = host.moduleResources
                if (context !is Context || resources == null) return@composeFunctionHandler null
                val overlay = BeFuckGalleryUi.createInlineFeedDownloadOverlay(
                    context,
                    resources,
                    feedPost,
                    isDetail,
                )
                if (isDetail) {
                    host.logOnce("detailDownloadFactory", "Detail AndroidView factory invoked; context=${context.javaClass.name}")
                    overlay.addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
                        host.logOnce(
                            "detailDownloadViewLaidOut",
                            "Detail download view bounds=$left,$top-$right,$bottom, attached=${view.isAttachedToWindow}",
                        )
                    }
                }
                overlay
            },
        )
        val update = Proxy.newProxyInstance(
            classLoader,
            arrayOf(symbols.function1Class),
            composeFunctionHandler(symbols) { view ->
                if (view is View) BeFuckGalleryUi.updateInlineFeedDownloadOverlay(view, feedPost)
                symbols.kotlinUnit
            },
        )
        val noOpHandler = composeFunctionHandler(symbols) { symbols.kotlinUnit }
        val reset = Proxy.newProxyInstance(classLoader, arrayOf(symbols.function1Class), noOpHandler)
        val release = Proxy.newProxyInstance(classLoader, arrayOf(symbols.function1Class), noOpHandler)
        symbols.androidViewComposable.invoke(
            null,
            factory,
            overlayModifier?.let { symbols.zIndexModifier.invoke(null, it, Float.MAX_VALUE) } ?: symbols.fillMaxSizeTopmostModifier,
            reset,
            release,
            update,
            composer,
            0,
            0,
        )
        host.logOnce("inlineDownloadInjection", "Inserted the download control inside each rendered post-media Compose Box")
    }

    private fun composeFunctionHandler(
        symbols: ComposeDownloadOverlaySymbols,
        invoke: (Any?) -> Any?,
    ): InvocationHandler = InvocationHandler { proxy, method, args ->
        when (method.name) {
            "invoke" -> invoke(args?.firstOrNull())
            "toString" -> "BeFuckFeedDownloadComposable"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> args != null && args.size == 1 && args[0] === proxy
            else -> symbols.kotlinUnit
        }
    }

    private fun installCurrentPostMediaOrientationHook(classLoader: ClassLoader) {
        val mappings = host.mappings ?: return
        try {
            val orientationMethod = mappings.mediaOrientationMethod(classLoader)
                ?: throw NoSuchMethodException("mi6.c dual-media orientation renderer")
            host.hook(orientationMethod)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val args = chain.args
                    val postId = if (args.isNotEmpty()) FeedPostMediaCache.beginDualMediaComposition(args[0]) else null
                    if (postId != null) {
                        // mi6.c's remembered state starts from this value; a separate eu callback tracks later taps.
                        FeedPostMediaCache.setComposingPrimaryDisplayed(args[mappings.mediaOrientationArgument] != true)
                    }
                    try {
                        chain.proceed()
                    } finally {
                        FeedPostMediaCache.endDualMediaComposition()
                    }
                }

            val contract = mappings.mediaFlipContract(classLoader)
            host.hook(contract.constructor)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val args = chain.args
                    val result = chain.proceed()
                    if (args.size == 2 && args[1] is Int && args[1] == mappings.mediaFlipBranch) {
                        FeedPostMediaCache.associateFlipStateWithComposingPost(args[0])
                    }
                    result
                }

            host.hook(contract.invokeMethod)
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    val callback = chain.thisObject
                    if (callback != null &&
                        contract.kindField.getInt(callback) == mappings.mediaFlipBranch &&
                        result is Boolean
                    ) {
                        FeedPostMediaCache.updatePrimaryDisplayedFromFlipState(
                            contract.stateField.get(callback),
                            result,
                        )
                    }
                    result
                }
            host.info("Hooked mi6.c's current-side seed and its remembered tap-swap state for downloads")
        } catch (error: Throwable) {
            host.error("Could not resolve the tap-swapped media selection; inline downloads will default to primary", error)
        }
    }
}
