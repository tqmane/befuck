package dev.tqmane.befuck.posting

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ThreadLocalRandom
import java.util.function.Consumer

/** BeFake's signed-URL -> object PUTs -> create-post API sequence. */
object BeFakeUploadController {
    private const val TAG = "BeFuck/BeFake"
    private fun getApiRoot(): String = BeFakeAuthHeaders.getApiRoot()
    private const val MAX_RESPONSE_BYTES = 1_048_576
    private val executor = Executors.newSingleThreadExecutor()
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        .withZone(ZoneOffset.UTC)

    private data class SignedUpload(
        val url: String,
        val path: String,
        val bucket: String,
        val headers: Map<String, String>,
    )

    private data class SignedUploadPair(val back: SignedUpload, val front: SignedUpload)

    private data class ApiResponse(val status: Int, val body: ByteArray)

    @JvmStatic
    fun postNow(context: Context, request: GalleryPostRequest, completion: Consumer<Boolean>) {
        val completed = AtomicBoolean(false)
        fun finish(success: Boolean) {
            if (completed.compareAndSet(false, true)) completion.accept(success)
        }

        executor.execute {
            try {
                val back = requireNotNull(request.back) { "The back photo is required for a post" }
                val front = requireNotNull(request.front) { "The front photo is required for a post" }
                require(back.isVideo == front.isVideo) {
                    "Front and back media must both be photos or both be videos"
                }
                val initialHeaders = apiHeaders()
                Log.i(TAG, "postNow started. ApiRoot: ${getApiRoot()}, headers: ${initialHeaders.keys}")
                val takenAt = resolveTakenAt(initialHeaders, request.isLate)
                require(!back.isVideo && !front.isVideo && back.mimeType == "image/webp" && front.mimeType == "image/webp") {
                    "This uploader requires prepared WebP photos; videos use the official DualVideo pipeline"
                }
                val uploads = requestSignedUploads(initialHeaders)
                putMedia(uploads.back, back)
                putMedia(uploads.front, front)
                val postPayload = createPayload(request, uploads, takenAt)

                val postResponse = apiRequest(
                    path = "/content/posts",
                    method = "POST",
                    headers = apiHeaders(),
                    body = postPayload.toString().toByteArray(Charsets.UTF_8),
                    contentType = "application/json",
                )
                Log.i(TAG, "Create-post endpoint returned HTTP ${postResponse.status}")
                if (postResponse.status !in 200..299) {
                    throw IOException("Create-post request failed with HTTP ${postResponse.status}")
                }
                finish(true)
            } catch (failure: Throwable) {
                Log.e(TAG, "BeFake post failed before a confirmed create-post response", failure)
                finish(false)
            }
        }
    }

    private fun apiHeaders(): Map<String, String> {
        val captured = BeFakeAuthHeaders.snapshot()
        val authorization = captured["Authorization"]
        check(!authorization.isNullOrBlank()) {
            "No BeReal Authorization header captured yet. Open the feed and wait for it to finish loading, then retry."
        }
        return captured
    }

    private fun requestSignedUploads(headers: Map<String, String>): SignedUploadPair {
        val response = apiRequest(
            path = "/content/posts/upload-url?mimeType=image%2Fwebp",
            method = "GET",
            headers = headers,
        )
        Log.i(TAG, "Signed-upload endpoint returned HTTP ${response.status} for image/webp")
        if (response.status !in 200..299) {
            throw IOException("Signed-upload request failed with HTTP ${response.status}")
        }
        val root = JSONObject(response.body.toString(Charsets.UTF_8))
        val data = root.optJSONArray("data") ?: error("Signed-upload response omitted data slots")
        require(data.length() >= 2) { "Signed-upload response had ${data.length()} media slots; expected at least 2" }
        return SignedUploadPair(back = parseSignedSlot(data, 1), front = parseSignedSlot(data, 0))
    }

    private fun parseSignedSlot(data: JSONArray, index: Int): SignedUpload {
            val item = data.optJSONObject(index) ?: error("Signed-upload slot $index is invalid")
            val uploadUrl = item.optString("url")
            val path = item.optString("path")
            val bucket = item.optString("bucket")
            val putHeaders = item.optJSONObject("headers") ?: JSONObject()
            require(uploadUrl.startsWith("https://")) { "Signed-upload slot $index did not provide an HTTPS URL" }
            require(path.isNotBlank() && bucket.isNotBlank()) { "Signed-upload slot $index omitted path or bucket" }
            val copiedHeaders = buildMap {
                putHeaders.keys().forEach { key ->
                    val value = putHeaders.optString(key)
                    if (value.isNotBlank()) put(key, value)
                }
            }
            return SignedUpload(uploadUrl, path, bucket, copiedHeaders)
    }

