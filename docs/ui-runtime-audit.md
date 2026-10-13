# UI and runtime verification

## Current implementation

See [Saved-post calendar and official Material controls](calendar-ui.md) for the current UI, interaction contract, test coverage and reviewed synthetic screenshots.

The follow-up adds an embedded saved-post calendar, official `MaterialSwitch` controls under an isolated `Theme.Material3Expressive` theme, Material buttons with centered icon/text, bounded tablet layouts, and 24 additional regression tests. The suite now contains 47 tests. All 47 passed in the [implementation verification run](https://github.com/tqmane/befuck/actions/runs/38353353751), together with `assembleDebug`, `checkModule` and `lintDebug`. The regular pull-request workflow repeats verification on the final PR commit.

## Original audit history

The [initial UI/runtime audit](ui-runtime-audit-baseline.md) is retained unchanged as a historical record. Its references to 23 tests, 35 icons, custom View-only controls and unavailable visual review describe that earlier baseline, not the current follow-up. There are now 36 pinned Tabler icons. The calendar/settings follow-up uses official Material Components where described and its actual View renders have been inspected.

The original fixes to media ownership, stale location/picker results, downloads, cache JSON and video lifecycle remain in place. No host symbol presets, version numbers or user data are changed by this UI iteration.

## Verification boundary

Successful compilation, lint, JVM/Robolectric tests and inspection of synthetic View renders do not establish that all BeReal functionality is bug-free. Real-device BeReal 3.97.1 injection, host class-loader/resource coexistence, permission dialogs, native map rendering and hardware media processing still require device qualification. No physical device/emulator or real account was used for these tests.
