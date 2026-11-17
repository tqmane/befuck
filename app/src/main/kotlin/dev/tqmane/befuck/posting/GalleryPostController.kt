package dev.tqmane.befuck.posting

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.tqmane.befuck.archive.ArchiveAccounts
import dev.tqmane.befuck.runtime.HostReflection
import dev.tqmane.befuck.runtime.HostWorkManager
import dev.tqmane.befuck.symbols.HostClass
import dev.tqmane.befuck.symbols.HostMethod
import dev.tqmane.befuck.symbols.KnownMappings
import dev.tqmane.befuck.symbols.ResolvedSymbols
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

object GalleryPostController {
    private const val TAG = "BeFuck/Post"
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val uploadMonitor = Executors.newSingleThreadScheduledExecutor()
    private const val MAX_UPLOAD_MONITOR_POLLS = 1_800
    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun isReady(symbols: ResolvedSymbols?): Boolean = symbols != null &&
        symbols.postDomainModelConstructor != null &&
        symbols.postDomainModelFieldOrder.size == 28 &&
        symbols.postContentsConstructor != null &&
        symbols.postMediaConstructor != null &&
        symbols.postCoreDraftClass != null &&
        symbols.postCoreDraftConstructor != null &&
        symbols.sendPostCoroutineConstructor != null &&
        symbols.sendPostRepositoryInterface != null &&
        symbols.postUploadWorkerClass != null &&
        symbols.friendsVisibility != null &&
        symbols.currentUserProviderClass != null &&
        symbols.currentUserProviderMethod != null &&
        symbols.currentUserUidField != null

    /** Read-only current-user provider. Never infer login identity from arbitrary User constructors. */
    internal fun readCurrentAccount(activity: Activity, symbols: ResolvedSymbols, completion: Consumer<String?>) {
        readAccountForOwner(
            loader = activity.classLoader,
            ownerProvider = { activity },
            timeoutMessage = "Current-account provider timed out; archive identity remains unavailable",
            failurePrefix = "Current-account provider",
            completion = completion,
            symbols = symbols,
        )
    }

    /**
     * The periodic sync runs without an Activity. Read the authenticated account from
     * the same host Koin provider used by the foreground archive UI.
     */
    @JvmStatic
    fun readCurrentAccountInBackground(context: Context, symbols: ResolvedSymbols, completion: Consumer<String?>) {
        readAccountForOwner(
            loader = context.classLoader,
            ownerProvider = ::processLifecycleOwner,
            timeoutMessage = "Background current-user provider timed out",
            failurePrefix = "Background current-user provider",
            completion = completion,
            symbols = symbols,
        )
    }

    private fun readAccountForOwner(
        loader: ClassLoader,
        ownerProvider: (ClassLoader) -> Any,
        timeoutMessage: String,
        failurePrefix: String,
        symbols: ResolvedSymbols,
        completion: Consumer<String?>,
    ) {
        val once = AtomicBoolean(false)
        val requestedGeneration = ArchiveAccounts.snapshot().generation
        fun deliver(uid: String?) {
            if (once.compareAndSet(false, true)) completion.accept(uid)
        }
        val timeout = Runnable {
            Log.w(TAG, timeoutMessage)
            deliver(null)
        }
        mainHandler.postDelayed(timeout, 15_000L)
        executor.execute {
            try {
                val type = requireNotNull(symbols.currentUserProviderClass)
                val method = requireNotNull(symbols.currentUserProviderMethod)
                val uid = requireNotNull(symbols.currentUserUidField)
                val receiver = resolveKoinInstance(type, loader, symbols.versionName)
                val coroutine = coroutineContext(lifecycleScope(ownerProvider(loader), loader))
                invokeSuspend(receiver, method, emptyArray(), method.parameterTypes.single(), coroutine) { user, failure ->
                    mainHandler.removeCallbacks(timeout)
                    val value = if (failure == null && user != null && uid.declaringClass.isInstance(user)) {
                        runCatching { uid.get(user) as? String }.getOrNull()
                    } else null
                    if (failure != null || (user != null && !uid.declaringClass.isInstance(user))) {
                        Log.w(TAG, "$failurePrefix did not return the expected current-user model")
                    }
                    val validated = value?.takeIf(String::isNotBlank)
                    if (validated != null && once.get()) {
                        // The 15-second timeout must not permanently discard a
                        // later authenticated-user Flow result. Reject stale accounts.
                        ArchiveAccounts.accept(requestedGeneration, validated)
                    } else {
                        deliver(validated)
                    }
                }
            } catch (failure: Throwable) {
                mainHandler.removeCallbacks(timeout)
                Log.w(TAG, "$failurePrefix unavailable (${failure.javaClass.simpleName}); archive identity remains unavailable")
                deliver(null)
            }
        }
    }

