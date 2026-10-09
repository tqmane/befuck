package dev.tqmane.befuck;

import android.content.Context;
import android.content.Intent;
import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Trace;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import dalvik.system.InMemoryDexClassLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import dev.tqmane.befuck.symbols.BeRealSymbolResolver;
import dev.tqmane.befuck.symbols.ResolvedSymbols;
import dev.tqmane.befuck.symbols.KnownMappings3970;
import dev.tqmane.befuck.runtime.RuntimeKnowledge;
import dev.tqmane.befuck.runtime.ComposeHookScope;
import dev.tqmane.befuck.posting.BeFakeAuthHeaders;
import dev.tqmane.befuck.posting.BeFakeUploadController;
import dev.tqmane.befuck.download.FeedPostMediaCache;
import dev.tqmane.befuck.symbols.FeedMediaSymbols;
import dev.tqmane.befuck.ui.BeFuckGalleryUi;

public final class BeRealModule extends XposedModule {
    private static final String TAG = "BeFuck";
    private static final String TARGET_PACKAGE = "com.bereal.ft";
    private static final String GENERATED_DEX_ENTRY = "assets/pairip/anonymous.dex";
    private static final String APPLICATION_DISPATCH_CLASS =
            "bereal.app.BeRealApplication$c2020060317";
    private static final String ACTIVITY_CREATE_DISPATCH_CLASS =
            "bereal.app.MainActivity$c2020060319";
    private static final String ACTIVITY_DESTROY_DISPATCH_CLASS =
            "bereal.app.MainActivity$c2020060318";
    private static final String ACTIVITY_RESUME_DISPATCH_CLASS =
            "bereal.app.MainActivity$c2020060317";

    private ClassLoader generatedDexParent;
    private ClassLoader generatedDexClassLoader;
    private ClassLoader targetClassLoader;
    private ApplicationInfo targetApplicationInfo;
    private volatile Context applicationContext;
    private volatile Resources moduleResources;
    private final AtomicBoolean runtimeInitializationStarted = new AtomicBoolean();
    private final AtomicBoolean authHeaderCaptureInstalled = new AtomicBoolean();
    private final AtomicBoolean authUrlCaptureInstalled = new AtomicBoolean();
    private final AtomicBoolean authHeaderCaptureLogged = new AtomicBoolean();
    private final AtomicBoolean feedMediaCaptureLogged = new AtomicBoolean();
    private final AtomicBoolean visibleFeedMediaCaptureLogged = new AtomicBoolean();
    private final AtomicBoolean feedMediaDeserializerInvokedLogged = new AtomicBoolean();
    private final AtomicBoolean feedMediaCaptureFailedLogged = new AtomicBoolean();
    private final AtomicBoolean sponsoredFeedPostSuppressionLogged = new AtomicBoolean();
    private final AtomicBoolean localUnblurLogged = new AtomicBoolean();
    private final AtomicBoolean timelineCellUnblurLogged = new AtomicBoolean();
    private final AtomicBoolean pullDownGridCardUnblurLogged = new AtomicBoolean();
    private final AtomicBoolean pullDownGridMediaUnblurLogged = new AtomicBoolean();
    private final AtomicBoolean pullDownGridCardSeenLogged = new AtomicBoolean();
    private final AtomicBoolean pullDownGridMediaSeenLogged = new AtomicBoolean();
    private final AtomicBoolean pullDownGridDetailsClickLogged = new AtomicBoolean();
    private final AtomicBoolean homeGridTileUnblurLogged = new AtomicBoolean();
    private final AtomicBoolean homeGridTileSeenLogged = new AtomicBoolean();
    private final AtomicBoolean homeGridDetailsClickLogged = new AtomicBoolean();
    private final AtomicBoolean homeGridDetailSelectionLogged = new AtomicBoolean();
    private final AtomicBoolean homeFeedCanBlurDisabledLogged = new AtomicBoolean();
    private final AtomicBoolean friendsOfFriendsCanBlurDisabledLogged = new AtomicBoolean();
    private final AtomicBoolean otherFeedCanBlurDisabledLogged = new AtomicBoolean();
    private final AtomicBoolean homeFeedBlurredStateRegularizedLogged = new AtomicBoolean();
    private final AtomicBoolean friendsOfFriendsBlurredStateRegularizedLogged = new AtomicBoolean();
    private final AtomicBoolean otherFeedBlurredStateRegularizedLogged = new AtomicBoolean();
    private final AtomicBoolean universalOptionsUnblurredLogged = new AtomicBoolean();
    private final AtomicBoolean universalReactionsEnabledLogged = new AtomicBoolean();
    private final AtomicBoolean universalPostStateRegularizedLogged = new AtomicBoolean();
    private final AtomicBoolean inlineDownloadInjectionLogged = new AtomicBoolean();
    private final AtomicBoolean inlineDownloadInjectionFailedLogged = new AtomicBoolean();
    private final AtomicBoolean inlineDownloadDetailFallbackLogged = new AtomicBoolean();
    private final AtomicBoolean detailDownloadFactoryLogged = new AtomicBoolean();
    private final AtomicBoolean detailDownloadViewLaidOutLogged = new AtomicBoolean();
    private final AtomicBoolean detailGridMediaLookupMissLogged = new AtomicBoolean();
    private final AtomicBoolean pendingDetailPmgSeenLogged = new AtomicBoolean();
    private final AtomicBoolean pendingDetailGshSeenLogged = new AtomicBoolean();
    private final AtomicBoolean pendingDetailMi6SeenLogged = new AtomicBoolean();
    private final AtomicBoolean detailGridComposerBoundLogged = new AtomicBoolean();
    private final AtomicBoolean detailMediaOverlayComposedLogged = new AtomicBoolean();
    private final AtomicBoolean adViewSuppressionLogged = new AtomicBoolean();
    private final AtomicBoolean videoUploadDiagnosticsInstalled = new AtomicBoolean();
    private volatile ResolvedSymbols resolvedSymbols;
    private boolean composeInjectionReady;
    private final ThreadLocal<Object> protobufMessageInfo = new ThreadLocal<>();
    private final ThreadLocal<Object> retryingConcurrentCamera = new ThreadLocal<>();
    private final ThreadLocal<Boolean> homeFeedModelMapping = new ThreadLocal<>();
    private final ThreadLocal<Boolean> friendsOfFriendsModelMapping = new ThreadLocal<>();
    private final Map<Object, Boolean> pendingConcurrentSessionRetries = new WeakHashMap<>();
    private final Map<Object, Map<String, String>> pendingAuthHeadersByBuilder =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Class<?>, Field> okHttpBuilderUrlFields = new ConcurrentHashMap<>();
    private final Map<Class<?>, Method> okHttpUrlHostMethods = new ConcurrentHashMap<>();
    private volatile boolean protobufNullFieldLogged;
    private final AtomicBoolean roomNullColumnProbeLogged = new AtomicBoolean();
    private final AtomicBoolean cameraOriginSentinelLogged = new AtomicBoolean();
    private final AtomicBoolean media3NetworkObserverDispatchLogged = new AtomicBoolean();
    private volatile boolean fusedLocationApiHooksInstalled;
    private volatile String lastCameraShutterState;
    private volatile String lastMainCameraCaptureState;
    private volatile String lastMainCameraGateState;
    private volatile String lastMainCameraLayoutState;
    private volatile String lastMainCameraControlState;
    private volatile String lastCameraXConcurrentState;
    private volatile Boolean lastCurrentUserVerified;

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        final ClassLoader classLoader = param.getDefaultClassLoader();
        targetClassLoader = classLoader;
        targetApplicationInfo = param.getApplicationInfo();
        info("Loaded for " + param.getPackageName() + "; installing PairIP bypass before deferred BeFuck resolution");

