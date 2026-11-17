package dev.tqmane.befuck.hooks

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Bundle
import android.util.Base64
import dalvik.system.InMemoryDexClassLoader
import dev.tqmane.befuck.runtime.PreludeNativeLibrary
import dev.tqmane.befuck.symbols.HostMappings
import dev.tqmane.befuck.symbols.KnownMappings
import dev.tqmane.befuck.symbols.PostMapSymbolResolver
import dev.tqmane.befuck.ui.NpatchPostMap
import io.github.libxposed.api.XposedInterface
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Repackaged host (NPatch/LSPatch), Prelude native, and PairIP VM/lifecycle DEX compatibility hooks. */
internal class RepackagedCompatHooks(private val host: HookHost) {
    private var generatedDexParent: ClassLoader? = null
    private var generatedDexClassLoader: ClassLoader? = null

    fun installPostMap(context: Context, loader: ClassLoader, versionName: String?, versionCode: Long) {
        try {
            val resources = host.moduleResources ?: return
            val installed = context.packageManager.getApplicationInfo(host.targetPackage, PackageManager.GET_META_DATA)
            val metaData = installed.metaData
            if (metaData == null || !metaData.containsKey("npatch")) return
            val symbols = PostMapSymbolResolver.resolve(
                context,
                loader,
                host.moduleApplicationInfo,
                versionName,
                versionCode,
            ) { message -> host.info(message) }
            if (symbols == null) {
                host.info("NPatch post map replacement unavailable for this host symbol shape")
                return
            }
            host.hook(symbols.render).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    if (symbols.inHostSheet) {
                        val display = chain.getArg(1)
                        if (display != null && symbols.matches(display)) {
                            NpatchPostMap.composeSheet(symbols, display, chain.args.toTypedArray(), resources)
                        } else {
                            chain.proceed()
                        }
                    } else {
                        if (symbols.matches(chain.thisObject)) {
                            NpatchPostMap.compose(symbols, chain.thisObject, chain.getArg(symbols.composerArgument), resources)
                        } else {
                            chain.proceed()
                        }
                    }
                }
            host.info("Installed NPatch post map using OpenStreetMap/OpenFreeMap; Google API identity unchanged")
        } catch (failure: Throwable) {
            host.error("Could not install NPatch post map replacement", failure)
        }
    }

    fun installStartupCompatibility(context: Context, loader: ClassLoader) {
        // Repackaging changes the signing certificate and Play install provenance. Limit this
        // to known embedded patch loaders; retain the host's remote authentication flow.
        val mappings = host.mappings ?: return
        try {
            val installed = context.packageManager.getApplicationInfo(host.targetPackage, PackageManager.GET_META_DATA)
            val metaData = installed.metaData ?: return
            if (!metaData.containsKey("npatch") && !metaData.containsKey("lspatch")) return

            // 3.97.0 has a different protected VMRunner/startup sequence. Keep its
            // existing recovery path unmodified; it does not apply to 3.97.1.
            if (mappings.requiresRuntimeRecovery) {
                installSigningInfoCompatibility(context, mappings, metaData)
                for (check in mappings.repackagedStartupChecks(loader)) {
                    host.hook(check).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                        val arg = chain.getArg(0)
                        if (arg is Context && host.targetPackage == arg.packageName) {
                            host.info("Skipped repackaged application startup check: ${check.name}")
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                    host.info("Installed repackaged application compatibility: ${check.name}")
                }
            }

            // NPatch changes install provenance, so only its 3.97.1 host skips the
            // PairIP startup check; ordinary Play installs retain the original flow.
            val licenseCheck = if (metaData.containsKey("npatch")) mappings.repackagedLicenseCheck(loader) else null
            if (licenseCheck != null) {
                host.hook(licenseCheck)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val target = chain.getArg(0)
                        if (target is Context && host.targetPackage == target.packageName) {
                            host.logOnce("npatchLicenseCompatibility", "Skipped PairIP license startup for NPatch host")
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                host.info("Installed NPatch startup compatibility for BeReal 3.97.1")
            }
        } catch (failure: Throwable) {
            host.error("Could not install repackaged application startup compatibility", failure)
        }
    }

    fun installPreludeNativeCompatibility(context: Context, loader: ClassLoader) {
        // The protected host library traps in ffi_prelude_uniffi_contract_version
        // after VMRunner is replaced, including on unmodified APKs using root Xposed.
        // This must not depend on repackaging metadata or certificate-hook success.
        val mappings = host.mappings ?: return
        val resources = host.moduleResources ?: return
        try {
            val load = mappings.preludeLibraryLoad(loader) ?: return
            val interfaces = mappings.preludeLibraryInterfaces(loader)
            host.hook(load).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                if (!interfaces.contains(chain.getArg(1))) return@intercept chain.proceed()
                val args = chain.args.toTypedArray()
                args[0] = PreludeNativeLibrary.extract(context, resources)
                val result = chain.proceed(args)
                host.info("Loaded upstream Prelude 0.4.1 native implementation with host ABI checks retained")
                result
            }
            host.info("Installed version-scoped Prelude native compatibility for root and embedded loaders")
        } catch (failure: Throwable) {
            host.error("Could not install Prelude native compatibility", failure)
        }
    }

    fun installVmRunnerInitializerHook(classLoader: ClassLoader) {
        try {
            val vmRunner = Class.forName("com.pairip.VMRunner", false, classLoader)
            host.hookClassInitializer(vmRunner)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept {
                    host.info("Skipped VMRunner.<clinit>; libpairipcore is not loaded")
                    null
                }
            host.info("Hooked VMRunner class initializer")
        } catch (_: ClassNotFoundException) {
            host.info("Host has no PairIP VMRunner; using its original initialization")
        } catch (error: Throwable) {
            host.error("Could not hook VMRunner class initializer", error)
        }
    }

    fun installStartupLauncherHook(classLoader: ClassLoader) {
        try {
            val startupLauncher = Class.forName("com.pairip.StartupLauncher", false, classLoader)
            val launch = startupLauncher.getDeclaredMethod("launch")
            host.hook(launch)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept {
                    restoreLifecycleDispatch(classLoader)
                    host.info("Skipped StartupLauncher.launch() PairIP VM entry")
                    null
                }
            host.info("Hooked StartupLauncher.launch()")
        } catch (_: ClassNotFoundException) {
            host.info("Host has no PairIP launcher; using its original lifecycle")
        } catch (error: Throwable) {
            host.error("Could not hook StartupLauncher.launch()", error)
        }
    }

    private fun verifiedOriginalRepackagedSignature(mappings: HostMappings, metadata: Bundle): Signature {
        val encoded = metadata.getString("npatch") ?: metadata.getString("lspatch")
            ?: error("Missing patch signature metadata")
        val config = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8))
        @Suppress("DEPRECATION")
        val original = Signature(config.getString("originalSignature"))
        val digest = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(original.toByteArray()), Base64.NO_WRAP)
        check(digest == mappings.signingCertificateSha256) {
            "Patch metadata certificate does not match the supported original APK"
        }
        return original
    }

    @Suppress("DEPRECATION")
    private fun installSigningInfoCompatibility(context: Context, mappings: HostMappings, metadata: Bundle) {
        val original = verifiedOriginalRepackagedSignature(mappings, metadata)
        // Some API 102 NPatch runtimes do not apply their legacy PackageManager hooks.
        // Restore the configured certificate through the modern API, for this host only.
        var count = 0
        var type: Class<*>? = context.packageManager.javaClass
        while (type != null) {
            for (method in type.declaredMethods) {
                if (method.returnType != PackageInfo::class.java || Modifier.isAbstract(method.modifiers) ||
                    !(method.name == "getPackageInfo" || method.name == "getPackageInfoAsUser")
                ) {
                    continue
                }
                host.hook(method).intercept { chain ->
                    val result = chain.proceed() as? PackageInfo ?: return@intercept null
                    if (host.targetPackage != result.packageName) return@intercept result
                    val sigs = result.signatures
                    if (sigs != null && sigs.size == 1) {
                        sigs[0] = Signature(original.toByteArray())
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val signingInfo = result.signingInfo
                        if (signingInfo != null && !signingInfo.hasMultipleSigners()) {
                            val current = signingInfo.apkContentsSigners
                            val history = signingInfo.signingCertificateHistory
                            if (current != null && current.size == 1) current[0] = Signature(original.toByteArray())
                            if (history != null && history.size == 1) history[0] = Signature(original.toByteArray())
                        }
                    }
                    result
                }
                count++
            }
            type = type.superclass
        }
        val legacy = context.packageManager.getPackageInfo(host.targetPackage, PackageManager.GET_SIGNATURES)
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(host.targetPackage, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            legacy
        }
        val legacyMatches = legacy.signatures?.let { it.size == 1 && original == it[0] } == true
        val modernSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) modern.signingInfo?.apkContentsSigners else modern.signatures
        val modernMatches = modernSigners?.let { it.size == 1 && original == it[0] } == true
        host.info("Repackaged signing compatibility: methods=$count; legacy=$legacyMatches; modern=$modernMatches")
    }

    private fun restoreLifecycleDispatch(loader: ClassLoader) {
        val mappings = host.mappings ?: KnownMappings.protectedRuntime(loader) ?: return
        val asset = mappings.lifecycleDexAsset ?: return
        try {
            val generated = getGeneratedDexClassLoader(loader, asset)
            for (binding in mappings.lifecycleBindings) {
                try {
                    binding.install(loader, generated)
                } catch (failure: Throwable) {
                    host.error("Could not restore a host lifecycle binding", failure)
                }
            }
        } catch (failure: Throwable) {
            host.error("Could not load the host lifecycle DEX", failure)
        }
        for ((field, value) in mappings.bootstrapStringRepairs(loader)) {
            host.restoreStaticStringIfNull(field, "runtime-string", value)
        }
    }

    @Synchronized
    private fun getGeneratedDexClassLoader(parent: ClassLoader, assetEntry: String): ClassLoader {
        generatedDexClassLoader?.let { cached ->
            if (generatedDexParent === parent) return cached
        }

        val moduleApk = host.moduleApplicationInfo.sourceDir
        if (moduleApk.isNullOrEmpty()) throw IOException("Vector did not provide the module APK path")

        val dexBytes = ZipFile(moduleApk).use { zipFile ->
            val entry = zipFile.getEntry(assetEntry) ?: throw IOException("Missing $assetEntry in $moduleApk")
            if (entry.size <= 0 || entry.size > 4 * 1024 * 1024) {
                throw IOException("Unexpected PairIP DEX size: ${entry.size}")
            }
            zipFile.getInputStream(entry).use { input ->
                val output = ByteArrayOutputStream(entry.size.toInt())
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }

        val created = InMemoryDexClassLoader(ByteBuffer.wrap(dexBytes), parent)
        generatedDexClassLoader = created
        generatedDexParent = parent
        host.info("Loaded captured PairIP DEX (${dexBytes.size} bytes) into the target class loader")
        return created
    }
}
