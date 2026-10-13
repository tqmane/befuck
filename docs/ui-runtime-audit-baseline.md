# Expressive UI and runtime audit

## Scope and design

This change keeps BeFuck's black/white theme and its existing resource-independent Android View integration inside BeReal. It applies Material 3 Expressive-inspired grouping, large rounded shapes, pill actions, typography hierarchy and restrained press motion. It is not a migration to Compose or a claim that these are the official Material Components widgets. The host's theme and resource IDs must not leak into module UI resources.

Gallery posting now has separate 3:4 media cards, explicit photo/video choices, caption/audience sections, a retake control and a persistent primary action. At large font sizes, paired cards and audience choices stack vertically. Settings and repair lists reuse the same tokens. Photo cropping remains a full-screen workspace, not a photo squeezed into a confirmation dialog. Video editing uses the same controls and actual Tabler transport icons.

The map is a full-screen workspace with a search field, selectable search results, explicit current-location action, selected-coordinate card, clear/confirm controls, retry state and attribution. The default viewport is not a silently selected posting location. Permission is requested only after pressing current location.

Saved posts have a fixed header, username/date filters, separate back/front/all actions and paged row inflation. The database snapshot is read off the UI thread. The screen intentionally does not load remote photo previews just to browse the URL index.

## Icons and dependencies

The 35 icons are official Tabler 3.49.0 SVGs, pinned to `bbed884d15354b5cebf2493371f20dc2d5e83eaf`, with the upstream MIT license. `checks/export_tabler.py` converts those paths to resource-independent Kotlin drawables and the two injected Android vector resources. Normal builds and CI use vendored files; they do not download icons. `--fetch` is an explicit maintainer regeneration option. The complete Tabler license is also packaged in the APK.

Sources: [Tabler](https://github.com/tabler/tabler-icons/tree/bbed884d15354b5cebf2493371f20dc2d5e83eaf), [Material 3 Expressive](https://m3.material.io/blog/building-with-m3-expressive), [Robolectric](https://robolectric.org/getting-started/).

## Defects addressed

| Area | Failure mechanism | Change / evidence |
| --- | --- | --- |
| Map selection | An earlier search or GPS result could replace a newer manual pin or undo clear. | Generation-gated proposals, explicit result selection, invalidation on edit/clear/pause/dismiss; five state regression tests. |
| Location lifecycle | Requests and map state could outlive their screen. | Cancelable search connections/futures, cancelable current-location request with timeout, explicit MapView start/resume/pause/stop/destroy ownership. |
| Image loading | Work on the UI thread, unbounded panorama dimension, and missed mirrored EXIF orientations. | Worker decoding, either-edge sample budget, closed streams, all eight EXIF transforms; actual JPEG and orientation tests. |
| Crop export | Work or decoded images could return after dismissal. | Immutable transform snapshot and explicit bitmap ownership; disabled editing during export, stale-result cleanup and snapshot rendering test. |
| Picker ownership | An old Activity result could be consumed by a newly opened gallery session. | Session-and-request ownership, stale-result rejection; real dialog/picker lifecycle regression test. |
| Posting files | Dismissal could remove media before a submission worker had finished using it. | Lease before starting submission, immutable retry metadata, identity-aware callbacks and orphan cleanup; leased/unleased file deletion test. |
| Posting state | Completion could restore posting controls incorrectly or update a dead screen. | Once-only completion and common availability calculation, active-session checks, frozen form metadata during submission. |
| Video preview | Old MediaPlayer callbacks and older frame requests could mutate the active editor. | Player identity checks, guarded transitions, release without starting another poster request, generation-gated frame delivery. Requires real decoder/device qualification. |
| Downloads | Opening the response could leak an already-open output, partial or HTML error responses could be published, and throwing consumers could receive a second completion. | Nested stream ownership, bounded/length-checked copy, MIME rejection, HTTP 200 requirement, pending-item cleanup, coalesced identical requests and isolated subscriber callbacks; five negative/success IO tests. |
| Cached post metadata | Explicit JSON null booleans could discard a cached post; literal caption `null` was lost. | Typed optional JSON reads; two regression tests. |
| Cache opening | A failed DB open could leave initialization permanently marked in-progress. | Reset in finally, close partial DB, permit another initialization attempt. Code reviewed; failure/retry not device-tested. |
| Runtime settings | Programmatic switch refresh wrote preferences back; malformed stored rule types could crash reads; diagnostic de-duplication grew without a bound. | Refresh guard, typed rule reads, 512-entry event set. Explicit manual empty-string repair is allowed; automatic inference is not broadened. |
| Windows / access | Child dialogs could remain after owner teardown; fixed confirmation heights and small targets wasted space or clipped content. | Weak owner registry, natural-height bounded scrolling forms, 48dp icon targets and 52dp primary controls. |

## Reproducible verification

Use the repository's JDK 17 / Gradle wrapper:

```sh
python3 checks/export_tabler.py
./gradlew --no-daemon --continue :app:assembleDebug :app:checkModule :app:testDebugUnitTest :app:lintDebug
```

The implementation audit run [38343553008](https://github.com/tqmane/befuck/actions/runs/38343553008) passed compilation, `checkModule`, lint and all 23 added tests (0 failures, 0 skips). Runtime/UI tests use Robolectric with Android 13/API 33 and native graphics; pure IO/state tests run on the JVM. The regular PR workflow now repeats these checks and retains XML/HTML reports and the generated debug APK. No test logs in this change contain real account credentials or media.

The generated images below draw the actual gallery View hierarchy, not a separate mock-up. They are synthetic Robolectric renders without BeReal/system chrome and do not prove real-device visual parity. The normal CI report artifact regenerates them:

- [English, 400dp](ui/gallery-en.png)
- [Japanese, 320dp](ui/gallery-ja-compact.png)
- [Japanese, 320dp with 1.8x font scale](ui/gallery-ja-large-type.png)

## Qualification still required

Compilation and the tests above are **not** evidence that all BeReal features work or that no other defects exist. The uploaded BeReal 3.97.1 APK set was not fully re-qualified: the local analysis environment failed before a complete decompilation/host-symbol comparison could be finished. No physical device or emulator was modified, and no authenticated post, real-account request, location permission prompt, native map tile rendering or hardware video encode/decode was exercised. The screenshots were generated successfully, but the unavailable local image-inspection environment prevented a final visual review of their pixels. They are included for reviewer inspection rather than described as visually approved.

Before release, exercise the injected UI on the intended BeReal 3.97.1 installation: open/close/reopen, navigate away during picker/crop/upload, deny/revoke location permission, select a pin while a search is pending, lose connectivity, rotate/background the app during video editing, and save to full storage. Verify theme/resource isolation, GL map lifecycle, retained official posting drafts and actual upload confirmation. API-29 fallback paths also require device qualification. Saved-index read failure/retry, real MediaStore rollback and malformed runtime preference cases are code-reviewed but not covered by the new end-to-end tests.

## External map/search services

Place searches send the entered query to the configured HTTPS endpoint. Map display also contacts the style/tile provider and exposes requested map areas to that provider. No permission is needed for manual pin placement. Attribution is retained.

The default search provider is public Nominatim. This client uses explicit searches only, a bounded cache, identification, and per-process throttling. **That is not an app-wide traffic guarantee across multiple devices.** Public Nominatim's policy applies to aggregate application traffic. For regular/shared deployment, configure an appropriately licensed compatible provider or your own service through the map's provider settings; do not assume a per-process throttle grants unrestricted public-server access. [Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/).

No README, release version, release tag, real user settings or host symbol presets are intentionally changed by this UI/audit work.
