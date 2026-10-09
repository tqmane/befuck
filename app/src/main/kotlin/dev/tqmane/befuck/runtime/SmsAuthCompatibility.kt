package dev.tqmane.befuck.runtime

import dev.tqmane.befuck.symbols.KnownMappings3970
import java.net.URI
import java.util.Base64
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/** Adapts only the two Vonage SMS calls; the host still verifies the user's code. */
object SmsAuthCompatibility {
    @JvmStatic
    fun matches(method: String, url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return method == "POST" && uri.scheme == "https" && uri.host == "auth-l7.bereal.com" &&
            uri.port in listOf(-1, 443) && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && uri.rawPath in listOf(
                "/api/vonage/request-code", "/api/vonage/check-code")
    }

    @JvmStatic
    fun headers(deviceId: String, seconds: Long, timezone: String): Map<String, String> {
        require(deviceId.isNotBlank() && timezone.isNotBlank())
        val base64 = Base64.getEncoder()
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(KnownMappings3970.SMS_CLIENT_HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val input = base64.encode("$deviceId$timezone$seconds".toByteArray(Charsets.UTF_8))
        val signature = base64.encodeToString("1:$seconds:".toByteArray(Charsets.UTF_8) + mac.doFinal(input))
        return KnownMappings3970.SMS_CLIENT_HEADERS + mapOf(
            "bereal-device-id" to deviceId, "bereal-timezone" to timezone, "bereal-signature" to signature)
    }

    /** Returns a replacement builder, keeping credentials and request bodies in memory. */
    @JvmStatic
    fun replacementBuilder(request: Any): Any? {
        val type = request.javaClass
        val url = type.getMethod("url").invoke(request).toString()
        if (!matches(type.getMethod("method").invoke(request) as String, url)) return null
        val deviceId = type.getMethod("header", String::class.java).invoke(request, "bereal-device-id") as? String
        if (deviceId.isNullOrBlank()) return null // Retrofit builds once before the identity interceptor runs.
        val builder = type.getMethod("newBuilder").invoke(request)
        if (URI(url).rawPath == "/api/vonage/request-code") {
            val loader = type.classLoader
            val bodyType = Class.forName("okhttp3.RequestBody", false, loader)
            val bufferType = Class.forName("okio.Buffer", false, loader)
            val sinkType = Class.forName("okio.BufferedSink", false, loader)
            val mediaType = Class.forName("okhttp3.MediaType", false, loader)
            val body = type.getMethod("body").invoke(request) ?: return null
            val buffer = bufferType.getConstructor().newInstance()
            bodyType.getMethod("writeTo", sinkType).invoke(body, buffer)
            val original = JSONObject(bufferType.getMethod("readUtf8").invoke(buffer) as String)
            require(original.getString("deviceId") == deviceId)
            val phone = original.getString("phoneNumber")
            require(phone.isNotBlank())
            val json = JSONObject().put("deviceId", deviceId).put("phoneNumber", phone).put("tokens", JSONArray())
            val contentType = bodyType.getMethod("contentType").invoke(body)
            val replacement = bodyType.getMethod("create", mediaType, String::class.java)
                .invoke(null, contentType, json.toString())
            builder.javaClass.getMethod("post", bodyType).invoke(builder, replacement)
            builder.javaClass.getMethod("removeHeader", String::class.java).invoke(builder, "Content-Length")
        }
        val setHeader = builder.javaClass.getMethod("header", String::class.java, String::class.java)
        for ((name, value) in headers(deviceId, System.currentTimeMillis() / 1000, TimeZone.getDefault().id)) {
            setHeader.invoke(builder, name, value)
        }
        return builder
    }
}
