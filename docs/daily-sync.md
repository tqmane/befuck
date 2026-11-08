# Background feed sync

Enable **BeFuck settings → Background synchronization** and choose a sync interval: 1, 2, 3, 6, 12 or 24 hours. The default interval is 3 hours; synchronization is initially disabled. Enabling it queues an immediate first refresh. Changing the interval replaces the pending job; disabling it cancels BeFuck jobs.

The host app fetches its current authenticated feed without opening an Activity. Existing archive hooks merge returned post metadata and media URLs into Saved posts. Existing records are retained. This does not download photo/video files or recover older posts missing from the current feed.

Jobs require a network connection and use BeReal's own WorkManager. Only privately marked diagnostics jobs are intercepted; ordinary diagnostics run normally. No authentication tokens are saved by the scheduler. Account identity is checked before fetching and after completion. Android Doze, app restrictions and force-stop can delay execution.

Based on [PR #19](https://github.com/tqmane/befuck/pull/19), with a configurable interval and updated 3.99.1 contracts.

## Recovery

Unknown host versions retain the + entry and settings while semantic symbol discovery runs. Functions needing an unverified version mapping remain unavailable. On hosts without the old protected string runtime, **Test automatic recovery** tests fresh symbol discovery from the APK without presets or saved signatures, then restores the original cache and preferences. The old protected-runtime probe is retained for versions that need it.

## Validation

Run `./gradlew :app:assembleDebug :app:checkModule :app:testDebugUnitTest :app:lintDebug`. Physical validation must separately cover the selected host version, schedule updates, background archive writes and cancellation. Device verification details are kept with the local build output.
