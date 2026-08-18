# Task Report: KBD-001 Route-A Keyboard Shell Integration

## Result

DONE

The product `OpenTypelessImeService` now owns a minimal, product-controlled Route-A Shell frame.
Exactly one Shell route is frozen when the service starts. Route A is the default; the prior voice
frame remains a mutually exclusive rollback route. A selected-route failure is terminal and never
falls through to the other route.

This task establishes the IME root and its toolbar/composition/key/extension slots. It does not
implement the alphabet, symbols, field-specific layouts, toolbar placement model, candidate bar or
Rime.

## Scope

- Implemented: closed Shell route/config/selector/frame; product IME wiring; synchronous default-on
  feature flag migration; legacy rollback; source/compiled/manifest gates; deny-all data-transfer
  rules; JVM, build, emulator and Xiaomi validation.
- Not implemented: KBD-002/003/004/006/007, RIM runtime/resources, a user-facing flag switch,
  signed Release, or full daily-use acceptance.

## Changes

- `android/app/src/main/java/com/opentypeless/android/keyboard/shell/*`: capability-free Shell
  contract, route selection, rollback config and four-slot frame.
- `android/app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java`: freezes one
  route in `onCreate` and constructs one selected frame in `onCreateInputView`.
- `android/app/src/main/res/xml/data_extraction_rules.xml`: denies cloud backup and device transfer
  for all nine Android storage domains.
- `android/architecture-tests/keyboard_shell_contract.py`: exact source set, capability, flag,
  selector and service-wiring gate.
- `android/scripts/verify_keyboard_shell_manifest.py`: source and actual Debug/Release merged-
  manifest/backup gate.
- Gradle and `scripts/verify_android.sh`: mandatory KBD-001 preflight/build wiring.
- JVM/Android tests: exclusive selection, no fallback, default/migration/rollback and slot ownership.

## Architecture

- contracts: `KeyboardShellRoute` is closed; `KeyboardShellSelector` invokes exactly one factory;
  `KeyboardShellFrame` owns only Android Views.
- state changes: canonical boolean preference `keyboard_shell_route_a`; missing state defaults true;
  legacy alias `enabled` is synchronously migrated and removed.
- migration: bounded one-key migration only; no database or user-text migration.
- feature flag: read once per service lifetime. A change takes effect only after IME process restart.

The Shell receives no `InputConnection`, editor manager, arbitrary key code, reflection, native or
network capability. Existing live key callbacks remain behind the existing keyboard façade and sole
EditorTransactionManager authority.

## Security & privacy

- data sent/stored: no new network or user text; one nonsensitive local boolean is stored.
- permissions/components: no permission or exported component added. The gate rejects upstream
  Floris App/import/share/profileable surfaces and unknown component drift.
- threat considerations: no fallback after route failure, no simultaneous writers, exact
  Debug/Release compiled writer/IC checks, `allowBackup=false`, and explicit cloud/device-transfer
  deny-all rules.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `scripts/verify_android.sh preflight` | PASS | 120 architecture tests, 11 Android-script tests, 10 mobile-voice tests, KBD source/manifest gates and pinned Sherpa verification. |
| `scripts/verify_android.sh unit` | PASS | Clean build; 954 JVM tests, 0 failures/errors/skips; Debug/Release compiled architecture gate PASS; 68/68 Gradle tasks executed. |
| Targeted strict assemble command | PASS | Debug, unsigned Release and AndroidTest; manifest gates PASS; 110 tasks, 72 executed/38 up-to-date. |
| `:app:lintRelease` with strict dependency verification | PASS | 24 tasks; 7 executed/17 up-to-date. |
| Exact KBD-001 instrumentation on API35 ARM64 emulator | PASS | 3/3; runner code `-1`. |
| Exact KBD-001 instrumentation on Xiaomi 10 Ultra | PASS | 3/3; main/test install Success; default IME stayed PangIME before and after. |
| System-selected IME smoke on API35 ARM64 emulator | PASS | OpenTypeless bound and visible, `mInputShown=true`, served Settings Search `InputConnection`, persisted Route A flag true; emulator restored to LatinIME. |
| First full unit attempt | FAIL — superseded | An unrelated MockWebServer count assertion failed once; its isolated rerun passed and the subsequent clean 954-test run passed. No KBD/network code was changed to hide it. |

## Evidence

- Debug APK: 56,447,253 bytes, SHA-256
  `8166f7f964de84d7f06c9beae5a75182545a42e6dbce09481b479da3c1126727`.
- Unsigned Release APK: 54,638,261 bytes, SHA-256
  `fba042177c8e723061122a14b158e0c5d836409b8f670e4ebddcae2d0d78bfd2`.
- AndroidTest APK: 1,060,847 bytes, SHA-256
  `6dcd9602bcad67c254754c1b5dbdd3b829301bfbfaf45d2b166665a2de253ec3`.
- Emulator screenshot was inspected locally; it showed the real OpenTypeless voice controls inside
  the selected Route-A frame. It is diagnostic evidence, not a release artifact.

## Risks

- The current frame intentionally re-homes the existing voice controls. It is not yet a usable
  alphabet keyboard; KBD-002 is the next user-visible step.
- The rollback flag has an application API and contract tests but no user-facing settings control;
  REL-004 owns the release rollback checklist.
- Xiaomi validated installation and the exact KBD contract tests, not selection as the daily default
  IME. That switch remains reserved for the completed personal-use P0 slice.
- Release is unsigned. Current GitHub Actions has no matching run for this dirty local candidate.

## Rollback

Persist `KeyboardShellConfig.setRouteAEnabled(context, false)` and restart the IME process. The next
service instance selects only the legacy voice frame. No user data or database rollback is needed.

## Follow-ups

- `KBD-002`
- `KBD-003`
- `KBD-004`
- `KBD-006`
- `REL-004`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with extensive pre-existing work; KBD-001 changes are not staged,
  committed or pushed.
