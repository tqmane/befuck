# Daily background feed sync (experimental)

## Scope

BeFuck is an LSPosed/NPatch module. The normal capture hooks persist metadata and signed media URLs from BeReal's own deserialized feed objects into the per-account `ArchiveRepository` SQLite archive. They **do not** persist image/video bytes, and a feed refresh does not enumerate old posts omitted by the host API.

The opt-in setting **Settings → Background synchronization → Sync the feed every 24 hours** adds a background refresh. It is disabled by default. The first run is queued immediately. On each attempt, the worker schedules the next attempt for at least 24 hours later. Android Doze, App Standby, network restrictions and a user force-stop can delay or prevent delivery; it is not an exact wall-clock alarm.

## LSPosed architecture

The LSPosed module does not own a component in `com.bereal.ft`'s manifest. Merely adding a WorkManager worker to BeFuck's module APK would schedule a separate package and would not give that worker BeReal's private storage or authenticated repositories.

Instead, on the known, verified 3.97.x/3.98.0 host variants:

1. The host's `WorkManagerImpl` schedules a uniquely named **one-time** job for the host's existing `androidx.work.impl.workers.DiagnosticsWorker`. Jobs are marked with two BeFuck-only `Data` keys.
2. An Xposed hook intercepts `DiagnosticsWorker.doWork()` **only** when the complete marker is present. The original diagnostics worker runs unchanged for all other tasks. We use a one-time request because the minified 3.98.0 host retains its unique one-time scheduling interface but not the periodic one.
3. The task alternates between private `a` and `b` unique names, with `ExistingWorkPolicy.KEEP`, to schedule the next job while the current job is still RUNNING.
4. The host Koin current-user provider establishes a valid account, then its native force-fetch HomeFeed repository returns the current feed without starting an Activity. Existing deserializer hooks save newly observed posts through `ArchiveRepository`.
5. A worker-thread barrier waits for already-enqueued SQLite writes to finish before marking the sync successful.

No access tokens or HTTP authorization headers are stored by the scheduler. The account scope and generation are rechecked before acknowledging success, so a logout/account switch cannot attribute a stale callback to a new account. Precise GPS capture retains the existing verified-friend/owner restrictions.

## R8 and compatibility

BeReal 3.98.0 R8-minifies many AndroidX symbols, including `WorkRequest.Builder`, `Data.Builder`, `WorkManagerImpl`, and `ProcessLifecycleOwner`. Sync uses typed reflection from the **host** ClassLoader, with no direct dependencies on host WorkManager classes in the module, and uses the static `ProcessLifecycleOwner` singleton instead of a removed/inlined companion accessor.

A host version or WorkManager implementation change can invalidate this bridge. Failure should be fail-closed (no network requests from an unknown account; the normal BeReal diagnostics worker remains unchanged for unmarked work). Refresh and scheduling errors log **failure category only**; no tokens, account identifiers or media URLs should appear in logs.

## UI and operational limitations

- Opt-in toggle is in the module settings. Turning it off prevents subsequent feed fetches and requests cancellation of both unique work names; an already-queued, marked but disabled job is a no-op.
- The job requires connected networking and relies on BeReal's own login and repositories. If the account is logged out, it does not sync.
- This saves post metadata and media URLs. It does **not** automatically download, revalidate or renew the signed media URLs, cache media bytes, or backfill past posts not returned by the feed.
- `last_success` in `befuck_daily_sync` preferences is updated only after a validated account, successful feed response, and archive barrier.
- Running the same feed again relies on the archive's idempotent post/URL merge; it does not delete earlier records.

## Verification

Run the checked-in verification suite:

```sh
./gradlew --no-daemon --continue :app:assembleDebug :app:checkModule :app:testDebugUnitTest :app:lintDebug
```

Verify on a disposable **BeReal 3.98.0** installation with LSPosed enabled: start the app, opt in, verify a marked host WorkManager job is queued, allow background execution and confirm `befuck.daily_feed_sync.*.v1` alternates while `ArchiveRepository` gains only host-returned posts. Confirm an unmarked DiagnosticsWorker remains unchanged; verify logout, account switching, offline/network recovery, repeated attempts, reboot, forced stop, opt-out, and module/app update. Unverified device behavior must not be called complete.
