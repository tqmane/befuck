# Saved-post calendar and official Material controls

## Calendar behavior

Saved posts opens in the **Calendar** tab on the current local date. A date selection updates the heading, count and matching posts directly **below the calendar**; it does not launch a separate date-picker dialog. Use the arrows for adjacent months, tap the month heading for a year/month chooser, and use **Today** to return to today. The reference image's teal accent was not copied: BeFuck remains black and white.

A white circle identifies the selected date, an outline identifies an unselected current date, and a separate dot identifies dates with saved posts. Username filtering uses the same predicate for the dots, counts and displayed posts. **All dates** retains the username filter and includes entries whose date is unknown.

Dates are derived from `PostMediaMetadata.timestamp`: valid `postedAt` first, then `takenAt`, converted through the device's local time zone. Cache capture time is not substituted as the posting date. Unknown timestamps are not silently assigned to today. Month browsing does not change the selected date until a day is selected. Leap days, six-row months, year boundaries and navigation bounds are explicitly tested.

This is a browser for the existing local post index, not an API for discovering uncached historical BeReal posts. Browsing it does not fetch remote image previews. Existing back/front/all-media saving is retained, with more explicit labels, partial-save feedback and disabled actions while the selected download is pending.

## Official Material widgets, not painted imitations

The previous `android.widget.Switch` with hand-made track/thumb drawables has been removed. Settings and the gallery's late-post switch use **`com.google.android.material.materialswitch.MaterialSwitch` from Material Components 1.14.0**, under a new, isolated **`Theme.Material3Expressive.Dark.NoActionBar`** theme. The official switch's thumb, track and state animations are not replaced. `Widget.Material3.CompoundButton.MaterialSwitch` is the official switch style; there is no separate custom `ExpressiveSwitch` class in this change.

`ModuleUiContext` uses the module's resources, a newly allocated theme and a cloned inflater. It does not call `setTheme()` on the BeReal Activity or reuse its theme object. The entry point already creates module resources using the host's display metrics/configuration. The actual injected class-loader/resource combination nevertheless still needs device qualification.

Calendar tabs, saved-media actions and the primary posting action use official `MaterialButton`. `ICON_GRAVITY_TEXT_START` keeps each icon with its label instead of leaving an icon at the far left of a stretched button. Other lightweight View-based layouts remain custom; this is not a claim that every screen is an official Material widget or a Compose migration.

All custom icons remain official Tabler 3.49.0 sources pinned to `bbed884d15354b5cebf2493371f20dc2d5e83eaf`. The added calendar icon brings the count to 36. `TablerIconDrawable` now implements tint lists, state changes, explicit color-filter precedence and alpha correctly, including white-to-black tint changes on selected buttons.

Primary sources: [Material Switch guidance](https://github.com/material-components/material-components-android/blob/1.14.0/docs/components/Switch.md), [Material themes](https://github.com/material-components/material-components-android/blob/1.14.0/lib/java/com/google/android/material/theme/res/values/themes.xml), [Tabler sources](https://github.com/tabler/tabler-icons/tree/bbed884d15354b5cebf2493371f20dc2d5e83eaf).

## Layout refinement

Settings is organized into grouped rows, with outer/inner corner hierarchy and distinct Tabler icons for adding rules, browsing rules, recovery tests and logs. Settings, repair lists and gallery are capped at 720dp, the saved-post screen at 760dp and its calendar at 560dp. The caps are measured against the actual window so a tablet or split-screen window does not force every row across the full display.

At large font sizes the calendar/all-date controls stack vertically. Date labels are measured against their cell using a two-digit reference and actual glyph width, with rounding space for bold selected digits. Selection fill and post dots are separate Views rather than drawing outside TextView's clipped text area. The month heading fits its available width. Calendar accessibility exposes the full date, count and selected state; keyboard focus is retained across day selection. Read-only log dialogs now use one Close action.

## Regression coverage and reviewed renders

The follow-up adds 24 tests to the prior 23:

| Tests | Count | Evidence exercised |
| --- | ---: | --- |
| SavedCalendarModelTest | 11 | All months 2000–2100, leap years, six-week grids, year limits, local-midnight boundaries, timestamp precedence, unknown dates, search and snapshot deduplication |
| CalendarScreenTest | 7 | Real day clicks and lists below, all-date/today navigation, search/year chooser, queued cancellation, refresh races, loader failure/retry, actual MaterialSwitch preference changes, phone/tablet/large-text layouts |
| TablerTintTest | 4 | Actual raster colors after tint/state changes, clearing filters and MaterialButton icon tinting |
| CalendarDayPixelsTest | 2 | A visible selected-date fill and a visible post dot outside the text area |

All **47 tests passed, with no failures or skips**, in [run 38353353751](https://github.com/tqmane/befuck/actions/runs/38353353751), along with assembly, `checkModule` and lint. Existing lint warnings are not claimed to be eliminated. The normal PR workflow repeats these checks and publishes a machine-readable test summary.

The rendered pixels were inspected, not merely generated. This exposed an invisible selected-tab icon and clipped/missing date-selection details that a successful build did not detect. Their fixes have dedicated tint/pixel and layout assertions. These images draw the **actual View hierarchy using synthetic local fixtures** on Robolectric Android 13/API 33 with native graphics, not a separate mock-up or a real BeReal session:

- [Calendar with the selected day's posts](ui/calendar-ja.png)
- [Bounded tablet calendar](ui/calendar-tablet.png)
- [320dp calendar at 1.8x font size](ui/calendar-large-type.png)
- [Grouped settings with official Material switches](ui/settings-material3expressive.png)

No real usernames, media or account tokens are used by these fixtures. Their `example.invalid` media URLs are not fetched. Static renders do not validate real-time animation performance.

## Reproduce

```sh
python3 checks/export_tabler.py
./gradlew --no-daemon --continue :app:assembleDebug :app:checkModule :app:testDebugUnitTest :app:lintDebug
```

The regular PR workflow is read-only. It publishes the module debug APK, XML/HTML reports, `test-summary.json` and separate calendar/settings render artifacts. Normal icon generation uses the vendored SVGs; no icon network fetch is required. The temporary source-migration workflow is not part of the final change.

## Remaining device qualification

No physical device or emulator was installed or changed, and no authenticated posting or real-account network request was exercised. In particular, the official Material widgets' interaction with the intended BeReal 3.97.1 host resources/class loader and actual rendering/animations remains a device test, not something inferred from compilation or Robolectric success. Existing map, media and authentication qualification limits in the baseline audit also remain.

The loader-failure and cancellation tests exercise the screen's injectable loader/queue; they are not a claim that a physically corrupted SQLite database or every storage error has been reproduced on a device. A passing test suite is not a guarantee that all application bugs have been eliminated.