    /** Fetch feed DTOs via the host repository without opening or mutating an Activity. */
    @JvmStatic
    fun refreshHomeFeedInBackground(context: Context, versionName: String?, completion: Consumer<Boolean>) {
        if (!KnownMappings.isSupportedVersion(versionName)) {
            completion.accept(false)
            return
        }
        val once = AtomicBoolean(false)
        fun deliver(success: Boolean) {
            if (once.compareAndSet(false, true)) completion.accept(success)
        }
        executor.execute {
            try {
                val loader = context.classLoader
                val mapping = KnownMappings.current()
                val stateHolderClass = Class.forName(mapping.className(HostClass.HomeFeedStateHolder), false, loader)
                val feedClass = Class.forName(mapping.className(HostClass.HomeFeed), false, loader)
                fetchFirstHomeFeed(loader, stateHolderClass, versionName, processLifecycleOwner(loader)) { _, feed, failure ->
                    if (failure != null) Log.w(TAG, "Background feed refresh returned an error")
                    deliver(failure == null && feedClass.isInstance(feed))
                }
            } catch (failure: Throwable) {
                Log.w(TAG, "Background feed refresh unavailable (${failure.javaClass.simpleName})")
                deliver(false)
            }
        }
    }

    /** Refreshes the app-owned feed state after a BeFake created its post outside the feed ViewModel. */
    @JvmStatic
    fun refreshHomeFeed(activity: Activity, versionName: String?) {
        if (activity.isFinishing || activity.isDestroyed || !KnownMappings.isSupportedVersion(versionName)) return
        executor.execute {
            try {
                val loader = activity.classLoader
                val mapping = KnownMappings.current()
                val stateHolderClass = Class.forName(mapping.className(HostClass.HomeFeedStateHolder), false, loader)
                val feedClass = Class.forName(mapping.className(HostClass.HomeFeed), false, loader)
                val trackingField = feedClass.getDeclaredField("g").apply { isAccessible = true }
                val refreshReasonClass = Class.forName(mapping.className(HostClass.FeedRefreshReason), false, loader)
                val refreshReason = requireNotNull(
                    refreshReasonClass.getDeclaredField("a").apply { isAccessible = true }.get(null)
                ) { "Feed refresh reason is unavailable" }
                require(refreshReason.toString() == "FromToaster") { "Resolved feed refresh reason is not FromToaster" }
                val updateFeedState = stateHolderClass.declaredMethods.singleOrNull { method ->
                    val parameters = method.parameterTypes
                    method.name == "c" && method.returnType == Void.TYPE && parameters.size == 5 &&
                        parameters[0] == feedClass && parameters[1] == Boolean::class.javaPrimitiveType &&
                        parameters[3].isInstance(refreshReason) && parameters[4] == trackingField.type
                } ?: error("Home feed force-refresh method is unresolved")
                updateFeedState.isAccessible = true

                fetchFirstHomeFeed(loader, stateHolderClass, versionName, activity) { stateHolder, feed, failure ->
                    if (failure != null || feed == null || !feedClass.isInstance(feed)) {
                        Log.w(TAG, "BeFake post created, but the HomeFeed fetch returned no feed", failure)
                    } else {
                        activity.runOnUiThread {
                            runCatching {
                                updateFeedState.invoke(stateHolder, feed, true, null, refreshReason, trackingField.get(feed))
                                Log.i(TAG, "Refreshed the active BeReal feed after BeFake posting")
                            }.onFailure { refreshFailure ->
                                Log.w(TAG, "BeFake post created, but HomeFeed state could not be updated", refreshFailure)
                            }
                        }
                    }
                }
            } catch (failure: Throwable) {
                Log.w(TAG, "Could not invoke BeReal's feed refresh after BeFake posting", failure)
            }
        }
    }

