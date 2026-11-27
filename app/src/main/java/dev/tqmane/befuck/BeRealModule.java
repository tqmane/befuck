package dev.tqmane.befuck;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.tqmane.befuck.hooks.AdSuppressionHooks;
import dev.tqmane.befuck.hooks.AuthHooks;
import dev.tqmane.befuck.hooks.FeedMediaHooks;
import dev.tqmane.befuck.hooks.GalleryAndUploadHooks;
import dev.tqmane.befuck.hooks.HookHost;
import dev.tqmane.befuck.hooks.LocationFallbackHooks;
import dev.tqmane.befuck.hooks.RepackagedCompatHooks;
import dev.tqmane.befuck.hooks.RuntimeRecoveryHooks;
import dev.tqmane.befuck.runtime.RuntimeKnowledge;
import dev.tqmane.befuck.symbols.BeRealSymbolResolver;
import dev.tqmane.befuck.symbols.HostMappings;
import dev.tqmane.befuck.symbols.KnownMappings;
import dev.tqmane.befuck.symbols.ResolvedSymbols;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** libxposed module entry point and high-level hook orchestrator for BeReal. */
public final class BeRealModule extends XposedModule {
    private static final String TARGET_PACKAGE = "com.bereal.ft";

    private final AtomicBoolean runtimeInitializationStarted = new AtomicBoolean();
    private final HookHost host = new HookHost(this, TARGET_PACKAGE);
    private final RepackagedCompatHooks repackaged = new RepackagedCompatHooks(host);
    private final RuntimeRecoveryHooks runtimeRecovery = new RuntimeRecoveryHooks(host);
    private final AuthHooks auth = new AuthHooks(host);
    private final FeedMediaHooks feedMedia = new FeedMediaHooks(host);
    private final LocationFallbackHooks locationFallback = new LocationFallbackHooks(host);
    private final AdSuppressionHooks adSuppression = new AdSuppressionHooks(host);
    private final GalleryAndUploadHooks galleryAndUpload = new GalleryAndUploadHooks(host);

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        final ClassLoader classLoader = param.getDefaultClassLoader();
        host.setTargetClassLoader(classLoader);
        host.setTargetApplicationInfo(param.getApplicationInfo());
        host.info("Loaded for " + param.getPackageName() + "; preparing version-scoped BeFuck hooks");

