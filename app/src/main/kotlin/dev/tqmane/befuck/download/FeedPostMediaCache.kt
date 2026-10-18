package dev.tqmane.befuck.download

import dev.tqmane.befuck.symbols.KnownMappings
import dev.tqmane.befuck.symbols.HostClass
import dev.tqmane.befuck.archive.ArchiveRepository
import dev.tqmane.befuck.archive.ArchiveAccounts
import android.util.LruCache
import android.content.Context
import org.json.JSONObject
import dev.tqmane.befuck.symbols.FeedMediaSymbols
import dev.tqmane.befuck.symbols.CorePostLocationSymbols
import dev.tqmane.befuck.symbols.MapPlaceholderLocationSymbols
import dev.tqmane.befuck.symbols.RemotePostLocationSymbols
import java.net.URI
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

data class FeedMediaItem(
    val url: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
) {
    val isVideo: Boolean
        get() = mimeType?.contains("video", ignoreCase = true) == true ||
            runCatching { URI(url).path.orEmpty().lowercase().endsWith(".mp4") }.getOrDefault(false)
}

data class FeedPostMedia(
    val postId: String,
    val takenAt: String?,
    val caption: String?,
    val primary: FeedMediaItem?,
    val secondary: FeedMediaItem?,
    val isOwnPost: Boolean? = null,
    internal val capturedAtMillis: Long,
    val username: String? = null,
    val postedAt: String? = null,
    val bts: FeedMediaItem? = null,
    val ownerUid: String? = null,
    val momentId: String? = null,
    val isMain: Boolean? = null,
    val location: PostLocation? = null,
)

/** Bounded, process-local index of posts BeReal has already deserialized for the feed. */
object FeedPostMediaCache {
    private val cache = LruCache<String, FeedPostMedia>(200)
    private var cacheEpoch = -1L
    private val currentUsernames = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var verifiedFriendUids: Set<String> = emptySet()
    private val friendQuerySequence = AtomicLong()
    private val loggedRemoteWithLocation = AtomicBoolean(false)
    private val loggedRemoteWithoutLocation = AtomicBoolean(false)
    private val loggedMissingViewer = AtomicBoolean(false)
    private val loggedSavedLocation = AtomicBoolean(false)
    private var appliedFriendQueryToken = -1L
    // Weak-only CorePosts were being collected before account resolution.
    // Retain at most 48 unmodified host models, not copied coordinates, until
    // the host author and viewer scope are verified. Account switches evict them.
    private data class CoreLocationSource(val model: Any, val symbols: CorePostLocationSymbols)
    private val coreLocationSources = LruCache<String, CoreLocationSource>(48)
    private data class MapLocationSource(val model: WeakReference<Any>, val reader: MapPlaceholderLocationSymbols)
    // Map placeholders may be instantiated before CorePost tells us who owns the post.
    // Retain only a weak host model, NOT an unverified coordinate or account association.
    private val pendingMapLocations = LruCache<String, MapLocationSource>(200)
    // Keep the original feed DTO until the host's account and friendship state are known.
    // It is capped to 200 entries and never read for GPS before authorization.
    private data class RemoteLocationSource(val model: Any, val reader: RemotePostLocationSymbols)
    private val remoteLocationSources = LruCache<String, RemoteLocationSource>(200)
    private data class VerifiedOwner(val uid: String, val acceptedByHost: Boolean)
    private val verifiedOwners = LruCache<String, VerifiedOwner>(200)
    private var lastScope = ArchiveAccounts.snapshot()

    init {
        ArchiveAccounts.subscribe {
            synchronized(cache) {
                val next = ArchiveAccounts.snapshot()
                val previous = lastScope
                lastScope = next
                if (previous.uid == null && next.uid != null && next.generation == previous.generation + 1L) {
                    // First proven login of this process: carry media/host models already
                    // decoded before the account became available into the right account.
                    val pendingMedia = cache.snapshot().values.toList()
                    cacheEpoch = next.generation
                    pendingMedia.forEach { storePost(it, next) }
                } else {
                    cacheEpoch = -1L
                    withCache { }
                }
            }
        }
    }

    @JvmStatic
    fun captureCurrentUser(model: Any?) {
        // Compatibility hook name: updates author names only, never the active account.
        if (model == null) return
        runCatching {
            fun text(name: String) = model.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(model) as? String
            val uid = text("uid")?.takeIf { it.isNotBlank() } ?: return
            val username = text("userName")?.takeIf { it.isNotBlank() } ?: return
            currentUsernames[uid] = username
            withCache {
                cache.snapshot().values.filter { it.ownerUid == uid && it.username.isNullOrBlank() }
                    .forEach { storePost(FeedPostMedia(it.postId, null, null, null, null, capturedAtMillis = System.currentTimeMillis(), username = username, ownerUid = uid)) }
            }
        }
    }

