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
        System.out.println("PASS: paused/nested Compose scopes, version guards, authentication host boundaries, timestamps, MP4 sample preservation, malformed atoms, repair inference, selected RealMoji snapshot");
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