        installApplicationContextHook(classLoader);
        runtimeRecovery.installNullTraceSectionGuard();
        repackaged.installVmRunnerInitializerHook(classLoader);
        repackaged.installStartupLauncherHook(classLoader);
    }

    private void installApplicationContextHook(ClassLoader classLoader) {
        try {
            Class<?> applicationClass = Class.forName("android.app.Application", false, null);
            Method attach = applicationClass.getDeclaredMethod("attach", Context.class);
            hook(attach)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object context = chain.getArg(0);
                        if (context instanceof Context
                                && TARGET_PACKAGE.equals(((Context) context).getPackageName())) {
                            host.setApplicationContext((Context) context);
                            initializeRuntimeHooks((Context) context, classLoader);
                        }
                        return chain.proceed();
                    });
            host.info("Hooked Application.attach for the BeReal process context");
        } catch (Throwable error) {
            host.error("Could not hook Application.attach for the BeReal process context", error);
        }
    }

    private void initializeRuntimeHooks(Context context, ClassLoader classLoader) {
        if (!runtimeInitializationStarted.compareAndSet(false, true)) {
            return;
        }

        String versionName = null;
        long versionCode = -1L;
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            versionName = packageInfo.versionName;
            versionCode = packageInfo.getLongVersionCode();
        } catch (Throwable error) {
            host.error("Could not read BeReal version before symbol resolution", error);
        }

        HostMappings mappings = KnownMappings.find(versionName, versionCode);
        host.setMappings(mappings);
        Resources moduleResources = loadModuleResources(context);
        host.setModuleResources(moduleResources);

        ApplicationInfo targetInfo = host.getTargetApplicationInfo();
        ApplicationInfo appInfo = targetInfo != null ? targetInfo : context.getApplicationInfo();
        try {
            host.setSmsAuthCompatibility(mappings != null && mappings.getSupportsSmsAuth());
            if (mappings != null) {
                repackaged.installPreludeNativeCompatibility(context, classLoader);
                repackaged.installStartupCompatibility(context, classLoader);
            }
            RuntimeKnowledge.initialize(context, classLoader, versionName, versionCode, getModuleApplicationInfo());
            if (mappings != null) {
                runtimeRecovery.installComposeTracingContextGuard(classLoader);
                auth.installAuthFailureDiagnostics(classLoader);
                auth.installSmsRequestPayloadRepair(classLoader);
                auth.installTextFieldFocusGuard(classLoader);
                auth.installEmailAnalyticsGuard(classLoader);
            }
            RuntimeKnowledge.setPresetAssets(moduleResources == null ? null : moduleResources.getAssets());
            if (mappings != null && mappings.getRequiresRuntimeRecovery()) {
                runtimeRecovery.installRuntimeRecoveryGuards(classLoader);
            }
            host.setResolvedSymbols(BeRealSymbolResolver.resolve(
                    appInfo, classLoader, versionName, versionCode, host::info));
        } catch (Throwable error) {
            host.error("BeFuck SymbolResolver failed; affected hooks will use explicit known-version fallback only", error);
        }

        repackaged.installPostMap(context, classLoader, versionName, versionCode);
        installRuntimeHooks(classLoader, mappings, host.getResolvedSymbols());
        if (mappings == null) {
            host.info("No version mapping; DexKit-capable hooks and the BeFuck entry remain available: "
                    + versionName + " (" + versionCode + ")");
        }
    }

    private void installRuntimeHooks(ClassLoader classLoader, HostMappings mappings, ResolvedSymbols symbols) {
        if (mappings != null) {
            for (Map.Entry<Field, String> repair : mappings.runtimeStringRepairs(classLoader).entrySet()) {
                Field field = repair.getKey();
                host.restoreStaticStringIfNull(field, field.getDeclaringClass().getName() + "." + field.getName(), repair.getValue());
            }
            if (mappings.getRequiresRuntimeRecovery()) {
                runtimeRecovery.installAnalyticsNullKeyGuard(classLoader);
                runtimeRecovery.installProtobufNullFieldProbe(classLoader);
                runtimeRecovery.installRoomNullColumnGuard(classLoader);
                runtimeRecovery.installCameraOriginSentinelGuard(symbols);
                runtimeRecovery.installMedia3NetworkTypeReceiverHook(classLoader);
            }
        }
        locationFallback.install(symbols);
        galleryAndUpload.installConcurrentVideoSecondaryFrontSelection(symbols);
        auth.installBeFakeAuthHeaderCapture(classLoader);
        feedMedia.installComposeInjectionScope(classLoader);
        feedMedia.installFeedMediaCaptureAndUnblur(classLoader, symbols);
        galleryAndUpload.installDailyFeedSync(classLoader, symbols);
        feedMedia.installHomeGridPostTileUnblurHook(classLoader, symbols);
        feedMedia.installFeedOptionsCanBlurHooks(classLoader, symbols);
        galleryAndUpload.installBeFuckGalleryFeature(classLoader, symbols);
        galleryAndUpload.installRealMojiDownloadMenu(classLoader);
        adSuppression.installAll(classLoader);
    }

    private Resources loadModuleResources(Context hostContext) {
        try {
            ApplicationInfo moduleInfo = getModuleApplicationInfo();
            Constructor<AssetManager> constructor = AssetManager.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            AssetManager assets = constructor.newInstance();
            Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            addAssetPath.setAccessible(true);
            Object cookie = addAssetPath.invoke(assets, moduleInfo.sourceDir);
            if (!(cookie instanceof Integer) || ((Integer) cookie) == 0) {
                throw new IllegalStateException("Could not attach the Xposed module APK asset path");
            }
            host.info("Loaded BeFuck resources from the Xposed module APK");
            return new Resources(
                    assets,
                    hostContext.getResources().getDisplayMetrics(),
                    hostContext.getResources().getConfiguration()
            );
        } catch (Throwable error) {
            host.error("Could not load BeFuck module resources from its APK", error);
            return null;
        }
    }
}