        installApplicationContextHook(classLoader);
        installNullTraceSectionGuard();
        installComposeTracingContextGuard(classLoader);
        installVmRunnerInitializerHook(classLoader);
        installStartupLauncherHook(classLoader);
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
                            applicationContext = (Context) context;
                            initializeRuntimeHooks((Context) context, classLoader);
                        }
                        return chain.proceed();
                    });
            info("Hooked Application.attach for the BeReal process context");
        } catch (Throwable error) {
            error("Could not hook Application.attach for the BeReal process context", error);
        }
    }

    private void initializeRuntimeHooks(Context context, ClassLoader classLoader) {
        if (!runtimeInitializationStarted.compareAndSet(false, true)) {
            return;
        }

        String versionName = null;
        long versionCode = -1L;
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                    TARGET_PACKAGE,
                    0
            );
            versionName = packageInfo.versionName;
            versionCode = packageInfo.getLongVersionCode();
        } catch (Throwable error) {
            error("Could not read BeReal version before symbol resolution", error);
        }

        try {
            ApplicationInfo appInfo = targetApplicationInfo != null
                    ? targetApplicationInfo
                    : context.getApplicationInfo();
            moduleResources = loadModuleResources(context);
            installRepackagedStartupCompatibility(context, classLoader, versionName, versionCode);
            RuntimeKnowledge.initialize(context, classLoader, versionName, versionCode);
            installAuthFailureDiagnostics(classLoader, versionName, versionCode);
            installSmsRequestPayloadRepair(classLoader, versionName, versionCode);
            installEmailAnalyticsGuard(classLoader, versionName, versionCode);
            RuntimeKnowledge.setPresetAssets(moduleResources == null ? null : moduleResources.getAssets());
            installRuntimeRecoveryGuards(classLoader);
            resolvedSymbols = BeRealSymbolResolver.resolve(
                    appInfo,
                    classLoader,
                    versionName,
                    versionCode,
                    message -> info(message)
            );
        } catch (Throwable error) {
            error("BeFuck SymbolResolver failed; affected hooks will use explicit known-version fallback only", error);
        }

        installRuntimeHooks(classLoader, resolvedSymbols);
    }

    private void installRepackagedStartupCompatibility(Context host, ClassLoader loader, String name, long code) {
        // Repackaging changes the signing certificate and Play install provenance. Limit this
        // to known embedded patch loaders; retain the host's remote authentication flow.
        try {
            ApplicationInfo installed = host.getPackageManager().getApplicationInfo(
                    TARGET_PACKAGE, android.content.pm.PackageManager.GET_META_DATA);
            if (installed.metaData == null || (!installed.metaData.containsKey("npatch")
                    && !installed.metaData.containsKey("lspatch"))) return;
            if (!KnownMappings3970.isKnownVersion(name, code)) return;
            installRepackagedSigningInfoCompatibility(host, installed.metaData);
            for (Method check : KnownMappings3970.repackagedStartupChecks(loader, name, code)) {
                hook(check).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    Object context = chain.getArg(0);
                    if (context instanceof Context && TARGET_PACKAGE.equals(((Context) context).getPackageName())) {
                        info("Skipped repackaged application startup check: " + check.getName());
                        return null;
                    }
                    return chain.proceed();
                });
                info("Installed repackaged application compatibility: " + check.getName());
            }
            Method load = KnownMappings3970.preludeLibraryLoad(loader, name, code);
            List<Class<?>> interfaces = KnownMappings3970.preludeLibraryInterfaces(loader, name, code);
            if (load != null && moduleResources != null) {
                hook(load).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    if (!interfaces.contains(chain.getArg(1))) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    args[0] = dev.tqmane.befuck.runtime.PreludeNativeLibrary.extract(host, moduleResources);
                    Object result = chain.proceed(args);
                    info("Loaded upstream Prelude 0.4.1 native implementation with host ABI checks retained");
                    return result;
                });
            }
        } catch (Throwable failure) {
            error("Could not install repackaged application startup compatibility", failure);
        }
    }

    private void installRepackagedSigningInfoCompatibility(Context host, Bundle metadata) throws Exception {
        String encoded = metadata.getString("npatch");
        if (encoded == null) encoded = metadata.getString("lspatch");
        if (encoded == null) throw new IllegalStateException("Missing patch signature metadata");
        org.json.JSONObject config = new org.json.JSONObject(new String(
                android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8));
        android.content.pm.Signature original = new android.content.pm.Signature(config.getString("originalSignature"));
        String digest = android.util.Base64.encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(original.toByteArray()), android.util.Base64.NO_WRAP);
        if (!KnownMappings3970.SIGNING_CERTIFICATE_SHA256.equals(digest)) {
            throw new IllegalStateException("Patch metadata certificate does not match the supported original APK");
        }
        // Some API 102 NPatch runtimes do not apply their legacy PackageManager hooks.
        // Restore the configured certificate through the modern API, for this host only.
        int count = 0;
        for (Class<?> type = host.getPackageManager().getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getReturnType() != PackageInfo.class || Modifier.isAbstract(method.getModifiers())
                        || !("getPackageInfo".equals(method.getName()) || "getPackageInfoAsUser".equals(method.getName()))) continue;
                hook(method).intercept(chain -> {
                    PackageInfo result = (PackageInfo) chain.proceed();
                    if (result == null || !TARGET_PACKAGE.equals(result.packageName)) return result;
                    if (result.signatures != null && result.signatures.length == 1) {
                        result.signatures[0] = new android.content.pm.Signature(original.toByteArray());
                    }
                    if (result.signingInfo != null && !result.signingInfo.hasMultipleSigners()) {
                        android.content.pm.Signature[] current = result.signingInfo.getApkContentsSigners();
                        android.content.pm.Signature[] history = result.signingInfo.getSigningCertificateHistory();
                        if (current != null && current.length == 1) current[0] = new android.content.pm.Signature(original.toByteArray());
                        if (history != null && history.length == 1) history[0] = new android.content.pm.Signature(original.toByteArray());
                    }
                    return result;
                });
                count++;
            }
        }
        PackageInfo legacy = host.getPackageManager().getPackageInfo(TARGET_PACKAGE, android.content.pm.PackageManager.GET_SIGNATURES);
        PackageInfo modern = host.getPackageManager().getPackageInfo(TARGET_PACKAGE, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
        boolean legacyMatches = legacy.signatures != null && legacy.signatures.length == 1 && original.equals(legacy.signatures[0]);
        android.content.pm.Signature[] modernSigners = modern.signingInfo == null ? null : modern.signingInfo.getApkContentsSigners();
        boolean modernMatches = modernSigners != null && modernSigners.length == 1 && original.equals(modernSigners[0]);
        info("Repackaged signing compatibility: methods=" + count + "; legacy=" + legacyMatches + "; modern=" + modernMatches);
    }

    private void installEmailAnalyticsGuard(ClassLoader loader, String name, long code) {
        try {
            KnownMappings3970.EmailAnalyticsGuard guard = KnownMappings3970.emailAnalyticsGuard(loader, name, code);
            if (guard == null) return;
            for (Map.Entry<Constructor<?>, List<Field>> entry : guard.getConstructors().entrySet()) {
                hook(entry.getKey()).intercept(chain -> {
                    if (!guard.hasMissingStep(chain.getArg(0))) return chain.proceed();
                    // Keep a fully initialized event object for callers, but do not invent its
                    // missing analytics label. The emission hook below drops this event.
                    getInvoker(guard.getBaseConstructor()).invokeSpecial(chain.getThisObject(),
                            0, Collections.emptyList(), guard.getEventNames().get(entry.getKey().getDeclaringClass()));
                    for (int i = 0; i < entry.getValue().size(); i++) {
                        entry.getValue().get(i).set(chain.getThisObject(), chain.getArg(i));
                    }
                    return null;
                });
            }
            hook(guard.getEmit()).intercept(chain -> {
                if (!guard.shouldOmit(chain.getArg(0))) return chain.proceed();
                info("Omitted email onboarding analytics with a missing step label");
                return null;
            });
            info("Installed version-scoped email screen analytics guard");
        } catch (Throwable failure) {
            error("Could not install email screen analytics guard", failure);
        }
    }

    private void installSmsRequestPayloadRepair(ClassLoader loader, String name, long code) {
        try {
            Constructor<?> constructor = KnownMappings3970.smsRequestConstructor(loader, name, code);
            if (constructor == null) return;
            hook(constructor).intercept(chain -> {
                Object[] original = chain.getArgs().toArray();
                Object[] args = KnownMappings3970.smsRequestArguments(original);
                // Log only payload shape, never phone numbers, device IDs, or tokens.
                info("SMS request-code payload: tokenCount=" + ((List<?>) args[3]).size()
                        + "; normalizedNullTokens=" + (original != args));
                return chain.proceed(args);
            });
            info("Installed version-scoped SMS request-code payload repair");
        } catch (Throwable failure) {
            error("Could not install SMS request-code payload repair", failure);
        }
    }

    private void installAuthFailureDiagnostics(ClassLoader loader, String name, long code) {
        try {
            Map<String, Constructor<?>> constructors = KnownMappings3970.authDiagnosticConstructors(loader, name, code);
            for (Map.Entry<String, Constructor<?>> entry : constructors.entrySet()) {
                final String kind = entry.getKey();
                hook(entry.getValue()).intercept(chain -> {
                    Object result = chain.proceed();
                    // Never stringify token arguments, exception messages, or network payloads.
                    if ("request_failure".equals(kind)) {
                        String reason = String.valueOf(chain.getArg(1));
                        String[] known = {"AntibotChallengeFailure", "PhoneNumberBlocked", "CountryBlocked",
                                "SessionExpired", "UserSuspended", "CountryCodeInvalid", "CountryNotAllowedAcrossAllOTP",
                                "SmsServiceDown", "InvalidPayload", "PhoneNumberBanned", "RateLimitReached",
                                "ConcurrentVerification", "SpamDetected", "UnderageBlocked"};
                        boolean recognized = java.util.Arrays.asList(known).contains(reason);
                        info("Auth request-code failure category=" + (recognized ? reason : "Unclassified"));
                    } else if ("challenge_token".equals(kind)) {
                        Object provider = chain.getArg(1);
                        info("Challenge token generated provider=" + ("PR".equals(provider) ? "Prelude" : "RE".equals(provider) ? "Recaptcha" : "Other")
                                + "; present=" + (chain.getArg(0) instanceof String && !((String) chain.getArg(0)).isEmpty()));
                    } else if ("recaptcha_initialization".equals(kind)) {
                        info("Recaptcha initialization failure code=" + (Integer) chain.getArg(0));
                    }
                    return result;
                });
            }
            if (!constructors.isEmpty()) info("Installed version-scoped authentication diagnostics");
        } catch (Throwable failure) {
            error("Could not install authentication failure diagnostics", failure);
        }
    }

    private Resources loadModuleResources(Context hostContext) {
        try {
            ApplicationInfo moduleInfo = getModuleApplicationInfo();
            java.lang.reflect.Constructor<AssetManager> constructor = AssetManager.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            AssetManager assets = constructor.newInstance();
            Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            addAssetPath.setAccessible(true);
            Object cookie = addAssetPath.invoke(assets, moduleInfo.sourceDir);
            if (!(cookie instanceof Integer) || ((Integer) cookie) == 0) {
                throw new IllegalStateException("Could not attach the Xposed module APK asset path");
            }
            info("Loaded BeFuck resources from the Xposed module APK");
            return new Resources(
                    assets,
                    hostContext.getResources().getDisplayMetrics(),
                    hostContext.getResources().getConfiguration()
            );
        } catch (Throwable error) {
            error("Could not load BeFuck module resources from its APK", error);
            return null;
        }
    }

    private void installRuntimeRecoveryGuards(ClassLoader classLoader) {
        try {
            Method attach = android.os.Binder.class.getDeclaredMethod("attachInterface", android.os.IInterface.class, String.class);
            hook(attach).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                if (chain.getArg(0) != null && chain.getArg(1) == null) {
                    String recovered = RuntimeKnowledge.recoverArgument("binder_descriptor");
                    if (recovered != null) {
                        Object[] args = chain.getArgs().toArray(); args[1] = recovered;
                        return chain.proceed(args);
                    }
                    // Persist unresolved descriptors before a later native Parcel call can abort the process.
                    RuntimeKnowledge.flushDiagnostics();
                }
                return chain.proceed();
            });
        } catch (Throwable failure) { error("Could not install Binder descriptor recovery", failure); }
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            try {
                RuntimeKnowledge.recordFailure(failure);
                RuntimeKnowledge.flushDiagnostics();
            } finally {
                if (previous != null) previous.uncaughtException(thread, failure);
            }
        });
        try {
            java.lang.reflect.Constructor<StringBuilder> builder = StringBuilder.class.getDeclaredConstructor(String.class);
            hook(builder).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                if (chain.getArg(0) == null) {
                    String recovered = RuntimeKnowledge.recoverArgument("string_builder_prefix");
                    if (recovered != null) {
                        Object[] args = chain.getArgs().toArray(); args[0] = recovered;
                        return chain.proceed(args);
                    }
                }
                return chain.proceed();
            });
            for (String name : new String[]{"endsWith", "startsWith"}) {
                Method method = String.class.getDeclaredMethod(name, String.class);
                hook(method).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    if (chain.getArg(0) == null) {
                        String recovered = RuntimeKnowledge.recoverArgument("string_" + name);
                        if (recovered != null) {
                            Object[] args = chain.getArgs().toArray(); args[0] = recovered;
                            return chain.proceed(args);
                        }
                    }
                    return chain.proceed();
                });
            }
            info("Installed null-argument recovery and version-scoped JSONL diagnostics");
        } catch (Throwable failure) {
            error("Could not install a String null-argument recovery hook", failure);
        }
        if (KnownMappings3970.isKnownVersion(RuntimeKnowledge.getVersionName())) {
            try {
                Method property = Class.forName("she", false, classLoader).getDeclaredMethod("K", Object.class, String.class);
                hook(property).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    if (chain.getArg(1) == null) {
                        String recovered = RuntimeKnowledge.recoverArgument("analytics_key");
                        if (recovered != null) {
                            Object[] args = chain.getArgs().toArray(); args[1] = recovered;
                            return chain.proceed(args);
                        }
                    }
                    return chain.proceed();
                });
            } catch (Throwable failure) { error("Could not install analytics-key recovery", failure); }
        }
    }

    private void installRuntimeHooks(ClassLoader classLoader, ResolvedSymbols symbols) {
        for (Map.Entry<Field, String> repair : KnownMappings3970.runtimeStringRepairs(
                classLoader, RuntimeKnowledge.getVersionName()).entrySet()) {
            Field field = repair.getKey();
            restoreStaticStringIfNull(field, field.getDeclaringClass().getName() + "." + field.getName(), repair.getValue());
        }
        installAnalyticsNullKeyGuard(classLoader);
        installProtobufNullFieldProbe(classLoader);
        installRoomNullColumnGuard(classLoader);
        installCameraOriginSentinelGuard(classLoader, symbols);
        installFusedLocationFallback(classLoader, symbols);
        installMedia3NetworkTypeReceiverHook(classLoader);
        installConcurrentVideoSecondaryFrontSelection(classLoader, symbols);
        installBeFakeAuthHeaderCapture(classLoader);
        installComposeInjectionScope(classLoader);
        installFeedMediaCaptureAndUnblur(classLoader, symbols);
        installHomeGridPostTileUnblurHook(classLoader, symbols);
        installFeedOptionsCanBlurHooks(classLoader, symbols);
        installBeFuckGalleryFeature(classLoader, symbols);
        installRealMojiDownloadMenu(classLoader);
        installAdViewSuppression();
    }

    private void installRealMojiDownloadMenu(ClassLoader loader) {
        if (!KnownMappings3970.isKnownVersion(RuntimeKnowledge.getVersionName()) || moduleResources == null) return;
        try {
            Class<?> actionClass = Class.forName("ddi", false, loader);
            Class<?> itemClass = Class.forName("a7i", false, loader);
            Class<?> entryClass = Class.forName("edi", false, loader);
            Class<?> selectedClass = Class.forName("ndi", false, loader);
            Class<?> viewerItemClass = Class.forName("mdi", false, loader);
            Class<?> listClass = Class.forName("u4a", false, loader);
            Constructor<?> selected = selectedClass.getDeclaredConstructor(viewerItemClass, String.class, listClass, int.class);
            Constructor<?> item = itemClass.getDeclaredConstructor(String.class, String.class, String.class, boolean.class, Integer.class);
            Constructor<?> entry = entryClass.getDeclaredConstructor(actionClass, itemClass, String.class);
            Method immutableList = Class.forName("ie5", false, loader).getDeclaredMethod("z", Iterable.class);
            Method clicked = Class.forName("sdi", false, loader).getDeclaredMethod("G", actionClass);
            Field realMoji = viewerItemClass.getDeclaredField("a");
            Field tag = entryClass.getDeclaredField("c");
            String downloadTag = "befuck.realmoji.download";
            String label = moduleResources.getString(dev.tqmane.befuck.R.string.befuck_realmoji_download);
            int icon = applicationContext.getResources().getIdentifier("ic_arrow_down_to_line", "drawable", TARGET_PACKAGE);
            // Handle our interface proxy before the native sealed-action branches (profile/delete/report).
            hook(clicked).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                Object action = chain.getArg(0);
                if (action != null && Proxy.isProxyClass(action.getClass())) {
                    InvocationHandler handler = Proxy.getInvocationHandler(action);
                    if (handler instanceof dev.tqmane.befuck.download.RealMojiDownloadAction) {
                        ((dev.tqmane.befuck.download.RealMojiDownloadAction) handler).download(applicationContext, moduleResources);
                        return null;
                    }
                }
                return chain.proceed();
            });
            hook(selected).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                try {
                    List<?> existing = (List<?>) args[2];
                    boolean present = false;
                    for (Object value : existing) if (downloadTag.equals(tag.get(value))) present = true;
                    if (!present) {
                        Object model = realMoji.get(args[0]);
                        Object action = dev.tqmane.befuck.download.RealMojiDownloadAction.create(loader, model);
                        Object menuItem = item.newInstance(label, null, label, false, icon == 0 ? null : Integer.valueOf(icon));
                        List<Object> items = new ArrayList<>(existing);
                        items.add(entry.newInstance(action, menuItem, downloadTag));
                        args[2] = immutableList.invoke(null, items);
                    }
                } catch (Throwable failure) {
                    error("Could not append the selected RealMoji download entry", failure);
                }
                return chain.proceed(args);
            });
            info("Installed selected RealMoji download entry in the native overflow menu for 3597523");
        } catch (Throwable failure) {
            error("Could not install the version-specific RealMoji download menu", failure);
        }
    }

    private void installBeFakeAuthHeaderCapture(ClassLoader classLoader) {
        if (!authHeaderCaptureInstalled.compareAndSet(false, true)) return;
        try {
            Class<?> builderClass = Class.forName("okhttp3.Request$Builder", false, classLoader);
            int hooked = 0;
            for (Method method : builderClass.getDeclaredMethods()) {
                String name = method.getName();
                Class<?>[] parameters = method.getParameterTypes();
                if (!("addHeader".equals(name) || "header".equals(name))
                        || parameters.length != 2
                        || parameters[0] != String.class
                        || parameters[1] != String.class) {
                    continue;
                }
                hook(method)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object builder = chain.getThisObject();
                            Object key = chain.getArg(0);
                            Object value = chain.getArg(1);
                            if (key instanceof String && value instanceof String) {
                                String normalized = ((String) key).toLowerCase(java.util.Locale.ROOT);
                                if ("authorization".equals(normalized) || "bereal-device-id".equals(normalized)) {
                                    String host = resolveOkHttpBuilderHost(builder);
                                    if (host == null) {
                                        synchronized (pendingAuthHeadersByBuilder) {
                                            Map<String, String> pending = pendingAuthHeadersByBuilder.get(builder);
                                            if (pending == null) {
                                                pending = new LinkedHashMap<>();
                                                pendingAuthHeadersByBuilder.put(builder, pending);
                                            }
                                            pending.put((String) key, (String) value);
                                        }
                                    } else {
                                        captureBeRealHeader(host, (String) key, (String) value);
                                    }
                                }
                            }
                            return chain.proceed();
                        });
                hooked++;
            }
            for (Method method : builderClass.getDeclaredMethods()) {
                if ("build".equals(method.getName()) && method.getParameterCount() == 0) {
                    hook(method)
                            .setPriority(XposedInterface.PRIORITY_HIGHEST)
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> {
                                Object request = chain.proceed();
                                if (request != null) {
                                    captureFromBuiltOkHttpRequest(request);
                                }
                                return request;
                            });
                    hooked++;
                }
            }
            if (hooked == 0) throw new NoSuchMethodException("okhttp3.Request.Builder header setters unavailable");
            installOkHttpBuilderUrlCapture(builderClass);
            info("Hooked OkHttp request header setters for BeFake; capture is restricted to bereal.com hosts");
        } catch (Throwable error) {
            authHeaderCaptureInstalled.set(false);
            error("Could not install BeFake's in-memory BeReal auth-header capture", error);
        }
    }

    private void captureFromBuiltOkHttpRequest(Object request) {
        try {
            Method urlMethod = request.getClass().getMethod("url");
            Object httpUrl = urlMethod.invoke(request);
            if (httpUrl == null) return;
            Method hostMethod = httpUrl.getClass().getMethod("host");
            String host = (String) hostMethod.invoke(httpUrl);
            if (host == null || !host.toLowerCase(java.util.Locale.ROOT).endsWith("bereal.com")) return;

            String fullUrl = httpUrl.toString();
            if (fullUrl.contains("/api/")) {
                BeFakeAuthHeaders.setApiHost(host);
            }

            Method headersMethod = request.getClass().getMethod("headers");
            Object headersObj = headersMethod.invoke(request);
            if (headersObj == null) return;

            Method namesMethod = headersObj.getClass().getMethod("names");
            @SuppressWarnings("unchecked")
            Set<String> names = (Set<String>) namesMethod.invoke(headersObj);
            Method getMethod = headersObj.getClass().getMethod("get", String.class);

            if (names != null && getMethod != null) {
                for (String name : names) {
                    String value = (String) getMethod.invoke(headersObj, name);
                    if (value != null && !value.isEmpty()) {
                        captureBeRealHeader(host, name, value);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void installOkHttpBuilderUrlCapture(Class<?> builderClass) throws Throwable {
        if (!authUrlCaptureInstalled.compareAndSet(false, true)) return;
        int hooked = 0;
        try {
            for (Method method : builderClass.getDeclaredMethods()) {
                if (!"url".equals(method.getName()) || method.getParameterCount() != 1 ||
                        !builderClass.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                hook(method)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object builder = chain.getThisObject();
                            String host = resolveOkHttpBuilderHost(builder);
                            if (host != null) capturePendingBeRealHeaders(builder, host);
                            return result;
                        });
                hooked++;
            }
            if (hooked == 0) throw new NoSuchMethodException("okhttp3.Request.Builder.url setters unavailable");
        } catch (Throwable failure) {
            authUrlCaptureInstalled.set(false);
            throw failure;
        }
    }

    private void capturePendingBeRealHeaders(Object builder, String host) {
        Map<String, String> pending;
        synchronized (pendingAuthHeadersByBuilder) {
            pending = pendingAuthHeadersByBuilder.remove(builder);
        }
        if (pending == null) return;
        for (Map.Entry<String, String> entry : pending.entrySet()) {
            captureBeRealHeader(host, entry.getKey(), entry.getValue());
        }
    }

    private void captureBeRealHeader(String host, String key, String value) {
        BeFakeAuthHeaders.capture(host, key, value);
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        if (("authorization".equals(normalized) || "bereal-device-id".equals(normalized))
                && host.toLowerCase(java.util.Locale.ROOT).endsWith("bereal.com")
                && authHeaderCaptureLogged.compareAndSet(false, true)) {
            info("Captured current BeReal API auth headers in memory (values redacted)");
        }
    }

    private String resolveOkHttpBuilderHost(Object builder) {
        if (builder == null) return null;
        Class<?> builderClass = builder.getClass();
        Field urlField = okHttpBuilderUrlFields.get(builderClass);
        if (urlField == null) {
            Class<?> current = builderClass;
            while (current != null) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    Method hostMethod = findOkHttpUrlHostMethod(field.getType());
                    if (hostMethod != null) {
                        field.setAccessible(true);
                        okHttpBuilderUrlFields.put(builderClass, field);
                        urlField = field;
                        break;
                    }
                }
                if (urlField != null) break;
                current = current.getSuperclass();
            }
        }
        if (urlField == null) return null;
        try {
            urlField.setAccessible(true);
            Object url = urlField.get(builder);
            if (url == null) return null;
            Method hostMethod = findOkHttpUrlHostMethod(url.getClass());
            if (hostMethod == null) return null;
            hostMethod.setAccessible(true);
            Object host = hostMethod.invoke(url);
            return host instanceof String && ((String) host).contains(".") ? (String) host : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Method findOkHttpUrlHostMethod(Class<?> urlClass) {
        Method cached = okHttpUrlHostMethods.get(urlClass);
        if (cached != null) return cached;
        for (Method method : urlClass.getMethods()) {
            if ("host".equals(method.getName()) && method.getParameterCount() == 0 &&
                    method.getReturnType() == String.class) {
                okHttpUrlHostMethods.put(urlClass, method);
                return method;
            }
        }
        return null;
    }

    private void installBeFuckGalleryFeature(ClassLoader classLoader, ResolvedSymbols symbols) {
        try {
            Class<?> mainActivity = Class.forName("bereal.app.MainActivity", false, classLoader);
            Method onResume = mainActivity.getDeclaredMethod("onResume");
            hook(onResume)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object activity = chain.getThisObject();
                        if (activity instanceof Activity) {
                            BeFuckGalleryUi.installEntryButton(
                                    (Activity) activity,
                                    moduleResources,
                                    symbols
                            );
                        }
                        return result;
                    });
            info("Hooked BeReal MainActivity.onResume for the BeFake gallery entry; auth captured from BeReal API requests");

            Method onPause = findActivityLifecycleMethod(mainActivity, "onPause");
            if (onPause != null) {
                hook(onPause)
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object activity = chain.getThisObject();
                            Object result = chain.proceed();
                            if (activity instanceof Activity
                                    && "bereal.app.MainActivity".equals(activity.getClass().getName())) {
                                BeFuckGalleryUi.onActivityPause((Activity) activity);
                            }
                            return result;
                        });
                info("Hooked MainActivity.onPause to stop the home-only BeFake entry monitor");
            } else {
                info("MainActivity.onPause was not declared on this class hierarchy; route monitor uses the activity lifetime");
            }

            Method onDestroy = findActivityLifecycleMethod(mainActivity, "onDestroy");
            if (onDestroy != null) {
                hook(onDestroy)
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object activity = chain.getThisObject();
                            Object result = chain.proceed();
                            if (activity instanceof Activity
                                    && "bereal.app.MainActivity".equals(activity.getClass().getName())) {
                                BeFuckGalleryUi.onActivityDestroy((Activity) activity);
                            }
                            return result;
                        });
                info("Hooked MainActivity.onDestroy to close stale BeFuck dialogs");
            }

            Method onActivityResult = findActivityResultMethod(mainActivity);
            if (onActivityResult != null) {
                hook(onActivityResult)
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object activity = chain.getThisObject();
                            Object[] args = chain.getArgs().toArray();
                            if (activity instanceof Activity
                                    && args.length == 3
                                    && args[0] instanceof Integer
                                    && args[1] instanceof Integer) {
                                BeFuckGalleryUi.onActivityResult(
                                        (Activity) activity,
                                        (Integer) args[0],
                                        (Integer) args[1],
                                        args[2] instanceof Intent ? (Intent) args[2] : null
                                );
                            }
                            return result;
                        });
                info("Hooked the app-specific Activity result method for selected gallery images");
            } else {
                info("Gallery picker result hook unavailable; BeFuck entry remains disabled for image selection");
            }

            Method onPermissionsResult = findActivityPermissionResultMethod(mainActivity);
            if (onPermissionsResult != null) {
                hook(onPermissionsResult)
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object activity = chain.getThisObject();
                            Object[] args = chain.getArgs().toArray();
                            if (activity instanceof Activity && args.length == 3
                                    && args[0] instanceof Integer && args[2] instanceof int[]) {
                                BeFuckGalleryUi.onRequestPermissionsResult(
                                        (Activity) activity,
                                        (Integer) args[0],
                                        (int[]) args[2]
                                );
                            }
                            return result;
                        });
                info("Hooked the Activity permission result callback for the BeFake current-location map");
            } else {
                info("Activity permission result callback unavailable; the BeFake map can still use an existing location grant");
            }
        } catch (Throwable error) {
            error("Could not install the BeFuck Home entry or gallery result hook", error);
        }

        info("BeFake direct signed-upload/API post configured");
        installVideoUploadFailureDiagnostics(classLoader);
    }

    private void installVideoUploadFailureDiagnostics(ClassLoader classLoader) {
        if (!videoUploadDiagnosticsInstalled.compareAndSet(false, true)) return;
        try {
            if (resolvedSymbols != null && KnownMappings3970.isKnownVersion(resolvedSymbols.getVersionName())) {
                Field recursionDepth = Class.forName("androidx.credentials.gZ.MqonvtnPZU", false, classLoader)
                        .getDeclaredField("rjWJOvNnlBpCE");
                recursionDepth.setAccessible(true);
                restoreStaticStringIfNull(recursionDepth, "uploadRecursionDepth", "recursionDepth");
                Class<?> fileTypeStrings = Class.forName("com.yoti.mobile.android.yotisdkcore.stepTracker.di.CGh.TcjS", false, classLoader);
                Field mp3Extension = fileTypeStrings.getDeclaredField("csWHdjHvbvur");
                mp3Extension.setAccessible(true);
                restoreStaticStringIfNull(mp3Extension, "media3Mp3Extension", ".mp3");
                Field avifExtension = fileTypeStrings.getDeclaredField("OPbZdoBRniyAS");
                avifExtension.setAccessible(true);
                restoreStaticStringIfNull(avifExtension, "media3AvifExtension", ".avif");
                Field commercialFlagName = Class.forName("androidx.media3.extractor.text.pgs.wtco.kKFOp", false, classLoader)
                        .getDeclaredField("DJrrLBsuQ");
                commercialFlagName.setAccessible(true);
                restoreStaticStringIfNull(commercialFlagName, "cancelPostCommercialFlagName", "isCommercialCollaboration");
                Field mentionPrefix = Class.forName("com.pubmatic.sdk.webrendering.rVzC.QfYVeFFNcJ", false, classLoader)
                        .getDeclaredField("XpQSiFrhKPuQAsg");
                mentionPrefix.setAccessible(true);
                restoreStaticStringIfNull(mentionPrefix, "commentMentionPrefix", "@");
            }
            Class<?> worker = Class.forName(
                    "bereal.app.data.post.repository.mypost.worker.UploadUnsentPostWorker",
                    false,
                    classLoader
            );
            int hooked = 0;
            for (Method method : worker.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                boolean diagnosticMethod = "g".equals(method.getName()) || "i".equals(method.getName());
                if (!diagnosticMethod || parameters.length != 3
                        || parameters[0] != String.class
                        || !Throwable.class.isAssignableFrom(parameters[1])
                        || !Map.class.isAssignableFrom(parameters[2])) {
                    continue;
                }
                hook(method)
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object stage = chain.getArg(0);
                            Object failure = chain.getArg(1);
                            if (stage instanceof String && failure instanceof Throwable) {
                                Throwable throwable = (Throwable) failure;
                                String details;
                                if ("up4".equals(throwable.getClass().getSimpleName())) {
                                    try {
                                        details = throwable.toString();
                                    } catch (Throwable ignored) {
                                        details = throwable.getClass().getName();
                                    }
                                } else if (throwable instanceof NullPointerException) {
                                    StringBuilder stack = new StringBuilder(throwable.getClass().getName());
                                    StackTraceElement[] frames = throwable.getStackTrace();
                                    for (int index = 0; index < Math.min(frames.length, 12); index++) {
                                        stack.append(" <- ")
                                                .append(frames[index].getClassName())
                                                .append('#')
                                                .append(frames[index].getMethodName())
                                                .append(':')
                                                .append(frames[index].getLineNumber());
                                    }
                                    details = stack.toString();
                                } else {
                                    details = Log.getStackTraceString(throwable)
                                            .replaceAll("https?://[^\\s)]+", "<url>")
                                            .replaceAll("(?i)Bearer [A-Za-z0-9._~-]+", "Bearer <redacted>");
                                }
                                info("Official upload worker failure stage=" + stage + " details=" + details);
                            }
                            return chain.proceed();
                        });
                hooked++;
            }
            Method uploadStep = null;
            for (Method method : worker.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!"j".equals(method.getName()) || method.getReturnType() != Object.class
                        || parameters.length != 3 || parameters[0] != int.class
                        || parameters[1] != String.class) {
                    continue;
                }
                uploadStep = method;
                break;
            }
            if (uploadStep != null) {
                hook(uploadStep)
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object postId = chain.getArg(1);
                            Object depth = chain.getArg(0);
                            info("Upload worker step entry: depth=" + depth + ", postId=" +
                                    (postId instanceof String ? ((String) postId).substring(0, Math.min(8, ((String) postId).length())) : "null"));
                            return chain.proceed();
                        });
                hooked++;
            }
            info("Installed official upload-worker diagnostics on " + hooked + " method(s)");
        } catch (Throwable failure) {
            error("Could not install official video-processing diagnostics", failure);
        }
    }

    private Method findActivityLifecycleMethod(Class<?> activityClass, String name) {
        Class<?> current = activityClass;
        while (current != null && Activity.class.isAssignableFrom(current)) {
            try {
                Method method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (Throwable error) {
                error("Could not resolve Activity." + name, error);
                return null;
            }
        }
        return null;
    }

    private void installComposeInjectionScope(ClassLoader loader) {
        Map<String, Method> methods = KnownMappings3970.composeRuntimeMethods(loader, RuntimeKnowledge.getVersionName());
        if (methods.isEmpty()) {
            info("Compose download additions disabled: runtime signatures unresolved");
            return;
        }
        try {
            hook(methods.get("start")).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                Object composer = chain.proceed();
                ComposeHookScope.started(composer);
                return composer;
            });
            hook(methods.get("execute")).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                Object execute = chain.proceed();
                ComposeHookScope.executed(chain.getThisObject(), Boolean.TRUE.equals(execute));
                return execute;
            });
            hook(methods.get("end")).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                ComposeHookScope.ending(chain.getThisObject());
                Object scope = chain.proceed();
                ComposeHookScope.ended(chain.getThisObject());
                return scope;
            });
            composeInjectionReady = true;
            info("Installed restart-scope Compose additions with paused/skipped composition tracking");
        } catch (Throwable failure) {
            error("Could not install Compose download scope tracking", failure);
        }
    }

    private void installFeedMediaCaptureAndUnblur(ClassLoader classLoader, ResolvedSymbols symbols) {
        FeedMediaSymbols media = symbols == null ? null : symbols.getFeedMediaSymbols();
        info("Installing feed media hooks; capture=" + (media != null && media.getCanCaptureFeedMedia())
                + ", unblur=" + (media != null && media.getCanUnblurLocally()));
        if (media == null) {
            info("[SymbolResolver] Feed media capture and local unblur disabled: media symbols unresolved");
            return;
        }

        if (KnownMappings3970.isKnownVersion(symbols.getVersionName())) {
            try {
                Class<?> currentUser = Class.forName("hun", false, classLoader);
                Class<?> mediaModel = Class.forName("wi1", false, classLoader);
                Field aspectRatio = mediaModel.getDeclaredField("aspectRatio");
                aspectRatio.setAccessible(true);
                for (Constructor<?> constructor : mediaModel.getDeclaredConstructors()) {
                    if (constructor.getParameterCount() != 4 || constructor.getParameterTypes()[0] != String.class) continue;
                    hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> {
                                Object result = chain.proceed();
                                int height = (Integer) chain.getArg(1);
                                int width = (Integer) chain.getArg(2);
                                if (height > 0 && width > 0) aspectRatio.setFloat(chain.getThisObject(), (float) width / height);
                                return result;
                            });
                }
                for (Constructor<?> constructor : currentUser.getDeclaredConstructors()) {
                    hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> {
                                Object result = chain.proceed();
                                FeedPostMediaCache.captureCurrentUser(chain.getThisObject());
                                return result;
                            });
                }
                Class<?> corePost = Class.forName("h45", false, classLoader);
                for (Constructor<?> constructor : corePost.getDeclaredConstructors()) {
                    if (constructor.getParameterCount() != 28) continue;
                    hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> {
                                Object result = chain.proceed();
                                FeedPostMediaCache.captureCorePost(chain.getThisObject());
                                return result;
                            });
                }
                info("Capturing CorePost URLs, owner and original timestamps from all loaded feed models");
                Class<?> unsentRepository = Class.forName("s55", false, classLoader);
                Class<?> postRepository = Class.forName("z4e", false, classLoader);
                Class<?> createContinuation = Class.forName("t4e", false, classLoader);
                Field pendingCore = createContinuation.getDeclaredField("r");
                pendingCore.setAccessible(true);
                for (Method method : postRepository.getDeclaredMethods()) {
                    if (!"d".equals(method.getName()) || method.getParameterCount() != 5 ||
                            method.getParameterTypes()[0] != corePost) continue;
                    hook(method).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> {
                                Object core = chain.getArg(0);
                                Object continuation = chain.getArg(4);
                                if (core == null && createContinuation.isInstance(continuation)) core = pendingCore.get(continuation);
                                Object result = chain.proceed();
                                dev.tqmane.befuck.posting.GalleryPostController.noteOfficialPostPublished(core, result);
                                return result;
                            });
                }
                Class<?> unsentPost = Class.forName("tin", false, classLoader);
                for (Method method : unsentRepository.getDeclaredMethods()) {
                    if (!"p".equals(method.getName()) || method.getParameterCount() != 2 ||
                            method.getParameterTypes()[0] != unsentPost) continue;
                    hook(method).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> {
                                dev.tqmane.befuck.posting.GalleryPostController.noteOfficialPostSaved(chain.getArg(0));
                                return chain.proceed();
                            });
                }
            } catch (Throwable error) {
                error("Could not capture CorePost metadata", error);
            }
        }

        Object sponsoredModifier = null;
        try { sponsoredModifier = createZeroSizeComposeModifier(classLoader); }
        catch (Throwable failure) { error("Could not resolve sponsored-card size modifier", failure); }
        final Object hiddenSponsoredModifier = sponsoredModifier;
        Method feedCardMethod = media.getPostFeedCardComposableMethod();
        if (feedCardMethod != null) {
            try {
                hook(feedCardMethod)
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object[] args = chain.getArgs().toArray();
                            if (args.length > 1 && media.getViewStateRealSponsoredPostUiStateField() != null) {
                                try {
                                    Object sponsoredState = media.getViewStateRealSponsoredPostUiStateField().get(args[1]);
                                    if (sponsoredState != null && hiddenSponsoredModifier != null &&
                                            feedCardMethod.getParameterTypes()[0].isInstance(hiddenSponsoredModifier)) {
                                        if (sponsoredFeedPostSuppressionLogged.compareAndSet(false, true)) {
                                            info("Hid the sponsored feed card with a zero-size modifier while preserving its composition");
                                        }
                                        args[0] = hiddenSponsoredModifier;
                                    }
                                } catch (Throwable error) {
                                    info("Sponsored feed-card state could not be read; leaving the card unchanged");
                                }
                            }
                            FeedPostMediaCache.beginVisiblePostComposition(args.length > 1 ? args[1] : null, media);
                            try {
                                Object result = chain.proceed(args);
                                if (args.length > 1 && FeedPostMediaCache.captureVisibleFeedState(args[1], media)
                                        && visibleFeedMediaCaptureLogged.compareAndSet(false, true)) {
                                    info("Captured loaded primary/secondary/BTS media from the visible feed card state");
                                }
                                return result;
                            } finally {
                                FeedPostMediaCache.endVisiblePostComposition();
                            }
                        });
                info("Hooked resolved feed-card composition for media indexing from local/remote models");
            } catch (Throwable error) {
                error("Could not hook feed-card media indexing", error);
            }
        }

        Method deserializer = media.getPostDeserializerMethod();
        Class<?> postModel = media.getPostModelClass();
        if (deserializer != null && postModel != null) {
            try {
                hook(deserializer)
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            if (feedMediaDeserializerInvokedLogged.compareAndSet(false, true)) {
                                info("PostRemoteModel deserializer invoked; returnedType="
                                        + (result == null ? "null" : result.getClass().getName())
                                        + ", expectedModel=" + postModel.getName());
                            }
                            if (result != null && postModel.isInstance(result)) {
                                boolean captured = FeedPostMediaCache.capture(result, media);
                                if (captured && feedMediaCaptureLogged.compareAndSet(false, true)) {
                                    info("Captured feed post media URLs in the bounded in-memory download index");
                                } else if (!captured && feedMediaCaptureFailedLogged.compareAndSet(false, true)) {
                                    info("PostRemoteModel parsed, but its media URLs did not match the validated capture shape");
                                }
                            }
                            return result;
                        });
                info("Hooked the resolved PostRemoteModel deserializer for image/BTS download indexing");
            } catch (Throwable error) {
                error("Could not hook PostRemoteModel media capture", error);
            }
        } else {
            info("[SymbolResolver] PostRemoteModel media capture disabled: deserializer or model class unresolved");
        }

        Method renderMedia = media.getBlurredMediaRenderMethod();
        if (renderMedia != null) {
            try {
                ComposeDownloadOverlaySymbols overlaySymbols = resolveComposeDownloadOverlaySymbols(classLoader);
                hook(renderMedia)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object[] args = chain.getArgs().toArray();
                            if (FeedPostMediaCache.hasPendingGridPostForDetail() && pendingDetailPmgSeenLogged.compareAndSet(false, true)) {
                                info("Selected-grid detail entered the pmg.a download hook");
                            }
                            if (args.length > 2 && Boolean.TRUE.equals(args[2])) {
                                args[2] = Boolean.FALSE;
                                if (localUnblurLogged.compareAndSet(false, true)) info("Disabled BeReal feed media blur at its local rendering boundary");
                            }
                            ComposeHookScope.Frame frame = null;
                            if (composeInjectionReady && overlaySymbols != null && args.length > 16 && args[16] != null) {
                                frame = ComposeHookScope.push(args[16], () -> {
                                    if (FeedPostMediaCache.hasPendingGridPostForDetail()) return;
                                    Object post = FeedPostMediaCache.postForDualMedia(args[1]);
                                    if (post == null) return;
                                    try { injectInlineDownloadOverlay(args[16], classLoader, overlaySymbols, null, post, false); }
                                    catch (Throwable failure) { throw new IllegalStateException("Could not compose the feed download control", failure); }
                                });
                            }
                            try { return chain.proceed(args); }
                            finally { if (frame != null) ComposeHookScope.pop(frame); }
                        });
                info("Hooked resolved feed-media renderer for local-only unblur and per-post downloads");
                installCurrentPostMediaOrientationHook(classLoader);
            } catch (Throwable error) {
                error("Could not hook local feed-media unblur renderer", error);
            }
        }

        Method blurredOverlay = media.getBlurredOverlayComposableMethod();
        if (blurredOverlay != null) {
            try {
                Object zeroSizeModifier = createZeroSizeComposeModifier(classLoader);
                hook(blurredOverlay)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object[] args = chain.getArgs().toArray();
                            if (zeroSizeModifier != null && args.length > 0) {
                                args[0] = zeroSizeModifier;
                                return chain.proceed(args);
                            }
                            return chain.proceed();
                        });
                info("Hooked the resolved gated-post Compose overlay with a zero-size local modifier; composition and server post state are unchanged");
            } catch (Throwable error) {
                error("Could not hide the gated-post overlay locally", error);
            }
        }

        Method timelineBlurredCard = symbols.getTimelineBlurredCardComposableMethod();
        if (timelineBlurredCard != null) {
            try {
                hook(timelineBlurredCard)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object[] args = chain.getArgs().toArray();
                            if (args.length > 5 && Boolean.TRUE.equals(args[5])) {
                                args[5] = Boolean.FALSE;
                                if (timelineCellUnblurLogged.compareAndSet(false, true)) {
                                    info("Disabled the blurred timeline CTA at its local Compose rendering boundary; the camera action is no longer composed");
                                }
                                return chain.proceed(args);
                            }
                            return chain.proceed();
                        });
                info("Hooked the version-mapped timeline cell to remove its local blur gate and camera CTA");
            } catch (Throwable error) {
                error("Could not hook the local timeline blur CTA", error);
            }
        }

        Method gridCard = symbols.getPullDownGridCardComposableMethod();
        if (gridCard != null) {
            try {
                hook(gridCard)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object[] args = chain.getArgs().toArray();
                            String gridDetailPostId = FeedPostMediaCache.beginGridDetailComposition(
                                    args.length > 1 && args[1] instanceof String ? (String) args[1] : null
                            );
                            if (gridDetailPostId != null && detailGridComposerBoundLogged.compareAndSet(false, true)) {
                                info("Bound gsh.a for the selected grid post's full-screen media composition");
                            }
                            if (FeedPostMediaCache.hasPendingGridPostForDetail() &&
                                    pendingDetailGshSeenLogged.compareAndSet(false, true)) {
                                info("Selected-grid detail entered the gsh.a card hook");
                            }
                            if (pullDownGridCardSeenLogged.compareAndSet(false, true)) {
                                info("gsh.a card renderer invoked; blur-gate argument index 16=" +
                                        (args.length > 16 ? args[16] : "missing"));
                            }
                            try {
                                boolean modified = false;
                                if (args.length > 8 && args[7] != null && args[8] != args[7]) {
                                    args[8] = args[7];
                                    modified = true;
                                    if (pullDownGridDetailsClickLogged.compareAndSet(false, true)) {
                                        info("Bound gsh.a tile clicks to the details callback instead of the camera callback");
                                    }
                                }
                                if (args.length > 16 && Boolean.TRUE.equals(args[16])) {
                                    args[16] = Boolean.FALSE;
                                    modified = true;
                                    if (pullDownGridCardUnblurLogged.compareAndSet(false, true)) {
                                        info("Disabled the gsh.a card blur gate at its local Compose rendering boundary");
                                    }
                                }
                                if (modified) return chain.proceed(args);
                                return chain.proceed();
                            } finally {
                                FeedPostMediaCache.endGridDetailComposition();
                            }
                        });
                info("Hooked the version-mapped gsh.a card renderer for local-only unblur");
            } catch (Throwable error) {
                error("Could not hook the gsh.a card renderer", error);
            }
        }

        Method gridMedia = symbols.getPullDownGridMediaComposableMethod();
        if (gridMedia != null) {
            try {
                Class<?> composerType = Class.forName("androidx.compose.runtime.Composer", false, classLoader);
                Class<?> functionType = Class.forName("ns8", false, classLoader);
                Class<?> disposableType = Class.forName("androidx.compose.runtime.DisposableEffectResult", false, classLoader);
                Map<String, Method> runtimeMethods = KnownMappings3970.composeRuntimeMethods(classLoader, symbols.getVersionName());
                if (!composeInjectionReady || runtimeMethods.isEmpty()) throw new NoSuchMethodException("Compose restart-scope methods");
                Method beginGroup = runtimeMethods.get("replaceStart");
                Method endGroup = runtimeMethods.get("replaceEnd");
                Method effect = runtimeMethods.get("effect");
                ComposeDownloadOverlaySymbols overlaySymbols = resolveComposeDownloadOverlaySymbols(classLoader);
                if (overlaySymbols == null) throw new NoSuchMethodException("Detail download Compose symbols");
                int composerIndex = -1;
                Class<?>[] mediaParameters = gridMedia.getParameterTypes();
                for (int i = 0; i < mediaParameters.length; i++) if (mediaParameters[i] == composerType) composerIndex = i;
                if (composerIndex < 0) throw new NoSuchMethodException("Detail media Composer parameter");
                final int detailComposerIndex = composerIndex;
                hook(gridMedia)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object[] args = chain.getArgs().toArray();
                            if (FeedPostMediaCache.hasPendingGridPostForDetail() &&
                                    pendingDetailMi6SeenLogged.compareAndSet(false, true)) {
                                info("Selected-grid detail entered the mi6.a media hook");
                            }
                            if (pullDownGridMediaSeenLogged.compareAndSet(false, true)) {
                                info("mi6.a media renderer invoked; isBlurred argument index 3=" +
                                        (args.length > 3 ? args[3] : "missing"));
                            }
                            Object selectedDetailPost = FeedPostMediaCache.composingGridDetailPost();
                            if (selectedDetailPost == null && args.length > 0) {
                                selectedDetailPost = FeedPostMediaCache.postForDualMedia(args[0]);
                            }
                            if (selectedDetailPost == null && args.length > 2) {
                                selectedDetailPost = FeedPostMediaCache.postForDualMedia(args[2]);
                            }
                            Object composer = args[detailComposerIndex];
                            if (args.length > 3 && Boolean.TRUE.equals(args[3])) {
                                args[3] = Boolean.FALSE;
                                if (pullDownGridMediaUnblurLogged.compareAndSet(false, true)) info("Disabled the mi6.a isBlurred gate at its local rendering boundary");
                            }
                            final Object detailPost = selectedDetailPost;
                            ComposeHookScope.Frame frame = ComposeHookScope.push(composer, () -> {
                                try {
                                    beginGroup.invoke(composer, 0x42524644);
                                    try {
                                        if (detailPost instanceof dev.tqmane.befuck.download.FeedPostMedia) {
                                            dev.tqmane.befuck.download.FeedPostMedia post = (dev.tqmane.befuck.download.FeedPostMedia) detailPost;
                                            Object callback = Proxy.newProxyInstance(classLoader, new Class<?>[]{functionType},
                                                    composeFunctionHandler(overlaySymbols, ignored -> {
                                                        BeFuckGalleryUi.DetailDownloadBinding binding = BeFuckGalleryUi.showDetailDownloadButton(post);
                                                        return Proxy.newProxyInstance(classLoader, new Class<?>[]{disposableType}, (proxy, method, values) -> {
                                                            if (method.getName().equals("dispose")) { BeFuckGalleryUi.disposeDetailDownloadButton(binding); return null; }
                                                            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                                                            if (method.getName().equals("equals")) return values != null && values.length == 1 && values[0] == proxy;
                                                            return "BeFuckDetailDownloadLifetime";
                                                        });
                                                    }));
                                            effect.invoke(null, post, callback, composer);
                                        }
                                    } finally { endGroup.invoke(composer); }
                                } catch (Throwable failure) { throw new IllegalStateException("Could not compose the detail download lifetime", failure); }
                            });
                            try { return chain.proceed(args); }
                            finally { ComposeHookScope.pop(frame); }
                        });
                info("Hooked the version-mapped mi6.a media renderer for local-only unblur");
            } catch (Throwable error) {
                error("Could not hook the mi6.a media renderer", error);
            }
        }
    }

    private void installHomeGridPostTileUnblurHook(ClassLoader classLoader, ResolvedSymbols symbols) {
        Method tileComposable = symbols == null ? null : symbols.getHomeGridPostTileComposableMethod();
        if (tileComposable == null) {
            info("Home-grid post unblur disabled: the version-mapped f3i tile composable was unresolved");
            return;
        }
        try {
            Class<?> tileClass = Class.forName("f3i", false, classLoader);
            Class<?> imageDataClass = Class.forName("zxl", false, classLoader);
            Class<?> badgeClass = Class.forName("u2i", false, classLoader);
            Field postIdField = tileClass.getDeclaredField("a");
            Field imageDataField = tileClass.getDeclaredField("b");
            Field nameField = tileClass.getDeclaredField("c");
            Field timeLabelField = tileClass.getDeclaredField("d");
            Field verifiedField = tileClass.getDeclaredField("e");
            Field blurredField = tileClass.getDeclaredField("f");
            Field badgeField = tileClass.getDeclaredField("g");
            Field[] tileFields = {postIdField, imageDataField, nameField, timeLabelField, verifiedField, blurredField, badgeField};
            for (Field field : tileFields) field.setAccessible(true);
            Constructor<?> tileConstructor = tileClass.getDeclaredConstructor(
                    String.class, imageDataClass, String.class, String.class,
                    Boolean.TYPE, Boolean.TYPE, badgeClass
            );
            tileConstructor.setAccessible(true);

            hook(tileComposable)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object[] args = chain.getArgs().toArray();
                        if (args.length > 0 && tileClass.isInstance(args[0])) {
                            Object tile = args[0];
                            boolean wasBlurred = blurredField.getBoolean(tile);
                            if (homeGridTileSeenLogged.compareAndSet(false, true)) {
                                info("Home-grid z89.b invoked; f3i.isBlurred=" + wasBlurred);
                            }
                            if (wasBlurred) {
                                Object[] copiedFields = new Object[tileFields.length];
                                for (int i = 0; i < tileFields.length; i++) {
                                    copiedFields[i] = tileFields[i].get(tile);
                                }
                                copiedFields[5] = Boolean.FALSE;
                                args[0] = tileConstructor.newInstance(copiedFields);
                                if (homeGridTileUnblurLogged.compareAndSet(false, true)) {
                                    info("Disabled the 3-column home-grid post tile blur at its local Compose rendering boundary");
                                }
                                return chain.proceed(args);
                            }
                        }
                        return chain.proceed();
                    });
            info("Hooked the version-mapped home-grid f3i tile renderer for local-only unblur");
            installHomeGridDetailsClickHook(classLoader);
        } catch (Throwable error) {
            error("Could not hook the home-grid post tile renderer", error);
        }
    }

    private void installHomeGridDetailsClickHook(ClassLoader classLoader) {
        try {
            Class<?> clickLambdaClass = Class.forName("d42", false, classLoader);
            Class<?> tileContainerClass = Class.forName("b99", false, classLoader);
            Class<?> tileModelClass = Class.forName("f3i", false, classLoader);
            Class<?> callbackClass = Class.forName("ps8", false, classLoader);
            Field branchField = clickLambdaClass.getDeclaredField("a");
            Field callbackField = clickLambdaClass.getDeclaredField("b");
            Field tileContainerField = clickLambdaClass.getDeclaredField("c");
            Field tileModelField = tileContainerClass.getDeclaredField("b");
            Field postIdField = tileModelClass.getDeclaredField("a");
            Field blurredField = tileModelClass.getDeclaredField("f");
            Field[] fields = {branchField, callbackField, tileContainerField, tileModelField, postIdField, blurredField};
            for (Field field : fields) field.setAccessible(true);
            Method callbackInvoke = callbackClass.getDeclaredMethod("invoke", Object.class, Object.class);
            callbackInvoke.setAccessible(true);
            Method invoke = clickLambdaClass.getDeclaredMethod("invoke");
            invoke.setAccessible(true);

            hook(invoke)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object clickLambda = chain.getThisObject();
                        if (clickLambda != null && branchField.getInt(clickLambda) == 12) {
                            Object tileContainer = tileContainerField.get(clickLambda);
                            Object tileModel = tileContainerClass.isInstance(tileContainer)
                                    ? tileModelField.get(tileContainer)
                                    : null;
                            if (tileModelClass.isInstance(tileModel)) {
                                Object callback = callbackField.get(clickLambda);
                                Object postId = postIdField.get(tileModel);
                                if (postId instanceof String) {
                                    FeedPostMediaCache.noteGridPostSelected((String) postId);
                                    if (homeGridDetailSelectionLogged.compareAndSet(false, true)) {
                                        info("Bound the selected home-grid post ID to its subsequent detail media composition");
                                    }
                                }
                                if (callback != null && postId instanceof String) {
                                    if (homeGridDetailsClickLogged.compareAndSet(false, true)) {
                                        info("Changed home-grid tile click to OnDetailsClicked with isBlurred=false");
                                    }
                                    try {
                                        return callbackInvoke.invoke(callback, postId, Boolean.FALSE);
                                    } catch (InvocationTargetException error) {
                                        throw error.getCause();
                                    }
                                }
                            }
                        }
                        return chain.proceed();
                    });
            info("Hooked the home-grid tile click event to route locally unblurred posts to Details, not Camera");
        } catch (Throwable error) {
            error("Could not hook the home-grid tile details click route", error);
        }
    }

    private void installFeedOptionsCanBlurHooks(ClassLoader classLoader, ResolvedSymbols symbols) {
        Method homeEmitter = symbols == null ? null : symbols.getHomeFeedItemEmitterMethod();
        Method friendsOfFriendsEmitter = symbols == null ? null : symbols.getFriendsOfFriendsFeedItemEmitterMethod();
        Method mapper = symbols == null ? null : symbols.getHomeFeedCanBlurMapperMethod();
        if ((homeEmitter == null && friendsOfFriendsEmitter == null) || mapper == null) {
            info("Feed unblur model hook disabled: feed emitter or canBlur mapper unresolved");
            return;
        }
        try {
            Class<?> optionsClass = Class.forName("wl7", false, classLoader);
            FeedBlurModelRewriter blurModelRewriter = resolveFeedBlurModelRewriter(classLoader);
            Field[] optionFields = new Field[8];
            Class<?>[] booleanParameters = new Class<?>[8];
            java.util.Arrays.fill(booleanParameters, Boolean.TYPE);
            for (int i = 0; i < optionFields.length; i++) {
                optionFields[i] = optionsClass.getDeclaredField(String.valueOf((char) ('a' + i)));
                optionFields[i].setAccessible(true);
            }
            Constructor<?> optionsConstructor = optionsClass.getDeclaredConstructor(booleanParameters);
            optionsConstructor.setAccessible(true);

            try {
                hook(optionsConstructor)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object[] args = chain.getArgs().toArray();
                            if (args.length >= 4) {
                                args[1] = Boolean.TRUE;  // allowedToSendMessage
                                args[2] = Boolean.TRUE;  // allowedToReactWithRealMoji
                                args[3] = Boolean.FALSE; // canBlur
                                if (universalOptionsUnblurredLogged.compareAndSet(false, true)) {
                                    info("Universally enabled allowedToSendMessage/allowedToReactWithRealMoji and disabled canBlur on FeedOptions");
                                }
                                return chain.proceed(args);
                            }
                            return chain.proceed();
                        });
                info("Hooked FeedOptions constructor for universal unblur and interactions");
            } catch (Throwable error) {
                error("Could not hook FeedOptions constructor", error);
            }

            try {
                Class<?> reactionsClass = Class.forName("lkg", false, classLoader);
                for (Constructor<?> c : reactionsClass.getDeclaredConstructors()) {
                    c.setAccessible(true);
                    hook(c)
                            .setPriority(XposedInterface.PRIORITY_HIGHEST)
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> {
                                Object[] args = chain.getArgs().toArray();
                                if (args.length >= 2) {
                                    args[0] = Boolean.TRUE; // canSendMessage
                                    args[1] = Boolean.TRUE; // canReactWithRealMoji
                                    if (universalReactionsEnabledLogged.compareAndSet(false, true)) {
                                        info("Universally enabled canSendMessage and canReactWithRealMoji on ReactionsUiState (lkg)");
                                    }
                                    return chain.proceed(args);
                                }
                                return chain.proceed();
                            });
                }
                info("Hooked ReactionsUiState (lkg) constructor for universal interactions");
            } catch (Throwable error) {
                error("Could not hook ReactionsUiState (lkg) constructor", error);
            }

            if (blurModelRewriter != null && blurModelRewriter.postConstructor != null) {
                try {
                    hook(blurModelRewriter.postConstructor)
                            .setPriority(XposedInterface.PRIORITY_HIGHEST)
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> {
                                Object[] args = chain.getArgs().toArray();
                                if (args.length > 5 && blurModelRewriter.blurredStateClass.isInstance(args[5])) {
                                    args[5] = blurModelRewriter.regularState;
                                    if (universalPostStateRegularizedLogged.compareAndSet(false, true)) {
                                        info("Universally regularized rm7 post constructor state from om7 to pm7");
                                    }
                                    return chain.proceed(args);
                                }
                                return chain.proceed();
                            });
                    info("Hooked rm7 post constructor for universal om7-to-pm7 state regularization");
                } catch (Throwable error) {
                    error("Could not hook rm7 post constructor", error);
                }
            }

            hookFeedModelMappingScope(homeEmitter, homeFeedModelMapping, "HomeFeed");
            hookFeedModelMappingScope(friendsOfFriendsEmitter, friendsOfFriendsModelMapping, "FriendsOfFriends");

            hook(mapper)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object[] args = chain.getArgs().toArray();
                        boolean inHomeFeed = Boolean.TRUE.equals(homeFeedModelMapping.get());
                        boolean inFriendsOfFriends = Boolean.TRUE.equals(friendsOfFriendsModelMapping.get());
                        boolean changed = false;
                        if (args.length > 1 && blurModelRewriter != null) {
                            try {
                                Object regularPost = blurModelRewriter.regularize(args[1]);
                                if (regularPost != args[1]) {
                                    args[1] = regularPost;
                                    changed = true;
                                    AtomicBoolean logged = inFriendsOfFriends
                                            ? friendsOfFriendsBlurredStateRegularizedLogged
                                            : inHomeFeed ? homeFeedBlurredStateRegularizedLogged
                                            : otherFeedBlurredStateRegularizedLogged;
                                    String feedName = inFriendsOfFriends ? "FriendsOfFriends"
                                            : inHomeFeed ? "HomeFeed" : "other feed route";
                                    if (logged.compareAndSet(false, true)) {
                                        info("Mapped " + feedName + " om7/Blurred post state to a local pm7/Regular state with empty reaction data");
                                    }
                                }
                            } catch (Throwable error) {
                                error("Could not regularize a local blurred feed-post model", error);
                            }
                        }
                        if (args.length > 9 &&
                                args[9] != null && optionsClass.isInstance(args[9])) {
                            try {
                                Object unblurredOptions = disableFeedCanBlur(
                                        args[9], optionsClass, optionFields, optionsConstructor
                                );
                                if (unblurredOptions != args[9]) {
                                    args[9] = unblurredOptions;
                                    changed = true;
                                    AtomicBoolean logged = inFriendsOfFriends
                                            ? friendsOfFriendsCanBlurDisabledLogged
                                            : inHomeFeed ? homeFeedCanBlurDisabledLogged : otherFeedCanBlurDisabledLogged;
                                    String feedName = inFriendsOfFriends ? "FriendsOfFriends"
                                            : inHomeFeed ? "HomeFeed" : "detail/other feed";
                                    if (logged.compareAndSet(false, true)) {
                                        info("Disabled FeedOptions.canBlur while mapping " + feedName +
                                                " items; post/reaction data remains intact");
                                    }
                                    return chain.proceed(args);
                                }
                            } catch (Throwable error) {
                                AtomicBoolean logged = inFriendsOfFriends
                                        ? friendsOfFriendsCanBlurDisabledLogged
                                        : inHomeFeed ? homeFeedCanBlurDisabledLogged : otherFeedCanBlurDisabledLogged;
                                String feedName = inFriendsOfFriends ? "FriendsOfFriends"
                                        : inHomeFeed ? "HomeFeed" : "detail/other feed";
                                if (logged.compareAndSet(false, true)) {
                                    error("Could not copy " + feedName + " FeedOptions with canBlur=false", error);
                                }
                            }
                        }
                        if (changed) return chain.proceed(args);
                        return chain.proceed();
                    });
            info("Hooked the 3.97.0 HomeFeed and FriendsOfFriends mappers for local canBlur=false and blurred-state normalization");
        } catch (Throwable error) {
            error("Could not install the local feed canBlur model hooks", error);
        }
    }

    private void hookFeedModelMappingScope(Method emitter, ThreadLocal<Boolean> scope, String feedName) {
        if (emitter == null) return;
        try {
            hook(emitter)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Boolean previous = scope.get();
                        scope.set(Boolean.TRUE);
                        try {
                            return chain.proceed();
                        } finally {
                            if (previous == null) scope.remove();
                            else scope.set(previous);
                        }
                    });
            info("Hooked " + feedName + " item emission to scope its canBlur mapping change");
        } catch (Throwable error) {
            error("Could not hook " + feedName + " item emission", error);
        }
    }

    private Object disableFeedCanBlur(
            Object options,
            Class<?> optionsClass,
            Field[] optionFields,
            Constructor<?> constructor
    ) throws Throwable {
        if (!optionsClass.isInstance(options)) return options;
        Object[] values = new Object[optionFields.length];
        for (int i = 0; i < optionFields.length; i++) {
            values[i] = optionFields[i].getBoolean(options);
        }
        if (Boolean.TRUE.equals(values[1]) && Boolean.TRUE.equals(values[2]) && Boolean.FALSE.equals(values[3])) {
            return options;
        }
        values[1] = Boolean.TRUE;
        values[2] = Boolean.TRUE;
        values[3] = Boolean.FALSE;
        return constructor.newInstance(values);
    }

    private static final class FeedBlurModelRewriter {
        final Class<?> postClass;
        final Class<?> blurredStateClass;
        final Field stateField;
        final Field[] postFields;
        final Constructor<?> postConstructor;
        final Object regularState;

        FeedBlurModelRewriter(
                Class<?> postClass,
                Class<?> blurredStateClass,
                Field stateField,
                Field[] postFields,
                Constructor<?> postConstructor,
                Object regularState
        ) {
            this.postClass = postClass;
            this.blurredStateClass = blurredStateClass;
            this.stateField = stateField;
            this.postFields = postFields;
            this.postConstructor = postConstructor;
            this.regularState = regularState;
        }

        Object regularize(Object post) throws Throwable {
            if (post == null || !postClass.isInstance(post) ||
                    !blurredStateClass.isInstance(stateField.get(post))) return post;
            Object[] values = new Object[postFields.length];
            for (int i = 0; i < postFields.length; i++) values[i] = postFields[i].get(post);
            values[5] = regularState;
            return postConstructor.newInstance(values);
        }
    }

    private FeedBlurModelRewriter resolveFeedBlurModelRewriter(ClassLoader classLoader) {
        try {
            Class<?> postClass = Class.forName("rm7", false, classLoader);
            Class<?> blurredStateClass = Class.forName("om7", false, classLoader);
            Class<?> regularStateClass = Class.forName("pm7", false, classLoader);
            Class<?> myRealmojisClass = Class.forName("c8e", false, classLoader);
            Class<?> realmojisClass = Class.forName("jpg", false, classLoader);
            Class<?> emptyListClass = Class.forName("ax6", false, classLoader);
            Field stateField = postClass.getDeclaredField("f");
            stateField.setAccessible(true);
            Field[] postFields = new Field[15];
            for (int i = 0; i < postFields.length; i++) {
                postFields[i] = postClass.getDeclaredField(String.valueOf((char) ('a' + i)));
                postFields[i].setAccessible(true);
            }
            Constructor<?> postConstructor = null;
            for (Constructor<?> candidate : postClass.getDeclaredConstructors()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (parameters.length == 15 && parameters[0] == String.class &&
                        parameters[1] == Boolean.TYPE && parameters[2].getName().equals("h45") &&
                        parameters[5].getName().equals("qm7")) {
                    postConstructor = candidate;
                    break;
                }
            }
            if (postConstructor == null) throw new NoSuchMethodException("rm7 full constructor");
            postConstructor.setAccessible(true);

            Field emptyListField = emptyListClass.getDeclaredField("a");
            emptyListField.setAccessible(true);
            Object emptyList = emptyListField.get(null);
            Constructor<?> myRealmojisConstructor = myRealmojisClass.getDeclaredConstructor(List.class, Class.forName("b8e", false, classLoader));
            Constructor<?> realmojisConstructor = realmojisClass.getDeclaredConstructor(Boolean.TYPE, Integer.TYPE, Integer.TYPE, List.class);
            Constructor<?> regularStateConstructor = regularStateClass.getDeclaredConstructor(myRealmojisClass, realmojisClass);
            myRealmojisConstructor.setAccessible(true);
            realmojisConstructor.setAccessible(true);
            regularStateConstructor.setAccessible(true);
            Object myRealmojis = myRealmojisConstructor.newInstance(emptyList, null);
            Object realmojis = realmojisConstructor.newInstance(Boolean.FALSE, 0, 0, emptyList);
            Object regularState = regularStateConstructor.newInstance(myRealmojis, realmojis);
            info("Resolved local om7-to-pm7 feed state rewriter");
            return new FeedBlurModelRewriter(postClass, blurredStateClass, stateField, postFields, postConstructor, regularState);
        } catch (Throwable error) {
            error("Could not resolve local om7-to-pm7 feed state rewriter", error);
            return null;
        }
    }

    private static final class ComposeDownloadOverlaySymbols {
        final Class<?> function1Class;
        final Method androidViewComposable;
        final Method zIndexModifier;
        final Object fillMaxSizeModifier;
        final Object fillMaxSizeTopmostModifier;
        final Object kotlinUnit;

        ComposeDownloadOverlaySymbols(
                Class<?> function1Class,
                Method androidViewComposable,
                Method zIndexModifier,
                Object fillMaxSizeModifier,
                Object fillMaxSizeTopmostModifier,
                Object kotlinUnit
        ) {
            this.function1Class = function1Class;
            this.androidViewComposable = androidViewComposable;
            this.zIndexModifier = zIndexModifier;
            this.fillMaxSizeModifier = fillMaxSizeModifier;
            this.fillMaxSizeTopmostModifier = fillMaxSizeTopmostModifier;
            this.kotlinUnit = kotlinUnit;
        }
    }

    private ComposeDownloadOverlaySymbols resolveComposeDownloadOverlaySymbols(ClassLoader classLoader) {
        try {
            Class<?> modifierClass = Class.forName("androidx.compose.ui.Modifier", false, classLoader);
            Class<?> composerClass = Class.forName("androidx.compose.runtime.Composer", false, classLoader);
            Class<?> function1Class = Class.forName("ns8", false, classLoader);
            Class<?> androidViewClass = Class.forName("androidx.compose.ui.viewinterop.AndroidView_androidKt", false, classLoader);
            Method androidViewComposable = null;
            for (Method method : androidViewClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers()) && method.getName().equals("a") &&
                        method.getReturnType() == Void.TYPE && parameters.length == 8 &&
                        parameters[0] == function1Class && parameters[1] == modifierClass &&
                        parameters[2] == function1Class && parameters[3] == function1Class &&
                        parameters[4] == function1Class && parameters[5] == composerClass &&
                        parameters[6] == Integer.TYPE && parameters[7] == Integer.TYPE) {
                    androidViewComposable = method;
                    break;
                }
            }
            if (androidViewComposable == null) throw new NoSuchMethodException("Compose AndroidView composable signature");
            androidViewComposable.setAccessible(true);

            Class<?> companionClass = Class.forName("androidx.compose.ui.Modifier$Companion", false, classLoader);
            Field companionField = companionClass.getDeclaredField("a");
            companionField.setAccessible(true);
            Object baseModifier = companionField.get(null);
            Class<?> sizeKt = Class.forName("androidx.compose.foundation.layout.SizeKt", false, classLoader);
            Method fillMaxSize = null;
            for (Method method : sizeKt.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers()) && method.getName().equals("f") &&
                        method.getReturnType() == modifierClass && parameters.length == 2 &&
                        parameters[0] == modifierClass && parameters[1] == Float.TYPE) {
                    fillMaxSize = method;
                    break;
                }
            }
            if (fillMaxSize == null) throw new NoSuchMethodException("Compose Modifier.fillMaxSize");
            fillMaxSize.setAccessible(true);
            Object fillMaxSizeModifier = fillMaxSize.invoke(null, baseModifier, 1.0f);

            Class<?> zIndexClass = Class.forName("androidx.compose.ui.ZIndexModifierKt", false, classLoader);
            Method zIndex = zIndexClass.getDeclaredMethod("a", modifierClass, Float.TYPE);
            zIndex.setAccessible(true);
            Object fillMaxSizeTopmostModifier = zIndex.invoke(null, fillMaxSizeModifier, Float.MAX_VALUE);

            Class<?> unitClass = Class.forName("bhn", false, classLoader);
            Field unitField = unitClass.getDeclaredField("a");
            unitField.setAccessible(true);
            Object kotlinUnit = unitField.get(null);
            return new ComposeDownloadOverlaySymbols(
                    function1Class, androidViewComposable, zIndex, fillMaxSizeModifier,
                    fillMaxSizeTopmostModifier, kotlinUnit
            );
        } catch (Throwable failure) {
            error("Compose AndroidView injection is unavailable; per-post download buttons are disabled", failure);
            return null;
        }
    }

    private void injectInlineDownloadOverlay(
            Object composer,
            ClassLoader classLoader,
            ComposeDownloadOverlaySymbols symbols,
            Object overlayModifier,
            Object post,
            boolean isDetail
    ) throws Throwable {
        if (composer == null || symbols == null || !symbols.androidViewComposable.getParameterTypes()[5].isInstance(composer)) return;
        dev.tqmane.befuck.download.FeedPostMedia feedPost =
                (dev.tqmane.befuck.download.FeedPostMedia) post;
        Object factory = Proxy.newProxyInstance(
                classLoader,
                new Class<?>[]{symbols.function1Class},
                composeFunctionHandler(symbols, (context) -> {
                    if (!(context instanceof Context)) return null;
                    View overlay = BeFuckGalleryUi.createInlineFeedDownloadOverlay(
                            (Context) context, moduleResources, feedPost, isDetail
                    );
                    if (isDetail) {
                        if (detailDownloadFactoryLogged.compareAndSet(false, true)) {
                            info("Detail AndroidView factory invoked; context=" + context.getClass().getName());
                        }
                        overlay.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                           oldLeft, oldTop, oldRight, oldBottom) -> {
                            if (detailDownloadViewLaidOutLogged.compareAndSet(false, true)) {
                                info("Detail download view bounds=" + left + "," + top + "-" + right + "," + bottom +
                                        ", attached=" + view.isAttachedToWindow());
                            }
                        });
                    }
                    return overlay;
                })
        );
        Object update = Proxy.newProxyInstance(
                classLoader,
                new Class<?>[]{symbols.function1Class},
                composeFunctionHandler(symbols, view -> {
                    if (view instanceof View) {
                        BeFuckGalleryUi.updateInlineFeedDownloadOverlay((View) view, feedPost);
                    }
                    return symbols.kotlinUnit;
                })
        );
        java.lang.reflect.InvocationHandler noOpHandler = composeFunctionHandler(symbols, ignored -> symbols.kotlinUnit);
        Object reset = Proxy.newProxyInstance(classLoader, new Class<?>[]{symbols.function1Class}, noOpHandler);
        Object release = Proxy.newProxyInstance(classLoader, new Class<?>[]{symbols.function1Class}, noOpHandler);
        symbols.androidViewComposable.invoke(
                null,
                factory,
                overlayModifier == null
                        ? symbols.fillMaxSizeTopmostModifier
                        : symbols.zIndexModifier.invoke(null, overlayModifier, Float.MAX_VALUE),
                reset,
                release,
                update,
                composer,
                0,
                0
        );
        if (inlineDownloadInjectionLogged.compareAndSet(false, true)) {
            info("Inserted the download control inside each rendered post-media Compose Box");
        }
    }

    private java.lang.reflect.InvocationHandler composeFunctionHandler(
            ComposeDownloadOverlaySymbols symbols,
            java.util.function.Function<Object, Object> invoke
    ) {
        return (proxy, method, args) -> {
            if (method.getName().equals("invoke")) {
                return invoke.apply(args == null || args.length == 0 ? null : args[0]);
            }
            if (method.getName().equals("toString")) return "BeFuckFeedDownloadComposable";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return args != null && args.length == 1 && proxy == args[0];
            return symbols.kotlinUnit;
        };
    }

    private void installCurrentPostMediaOrientationHook(ClassLoader classLoader) {
        try {
            Class<?> composerClass = Class.forName("androidx.compose.runtime.Composer", false, classLoader);
            Class<?> mediaRenderer = Class.forName("mi6", false, classLoader);
            Method orientationMethod = null;
            for (Method method : mediaRenderer.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers()) && method.getName().equals("c") &&
                        method.getReturnType() == Void.TYPE && parameters.length == 34 &&
                        parameters[0].getName().equals("zh6") && parameters[9] == Boolean.TYPE &&
                        parameters[30] == composerClass && parameters[31] == Integer.TYPE &&
                        parameters[32] == Integer.TYPE && parameters[33] == Integer.TYPE) {
                    orientationMethod = method;
                    break;
                }
            }
            if (orientationMethod == null) throw new NoSuchMethodException("mi6.c dual-media orientation renderer");
            hook(orientationMethod)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object[] args = chain.getArgs().toArray();
                        String postId = args.length > 0 ? FeedPostMediaCache.beginDualMediaComposition(args[0]) : null;
                        if (args.length > 9 && postId != null) {
                            // mi6.c's remembered state starts from this value; a separate eu callback tracks later taps.
                            FeedPostMediaCache.setComposingPrimaryDisplayed(!Boolean.TRUE.equals(args[9]));
                        }
                        try {
                            return chain.proceed();
                        } finally {
                            FeedPostMediaCache.endDualMediaComposition();
                        }
                    });

            Class<?> flipLambda = Class.forName("eu", false, classLoader);
            Class<?> mutableStateClass = Class.forName("androidx.compose.runtime.MutableState", false, classLoader);
            Constructor<?> flipConstructor = null;
            for (Constructor<?> constructor : flipLambda.getDeclaredConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 2 && parameters[0] == mutableStateClass && parameters[1] == Integer.TYPE) {
                    flipConstructor = constructor;
                    break;
                }
            }
            if (flipConstructor == null) throw new NoSuchMethodException("eu(MutableState, int)");
            Field callbackKindField = flipLambda.getDeclaredField("a");
            Field callbackStateField = flipLambda.getDeclaredField("b");
            callbackKindField.setAccessible(true);
            callbackStateField.setAccessible(true);
            Field selectedPostStateField = callbackStateField;
            Field selectedPostKindField = callbackKindField;
            hook(flipConstructor)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object[] args = chain.getArgs().toArray();
                        Object result = chain.proceed();
                        if (args.length == 2 && args[1] instanceof Integer && ((Integer) args[1]) == 27) {
                            FeedPostMediaCache.associateFlipStateWithComposingPost(args[0]);
                        }
                        return result;
                    });

            Method flipGetter = flipLambda.getDeclaredMethod("invoke");
            flipGetter.setAccessible(true);
            hook(flipGetter)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object callback = chain.getThisObject();
                        if (callback != null && selectedPostKindField.getInt(callback) == 27 && result instanceof Boolean) {
                            FeedPostMediaCache.updatePrimaryDisplayedFromFlipState(
                                    selectedPostStateField.get(callback),
                                    (Boolean) result
                            );
                        }
                        return result;
                    });
            info("Hooked mi6.c's current-side seed and its remembered tap-swap state for downloads");
        } catch (Throwable error) {
            error("Could not resolve the tap-swapped media selection; inline downloads will default to primary", error);
        }
    }

    private void installAdViewSuppression() {
        try {
            int hooked = 0;
            for (Method method : ViewGroup.class.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!"addView".equals(method.getName()) || parameters.length == 0 || parameters[0] != View.class) {
                    continue;
                }
                hook(method)
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object child = chain.getArg(0);
                            if (child instanceof View && isAdSdkView((View) child)) {
                                ((View) child).setVisibility(View.GONE);
                                if (adViewSuppressionLogged.compareAndSet(false, true)) {
                                    info("Suppressed an embedded ad view after it entered the Android view hierarchy");
                                }
                            }
                            return result;
                        });
                hooked++;
            }
            if (hooked == 0) throw new NoSuchMethodException("ViewGroup.addView(View, ...) methods unavailable");
            info("Hooked Android view insertion for BeReal and vendor ad-view suppression");
        } catch (Throwable error) {
            error("Could not install ad-view suppression", error);
        }
    }

    private boolean isAdSdkView(View view) {
        String name = view.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("advertsdata") || name.contains("sparkads") || name.contains("adsglobalpackage")) {
            return true;
        }
        return name.contains("com.applovin.mediation.ads.maxadview")
                || name.contains("com.applovin.mediation.nativeads.maxnativeadview")
                || name.contains("com.google.android.gms.ads.adview")
                || name.contains("com.google.android.gms.ads.nativead.nativeadview")
                || name.contains("com.google.android.gms.ads.nativead.mediaview")
                || name.contains("com.bytedance.sdk.openadsdk") && name.contains("adview")
                || name.contains("com.pangle") && name.contains("adview")
                || name.contains("io.adn.sdk") && (name.contains("nativead") || name.contains("adview"))
                || name.contains("net.pubnative.lite.sdk.views.hybidadview")
                || name.contains("com.vungle.ads") && name.contains("view")
                || name.contains("com.pubmatic.sdk") && name.contains("view")
                || name.contains("com.appharbr.sdk") && name.contains("view");
    }

    private Object createZeroSizeComposeModifier(ClassLoader classLoader) throws Exception {
        Class<?> modifierClass = Class.forName("androidx.compose.ui.Modifier", false, classLoader);
        Class<?> sizeElementClass = Class.forName("androidx.compose.foundation.layout.SizeElement", false, classLoader);
        java.lang.reflect.Constructor<?> sizeConstructor = null;
        for (java.lang.reflect.Constructor<?> candidate : sizeElementClass.getDeclaredConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length == 5
                    && parameters[0] == Float.TYPE
                    && parameters[1] == Float.TYPE
                    && parameters[2] == Float.TYPE
                    && parameters[3] == Float.TYPE
                    && parameters[4] == Boolean.TYPE) {
                sizeConstructor = candidate;
                break;
            }
        }
        if (sizeConstructor == null) throw new NoSuchMethodException("Compose SizeElement(float x4, boolean)");
        sizeConstructor.setAccessible(true);
        Object element = sizeConstructor.newInstance(0f, 0f, 0f, 0f, false);

        Class<?> companionClass = Class.forName("androidx.compose.ui.Modifier$Companion", false, classLoader);
        Field singletonField = null;
        for (Field field : companionClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && modifierClass.isAssignableFrom(field.getType())) {
                singletonField = field;
                break;
            }
        }
        if (singletonField == null) throw new NoSuchFieldException("Modifier.Companion singleton");
        singletonField.setAccessible(true);
        Object modifier = singletonField.get(null);
        Method append = null;
        for (Method candidate : modifierClass.getMethods()) {
            if (candidate.getParameterTypes().length == 1
                    && candidate.getParameterTypes()[0] == modifierClass
                    && candidate.getReturnType() == modifierClass) {
                append = candidate;
                break;
            }
        }
        if (append == null) throw new NoSuchMethodException("Modifier.then(Modifier)");
        return append.invoke(modifier, element);
    }

    private Method findActivityResultMethod(Class<?> activityClass) {
        Class<?> current = activityClass;
        while (current != null && Activity.class.isAssignableFrom(current)) {
            try {
                Method method = current.getDeclaredMethod(
                        "onActivityResult",
                        int.class,
                        int.class,
                        Intent.class
                );
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (Throwable error) {
                error("Could not resolve gallery result callback on " + current.getName(), error);
                return null;
            }
        }
        return null;
    }

    private Method findActivityPermissionResultMethod(Class<?> activityClass) {
        Class<?> current = activityClass;
        while (current != null && Activity.class.isAssignableFrom(current)) {
            try {
                Method method = current.getDeclaredMethod(
                        "onRequestPermissionsResult",
                        int.class,
                        String[].class,
                        int[].class
                );
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (Throwable error) {
                error("Could not resolve permission result callback on " + current.getName(), error);
                return null;
            }
        }
        return null;
    }

    private void installMedia3NetworkTypeReceiverHook(ClassLoader classLoader) {
        try {
            Class<?> observerClass = Class.forName(
                    "androidx.media3.common.util.NetworkTypeObserver",
                    false,
                    classLoader
            );
            Class<?> receiverClass = Class.forName(
                    "androidx.media3.common.util.NetworkTypeObserver$Receiver",
                    false,
                    classLoader
            );
            Method getInstance = observerClass.getDeclaredMethod("getInstance", Context.class);
            Method handleConnectivityChange = observerClass.getDeclaredMethod(
                    "handleConnectivityActionBroadcast",
                    Context.class
            );
            Field backgroundExecutor = observerClass.getDeclaredField("backgroundExecutor");
            Method onReceive = receiverClass.getDeclaredMethod("onReceive", Context.class, Intent.class);
            getInstance.setAccessible(true);
            handleConnectivityChange.setAccessible(true);
            backgroundExecutor.setAccessible(true);
            onReceive.setAccessible(true);

            hook(onReceive)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object contextArg = chain.getArg(0);
                        if (!(contextArg instanceof Context)) {
                            return null;
                        }
                        try {
                            Context context = (Context) contextArg;
                            Object observer = getInstance.invoke(null, context);
                            Object executor = backgroundExecutor.get(observer);
                            if (!(executor instanceof Executor)) {
                                throw new IllegalStateException(
                                        "Media3 NetworkTypeObserver executor unavailable"
                                );
                            }
                            ((Executor) executor).execute(() -> {
                                try {
                                    handleConnectivityChange.invoke(observer, context);
                                } catch (Throwable failure) {
                                    Throwable cause = failure instanceof InvocationTargetException
                                            && failure.getCause() != null
                                            ? failure.getCause()
                                            : failure;
                                    error("Media3 connectivity update failed without PairIP VMRunner", cause);
                                }
                            });
                            if (media3NetworkObserverDispatchLogged.compareAndSet(false, true)) {
                                info("Dispatched Media3 connectivity callback without PairIP VMRunner");
                            }
                        } catch (Throwable failure) {
                            Throwable cause = failure instanceof InvocationTargetException
                                    && failure.getCause() != null
                                    ? failure.getCause()
                                    : failure;
                            error("Could not dispatch Media3 connectivity callback without PairIP VMRunner", cause);
                        }
                        return null;
                    });
            info("Hooked Media3 NetworkTypeObserver receiver to its Java implementation");
        } catch (Throwable error) {
            error("Could not hook Media3 NetworkTypeObserver receiver", error);
        }
    }

    private void installConcurrentVideoSecondaryFrontSelection(
            ClassLoader classLoader,
            ResolvedSymbols symbols
    ) {
        try {
            Class<?> cameraViewModel = symbols == null ? null : symbols.getCameraViewModelClass();
            Class<?> facing = symbols == null ? null : symbols.getCameraFacingEnumClass();
            Method bindConcurrentCameras = symbols == null
                    ? null
                    : symbols.getCameraBindConcurrentMethod();
            if (cameraViewModel == null || facing == null || bindConcurrentCameras == null) {
                info("[SymbolResolver] Concurrent-camera preference hook disabled: required symbols unresolved");
                return;
            }
            Object back = null;
            for (Object value : facing.getEnumConstants()) {
                if (value instanceof Enum && "Back".equals(((Enum<?>) value).name())) {
                    back = value;
                    break;
                }
            }
            if (back == null) {
                throw new NoSuchFieldException(facing.getName() + ".Back");
            }

            Object rearPrimaryFacing = back;
            hook(bindConcurrentCameras)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object requestedFacing = chain.getArg(1);
                        info("Concurrent camera bind requested=" + requestedFacing
                                + "; using rear primary and selfie secondary");
                        return chain.proceed(new Object[]{chain.getArg(0), rearPrimaryFacing});
                    });
            info("Hooked concurrent-camera bind to use the selfie camera second");
        } catch (Throwable error) {
            error("Could not hook concurrent-camera selfie-camera ordering", error);
        }
    }

    private void installNullTraceSectionGuard() {
        try {
            Class<?> trace = Class.forName("android.os.Trace", false, null);
            Method beginSection = trace.getDeclaredMethod("beginSection", String.class);
            hook(beginSection)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        if (chain.getArg(0) == null) {
                            return chain.proceed(new Object[]{"BeRealTrace:unresolved"});
                        }
                        return chain.proceed();
                    });
            info("Hooked Android Trace.beginSection null-name guard");
        } catch (Throwable error) {
            error("Could not hook Android Trace.beginSection null-name guard", error);
        }
    }

    private void installComposeTracingContextGuard(ClassLoader classLoader) {
        try {
            Class<?> tracingContext = Class.forName(
                    "androidx.compose.runtime.TracingContext",
                    false,
                    classLoader
            );
            Class<?> unitClass = Class.forName("bhn", false, classLoader);
            Field unitField = unitClass.getDeclaredField("a");
            unitField.setAccessible(true);
            Object unit = unitField.get(null);
            Method beginTrace = tracingContext.getDeclaredMethod("z");
            hook(beginTrace)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Trace.beginSection("Compose:coroutineTracing");
                        return unit;
                    });
            info("Hooked Compose TracingContext null section name");
        } catch (Throwable error) {
            error("Could not hook Compose TracingContext null section name", error);
        }
    }

    private void restoreStaticStringIfNull(Field field, String symbol, String value) {
        try {
            field.setAccessible(true);
            RuntimeKnowledge.rememberRepair(field, value, symbol);
            if (field.get(null) == null) {
                field.set(null, value);
                info("Restored missing static string " + symbol + " via resolved Field "
                        + field.getDeclaringClass().getName() + "." + field.getName());
            }
        } catch (Throwable error) {
            error("Could not restore resolved string field " + symbol, error);
        }
    }

    private void installLocationAndCaptureDiagnostics(
            ClassLoader classLoader,
            ResolvedSymbols symbols
    ) {
        try {
            Method requestLocation = symbols == null ? null : symbols.getLocationRequestMethod();
            if (requestLocation == null) {
                info("[SymbolResolver] Location request diagnostics disabled: request method unresolved");
                return;
            }
            hook(requestLocation)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        info("Location request entered; fresh=" + chain.getArg(0));
                        try {
                            Object result = chain.proceed();
                            info("Location request returned " + summarizeLocationResult(result));
                            return result;
                        } catch (Throwable failure) {
                            error("Location request threw " + failure.getClass().getName(), failure);
                            throw failure;
                        }
                    });
            info("Hooked BeReal fused-location request");
        } catch (Throwable error) {
            error("Could not hook BeReal fused-location request", error);
        }

        try {
            Class<?> locationContinuation = Class.forName("pfb", false, classLoader);
            Method resume = locationContinuation.getDeclaredMethod("invokeSuspend", Object.class);
            hook(resume)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        try {
                            Object result = chain.proceed();
                            info("Location task completed " + summarizeLocationResult(result));
                            return result;
                        } catch (Throwable failure) {
                            error("Location task continuation threw " + failure.getClass().getName(), failure);
                            throw failure;
                        }
                    });
            info("Hooked BeReal location task completion");
        } catch (Throwable error) {
            error("Could not hook BeReal location task completion", error);
        }

        try {
            Class<?> geocoderContinuation = Class.forName("ofb", false, classLoader);
            Method geocode = geocoderContinuation.getDeclaredMethod("invokeSuspend", Object.class);
            hook(geocode)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        try {
                            Object result = chain.proceed();
                            info("Reverse geocoder completed " + summarizeLocationResult(result));
                            return result;
                        } catch (Throwable failure) {
                            error("Reverse geocoder threw " + failure.getClass().getName(), failure);
                            throw failure;
                        }
                    });
            info("Hooked BeReal reverse-geocoder completion");
        } catch (Throwable error) {
            error("Could not hook BeReal reverse-geocoder completion", error);
        }

        try {
            Class<?> cameraViewModel = Class.forName("uo2", false, classLoader);
            Class<?> uiEvent = Class.forName("p00", false, classLoader);
            Method publishUiEvent = cameraViewModel.getDeclaredMethod("U", uiEvent);
            hook(publishUiEvent)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object event = chain.getArg(0);
                        if (event != null) {
                            info("BeReal camera/post UI event: "
                                    + event.getClass().getSimpleName());
                        }
                        if (event != null && "ll2".equals(event.getClass().getSimpleName())) {
                            try {
                                Field message = event.getClass().getDeclaredField("b");
                                message.setAccessible(true);
                                info("BeReal camera/post error event: " + message.get(event));
                            } catch (Throwable ignored) {
                                info("BeReal camera/post error event received");
                            }
                        }
                        return chain.proceed();
                    });
            info("Hooked BeReal camera/post error events");
        } catch (Throwable error) {
            error("Could not hook BeReal camera/post error events", error);
        }

        installCameraLocationEventProbe(classLoader);
        installCameraShutterStateProbe(classLoader);
        installCameraShutterButtonProbe(classLoader);
        installMainCameraCaptureButtonProbe(classLoader);
        installMainCameraGateProbe(classLoader);
        installMainCameraLayoutProbe(classLoader);
        installMainCameraControlProbe(classLoader);
        installConcurrentVideoSessionRetry(classLoader);
        installCameraStateRegistryProbe(classLoader);
        installCurrentUserVerificationProbe(classLoader);
        installFusedLocationFallback(classLoader, symbols);
        installLocationPipelineProbe(classLoader);
        installCameraLocationStateProbe(classLoader);
        installLocationInputStateProbe(classLoader);
    }

    private void installCameraShutterStateProbe(ClassLoader classLoader) {
        try {
            Class<?> cameraUi = Class.forName("tai", false, classLoader);
            Method shutterComposable = null;
            for (Method method : cameraUi.getDeclaredMethods()) {
                if ("b".equals(method.getName())
                        && Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 9) {
                    shutterComposable = method;
                    break;
                }
            }
            if (shutterComposable == null) {
                throw new NoSuchMethodException("tai.b shutter composable");
            }
            hook(shutterComposable)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object state = chain.getArg(1);
                        String stateName = state == null
                                ? "null"
                                : state.getClass().getSimpleName();
                        if (!stateName.equals(lastCameraShutterState)) {
                            lastCameraShutterState = stateName;
                            info("Camera shutter state=" + stateName
                                    + ", enabled=" + "nwm".equals(stateName));
                        }
                        return chain.proceed();
                    });
            info("Hooked BeReal camera shutter state");
        } catch (Throwable error) {
            error("Could not hook BeReal camera shutter state", error);
        }
    }

    private void installCameraShutterButtonProbe(ClassLoader classLoader) {
        try {
            Class<?> cameraButtons = Class.forName("qzh", false, classLoader);
            Method buttonComposable = null;
            for (Method method : cameraButtons.getDeclaredMethods()) {
                if ("a".equals(method.getName())
                        && Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 10) {
                    buttonComposable = method;
                    break;
                }
            }
            if (buttonComposable == null) {
                throw new NoSuchMethodException("qzh.a composable button");
            }
            hook(buttonComposable)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object label = chain.getArg(4);
                        if ("shutter button".equals(label)) {
                            Object enabled = chain.getArg(0);
                            info("Camera shutter composable enabled=" + enabled);
                        }
                        return chain.proceed();
                    });
            info("Hooked BeReal camera shutter composable");
        } catch (Throwable error) {
            error("Could not hook BeReal camera shutter composable", error);
        }
    }

    private void installMainCameraCaptureButtonProbe(ClassLoader classLoader) {
        try {
            Class<?> captureButton = Class.forName("gql", false, classLoader);
            Method composeCaptureButton = null;
            for (Method method : captureButton.getDeclaredMethods()) {
                if ("a".equals(method.getName())
                        && Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 10) {
                    composeCaptureButton = method;
                    break;
                }
            }
            if (composeCaptureButton == null) {
                throw new NoSuchMethodException("gql.a camera capture button");
            }
            hook(composeCaptureButton)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object mode = chain.getArg(2);
                        Object captureState = chain.getArg(3);
                        String state = "mode=" + mode
                                + ", visible=" + chain.getArg(0)
                                + ", enabled=" + chain.getArg(5)
                                + ", captureState=" + (captureState == null
                                ? "null"
                                : captureState.getClass().getSimpleName());
                        if (!state.equals(lastMainCameraCaptureState)) {
                            lastMainCameraCaptureState = state;
                            info("Main camera capture button: " + state);
                        }
                        return chain.proceed();
                    });
            info("Hooked main BeReal camera capture button state");
        } catch (Throwable error) {
            error("Could not hook main BeReal camera capture button state", error);
        }
    }

    private void installCurrentUserVerificationProbe(ClassLoader classLoader) {
        try {
            Class<?> currentUser = Class.forName("hun", false, classLoader);
            Method isVerified = currentUser.getDeclaredMethod("x");
            hook(isVerified)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (result instanceof Boolean) {
                            Boolean verified = (Boolean) result;
                            if (!verified.equals(lastCurrentUserVerified)) {
                                lastCurrentUserVerified = verified;
                                info("Current-user isVerified state=" + verified);
                            }
                        }
                        return result;
                    });
            info("Hooked BeReal current-user verification flag");
        } catch (Throwable error) {
            error("Could not hook BeReal current-user verification flag", error);
        }
    }

    private void installMainCameraGateProbe(ClassLoader classLoader) {
        try {
            Class<?> cameraScreen = Class.forName("tl2", false, classLoader);
            Method composeCameraScreen = cameraScreen.getDeclaredMethod(
                    "b",
                    Class.forName("uo2", false, classLoader),
                    Class.forName("androidx.compose.runtime.Composer", false, classLoader),
                    int.class
            );
            hook(composeCameraScreen)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object viewModel = chain.getArg(0);
                        Object gateValue = null;
                        try {
                            Field gateFlow = viewModel.getClass().getDeclaredField("n0");
                            gateFlow.setAccessible(true);
                            Object flowState = gateFlow.get(viewModel);
                            Method getValue = flowState.getClass().getMethod("getValue");
                            gateValue = getValue.invoke(flowState);
                        } catch (Throwable ignored) {
                        }
                        String state = "rootCaptureGate=" + gateValue;
                        if (!state.equals(lastMainCameraGateState)) {
                            lastMainCameraGateState = state;
                            info("Main camera gate: " + state);
                        }
                        return chain.proceed();
                    });
            info("Hooked main BeReal camera capture gate");
        } catch (Throwable error) {
            error("Could not hook main BeReal camera capture gate", error);
        }
    }

    private void installMainCameraLayoutProbe(ClassLoader classLoader) {
        try {
            Class<?> cameraLayout = Class.forName("tl2", false, classLoader);
            Method composeCameraLayout = null;
            for (Method method : cameraLayout.getDeclaredMethods()) {
                if ("a".equals(method.getName())
                        && Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 17) {
                    composeCameraLayout = method;
                    break;
                }
            }
            if (composeCameraLayout == null) {
                throw new NoSuchMethodException("tl2.a camera layout");
            }
            hook(composeCameraLayout)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object cameraState = chain.getArg(1);
                        String state = "layoutEnabledArg=" + chain.getArg(11)
                                + ", cameraState=" + (cameraState == null
                                ? "null"
                                : cameraState.getClass().getSimpleName());
                        if (!state.equals(lastMainCameraLayoutState)) {
                            lastMainCameraLayoutState = state;
                            info("Main camera layout: " + state);
                        }
                        return chain.proceed();
                    });
            info("Hooked main BeReal camera layout state");
        } catch (Throwable error) {
            error("Could not hook main BeReal camera layout state", error);
        }
    }

    private void installMainCameraControlProbe(ClassLoader classLoader) {
        try {
            Class<?> cameraControls = Class.forName("vk2", false, classLoader);
            Method composeCameraControls = null;
            for (Method method : cameraControls.getDeclaredMethods()) {
                if ("a".equals(method.getName())
                        && Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 9) {
                    composeCameraControls = method;
                    break;
                }
            }
            if (composeCameraControls == null) {
                throw new NoSuchMethodException("vk2.a camera controls");
            }
            hook(composeCameraControls)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object preview = chain.getArg(1);
                        Object previewOptions = null;
                        Object mode = null;
                        boolean showControls = false;
                        try {
                            Field showField = preview.getClass().getDeclaredField("a");
                            showField.setAccessible(true);
                            showControls = showField.getBoolean(preview);
                            Field optionsField = preview.getClass().getDeclaredField("d");
                            optionsField.setAccessible(true);
                            previewOptions = optionsField.get(preview);
                            Field mandatoryField = previewOptions.getClass().getDeclaredField("a");
                            mandatoryField.setAccessible(true);
                            Object mandatory = mandatoryField.get(previewOptions);
                            Field modeField = mandatory.getClass().getDeclaredField("b");
                            modeField.setAccessible(true);
                            mode = modeField.get(mandatory);
                        } catch (Throwable ignored) {
                        }
                        Object captureState = chain.getArg(2);
                        String state = "mode=" + mode
                                + ", showControls=" + showControls
                                + ", captureEnabledArg=" + chain.getArg(4)
                                + ", captureState=" + (captureState == null
                                ? "null"
                                : captureState.getClass().getSimpleName());
                        if (!state.equals(lastMainCameraControlState)) {
                            lastMainCameraControlState = state;
                            info("Main camera controls: " + state);
                        }
                        return chain.proceed();
                    });
            info("Hooked main BeReal camera controls state");
        } catch (Throwable error) {
            error("Could not hook main BeReal camera controls state", error);
        }
    }

    private void installConcurrentVideoSessionRetry(ClassLoader classLoader) {
        try {
            Class<?> camera2 = Class.forName(
                    "androidx.camera.camera2.internal.Camera2CameraImpl",
                    false,
                    classLoader
            );
            Method createCaptureSession = camera2.getDeclaredMethod("E");
            hook(createCaptureSession)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object camera = chain.getThisObject();
                        logCameraXConcurrentState(camera, "before-session-create");
                        Object result = chain.proceed();
                        logCameraXConcurrentState(camera, "after-session-create");
                        if (camera == null || camera == retryingConcurrentCamera.get()) {
                            return result;
                        }
                        if (isConcurrentVideoMode(camera) && !isConcurrentSessionReady(camera)) {
                            scheduleConcurrentSessionRetry(camera, createCaptureSession, 1);
                        }
                        return result;
                    });
            info("Hooked CameraX concurrent capture-session readiness");
        } catch (Throwable error) {
            error("Could not hook CameraX concurrent capture-session readiness", error);
        }
    }

    private void installCameraStateRegistryProbe(ClassLoader classLoader) {
        try {
            Class<?> registryClass = Class.forName(
                    "androidx.camera.core.impl.CameraStateRegistry",
                    false,
                    classLoader
            );
            Method cameraReadiness = registryClass.getDeclaredMethod(
                    "i",
                    String.class,
                    String.class
            );
            hook(cameraReadiness)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object registry = chain.getThisObject();
                        Object result = chain.proceed();
                        if (Boolean.FALSE.equals(result)) {
                            try {
                                Field coordinatorField = registry.getClass().getDeclaredField("d");
                                coordinatorField.setAccessible(true);
                                Object coordinator = coordinatorField.get(registry);
                                Method modeMethod = coordinator.getClass().getMethod("d");
                                if (((Number) modeMethod.invoke(coordinator)).intValue() == 2) {
                                    String state = "camera=" + chain.getArg(0)
                                            + ", partner=" + chain.getArg(1)
                                            + ", concurrentRegistryReady=false";
                                    if (!state.equals(lastCameraXConcurrentState)) {
                                        lastCameraXConcurrentState = state;
                                        info("CameraX concurrent readiness: " + state);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        return result;
                    });
            info("Hooked CameraX concurrent camera-state readiness");
        } catch (Throwable error) {
            error("Could not hook CameraX concurrent camera-state readiness", error);
        }
    }

    private void logCameraXConcurrentState(Object camera, String phase) {
        if (camera == null) {
            return;
        }
        try {
            Field coordinatorField = camera.getClass().getDeclaredField("t");
            coordinatorField.setAccessible(true);
            Object coordinator = coordinatorField.get(camera);
            Method modeMethod = coordinator.getClass().getMethod("d");
            int mode = ((Number) modeMethod.invoke(coordinator)).intValue();
            if (mode != 2) {
                return;
            }
            Field infoField = camera.getClass().getDeclaredField("j");
            infoField.setAccessible(true);
            Object cameraInfo = infoField.get(camera);
            Field idField = cameraInfo.getClass().getDeclaredField("a");
            idField.setAccessible(true);
            String cameraId = String.valueOf(idField.get(cameraInfo));
            String partnerId = String.valueOf(
                    coordinator.getClass().getMethod("c", String.class)
                            .invoke(coordinator, cameraId)
            );
            Field stateField = camera.getClass().getDeclaredField("e");
            stateField.setAccessible(true);
            Object internalState = stateField.get(camera);
            String state = phase + ": camera=" + cameraId
                    + ", partner=" + partnerId
                    + ", internalState=" + internalState
                    + ", registryReady=" + isConcurrentSessionReady(camera);
            if (!state.equals(lastCameraXConcurrentState)) {
                lastCameraXConcurrentState = state;
                info("CameraX concurrent session state: " + state);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isConcurrentVideoMode(Object camera) {
        try {
            Field coordinatorField = camera.getClass().getDeclaredField("t");
            coordinatorField.setAccessible(true);
            Object coordinator = coordinatorField.get(camera);
            Method operatingMode = coordinator.getClass().getMethod("d");
            return ((Number) operatingMode.invoke(coordinator)).intValue() == 2;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isConcurrentSessionReady(Object camera) {
        try {
            Field infoField = camera.getClass().getDeclaredField("j");
            infoField.setAccessible(true);
            Object cameraInfo = infoField.get(camera);
            Field idField = cameraInfo.getClass().getDeclaredField("a");
            idField.setAccessible(true);
            String cameraId = (String) idField.get(cameraInfo);

            Field coordinatorField = camera.getClass().getDeclaredField("t");
            coordinatorField.setAccessible(true);
            Object coordinator = coordinatorField.get(camera);
            Method partnerMethod = coordinator.getClass().getMethod("c", String.class);
            String partnerId = (String) partnerMethod.invoke(coordinator, cameraId);

            Field registryField = camera.getClass().getDeclaredField("u");
            registryField.setAccessible(true);
            Object registry = registryField.get(camera);
            Method readyMethod = registry.getClass().getMethod("i", String.class, String.class);
            return Boolean.TRUE.equals(readyMethod.invoke(registry, cameraId, partnerId));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void scheduleConcurrentSessionRetry(Object camera, Method createCaptureSession, int attempt) {
        if (attempt > 5) {
            info("CameraX concurrent session stayed unready after retries");
            return;
        }
        synchronized (pendingConcurrentSessionRetries) {
            if (Boolean.TRUE.equals(pendingConcurrentSessionRetries.get(camera))) {
                return;
            }
            pendingConcurrentSessionRetries.put(camera, Boolean.TRUE);
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            synchronized (pendingConcurrentSessionRetries) {
                pendingConcurrentSessionRetries.remove(camera);
            }
            if (!isConcurrentVideoMode(camera)) {
                return;
            }

            try {
                Field stateField = camera.getClass().getDeclaredField("e");
                stateField.setAccessible(true);
                Object cameraState = stateField.get(camera);
                if (!(cameraState instanceof Enum)
                        || !"OPENED".equals(((Enum<?>) cameraState).name())) {
                    return;
                }

                if (!isConcurrentSessionReady(camera)) {
                    scheduleConcurrentSessionRetry(camera, createCaptureSession, attempt + 1);
                    return;
                }

                Field executorField = camera.getClass().getDeclaredField("c");
                executorField.setAccessible(true);
                Executor cameraExecutor = (Executor) executorField.get(camera);
                cameraExecutor.execute(() -> {
                    try {
                        retryingConcurrentCamera.set(camera);
                        createCaptureSession.invoke(camera);
                        info("Retried CameraX session after both concurrent cameras became ready");
                    } catch (Throwable error) {
                        error("CameraX concurrent capture-session retry failed", error);
                    } finally {
                        retryingConcurrentCamera.remove();
                    }
                });
            } catch (Throwable error) {
                error("Could not inspect CameraX concurrent capture-session state", error);
            }
        }, 300L);
    }

    private void installCameraLocationEventProbe(ClassLoader classLoader) {
        try {
            Class<?> cameraViewModel = Class.forName("uo2", false, classLoader);
            Class<?> cameraAction = Class.forName("kk2", false, classLoader);
            Class<?> cameraActionKind = Class.forName("qf2", false, classLoader);
            Field locationAction = cameraActionKind.getDeclaredField("b");
            locationAction.setAccessible(true);
            Object locationActionValue = locationAction.get(null);

            Method dispatchAction = cameraViewModel.getDeclaredMethod("T", cameraAction);
            hook(dispatchAction)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object event = chain.getArg(0);
                        String eventName = event == null ? "null" : event.getClass().getName();
                        String actionName = eventName;
                        String simpleName = event == null
                                ? "null"
                                : event.getClass().getSimpleName();
                        if (event != null && "mj2".equals(simpleName)) {
                            try {
                                Field kind = event.getClass().getDeclaredField("a");
                                kind.setAccessible(true);
                                Object value = kind.get(event);
                                actionName = locationActionValue.equals(value)
                                        ? "Location"
                                        : "other-camera-control";
                            } catch (Throwable ignored) {
                                actionName = "other-camera-control";
                            }
                        } else if (event != null && "uj2".equals(simpleName)) {
                            try {
                                Field option = event.getClass().getDeclaredField("a");
                                option.setAccessible(true);
                                Object optionValue = option.get(event);
                                Field mode = optionValue.getClass().getDeclaredField("d");
                                mode.setAccessible(true);
                                actionName = "Location option selected: "
                                        + summarizeLocationMode(mode.get(optionValue));
                            } catch (Throwable ignored) {
                                actionName = "Location option selected";
                            }
                        } else if (event != null && "tj2".equals(simpleName)) {
                            actionName = "Hide location sheet";
                        }
                        info("Camera control event: " + actionName);
                        return chain.proceed();
                    });
            info("Hooked BeReal camera location-control events");
        } catch (Throwable error) {
            error("Could not hook BeReal camera location-control events", error);
        }
    }

    private void installFusedLocationFallback(ClassLoader classLoader, ResolvedSymbols symbols) {
        try {
            Method getFusedClient = symbols == null ? null : symbols.getLocationClientGetter();
            if (getFusedClient == null) {
                info("[SymbolResolver] FusedLocation fallback hook disabled: client getter unresolved");
                return;
            }
            hook(getFusedClient)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object client = chain.proceed();
                        if (client != null) {
                            info("BeReal resolved its FusedLocation client: "
                                    + client.getClass().getName());
                            installFusedLocationApiHooks(client);
                        } else {
                            info("BeReal resolved a null FusedLocation client");
                        }
                        return client;
                    });
            info("Hooked BeReal FusedLocation client resolution");
        } catch (Throwable error) {
            error("Could not hook BeReal FusedLocation client resolution", error);
        }
    }

    private synchronized void installFusedLocationApiHooks(Object client) {
        if (fusedLocationApiHooksInstalled) {
            return;
        }
        boolean anyHooked = false;
        for (Method method : client.getClass().getMethods()) {
            String name = method.getName();
            if (!"getCurrentLocation".equals(name)
                    && !"getLastLocation".equals(name)
                    && !"getLocationAvailability".equals(name)) {
                continue;
            }
            try {
                hook(method)
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            info("FusedLocation API entered: " + name);
                            try {
                            Object result = chain.proceed();
                            info("FusedLocation API returned: " + name + " -> "
                                    + summarizeLocationResult(result));
                            watchFusedLocationTask(result, name);
                            if ("getCurrentLocation".equals(name)
                                    || "getLastLocation".equals(name)) {
                                return bridgeFusedLocationTask(result, name);
                            }
                            return result;
                            } catch (Throwable failure) {
                                error("FusedLocation API threw: " + name + " -> "
                                        + failure.getClass().getName(), failure);
                                throw failure;
                            }
                        });
                anyHooked = true;
                info("Hooked FusedLocation API method " + method.getDeclaringClass().getName()
                        + "." + name);
            } catch (Throwable error) {
                error("Could not hook FusedLocation API method "
                        + method.getDeclaringClass().getName() + "." + name, error);
            }
        }
        fusedLocationApiHooksInstalled = anyHooked;
        if (!anyHooked) {
            info("Resolved FusedLocation client exposes no hookable request methods");
        }
    }

    private void watchFusedLocationTask(Object task, String apiName) {
        if (task == null) {
            info("FusedLocation task is null: " + apiName);
            return;
        }
        try {
            ClassLoader taskLoader = task.getClass().getClassLoader();
            Class<?> taskApi = Class.forName("com.google.android.gms.tasks.Task", false, taskLoader);
            Class<?> listenerApi = Class.forName(
                    "com.google.android.gms.tasks.OnCompleteListener",
                    false,
                    taskLoader
            );
            InvocationHandler observer = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("onComplete".equals(method.getName()) && args != null && args.length == 1) {
                        Object completedTask = args[0];
                        try {
                            boolean successful = ((Boolean) taskApi
                                    .getMethod("isSuccessful")
                                    .invoke(completedTask)).booleanValue();
                            boolean canceled = ((Boolean) taskApi
                                    .getMethod("isCanceled")
                                    .invoke(completedTask)).booleanValue();
                            Object failure = taskApi.getMethod("getException").invoke(completedTask);
                            Object result = successful
                                    ? taskApi.getMethod("getResult").invoke(completedTask)
                                    : null;
                            String resultType = result == null
                                    ? "null"
                                    : result.getClass().getName();
                            String failureType = failure == null
                                    ? "null"
                                    : failure.getClass().getName();
                            if (failure != null) {
                                try {
                                    Field statusField = failure.getClass().getDeclaredField("a");
                                    statusField.setAccessible(true);
                                    Object status = statusField.get(failure);
                                    Field statusCodeField = status.getClass().getDeclaredField("a");
                                    statusCodeField.setAccessible(true);
                                    Object statusCode = statusCodeField.get(status);
                                    failureType += "(statusCode=" + statusCode + ")";
                                } catch (Throwable ignored) {
                                    // Not all task failures expose a Google Play Services status code.
                                }
                            }
                            String availability = "";
                            if (result != null
                                    && result.getClass().getName().endsWith("LocationAvailability")) {
                                try {
                                    Object available = result.getClass()
                                            .getMethod("isLocationAvailable")
                                            .invoke(result);
                                    availability = ", available=" + available;
                                } catch (Throwable ignored) {
                                    availability = ", available=unreadable";
                                }
                            }
                            info("FusedLocation task completed: " + apiName
                                    + ", successful=" + successful
                                    + ", canceled=" + canceled
                                    + ", resultType=" + resultType
                                    + ", failureType=" + failureType
                                    + availability);
                        } catch (Throwable inspectionError) {
                            error("Could not inspect FusedLocation task completion for " + apiName,
                                    inspectionError);
                        }
                        return null;
                    }
                    if ("toString".equals(method.getName())) {
                        return "BeRealLocationTaskObserver";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return Integer.valueOf(System.identityHashCode(proxy));
                    }
                    if ("equals".equals(method.getName())) {
                        return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
                    }
                    return null;
                }
            };
            Object listener = Proxy.newProxyInstance(
                    listenerApi.getClassLoader(),
                    new Class<?>[]{listenerApi},
                    observer
            );
            Method addListener = taskApi.getMethod("addOnCompleteListener", listenerApi);
            addListener.invoke(task, listener);
            info("Attached FusedLocation task observer: " + apiName);
        } catch (Throwable error) {
            error("Could not attach FusedLocation task observer for " + apiName, error);
        }
    }

    private Object bridgeFusedLocationTask(Object originalTask, String apiName) {
        if (originalTask == null) {
            return null;
        }
        try {
            ClassLoader taskLoader = originalTask.getClass().getClassLoader();
            Class<?> taskApi = Class.forName("com.google.android.gms.tasks.Task", false, taskLoader);
            Class<?> listenerApi = Class.forName(
                    "com.google.android.gms.tasks.OnCompleteListener",
                    false,
                    taskLoader
            );
            Class<?> taskSourceApi = Class.forName(
                    "com.google.android.gms.tasks.TaskCompletionSource",
                    true,
                    taskLoader
            );
            Object taskSource = taskSourceApi.getDeclaredConstructor().newInstance();
            Object bridgedTask = taskSourceApi.getMethod("getTask").invoke(taskSource);
            AtomicBoolean completed = new AtomicBoolean(false);

            InvocationHandler bridge = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if (!"onComplete".equals(method.getName()) || args == null || args.length != 1) {
                        if ("toString".equals(method.getName())) {
                            return "BeRealLocationFallbackBridge";
                        }
                        if ("hashCode".equals(method.getName())) {
                            return Integer.valueOf(System.identityHashCode(proxy));
                        }
                        if ("equals".equals(method.getName())) {
                            return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
                        }
                        return null;
                    }

                    Object finishedTask = args[0];
                    try {
                        boolean successful = ((Boolean) taskApi
                                .getMethod("isSuccessful")
                                .invoke(finishedTask)).booleanValue();
                        boolean canceled = ((Boolean) taskApi
                                .getMethod("isCanceled")
                                .invoke(finishedTask)).booleanValue();
                        Object failure = taskApi.getMethod("getException").invoke(finishedTask);
                        Object result = successful
                                ? taskApi.getMethod("getResult").invoke(finishedTask)
                                : null;

                        if (successful && result != null) {
                            completeFallbackTask(taskSource, taskSourceApi, completed,
                                    (Location) result, null, apiName);
                            return null;
                        }
                        if (canceled) {
                            completeFallbackTask(taskSource, taskSourceApi, completed,
                                    null, new CancellationException("FusedLocation request canceled"), apiName);
                            return null;
                        }

                        Integer statusCode = googleApiStatusCode(failure);
                        if ((statusCode != null && statusCode.intValue() == 17)
                                || (successful && result == null)) {
                            info("FusedLocation " + apiName
                                    + " unavailable; trying Android LocationManager");
                            requestAndroidLocationFallback(
                                    taskSource,
                                    taskSourceApi,
                                    completed,
                                    apiName
                            );
                            return null;
                        }

                        if (failure instanceof Throwable) {
                            completeFallbackTask(taskSource, taskSourceApi, completed,
                                    null, (Throwable) failure, apiName);
                        } else {
                            completeFallbackTask(taskSource, taskSourceApi, completed,
                                    null, new IllegalStateException("FusedLocation failed"), apiName);
                        }
                    } catch (Throwable inspectionError) {
                        error("Could not bridge FusedLocation task " + apiName, inspectionError);
                        completeFallbackTask(taskSource, taskSourceApi, completed,
                                null, inspectionError, apiName);
                    }
                    return null;
                }
            };

            Object listener = Proxy.newProxyInstance(
                    listenerApi.getClassLoader(),
                    new Class<?>[]{listenerApi},
                    bridge
            );
            taskApi.getMethod("addOnCompleteListener", listenerApi)
                    .invoke(originalTask, listener);
            info("Bridging FusedLocation task with native fallback: " + apiName);
            return bridgedTask;
        } catch (Throwable error) {
            error("Could not bridge FusedLocation task " + apiName, error);
            return originalTask;
        }
    }

    private Integer googleApiStatusCode(Object failure) {
        if (failure == null) {
            return null;
        }
        try {
            Field statusField = failure.getClass().getDeclaredField("a");
            statusField.setAccessible(true);
            Object status = statusField.get(failure);
            if (status == null) {
                return null;
            }
            Field codeField = status.getClass().getDeclaredField("a");
            codeField.setAccessible(true);
            Object code = codeField.get(status);
            return code instanceof Number ? Integer.valueOf(((Number) code).intValue()) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void requestAndroidLocationFallback(
            Object taskSource,
            Class<?> taskSourceApi,
            AtomicBoolean completed,
            String apiName
    ) {
        try {
            Context context = applicationContext;
            if (context == null) {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
                currentApplication.setAccessible(true);
                Object current = currentApplication.invoke(null);
                if (current instanceof Context) {
                    context = (Context) current;
                }
            }
            if (context == null) {
                completeFallbackTask(taskSource, taskSourceApi, completed, null,
                        new IllegalStateException("Application context unavailable"), apiName);
                return;
            }

            LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (manager == null) {
                completeFallbackTask(taskSource, taskSourceApi, completed, null,
                        new IllegalStateException("LocationManager unavailable"), apiName);
                return;
            }

            if ("getLastLocation".equals(apiName)) {
                Location lastKnown = findLastKnownLocation(manager);
                completeFallbackTask(taskSource, taskSourceApi, completed, lastKnown, null, apiName);
                return;
            }

            ArrayList<String> providers = new ArrayList<>();
            List<String> enabledProviders = manager.getProviders(true);
            for (String preferred : new String[]{"network", "gps", "fused"}) {
                if (enabledProviders.contains(preferred) && !providers.contains(preferred)) {
                    providers.add(preferred);
                }
            }
            for (String provider : enabledProviders) {
                if (!"passive".equals(provider) && !providers.contains(provider)) {
                    providers.add(provider);
                }
            }

            if (providers.isEmpty()) {
                Location lastKnown = findLastKnownLocation(manager);
                completeFallbackTask(taskSource, taskSourceApi, completed, lastKnown, null, apiName);
                info("Android LocationManager fallback found no enabled providers");
                return;
            }

            Class<?> cancellationSignal = Class.forName("android.os.CancellationSignal");
            Class<?> executorClass = Class.forName("java.util.concurrent.Executor");
            Class<?> consumerClass = Class.forName("java.util.function.Consumer");
            Method getCurrentLocation = LocationManager.class.getMethod(
                    "getCurrentLocation",
                    String.class,
                    cancellationSignal,
                    executorClass,
                    consumerClass
            );
            AtomicInteger pendingProviders = new AtomicInteger(providers.size());
            Handler timeoutHandler = new Handler(Looper.getMainLooper());
            Runnable timeout = () -> {
                if (!completed.get()) {
                    Location lastKnown = findLastKnownLocation(manager);
                    completeFallbackTask(taskSource, taskSourceApi, completed,
                            lastKnown, null, apiName);
                }
            };
            timeoutHandler.postDelayed(timeout, 20000L);

            Executor directExecutor = Runnable::run;
            Consumer<Location> locationConsumer = location -> {
                if (location != null) {
                    completeFallbackTask(taskSource, taskSourceApi, completed,
                            location, null, apiName);
                    return;
                }
                if (pendingProviders.decrementAndGet() <= 0) {
                    Location lastKnown = findLastKnownLocation(manager);
                    completeFallbackTask(taskSource, taskSourceApi, completed,
                            lastKnown, null, apiName);
                }
            };

            info("Android LocationManager fallback requesting current fix from "
                    + providers.size() + " enabled provider(s)");
            for (String provider : providers) {
                try {
                    getCurrentLocation.invoke(manager, provider, null, directExecutor, locationConsumer);
                } catch (Throwable providerError) {
                    if (pendingProviders.decrementAndGet() <= 0) {
                        Location lastKnown = findLastKnownLocation(manager);
                        completeFallbackTask(taskSource, taskSourceApi, completed,
                                lastKnown, null, apiName);
                    }
                }
            }
        } catch (Throwable error) {
            error("Android LocationManager fallback failed for " + apiName
                    + ": " + error.getClass().getName(), error);
            completeFallbackTask(taskSource, taskSourceApi, completed, null, error, apiName);
        }
    }

    private Location findLastKnownLocation(LocationManager manager) {
        for (String provider : new String[]{"network", "gps", "passive", "fused"}) {
            try {
                Location location = manager.getLastKnownLocation(provider);
                if (location != null) {
                    return location;
                }
            } catch (SecurityException denied) {
                // Permission may be revoked while the asynchronous fallback is running.
                return null;
            } catch (Throwable ignored) {
                // Continue through providers without logging location data.
            }
        }
        return null;
    }

    private void completeFallbackTask(
            Object taskSource,
            Class<?> taskSourceApi,
            AtomicBoolean completed,
            Location location,
            Throwable failure,
            String apiName
    ) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (failure != null) {
                Exception exception = failure instanceof Exception
                        ? (Exception) failure
                        : new Exception(failure);
                taskSourceApi.getMethod("setException", Exception.class)
                        .invoke(taskSource, exception);
                error("Location fallback completed with " + failure.getClass().getName(), failure);
                return;
            }
            taskSourceApi.getMethod("setResult", Object.class).invoke(taskSource, location);
            info("Location fallback completed: " + apiName + "; resultType="
                    + (location == null ? "null" : location.getClass().getName()));
        } catch (Throwable completionError) {
            error("Could not complete native Location fallback task", completionError);
        }
    }

    private void installLocationPipelineProbe(ClassLoader classLoader) {
        try {
            Class<?> locationMode = Class.forName("hjl", false, classLoader);
            Class<?> continuation = Class.forName("wx4", false, classLoader);
            Class<?> callback = Class.forName("js8", false, classLoader);

            installLocationMethodProbe(
                    classLoader,
                    "tlg",
                    "c",
                    new Class<?>[]{locationMode, callback, continuation},
                    "Camera location selection",
                    0,
                    -1
            );
            installLocationMethodProbe(
                    classLoader,
                    "fjf",
                    "a",
                    new Class<?>[]{locationMode, continuation},
                    "Camera location permission check",
                    0,
                    -1
            );
            installLocationMethodProbe(
                    classLoader,
                    "fwj",
                    "a",
                    new Class<?>[]{locationMode, boolean.class, continuation},
                    "Camera location fetch",
                    0,
                    1
            );

            installLocationContinuationProbe(classLoader, "rlg", "Camera location selection");
            installLocationContinuationProbe(classLoader, "ejf", "Camera location permission check");
            installLocationContinuationProbe(classLoader, "dwj", "Camera location fetch");
        } catch (Throwable error) {
            error("Could not install Camera location pipeline probes", error);
        }
    }

    private void installCameraLocationStateProbe(ClassLoader classLoader) {
        try {
            Class<?> locationState = Class.forName("qgb", false, classLoader);
            Class<?> continuation = Class.forName("vx4", false, classLoader);
            Class<?> cameraStateCollector = Class.forName("pc4", false, classLoader);
            Method emit = cameraStateCollector.getDeclaredMethod(
                    "emit",
                    Object.class,
                    continuation
            );
            hook(emit)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object state = chain.getArg(0);
                        if (state != null && locationState.isInstance(state)) {
                            try {
                                Field mode = locationState.getDeclaredField("a");
                                Field userLocation = locationState.getDeclaredField("b");
                                Field allowPrecise = locationState.getDeclaredField("c");
                                mode.setAccessible(true);
                                userLocation.setAccessible(true);
                                allowPrecise.setAccessible(true);
                                info("Camera location state emitted: mode="
                                        + summarizeLocationMode(mode.get(state))
                                        + ", hasLocation=" + (userLocation.get(state) != null)
                                        + ", allowPrecise=" + allowPrecise.getBoolean(state));
                            } catch (Throwable inspectionError) {
                                error("Could not inspect Camera location state", inspectionError);
                            }
                        }
                        return chain.proceed();
                    });
            info("Hooked Camera location state emissions");
        } catch (Throwable error) {
            error("Could not hook Camera location state emissions", error);
        }

        try {
            Class<?> locationFlow = Class.forName("s0f", false, classLoader);
            Method createFlow = locationFlow.getDeclaredMethod("b");
            hook(createFlow)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        info("Camera location flow requested");
                        Object result = chain.proceed();
                        info("Camera location flow returned "
                                + (result == null ? "null" : result.getClass().getName()));
                        return result;
                    });
            info("Hooked Camera location flow creation");
        } catch (Throwable error) {
            error("Could not hook Camera location flow creation", error);
        }

        installLocationContinuationProbe(classLoader, "r0f", "Camera location state mapping");
    }

    private void installLocationInputStateProbe(ClassLoader classLoader) {
        try {
            Class<?> mapper = Class.forName("on2", false, classLoader);
            Method mapLocationState = mapper.getDeclaredMethod(
                    "invoke",
                    Object.class,
                    Object.class,
                    Object.class,
                    Object.class
            );
            hook(mapLocationState)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object source = chain.getArg(0);
                        String sourceName = source == null
                                ? "null"
                                : source.getClass().getSimpleName();
                        if ("nf5".equals(sourceName) || "of5".equals(sourceName)) {
                            Object permissions = chain.getArg(1);
                            String permissionState = "unreadable";
                            if (permissions != null) {
                                try {
                                    Field approximate = permissions.getClass().getDeclaredField("a");
                                    Field precise = permissions.getClass().getDeclaredField("b");
                                    Field any = permissions.getClass().getDeclaredField("c");
                                    approximate.setAccessible(true);
                                    precise.setAccessible(true);
                                    any.setAccessible(true);
                                    permissionState = "approx=" + approximate.getBoolean(permissions)
                                            + ", precise=" + precise.getBoolean(permissions)
                                            + ", any=" + any.getBoolean(permissions);
                                } catch (Throwable ignored) {
                                    permissionState = "unreadable";
                                }
                            }
                            info("Camera location input: " + sourceName
                                    + "; permissions{" + permissionState + "}");
                        }
                        try {
                            Object result = chain.proceed();
                            if ("nf5".equals(sourceName) || "of5".equals(sourceName)) {
                                info("Camera location state mapper returned "
                                        + (result == null ? "null" : result.getClass().getName()));
                            }
                            return result;
                        } catch (Throwable failure) {
                            error("Camera location state mapper threw "
                                    + failure.getClass().getName(), failure);
                            throw failure;
                        }
                    });
            info("Hooked Camera location input-state mapping");
        } catch (Throwable error) {
            error("Could not hook Camera location input-state mapping", error);
        }
    }

    private void installLocationMethodProbe(
            ClassLoader classLoader,
            String className,
            String methodName,
            Class<?>[] parameterTypes,
            String label,
            int modeArgument,
            int booleanArgument
    ) {
        try {
            Class<?> owner = Class.forName(className, false, classLoader);
            Method method = owner.getDeclaredMethod(methodName, parameterTypes);
            hook(method)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        StringBuilder details = new StringBuilder();
                        if (modeArgument >= 0) {
                            details.append(" mode=")
                                    .append(summarizeLocationMode(chain.getArg(modeArgument)));
                        }
                        if (booleanArgument >= 0) {
                            details.append(" flag=").append(chain.getArg(booleanArgument));
                        }
                        info(label + " entered" + details);
                        try {
                            Object result = chain.proceed();
                            info(label + " returned " + summarizeLocationResult(result));
                            return result;
                        } catch (Throwable failure) {
                            error(label + " threw " + failure.getClass().getName(), failure);
                            throw failure;
                        }
                    });
            info("Hooked " + label);
        } catch (Throwable error) {
            error("Could not hook " + label, error);
        }
    }

    private void installLocationContinuationProbe(
            ClassLoader classLoader,
            String className,
            String label
    ) {
        try {
            Class<?> continuation = Class.forName(className, false, classLoader);
            Method resume = continuation.getDeclaredMethod("invokeSuspend", Object.class);
            hook(resume)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        try {
                            Object result = chain.proceed();
                            info(label + " continuation returned " + summarizeLocationResult(result));
                            return result;
                        } catch (Throwable failure) {
                            error(label + " continuation threw "
                                    + failure.getClass().getName(), failure);
                            throw failure;
                        }
                    });
            info("Hooked " + label + " continuation");
        } catch (Throwable error) {
            error("Could not hook " + label + " continuation", error);
        }
    }

    private String summarizeLocationMode(Object mode) {
        if (mode == null) {
            return "null";
        }
        if (mode instanceof Enum<?>) {
            return ((Enum<?>) mode).name();
        }
        return mode.getClass().getName();
    }

    private String summarizeLocationResult(Object result) {
        if (result == null) {
            return "null";
        }
        String resultType = result.getClass().getName();
        if (!"df7".equals(result.getClass().getSimpleName())) {
            return resultType;
        }
        try {
            Field failureField = result.getClass().getDeclaredField("a");
            failureField.setAccessible(true);
            Object failure = failureField.get(result);
            return resultType + "; failure="
                    + (failure == null ? "null" : failure.getClass().getName());
        } catch (Throwable error) {
            return resultType + "; failure=unreadable";
        }
    }

    private void installVmRunnerInitializerHook(ClassLoader classLoader) {
        try {
            Class<?> vmRunner = Class.forName("com.pairip.VMRunner", false, classLoader);
            hookClassInitializer(vmRunner)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        info("Skipped VMRunner.<clinit>; libpairipcore is not loaded");
                        return null;
                    });
            info("Hooked VMRunner class initializer");
        } catch (Throwable error) {
            error("Could not hook VMRunner class initializer", error);
        }
    }

    private void installStartupLauncherHook(ClassLoader classLoader) {
        try {
            Class<?> startupLauncher = Class.forName("com.pairip.StartupLauncher", false, classLoader);
            Method launch = startupLauncher.getDeclaredMethod("launch");
            hook(launch)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        restoreBeRealOnCreateDispatch(classLoader);
                        restoreMainActivityLifecycleDispatch(classLoader);
                        info("Skipped StartupLauncher.launch() PairIP VM entry");
                        return null;
                    });
            info("Hooked StartupLauncher.launch()");
        } catch (Throwable error) {
            error("Could not hook StartupLauncher.launch()", error);
        }
    }

    private void installAnalyticsNullKeyGuard(ClassLoader classLoader) {
        try {
            Class<?> analyticsIdentity = Class.forName("rb0", false, classLoader);
            Method identify = analyticsIdentity.getDeclaredMethod("g", Map.class);
            hook(identify)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object argument = chain.getArg(0);
                        if (argument instanceof Map) {
                            Map<?, ?> properties = (Map<?, ?>) argument;
                            if (properties.containsKey(null)) {
                                Map<Object, Object> sanitized = new LinkedHashMap<>(properties);
                                int removed = sanitized.containsKey(null) ? 1 : 0;
                                sanitized.remove(null);
                                Object[] args = chain.getArgs().toArray();
                                args[0] = sanitized;
                                info("Removed " + removed + " null-key analytics property before Identify");
                                return chain.proceed(args);
                            }
                        }
                        return chain.proceed();
                    });
            info("Hooked analytics Identify map for null property keys");
        } catch (Throwable error) {
            error("Could not hook analytics Identify map", error);
        }
    }

    private void installProtobufNullFieldProbe(ClassLoader classLoader) {
        try {
            Class<?> protobufSchema = Class.forName("sxc", false, classLoader);
            Class<?> messageInfo = Class.forName("rqh", false, classLoader);
            Method buildSchema = null;
            for (Method method : protobufSchema.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if ("C".equals(method.getName())
                        && parameters.length > 0
                        && parameters[0] == messageInfo) {
                    buildSchema = method;
                    break;
                }
            }
            if (buildSchema == null) {
                throw new NoSuchMethodException("sxc.C(rqh, ...)");
            }

            Method schemaBuilder = buildSchema;
            hook(schemaBuilder)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object previous = protobufMessageInfo.get();
                        protobufMessageInfo.set(chain.getArg(0));
                        try {
                            return chain.proceed();
                        } finally {
                            if (previous == null) {
                                protobufMessageInfo.remove();
                            } else {
                                protobufMessageInfo.set(previous);
                            }
                        }
                    });

            Method getField = protobufSchema.getDeclaredMethod("O", Class.class, String.class);
            hook(getField)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object classArgument = chain.getArg(0);
                        if (chain.getArg(1) == null && classArgument instanceof Class) {
                            Class<?> messageClass = (Class<?>) classArgument;
                            Field inferredField = inferMissingProtoField(messageClass);
                            if (inferredField != null) {
                                info("Recovered protobuf field name for " + messageClass.getName()
                                        + ": " + inferredField.getName());
                                return inferredField;
                            }

                            if (!protobufNullFieldLogged) {
                                protobufNullFieldLogged = true;
                                StringBuilder fields = new StringBuilder();
                                for (Field field : messageClass.getDeclaredFields()) {
                                    if (fields.length() > 0) {
                                        fields.append(", ");
                                    }
                                    fields.append(field.getName())
                                            .append(':')
                                            .append(field.getType().getName());
                                }
                                info("Protobuf schema requested a null field name for "
                                        + messageClass.getName() + "; declaredFields=[" + fields + "]");
                            }
                        }
                        return chain.proceed();
                    });
            info("Hooked protobuf schema field lookup with metadata-based recovery");
        } catch (Throwable error) {
            error("Could not hook protobuf schema field lookup", error);
        }
    }

    private void installRoomNullColumnGuard(ClassLoader classLoader) {
        try {
            Class<?> sqliteStatement = Class.forName("androidx.sqlite.SQLiteStatement", false, classLoader);
            Class<?> statementUtil = Class.forName("androidx.room.util.SQLiteStatementUtil", false, classLoader);
            Method findColumn = statementUtil.getDeclaredMethod("b", sqliteStatement, String.class);
            Method getColumnCount = sqliteStatement.getMethod("getColumnCount");
            Method getColumnName = sqliteStatement.getMethod("getColumnName", int.class);

            hook(findColumn)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        Object statement = chain.getArg(0);
                        if (statement != null && chain.getArg(1) == null) {
                            try {
                                int columnCount = ((Number) getColumnCount.invoke(statement)).intValue();
                                ArrayList<String> columns = new ArrayList<>(columnCount);
                                int idIndex = -1;
                                for (int index = 0; index < columnCount; index++) {
                                    Object columnName = getColumnName.invoke(statement, index);
                                    String name = columnName instanceof String ? (String) columnName : null;
                                    columns.add(name);
                                    if ("id".equals(name)) {
                                        idIndex = index;
                                    }
                                }
                                if (columnCount == 6
                                        && idIndex >= 0
                                        && columns.contains("ownerId")
                                        && columns.contains("postId")
                                        && columns.contains("url")
                                        && columns.contains("emoji")
                                        && columns.contains("postedAt")) {
                                    info("Recovered null Room column key as RealMojiEntity.id from statement schema");
                                    return idIndex;
                                }
                                if (roomNullColumnProbeLogged.compareAndSet(false, true)) {
                                    String details = "Room null column lookup: statementClass="
                                            + statement.getClass().getName()
                                            + ", sql=" + readRoomStatementSql(statement)
                                            + ", columns=" + columns;
                                    Log.w(TAG, details);
                                    log(Log.WARN, TAG, details);
                                }
                            } catch (Throwable inspectionError) {
                                error("Could not inspect Room statement with a null column key", inspectionError);
                            }
                        }
                        return chain.proceed();
                    });
            info("Hooked Room null-column lookup with a RealMojiEntity schema fallback");
        } catch (Throwable error) {
            error("Could not hook Room null-column lookup", error);
        }
    }

    private String readRoomStatementSql(Object statement) {
        for (Class<?> type = statement.getClass(); type != null; type = type.getSuperclass()) {
            String[] candidateNames = "androidx.sqlite.driver.SupportSQLiteStatement".equals(type.getName())
                    ? new String[]{"b", "sql"}
                    : new String[]{"sql"};
            for (String candidateName : candidateNames) {
                try {
                    Field sqlField = type.getDeclaredField(candidateName);
                    sqlField.setAccessible(true);
                    Object sql = sqlField.get(statement);
                    if (sql instanceof String) {
                        return (String) sql;
                    }
                } catch (NoSuchFieldException ignored) {
                } catch (Throwable error) {
                    return "<unreadable:" + error.getClass().getSimpleName() + ">";
                }
            }
        }
        return "<unavailable:" + statement.getClass().getName() + ">";
    }

    private void installCameraOriginSentinelGuard(
            ClassLoader classLoader,
            ResolvedSymbols symbols
    ) {
        List<Method> parsers = symbols == null
                ? java.util.Collections.emptyList()
                : symbols.getCameraOriginParserMethods();
        if (parsers.isEmpty()) {
            info("[SymbolResolver] Camera origin guard disabled: route parsers unresolved");
            return;
        }

        int hooked = 0;
        for (Method parseFromBundle : parsers) {
            try {
                hook(parseFromBundle)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object argument = chain.getArg(0);
                            if (argument instanceof Bundle) {
                                Bundle route = (Bundle) argument;
                                String origin = route.getString("origin");
                                if (origin != null
                                        && (origin.trim().isEmpty() || "null".equals(origin.trim()))) {
                                    Bundle sanitized = new Bundle(route);
                                    sanitized.remove("origin");
                                    Object[] args = chain.getArgs().toArray();
                                    args[0] = sanitized;
                                    if (cameraOriginSentinelLogged.compareAndSet(false, true)) {
                                        info("Removed null-like camera origin before enum parsing; "
                                                + "letting the original parser use its default enum");
                                    }
                                    return chain.proceed(args);
                                }
                            }
                            return chain.proceed();
                        });
                hooked++;
                info("Hooked camera route parser " + parseFromBundle.getDeclaringClass().getName()
                        + "." + parseFromBundle.getName());
            } catch (Throwable error) {
                error("Could not hook camera route parser " + parseFromBundle, error);
            }
        }
        info("Camera origin guard active on " + hooked + " validated Bundle parser(s)");
    }

    private Field inferMissingProtoField(Class<?> messageClass) {
        Object messageInfo = protobufMessageInfo.get();
        if (messageInfo == null) {
            return null;
        }

        try {
            Field objectNamesField = messageInfo.getClass().getDeclaredField("c");
            objectNamesField.setAccessible(true);
            Object value = objectNamesField.get(messageInfo);
            if (!(value instanceof Object[])) {
                return null;
            }

            Set<String> knownNames = new HashSet<>();
            for (Object entry : (Object[]) value) {
                if (entry instanceof String) {
                    knownNames.add((String) entry);
                }
            }

            ArrayList<Field> candidates = new ArrayList<>();
            for (Field field : messageClass.getDeclaredFields()) {
                String name = field.getName();
                if (!Modifier.isStatic(field.getModifiers())
                        && name.endsWith("_")
                        && !knownNames.contains(name)) {
                    candidates.add(field);
                }
            }
            return candidates.size() == 1 ? candidates.get(0) : null;
        } catch (Throwable error) {
            error("Could not infer protobuf field name for " + messageClass.getName(), error);
            return null;
        }
    }

    private void restoreBeRealOnCreateDispatch(ClassLoader classLoader) {
        try {
            Class<?> applicationClass = Class.forName("bereal.app.BeRealApplication", false, classLoader);
            ClassLoader generatedLoader = getGeneratedDexClassLoader(classLoader);
            Class<?> dispatchClass = Class.forName(
                    APPLICATION_DISPATCH_CLASS,
                    false,
                    generatedLoader
            );
            Method applicationOnCreate = dispatchClass.getDeclaredMethod("onCreate", applicationClass);

            Class<?> dispatchHolder = Class.forName(
                    "androidx.lifecycle.viewmodel.savedstate.bJTm.Siorkv",
                    false,
                    classLoader
            );
            Field dispatchField = dispatchHolder.getDeclaredField("osFLetKzporkDk");
            dispatchField.setAccessible(true);
            dispatchField.set(null, applicationOnCreate);
            info("Restored BeRealApplication.onCreate from the captured PairIP DEX");
        } catch (Throwable error) {
            error("Could not restore BeRealApplication.onCreate dispatch method", error);
        }

        try {
            Class<?> firebaseStringHolder = Class.forName(
                    "androidx.compose.foundation.text.input.internal.ZDPh.NhqDXGO",
                    false,
                    classLoader
            );
            Field googleApiKeyResourceName = firebaseStringHolder.getDeclaredField("nSRuyL");
            googleApiKeyResourceName.setAccessible(true);
            restoreStaticStringIfNull(googleApiKeyResourceName, "runtime-string", "google_api_key");
            info("Restored Firebase google_api_key resource-name constant");
        } catch (Throwable error) {
            error("Could not restore Firebase google_api_key resource-name constant", error);
        }

        try {
            Class<?> firebaseAuthStringHolder = Class.forName(
                    "io.adn.sdk.internal.data.repository.player.zs.ueWceEmbmp",
                    false,
                    classLoader
            );
            Field processDeathAppName = firebaseAuthStringHolder.getDeclaredField("etoRAGaqfz");
            processDeathAppName.setAccessible(true);
            restoreStaticStringIfNull(processDeathAppName, "runtime-string", "firebaseAppName");
            info("Restored Firebase Auth process-death app-name key");
        } catch (Throwable error) {
            error("Could not restore Firebase Auth process-death app-name key", error);
        }

        try {
            Class<?> grpcStringHolder = Class.forName(
                    "com.moloco.sdk.internal.client_metrics_data.dcLb.gvCIexqtCeo",
                    false,
                    classLoader
            );
            Field initialBackoffKey = grpcStringHolder.getDeclaredField("JzYCqx");
            initialBackoffKey.setAccessible(true);
            restoreStaticStringIfNull(initialBackoffKey, "runtime-string", "initialBackoff");
            info("Restored gRPC initialBackoff service-config key");
        } catch (Throwable error) {
            error("Could not restore gRPC initialBackoff service-config key", error);
        }

        try {
            Class<?> yotiStepTrackerStrings = Class.forName(
                    "com.yoti.mobile.android.yotisdkcore.stepTracker.di.CGh.TcjS",
                    false,
                    classLoader
            );
            Field stepTrackerDataStoreName = yotiStepTrackerStrings.getDeclaredField("aURUwWhsGN");
            stepTrackerDataStoreName.setAccessible(true);
            restoreStaticStringIfNull(stepTrackerDataStoreName, "runtime-string", "hjdbfhjebe");
            info("Restored Yoti StepTracker DataStore name");
        } catch (Throwable error) {
            error("Could not restore Yoti StepTracker DataStore name", error);
        }

        try {
            Class<?> serializationStringHolder = Class.forName(
                    "bereal.app.features.sharing.ui.VrJ.usGKIW",
                    false,
                    classLoader
            );
            Field serializerMethodName = serializationStringHolder.getDeclaredField("PjJIMWYD");
            serializerMethodName.setAccessible(true);
            restoreStaticStringIfNull(serializerMethodName, "runtime-string", "serializer");
            info("Restored kotlinx.serialization serializer method-name constant");
        } catch (Throwable error) {
            error("Could not restore kotlinx.serialization serializer method-name constant", error);
        }

        try {
            Class<?> navigationStringHolder = Class.forName(
                    "com.google.firebase.crashlytics.yhxg.ZXgeV",
                    false,
                    classLoader
            );
            Field regexWildcard = navigationStringHolder.getDeclaredField("idmeSbwqjtNmJat");
            regexWildcard.setAccessible(true);
            restoreStaticStringIfNull(regexWildcard, "runtime-string", ".*");
            info("Restored NavDeepLink regex wildcard constant");
        } catch (Throwable error) {
            error("Could not restore NavDeepLink regex wildcard constant", error);
        }

        try {
            Class<?> sourcepointStringHolder = Class.forName(
                    "androidx.credentials.gZ.MqonvtnPZU",
                    false,
                    classLoader
            );
            Field localVersionElementName = sourcepointStringHolder.getDeclaredField("krGLjoMJIyAqbMV");
            localVersionElementName.setAccessible(true);
            restoreStaticStringIfNull(localVersionElementName, "runtime-string", "localVersion");
            info("Restored Sourcepoint State serializer localVersion element name");
        } catch (Throwable error) {
            error("Could not restore Sourcepoint State serializer localVersion element name", error);
        }

        try {
            Class<?> sourcepointErrorStrings = Class.forName(
                    "com.yalantis.ucrop.task.zsyC.uFMhyqS",
                    false,
                    classLoader
            );
            Field invalidRequestApiPrefix = sourcepointErrorStrings.getDeclaredField("UKeYkGMltUg");
            invalidRequestApiPrefix.setAccessible(true);
            restoreStaticStringIfNull(invalidRequestApiPrefix, "runtime-string", "The SDK got an unexpected response from ");
            info("Restored Sourcepoint InvalidRequestAPIError description prefix");
        } catch (Throwable error) {
            error("Could not restore Sourcepoint InvalidRequestAPIError description prefix", error);
        }

        try {
            Class<?> protobufStringHolder = Class.forName(
                    "io.adn.sdk.internal.data.repository.player.zs.ueWceEmbmp",
                    false,
                    classLoader
            );
            Field connectProfileFieldName = protobufStringHolder.getDeclaredField("lzWf");
            connectProfileFieldName.setAccessible(true);
            restoreStaticStringIfNull(connectProfileFieldName, "runtime-string", "connectProfileEnabled_");
            info("Restored protobuf iyn.connectProfileEnabled_ field-name constant");
        } catch (Throwable error) {
            error("Could not restore protobuf iyn field-name constant", error);
        }

        try {
            Class<?> roomStringHolder = Class.forName(
                    "io.adn.sdk.internal.data.repository.player.zs.ueWceEmbmp",
                    false,
                    classLoader
            );
            Field realMojiIdColumnName = roomStringHolder.getDeclaredField("PcmDyNHwOUmDKo");
            realMojiIdColumnName.setAccessible(true);
            restoreStaticStringIfNull(realMojiIdColumnName, "runtime-string", "id");
            info("Restored Room RealMojiEntity id column name");
        } catch (Throwable error) {
            error("Could not restore Room RealMojiEntity id column name", error);
        }

        try {
            Class<?> protobufDurationStrings = Class.forName(
                    "com.google.android.gms.ads.mediation.customevent.DL.dbGwTqUCgbZlbI",
                    false,
                    classLoader
            );
            Field nanosFieldName = protobufDurationStrings.getDeclaredField("MkYePb");
            nanosFieldName.setAccessible(true);
            restoreStaticStringIfNull(nanosFieldName, "runtime-string", "nanos_");
            info("Restored protobuf bk6.nanos_ field-name constant");
        } catch (Throwable error) {
            error("Could not restore protobuf bk6 field-name constant", error);
        }

        try {
            Class<?> conversationStringHolder = Class.forName(
                    "com.applovin.mediation.adapters.googleadmanager.gkls.musXWZnlt",
                    false,
                    classLoader
            );
            Field currentSeqNumFieldName = conversationStringHolder.getDeclaredField("rnrBfmTsvrx");
            currentSeqNumFieldName.setAccessible(true);
            restoreStaticStringIfNull(currentSeqNumFieldName, "runtime-string", "currentSeqNum_");
            info("Restored protobuf u7d.currentSeqNum_ field-name constant");
        } catch (Throwable error) {
            error("Could not restore protobuf u7d field-name constant", error);
        }

        try {
            Class<?> requestStringHolder = Class.forName(
                    "com.moloco.sdk.xenoss.sdkdevkit.android.adrenderer.internal.ui.utils.UdT.vACnWl",
                    false,
                    classLoader
            );
            Field bitFieldName = requestStringHolder.getDeclaredField("KbRdYlMM");
            bitFieldName.setAccessible(true);
            restoreStaticStringIfNull(bitFieldName, "runtime-string", "bitField0_");
            info("Restored protobuf r8j.bitField0_ field-name constant");
        } catch (Throwable error) {
            error("Could not restore protobuf r8j field-name constant", error);
        }

        try {
            Class<?> cdnConfigStringHolder = Class.forName(
                    "com.moloco.sdk.internal.client_metrics_data.dcLb.gvCIexqtCeo",
                    false,
                    classLoader
            );
            Field cdnConfigWeightName = cdnConfigStringHolder.getDeclaredField("MTWLxtc");
            cdnConfigWeightName.setAccessible(true);
            restoreStaticStringIfNull(cdnConfigWeightName, "runtime-string", "weight");
            info("Restored CDNConfigDomainApiModel.weight field-name constant");
        } catch (Throwable error) {
            error("Could not restore CDNConfigDomainApiModel field-name constant", error);
        }

        try {
            Class<?> yotiStrings = Class.forName(
                    "com.yoti.mobile.android.yotisdkcore.stepTracker.di.CGh.TcjS",
                    false,
                    classLoader
            );
            Field linkedUserProfilePictureName = yotiStrings.getDeclaredField("kFIUlRtoLhCxv");
            linkedUserProfilePictureName.setAccessible(true);
            restoreStaticStringIfNull(linkedUserProfilePictureName, "runtime-string", "profilePicture");
            info("Restored LinkedUser.profilePicture field-name constant");
        } catch (Throwable error) {
            error("Could not restore LinkedUser.profilePicture field-name constant", error);
        }

        try {
            Class<?> roomEnumStrings = Class.forName(
                    "com.moloco.sdk.xenoss.sdkdevkit.android.adrenderer.internal.ui.utils.UdT.vACnWl",
                    false,
                    classLoader
            );
            Field visibleStatusName = roomEnumStrings.getDeclaredField("nJwTuZXtvIyRt");
            visibleStatusName.setAccessible(true);
            restoreStaticStringIfNull(visibleStatusName, "runtime-string", "Visible");
            info("Restored Room Visible enum value string");
        } catch (Throwable error) {
            error("Could not restore Room Visible enum value string", error);
        }

        try {
            Class<?> cameraStringHolder = Class.forName(
                    "com.bytedance.sdk.openadsdk.of.ykK.nmvDveFz",
                    false,
                    classLoader
            );
            Field supportedSizesMessage = cameraStringHolder.getDeclaredField("SmlWeNRBla");
            supportedSizesMessage.setAccessible(true);
            restoreStaticStringIfNull(supportedSizesMessage, "runtime-string", "No available output size is found for ");
            info("Restored CameraX supported-size validation message prefix");
        } catch (Throwable error) {
            error("Could not restore CameraX supported-size validation message prefix", error);
        }
    }

    private void restoreMainActivityLifecycleDispatch(ClassLoader classLoader) {
        try {
            Class<?> activityClass = Class.forName("bereal.app.MainActivity", false, classLoader);
            ClassLoader generatedLoader = getGeneratedDexClassLoader(classLoader);
            Class<?> bundleClass = Bundle.class;
            Method onCreate = Class.forName(ACTIVITY_CREATE_DISPATCH_CLASS, false, generatedLoader)
                    .getDeclaredMethod("onCreate", activityClass, bundleClass);
            Method onDestroy = Class.forName(ACTIVITY_DESTROY_DISPATCH_CLASS, false, generatedLoader)
                    .getDeclaredMethod("onDestroy", activityClass);
            Method onResume = Class.forName(ACTIVITY_RESUME_DISPATCH_CLASS, false, generatedLoader)
                    .getDeclaredMethod("onResume", activityClass);

            Class<?> lifecycleDispatch = Class.forName(
                    "android.net.http.XXN.LDboynjnJnbjK",
                    false,
                    classLoader
            );
            setStaticMethod(lifecycleDispatch, "EPGBjnKOVGcR", onCreate);
            setStaticMethod(lifecycleDispatch, "Clh", onDestroy);

            Class<?> resumeDispatch = Class.forName(
                    "com.moloco.sdk.acm.http.pg.JZLYrXU",
                    false,
                    classLoader
            );
            setStaticMethod(resumeDispatch, "wdndhQOAVjwiG", onResume);
            info("Restored MainActivity onCreate/onDestroy/onResume dispatch from the captured PairIP DEX");
        } catch (Throwable error) {
            error("Could not restore MainActivity lifecycle dispatch", error);
        }
    }

    private synchronized ClassLoader getGeneratedDexClassLoader(ClassLoader parent)
            throws IOException {
        if (generatedDexClassLoader != null && generatedDexParent == parent) {
            return generatedDexClassLoader;
        }

        ApplicationInfo moduleInfo = getModuleApplicationInfo();
        String moduleApk = moduleInfo.sourceDir;
        if (moduleApk == null || moduleApk.isEmpty()) {
            throw new IOException("Vector did not provide the module APK path");
        }

        byte[] dexBytes;
        try (ZipFile zipFile = new ZipFile(moduleApk)) {
            ZipEntry entry = zipFile.getEntry(GENERATED_DEX_ENTRY);
            if (entry == null) {
                throw new IOException("Missing " + GENERATED_DEX_ENTRY + " in " + moduleApk);
            }
            if (entry.getSize() <= 0 || entry.getSize() > 4 * 1024 * 1024) {
                throw new IOException("Unexpected PairIP DEX size: " + entry.getSize());
            }

            try (InputStream input = zipFile.getInputStream(entry);
                 ByteArrayOutputStream output = new ByteArrayOutputStream((int) entry.getSize())) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                dexBytes = output.toByteArray();
            }
        }

        generatedDexClassLoader = new InMemoryDexClassLoader(ByteBuffer.wrap(dexBytes), parent);
        generatedDexParent = parent;
        info("Loaded captured PairIP DEX (" + dexBytes.length + " bytes) into the target class loader");
        return generatedDexClassLoader;
    }

    private static void setStaticMethod(Class<?> holder, String fieldName, Method value)
            throws ReflectiveOperationException {
        Field field = holder.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private void info(String message) {
        Log.i(TAG, message);
        log(Log.INFO, TAG, message);
    }

    private void error(String message, Throwable throwable) {
        RuntimeKnowledge.recordFailure(throwable);
        Log.e(TAG, message, throwable);
        log(Log.ERROR, TAG, message, throwable);
    }
}
