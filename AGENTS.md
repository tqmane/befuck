# Contributor instructions

## Scope and layout

- Read the working tree, affected code, and every caller before changing shared behavior. Preserve unrelated changes.
- `app/` is the only Android application module. Its package and resource namespace are `dev.tqmane.befuck`.
- `app/src/main/java/` owns the libxposed entry point and hook registration; `app/src/main/kotlin/` owns runtime recovery, symbol resolution, posting, downloads, and UI.
- Put version-specific names, member signatures, and confirmed repair values in `app/src/main/kotlin/dev/tqmane/befuck/symbols/KnownMappings<version>.kt`. Gate them by both version name and code, and validate member types.
- `RuntimeKnowledge.kt` manages persisted knowledge and invalidation; `BeRealSymbolResolver.kt` manages DexKit queries and validated reflection signatures. Do not duplicate their responsibilities.

## Implementation

- Reuse existing helpers and platform APIs. Prefer the smallest change that fixes the shared cause; avoid speculative abstractions and dependencies.
- Preserve authentication host restrictions, selection snapshots, lifecycle cleanup, and interface-token validation. Do not guess missing strings or choose ambiguous symbol candidates.
- Keep credentials in memory. Never commit credentials, signing keys, personal information, device identifiers, local absolute paths, runtime dumps, or private artifacts.
- Take environment configuration from environment variables or ignored local settings. Use repository-relative paths in documentation.
- Keep user-facing text in Android resources. Maintain English and Japanese resources, and keep the four README files consistent with implemented features and compatibility limits.
- Preserve third-party attribution and the PolyForm Noncommercial 1.0.0 license. Do not change or silently regenerate the version-specific lifecycle DEX.

## Validation

- Use the checked-in Gradle wrapper with JDK 17 or later and Android SDK Platform 37.
- Windows: `./gradlew.bat :app:assembleDebug :app:checkModule :app:lintDebug`.
- Linux/macOS: `./gradlew :app:assembleDebug :app:checkModule :app:lintDebug`.
- Keep small, runnable regression checks in `checks/`; put generated output under `build/`.
- Verify package metadata and the Xposed entry point after identifier changes. Build/test success does not prove runtime compatibility: report build, static, and device verification separately.
- Select a target device explicitly for device work. Installation, restart, data removal, posting, comments, and messages must remain within the requested scope.
