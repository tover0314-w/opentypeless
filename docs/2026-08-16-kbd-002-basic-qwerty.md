# Task Report: KBD-002 Basic QWERTY

## Result

DONE

The default Route-A product Shell now contains a compact four-row ASCII QWERTY keyboard with
one-shot Shift, double-tap Caps Lock, backward delete, tap-for-space/hold-to-talk, semantic Enter
and the existing keyboard-switch control. Every text mutation continues through the existing
keyboard facade and sole `EditorTransactionManager` authority.

## Scope

- Implemented: `a`..`z`; lower/Shift/Caps state; delete, space and Enter; 48dp keys; Chinese and
  English accessibility labels; fail-closed source contract; layout measurement regression; JVM,
  emulator, Xiaomi and system-selected IME validation.
- Not implemented: numbers/symbols, field-specific layouts, toolbar placement, candidates, Rime,
  haptics, horizontal/one-hand sizing, or making OpenTypeless the Xiaomi daily default.

## Changes

- `android/app/src/main/java/com/opentypeless/android/keyboard/latin/LatinKeyboardState.java`:
  process-only closed lower/one-shot Shift/Caps state with a 400ms double-tap window.
- `android/app/src/main/java/com/opentypeless/android/keyboard/latin/LatinKeyboardLayout.java`:
  capability-free four-row View layer and bounded callbacks.
- `OpenTypelessImeService.java`: Route A binds each keyboard intent once to the existing narrow
  keyboard facade; legacy voice Shell remains the mutually exclusive rollback path.
- resources/tests/gates: bilingual labels, 5 JVM state tests, 4 Android View tests and 8 hostile
  source fixtures.
- `OpenAiCompatibleClient.java`: separate REC-005 regression closure discovered by the full gate;
  all caller-controlled STT bounds now run before opening a connection.

## Architecture

- contracts: the View layer owns no editor, `InputConnection`, network, native, reflection or
  arbitrary-key capability; it emits only insert/delete/Enter/switch callbacks.
- state changes: Shift/Caps lives only in the current View process and is not persisted.
- migration: none.
- feature flag: KBD-001's service-frozen Route-A flag remains the only rollback switch.

The indent spacers use zero height and the Shell key stage uses content height. This prevents a
weighted `WRAP_CONTENT` child from expanding the IME to the full display and keeps all four rows
contiguous at the bottom.

## Security & privacy

- data sent/stored: no new data, network request, history or persistence.
- permissions/components: none added or changed.
- threat considerations: all text/delete/Enter operations retain generation/selection evidence and
  sole-ETM validation; disabled editor state disables every interactive key; no failure fallback or
  second writer was introduced.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `scripts/verify_android.sh preflight` with pinned JDK/SDK | PASS | 119 scripts tests, 11 Android-script tests, 128 architecture tests, 10 mobile-voice tests; KBD-002 source gate PASS. |
| `scripts/verify_android.sh unit` with pinned JDK/SDK | PASS | Clean 959/959 app JVM, 113/113 architecture-gate tests, Debug/Release compiled gate 2/2; 68/68 tasks executed. |
| Strict Debug/unsigned Release/AndroidTest assemble + `lintRelease` | PASS | 115 tasks: 78 executed, 37 up-to-date; both manifest gates PASS. |
| Exact KBD-002 class on API35 ARM64 emulator | PASS | Main/test install Success; `OK (4 tests)`, runner code `-1`. |
| Exact KBD-002 class on Xiaomi 10 Ultra | PASS | Main/test overlay install Success; `OK (4 tests)`, runner code `-1`; default stayed PangIME. |
| System-selected final APK on API35 ARM64 emulator | PASS | OpenTypeless selected and visible, `mInputShown=true`, real Test Host `InputConnection`; taps produced exact `abcD ` after Shift, space and delete; emulator restored to LatinIME. |
| First visual system-IME smoke | FAIL — superseded | A `WRAP_CONTENT` indent spacer expanded one row to 1836px and hid two rows. Zero-height spacers plus content-height key stage fixed it; the permanent tall-measure regression and final screenshot pass. |
| First two Android View test attempts | FAIL — superseded | Off-main Button animation caused two Looper failures; an unavailable `UiThreadTest` annotation then caused compile failure. Final tests use `runOnMainSync` and pass 4/4 on both devices. |
| First clean full unit attempt | FAIL — superseded | REC-005 opened a request before rejecting an oversized prompt. The bounded pre-open fix and exact MockWebServer test pass; final clean suite is 959/959. |

## Evidence

- Debug APK: 56,447,673 bytes, SHA-256
  `5eba214d20e813b76039ad8781340379fd50d072ab8cefd3c39c9d8e08498e10`.
- Unsigned Release APK: 54,655,065 bytes, SHA-256
  `f6027bff23f4aa5855938bc9d5f00620d42408ff56d65be15ea62f5ea8d85d7f`.
- AndroidTest APK: 1,064,263 bytes, SHA-256
  `3832b9197d0bd77ec54b7783cc0f39acfe1990280e42be722ae4e920e5a4caed`.
- All three APK ZIP integrity checks pass.
- Final emulator screenshot was visually inspected: four contiguous rows remain at the bottom and
  the host field shows `abcD `.

## Risks

- This is an ASCII base layer, not yet a complete daily keyboard. KBD-003/004/006 remain required
  before switching the Xiaomi default.
- Space combines tap-to-insert with hold-to-talk. Gesture refinement belongs to KBD-013 and must not
  break the current tap or recording lifecycle.
- Actual system-selected typing was exercised on the emulator; Xiaomi ran the exact production View
  contract but was deliberately not switched away from PangIME.
- Release is unsigned and the shared worktree is not a release source.

## Rollback

Disable KBD-001's `keyboard_shell_route_a` flag and restart the IME process to select only the legacy
voice Shell. Shift/Caps has no persisted state or migration to undo.

## Follow-ups

- `KBD-003`
- `KBD-004`
- `KBD-006`
- `KBD-008`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with extensive pre-existing work; KBD-002 and the bounded REC-005
  regression fix are not staged, committed or pushed.
