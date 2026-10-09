import dev.tqmane.befuck.download.PostMediaMetadata;
import dev.tqmane.befuck.runtime.RepairInference;
import dev.tqmane.befuck.runtime.ComposeHookScope;
import dev.tqmane.befuck.download.RealMojiDownloadAction;
import dev.tqmane.befuck.download.FeedPostMedia;
import dev.tqmane.befuck.posting.BeFakeAuthHeaders;
import dev.tqmane.befuck.symbols.KnownMappings3970;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;

/** Run with the Gradle :app:checkModule task. */
public final class MetadataCheck {
    public static void main(String[] args) throws Exception {
        checkComposeScopes();
        assert KnownMappings3970.isKnownVersion("3.97.0", 3597523L);
        assert !KnownMappings3970.isKnownVersion("3.97.0", 3597524L);
        assert !KnownMappings3970.isKnownVersion("3.98.0", 3597523L);
        assert !KnownMappings3970.isKnownVersion(null, 3597523L);
        checkSmsRequestPayload();
        checkSmsCompatibility();
        checkTextFieldFocusMapping();
        checkAdClassification();
        checkKnownStrings();
        BeFakeAuthHeaders.capture("bereal.com.example.org", "Authorization", "Bearer rejected");
        assert !BeFakeAuthHeaders.hasAuthorization();
        BeFakeAuthHeaders.capture("MOBILE-L7.BEREAL.COM", "AUTHORIZATION", "Bearer test-only");
        assert BeFakeAuthHeaders.hasAuthorization();
        BeFakeAuthHeaders.capture("mobile-l7.bereal.com", "Cookie", "ignored");
        assert !BeFakeAuthHeaders.snapshot().containsKey("Cookie");
        var metadata = PostMediaMetadata.INSTANCE;
        long millis = Instant.parse("2026-10-05T08:24:51.573Z").toEpochMilli();
        assert metadata.parseTimestamp("2026-10-05T17:24:51.573+09:00") == millis;
        assert metadata.parseTimestamp("invalid") == null;
        var loader = MetadataCheck.class.getClassLoader();
        var reaction = new RealMojiFixture();
        var action = RealMojiDownloadAction.create(loader, reaction);
        assert action.equals(RealMojiDownloadAction.create(loader, reaction));
        var mediaField = RealMojiDownloadAction.class.getDeclaredField("media");
        mediaField.setAccessible(true);
        var saved = (FeedPostMedia) mediaField.get(java.lang.reflect.Proxy.getInvocationHandler(action));
        reaction.e = "https://cdn.bereal.network/second.jpg";
        assert !action.equals(RealMojiDownloadAction.create(loader, reaction));
        assert saved.getPrimary().getUrl().endsWith("first.jpg") : "Selected RealMoji was not snapshotted";
        assert metadata.timestamp(saved) == reaction.h;
        assert metadata.filename(saved, "realmoji").contains("_realmoji_reaction-id");
        var inference = RepairInference.INSTANCE;
        assert inference.isBinderDescriptor("com.google.android.gms.maps.internal.IOnCameraIdleListener");
        assert inference.isBinderDescriptor("android.os.IServiceManager");
        assert !inference.isBinderDescriptor("recursionDepth");
        assert !inference.isBinderDescriptor("@");
        assert !inference.isBinderDescriptor("com.example.Invalid Descriptor");
        assert "recursionDepth".equals(inference.uniqueMissingKey(java.util.List.of("postId"), java.util.List.of("Event(postId=", ", recursionDepth="), 1));
        assert inference.uniqueMissingKey(java.util.List.of("postId"), java.util.List.of("Event(postId=", ", first=", ", second="), 1) == null;
        assert inference.uniqueMissingKey(java.util.List.of("postId"), java.util.List.of("Event(postId=", ", recursionDepth="), 2) == null;
        assert inference.uniqueMissingKey(java.util.List.of("postId"), java.util.List.of("Event(postId="), 1) == null;
        var write = Arrays.stream(PostMediaMetadata.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("writeMp4Date") && m.getParameterCount() == 2)
                .findFirst().orElseThrow();
        var file = Files.createTempFile("befuck-metadata-check-", ".mp4");
        try {
            for (int version : new int[]{0, 1}) {
                byte[] header = new byte[version == 0 ? 12 : 20];
                header[0] = (byte) version;
                byte[] payload = {11, 22, 33, 44};
                byte[] input = atom("moov", atom("mvhd", header));
                byte[] samples = atom("mdat", payload);
                var buffer = new ByteArrayOutputStream();
                buffer.write(input); buffer.write(samples);
                Files.write(file, buffer.toByteArray());
                write.invoke(metadata, file.toFile(), millis);
                byte[] result = Files.readAllBytes(file);
                var fields = ByteBuffer.wrap(result);
                long expected = millis / 1000 + 2_082_844_800L;
                long actual = version == 0 ? Integer.toUnsignedLong(fields.getInt(20)) : fields.getLong(20);
                assert actual == expected : "Incorrect MP4 creation time";
                assert Arrays.equals(samples, Arrays.copyOfRange(result, input.length, result.length)) : "Media samples changed";
            }
            Files.write(file, ByteBuffer.allocate(8).putInt(4).putInt(0x6d6f6f76).array());
            try { write.invoke(metadata, file.toFile(), millis); throw new AssertionError("Accepted invalid atom size"); }
            catch (InvocationTargetException expected) { assert expected.getCause() instanceof IllegalArgumentException; }
        } finally { Files.deleteIfExists(file); }
        System.out.println("PASS: SMS host boundaries/signatures, stale text-field focus mapping, paused/nested Compose scopes, version guards, authentication host boundaries, timestamps, MP4 sample preservation, malformed atoms, repair inference, selected RealMoji snapshot");
    }

