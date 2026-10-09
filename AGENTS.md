# Contributor instructions

## Scope and layout

- Read the working tree, affected code, and every caller before changing shared behavior. Preserve unrelated changes.
- `app/` is the only Android application module (`dev.tqmane.befuck`).
- `app/src/main/java/dev/tqmane/befuck/BeRealModule.java` owns the libxposed entry point and hook registration. Keep version numbers and obfuscated class names out of it.
- Kotlin sources live under `app/src/main/kotlin/dev/tqmane/befuck/`:
  - `symbols/`: Version-specific mappings (`KnownMappings<version>.kt`), shared reflection helpers (`HostMappings.kt`), DexKit dynamic resolution (`BeRealSymbolResolver.kt`), and signature caching (`SymbolCacheCodec.kt`).
  - `runtime/`: Persisted settings and cache management (`RuntimeKnowledge.kt`), Compose scope tracking, SMS authentication compatibility, and native library loading.
  - `posting/`: Gallery post requests, media snapshots, video crop/trim models (`VideoEdit.kt`), and Media3 video export (`Media3VideoCompressor.kt`).
  - `ui/`: Posting session UI (`BeFuckGalleryUi.kt`), image crop dialog (`CropDialog.kt`), and video crop/trim dialog (`VideoCropDialog.kt`).
  - `download/`: Feed media capture, metadata extraction, and media saving actions.
- Assets: Versioned signature presets live in `app/src/main/assets/befuck/`. Version-specific lifecycle DEX under `assets/pairip/` belongs strictly to the protected runtime; do not apply it to other versions.

## Implementation principles

- Reuse existing helpers and platform APIs. Prefer minimal, focused changes; avoid speculative abstractions or unnecessary dependencies.
- Video processing:
  - Crop into portrait 3:4 aspect ratio. Respect source video rotation metadata.
  - Max duration for selected intervals is 30 seconds.
  - Front and back video durations must be aligned. Retain original sources for re-editing.
  - Ensure lifecycle cleanup: release video players, surfaces, and frame bitmaps when dialogs close; pause playback when the host activity pauses.
- Preserve host boundaries, selection snapshots, lifecycle cleanup, and interface-token validations.
- Never commit credentials, signing keys, personal data, local absolute paths, or runtime dumps.
- Keep user-facing strings in Android resources (maintain both default English and Japanese resources). Keep all four README files (`README.md`, `README.ja.md`, `README.zh-CN.md`, `README.ko.md`) consistent.
- Preserve third-party attribution and the PolyForm Noncommercial 1.0.0 license.

## Validation

- Use the checked-in Gradle wrapper with JDK 17+ and Android SDK Platform 37.
- Windows: `./gradlew.bat :app:assembleDebug :app:checkModule :app:lintDebug`
- Linux/macOS: `./gradlew :app:assembleDebug :app:checkModule :app:lintDebug`
- Keep small, runnable regression checks in `checks/`; output goes under `build/`.
- Device testing: Do not start an emulator or install/run test apps on a device unless explicitly requested by the user. Rely on Gradle builds, `:app:checkModule`, and lint.
- Report build, static, and device verification separately.