    @JvmStatic
    fun initialize(context: Context) { ArchiveRepository.initialize(context.applicationContext ?: context) }

    private inline fun <T> withCache(action: () -> T): T = synchronized(cache) {
        val epoch = ArchiveAccounts.snapshot().generation
        if (cacheEpoch != epoch) {
            cache.evictAll(); coreLocationSources.evictAll(); pendingMapLocations.evictAll()
            remoteLocationSources.evictAll(); verifiedOwners.evictAll(); verifiedFriendUids = emptySet()
            appliedFriendQueryToken = -1L
            cacheEpoch = epoch
        }
        action()
    }

    private fun storePost(post: FeedPostMedia, scope: ArchiveAccounts.Scope = ArchiveAccounts.snapshot()) {
        if (ArchiveAccounts.snapshot() != scope) return
        val account = scope.uid.orEmpty()
        val previous = cache.get(post.postId) ?: runCatching { ArchiveRepository.get(account, post.postId) }.getOrNull()
        val verified = verifiedOwners.get(post.postId)
        val candidateOwner = post.ownerUid ?: previous?.ownerUid
        val consistent = verified != null && (candidateOwner == null || candidateOwner == verified.uid)
        val mayReadExact = consistent && !scope.uid.isNullOrBlank() && (
            verified!!.uid == scope.uid ||
                if (appliedFriendQueryToken >= 0L) verified.uid in verifiedFriendUids else verified.acceptedByHost
        )
        // PostRemoteModel includes location in the same deserialization as its media.
        // Do not even access the latitude/longitude fields until permission is established.
        val remoteLocation = remoteLocationSources.get(post.postId)?.let { original ->
            original.reader.snapshot(original.model) { id -> id == post.postId && mayReadExact }?.second
        }
        // Host CorePost may precede feed media: reconcile its proven author and only
        // currently authorized coordinate without navigating to the post's map.
        val source = coreLocationSources.get(post.postId)
        val linked = source?.model?.let { model ->
            source.symbols.identity(model)?.takeIf { (id, uid) ->
                id == post.postId && (post.ownerUid ?: previous?.ownerUid)?.let { it == uid } != false
            }?.let { (_, uid) -> uid to source.symbols.snapshot(model, scope.uid, verifiedFriendUids,
                    allowNativeAccepted = appliedFriendQueryToken < 0L) }
        }
        val observed = post.copy(ownerUid = post.ownerUid ?: linked?.first ?: verified?.uid,
            location = post.location ?: linked?.second ?: remoteLocation)
        // Runtime presentation can fill gaps, but storage gets only the raw observation (not inherited values).
        val merged = observed.copy(
            takenAt = post.takenAt ?: previous?.takenAt, postedAt = post.postedAt ?: previous?.postedAt,
            username = post.username ?: previous?.username, ownerUid = observed.ownerUid ?: previous?.ownerUid,
            caption = post.caption ?: previous?.caption, primary = post.primary ?: previous?.primary,
            secondary = post.secondary ?: previous?.secondary, bts = post.bts ?: previous?.bts,
            momentId = post.momentId ?: previous?.momentId, isMain = post.isMain ?: previous?.isMain,
            isOwnPost = post.isOwnPost ?: previous?.isOwnPost,
            location = observed.location?.takeIf { it.isMappable } ?: previous?.location?.takeIf { it.isMappable })
        cache.put(post.postId, merged)
        ArchiveRepository.capture(account, observed)
        if (account.isBlank() && loggedMissingViewer.compareAndSet(false, true)) {
            android.util.Log.w("BeFuck/Cache", "Feed was loaded before viewer identity; retaining loaded media and host models")
        }
        if (account.isNotBlank() && merged.location != null && previous?.location == null &&
            loggedSavedLocation.compareAndSet(false, true)) {
            android.util.Log.i("BeFuck/Cache", "Host-verified post location queued for persistent archive")
        }
        if (!merged.ownerUid.isNullOrBlank()) {
            // A verified post author may become available after the host map placeholder.
            // Re-read the original host object only while it is alive and only if its
            // own isFriend flag still authorizes the displayed coordinate.
            val pending = pendingMapLocations.get(post.postId)
            if (pending != null) {
                pendingMapLocations.remove(post.postId)
                val observed = pending.model.get()?.let { pending.reader.snapshot(it) { id -> id == post.postId } }
                if (observed != null) captureLocation(observed.first, observed.second, scope)
            }
        }
    }
    internal fun invalidateArchiveMemory() { synchronized(cache) {
        cache.evictAll(); coreLocationSources.evictAll(); pendingMapLocations.evictAll()
        remoteLocationSources.evictAll(); verifiedOwners.evictAll()
    } }

