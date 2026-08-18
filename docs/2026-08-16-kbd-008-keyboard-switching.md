# Task Report: KBD-008 Input-method and engine switching

## Result

DONE

## Scope

- Implemented: short-press next-IME request with system-picker fallback; explicit long-press
  picker; bounded LATIN/RIME engine selection contract; an EN/中文 control that stays hidden until
  a second engine is genuinely registered; English/Chinese accessibility text.
- Not implemented: Rime registration, Rime runtime or real Chinese candidate input. Those remain
  RIM-001..009 and the engine key deliberately cannot advertise them early.

## Changes

- `KeyboardEngineSelection`: immutable closed engine set, current engine and monotonic revision;
  unavailable and revision-exhausted transitions fail closed.
- `KeyboardSystemImeSwitcher`: classifies next-IME request, picker fallback and platform failure
  without exposing platform exception text.
- `LatinKeyboardLayout`: short/long switch gestures and the availability-gated engine button.
- `OpenTypelessImeService`: delegates only to the system IME API or the bounded engine selector;
  it does not write editor text and starts in Latin-only mode.
- JVM/View tests plus `keyboard_switching_contract.py`: exact source, callback, localization and
  capability boundary.

## Architecture

- contracts: the engine model is pure Java and has only `LATIN` and `RIME`; the platform adapter
  receives no editor, text, engine or native capability.
- state changes: engine selection is in-memory and session-independent; current product state is
  Latin-only. RIM integration must explicitly register availability before the button appears.
- migration: none.
- feature flag: none; the existing mutually-exclusive Route-A flag is unchanged.

## Security & privacy

- data sent/stored: none.
- permissions/components: none.
- threat considerations: platform errors are content-free; no fallback opens an arbitrary
  Settings intent; no switch callback can mutate editor text; absent Rime cannot be represented as
  available.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| KBD-008 hostile source contract | PASS | 9/9; full architecture Python suite 193/193. |
| switching JVM tests | PASS | 10/10 across engine state and system fallback. |
| clean `verify_android.sh all` | PASS | scripts 119/119; Android scripts 11/11; Gradle 191 tasks (187 executed, 4 up-to-date); app JVM 1004/1004; architecture-gate 114/114. |
| `LatinKeyboardLayoutInstrumentedTest` | PASS | exact final APK, 11/11 on API35 ARM64 emulator and 11/11 on Xiaomi 10 Ultra API33. |
| Xiaomi selected-system-IME switch | PASS | short press opened HyperOS picker when direct next was unavailable; choosing PangIME changed `default_input_method`; long press independently opened the picker. |
| KSP-012 post-build scans | PASS | 3 product + 2 test APKs; real Xiaohè 0, forbidden resource 0, violations 0. |

The first full preflight exposed an old KBD-003 fixture that mistook the new globe long-press
lambda for the letter long-press body. The helper was moved without weakening the gate; the full
suite then passed. The first clean build also failed lint because Android's direct next-IME API is
API 28 while minSdk is 26; API 26/27 now use the picker fallback and the clean graph passed.

One documentation-verifier invocation incorrectly passed positional paths to tools that require
`--repo-root`; all three rejected the arguments before validation. The documented `--repo-root ..`
invocations then passed, together with the unchanged 13/13 validator unit tests.

## Evidence

- Debug APK: 56,484,561 bytes, SHA-256
  `742c5b57c74a25f6cbd7d439e0bc52c93f4f6d823c25b5e3d4ed78c5453781b8`.
- unsigned Release APK: 54,658,721 bytes, SHA-256
  `f89c1b42578d20e959f77e70b0881a44ad04473bbf447885758ce73cc2152c0c`.
- app AndroidTest APK: 1,076,039 bytes, SHA-256
  `f65d98894a7ef8cd8ee1c7f820ef196535041789cb3eee33a38272c58e216115`.
- product/test scan manifests: `5aed692ee3f7aefabcf40037342a6b301718ecb4990dd470e32179f65570c41c` /
  `53365483d557a58e59e64ac3873075e8791d04554ad7a4b8fff82d9ad6f1b11b`.
- Xiaomi picker screenshots were visually inspected; exact PNG SHA-256 values are
  `f062ce5e1e09df3f7228186539ca50c5ccb0d0eb64d9161761b3f40fd28206d1` and
  `fdea2f8a5b1d1f669457335379f9e3dc6d73b7f8e19e9ede695cecb67471b3b5`.
- final defaults: emulator `com.android.inputmethod.latin/.LatinIME`; Xiaomi
  `com.flypy.input/PangIME.Android.InputService`.

## Risks

- HyperOS chose the explicit picker fallback rather than changing IME immediately. This is a
  successful usable fallback, not evidence that every OEM permits direct next-IME switching.
- The engine selector is intentionally invisible until Rime is connected; KBD-008 does not make
  Chinese input usable by itself.

## Rollback

Remove the two switching classes, View/service wiring, strings, tests/gate and the three new
artifact identities. No stored state or migration is involved.

## Follow-ups

- `RIM-001`
- `RIM-004`
- `RIM-005`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