    private fun fetchFirstHomeFeed(
        loader: ClassLoader,
        stateHolderClass: Class<*>,
        versionName: String?,
        lifecycleOwner: Any,
        completion: (Any, Any?, Throwable?) -> Unit,
    ) {
        val mapping = KnownMappings.current()
        val stateHolder = resolveKoinInstance(stateHolderClass, loader, versionName)
        val feedFlow = mapping.refreshFeedFlow(stateHolder)
        val flowClass = Class.forName(mapping.className(HostClass.Flow), false, loader)
        val continuationType = Class.forName(mapping.className(HostClass.Continuation), false, loader)
        val first = Class.forName(mapping.className(HostClass.FlowOperators), false, loader)
            .getDeclaredMethod(mapping.methodName(HostMethod.FlowFirst), flowClass, continuationType)
            .apply { isAccessible = true }
        require(flowClass.isInstance(feedFlow)) { "Home feed source did not return the expected Flow type" }
        val coroutine = coroutineContext(lifecycleScope(lifecycleOwner, loader))
        invokeSuspend(feedFlow, first, arrayOf(feedFlow), continuationType, coroutine) { feed, failure ->
            completion(stateHolder, feed, failure)
        }
    }

    private val createdPostIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val publishedPostIds = android.util.LruCache<String, String>(200)

    @JvmStatic
    fun noteOfficialPostPublished(core: Any?, result: Any?) {
        if (core == null || result?.javaClass?.simpleName != KnownMappings.current().className(HostClass.ResultSuccess)) return
        runCatching {
            val post = result.javaClass.getDeclaredField("a").apply { isAccessible = true }.get(result) ?: return
            if (post.javaClass.simpleName != KnownMappings.current().className(HostClass.PublishedPost)) return
            val localId = core.javaClass.getDeclaredField("a").apply { isAccessible = true }.get(core) as? String ?: return
            val remoteId = post.javaClass.getDeclaredField(KnownMappings.current().publishedPostIdField)
                .apply { isAccessible = true }.get(post) as? String ?: return
            if (remoteId.isBlank() || remoteId.startsWith("berealLocal")) return
            synchronized(publishedPostIds) { publishedPostIds.put(localId, remoteId) }
            Log.i(TAG, "Server confirmed official post creation: $remoteId")
        }.onFailure { Log.w(TAG, "Could not read the official create-post response", it) }
    }

    /** Called only by the version-checked native UnsentPost persistence hook. */
    @JvmStatic
    fun noteOfficialPostSaved(unsent: Any?) {
        if (unsent == null) return
        runCatching {
            val session = unsent.javaClass.getDeclaredField("j").apply { isAccessible = true }.get(unsent) as? String ?: return
            if (!createdPostIds.containsKey(session)) return
            val core = unsent.javaClass.getDeclaredField("a").apply { isAccessible = true }.get(unsent) ?: return
            val id = core.javaClass.getDeclaredField("a").apply { isAccessible = true }.get(core) as? String ?: return
            createdPostIds[session] = id
        }.onFailure { Log.w(TAG, "Could not identify the newly persisted official post", it) }
    }

