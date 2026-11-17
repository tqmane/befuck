package dev.tqmane.befuck.hooks

import dev.tqmane.befuck.posting.BeFakeAuthHeaders
import dev.tqmane.befuck.runtime.SmsAuthCompatibility
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Authentication diagnostics, SMS request compatibility, focus layout guard, and OkHttp auth capture. */
internal class AuthHooks(private val host: HookHost) {
    private val authHeaderCaptureInstalled = AtomicBoolean()
    private val authUrlCaptureInstalled = AtomicBoolean()
    private val pendingAuthHeadersByBuilder = Collections.synchronizedMap(WeakHashMap<Any, MutableMap<String, String>>())
    private val okHttpBuilderUrlFields = ConcurrentHashMap<Class<*>, Field>()
    private val okHttpUrlHostMethods = ConcurrentHashMap<Class<*>, Method>()

    fun installEmailAnalyticsGuard(loader: ClassLoader) {
        val mappings = host.mappings ?: return
        try {
            val guard = mappings.emailAnalyticsGuard(loader) ?: return
            for ((ctor, fields) in guard.constructors) {
                host.hook(ctor).intercept { chain ->
                    if (!guard.hasMissingStep(chain.getArg(0))) return@intercept chain.proceed()
                    // Keep a fully initialized event object for callers, but do not invent its
                    // missing analytics label. The emission hook below drops this event.
                    host.xposed.getInvoker(guard.baseConstructor).invokeSpecial(
                        chain.thisObject,
                        0,
                        emptyList<Any?>(),
                        guard.eventNames[ctor.declaringClass],
                    )
                    for (i in fields.indices) {
                        fields[i].set(chain.thisObject, chain.getArg(i))
                    }
                    null
                }
            }
            host.hook(guard.emit).intercept { chain ->
                if (!guard.shouldOmit(chain.getArg(0))) return@intercept chain.proceed()
                host.info("Omitted email onboarding analytics with a missing step label")
                null
            }
            host.info("Installed version-scoped email screen analytics guard")
        } catch (failure: Throwable) {
            host.error("Could not install email screen analytics guard", failure)
        }
    }

    fun installTextFieldFocusGuard(loader: ClassLoader) {
        val mappings = host.mappings ?: return
        try {
            val guard = mappings.textFieldFocusGuard(loader) ?: return
            host.hook(guard.notify).intercept { chain -> chain.proceed(guard.arguments(chain.args.toTypedArray())) }
            host.info("Installed version-scoped text-field focus layout guard")
        } catch (failure: Throwable) {
            host.error("Could not install text-field focus layout guard", failure)
        }
    }

    fun installSmsRequestPayloadRepair(loader: ClassLoader) {
        val mappings = host.mappings ?: return
        try {
            val constructor = mappings.smsRequestConstructor(loader) ?: return
            host.hook(constructor).intercept { chain ->
                val original = chain.args.toTypedArray()
                val args = mappings.smsRequestArguments(original)
                // Log only payload shape, never phone numbers, device IDs, or tokens.
                host.info(
                    "SMS request-code payload: tokenCount=${(args[3] as List<*>).size}; " +
                        "normalizedNullTokens=${original !== args}",
                )
                try {
                    host.info("SMS request-code shape: ${mappings.smsRequestShape(loader, args)}")
                } catch (failure: Throwable) {
                    // Diagnostic failures must not break authentication or expose request values.
                    host.info("SMS request-code shape unavailable: ${failure.javaClass.simpleName}")
                }
                chain.proceed(args)
            }
            host.info("Installed version-scoped SMS request-code payload repair")
        } catch (failure: Throwable) {
            host.error("Could not install SMS request-code payload repair", failure)
        }
    }