    /** Observe a constructed host CorePost even when no media was decoded yet.
     * Keep only a weak model reference until friendship is established;
     * do not turn unseen media-less CorePost models into empty archive entries.
     */
    @JvmStatic
    fun captureCorePostModel(model: Any?, reader: CorePostLocationSymbols?) {
        if (model == null || reader == null) return
        val scope = ArchiveAccounts.snapshot()
        val (id, ownerUid) = reader.identity(model) ?: return
        withCache {
            if (ArchiveAccounts.snapshot() != scope) return@withCache
            val previous = verifiedOwners.get(id)
            if (previous != null && previous.uid != ownerUid) {
                // Conflicting host author identity cannot authorize exact GPS.
                verifiedOwners.remove(id)
                coreLocationSources.remove(id)
                return@withCache
            }
            // Recording identity/status requires no coordinate read or account inference.
            verifiedOwners.put(id, VerifiedOwner(ownerUid, reader.nativeAccepted(model)))
            coreLocationSources.put(id, CoreLocationSource(model, reader))
            if (scope.uid.isNullOrBlank()) return@withCache
            val existing = cache.get(id) ?: runCatching { ArchiveRepository.get(scope.uid!!, id) }.getOrNull()
            if (existing != null && (existing.primary != null || existing.secondary != null || existing.bts != null) &&
                (existing.ownerUid == null || existing.ownerUid == ownerUid)) {
                storePost(FeedPostMedia(id, null, null, null, null,
                    capturedAtMillis = System.currentTimeMillis(), ownerUid = ownerUid,
                    location = reader.snapshot(model, scope.uid, verifiedFriendUids,
                        allowNativeAccepted = appliedFriendQueryToken < 0L)), scope)
            }
        }
    }

    /** Use only host-verified PostDataUiModel identity; a random User is never the viewer. */
    @JvmStatic
    fun capturePostDataIdentity(model: Any?, symbols: FeedMediaSymbols?) {
        if (model == null || symbols == null) return
        runCatching {
            val idField = symbols.postDataIdField ?: return
            val ownerField = symbols.postDataOwnerUidField ?: return
            val myPostField = symbols.postDataIsMineField ?: return
            if (!idField.declaringClass.isInstance(model) || ownerField.declaringClass != idField.declaringClass)
                return
            val id = (idField.get(model) as? String)?.takeIf { it.isNotBlank() && it.length <= 512 } ?: return
            val owner = (ownerField.get(model) as? String)?.takeIf { it.isNotBlank() && it.length <= 512 } ?: return
            if (myPostField.getBoolean(model)) ArchiveAccounts.observeVerifiedOwnPost(owner)
            val scope = ArchiveAccounts.snapshot()
            withCache {
                if (ArchiveAccounts.snapshot() != scope) return@withCache
                val old = verifiedOwners.get(id)
                if (old != null && old.uid != owner) {
                    verifiedOwners.remove(id)
                    return@withCache
                }
                verifiedOwners.put(id, VerifiedOwner(owner, old?.acceptedByHost == true))
                val media = cache.get(id)
                if (media != null && (media.primary != null || media.secondary != null || media.bts != null) &&
                    (media.ownerUid == null || media.ownerUid == owner)) {
                    storePost(FeedPostMedia(id, null, null, null, null,
                        capturedAtMillis = System.currentTimeMillis(), ownerUid = owner), scope)
                }
            }
        }.onFailure { android.util.Log.w("BeFuck/Cache", "Host post owner observation rejected", it) }
    }

    /** Allocate before running the host query so overlapping completions cannot undo newer results. */
    @JvmStatic
    fun beginFriendSnapshot(): Long = friendQuerySequence.incrementAndGet()

    /** Only the validated, complete host Friends graph query may supply this account-scoped set. */
    @JvmStatic
    fun captureFriendUids(viewerUid: String, capturedGeneration: Long, queryToken: Long, friendUids: Set<String>) {
        captureFriendUids(ArchiveAccounts.Scope(viewerUid, capturedGeneration), queryToken, friendUids)
    }