    private static void checkComposeScopes() {
        var composer = new Object();
        var emissions = new java.util.ArrayList<String>();
        var paused = ComposeHookScope.push(composer, () -> emissions.add("must not run"));
        ComposeHookScope.started(composer);
        ComposeHookScope.executed(composer, false);
        ComposeHookScope.ending(composer);
        ComposeHookScope.ended(composer);
        ComposeHookScope.pop(paused);
        assert emissions.isEmpty() : "A skipped/paused body emitted download UI";

        var outer = ComposeHookScope.push(composer, () -> {
            emissions.add("feed");
            // AndroidView creates its own nested restart group during injection.
            ComposeHookScope.started(composer);
            ComposeHookScope.executed(composer, true);
            ComposeHookScope.ending(composer);
            ComposeHookScope.ended(composer);
        });
        ComposeHookScope.started(composer);
        ComposeHookScope.executed(composer, true);
        var inner = ComposeHookScope.push(composer, () -> emissions.add("detail"));
        ComposeHookScope.started(composer);
        ComposeHookScope.executed(composer, true);
        ComposeHookScope.ending(new Object()); // Unrelated composers cannot mutate this frame.
        assert emissions.isEmpty();
        ComposeHookScope.ending(composer);
        assert emissions.equals(java.util.List.of("detail"));
        ComposeHookScope.ended(composer);
        ComposeHookScope.pop(inner);
        ComposeHookScope.ending(composer);
        assert emissions.equals(java.util.List.of("detail", "feed")) : "Nested injection re-entered the host callback";
        ComposeHookScope.ended(composer);
        ComposeHookScope.pop(outer);

        var failed = ComposeHookScope.push(composer, () -> { throw new IllegalStateException("fixture"); });
        ComposeHookScope.started(composer);
        ComposeHookScope.executed(composer, true);
        try { ComposeHookScope.ending(composer); throw new AssertionError("Injection error was swallowed"); }
        catch (IllegalStateException expected) { assert "fixture".equals(expected.getMessage()); }
        finally { ComposeHookScope.pop(failed); }
        ComposeHookScope.ending(composer);
        assert emissions.size() == 2 : "Failed composition left an active frame";
    }

    public interface FocusOffsetFixture {
        int d(int offset);
        int a(int offset);
    }

    private static void checkTextFieldFocusMapping() throws Exception {
        var loader = MetadataCheck.class.getClassLoader();
        assert KnownMappings3970.textFieldFocusGuard(loader, "3.98.0", 3597523L) == null;
        assert KnownMappings3970.textFieldFocusGuard(loader, "3.97.0", 3597524L) == null;
        var transform = FocusOffsetFixture.class.getMethod("d", int.class);
        FocusOffsetFixture original = new FocusOffsetFixture() {
            public int d(int offset) { return offset * 2; }
            public int a(int offset) { return offset / 2; }
        };
        var bounded = (FocusOffsetFixture) KnownMappings3970.boundedFocusMapping(original, transform, 5);
        assert bounded.d(3) == 5 : "Stale layout must bound the transformed cursor";
        assert bounded.d(2) == 4 : "Valid transformed positions must stay unchanged";
        assert bounded.d(-1) == 0;
        assert bounded.a(12) == 6 : "Reverse mapping must stay unchanged";
        assert original.d(3) == 6 : "Do not change the text field's own mapping";
        var empty = (FocusOffsetFixture) KnownMappings3970.boundedFocusMapping(original, transform, 0);
        assert empty.d(3) == 0 : "Empty layout must use the host's empty-field rectangle";
    }

