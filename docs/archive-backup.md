# Saved-post SQLite backup and merge

## User workflow

Open **Saved posts → Backup and merge**. The screen identifies the current account by UID. Export writes a standalone `.sqlite` backup using Android's document picker. Copy that file to a second device logged into the same account, select Import, inspect the new/updated/unchanged/additional-URL counts, and explicitly confirm **Merge without deleting**. To combine both devices completely, import A's backup on B, then export B's combined archive and import it on A. Repeating an unchanged backup is idempotent.

The SQLite file contains post information and media URLs, not image/video bytes. It is unencrypted; usernames, captions and full signed URLs are sensitive. The export excludes passwords, Authorization headers, app settings, runtime logs, the original legacy table and other accounts. A backup cannot make expired URLs valid again, and it does not fetch uncached historical posts. There is no automatic cloud or device synchronization.

## Schema

The existing `befuck-post-urls.db` is upgraded transactionally to schema 2. New writes no longer use the old `posts.data` JSON blob.

| Table | Key | Stored data |
| --- | --- | --- |
| `archive_posts` | `(account_uid, post_id)` | Author UID, username, posted/taken timestamps, derived UTC epoch-millisecond integer columns, caption, moment ID, main/own flags, first/last observation time, and a separate observation timestamp for each nullable field |
| `archive_media` | `(account_uid, post_id, slot, url)` | Back/front/BTS slot, the complete URL including its query signature, MIME type, dimensions, first/last observation time and per-field observation timestamps |
| `backup_info` | One row, backup files only | Format name, interchange version, account UID, export time, post and URL counts |
| `legacy_posts_v1` | Original key, local migration recovery only | Unchanged pre-upgrade JSON records; never used as the new write store or included in backups |

The post table has account/date and account/author indexes; media rows reference the composite post key. Usernames are not identity keys, and URLs are not post identity keys. The same post ID remains independent in different account partitions. Posting date strings are retained along with derived millisecond columns; the calendar continues to use device-local dates.

## Merge policy

Each non-null field carries its original observation time. A more recently observed value wins; equal-time conflicts use a stable value ordering, so import order does not change the result. Null/absent fields never clear known values. An explicitly empty caption is a value, not absence. An invalid date string does not erase a known parseable timestamp. First-seen is the minimum and last-seen the maximum of original observations. Importing never stamps old content with the import time.

Full media URL variants are unioned by slot and exact URL. The currently displayed/downloaded candidate is the most recently observed variant, with a deterministic URL tie-break. Other variants remain in SQLite and subsequent backups. Observation time is not proof of a server-side revision or URL validity: incorrect device clocks or cached host responses can affect candidate ranking. No automatic network validation or fallback-to-every-variant download is claimed.

A conflicting non-empty author UID for the same `(account_uid, post_id)` aborts the entire merge. The importer does not silently pick an author or apply only the non-conflicting half. Existing posts absent from a backup are retained. This format has no deletion propagation or tombstones.

Import first stages the selected document in private app cache, closes the input, validates it, builds a preview, and deletes the staged file. Confirmation uses that validated in-memory snapshot; changing the external file after preview cannot alter the pending merge. The actual commit re-reads current destination rows in one SQLite transaction, so captures that completed after preview are merged rather than overwritten. Preview counts can differ from final counts if the destination changed. Exceptions, author conflicts and cancellation before commit roll back all rows in that merge.

## Account attribution and old data

The host's already-resolved current-user provider and UID field identify the active account. Arbitrary `User` constructors are author/name observations only; constructing a friend's User object must not change the viewer account. This also corrects the former assumption that every observed User UID was an own-account UID.

Authentication-header changes invalidate the in-memory account attribution. The home/activity lifecycle resolves it again through the current-user provider. No bearer token is written to the archive or backup. Open archive screens, queued callbacks and restore/export leases reject an account-generation change. If identity cannot be resolved, export/import are unavailable rather than guessing from the backup or author name.