    internal fun captureFriendUids(scope: ArchiveAccounts.Scope, queryToken: Long, friendUids: Set<String>) {
        if (scope.uid.isNullOrBlank() || queryToken <= 0) return
        withCache {
            if (ArchiveAccounts.snapshot() != scope || queryToken <= appliedFriendQueryToken) return@withCache
            appliedFriendQueryToken = queryToken
            // Replacement, including an empty result, revokes permission for subsequent observations.
            val completeUids = friendUids.toSet()
            if (completeUids == verifiedFriendUids) return@withCache
            verifiedFriendUids = completeUids
            coreLocationSources.snapshot().forEach { (id, source) ->
                val model = source.model
                run {
                    val existing = cache.get(id) ?: runCatching { ArchiveRepository.get(scope.uid!!, id) }.getOrNull()
                    // Don't create a saved-post entry for an offscreen host model.
                    if (existing != null && (existing.primary != null || existing.secondary != null || existing.bts != null))
                        captureLocation(id, source.symbols.snapshot(model, scope.uid, verifiedFriendUids), scope)
                }
            }
            remoteLocationSources.snapshot().keys.forEach { id ->
                val saved = cache.get(id) ?: runCatching { ArchiveRepository.get(scope.uid!!, id) }.getOrNull()
                if (saved != null && (saved.primary != null || saved.secondary != null || saved.bts != null)) {
                    storePost(FeedPostMedia(id, null, null, null, null,
                        capturedAtMillis = System.currentTimeMillis()), scope)
                }
            }
        }
    }

    /** The caller must resolve this post ID from the same host state as the displayed coordinate. */
    @JvmStatic
    fun captureLocation(postId: String?, location: PostLocation?) {
        captureLocation(postId, location, ArchiveAccounts.snapshot())
    }

    internal fun captureLocation(postId: String?, location: PostLocation?, scope: ArchiveAccounts.Scope) {
        if (postId.isNullOrBlank() || location == null) return
        withCache {
            if (ArchiveAccounts.snapshot() != scope) return@withCache
            if (cache.get(postId)?.location == location) return@withCache
            storePost(FeedPostMedia(postId, null, null, null, null,
                capturedAtMillis = System.currentTimeMillis(), location = location), scope)
        }
    }

    /** A reused map screen cannot carry another viewer's exact-coordinate permission into this account. */
    internal fun captureDisplayedLocation(postId: String?, location: PostLocation?, scope: ArchiveAccounts.Scope) {
        if (postId.isNullOrBlank() || location == null || scope.uid == null) return
        withCache {
            if (ArchiveAccounts.snapshot() != scope) return@withCache
            val owner = knownPostOwner(postId, scope) ?: return@withCache
            // This is already the host's privacy-filtered, actually displayed map state.
            // Do not require the optional Friends SQL observation to run first.
            captureLocation(postId, location, scope)
        }
    }

    private fun knownPostOwner(postId: String, scope: ArchiveAccounts.Scope): String? =
        (cache.get(postId)?.ownerUid ?: runCatching { ArchiveRepository.get(scope.uid.orEmpty(), postId)?.ownerUid }.getOrNull())
            ?.takeIf(String::isNotBlank)

    @JvmStatic
    fun captureMapPlaceholder(model: Any?, symbols: FeedMediaSymbols?) {
        if (model == null) return
        val scope = ArchiveAccounts.snapshot()
        if (scope.uid == null) return
        withCache {
            if (ArchiveAccounts.snapshot() != scope) return@withCache
            val reader = symbols?.mapPlaceholderLocationSymbols ?: return@withCache
            // The host's isFriend flag is checked before the identity callback.
            // If the author is not known yet, keep only a weak host object and
            // return false BEFORE snapshot reads latitude or longitude.
            val located = reader.snapshot(model) { postId ->
                if (knownPostOwner(postId, scope) != null) true else {
                    pendingMapLocations.put(postId, MapLocationSource(WeakReference(model), reader))
                    false
                }
            } ?: return@withCache
            captureLocation(located.first, located.second, scope)
        }
    }

    internal fun fromJson(json: JSONObject): FeedPostMedia {
        fun text(key: String) = (json.opt(key) as? String)?.takeIf { it.isNotBlank() }
        fun media(key: String): FeedMediaItem? = json.optJSONObject(key)?.let {
            FeedMediaItem(it.getString("url"), it.optString("mime").takeIf(String::isNotBlank),
                it.optInt("width").takeIf { n -> n > 0 }, it.optInt("height").takeIf { n -> n > 0 })
        }
        return FeedPostMedia(json.getString("id"), text("takenAt"), json.opt("caption") as? String, media("primary"), media("secondary"),
            json.opt("own") as? Boolean, json.getLong("captured"), text("username"), text("postedAt"), media("bts"), text("ownerUid"), text("momentId"), json.opt("main") as? Boolean)
    }