    @JvmStatic
    fun postNow(
        activity: Activity,
        symbols: ResolvedSymbols,
        request: GalleryPostRequest,
        existingPostId: String? = null,
        draftSaved: Consumer<String>? = null,
        completion: Consumer<Boolean>,
    ) {
        if (!isReady(symbols) || !KnownMappings.isSupportedVersion(symbols.versionName)) {
            completion.accept(false)
            return
        }
        executor.execute {
            var sessionId: String? = null
            try {
                val loader = activity.classLoader
                fun observeUpload(postId: String) {
                    draftSaved?.accept(postId)
                    val work = HostWorkManager.enqueueUniqueOneTimeWork(
                        context = activity,
                        classLoader = loader,
                        workerClass = symbols.postUploadWorkerClass!!,
                        uniqueName = "upload_unsent_post_$postId",
                        inputEntries = mapOf("key_post_id" to postId),
                        linearBackoff = true,
                        tag = "SEND_POSTS_WORKER",
                    )
                    watchUploadAndCleanMedia(activity, loader, work, request, completion)
                    Log.i(TAG, "Submitted official upload for persisted CorePost $postId")
                }
                if (existingPostId?.startsWith("berealLocal") == true) {
                    observeUpload(existingPostId)
                    return@execute
                }
                val senderClass = Class.forName(KnownMappings.current().className(HostClass.PostSender), false, loader)
                val sender = resolveKoinInstance(senderClass, loader, symbols.versionName)
                val context = coroutineContext(lifecycleScope(activity, loader))
                val provider = senderClass.getDeclaredField("c").apply { isAccessible = true }.get(sender)
                    ?: error("Current BeReal moment provider is unavailable")
                val getMoment = HostReflection.allMethods(provider.javaClass).single {
                    it.name == "a" && it.parameterCount == 1 && it.returnType == Any::class.java
                }
                invokeSuspend(provider, getMoment, emptyArray(), getMoment.parameterTypes[0], context) { moment, momentFailure ->
                    if (momentFailure != null || moment == null) {
                        Log.e(TAG, "Current BeReal moment is unavailable", momentFailure)
                        completion.accept(false)
                    } else runCatching {
                        val momentId = moment.javaClass.getDeclaredField("id").apply { isAccessible = true }.get(moment) as String
                        val isMain = !dev.tqmane.befuck.download.FeedPostMediaCache.hasOwnMainPost(momentId)
                        val domain = GalleryPostModelRewriter.createDomainModel(symbols, request, isMain)
                        val session = symbols.postDomainModelFieldOrder[19].get(domain) as String
                        sessionId = session
                        createdPostIds[session] = ""
                        val send = senderClass.declaredMethods.single { method ->
                            method.name == "c" && method.parameterCount == 3 && method.parameterTypes[0].isInstance(domain)
                        }.apply { isAccessible = true }
                        // This native error-only callback ignores its result. The bundled
                        // stringifier avoids depending on a camera ViewModel for logging.
                        val errorCallback = send.parameterTypes[1].getDeclaredConstructor(Any::class.java, Int::class.javaPrimitiveType)
                            .newInstance(null, 0)
                        Log.i(TAG, "Creating official media pair; isMain=$isMain, video=${request.back?.isVideo}")
                        invokeSuspend(sender, send, arrayOf(domain, errorCallback), send.parameterTypes[2], context) { result, failure ->
                            val postId = createdPostIds.remove(session)?.takeIf(String::isNotBlank)
                            if (failure != null || result?.javaClass?.simpleName != KnownMappings.current().className(HostClass.ResultSuccess) || postId == null) {
                                Log.e(TAG, "Official post creation failed; result=${result?.javaClass?.simpleName}, persisted=${postId != null}", failure)
                                completion.accept(false)
                            } else runCatching { observeUpload(postId) }.onFailure {
                                Log.e(TAG, "Official post persisted but upload could not start", it)
                                completion.accept(false)
                            }
                        }
                    }.onFailure {
                        sessionId?.let(createdPostIds::remove)
                        Log.e(TAG, "Could not create the official media pair", it)
                        completion.accept(false)
                    }
                }
            } catch (failure: Throwable) {
                sessionId?.let(createdPostIds::remove)
                Log.e(TAG, "Could not invoke BeReal's official post creation flow", failure)
                completion.accept(false)
            }
        }
    }

    private fun lifecycleScope(owner: Any, classLoader: ClassLoader): Any {
        val lifecycleOwner = Class.forName("androidx.lifecycle.LifecycleOwner", false, classLoader)
        require(lifecycleOwner.isInstance(owner)) { "Expected host LifecycleOwner" }
        val extension = Class.forName("androidx.lifecycle.LifecycleOwnerKt", false, classLoader)
        val candidates = extension.declaredMethods.filter { method ->
            Modifier.isStatic(method.modifiers) && method.parameterTypes.contentEquals(arrayOf(lifecycleOwner)) &&
                method.returnType != Void.TYPE
        }
        require(candidates.size == 1) { "LifecycleOwner coroutine scope factory candidateCount=${candidates.size}" }
        return candidates.single().apply { isAccessible = true }.invoke(null, owner)
            ?: error("LifecycleOwner coroutine scope factory returned null")
    }

