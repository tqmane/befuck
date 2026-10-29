# NPatch post-map replacement

## Scope and compatibility

The original BeReal post map can fail Google Maps authorization after NPatch repackaging. The module replaces that post-location screen with MapLibre and OpenFreeMap when the installed host has `npatch` manifest metadata. Ordinary Google Play / root-LSPosed installations keep their original Google map. The separate gallery-posting location picker already uses MapLibre.

The shared map resolver is independent of the version tables used by other module features. A host missing from those tables can still receive the map replacement if its map contract is recognized and validated. This does not enable unrelated features on an unsupported BeReal version.

| Host | Map replacement | Current location and permissions |
| --- | --- | --- |
| NPatch, 3.97.0 / 3597523 | Supported | Supported |
| NPatch, 3.97.1 / 3599414 | Supported | Supported |
| NPatch, 3.98.0 / 3600852 | Supported (uses display location and avatar) | Limited (current coordinates are not supplied) |
| NPatch, later versions | Supported if map structure is compatible | Disabled until verified |
| Host without NPatch metadata | Not replaced (keeps original Google map) | Not applicable |

Unknown, ambiguous or incompatible map contracts leave the original screen in place. An unavailable permission helper does not prevent map rendering; an existing host location grant may still supply the current-location marker. The replacement never guesses a future coroutine selector or invokes a Google camera action as a permission request.

## Shared host contract

`PostMapSymbolResolver` discovers the rendered post-map surface through semantic screen markers and verifies its call relationship to the enclosing screen. It resolves the ready-state and photo models, the AndroidView overload, full-size modifier, state interfaces, navigation command and optional post-identity reader. Fresh synthetic model instances establish which fields represent the post coordinate, user coordinate, approximation flag and low/high photo. Application-owned state is not modified by those probes.

Every captured Compose state is read during composition, including while its value is null. Runtime value types distinguish ready, photo and current-location states without assuming a fixed order of obfuscated lambda fields. Missing data remains missing. Multiple candidates are rejected or leave the optional value unavailable.

Full member signatures are cached under a dedicated reader key. Cache identity includes the host and module APK files, including split APKs and same-version rebuilds. Restored members are validated again; a mismatched identity or invalid descriptor causes rediscovery. Clearing resolved signatures also clears this reader cache without deleting saved posts or feature settings.

The audited native permission-request recipes remain in `symbols/`. No obfuscated host identifiers or version branches are added to `BeRealModule` or the shared renderer.

### BeReal 3.98.0

In 3.98.0, the replaced post map uses the host app's display location and avatar. Because current-location coordinates are not passed to the replaced map, centering on the current location is limited.

## Coordinates, photos and saved posts

The Android map dependency now selects OpenGL ES instead of Vulkan. The supplied October 18 log shows a native crash around an NPatch/Vulkan map session, including a file-descriptor ownership violation. This is a compatibility mitigation, **not proof** that the underlying native crash can no longer occur on the device.

The replacement reads the host map's completed display state. It preserves the host's already randomized center and 5,000-metre area when the location is approximate; it does not substitute a raw post coordinate. Post and optional user markers, photo-preview toggling and native back navigation remain available. A missing-location placeholder at (0,0) is suppressed rather than displayed at sea.

When the optional post-identity reader can prove which post the map belongs to, the display coordinate and approximation flag can also be captured for that post's saved record. The author must be known in the current account's cache or archive. Coordinates are read only from the host's actually displayed, already privacy-filtered state, so the separate optional native Friends query need not complete first; the host's approximate marker remains approximate. Account identity is retained across extraction and storage. If the required identity or authorization is unavailable, the map still displays but does not add that saved location. This capture does not edit the BeReal post or change its location-sharing state. See [saved location sources and metadata behavior](saved-post-locations.md).

Host photo bitmaps are borrowed. The renderer preserves their alpha, creates a bounded marker image and never recycles a host-owned bitmap. Recomposition and late photo/current-location updates do not reset a manually moved camera.

## Renderer and lifetime

The existing MapLibre 13.3.1 dependency loads OpenFreeMap's Liberty style. Module resources and class loading use `ModuleUiContext`; the global MapLibre context wraps the application. Attach/detach and Activity lifecycle events start, resume, pause and stop the map. Composition release or Activity destruction destroys it, unregisters callbacks, releases borrowed state and invalidates late work.

Loading failures and timeouts expose an explicit retry action while leaving back navigation and attribution available. OpenFreeMap, OpenMapTiles and OpenStreetMap attribution remains visible, alongside the SDK's attribution control.

The style endpoint is `https://tiles.openfreemap.org/styles/liberty`. Tiles and styles require internet access; viewing a region requests its map data from the provider. Post IDs, photo URLs and BeReal authorization headers are not submitted to that basemap endpoint. Its appearance and points of interest differ from Google Maps, and this feature does not supply Google satellite imagery or offline maps.

## Verification

To run module checks, tests, and lint:

```sh
./gradlew :app:assembleDebug :app:checkModule :app:testDebugUnitTest :app:lintDebug
```

In embedded-module mode, rebuild BeFuck and embed the updated module when re-patching BeReal. Updating a separate module installation does not update a previously embedded copy. Saved-location storage migration is described in [the backup documentation](archive-backup.md).

## Implementation references

- [Shared resolver](../app/src/main/kotlin/dev/tqmane/befuck/symbols/PostMapSymbolResolver.kt)
- [Validated display-state bridge](../app/src/main/kotlin/dev/tqmane/befuck/symbols/PostMapSymbols.kt)
- [Optional audited permission helpers](../app/src/main/kotlin/dev/tqmane/befuck/symbols/PostMapPermissionActions.kt)
- [NPatch renderer and location capture](../app/src/main/kotlin/dev/tqmane/befuck/ui/NpatchPostMapView.kt)