    @JvmStatic
    fun get(postId: String): FeedPostMedia? = withCache { cache.get(postId) }
        ?: runCatching { ArchiveRepository.get(ArchiveAccounts.snapshot().uid.orEmpty(), postId) }.getOrNull()

    /** Field mapping checked against the 3.97.0 CorePost constructor, including database-loaded posts. */
    @JvmStatic
    @JvmOverloads
    fun captureCorePost(model: Any?, symbols: FeedMediaSymbols? = null) {
        if (model == null) return
        val scope = ArchiveAccounts.snapshot()
        runCatching {
            fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
            fun media(value: Any?, bts: Boolean = false): FeedMediaItem? {
                if (value == null) return null
                val content = field(value, if (bts) "b" else "a") ?: return null
                val url = field(content, "uri") as? String ?: return null
                if (!url.startsWith("https://")) return null
                val video = field(content, "mediaType").toString() == "VIDEO"
                return FeedMediaItem(url, if (video) "video/mp4" else null,
                    field(content, "width").toDimension(), field(content, "height").toDimension())
            }
            val id = field(model, "a") as? String ?: return
            val owner = field(model, "b") ?: return
            val ownerUid = field(owner, "uid") as? String
            val moment = field(model, "c")
            val primaryModel = field(model, "d")
            val captured = FeedPostMedia(id, field(model, "t")?.toString(), field(model, "k") as? String,
                media(primaryModel), media(field(model, "e")), null, System.currentTimeMillis(),
                (field(owner, "userName") as? String)?.takeIf { it.isNotBlank() } ?: ownerUid?.let(currentUsernames::get),
                field(model, "u")?.toString(),
                if (primaryModel?.javaClass?.simpleName == KnownMappings.current().className(HostClass.BtsMedia)) media(primaryModel, bts = true) else null, ownerUid,
                moment?.let { field(it, "a") as? String }, field(model, "o") as? Boolean)
            if (captured.primary == null && captured.secondary == null) return
            withCache {
                if (ArchiveAccounts.snapshot() != scope) return@withCache
                val location = symbols?.corePostLocationSymbols?.let { reader ->
                    if (scope.uid != null) coreLocationSources.put(id, CoreLocationSource(model, reader))
                    reader.snapshot(model, scope.uid, verifiedFriendUids,
                        allowNativeAccepted = appliedFriendQueryToken < 0L)
                }
                storePost(captured.copy(isOwnPost = ownerUid?.let { owner -> scope.uid?.let { it == owner } }, location = location), scope)
            }
        }.onFailure { android.util.Log.w("BeFuck/Cache", "CorePost media capture failed", it) }
    }
    @Volatile private var activeTimelinePostId: String? = null
    private val composingPostId = ThreadLocal<String?>()
    private val currentDualMediaPostId = ThreadLocal<String?>()
    private val flipStateLock = Any()
    private val flipStatePostIds = java.util.WeakHashMap<Any, String>()
    private val primaryDisplayedByPostId = LruCache<String, Boolean>(200)
    private val dualMediaPostLock = Any()
    private val dualMediaPostIds = java.util.WeakHashMap<Any, String>()
    private val pendingGridPostLock = Any()
    private var pendingGridPostId: String? = null
    private var pendingGridPostAtMillis: Long = 0L
    @Volatile private var activeGridDetailPostId: String? = null
    private val composingGridDetailPostId = ThreadLocal<String?>()

