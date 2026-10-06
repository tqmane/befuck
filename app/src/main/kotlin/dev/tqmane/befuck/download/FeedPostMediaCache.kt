package dev.tqmane.befuck.download

import android.util.LruCache
import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.util.concurrent.Executors
import dev.tqmane.befuck.symbols.FeedMediaSymbols
import java.net.URI
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

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
)

/** Bounded, process-local index of posts BeReal has already deserialized for the feed. */
object FeedPostMediaCache {
    private val cache = LruCache<String, FeedPostMedia>(200)
    private val storage = Executors.newSingleThreadExecutor()
    @Volatile private var database: SQLiteDatabase? = null
    private var initializing = false
    private val currentUsernames = java.util.concurrent.ConcurrentHashMap<String, String>()

    @JvmStatic
    fun captureCurrentUser(model: Any?) {
        if (model == null) return
        runCatching {
            fun text(name: String) = model.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(model) as? String
            val uid = text("uid")?.takeIf { it.isNotBlank() } ?: return
            val username = text("userName")?.takeIf { it.isNotBlank() } ?: return
            currentUsernames[uid] = username
            synchronized(cache) {
                cache.snapshot().values.filter { it.ownerUid == uid && it.username.isNullOrBlank() }
                    .forEach { storePost(it.copy(username = username)) }
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun initialize(context: Context) {
        if (initializing) return
        initializing = true
        val path = context.getDatabasePath("befuck-post-urls.db")
        storage.execute {
            runCatching {
                path.parentFile?.mkdirs()
                val db = SQLiteDatabase.openOrCreateDatabase(path, null)
                db.execSQL("CREATE TABLE IF NOT EXISTS posts (id TEXT PRIMARY KEY, captured INTEGER NOT NULL, data TEXT NOT NULL)")
                database = db
                synchronized(cache) { cache.snapshot().values.toList() }.forEach(::persist)
                android.util.Log.i("BeFuck/Cache", "Persistent post URL index ready")
            }.onFailure { android.util.Log.e("BeFuck/Cache", "Could not open post URL index", it) }
        }
    }

    private fun persist(post: FeedPostMedia) {
        val db = database ?: return
        // Native SQLite serializes these small per-post writes; media bytes are never cached here.
        runCatching {
            db.insertWithOnConflict("posts", null, ContentValues().apply {
                put("id", post.postId)
                put("captured", post.capturedAtMillis)
                put("data", toJson(post).toString())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }.onFailure { android.util.Log.e("BeFuck/Cache", "Could not persist post URLs", it) }
    }

    private fun storePost(post: FeedPostMedia) {
        val previous = cache.get(post.postId)
        if (previous != null && post.copy(capturedAtMillis = previous.capturedAtMillis) == previous) return
        cache.put(post.postId, post)
        storage.execute { persist(post) }
    }

    private fun toJson(post: FeedPostMedia): JSONObject = JSONObject().apply {
        put("id", post.postId); put("takenAt", post.takenAt); put("postedAt", post.postedAt)
        put("username", post.username); put("caption", post.caption); put("own", post.isOwnPost)
        put("ownerUid", post.ownerUid)
        put("momentId", post.momentId); put("main", post.isMain)
        put("captured", post.capturedAtMillis)
        fun media(item: FeedMediaItem?) = item?.let {
            JSONObject().put("url", it.url).put("mime", it.mimeType).put("width", it.width).put("height", it.height)
        }
        put("primary", media(post.primary)); put("secondary", media(post.secondary)); put("bts", media(post.bts))
    }

    private fun fromJson(json: JSONObject): FeedPostMedia {
        fun text(key: String) = json.optString(key).takeIf { it.isNotBlank() && it != "null" }
        fun media(key: String): FeedMediaItem? = json.optJSONObject(key)?.let {
            FeedMediaItem(it.getString("url"), it.optString("mime").takeIf(String::isNotBlank),
                it.optInt("width").takeIf { n -> n > 0 }, it.optInt("height").takeIf { n -> n > 0 })
        }
        return FeedPostMedia(json.getString("id"), text("takenAt"), text("caption"), media("primary"), media("secondary"),
            if (json.has("own")) json.getBoolean("own") else null, json.getLong("captured"), text("username"), text("postedAt"), media("bts"), text("ownerUid"), text("momentId"), if (json.has("main")) json.getBoolean("main") else null)
    }

    @JvmStatic
    fun get(postId: String): FeedPostMedia? = synchronized(cache) { cache.get(postId) } ?: runCatching {
        database?.rawQuery("SELECT data FROM posts WHERE id = ?", arrayOf(postId))?.use {
            if (it.moveToFirst()) fromJson(JSONObject(it.getString(0))) else null
        }
    }.getOrNull()

    /** Field mapping checked against the 3.97.0 CorePost constructor, including database-loaded posts. */
    @JvmStatic
    fun captureCorePost(model: Any?) {
        if (model == null) return
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
                if (primaryModel?.javaClass?.simpleName == "gmg") media(primaryModel, bts = true) else null, ownerUid,
                moment?.let { field(it, "a") as? String }, field(model, "o") as? Boolean)
            if (captured.primary == null && captured.secondary == null) return
            synchronized(cache) {
                val existing = get(id)
                storePost(captured.copy(
                    isOwnPost = if (ownerUid != null && currentUsernames.containsKey(ownerUid)) true else existing?.isOwnPost,
                    username = captured.username?.takeIf { it.isNotBlank() } ?: existing?.username,
                ))
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
            synchronized(cache) {
                val existing = get(id)
                storePost(
                    post.copy(
                        takenAt = post.takenAt ?: existing?.takenAt,
                        caption = post.caption ?: existing?.caption,
                        primary = post.primary ?: existing?.primary,
                        secondary = post.secondary ?: existing?.secondary,
                        isOwnPost = existing?.isOwnPost,
                        username = existing?.username,
                        postedAt = post.postedAt ?: existing?.postedAt,
                        bts = post.bts ?: existing?.bts,
                        ownerUid = existing?.ownerUid,
                        momentId = existing?.momentId,
                        isMain = existing?.isMain,
                    ),
                )
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
            val postId = symbols.postDataIdField!!.get(postData) as? String ?: return false
            if (postId.isBlank()) return false
            val isOwnPost = symbols.postDataIsMineField!!.getBoolean(postData)
            val dualMedia = symbols.viewStateDualMediaField!!.get(viewState) ?: return false
            val primaryValue = symbols.dualMediaPrimaryField!!.get(dualMedia)
            val secondaryValue = symbols.dualMediaSecondaryField!!.get(dualMedia)
            val primaryResolved = readUiMedia(primaryValue)
            val secondaryResolved = readUiMedia(secondaryValue)
            synchronized(cache) {
                val existing = get(postId)
                val merged = FeedPostMedia(
                    postId = postId,
                    takenAt = existing?.takenAt,
                    caption = existing?.caption,
                    primary = existing?.primary ?: primaryResolved,
                    secondary = existing?.secondary ?: secondaryResolved,
                    isOwnPost = isOwnPost,
                    capturedAtMillis = System.currentTimeMillis(),
                    username = Regex(", ownerUsername=([^,)]*)").find(postData.toString())?.groupValues?.get(1)
                        ?.takeIf { it.isNotBlank() } ?: existing?.username,
                    postedAt = existing?.postedAt,
                    bts = existing?.bts,
                    ownerUid = existing?.ownerUid,
                    momentId = existing?.momentId,
                    isMain = existing?.isMain,
                )
                if (merged.primary == null && merged.secondary == null) return false
                storePost(merged)
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
        return synchronized(cache) { cache.get(postId) }
    }

    @JvmStatic
    fun postForDualMedia(dualMedia: Any?): FeedPostMedia? {
        if (dualMedia == null) return null
        val postId = postIdForDualMedia(dualMedia)
            ?: return null
        return synchronized(cache) { cache.get(postId) }
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
        return synchronized(cache) { cache.get(postId) }
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
        return if (matchesSelection) synchronized(cache) { cache.get(mediaPostId) } else null
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
        val post = synchronized(cache) { cache.get(postId) } ?: return null
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
        val matchingPostIds = synchronized(cache) {
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
    fun recent(): List<FeedPostMedia> {
        val posts = linkedMapOf<String, FeedPostMedia>()
        runCatching {
            database?.rawQuery("SELECT data FROM posts ORDER BY captured DESC", null)?.use { cursor ->
                while (cursor.moveToNext()) runCatching { fromJson(JSONObject(cursor.getString(0))) }
                    .getOrNull()?.let { posts[it.postId] = it }
            }
        }
        synchronized(cache) { cache.snapshot().values.forEach { posts[it.postId] = it } }
        return posts.values.sortedByDescending { PostMediaMetadata.timestamp(it) ?: it.capturedAtMillis }
    }

    @JvmStatic
    fun activeTimelinePost(): FeedPostMedia? = synchronized(cache) {
        val activeId = activeTimelinePostId
        activeId?.let(cache::get)
            ?: cache.snapshot().values.maxByOrNull { it.capturedAtMillis }
    }

    @JvmStatic
    fun size(): Int = synchronized(cache) { cache.size() }

    fun hasOwnMainPost(momentId: String): Boolean = recent().any {
        it.momentId == momentId && it.isMain == true &&
            (it.isOwnPost == true || it.ownerUid?.let(currentUsernames::containsKey) == true)
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