The old JSON cache had no viewer-account identity. It migrates into an **unassigned** partition, as do observations made before a current account is safely available. No user UID is inferred from the author. A visible notice and explicit **Associate unassigned old data** confirmation let the owner associate those rows with the current account. This operation merges matching records and removes the unassigned copies in one transaction; the original legacy recovery table remains locally. Review the warning especially if the installation previously used multiple accounts. Merely opening the backup screen does not associate anything.

## Snapshot and validation safety

Document-provider byte I/O runs on a separate transfer worker after a storage-readiness barrier. A slow external provider therefore does not occupy the capture/persistence worker. SQLite snapshot and merge transactions still use the store lock. Cancellation is checked between stream reads and transaction steps; vendor providers that block indefinitely inside a read/open may delay transfer cleanup, so this is not a claim of forcibly cancellable cloud I/O.

Export reads a consistent SQL transaction and writes only the selected account to a new, separate SQLite database. The destination database is closed before it is copied to the selected document. It is not a raw copy of the live main file; active data may be in a WAL sidecar. Export failure cleans the private temporary file, not arbitrary user-selected documents. An interrupted external/cloud write can leave an incomplete destination document; do not treat it as a completed backup.

Import opens only the staged file read-only with query-only mode, uses fixed queries, and never ATTACHes it or executes its SQL against the live database. It checks the SQLite header, application ID, supported version, exact table/index definitions, absence of extra schema objects, quick integrity check, foreign keys, account identity, row counts, field types/lengths, observation-time consistency, URL validity and derived timestamp columns. Unsupported/corrupt/foreign-account backups are rejected. A manifest UID is an organization boundary, not a cryptographic signature or authentication credential; users should import only their trusted backups.

Limits are 64 MiB per transfer, 50,000 posts per account, 300,000 URL rows and 1,024 variants per post. Extremely future observation/export times (over 24 hours ahead of this device) are rejected. Captions are bounded at 32,768 characters, URLs at 8,192 and identifiers at 512. The combined destination is checked before committing, not just the imported file. Failed persistence is kept in a bounded in-memory retry buffer with original account and observation times; pending writes block successful backup until they have been persisted. This buffer is not a durable process-crash journal. Do not edit timestamps or change a manifest account to force a foreign backup through validation.

Schema migration retains the original table and rolls back on malformed legacy rows. A non-deleting corruption handler is used for opening the live database, so Android must not silently replace a corrupt original with an empty database. Corruption still requires recovery; this feature does not claim to repair arbitrary damaged SQLite files.

## Verification

Use the checked-in wrapper and JDK 17:

```sh
./gradlew --no-daemon --continue :app:assembleDebug :app:checkModule :app:testDebugUnitTest :app:lintDebug
```

Tests use native SQLite via Robolectric on API 29 and API 33, not just fake DAO methods. They cover typed SQL columns; legacy migration/reopen and rollback; two-device convergence; repeated and reversed merges; signed URL variants; null/empty/stale fields; wrong account and conflicting author; cancellation after a write; preview/commit races; WAL-independent export round trips; invalid schema/version/counts; and corrupt inputs. UI tests use an actual synthetic ContentProvider and the application's Activity-result routing to export a readable SQLite document, validate an import without writing, explicitly confirm it, and reject stale/foreign-account results. A blocked synthetic document-provider test also verifies that new post captures continue while the file transfer is waiting. No fixture URL is downloaded. The backup screen is rendered from its actual View hierarchy using synthetic data, and its rendered pixels have been visually inspected. Retry-buffer tests cover retained failed writes, coalescing and account isolation; the database tests also verify combined-destination limits roll back without making the existing archive exceed the supported size.

The PR's CI and machine-readable test summary record the final executed counts. Compilation alone is not a data-safety or compatibility test. Real BeReal 3.97.1 current-user-provider behavior, in-host resource/class-loader coexistence, vendor/cloud document providers, physical-device storage failures and interrupted processes still require device qualification. No physical device, real account, real media or real saved database was modified during these tests.

## Primary platform references

- [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [SQLite WAL: the sidecar is part of persistent state](https://www.sqlite.org/wal.html)
- [SQLite backup and snapshot considerations](https://www.sqlite.org/backup.html)