    /** Process-scoped coroutine context stays valid even if no BeReal Activity exists. */
    private fun processLifecycleOwner(classLoader: ClassLoader): Any {
        val lifecycleOwner = Class.forName("androidx.lifecycle.LifecycleOwner", false, classLoader)
        val processType = Class.forName("androidx.lifecycle.ProcessLifecycleOwner", false, classLoader)
        // BeReal 3.98.0 R8-inlines the companion accessor but retains a static singleton field.
        val singleton = processType.declaredFields.singleOrNull { field ->
            Modifier.isStatic(field.modifiers) && processType.isAssignableFrom(field.type)
        } ?: error("ProcessLifecycleOwner singleton unavailable")
        singleton.isAccessible = true
        val owner = requireNotNull(singleton.get(null)) { "ProcessLifecycleOwner is uninitialized" }
        require(lifecycleOwner.isInstance(owner))
        return owner
    }

    private fun coroutineContext(scope: Any): Any {
        val methods = HostReflection.allMethods(scope.javaClass)
        val getters = methods.filter { method ->
            method.parameterCount == 0 && !method.returnType.isPrimitive && isCoroutineContextType(method.returnType)
        }
        require(getters.size == 1) { "LifecycleScope coroutine context getter candidateCount=${getters.size}" }
        return getters.single().apply { isAccessible = true }.invoke(scope)
            ?: error("LifecycleScope returned a null coroutine context")
    }

    private fun isCoroutineContextType(type: Class<*>): Boolean {
        val methods = HostReflection.allMethods(type)
        return methods.any { it.parameterCount == 2 && it.returnType == Any::class.java } &&
            methods.any { it.parameterCount == 1 && it.returnType != Void.TYPE } &&
            methods.any { it.parameterCount == 1 && type.isAssignableFrom(it.returnType) }
    }

    private fun invokeSuspend(
        receiver: Any,
        method: Method,
        args: Array<Any?>,
        continuationType: Class<*>,
        coroutineContext: Any,
        completion: (Any?, Throwable?) -> Unit,
    ) {
        val completed = AtomicBoolean(false)
        val contract = if (continuationType.isInterface) continuationType
            else Class.forName(KnownMappings.current().className(HostClass.Continuation), false, receiver.javaClass.classLoader)
        val callback = Proxy.newProxyInstance(
            continuationType.classLoader ?: receiver.javaClass.classLoader,
            arrayOf(contract),
            InvocationHandler { proxy, invoked, invocationArgs ->
                when {
                    invoked.parameterCount == 0 && invoked.returnType.isInstance(coroutineContext) -> coroutineContext
                    invoked.parameterCount == 1 && invoked.returnType == Void.TYPE -> {
                        if (completed.compareAndSet(false, true)) {
                            val value = invocationArgs?.firstOrNull()
                            completion(value, findCoroutineFailure(value))
                        }
                        null
                    }
                    invoked.declaringClass == Any::class.java && invoked.name == "toString" -> "BeFuckContinuation"
                    invoked.declaringClass == Any::class.java && invoked.name == "hashCode" -> System.identityHashCode(proxy)
                    invoked.declaringClass == Any::class.java && invoked.name == "equals" -> proxy === invocationArgs?.firstOrNull()
                    else -> defaultValue(invoked.returnType)
                }
            },
        )

        val continuation = if (continuationType.isInterface) callback else {
            require(continuationType.name == KnownMappings.current().className(HostClass.ContinuationImpl)) {
                "Unsupported concrete coroutine continuation: ${continuationType.name}"
            }
            // Reuse Kotlin's bundled coroutine bridge. Its running state forwards
            // the native result (including failures) unchanged to our completion.
            val stateClass = Class.forName(KnownMappings.current().className(HostClass.CoroutineStart), false, receiver.javaClass.classLoader)
            val state = stateClass.declaredConstructors.single { it.parameterCount == 4 }
                .newInstance(callback, coroutineContext, null, callback)
            stateClass.getDeclaredField("r").apply { isAccessible = true }.setInt(state, 1)
            state
        }

        method.isAccessible = true
        val result = method.invoke(receiver, *(args + continuation))
        if (!isCoroutineSuspended(result) && completed.compareAndSet(false, true)) {
            completion(result, findCoroutineFailure(result))
        }
    }

    private fun isCoroutineSuspended(value: Any?): Boolean = value?.toString() == "COROUTINE_SUSPENDED"

