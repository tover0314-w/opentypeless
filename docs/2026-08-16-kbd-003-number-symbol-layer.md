# Task Report: KBD-003 Number and Symbol Layer

## Result

DONE

The product Route-A keyboard now provides a `123` entry, two deterministic symbol pages, an `ABC`
return path and one fixed long-press alternate for every ASCII letter key. All emitted text shares
KBD-002's single bounded callback and existing `EditorTransactionManager` authority.

## Scope

- Implemented: primary numbers/common symbols, a second extended-symbol page, page switching,
  return to letters, fixed long-press alternates, bilingual accessibility descriptions, layout
  snapshot/input tests and fail-closed source gates.
- Not implemented: field-specific automatic layouts, long-press popup previews, sound/haptics,
  repeated delete, Emoji, toolbar/candidates or Rime.

## Changes

- `LatinKeyboardState.java`: closed `LETTERS` / `SYMBOLS_PRIMARY` / `SYMBOLS_SECONDARY` process
  state; symbol entry resets Shift and page switching fails closed from the letter layer.
- `LatinKeyboardLayout.java`: exact three-row inventories for both symbol pages, `123`/`ABC` and
  page controls, and exact per-letter long-press alternates; the root remains the same compact
  four-row View.
- resources/tests: localized content descriptions, two new JVM state tests and three Android View
  cases for page input, the exact layout snapshot and consumed long-press gestures.
- `symbol_keyboard_contract.py`: seven hostile fixtures and a preflight hook lock the inventories,
  state graph, single dispatch and absence of editor/native/network/reflection capability.

## Architecture

- contracts: symbols are bounded constant strings emitted through `LatinKeyboardLayout.Listener`;
  the View never receives editor authority or arbitrary key codes.
- state changes: layer/page/Shift state is process-local and resets when the user leaves letters.
- migration: none.
- feature flag: the existing KBD-001 service-lifetime Route-A rollback flag is unchanged.

## Security & privacy

- data sent/stored: none; no history, learning, network or persistence was added.
- permissions/components: none added or changed.
- threat considerations: long press consumes its gesture and produces exactly one alternate;
  hidden/disabled layers cannot dispatch; symbol and long-press text still uses the sole ETM path
  with current generation/selection/fingerprint validation.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `scripts/verify_android.sh preflight` | PASS | 119 scripts, 11 Android-script, 135 architecture and 10 mobile-voice tests; KBD-003 source gate PASS. |
| `scripts/verify_android.sh unit` | PASS | Clean app JVM 961/961, architecture-gate 113/113 and Debug/Release compiled 2/2; 68/68 tasks executed. |
| Strict Debug/unsigned Release/AndroidTest assemble + `lintRelease` | PASS | 115 tasks: 78 executed, 37 up-to-date; both manifest gates PASS. |
| Exact KBD-003 View class on API35 ARM64 emulator | PASS | Main/test install Success; `OK (7 tests)`, instrumentation code `-1`. |
| Exact KBD-003 View class on Xiaomi 10 Ultra | PASS | Main/test overlay install Success; `OK (7 tests)`, code `-1`; default remained PangIME. |
| System-selected final APK on API35 ARM64 emulator | PASS | Real Test Host field received exact `1@?[1`: primary-page `1@?`, secondary-page `[`, then long-press `q` produced only `1`; `mInputShown=true`; LatinIME restored. |
| First unpaced ADB tap sequence | FAIL — superseded | The first digit tap arrived during page relayout and was not a valid stabilized UI action. The final sequence waits after each page transition and passes exactly. |

## Evidence

- Debug APK: 56,448,617 bytes, SHA-256
  `5c91b8cc4b868faa77c8a628d275b9071a741003d9507c8d1168a9d42a56a129`.
- Unsigned Release APK: 54,656,009 bytes, SHA-256
  `d1a7a9a179819209c3913b3811b58bf2711a144fa4e97837d669f6f2715c5ae5`.
- AndroidTest APK: 1,065,771 bytes, SHA-256
  `48d4abefde375d01fa936be82280bc5340215a1f77638e703e319ee0683e5613`.
- All three APK ZIP integrity checks pass; the primary/secondary/final keyboard screenshots were
  visually inspected.

## Risks

- Long-press alternates are accessible and functional but intentionally have no popup preview,
  sound or haptic feedback; KBD-005 owns those presentation refinements.
- KBD-004 is still required for numeric/phone/date fields to select dedicated layouts
  automatically; this task only provides the user-invoked symbol pages.
- Release is unsigned and the shared worktree is not a release source.

## Rollback

Disable KBD-001's `keyboard_shell_route_a` flag and restart the IME process to restore the legacy
voice Shell. Layer/page state is not persisted and needs no data rollback.

## Follow-ups

- `KBD-004`
- `KBD-005`
- `KBD-006`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with extensive pre-existing work; KBD-003 is not staged,
  committed or pushed.
