# Saved post locations, daily maps and image GPS

## Available location sources

Locations are observations of posts the host has actually loaded. The module does not download a historical location catalog or reconstruct locations for existing saved records.

| Observed host data | Capture rule | Stored precision |
| --- | --- | --- |
| Own CorePost location | The resolved author UID must match the retained current-account scope | Exact |
| Friend CorePost location in the normal feed | The author UID must appear in the host's validated complete Friends query for the same retained account scope | Exact |
| MapPlaceholder location | Validated post ID, native `isFriend` flag true, and a known current-account post author; no additional Friends-query prerequisite for the host-displayed point | Exact |
| NPatch post-map ready state | Identified post with a known current-account author and a completed host display state | Exact or approximate, matching that state |
| Missing, invalid or unproven data | No new location observation | Existing data retained |

Friend capture observes an existing native database query; it does not start a query or change its result. The observer validates the actual complete relationship query, all three current-viewer bindings and the host's `Friends` enum conversion. It requires every row to be read, the statement to be closed and the returned list to match those rows. Requests, follows, blocks, partial or malformed results do not establish friendship. The query shape and display-precision behavior were audited in both supplied APKs; a changed or ambiguous contract disables this source.

Each accepted complete query replaces the account's in-memory friend set, including an empty result. A delayed older query cannot undo a newer accepted snapshot. Removing a friend prevents subsequent raw-coordinate capture for that author; it does not delete historical locations already saved. Changing accounts clears the friend set and pending models. Until friendship is established, up to 200 original host models are retained only through weak references, without reading their raw coordinates. When a new complete friend set arrives, still-live models can supply their locations. Clearing archive memory drops the cached posts and pending references while retaining the current account's verified friend set.

For MapPlaceholder, the host's native `isFriend` flag must be true, the coordinate must be usable, and the saved post must have a known author in the current account. Since the host has already decided which location it may display, this source does not require a second observation of the optional Friends SQL query. If the map placeholder precedes the CorePost author, the module retains only a bounded **weak reference to the original host model**, without saving coordinates; it rechecks the host's flag and post identity after the owner arrives. NPatch display-state capture likewise requires the current account's known post author and uses the actual host-rendered coordinate with its approximation flag, rather than reading raw post data. Account generation changes drop pending references and stale observations.

None of these paths infers friendship from a username, a feed category, a field's position or a similarly shaped object. CorePost capture does not treat every other author's raw coordinate as exact. Saving or skipping an observation does not alter the original post-map renderer.

A normal-feed friend post can therefore acquire a location without opening its map once the current account and native Friends graph are available. Coverage still depends on those observations and the loaded post models. Some saved posts have no location, including old records, posts without coordinates and models that were released before friendship could be established. It is not guaranteed that scrolling a feed supplies locations for every friend. Reopening a post or its map can add a location when the required identity and precision data are available; there is no automatic old-record backfill.

Capture keeps the post ID, account scope and coordinate together. If the account generation changes during extraction, the observation is rejected. The value contains a complete finite latitude/longitude pair plus an explicit precision flag. Null is absence. The pair **(0,0)** is also a host placeholder for posts that did not share a location, so new observations at that pair are rejected. Historical 0,0 values remain readable in existing databases and backups, but they do not produce map markers or GPS metadata. Other valid equator/prime-meridian coordinates remain supported.

## Daily saved-post map

Open **Saved posts**, select a calendar day and choose **Show this day on map**. The map uses that device-local posting date and the current author filter. Its immutable snapshot includes all matching posts with locations, independently of the timeline's 40-post page size. The entry and screen report how many posts have saved locations and how many do not.

All located posts are included as points. Photo pins load asynchronously for the viewport, with at most 64 photo textures retained at once; other points remain selectable. Panning or selecting another post updates that set. Failed or expired photo URLs do not remove a location point. Photo previews use the existing media transport and bounded memory cache, while the basemap uses MapLibre/OpenFreeMap.

Tap a photo or point to identify its account. Repeated taps cycle through stacked hits, and previous/next controls can visit every located post. The card shows the account, local posting time, caption and an approximate-location label when applicable. An approximate selection also displays the same 5 km area used by the original post map. **Open post** opens the existing two-camera viewer and download actions for the selected post.

The saved map reads persisted post coordinates and requires no device-location permission. Its renderer and thumbnail requests are released on dismissal, Activity pause/destruction and window-focus loss; returning from the viewer restores the camera. An account change closes the parent saved-post screen and its map. Map styles, tiles and media previews still require network access; signed media URLs can expire.

The original post-map replacement remains [NPatch-only](npatch-post-map.md). This saved-post map is a module archive feature and does not replace a root-LSPosed host's Google map.

## GPS metadata on image download

The existing download metadata writer adds GPS latitude/longitude and their hemisphere references to **JPEG, PNG and WebP** when the saved location is exact. A location can be written even if the posting timestamp is missing. When a timestamp is available, the existing date/time, offset, author, caption and file-time behavior continues.

An approximate map center is not a photographic GPS fix. The writer does not encode it as exact GPS and clears inherited positional GPS tags in that case. Exact-coordinate replacement also clears stale positional GPS attributes such as altitude and GPS time before writing the new pair, because those additional measurements were not observed for the post. An absent location or the 0,0 legacy placeholder does not invent GPS coordinates.

This change adds no MP4 GPS writer. Existing MP4 timestamp handling continues. It does not rewrite files already saved to the device; download again after a location observation becomes available to include it in a new image file.

## Storage and compatibility

Database schema 3 stores the coordinate and precision as one nullable observed value. It does not merge latitude, longitude and precision independently. Later media-only observations cannot erase known location. Schema-2 archives gain nullable columns without losing old records; those old records initially remain without locations.

Backup format 2 includes saved locations and their observation times. Older schema-2 / format-1 backups remain importable; missing location in an old backup does not delete newer destination data. Backups remain unencrypted and include any saved coordinates. See [backup and merge](archive-backup.md) for account isolation, validation and limits.

## Verification scope and source references

Synthetic regression cases cover 0,0 placeholder suppression and legacy-backup retention, host-visible map coordinates before optional friend-query completion, late author arrival and account isolation, raw CorePost friendship validation, exact-GPS image writing with and without timestamps, suppression of approximate GPS, schema migration and backup compatibility, local-day/author filtering beyond pagination, stacked marker selection and UI disposal without initializing GL. The current build/test results must be reported separately. These cases do not prove real-host capture coverage, expired-URL recovery or MapLibre rendering on a device.

- [Location capture and account scope](../app/src/main/kotlin/dev/tqmane/befuck/download/FeedPostMediaCache.kt)
- [Resolved CorePost and placeholder readers](../app/src/main/kotlin/dev/tqmane/befuck/symbols/FeedMediaSymbols.kt)
- [Complete native Friends query observer](../app/src/main/kotlin/dev/tqmane/befuck/symbols/FriendGraphSymbols.kt)
- [Validated location value](../app/src/main/kotlin/dev/tqmane/befuck/download/PostLocation.kt)
- [Image metadata writer](../app/src/main/kotlin/dev/tqmane/befuck/download/PostMediaMetadata.kt)
- [Daily map and selection](../app/src/main/kotlin/dev/tqmane/befuck/ui/SavedPostsMapScreen.kt)
- [Archive representation and merge](../app/src/main/kotlin/dev/tqmane/befuck/archive/ArchiveRecord.kt)