    private fun findCoroutineFailure(value: Any?): Throwable? {
        if (value == null) return null
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val pending = ArrayDeque<Pair<Any, Int>>()
        pending.add(value to 0)
        while (pending.isNotEmpty()) {
            val (current, depth) = pending.removeFirst()
            if (!seen.add(current)) continue
            if (current is Throwable) return current
            if (depth >= 3) continue
            for (field in current.javaClass.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                try {
                    field.isAccessible = true
                    val child = field.get(current) ?: continue
                    if (child is Throwable) return child
                    if (!child.javaClass.name.startsWith("java.lang.")) pending.add(child to depth + 1)
                } catch (_: Throwable) {
                }
            }
        }
        return null
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    internal fun resolveKoinInstance(type: Class<*>, classLoader: ClassLoader, versionName: String?): Any {
        val root = requireNotNull(KnownMappings.current().resolveKoinApplication(classLoader)) {
            "Koin application root is unavailable"
        }
        val (resolver, getDefinition) = findKoinDefinitionResolver(root, versionName)
        val tokenType = getDefinition.parameterTypes[0]
        val tokenConstructor = tokenType.declaredConstructors.singleOrNull { ctor ->
            ctor.parameterTypes.contentEquals(arrayOf(Class::class.java))
        } ?: error("Koin type token constructor unresolved: ${tokenType.name}")
        tokenConstructor.isAccessible = true
        val token = tokenConstructor.newInstance(type)
        getDefinition.isAccessible = true
        return requireNotNull(getDefinition.invoke(resolver, token, null, null)) {
            "Koin returned null for ${type.name}"
        }
    }

    private fun watchUploadAndCleanMedia(
        context: Context,
        classLoader: ClassLoader,
        work: HostWorkManager.EnqueuedWork,
        request: GalleryPostRequest,
        completion: Consumer<Boolean>,
    ) {
        val completed = AtomicBoolean(false)
        fun finish(success: Boolean) {
            if (completed.compareAndSet(false, true)) completion.accept(success)
        }
        var poller: ScheduledFuture<*>? = null
        try {
            val workManagerClass = Class.forName("androidx.work.WorkManager", false, classLoader)
            val workManager = HostWorkManager.instance(context, classLoader)
            val query = HostReflection.allMethods(workManagerClass).singleOrNull { method ->
                method.parameterTypes.contentEquals(arrayOf(java.util.UUID::class.java)) &&
                    method.returnType != Void.TYPE && HostReflection.allMethods(method.returnType).any { candidate ->
                        candidate.name == "get" && candidate.parameterCount == 0
                    }
            } ?: error("WorkManager work-info Future query unavailable")
            val futureGet = HostReflection.allMethods(query.returnType).singleOrNull { method ->
                method.name == "get" && method.parameterCount == 0
            } ?: error("Work-info Future.get method unavailable")
            val workInfoClass = Class.forName("androidx.work.WorkInfo", false, classLoader)
            val stateClass = Class.forName("androidx.work.WorkInfo\$State", false, classLoader)
            val stateField = HostReflection.allFields(workInfoClass).singleOrNull { it.type == stateClass }
                ?: error("WorkInfo state field unavailable")
            query.isAccessible = true
            futureGet.isAccessible = true
            stateField.isAccessible = true
            val polls = java.util.concurrent.atomic.AtomicInteger()
            poller = uploadMonitor.scheduleWithFixedDelay({
                if (completed.get()) return@scheduleWithFixedDelay
                if (polls.incrementAndGet() > MAX_UPLOAD_MONITOR_POLLS) {
                    Log.w(TAG, "Timed out waiting for the official upload worker; media remains app-private")
                    poller?.cancel(false)
                    finish(false)
                    return@scheduleWithFixedDelay
                }
                try {
                    // KEEP may retain a work request already enqueued by BeReal's sender.
                    // Resolve that request by its official unique name instead of watching
                    // the UUID of a request WorkManager discarded.
                    val actualId = existingUploadWorkId(context, work.uniqueName) ?: work.id
                    val future = query.invoke(workManager, actualId) ?: return@scheduleWithFixedDelay
                    val info = requireNotNull(futureGet.invoke(future)) { "WorkManager returned no work-info" }
                    val state = requireNotNull(stateField.get(info)) { "WorkInfo has no state" }.toString()
                    when (state) {
                        "SUCCEEDED" -> {
                            poller?.cancel(false)
                            val postId = work.uniqueName.removePrefix("upload_unsent_post_")
                            if (synchronized(publishedPostIds) { publishedPostIds.get(postId) } == null) {
                                Log.e(TAG, "Upload work finished without a confirmed server post; prepared media retained")
                                finish(false)
                                return@scheduleWithFixedDelay
                            }
                            request.selectedMedia.forEach { media -> GalleryMediaPreparer.discardPreparedMedia(context, media) }
                            Log.i(TAG, "Official upload worker completed successfully")
                            finish(true)
                        }
                        "FAILED", "CANCELLED" -> {
                            poller?.cancel(false)
                            val failureDetails = readWorkerFailureDetails(info)
                            Log.e(
                                TAG,
                                "Official upload worker ended in $state${failureDetails?.let { "; $it" }.orEmpty()}; " +
                                    "media remains app-private for retry",
                            )
                            finish(false)
                        }
                    }
                } catch (failure: Throwable) {
                    if (polls.get() == 1) Log.w(TAG, "Could not read the official upload worker state", failure)
                }
            }, 1L, 1L, TimeUnit.SECONDS)
        } catch (failure: Throwable) {
            Log.w(TAG, "Could not observe official upload completion; prepared media remains app-private", failure)
            poller?.cancel(false)
            finish(false)
        }
    }

    private fun existingUploadWorkId(context: android.content.Context, name: String): java.util.UUID? {
        val file = File(context.noBackupFilesDir, "androidx.work.workdb")
        if (!file.isFile) return null
        return android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery(
                "SELECT w.id FROM WorkSpec w JOIN WorkName n ON w.id = n.work_spec_id WHERE n.name = ? ORDER BY w.last_enqueue_time DESC, w.rowid DESC LIMIT 1",
                arrayOf(name),
            ).use { if (it.moveToFirst()) java.util.UUID.fromString(it.getString(0)) else null }
        }
    }

