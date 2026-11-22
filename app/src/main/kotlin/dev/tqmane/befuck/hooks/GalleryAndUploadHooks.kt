package dev.tqmane.befuck.hooks

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.util.Log
import dev.tqmane.befuck.R
import dev.tqmane.befuck.download.RealMojiDownloadAction
import dev.tqmane.befuck.runtime.DailySync
import dev.tqmane.befuck.runtime.HostReflection
import dev.tqmane.befuck.runtime.RuntimeKnowledge
import dev.tqmane.befuck.symbols.HostClass
import dev.tqmane.befuck.symbols.KnownMappings
import dev.tqmane.befuck.symbols.ResolvedSymbols
import dev.tqmane.befuck.ui.BeFuckGalleryUi
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/** Gallery entry, MainActivity lifecycle/results, RealMoji download menu, camera pairing, and upload diagnostics. */
internal class GalleryAndUploadHooks(private val host: HookHost) {
    private val videoUploadDiagnosticsInstalled = AtomicBoolean()

    fun installDailyFeedSync(classLoader: ClassLoader, symbols: ResolvedSymbols?) {
        try {
            val diagnostics = Class.forName("androidx.work.impl.workers.DiagnosticsWorker", false, classLoader)
            val doWork = diagnostics.getDeclaredMethod("doWork")
            host.hook(doWork)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val worker = chain.thisObject
                    if (!DailySync.isMarkedWork(worker)) return@intercept chain.proceed()
                    host.applicationContext?.let { appContext -> DailySync.run(appContext, symbols, worker) }
                    DailySync.workResult(classLoader)
                }
            host.info("Installed opt-in background feed sync worker hook")
        } catch (failure: Throwable) {
            host.error("Could not install the background feed sync worker hook", failure)
        }
    }

    @SuppressLint("DiscouragedApi")
    fun installRealMojiDownloadMenu(loader: ClassLoader) {
        val mappings = host.mappings ?: return
        val resources = host.moduleResources ?: return
        val appContext = host.applicationContext ?: return
        if (!KnownMappings.isSupportedVersion(RuntimeKnowledge.versionName)) return
        try {
            val contract = mappings.realMojiMenuContract(loader)
            val downloadTag = "befuck.realmoji.download"
            val label = resources.getString(R.string.befuck_realmoji_download)
            val icon = appContext.resources.getIdentifier("ic_arrow_down_to_line", "drawable", host.targetPackage)
            // Handle our interface proxy before the native sealed-action branches (profile/delete/report).
            host.hook(contract.clickedMethod).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                val action = chain.getArg(0)
                if (action != null && Proxy.isProxyClass(action.javaClass)) {
                    val handler = Proxy.getInvocationHandler(action)
                    if (handler is RealMojiDownloadAction) {
                        handler.download(appContext, resources)
                        return@intercept contract.clickedResult
                    }
                }
                chain.proceed()
            }
            host.hook(contract.selectedConstructor).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                val args = chain.args.toTypedArray()
                try {
                    val existing = args[2] as List<*>
                    val present = existing.any { value -> downloadTag == contract.entryTagField.get(value) }
                    if (!present) {
                        val model = requireNotNull(contract.viewerModelField.get(args[0]))
                        val action = RealMojiDownloadAction.create(contract.actionClass, model)
                        val menuItem = mappings.realMojiMenuItem(loader, label, if (icon == 0) null else icon)
                        val items = ArrayList<Any?>(existing).apply {
                            add(contract.entryConstructor.newInstance(action, menuItem, downloadTag))
                        }
                        args[2] = contract.immutableListCopy.invoke(null, items)
                    }
                } catch (failure: Throwable) {
                    host.error("Could not append the selected RealMoji download entry", failure)
                }
                chain.proceed(args)
            }
            host.info("Installed selected RealMoji download entry for ${RuntimeKnowledge.versionName}")
        } catch (failure: Throwable) {
            host.error("Could not install the version-specific RealMoji download menu", failure)
        }
    }

    fun installBeFuckGalleryFeature(classLoader: ClassLoader, symbols: ResolvedSymbols?) {
        try {
            val mainActivity = Class.forName("bereal.app.MainActivity", false, classLoader)
            val onResume = mainActivity.getDeclaredMethod("onResume")
            host.hook(onResume)
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    val activity = chain.thisObject
                    if (activity is Activity) {
                        BeFuckGalleryUi.installEntryButton(activity, host.moduleResources, symbols)
                    }
                    result
                }
            host.info("Hooked BeReal MainActivity.onResume for the BeFake gallery entry; auth captured from BeReal API requests")

            val onPause = findActivityMethod(mainActivity, "Activity.onPause", "onPause")
            if (onPause != null) {
                host.hook(onPause)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val activity = chain.thisObject
                        val result = chain.proceed()
                        if (activity is Activity && activity.javaClass.name == "bereal.app.MainActivity") {
                            BeFuckGalleryUi.onActivityPause(activity)
                        }
                        result
                    }
                host.info("Hooked MainActivity.onPause to stop the home-only BeFake entry monitor")
            } else {
                host.info("MainActivity.onPause was not declared on this class hierarchy; route monitor uses the activity lifetime")
            }

            val onDestroy = findActivityMethod(mainActivity, "Activity.onDestroy", "onDestroy")
            if (onDestroy != null) {
                host.hook(onDestroy)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val activity = chain.thisObject
                        val result = chain.proceed()
                        if (activity is Activity && activity.javaClass.name == "bereal.app.MainActivity") {
                            BeFuckGalleryUi.onActivityDestroy(activity)
                        }
                        result
                    }
                host.info("Hooked MainActivity.onDestroy to close stale BeFuck dialogs")
            }

            val onActivityResult = findActivityMethod(
                mainActivity,
                "gallery result callback",
                "onActivityResult",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Intent::class.java,
            )
            if (onActivityResult != null) {
                host.hook(onActivityResult)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val result = chain.proceed()
                        val activity = chain.thisObject
                        val args = chain.args
                        if (activity is Activity && args.size == 3 && args[0] is Int && args[1] is Int) {
                            BeFuckGalleryUi.onActivityResult(
                                activity,
                                args[0] as Int,
                                args[1] as Int,
                                args[2] as? Intent,
                            )
                        }
                        result
                    }
                host.info("Hooked the app-specific Activity result method for selected gallery images")
            } else {
                host.info("Gallery picker result hook unavailable; BeFuck entry remains disabled for image selection")
            }

            val onPermissionsResult = findActivityMethod(
                mainActivity,
                "permission result callback",
                "onRequestPermissionsResult",
                Int::class.javaPrimitiveType!!,
                Array<String>::class.java,
                IntArray::class.java,
            )
            if (onPermissionsResult != null) {
                host.hook(onPermissionsResult)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val result = chain.proceed()
                        val activity = chain.thisObject
                        val args = chain.args
                        if (activity is Activity && args.size == 3 && args[0] is Int && args[2] is IntArray) {
                            BeFuckGalleryUi.onRequestPermissionsResult(
                                activity,
                                args[0] as Int,
                                args[2] as IntArray,
                            )
                        }
                        result
                    }
                host.info("Hooked the Activity permission result callback for the BeFake current-location map")
            } else {
                host.info("Activity permission result callback unavailable; the BeFake map can still use an existing location grant")
            }
        } catch (error: Throwable) {
            host.error("Could not install the BeFuck Home entry or gallery result hook", error)
        }

        host.info("BeFake direct signed-upload/API post configured")
        installVideoUploadFailureDiagnostics(classLoader)
    }

    fun installConcurrentVideoSecondaryFrontSelection(symbols: ResolvedSymbols?) {
        try {
            val cameraViewModel = symbols?.cameraViewModelClass
            val facing = symbols?.cameraFacingEnumClass
            val bindConcurrentCameras = symbols?.cameraBindConcurrentMethod
            if (cameraViewModel == null || facing == null || bindConcurrentCameras == null) {
                host.info("[SymbolResolver] Concurrent-camera preference hook disabled: required symbols unresolved")
                return
            }
            val rearPrimaryFacing = facing.enumConstants?.firstOrNull { value ->
                value is Enum<*> && value.name == "Back"
            } ?: throw NoSuchFieldException("${facing.name}.Back")

            host.hook(bindConcurrentCameras)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val requestedFacing = chain.getArg(1)
                    host.info("Concurrent camera bind requested=$requestedFacing; using rear primary and selfie secondary")
                    chain.proceed(arrayOf(chain.getArg(0), rearPrimaryFacing))
                }
            host.info("Hooked concurrent-camera bind to use the selfie camera second")
        } catch (error: Throwable) {
            host.error("Could not hook concurrent-camera selfie-camera ordering", error)
        }
    }

    private fun installVideoUploadFailureDiagnostics(classLoader: ClassLoader) {
        val mappings = host.mappings ?: return
        if (!videoUploadDiagnosticsInstalled.compareAndSet(false, true)) return
        try {
            for ((field, value) in mappings.uploadStringRepairs(classLoader)) {
                host.restoreStaticStringIfNull(field, "upload-string", value)
            }
            val uploadFailureSimpleName = mappings.className(HostClass.UploadFailure)
            var hooked = 0
            for (method in mappings.uploadWorkerDiagnosticMethods(classLoader)) {
                host.hook(method)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val stage = chain.getArg(0)
                        val failure = chain.getArg(1)
                        if (stage is String && failure is Throwable) {
                            val details = formatUploadFailure(failure, uploadFailureSimpleName)
                            host.info("Official upload worker failure stage=$stage details=$details")
                        }
                        chain.proceed()
                    }
                hooked++
            }
            val uploadStep = mappings.uploadWorkerStepMethod(classLoader)
            if (uploadStep != null) {
                host.hook(uploadStep)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val postId = chain.getArg(1)
                        val depth = chain.getArg(0)
                        val prefix = if (postId is String) postId.take(8) else "null"
                        host.info("Upload worker step entry: depth=$depth, postId=$prefix")
                        chain.proceed()
                    }
                hooked++
            }
            host.info("Installed official upload-worker diagnostics on $hooked method(s)")
        } catch (failure: Throwable) {
            host.error("Could not install official video-processing diagnostics", failure)
        }
    }

    private fun formatUploadFailure(throwable: Throwable, uploadFailureSimpleName: String): String {
        if (uploadFailureSimpleName == throwable.javaClass.simpleName) {
            return runCatching { throwable.toString() }.getOrElse { throwable.javaClass.name }
        }
        if (throwable is NullPointerException) {
            val frames = throwable.stackTrace
            return buildString {
                append(throwable.javaClass.name)
                for (index in 0 until minOf(frames.size, 12)) {
                    append(" <- ")
                        .append(frames[index].className)
                        .append('#')
                        .append(frames[index].methodName)
                        .append(':')
                        .append(frames[index].lineNumber)
                }
            }
        }
        return Log.getStackTraceString(throwable)
            .replace(Regex("https?://[^\\s)]+"), "<url>")
            .replace(Regex("(?i)Bearer [A-Za-z0-9._~-]+"), "Bearer <redacted>")
    }

    private fun findActivityMethod(
        activityClass: Class<*>,
        label: String,
        name: String,
        vararg paramTypes: Class<*>,
    ): Method? = try {
        HostReflection.findHierarchyMethod(activityClass, Activity::class.java, name, *paramTypes)
    } catch (error: Throwable) {
        host.error("Could not resolve $label on ${activityClass.name}", error)
        null
    }
}
