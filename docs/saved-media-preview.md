# Saved camera previews and full-screen viewer

Saved-post cards now contain two compact, rounded camera previews, with explicit back/front labels and separate download buttons. Tapping a preview opens a full-screen workspace for that **one post** at the tapped camera. Horizontal swipes or camera/arrow buttons switch between its available back/front media; paging never moves into another author's post. Missing cameras are not invented. Photos fit inside the screen without cropping and support pinch/double-tap zoom and panning. A zoom gesture is not a camera swipe. Vertical drags and cancelled gestures are not paging actions.

The card retains Save all media. The viewer offers Save this camera and Save all media, independent of whether image decoding has succeeded. Save requests snapshot the post/side at the time of the tap: a later swipe cannot change what is being saved. Card and viewer actions share a coordinator, so an in-flight all-media save cannot overlap another save of the same post from the other UI. Explicit saves continue when the viewer is closed. The downloader keeps the original account/authentication snapshot and exact URL set; a new account cannot inherit its queued credentials or coalesced operation. Downloaded media still uses the existing metadata-writing/MediaStore pipeline, not preview-resolution bitmaps.

## Loading, video and lifecycle

Only cards intersecting the visible viewport request thumbnails. Scrolling them out of view, filtering/rebuilding rows, opening the full-screen viewer, backgrounding the Activity, or closing the saved-post screen cancels/relinquishes old requests. Account-generation guards apply before network operations and again before displaying callbacks. Stale images from a different page and late video files are discarded rather than changing the active camera. A screen-scoped 16 MiB memory cache avoids needless repeat requests; it is cleared on screen teardown and is not part of SQLite backup/export.

Images are staged temporarily with a 24 MiB encoded-byte limit and decoded on a worker with ImageDecoder into bounded software bitmaps (512px thumbnail edge, 2048px full-screen edge). Orientation is handled by the decoder. Full-resolution downloads are unaffected by this display-resolution limit. Animated image previews are decoded as a still bitmap.

Video posters use MediaMetadataRetriever with a bounded MediaDataSource implementation. It performs validated 128 KiB HTTP range reads, retains up to eight blocks, and caps poster traffic at 8 MiB per attempt. A server ignoring Range can supply a complete small video within that same budget; a large response or inconsistent ranges fail to a retryable placeholder instead of downloading the whole video for every row. URLs must stay HTTPS without user-info/fragments. Authentication is anonymous first and retried only for the exact trusted BeReal origin that returned 401, not forwarded to an unrelated redirect. Failed HTML/error/truncated responses are not decoded as media. URL signatures/headers are not written into logs.

Videos do **not** autoplay in the list or on opening the viewer. Press Play to stage the video in private cache (up to 512 MiB) and play the local file using MediaPlayer/TextureView. Controls provide play/pause and seeking, request transient audio focus, and release playback, surfaces, files and requests on camera change, Activity pause, or dismissal. Resuming the Activity restores the selected preview, not automatic audio playback. Failed/expired/unsupported previews show an explicit error and retry while leaving original-media saving available. Decoder/native or vendor/provider blocking operations are not guaranteed to terminate instantly; HTTP timeouts and cancellation checkpoints bound normal I/O.

Temporary preview media is deleted on normal success/failure/close paths. Previewing necessarily contacts the media URL's server and uses data; it is no longer a zero-media-network URL-index screen. SQLite continues to contain URLs and metadata only. Previewing does not repair expired URLs or search for uncached posts.

## Compatibility correction from the previous PR review

A host media-kind value such as `VIDEO`, paired with a URL lacking an extension, now retains video classification when normalized into the archive (`video/mp4` fallback). Proper MIME subtypes remain preferred. A regression test covers this formerly uncommitted fix. This does not alter post/account keys or merge semantics.

## Verification

```sh
./gradlew --no-daemon --continue :app:assembleDebug :app:checkModule :app:testDebugUnitTest :app:lintDebug
```

New tests exercise camera paging boundaries and initial side, inline/full-screen save routing, overlapping save suppression, stale completion/account handling, actual View swipes, no-load-before-visible behavior, late image/video cleanup, missing/failed previews, worker decode dimensions and EXIF rotation, HTTPS/authentication redirect rules, range correctness/cache/limits, invalid response rejection and original download account snapshots. Tests use synthetic data and fake connections instead of downloading real users' media. The UI test renderer uses the actual card/viewer hierarchy, not an independently drawn mock-up.

Native video codecs, hardware TextureView playback, audio-focus behavior between real apps, gesture feel, live CDN Range support and injection in BeReal 3.97.1 still need device qualification. No physical device, emulator, real account, real media or existing saved SQLite database was modified. Successful compilation and Robolectric tests do not establish that every host-dependent path works. The PR's CI/test-summary and render artifacts record what was actually executed.

Primary platform contracts: [ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder), [MediaMetadataRetriever](https://developer.android.com/reference/android/media/MediaMetadataRetriever), [MediaDataSource](https://developer.android.com/reference/android/media/MediaDataSource), [MediaPlayer](https://developer.android.com/reference/android/media/MediaPlayer).