    fun installAuthFailureDiagnostics(loader: ClassLoader) {
        val mappings = host.mappings ?: return
        try {
            val constructors = mappings.authDiagnosticConstructors(loader)
            for ((kind, ctor) in constructors) {
                host.hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    // Never stringify token arguments, exception messages, or network payloads.
                    when (kind) {
                        "request_failure" -> {
                            val reason = chain.getArg(1).toString()
                            val recognized = reason in KNOWN_AUTH_FAILURE_REASONS
                            host.info("Auth request-code failure category=${if (recognized) reason else "Unclassified"}")
                        }
                        "challenge_token" -> {
                            val provider = chain.getArg(1)
                            val providerLabel = when (provider) {
                                "PR" -> "Prelude"
                                "RE" -> "Recaptcha"
                                else -> "Other"
                            }
                            val present = (chain.getArg(0) as? String)?.isNotEmpty() == true
                            host.info("Challenge token generated provider=$providerLabel; present=$present")
                        }
                        "recaptcha_initialization" -> {
                            host.info("Recaptcha initialization failure code=${chain.getArg(0) as Int}")
                        }
                    }
                    result
                }
            }
            if (constructors.isNotEmpty()) host.info("Installed version-scoped authentication diagnostics")
        } catch (failure: Throwable) {
            host.error("Could not install authentication failure diagnostics", failure)
        }
    }

    fun installBeFakeAuthHeaderCapture(classLoader: ClassLoader) {
        if (!authHeaderCaptureInstalled.compareAndSet(false, true)) return
        try {
            val builderClass = Class.forName("okhttp3.Request\$Builder", false, classLoader)
            var hooked = 0
            for (method in builderClass.declaredMethods) {
                val name = method.name
                val parameters = method.parameterTypes
                if (!(name == "addHeader" || name == "header") ||
                    parameters.size != 2 ||
                    parameters[0] != String::class.java ||
                    parameters[1] != String::class.java
                ) {
                    continue
                }
                host.hook(method)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val builder = chain.thisObject
                        val key = chain.getArg(0) as? String
                        val value = chain.getArg(1) as? String
                        if (key != null && value != null) {
                            val normalized = key.lowercase(Locale.ROOT)
                            if (normalized == "authorization" || normalized == "bereal-device-id") {
                                val targetHost = resolveOkHttpBuilderHost(builder)
                                if (targetHost == null) {
                                    synchronized(pendingAuthHeadersByBuilder) {
                                        val pending = pendingAuthHeadersByBuilder.getOrPut(builder) { LinkedHashMap() }
                                        pending[key] = value
                                    }
                                } else {
                                    captureBeRealHeader(targetHost, key, value)
                                }
                            }
                        }
                        chain.proceed()
                    }
                hooked++
            }
            for (method in builderClass.declaredMethods) {
                if (method.name == "build" && method.parameterCount == 0) {
                    host.hook(method)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept { chain ->
                            val request = chain.proceed() ?: return@intercept null
                            if (host.smsAuthCompatibility) {
                                try {
                                    val replacement = SmsAuthCompatibility.replacementBuilder(request)
                                    if (replacement != null) {
                                        val compatible = host.xposed.getInvoker(method)
                                            .setType(XposedInterface.Invoker.Type.ORIGIN)
                                            .invoke(replacement)
                                        captureFromBuiltOkHttpRequest(request)
                                        host.info("Applied SMS request payload compatibility repair")
                                        return@intercept compatible
                                    }
                                } catch (failure: Throwable) {
                                    host.info("SMS compatibility unavailable: ${failure.javaClass.simpleName}")
                                }
                            }
                            captureFromBuiltOkHttpRequest(request)
                            request
                        }
                    hooked++
                }
            }
            if (hooked == 0) throw NoSuchMethodException("okhttp3.Request.Builder header setters unavailable")
            installOkHttpBuilderUrlCapture(builderClass)
            host.info("Hooked OkHttp request header setters for BeFake; capture is restricted to bereal.com hosts")
        } catch (error: Throwable) {
            authHeaderCaptureInstalled.set(false)
            host.error("Could not install BeFake's in-memory BeReal auth-header capture", error)
        }
    }

    private fun captureFromBuiltOkHttpRequest(request: Any) {
        try {
            val httpUrl = request.javaClass.getMethod("url").invoke(request) ?: return
            val targetHost = httpUrl.javaClass.getMethod("host").invoke(httpUrl) as? String ?: return
            if (!targetHost.lowercase(Locale.ROOT).endsWith("bereal.com")) return

            val fullUrl = httpUrl.toString()
            // SMS uses a separate client identity; keep it out of gallery/upload headers.
            if (host.smsAuthCompatibility && SmsAuthCompatibility.matches(
                    request.javaClass.getMethod("method").invoke(request) as String,
                    fullUrl,
                )
            ) {
                return
            }
            if (fullUrl.contains("/api/")) {
                BeFakeAuthHeaders.setApiHost(targetHost)
            }

            val headersObj = request.javaClass.getMethod("headers").invoke(request) ?: return
            @Suppress("UNCHECKED_CAST")
            val names = headersObj.javaClass.getMethod("names").invoke(headersObj) as? Set<String> ?: return
            val getMethod = headersObj.javaClass.getMethod("get", String::class.java)
            for (name in names) {
                val value = getMethod.invoke(headersObj, name) as? String
                if (!value.isNullOrEmpty()) {
                    captureBeRealHeader(targetHost, name, value)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun installOkHttpBuilderUrlCapture(builderClass: Class<*>) {
        if (!authUrlCaptureInstalled.compareAndSet(false, true)) return
        var hooked = 0
        try {
            for (method in builderClass.declaredMethods) {
                if (method.name != "url" || method.parameterCount != 1 || !builderClass.isAssignableFrom(method.returnType)) {
                    continue
                }
                host.hook(method)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val result = chain.proceed()
                        val builder = chain.thisObject
                        val targetHost = resolveOkHttpBuilderHost(builder)
                        if (targetHost != null) capturePendingBeRealHeaders(builder, targetHost)
                        result
                    }
                hooked++
            }
            if (hooked == 0) throw NoSuchMethodException("okhttp3.Request.Builder.url setters unavailable")
        } catch (failure: Throwable) {
            authUrlCaptureInstalled.set(false)
            throw failure
        }
    }

    private fun capturePendingBeRealHeaders(builder: Any, targetHost: String) {
        val pending = synchronized(pendingAuthHeadersByBuilder) {
            pendingAuthHeadersByBuilder.remove(builder)
        } ?: return
        for ((key, value) in pending) {
            captureBeRealHeader(targetHost, key, value)
        }
    }

    private fun captureBeRealHeader(targetHost: String, key: String, value: String) {
        BeFakeAuthHeaders.capture(targetHost, key, value)
        val normalized = key.lowercase(Locale.ROOT)
        if ((normalized == "authorization" || normalized == "bereal-device-id") &&
            targetHost.lowercase(Locale.ROOT).endsWith("bereal.com")
        ) {
            host.logOnce("authHeaderCapture", "Captured current BeReal API auth headers in memory (values redacted)")
        }
    }

    private fun resolveOkHttpBuilderHost(builder: Any?): String? {
        if (builder == null) return null
        val builderClass = builder.javaClass
        var urlField = okHttpBuilderUrlFields[builderClass]
        if (urlField == null) {
            var current: Class<*>? = builderClass
            while (current != null && urlField == null) {
                for (field in current.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                    if (findOkHttpUrlHostMethod(field.getType()) != null) {
                        field.isAccessible = true
                        okHttpBuilderUrlFields[builderClass] = field
                        urlField = field
                        break
                    }
                }
                current = current.superclass
            }
        }
        urlField ?: return null
        return try {
            urlField.isAccessible = true
            val url = urlField.get(builder) ?: return null
            val hostMethod = findOkHttpUrlHostMethod(url.javaClass) ?: return null
            hostMethod.isAccessible = true
            val resolved = hostMethod.invoke(url) as? String
            if (resolved != null && resolved.contains(".")) resolved else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun findOkHttpUrlHostMethod(urlClass: Class<*>): Method? {
        okHttpUrlHostMethods[urlClass]?.let { return it }
        for (method in urlClass.methods) {
            if (method.name == "host" && method.parameterCount == 0 && method.returnType == String::class.java) {
                okHttpUrlHostMethods[urlClass] = method
                return method
            }
        }
        return null
    }

    private companion object {
        val KNOWN_AUTH_FAILURE_REASONS = setOf(
            "AntibotChallengeFailure", "PhoneNumberBlocked", "CountryBlocked",
            "SessionExpired", "UserSuspended", "CountryCodeInvalid", "CountryNotAllowedAcrossAllOTP",
            "SmsServiceDown", "InvalidPayload", "PhoneNumberBanned", "RateLimitReached",
            "ConcurrentVerification", "SpamDetected", "UnderageBlocked",
        )
    }
}
