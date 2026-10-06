package dev.tqmane.befuck.posting

import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Holds only the current BeReal API headers in memory; values are never logged or persisted. */
object BeFakeAuthHeaders {
    private val headers = AtomicReference<Map<String, String>>(emptyMap())
    private val apiHost = AtomicReference<String>("mobile-l7.bereal.com")

    @JvmStatic
    fun setApiHost(host: String?) {
        if (!host.isNullOrBlank() && isBeRealHost(host)) {
            apiHost.set(host.trim())
        }
    }

    @JvmStatic
    fun getApiRoot(): String = "https://${apiHost.get()}/api"

    @JvmStatic
    fun capture(urlHost: String?, name: String?, value: String?) {
        if (urlHost.isNullOrBlank() || !isBeRealHost(urlHost) || name.isNullOrBlank() || value.isNullOrBlank()) return
        val lower = name.lowercase(Locale.ROOT)
        val shouldCapture = lower == "authorization" || lower == "user-agent" || lower.startsWith("bereal-") || lower.startsWith("x-bereal")
        if (!shouldCapture) return

        val canonical = when (lower) {
            "authorization" -> "Authorization"
            "user-agent" -> "User-Agent"
            else -> lower
        }
        while (true) {
            val current = headers.get()
            val updated = HashMap(current)
            updated[canonical] = value
            if (headers.compareAndSet(current, Collections.unmodifiableMap(updated))) return
        }
    }

    @JvmStatic
    fun snapshot(): Map<String, String> = headers.get()

    @JvmStatic
    fun hasAuthorization(): Boolean = headers.get()["Authorization"].isNullOrBlank().not()

    private fun isBeRealHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT).trimEnd('.')
        return normalized == "bereal.com" || normalized.endsWith(".bereal.com")
    }
}