    private fun putMedia(slot: SignedUpload, media: GalleryMediaFile) {
                val file = File(media.path)
                require(file.isFile && file.length() > 0L) { "Prepared upload media is missing or empty" }
        val sizeLimit = if (media.isVideo) 512L * 1024L * 1024L else 16L * 1024L * 1024L
        require(file.length() <= sizeLimit) { "Prepared media exceeds the ${sizeLimit / 1024 / 1024} MiB upload limit" }
        val signedContentType = slot.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        require(signedContentType == null || signedContentType.equals(media.mimeType, ignoreCase = true)) {
            "Signed upload slot expects $signedContentType but prepared media is ${media.mimeType}"
        }
        val connection = URL(slot.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = 15_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(file.length())
            slot.headers.forEach(connection::setRequestProperty)
            if (signedContentType == null) connection.setRequestProperty("Content-Type", media.mimeType)
            file.inputStream().use { input -> connection.outputStream.use(input::copyTo) }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("Signed media PUT failed with HTTP $status")
            }
            Log.i(TAG, "Signed ${if (media.isVideo) "video" else "image"} PUT completed with HTTP $status (${file.length()} bytes)")
        } finally {
            connection.disconnect()
        }
    }

    private fun createPayload(
        request: GalleryPostRequest,
        uploads: SignedUploadPair,
        takenAt: String,
    ): JSONObject {
        val payload = JSONObject()
            .put("visibility", JSONArray().put(request.visibility))
            .put("isLate", request.isLate)
            .put("retakeCounter", request.retakeCount)
            .put("takenAt", takenAt)
            .put("backCamera", mediaPayload(uploads.back, requireNotNull(request.back)))
            .put("frontCamera", mediaPayload(uploads.front, requireNotNull(request.front)))
        if (request.caption.isNotBlank()) payload.put("caption", request.caption)
        request.location?.let { loc ->
            payload.put("location", JSONObject()
                .put("latitude", loc.latitude)
                .put("longitude", loc.longitude)
            )
        }
        return payload
    }

    private fun mediaPayload(slot: SignedUpload, media: GalleryMediaFile): JSONObject = JSONObject()
        .put("bucket", slot.bucket)
        .put("height", media.height)
        .put("width", media.width)
        .put("path", slot.path)

    private fun resolveTakenAt(headers: Map<String, String>, isLate: Boolean): String {
        if (!isLate) {
            val startDate = fetchLastMomentStart(headers)
            if (startDate != null) {
                val now = Instant.now()
                val proposed = startDate.plusSeconds(ThreadLocalRandom.current().nextLong(60, 105))
                val instant = minOf(now, proposed)
                if (proposed.isAfter(now)) Log.i(TAG, "Clamped computed takenAt to the current time")
                return timestampFormatter.format(instant)
            }
            Log.w(TAG, "Current BeReal moment could not be resolved; using current time for takenAt")
        }
        return timestampFormatter.format(Instant.now())
    }

    private fun fetchLastMomentStart(headers: Map<String, String>): Instant? {
        return runCatching {
            val me = apiRequest("/person/me", "GET", headers)
            if (me.status !in 200..299) {
                Log.w(TAG, "Current-user endpoint returned HTTP ${me.status} while resolving the moment")
                return@runCatching null
            }
            val region = JSONObject(me.body.toString(Charsets.UTF_8)).optString("region")
            if (!region.matches(Regex("[A-Za-z0-9_-]{1,32}"))) {
                Log.w(TAG, "Current-user response did not include a usable region for moment lookup")
                return@runCatching null
            }
            val encodedRegion = URLEncoder.encode(region, Charsets.UTF_8.name())
            val moment = apiRequest("/bereal/moments/last/$encodedRegion", "GET", headers)
            if (moment.status !in 200..299) {
                Log.w(TAG, "Last-moment endpoint returned HTTP ${moment.status}")
                return@runCatching null
            }
            val startDate = JSONObject(moment.body.toString(Charsets.UTF_8)).optString("startDate")
            val parsed = runCatching { OffsetDateTime.parse(startDate).toInstant() }
                .recoverCatching { Instant.parse(startDate) }
                .getOrNull()
            if (parsed == null) Log.w(TAG, "Last-moment response did not contain a parseable startDate")
            parsed
        }.onFailure { Log.w(TAG, "Could not resolve the current BeReal moment; using current time") }
            .getOrNull()
    }

    private fun apiRequest(
        path: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
        contentType: String? = null,
    ): ApiResponse {
        val fullUrl = getApiRoot() + path
        val connection = URL(fullUrl).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach(connection::setRequestProperty)
            if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBytes = stream?.use(::readBounded) ?: ByteArray(0)
            if (status !in 200..299) {
                Log.w(TAG, "API request $method $path failed with HTTP $status")
            }
            return ApiResponse(status, responseBytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_RESPONSE_BYTES) { "BeReal API response exceeded the 1 MiB limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

}
