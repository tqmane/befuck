package dev.tqmane.befuck.ui

import dev.tqmane.befuck.posting.LocationData
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal data class PlaceResult(val label: String, val location: LocationData)

/** Explicit searches only: bounded responses, cancellation, caching and process-wide throttling. */
internal class GeocodingRequest(private val query: String, private val language: String,
    private val endpoint: String = DEFAULT_ENDPOINT) {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { cancelled.set(true); connection?.disconnect() }
    private fun ensureActive() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException() }
    fun execute(): List<PlaceResult> {
        ensureActive()
        require(validEndpoint(endpoint)) { "Invalid geocoding endpoint" }
        val key = endpoint + "\n" + language + "\n" + query.trim().lowercase(Locale.ROOT)
        synchronized(cache) { cache[key]?.let { return it } }
        synchronized(rateLock) {
            ensureActive()
            val wait = 1_050_000_000L - (System.nanoTime() - lastRequestNanos)
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            ensureActive(); lastRequestNanos = System.nanoTime()
        }
        val encoded = URLEncoder.encode(query.trim().take(300), "UTF-8")
        val conn = URL("$endpoint?format=jsonv2&limit=5&q=$encoded").openConnection() as HttpURLConnection
        connection = conn
        try {
            ensureActive()
            conn.connectTimeout = 8_000; conn.readTimeout = 8_000; conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Language", language)
            conn.setRequestProperty("User-Agent", "BeFuckLocation/1.0 (+https://github.com/tqmane/befuck)")
            if (conn.responseCode != 200) throw IOException("Geocoding request failed")
            val data = conn.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) {
                    ensureActive(); val count = stream.read(buffer); if (count < 0) break
                    if (out.size() + count > MAX_RESPONSE_BYTES) throw IOException("Geocoding response too large")
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
            ensureActive()
            return parse(data).also { results -> synchronized(cache) {
                cache[key] = results; while (cache.size > 32) cache.remove(cache.keys.first())
            } }
        } finally { connection = null; conn.disconnect() }
    }
    companion object {
        const val DEFAULT_ENDPOINT = "https://nominatim.openstreetmap.org/search"
        const val ENDPOINT_PREFERENCE = "geocoding_endpoint"
        private const val MAX_RESPONSE_BYTES = 131_072
        private val rateLock = Any()
        private var lastRequestNanos = 0L
        private val cache = linkedMapOf<String, List<PlaceResult>>()
        fun validEndpoint(value: String): Boolean = runCatching {
            val uri = java.net.URI(value)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && value.length <= 2048
        }.getOrDefault(false)
        internal fun parse(body: String): List<PlaceResult> {
            if (body.length > MAX_RESPONSE_BYTES) throw IOException("Geocoding response too large")
            val entries = JSONArray(body)
            return (0 until minOf(entries.length(), 5)).mapNotNull { index ->
                val entry = entries.optJSONObject(index) ?: return@mapNotNull null
                val latitude = entry.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                val longitude = entry.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                val location = LocationData(latitude, longitude)
                if (!LocationSelectionState.isValid(location)) return@mapNotNull null
                val label = entry.optString("display_name").take(400).trim()
                if (label.isEmpty()) null else PlaceResult(label, location)
            }
        }
    }
}
