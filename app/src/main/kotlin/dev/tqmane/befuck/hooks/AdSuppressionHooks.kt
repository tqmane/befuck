package dev.tqmane.befuck.hooks

import android.view.View
import android.view.ViewGroup
import dev.tqmane.befuck.runtime.RuntimeKnowledge
import dev.tqmane.befuck.symbols.KnownMappings
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/** Feed/Native Compose ad suppression and Android ViewGroup ad-view hiding. */
internal class AdSuppressionHooks(private val host: HookHost) {
    private val adViewClasses = ConcurrentHashMap<Class<*>, Boolean>()

    fun installAll(loader: ClassLoader) {
        installFeedAdSelectionSuppression(loader)
        installNativeAdCompositionSuppression(loader)
        installAdViewSuppression()
    }

    private fun installFeedAdSelectionSuppression(loader: ClassLoader) {
        val mappings = host.mappings ?: return
        try {
            val select = mappings.resolveFeedAdSelector(loader) ?: return
            host.hook(select)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { null }
            host.info("Suppressed Native/MREC feed ad selection at the verified host boundary")
        } catch (failure: Throwable) {
            host.error("Could not install version-mapped feed ad selection suppression", failure)
        }
    }

    private fun installNativeAdCompositionSuppression(loader: ClassLoader) {
        val mappings = host.mappings ?: return
        try {
            val render = mappings.resolveNativeAdComposable(loader) ?: return
            val modifier = Class.forName("androidx.compose.ui.Modifier", false, loader)
            val modifierArgument = render.parameterTypes.indexOf(modifier)
            if (modifierArgument < 0) throw NoSuchMethodException("Native ad Modifier argument")
            val hidden = host.createZeroSizeComposeModifier(loader)
            host.hook(render)
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val args = chain.args.toTypedArray()
                    args[modifierArgument] = hidden
                    host.logOnce(
                        "sponsoredFeedPostSuppression",
                        "Hid the native Compose ad card with clipped bounds while preserving its composition",
                    )
                    chain.proceed(args)
                }
            host.info("Hooked the version-verified native Compose ad renderer")
        } catch (failure: Throwable) {
            host.error("Could not suppress the native Compose ad renderer", failure)
        }
    }

    private fun installAdViewSuppression() {
        if (!KnownMappings.isSupportedVersion(RuntimeKnowledge.versionName, RuntimeKnowledge.versionCode)) return
        try {
            host.hook(View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType))
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    if (chain.getArg(0) as Int != View.GONE && isAdSdkView(chain.thisObject as View)) {
                        val args = chain.args.toTypedArray()
                        args[0] = View.GONE
                        return@intercept chain.proceed(args)
                    }
                    chain.proceed()
                }
            host.info("Hooked ad visibility changes to keep recycled and asynchronously loaded ads hidden")
        } catch (error: Throwable) {
            host.error("Could not install persistent ad visibility suppression", error)
        }

        try {
            var hooked = 0
            for (method in ViewGroup::class.java.declaredMethods) {
                val parameters = method.parameterTypes
                if (!(method.name == "addView" || method.name == "addViewInLayout") ||
                    parameters.isEmpty() || parameters[0] != View::class.java
                ) {
                    continue
                }
                host.hook(method)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val child = chain.getArg(0)
                        if (child is View && isAdSdkView(child)) {
                            child.visibility = View.GONE
                            host.logOnce(
                                "adViewSuppression",
                                "Suppressed an embedded ad view before it entered the Android view hierarchy",
                            )
                        }
                        chain.proceed()
                    }
                hooked++
            }
            if (hooked == 0) throw NoSuchMethodException("ViewGroup.addView(View, ...) methods unavailable")
            host.info("Hooked Android view insertion for BeReal and vendor ad-view suppression")
        } catch (error: Throwable) {
            host.error("Could not install ad-view suppression", error)
        }
    }

    private fun isAdSdkView(view: View): Boolean =
        adViewClasses.computeIfAbsent(view.javaClass) { type -> host.mappings?.isAdViewClass(type) == true }
}
