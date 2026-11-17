package dev.tqmane.befuck.hooks

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import dev.tqmane.befuck.symbols.ResolvedSymbols
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/** FusedLocation API observation and Android LocationManager fallback when GMS returns unavailable/null. */
internal class LocationFallbackHooks(private val host: HookHost) {
    @Volatile private var fusedLocationApiHooksInstalled = false

    private data class TaskOutcome(
        val successful: Boolean,
        val canceled: Boolean,
        val result: Any?,
        val failure: Any?,
    )

    fun install(symbols: ResolvedSymbols?) {
        try {
            val getFusedClient = symbols?.locationClientGetter
            if (getFusedClient == null) {
                host.info("[SymbolResolver] FusedLocation fallback hook disabled: client getter unresolved")
                return
            }
            host.hook(getFusedClient)
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val client = chain.proceed()
                    if (client != null) {
                        host.info("BeReal resolved its FusedLocation client: ${client.javaClass.name}")
                        installFusedLocationApiHooks(client)
                    } else {
                        host.info("BeReal resolved a null FusedLocation client")
                    }
                    client
                }
            host.info("Hooked BeReal FusedLocation client resolution")
        } catch (error: Throwable) {
            host.error("Could not hook BeReal FusedLocation client resolution", error)
        }
    }

    @Synchronized
    private fun installFusedLocationApiHooks(client: Any) {
        if (fusedLocationApiHooksInstalled) return
        var anyHooked = false
        for (method in client.javaClass.methods) {
            val name = method.name
            if (name != "getCurrentLocation" && name != "getLastLocation" && name != "getLocationAvailability") {
                continue
            }
            try {
                host.hook(method)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        host.info("FusedLocation API entered: $name")
                        try {
                            val result = chain.proceed()
                            host.info("FusedLocation API returned: $name -> ${result?.javaClass?.name ?: "null"}")
                            watchFusedLocationTask(result, name)
                            if (name == "getCurrentLocation" || name == "getLastLocation") {
                                bridgeFusedLocationTask(result, name)
                            } else {
                                result
                            }
                        } catch (failure: Throwable) {
                            host.error("FusedLocation API threw: $name -> ${failure.javaClass.name}", failure)
                            throw failure
                        }
                    }
                anyHooked = true
                host.info("Hooked FusedLocation API method ${method.declaringClass.name}.$name")
            } catch (error: Throwable) {
                host.error("Could not hook FusedLocation API method ${method.declaringClass.name}.$name", error)
            }
        }
        fusedLocationApiHooksInstalled = anyHooked
        if (!anyHooked) {
            host.info("Resolved FusedLocation client exposes no hookable request methods")
        }
    }

    private fun watchFusedLocationTask(task: Any?, apiName: String) {
        if (task == null) {
            host.info("FusedLocation task is null: $apiName")
            return
        }
        try {
            val taskLoader = task.javaClass.classLoader
            val taskApi = Class.forName("com.google.android.gms.tasks.Task", false, taskLoader)
            val listenerApi = Class.forName("com.google.android.gms.tasks.OnCompleteListener", false, taskLoader)
            attachTaskCompleteListener(task, taskApi, listenerApi, "BeRealLocationTaskObserver") { completedTask ->
                try {
                    val outcome = inspectCompletedTask(taskApi, completedTask)
                    val resultType = outcome.result?.javaClass?.name ?: "null"
                    var failureType = outcome.failure?.javaClass?.name ?: "null"
                    googleApiStatusCode(outcome.failure)?.let { code -> failureType += "(statusCode=$code)" }
                    var availability = ""
                    if (outcome.result != null && outcome.result.javaClass.name.endsWith("LocationAvailability")) {
                        availability = try {
                            ", available=${outcome.result.javaClass.getMethod("isLocationAvailable").invoke(outcome.result)}"
                        } catch (_: Throwable) {
                            ", available=unreadable"
                        }
                    }
                    host.info(
                        "FusedLocation task completed: $apiName, successful=${outcome.successful}, " +
                            "canceled=${outcome.canceled}, resultType=$resultType, failureType=$failureType$availability",
                    )
                } catch (inspectionError: Throwable) {
                    host.error("Could not inspect FusedLocation task completion for $apiName", inspectionError)
                }
            }
            host.info("Attached FusedLocation task observer: $apiName")
        } catch (error: Throwable) {
            host.error("Could not attach FusedLocation task observer for $apiName", error)
        }
    }

    private fun bridgeFusedLocationTask(originalTask: Any?, apiName: String): Any? {
        if (originalTask == null) return null
        return try {
            val taskLoader = originalTask.javaClass.classLoader
            val taskApi = Class.forName("com.google.android.gms.tasks.Task", false, taskLoader)
            val listenerApi = Class.forName("com.google.android.gms.tasks.OnCompleteListener", false, taskLoader)
            val taskSourceApi = Class.forName("com.google.android.gms.tasks.TaskCompletionSource", true, taskLoader)
            val taskSource = taskSourceApi.getDeclaredConstructor().newInstance()
            val bridgedTask = taskSourceApi.getMethod("getTask").invoke(taskSource)
            val completed = AtomicBoolean(false)

            attachTaskCompleteListener(originalTask, taskApi, listenerApi, "BeRealLocationFallbackBridge") { finishedTask ->
                try {
                    val outcome = inspectCompletedTask(taskApi, finishedTask)
                    if (outcome.successful && outcome.result != null) {
                        completeFallbackTask(taskSource, taskSourceApi, completed, outcome.result as Location, null, apiName)
                        return@attachTaskCompleteListener
                    }
                    if (outcome.canceled) {
                        completeFallbackTask(
                            taskSource,
                            taskSourceApi,
                            completed,
                            null,
                            CancellationException("FusedLocation request canceled"),
                            apiName,
                        )
                        return@attachTaskCompleteListener
                    }
                    val statusCode = googleApiStatusCode(outcome.failure)
                    if (statusCode == 17 || (outcome.successful && outcome.result == null)) {
                        host.info("FusedLocation $apiName unavailable; trying Android LocationManager")
                        requestAndroidLocationFallback(taskSource, taskSourceApi, completed, apiName)
                        return@attachTaskCompleteListener
                    }
                    val failure = (outcome.failure as? Throwable) ?: IllegalStateException("FusedLocation failed")
                    completeFallbackTask(taskSource, taskSourceApi, completed, null, failure, apiName)
                } catch (inspectionError: Throwable) {
                    host.error("Could not bridge FusedLocation task $apiName", inspectionError)
                    completeFallbackTask(taskSource, taskSourceApi, completed, null, inspectionError, apiName)
                }
            }
            host.info("Bridging FusedLocation task with native fallback: $apiName")
            bridgedTask
        } catch (error: Throwable) {
            host.error("Could not bridge FusedLocation task $apiName", error)
            originalTask
        }
    }

    private fun attachTaskCompleteListener(
        task: Any,
        taskApi: Class<*>,
        listenerApi: Class<*>,
        label: String,
        onComplete: (Any) -> Unit,
    ) {
        val listener = Proxy.newProxyInstance(listenerApi.classLoader, arrayOf(listenerApi)) { proxy, method, args ->
            when (method.name) {
                "onComplete" -> {
                    if (args != null && args.size == 1 && args[0] != null) onComplete(args[0])
                    null
                }
                "toString" -> label
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args != null && args.size == 1 && proxy === args[0]
                else -> null
            }
        }
        taskApi.getMethod("addOnCompleteListener", listenerApi).invoke(task, listener)
    }

    private fun inspectCompletedTask(taskApi: Class<*>, completedTask: Any): TaskOutcome {
        val successful = taskApi.getMethod("isSuccessful").invoke(completedTask) as Boolean
        val canceled = taskApi.getMethod("isCanceled").invoke(completedTask) as Boolean
        val failure = taskApi.getMethod("getException").invoke(completedTask)
        val result = if (successful) taskApi.getMethod("getResult").invoke(completedTask) else null
        return TaskOutcome(successful, canceled, result, failure)
    }

    private fun googleApiStatusCode(failure: Any?): Int? {
        if (failure == null) return null
        return try {
            val statusField = failure.javaClass.getDeclaredField("a").apply { isAccessible = true }
            val status = statusField.get(failure) ?: return null
            val codeField = status.javaClass.getDeclaredField("a").apply { isAccessible = true }
            (codeField.get(status) as? Number)?.toInt()
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestAndroidLocationFallback(
        taskSource: Any,
        taskSourceApi: Class<*>,
        completed: AtomicBoolean,
        apiName: String,
    ) {
        try {
            val context = resolveFallbackContext() ?: run {
                completeFallbackTask(taskSource, taskSourceApi, completed, null, IllegalStateException("Application context unavailable"), apiName)
                return
            }
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run {
                completeFallbackTask(taskSource, taskSourceApi, completed, null, IllegalStateException("LocationManager unavailable"), apiName)
                return
            }
            if (apiName == "getLastLocation") {
                completeFallbackTask(taskSource, taskSourceApi, completed, findLastKnownLocation(manager), null, apiName)
                return
            }

            val enabledProviders = manager.getProviders(true)
            val providers = ArrayList<String>()
            for (preferred in arrayOf("network", "gps", "fused")) {
                if (preferred in enabledProviders && preferred !in providers) providers.add(preferred)
            }
            for (provider in enabledProviders) {
                if (provider != "passive" && provider !in providers) providers.add(provider)
            }
            if (providers.isEmpty()) {
                completeFallbackTask(taskSource, taskSourceApi, completed, findLastKnownLocation(manager), null, apiName)
                host.info("Android LocationManager fallback found no enabled providers")
                return
            }

            val getCurrentLocation = LocationManager::class.java.getMethod(
                "getCurrentLocation",
                String::class.java,
                CancellationSignal::class.java,
                Executor::class.java,
                Consumer::class.java,
            )
            val pendingProviders = AtomicInteger(providers.size)
            val timeoutHandler = Handler(Looper.getMainLooper())
            val timeout = Runnable {
                if (!completed.get()) {
                    completeFallbackTask(taskSource, taskSourceApi, completed, findLastKnownLocation(manager), null, apiName)
                }
            }
            timeoutHandler.postDelayed(timeout, 20_000L)

            val directExecutor = Executor { runnable -> runnable.run() }
            val locationConsumer = Consumer<Location?> { location ->
                if (location != null) {
                    timeoutHandler.removeCallbacks(timeout)
                    completeFallbackTask(taskSource, taskSourceApi, completed, location, null, apiName)
                } else if (pendingProviders.decrementAndGet() <= 0) {
                    timeoutHandler.removeCallbacks(timeout)
                    completeFallbackTask(taskSource, taskSourceApi, completed, findLastKnownLocation(manager), null, apiName)
                }
            }

            host.info("Android LocationManager fallback requesting current fix from ${providers.size} enabled provider(s)")
            for (provider in providers) {
                try {
                    getCurrentLocation.invoke(manager, provider, null, directExecutor, locationConsumer)
                } catch (_: Throwable) {
                    if (pendingProviders.decrementAndGet() <= 0) {
                        timeoutHandler.removeCallbacks(timeout)
                        completeFallbackTask(taskSource, taskSourceApi, completed, findLastKnownLocation(manager), null, apiName)
                    }
                }
            }
        } catch (error: Throwable) {
            host.error("Android LocationManager fallback failed for $apiName: ${error.javaClass.name}", error)
            completeFallbackTask(taskSource, taskSourceApi, completed, null, error, apiName)
        }
    }

    private fun resolveFallbackContext(): Context? {
        host.applicationContext?.let { return it }
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThread.getDeclaredMethod("currentApplication").apply { isAccessible = true }
            currentApplication.invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun findLastKnownLocation(manager: LocationManager): Location? {
        for (provider in arrayOf("network", "gps", "passive", "fused")) {
            try {
                val location = manager.getLastKnownLocation(provider)
                if (location != null) return location
            } catch (_: SecurityException) {
                // Permission may be revoked while the asynchronous fallback is running.
                return null
            } catch (_: Throwable) {
                // Continue through providers without logging location data.
            }
        }
        return null
    }

    private fun completeFallbackTask(
        taskSource: Any,
        taskSourceApi: Class<*>,
        completed: AtomicBoolean,
        location: Location?,
        failure: Throwable?,
        apiName: String,
    ) {
        if (!completed.compareAndSet(false, true)) return
        try {
            if (failure != null) {
                val exception = (failure as? Exception) ?: Exception(failure)
                taskSourceApi.getMethod("setException", Exception::class.java).invoke(taskSource, exception)
                host.error("Location fallback completed with ${failure.javaClass.name}", failure)
                return
            }
            taskSourceApi.getMethod("setResult", Any::class.java).invoke(taskSource, location)
            host.info("Location fallback completed: $apiName; resultType=${location?.javaClass?.name ?: "null"}")
        } catch (completionError: Throwable) {
            host.error("Could not complete native Location fallback task", completionError)
        }
    }
}
