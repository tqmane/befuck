# Contributor instructions

## Scope and layout

- Read the working tree, affected code, and all callers before changing shared behavior. Preserve unrelated changes.
- `app/` is the only Android application module (`dev.tqmane.befuck`).
- `app/src/main/java/dev/tqmane/befuck/BeRealModule.java` owns the libxposed entry point and hook registration. Keep version numbers and obfuscated class names out of it.
- Kotlin sources live under `app/src/main/kotlin/dev/tqmane/befuck/`:
  - `symbols/`: Version-specific symbol mappings (`KnownMappings<version>.kt`), shared reflection helpers (`HostMappings.kt`), DexKit dynamic resolution (`BeRealSymbolResolver.kt`), and signature caching (`SymbolCacheCodec.kt`).
  - `runtime/`: Runtime state, persistence, settings, Compose state tracking, and authentication compatibility helpers.
  - `posting/`: Gallery post requests, media preparation, and upload pipeline orchestration.
  - `ui/`: Module UI, dialogs, media preview/cropping, and user interaction.
  - `download/`: Feed media capture, metadata extraction, and saving.
- Assets: Versioned signature presets live in `app/src/main/assets/befuck/`.

## Architecture and implementation principles

- **Minimal, focused changes**: Prefer minimal, direct solutions. Avoid speculative abstractions, unnecessary dependencies, or reinventing platform/host APIs.
- **Symbol encapsulation**: Keep version numbers and obfuscated host symbols isolated within `symbols/`. Never reference obfuscated host names across modules or translate one version's names directly into another.
- **Host boundary and safety**: Respect host app boundaries, API contracts, selection snapshots, and interface validations. Do not bypass host integrity checks carelessly.
- **Resource and lifecycle management**: Always release heavy resources (players, surfaces, bitmaps, and listeners) when UI components dismiss or the host activity pauses.
- **Security and privacy**: Keep tokens and credentials strictly in memory. Never commit credentials, private keys, personal data, local paths, or runtime dumps.
- **Localization and documentation**: Maintain user-facing strings in Android resources (both default English and Japanese). Keep all four README files (`README.md`, `README.ja.md`, `README.zh-CN.md`, `README.ko.md`) consistent.
- **Attribution and licensing**: Preserve third-party notices and comply with the PolyForm Noncommercial 1.0.0 license.

## Validation

- Use the checked-in Gradle wrapper with JDK 17+ and Android SDK Platform 37.
- Windows: `./gradlew.bat :app:assembleDebug :app:checkModule :app:lintDebug`
- Linux/macOS: `./gradlew :app:assembleDebug :app:checkModule :app:lintDebug`
- Keep small, runnable regression checks in `checks/`; build output belongs under `build/`.
- Device testing: Do not start an emulator or install/run test apps on a device unless explicitly requested by the user. Rely on Gradle builds, `:app:checkModule`, and lint.
- Report build, static, and device verification separately.