    private static void checkSmsCompatibility() {
        String endpoint = "https://auth-l7.bereal.com/api/vonage/request-code";
        assert dev.tqmane.befuck.runtime.SmsAuthCompatibility.matches("POST", endpoint);
        assert dev.tqmane.befuck.runtime.SmsAuthCompatibility.matches("POST", endpoint.replace("request-code", "check-code"));
        for (String rejected : new String[]{
                endpoint.replace("https:", "http:"), endpoint.replace("auth-l7", "mobile-l7"),
                endpoint.replace("bereal.com", "bereal.com.example.org"), endpoint + "?redirect=1",
                endpoint + "/extra", endpoint + "#fragment", endpoint.replace("/api/", "/%61pi/"),
                endpoint.replace("auth-l7", "user@auth-l7"), "not a URL",
                endpoint.replace("bereal.com", "bereal.com:8080"),
                "https://ogma-l7.bereal.com/public.auth.v2.PhoneVerificationService/IssueTokenByPhoneNumber"}) {
            assert !dev.tqmane.befuck.runtime.SmsAuthCompatibility.matches("POST", rejected) : rejected;
        }
        assert !dev.tqmane.befuck.runtime.SmsAuthCompatibility.matches("GET", endpoint);
        var headers = dev.tqmane.befuck.runtime.SmsAuthCompatibility.headers("fixture-device", 1700000000L, "Asia/Tokyo");
        assert headers.get("bereal-signature").equals("MToxNzAwMDAwMDAwOq2F32QIrrv4OyvC8d8Ptu5ulUWkBOQnJvjI4a1QKQfr");
        assert headers.get("bereal-device-id").equals("fixture-device");
        assert headers.get("bereal-timezone").equals("Asia/Tokyo");
        assert headers.get("bereal-platform").equals("iOS");
        assert !headers.containsKey("authorization");
        assert !headers.get("bereal-signature").equals(
                dev.tqmane.befuck.runtime.SmsAuthCompatibility.headers("fixture-device", 1700000001L, "Asia/Tokyo").get("bereal-signature"));
        try {
            dev.tqmane.befuck.runtime.SmsAuthCompatibility.headers("", 1700000000L, "Asia/Tokyo");
            throw new AssertionError("Missing device identity accepted");
        } catch (IllegalArgumentException expected) { }
    }

    private static void checkSmsRequestPayload() throws Exception {
        var loader = MetadataCheck.class.getClassLoader();
        assert KnownMappings3970.smsRequestConstructor(loader, "3.98.0", 3597523L) == null;
        assert KnownMappings3970.smsRequestConstructor(loader, "3.97.0", 3597524L) == null;
        var constructor = KnownMappings3970.smsRequestConstructor(loader, "3.97.0", 3597523L);
        assert constructor.getDeclaringClass() == mtj.class;
        Object[] missing = {"device-fixture", "stable-fixture", "phone-fixture", null};
        Object[] repaired = KnownMappings3970.smsRequestArguments(missing);
        assert missing[3] == null : "Must not mutate the caller's arguments";
        assert repaired != missing && ((java.util.ArrayList<?>) repaired[3]).isEmpty();
        for (int i = 0; i < 3; i++) assert repaired[i] == missing[i];
        assert KnownMappings3970.smsRequestArguments(repaired) == repaired : "Repair must be idempotent";
        var tokens = new java.util.ArrayList<>(java.util.List.of(new Object()));
        Object[] populated = {missing[0], missing[1], missing[2], tokens};
        assert KnownMappings3970.smsRequestArguments(populated) == populated;
        assert populated[3] == tokens : "Existing challenge tokens must retain identity and contents";
        assert tokens.size() == 1;
        var validTokens = new java.util.ArrayList<>(java.util.List.of(
                new hj0("private-recaptcha-fixture", "RE"), new hj0("private-prelude-fixture", "PR")));
        Object[] valid = {missing[0], missing[1], missing[2], validTokens};
        String shape = KnownMappings3970.smsRequestShape(loader, "3.97.0", 3597523L, valid);
        assert shape.contains("recaptcha=1; prelude=1");
        assert shape.contains("unknownIdentifiers=0; missingTokens=0; invalidEntries=0");
        for (String secret : new String[]{"device-fixture", "stable-fixture", "phone-fixture",
                "private-recaptcha-fixture", "private-prelude-fixture"}) assert !shape.contains(secret);
        assert valid[3] == validTokens && validTokens.size() == 2 : "Diagnostics must not change tokens";
        var malformed = new java.util.ArrayList<Object>();
        malformed.add(new hj0(null, "RE"));
        malformed.add(new hj0(" ", "private-unknown-provider"));
        malformed.add(null);
        malformed.add(new Object() { @Override public String toString() { throw new AssertionError("Must not stringify"); } });
        shape = KnownMappings3970.smsRequestShape(loader, "3.97.0", 3597523L,
                new Object[]{null, "", null, malformed});
        assert shape.contains("deviceIdPresent=false; stableDeviceIdPresent=false; phonePresent=false");
        assert shape.contains("unknownIdentifiers=1; missingTokens=2; invalidEntries=2");
        assert !shape.contains("private-unknown-provider");
        assert KnownMappings3970.smsRequestShape(loader, "3.97.0", 3597524L, valid).equals("unsupportedVersion=true");
        assert KnownMappings3970.smsRequestShape(loader, "3.98.0", 3597523L, valid).equals("unsupportedVersion=true");
        assert KnownMappings3970.smsRequestShape(loader, "3.97.0", 3597523L, new Object[0]).equals("unexpectedArguments=true");
        assert KnownMappings3970.smsRequestShape(loader, "3.97.0", 3597523L, missing).equals("tokenListPresent=false");
    }

