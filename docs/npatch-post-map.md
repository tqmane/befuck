# NPatch post-map fallback

## Failure and scope

The supplied 2026-10-17 log reports Google Maps `Error requesting API token. StatusCode=INVALID_ARGUMENT`
followed by `Authorization failure`. The Google renderer loads, but its API authorization fails.
This is separate from the FusedLocation errors and native location fallback also present in the log.
Changing the BeFuck startup signer gate cannot authorize a patched APK in a Google Cloud project.

Only a host with `npatch` manifest metadata and the verified BeReal **3.97.1 / 3599414** symbol
shape receives the replacement. Ordinary Google Play / root-LSPosed installs retain Google Maps.
There is no original-signature check, API-key replacement, certificate impersonation, or Google
service hook in this change. APKEditor-merged inputs do not need an official signing certificate.

The replacement is the **original BeReal post-location screen**, not just the BeFuck gallery
posting location picker. The latter already uses MapLibre and is unchanged.

## APK boundary audit

All 12 DEX files in the supplied APK were inspected using JADX 1.5.6 / its DEX reader. Fourteen
state-field descriptors and nine method descriptors were checked against the new bindings.

* `omg.a(Composer,int)` owns the post ViewModel, state collection, navigation and BackHandler.
* Its content lambda `mmg.invoke(Object,Object,Object)` is the only real host caller of the
  Google-map composable `h79.a(...)` (the other caller is its recomposition wrapper).
* `mmg.a/f/h/i/j` are the display state, current-user location, permission state, photo state and
  mutable photo-preview toggle. These Compose states are read during composition, not in an
  asynchronous MapLibre callback, so updates continue to invalidate the screen.
* `rmg.a/b/c` contain the display marker, optional user location and approximate-area flag.
  The ViewModel has already randomized the display marker when precise location is unavailable.
  No raw post-coordinate field is read. The original circle radius is **5,000 metres**.
* `qwb.a/b` supply the existing low/high photo bitmaps. The replacement borrows them, makes its
  own bounded pin bitmap, and never recycles host-owned bitmaps.
* Navigation continues through `tbe.f()`. Permission requests launch the original `pmg` case 3
  in the host ViewModel scope. Old Google camera actions (`pmg` case 2 / `mkg` case 3) are not called.
* The existing host AndroidView composable's eight-argument overload mounts the native view.
  Its release callback destroys the renderer; `onReset` is null to prevent cross-post view reuse.
  In this APK `SizeKt.e` is **fillMaxSize**; `SizeKt.f` is only fillMaxWidth.

All obfuscated names remain in `symbols/`. Resolution validates method static/return/parameter
shapes and field types before hooking. Missing symbols leave the original screen untouched.

## Renderer and lifetime

The already installed MapLibre 13.3.1 dependency renders OpenFreeMap's Liberty style and
OpenStreetMap-derived data. No dependency or provider-setting screen was added.

Post and user markers, photo-preview toggling, the 5 km approximate area, location permission
action, zoom/pan and native back navigation remain available. Recomposition and late photo/GPS
updates do not reset a manually moved camera. Unavailable or invalid coordinates produce no pin,
not a fabricated location. The map is read-only and never edits the post's location or saved data.

Module resources, theme, inflater and class loader are isolated using the existing ModuleUiContext.
The global MapLibre context wraps the application, not the Activity. Attach/detach and Activity
lifecycle events start/resume/pause/stop the map; composition release and Activity destruction
destroy it, unregister callbacks, clear borrowed state and invalidate late callbacks.

Loading errors and timeouts expose a retry button while leaving back navigation and attribution
available. Retry is explicit, not a polling loop. Attribution is visible for OpenFreeMap,
OpenMapTiles and OpenStreetMap, with the SDK's attribution control retained.

Map tiles/styles require internet access. The default endpoint is
`https://tiles.openfreemap.org/styles/liberty`. Viewing a region requests that region's map data
from this third-party provider; the fallback does not upload post IDs, photos or authentication
headers. Basemap appearance/POIs differ from Google Maps; this is not a repair of Google's API
authorization and does not provide Google satellite imagery or offline maps.

## Validation and remaining device checks

The seven new JVM/Robolectric tests exercise display-state subscriptions, privacy flags, invalid
coordinates, photo ownership/toggling, native navigation/permission callback dispatch, the
AndroidView factory/update/release contract, a 5 km geodesic ring across the date line, version
failure behavior, control layout, and idempotent disposal before GPU initialization.

Run the normal verification suite:

```sh
./gradlew :app:assembleDebug :app:checkModule :app:testDebugUnitTest :app:lintDebug
```

The tests do **not** run a real Google service, the Android MapLibre GL renderer, or injected
Compose on an NPatch device. The actual NPatch device still needs a check for tile rendering,
portrait/landscape and floating-window resizing, photo updates, granting location permission,
back/reopen and background/resume. No device/emulator was installed or modified for this change.

In NPatch embedded-module mode, build the updated BeFuck module and embed it when re-patching
BeReal. Updating a separate module installation does not change an already embedded module.
The change contains no uninstall, app-data clearing, account reset or archive migration.
