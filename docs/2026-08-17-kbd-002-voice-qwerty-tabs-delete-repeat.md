# Task Report: KBD-002 interaction follow-up

## Result

DONE

## Scope

- Implemented: separate Voice/QWERTY input tabs; voice-first ordinary-field page with a central
  112dp microphone; compact rounded and staggered QWERTY rows; bounded backspace repeat.
- Not implemented: speech backend changes, Rime behavior changes, theme/height settings, swipe
  switching, store/plugin work or a signed release.

## Changes

- `KeyboardInputModeLayout`: capability-free, exactly-one-visible-page tab container; sensitive
  policy hides Voice and forces QWERTY; active/preparing dictation temporarily locks page switching
  so its stop control cannot be hidden.
- `LatinKeyboardLayout` / `BoundedDeleteRepeater`: plain Space and deterministic delete repeat with
  immediate first delete, 320ms repeat delay, 58ms interval and 120-delete hard limit.
- `OpenTypelessImeService`: reuses the existing continuous-dictation callback for the large Voice
  button and the existing keyboard transaction callback for every repeated delete.
- bilingual IME resources and a theme-adaptive vector microphone.

## Architecture

- contracts: tab View receives no editor/network/native capability; repeat scheduler receives only
  one bounded delete callback.
- state changes: input-page selection is process/session UI state only; no persistence change.
- migration: none.
- feature flag: existing Route-A Shell flag only; no new flag.

## Security & privacy

- data sent/stored: none added.
- permissions/components: none added.
- threat considerations: repeated deletes still route one-by-one through the existing
  `EditorSessionManager`/ETM target validation. Release, cancel, input disable, input finish, window
  hide and service destruction cancel pending callbacks. Sensitive fields cannot open Voice.

## Tests actually run

| Command / check | Result | Notes |
|---|---|---|
| targeted `BoundedDeleteRepeaterTest` + AndroidTest compilation | PASS | 3/3 repeater cases |
| Debug + AndroidTest assembly | PASS | manifest boundary also passed |
| `LatinKeyboardLayoutInstrumentedTest` + `KeyboardInputModeLayoutInstrumentedTest` | PASS | 16/16 on API35 arm64 and Xiaomi 10 Ultra |
| full `:app:testDebugUnitTest` | PASS | 1059 tests; 0 failure/error/skipped |
| `:app:lintRelease :app:assembleRelease` | PASS | unsigned Release; lint has 8 warnings and 0 errors |
| Xiaomi system-selected IME tab traversal | PASS | Voice first; accessibility action opens QWERTY; 1/1 |
| Xiaomi physical long-press check | PASS | 20 chars -> 15 after one 800ms hold; cleanup hold emptied field |

Two non-product failures were retained during validation: the first system-IME probe ran while the
phone screen was asleep; a later tab assertion expected only the enabled microphone description
while an existing protected draft correctly made the button unavailable. The final test accepts
both bounded UI states and passed.

## Evidence

- Debug APK: 65,721,319 bytes; SHA-256
  `81e07253b869c95805ae7d109d021990a75ba1342cc7ce2ac13f15e94ac42fc4`.
- AndroidTest APK: 1,113,775 bytes; SHA-256
  `44c1e0977a8a5aa0465eeb84e821ed8647da65d0059d9a280caf60fcbe0c9f78`.
- unsigned Release APK: 63,607,185 bytes; SHA-256
  `08c41cf8304719c04b7bfdbce853bdcfe1f22e3b718790482ce353f316dc4a2f`.

## Risks

- This is the current programmatic IME View, not a complete Material theme editor or signed store
  release.
- The central button reuses existing continuous dictation; recognition quality is owned by the
  configured provider/model and was not re-benchmarked here.

## Rollback

- Remove `KeyboardInputModeLayout` and the Voice page wiring to restore direct QWERTY display.
- Remove `BoundedDeleteRepeater` and its touch listener to restore single-click delete.
- No data rollback is required.

## Follow-ups

- `KBD-009`
- `KBD-012`

## Git

- branch: `agent/android-offline-followup`
- commit: not created
- worktree status: pre-existing shared changes remain; task files are unstaged