    @JvmStatic
    fun capture(model: Any?, symbols: FeedMediaSymbols?): Boolean {
        if (model == null || symbols == null || !symbols.canCaptureFeedMedia) return false
        return try {
            require(symbols.postModelClass!!.isInstance(model))
            val id = symbols.postIdField!!.get(model) as? String ?: return false
            if (id.isBlank()) return false
            val primary = readMedia(symbols.postPrimaryMediaField?.get(model), symbols)
                ?: readMedia(symbols.postPrimaryField!!.get(model), symbols)
            val secondary = readMedia(symbols.postSecondaryMediaField?.get(model), symbols)
                ?: readMedia(symbols.postSecondaryField!!.get(model), symbols)
            if (primary == null && secondary == null) return false
            val post = FeedPostMedia(
                postId = id,
                takenAt = symbols.postTakenAtField?.get(model) as? String,
                caption = symbols.postCaptionField?.get(model) as? String,
                primary = primary,
                secondary = secondary,
                isOwnPost = null,
                capturedAtMillis = System.currentTimeMillis(),
                postedAt = runCatching { model.javaClass.getDeclaredField("postedAt").apply { isAccessible = true }.get(model) as? String }.getOrNull(),
                bts = readMedia(symbols.postBtsMediaField?.get(model), symbols),
            )
            withCache {
                symbols.remotePostLocationSymbols?.let { reader ->
                    remoteLocationSources.put(id, RemoteLocationSource(model, reader))
                    val available = reader.hasLocationFieldValue(model)
                    if (available && loggedRemoteWithLocation.compareAndSet(false, true))
                        android.util.Log.i("BeFuck/Cache", "Feed PostRemoteModel contains a shared location field")
                    if (!available && loggedRemoteWithoutLocation.compareAndSet(false, true))
                        android.util.Log.i("BeFuck/Cache", "Feed PostRemoteModel location is null; cannot archive it from this feed response")
                }
                storePost(post)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun captureVisibleFeedState(viewState: Any?, symbols: FeedMediaSymbols?): Boolean {
        if (viewState == null || symbols == null || !symbols.canCaptureVisibleFeedMedia) return false
        return try {
            val postData = symbols.viewStatePostDataField!!.get(viewState) ?: return false
            capturePostDataIdentity(postData, symbols)
            val postId = symbols.postDataIdField!!.get(postData) as? String ?: return false
            if (postId.isBlank()) return false
            val isOwnPost = symbols.postDataIsMineField!!.getBoolean(postData)
            val dualMedia = symbols.viewStateDualMediaField!!.get(viewState) ?: return false
            val primaryValue = symbols.dualMediaPrimaryField!!.get(dualMedia)
            val secondaryValue = symbols.dualMediaSecondaryField!!.get(dualMedia)
            val primaryResolved = readUiMedia(primaryValue)
            val secondaryResolved = readUiMedia(secondaryValue)
            withCache {
                val observed = FeedPostMedia(postId, null, null, primaryResolved, secondaryResolved, isOwnPost,
                    System.currentTimeMillis(),
                    username = Regex(", ownerUsername=([^,)]*)").find(postData.toString())?.groupValues?.get(1)?.takeIf(String::isNotBlank))
                if (observed.primary == null && observed.secondary == null) return false
                storePost(observed)
            }
            synchronized(dualMediaPostLock) { dualMediaPostIds[dualMedia] = postId }
            activeTimelinePostId = postId
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun beginVisiblePostComposition(viewState: Any?, symbols: FeedMediaSymbols?): FeedPostMedia? {
        composingPostId.remove()
        if (viewState == null || symbols == null || !symbols.canCaptureVisibleFeedMedia) return null
        val postId = runCatching {
            val postData = symbols.viewStatePostDataField!!.get(viewState) ?: return null
            symbols.postDataIdField!!.get(postData) as? String
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
        if (!captureVisibleFeedState(viewState, symbols)) return null
        composingPostId.set(postId)
        return get(postId)
    }

    @JvmStatic
    fun composingPost(): FeedPostMedia? {
        val postId = currentComposingPostId() ?: return null
        return withCache { cache.get(postId) }
    }

    @JvmStatic
    fun postForDualMedia(dualMedia: Any?): FeedPostMedia? {
        if (dualMedia == null) return null
        val postId = postIdForDualMedia(dualMedia)
            ?: return null
        return withCache { cache.get(postId) }
    }

    @JvmStatic
    fun beginDualMediaComposition(dualMedia: Any?): String? {
        val postId = if (dualMedia == null) null else postIdForDualMedia(dualMedia)
        val selectedPostId = postId ?: composingPostId.get()
        if (selectedPostId == null) currentDualMediaPostId.remove() else currentDualMediaPostId.set(selectedPostId)
        return selectedPostId
    }

    @JvmStatic
    fun endDualMediaComposition() {
        currentDualMediaPostId.remove()
    }

    @JvmStatic
    fun noteGridPostSelected(postId: String?) {
        if (postId.isNullOrBlank()) return
        synchronized(pendingGridPostLock) {
            pendingGridPostId = postId
            pendingGridPostAtMillis = System.currentTimeMillis()
        }
        activeGridDetailPostId = postId
    }

    @JvmStatic
    fun beginGridDetailComposition(postId: String?): String? {
        val selectedPostId = activeGridDetailPostId
        if (selectedPostId != null && selectedPostId == postId) {
            composingGridDetailPostId.set(selectedPostId)
            return selectedPostId
        }
        composingGridDetailPostId.remove()
        return null
    }

    @JvmStatic
    fun composingGridDetailPost(): FeedPostMedia? {
        val postId = composingGridDetailPostId.get() ?: return null
        return withCache { cache.get(postId) }
    }

    @JvmStatic
    fun endGridDetailComposition() {
        composingGridDetailPostId.remove()
    }

    @JvmStatic
    fun endGridDetail(postId: String) {
        if (activeGridDetailPostId == postId) activeGridDetailPostId = null
        synchronized(pendingGridPostLock) {
            if (pendingGridPostId == postId) {
                pendingGridPostId = null
                pendingGridPostAtMillis = 0L
            }
        }
    }

    @JvmStatic
    fun hasPendingGridPostForDetail(): Boolean = synchronized(pendingGridPostLock) {
        val elapsed = System.currentTimeMillis() - pendingGridPostAtMillis
        pendingGridPostId != null && elapsed >= 0L && elapsed <= 15_000L
    }

    @JvmStatic
    fun clearPendingGridPostForDetailIfMatches(candidate: Any?) {
        val postId = (candidate as? FeedPostMedia)?.postId ?: return
        synchronized(pendingGridPostLock) {
            if (pendingGridPostId == postId) {
                pendingGridPostId = null
                pendingGridPostAtMillis = 0L
            }
        }
    }

    @JvmStatic
    fun consumePendingGridPostForDetail(dualMedia: Any?): FeedPostMedia? {
        val mediaPostId = dualMedia?.let(::postIdForDualMedia) ?: return null
        val matchesSelection = synchronized(pendingGridPostLock) {
            val elapsed = System.currentTimeMillis() - pendingGridPostAtMillis
            val isCurrentSelection = pendingGridPostId == mediaPostId && elapsed >= 0L && elapsed <= 15_000L
            if (isCurrentSelection) {
                pendingGridPostId = null
                pendingGridPostAtMillis = 0L
            }
            isCurrentSelection
        }
        return if (matchesSelection) withCache { cache.get(mediaPostId) } else null
    }

    @JvmStatic
    fun consumeSelectedGridPostForDetail(dualMedia: Any?): FeedPostMedia? {
        val postId = synchronized(pendingGridPostLock) {
            val selectedPostId = pendingGridPostId
            val elapsed = System.currentTimeMillis() - pendingGridPostAtMillis
            val isCurrentSelection = selectedPostId != null && elapsed >= 0L && elapsed <= 20_000L
            if (isCurrentSelection) {
                pendingGridPostId = null
                pendingGridPostAtMillis = 0L
            }
            selectedPostId.takeIf { isCurrentSelection }
        } ?: return null
        val post = withCache { cache.get(postId) } ?: return null
        if (dualMedia != null) synchronized(dualMediaPostLock) { dualMediaPostIds[dualMedia] = postId }
        return post
    }

    @JvmStatic
    fun setComposingPrimaryDisplayed(isPrimaryDisplayed: Boolean) {
        val postId = currentComposingPostId() ?: return
        synchronized(flipStateLock) { primaryDisplayedByPostId.put(postId, isPrimaryDisplayed) }
    }

    @JvmStatic
    fun composingPostId(): String? = currentComposingPostId()

    @JvmStatic
    fun isPrimaryDisplayed(postId: String): Boolean = synchronized(flipStateLock) {
        primaryDisplayedByPostId.get(postId) ?: true
    }

    @JvmStatic
    fun associateFlipStateWithComposingPost(state: Any?) {
        val postId = currentComposingPostId() ?: return
        if (state != null) synchronized(flipStateLock) { flipStatePostIds[state] = postId }
    }

    @JvmStatic
    fun updatePrimaryDisplayedFromFlipState(state: Any?, isSecondaryDisplayedAsPrimary: Boolean) {
        if (state == null) return
        val postId = synchronized(flipStateLock) { flipStatePostIds[state] } ?: return
        synchronized(flipStateLock) { primaryDisplayedByPostId.put(postId, !isSecondaryDisplayedAsPrimary) }
    }

    @JvmStatic
    fun endVisiblePostComposition() {
        composingPostId.remove()
    }

    private fun currentComposingPostId(): String? = currentDualMediaPostId.get() ?: composingPostId.get()

    private fun postIdForDualMedia(dualMedia: Any): String? {
        synchronized(dualMediaPostLock) { dualMediaPostIds[dualMedia] }?.let { return it }
        val urls = mutableListOf<String>()
        collectHttpsStrings(dualMedia, urls, Collections.newSetFromMap(IdentityHashMap()), 0)
        if (urls.isEmpty()) return null
        val exactUrls = urls.toHashSet()
        val normalizedUrls = urls.mapTo(HashSet(), ::normalizedMediaUrl)
        val matchingPostIds = withCache {
            cache.snapshot().values.asSequence()
                .filter { post ->
                    listOfNotNull(post.primary?.url, post.secondary?.url).any { url ->
                        url in exactUrls || normalizedMediaUrl(url) in normalizedUrls
                    }
                }
                .map(FeedPostMedia::postId)
                .distinct()
                .toList()
        }
        val postId = matchingPostIds.singleOrNull() ?: return null
        synchronized(dualMediaPostLock) { dualMediaPostIds[dualMedia] = postId }
        return postId
    }

    private fun normalizedMediaUrl(url: String): String = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return@runCatching url.substringBefore('?')
        val path = uri.path.orEmpty()
        if (path.isEmpty()) url.substringBefore('?') else "$host$path"
    }.getOrDefault(url.substringBefore('?'))

    @JvmStatic
    fun recent(): List<FeedPostMedia> = ArchiveRepository.recent(ArchiveAccounts.snapshot().uid.orEmpty())

    @JvmStatic
    fun activeTimelinePost(): FeedPostMedia? = withCache {
        val activeId = activeTimelinePostId
        activeId?.let(cache::get)
            ?: cache.snapshot().values.maxByOrNull { it.capturedAtMillis }
    }

    @JvmStatic
    fun size(): Int = withCache { cache.size() }

    fun hasOwnMainPost(momentId: String): Boolean = recent().any {
        it.momentId == momentId && it.isMain == true &&
            (it.isOwnPost == true || it.ownerUid == ArchiveAccounts.snapshot().uid && it.ownerUid != null)
    }

    private fun readMedia(value: Any?, symbols: FeedMediaSymbols): FeedMediaItem? {
        if (value == null || symbols.mediaUrlField == null) return null
        val url = symbols.mediaUrlField.get(value) as? String ?: return null
        if (url.isBlank() || !url.startsWith("https://", ignoreCase = true)) return null
        val width = symbols.mediaWidthField?.get(value).toDimension()
        val height = symbols.mediaHeightField?.get(value).toDimension()
        val mediaType = symbols.mediaTypeField?.get(value) as? String
        return FeedMediaItem(url, mediaType, width, height)
    }

    private fun readUiMedia(value: Any?): FeedMediaItem? {
        if (value == null) return null
        val description = runCatching { value.toString() }.getOrDefault("")
        val isVideo = description.startsWith("Video(")
        val urls = mutableListOf<String>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        collectHttpsStrings(value, urls, seen, 0)
        val typedUrl = Regex("MediaRemoteModel\\(url=(https://[^,\\s)]+)")
            .find(description)
            ?.groupValues
            ?.getOrNull(1)
        val url = typedUrl ?: urls.firstOrNull() ?: return null
        val pathExtension = runCatching { URI(url).path.orEmpty().substringAfterLast('.', "").lowercase() }
            .getOrDefault("")
        val imageMimeType = when (pathExtension) {
            "webp" -> "image/webp"
            "png" -> "image/png"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        return FeedMediaItem(
            url = url,
            mimeType = if (isVideo) "video/mp4" else imageMimeType,
            width = null,
            height = null,
        )
    }

    private fun collectHttpsStrings(value: Any?, urls: MutableList<String>, seen: MutableSet<Any>, depth: Int) {
        if (value == null || depth > 5 || urls.size >= 8) return
        if (value is String) {
            if (value.startsWith("https://", ignoreCase = true) && value.length < 8_192) urls += value
            return
        }
        if (!seen.add(value)) return
        if (value is Iterable<*>) {
            value.forEach { collectHttpsStrings(it, urls, seen, depth + 1) }
            return
        }
        if (value.javaClass.isArray) {
            for (index in 0 until java.lang.reflect.Array.getLength(value)) {
                collectHttpsStrings(java.lang.reflect.Array.get(value, index), urls, seen, depth + 1)
            }
            return
        }
        val packageName = value.javaClass.name
        if (packageName.startsWith("java.") || packageName.startsWith("android.") || packageName.startsWith("kotlin.")) return
        for (field in value.javaClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive || urls.size >= 8) continue
            try {
                field.isAccessible = true
                collectHttpsStrings(field.get(value), urls, seen, depth + 1)
            } catch (_: Throwable) {
            }
        }
    }

    private fun Any?.toDimension(): Int? = when (this) {
        is Number -> toDouble().takeIf { it > 0.0 && it.isFinite() }?.toInt()
        else -> null
    }
}