    private static void checkKnownStrings() throws Exception {
        var loader = MetadataCheck.class.getClassLoader();
        assert KnownMappings3970.runtimeStringRepairs(loader, "3.98.0", 3597523L).isEmpty();
        assert KnownMappings3970.runtimeStringRepairs(loader, "3.97.0", 3597524L).isEmpty();
        var repairs = KnownMappings3970.runtimeStringRepairs(loader, "3.97.0", 3597523L);
        var fixture = okhttp3.internal.connection.udV.ewVWKVT.class;
        assert repairs.size() == 2 : "Exclude final, instance, non-String, and missing fields";
        assert "".equals(repairs.get(fixture.getDeclaredField("PLXlOOMCGp")));
        assert "video/x-vnd.on2.vp9".equals(repairs.get(fixture.getDeclaredField("oiooMrpCpO")));
        assert okhttp3.internal.connection.udV.ewVWKVT.PLXlOOMCGp == null : "Resolution must not write fields";
        assert "already initialized".equals(okhttp3.internal.connection.udV.ewVWKVT.oiooMrpCpO);
    }

    private static void checkAdClassification() throws Exception {
        assert KnownMappings3970.isAdViewClass(AdManagerViewFixture.class, "3.97.0", 3597523L)
                : "SDK subclasses must remain hidden";
        assert !KnownMappings3970.isAdViewClass(AdManagerViewFixture.class, "3.97.0", 3597524L);
        assert !KnownMappings3970.isAdViewClass(AdManagerViewFixture.class, "3.98.0", 3597523L);
        assert !KnownMappings3970.isAdViewClass(OrdinaryAdViewNamedFixture.class, "3.97.0", 3597523L)
                : "An ad-like name is not evidence that a view is an advertisement";
        assert !KnownMappings3970.isAdViewClass(Object.class, "3.97.0", 3597523L);
        var loader = MetadataCheck.class.getClassLoader();
        assert KnownMappings3970.sponsoredFeedFields(loader, "3.97.0", 3597524L).isEmpty();
        var fields = KnownMappings3970.sponsoredFeedFields(loader, "3.97.0", 3597523L);
        assert fields.size() == 2;
        var ordinary = new okg();
        for (var field : fields) assert field.get(ordinary) == null;
        var loading = new okg();
        loading.e = new jkg();
        assert fields.get(0).get(loading) == loading.e : "Catch a sponsored card before its loaded state exists";
        assert fields.get(1).get(loading) == null;
        var loaded = new okg();
        loaded.h = new eki();
        assert fields.get(1).get(loaded) == loaded.h;
    }

    public static final class RealMojiFixture {
        public String a = "reaction-id", b = "owner-id", c = "⚡", e = "https://cdn.bereal.network/first.jpg", f = "owner";
        public long h = 1_759_655_091_573L;
    }

    private static byte[] atom(String name, byte[] payload) throws Exception {
        var output = new ByteArrayOutputStream();
        var data = new DataOutputStream(output);
        data.writeInt(8 + payload.length); data.writeBytes(name); data.write(payload);
        return output.toByteArray();
    }
}

class AdManagerViewFixture extends com.google.android.gms.ads.BaseAdView {}
class OrdinaryAdViewNamedFixture {}
class okg { public jkg e; public eki h; }
class jkg {}
class eki {}
class hj0 {
    private final String token, identifier;
    hj0(String token, String identifier) { this.token = token; this.identifier = identifier; }
}

// Native menu action is a marker interface; the download action must remain separate from delete/report.
interface ddi {}

// Exact 3.97.0 request signature; contains no real account or device data.
class mtj {
    private String deviceId, stableDeviceId, phoneNumber;
    private java.util.List<?> tokens;
    mtj(String deviceId, String stableDeviceId, String phoneNumber, java.util.ArrayList<?> tokens) {
        this.deviceId = deviceId;
        this.stableDeviceId = stableDeviceId;
        this.phoneNumber = phoneNumber;
        this.tokens = tokens;
    }
}
