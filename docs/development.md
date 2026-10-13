# Development and verification

The repository contains one Android application module, `app`. Build with the checked-in Gradle wrapper, JDK 17+ and Android SDK Platform 37. Application code lives in `app/src/main`, Android/JVM regression tests in `app/src/test`, and standalone checks and the icon generator in `checks/`.

## Local verification

Windows (PowerShell):

```powershell
.\gradlew.bat --no-daemon --continue :app:assembleDebug :app:checkModule :app:testDebugUnitTest :app:lintDebug
```

Linux / macOS:

```sh
bash ./gradlew --no-daemon --continue :app:assembleDebug :app:checkModule :app:testDebugUnitTest :app:lintDebug
```

`checkModule` exercises metadata, version guards, authentication boundaries, Compose scope handling and selection snapshots. Unit tests cover archive/merge behavior, media transfers, UI state, lifecycle and synthetic View rendering. Lint checks Android API and resource usage.

| Output | Location |
| --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Unit-test HTML report | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Unit-test JUnit XML | `app/build/test-results/testDebugUnitTest/` |
| Lint reports | `app/build/reports/lint-results-debug.*` |
| Generated synthetic screenshots | `app/build/reports/ui/` |

Generate an unsigned release APK with `:app:assembleRelease`; it is written to `app/build/outputs/apk/release/app-release-unsigned.apk`.

## GitHub Actions

| Workflow | Trigger | Work |
| --- | --- | --- |
| [Debug APK](../.github/workflows/debug-build.yml) | Pull requests, pushes to `main`, or manual dispatch | Verify vendored icons; assemble Debug; run module checks, unit tests and lint; upload the APK and verification reports |
| [Release APK](../.github/workflows/release-build.yml) | Tags or manual dispatch with an existing tag | Build that tag, sign with repository secrets, and publish the APK to GitHub Releases |

CI verifies the checked-in application source. Generated reports, JUnit XML and all UI renders are collected in one verification-report artifact, including on failure. Build outputs stay under `build/` and are not committed back to the branch. The release workflow requires the existing repository signing secrets.

## Maintainer tools and UI snapshots

Tabler icons are vendored under `third_party/tabler/` with their pinned sources and MIT license. Run `python3 checks/export_tabler.py` to regenerate Kotlin paths and vector resources; normal generation is offline. CI checks that regeneration produces no source diff. `--fetch` explicitly refreshes the pinned upstream sources.

`python3 checks/check_known_strings.py` validates the reviewed string inventory, and `python3 checks/check_room_schema.py` validates the corresponding Room table definitions without a private APK or device.

Reviewed synthetic snapshots are kept in `docs/ui/` and updated manually from `app/build/reports/ui/` when a UI change is reviewed. They use local fixtures and do not show a real BeReal session:

- [Gallery](ui/gallery-en.png), [compact Japanese gallery](ui/gallery-ja-compact.png), [large-text gallery](ui/gallery-ja-large-type.png)
- [Calendar and Material controls](calendar-ui.md)
- [Saved camera previews and viewer](saved-media-preview.md)
- [SQLite backup and merge](archive-backup.md)

Builds, lint and JVM/Robolectric tests are separate from device qualification. Host class-loader/resource coexistence, real permission flows, native maps, hardware video playback, audio focus and live CDN behavior require explicit testing in the intended BeReal installation. Do not start an emulator or install/run test apps on a device unless the user requests it. Keep credentials, personal media and runtime dumps out of tracked files and CI artifacts.
