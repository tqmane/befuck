package dev.tqmane.befuck.symbols

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList

/**
 * Verified structure-only compatibility for the original BeReal 3.97.1 (3599414).
 *
 * Its R8 names and missing-string pools differ from 3.97.0: NEVER apply
 * KnownMappings3970's obfuscated members or its 1,401 string repair literals here.
 * The full 12-DEX base APK is scanned independently by BeRealSymbolResolver.
 */
internal object KnownMappings3971 {
    const val VERSION_NAME = "3.97.1"
    const val VERSION_CODE = 3599414L
    const val EXPECTED_DEX_COUNT = 12

    @JvmStatic
    fun isKnownVersion(name: String?, code: Long): Boolean =
        name == VERSION_NAME && code == VERSION_CODE

    // All of these class descriptors were found in the supplied 3.97.1 base APK.
    // Check superclasses too: e.g. AdManagerAdView extends BaseAdView.
    private val adViewClassNames = setOf(

        "bereal.app.features.ads.ui.natives.view.MaxNativeAdViewContainer",
        "com.applovin.adview.AppLovinAdView",
        "com.applovin.mediation.ads.MaxAdView",
        "com.applovin.mediation.nativeAds.MaxNativeAdView",
        "com.applovin.impl.sdk.nativeAd.AppLovinMediaView",
        "com.google.android.gms.ads.BaseAdView",
        "com.google.android.gms.ads.nativead.NativeAdView",
        "com.google.android.gms.ads.nativead.MediaView",
        "com.google.android.gms.ads.formats.MediaView",
        "com.bytedance.sdk.openadsdk.api.nativeAd.PAGMediaView",
        "com.bytedance.sdk.openadsdk.adapter.MediaView",
        "com.pubmatic.sdk.nativead.POBNativeAdView",
        "com.pubmatic.sdk.nativead.views.POBNativeTemplateView",
        "com.pubmatic.sdk.openwrap.banner.POBBannerView",
        "com.pubmatic.sdk.appopenad.ui.POBAppOpenAdViewContainer",
        "com.pubmatic.sdk.webrendering.ui.POBAdViewContainer",
        "com.pubmatic.sdk.webrendering.ui.POBMraidViewContainer",
        "com.pubmatic.sdk.video.player.POBVideoPlayerView",
        "com.pubmatic.sdk.video.player.POBVastHTMLView",
        "com.pubmatic.sdk.video.player.POBMraidEndCardView",
        "com.pubmatic.sdk.common.view.POBWebView",
        "com.vungle.ads.BannerView",
        "com.vungle.ads.VungleBannerView",
        "com.vungle.ads.internal.ui.view.MediaView",
        "net.pubnative.lite.sdk.views.HyBidAdView",
        "net.pubnative.lite.sdk.vpaid.VideoAdView",
    )

    @JvmStatic
    fun isAdViewClass(type: Class<*>, name: String?, code: Long): Boolean {
        if (!isKnownVersion(name, code)) return false
        return generateSequence(type) { it.superclass }.any { it.name in adViewClassNames }
    }

    /** The serializable request moved from mtj to euj; all four fields and its
     * four-argument constructor are confirmed in classes10.dex of 3599414.
     * Keep the version code AND the field/constructor types as guards.
     */
    @JvmStatic
    fun smsRequestConstructor(loader: ClassLoader, name: String?, code: Long): Constructor<*>? {
        if (!isKnownVersion(name, code)) return null
        val request = Class.forName("euj", false, loader)
        for ((fieldName, expectedType) in mapOf(
            "deviceId" to String::class.java,
            "stableDeviceId" to String::class.java,
            "phoneNumber" to String::class.java,
            "tokens" to List::class.java,
        )) {
            val field = request.getDeclaredField(fieldName)
            require(field.type == expectedType && !Modifier.isStatic(field.modifiers))
        }
        return request.getDeclaredConstructor(
            String::class.java, String::class.java, String::class.java, ArrayList::class.java,
        )
    }

    /** Stable Compose types/signatures verified in 3.97.1 classes.dex, 9-12.
     * Reuse the input-bounds logic, not an obsolete obfuscated type name.
     */
    @JvmStatic
    fun textFieldFocusGuard(loader: ClassLoader, name: String?, code: Long): KnownMappings3970.TextFieldFocusGuard? {
        if (!isKnownVersion(name, code)) return null
        fun type(n: String) = Class.forName(n, false, loader)
        val layout = type("androidx.compose.ui.text.TextLayoutResult")
        val input = type("androidx.compose.ui.text.TextLayoutInput")
        val annotated = type("androidx.compose.ui.text.AnnotatedString")
        val mapping = type("androidx.compose.ui.text.input.OffsetMapping")
        require(mapping.isInterface && CharSequence::class.java.isAssignableFrom(annotated))
        val notify = type("androidx.compose.foundation.text.TextFieldDelegate\$Companion")
            .getDeclaredMethod("b",
                type("androidx.compose.ui.text.input.TextFieldValue"),
                type("androidx.compose.foundation.text.TextDelegate"),
                layout,
                type("androidx.compose.ui.layout.LayoutCoordinates"),
                type("androidx.compose.ui.text.input.TextInputSession"),
                Boolean::class.javaPrimitiveType,
                mapping,
            )
        require(Modifier.isStatic(notify.modifiers) && notify.returnType == Void.TYPE)
        fun field(owner: Class<*>, name: String, expected: Class<*>): Field =
            owner.getDeclaredField(name).apply {
                require(type == expected && !Modifier.isStatic(modifiers))
                isAccessible = true
            }
        val transform = mapping.getDeclaredMethod("d", Int::class.javaPrimitiveType).apply {
            require(returnType == Int::class.javaPrimitiveType && !Modifier.isStatic(modifiers))
        }
        notify.isAccessible = true
        return KnownMappings3970.TextFieldFocusGuard(
            notify, field(layout, "a", input), field(input, "a", annotated), transform,
        )
    }

    /** The standard PairIP license entry remains checkLicense(Context) in classes2.dex.
     * Only invoked on an already-repackaged host with validated original signing metadata.
     */
    @JvmStatic
    fun repackagedStartupChecks(loader: ClassLoader, name: String?, code: Long): List<Method> {
        if (!isKnownVersion(name, code)) return emptyList()
        return listOf(
            Class.forName("com.pairip.licensecheck.LicenseClient", false, loader)
                .getDeclaredMethod("checkLicense", android.content.Context::class.java)
                .apply { require(Modifier.isStatic(modifiers) && returnType == Void.TYPE) },
        )
    }
}