    private fun readWorkerFailureDetails(workInfo: Any): String? = runCatching {
        val outputData = HostReflection.allMethods(workInfo.javaClass).firstOrNull { method ->
            method.name == "getOutputData" && method.parameterCount == 0
        }?.apply { isAccessible = true }?.invoke(workInfo) ?: return@runCatching null
        val values = HostReflection.allMethods(outputData.javaClass).firstOrNull { method ->
            method.name == "getKeyValueMap" && method.parameterCount == 0 && Map::class.java.isAssignableFrom(method.returnType)
        }?.apply { isAccessible = true }?.invoke(outputData) as? Map<*, *> ?: return@runCatching null
        values.entries.asSequence()
            .filter { (key, _) -> key is String && key.contains(Regex("error|failure|exception|stage|reason", RegexOption.IGNORE_CASE)) }
            .take(6)
            .mapNotNull { (key, value) ->
                if (key !is String || value !is String && value !is Number && value !is Boolean) return@mapNotNull null
                val safeValue = value.toString()
                    .replace(Regex("(?i)bearer\\s+[^\\s,;]+"), "Bearer [redacted]")
                    .take(180)
                "$key=$safeValue"
            }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = ", ")
    }.getOrNull()

    private fun findKoinDefinitionResolver(root: Any, versionName: String?): Pair<Any, Method> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val pending = ArrayDeque<Pair<Any, Int>>()
        pending.add(root to 0)
        while (pending.isNotEmpty()) {
            val (instance, depth) = pending.removeFirst()
            if (!seen.add(instance)) continue
            val methods = instance.javaClass.declaredMethods.filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.returnType == Any::class.java &&
                    method.parameterCount == 3 &&
                    method.parameterTypes[0].declaredConstructors.any { ctor ->
                        ctor.parameterTypes.contentEquals(arrayOf(Class::class.java))
                    }
            }
            if (methods.size == 1) return instance to methods.single()
            if (methods.size > 1 && KnownMappings.isSupportedVersion(versionName)) {
                methods.singleOrNull { it.name == KnownMappings.current().methodName(HostMethod.KoinResolve) }?.let { return instance to it }
            }
            if (depth >= 3) continue
            for (field in instance.javaClass.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive || field.type.isArray) continue
                if (field.type.name.startsWith("java.") || field.type.name.startsWith("android.")) continue
                try {
                    field.isAccessible = true
                    field.get(instance)?.let { pending.add(it to depth + 1) }
                } catch (_: Throwable) {
                }
            }
        }
        error("Could not locate Koin's definition resolver in the application container")
    }
}
