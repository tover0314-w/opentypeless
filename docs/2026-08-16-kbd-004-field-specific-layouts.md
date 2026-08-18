# Task Report: KBD-004 Field-specific layouts

## Result

DONE

## Scope

- Implemented: automatic URL, email, phone, decimal-number, date and password keyboard profiles;
  direct email/URL punctuation; dedicated phone/number/date panels; localized profile accessibility
  labels; Test Host system-IME verification; fail-closed source gate.
- Not implemented: toolbar placement, candidate UI, Rime, theme/haptics, signed release or changing
  Xiaomi's system security-keyboard policy.

## Changes

- `android/app/src/main/java/com/opentypeless/android/keyboard/field/KeyboardFieldProfile.java`:
  closed, metadata-only profile selection with sensitive classification taking precedence.
- `android/app/src/main/java/com/opentypeless/android/keyboard/latin/LatinKeyboardLayout.java` and
  `LatinKeyboardState.java`: profile rows/shortcuts, localized profile description and letter reset.
- `android/app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java`: selects the
  profile from each `onStartInput` target and applies it to the existing Route-A layout.
- app JVM/AndroidTest and Test Host AndroidTest: policy, View/callback, field-switch and OEM security
  keyboard coverage.
- `android/architecture-tests/field_keyboard_contract.py` plus hostile tests and preflight hook:
  exact profile inventory, rows, test-host matrix, capability and single-writer checks.
- English/Chinese IME resources: profile accessibility labels.
- `third_party/rime/resource-policy.v1.json`: four post-KBD-004 APK identities added after recursive
  scans found zero Rime/Xiaohè resource violations; no resource was added or allowed.

## Architecture

- contracts: `KeyboardFieldProfile` is a closed enum. It stores no `EditorInfo`, editor capability,
  text or persistence; it only maps current metadata to a View profile.
- state changes: changing fields resets symbols/Shift to the letter layer before rebuilding the
  bounded View. Numeric profiles hide space and expose only fixed numeric punctuation.
- migration: none.
- feature flag: none; the existing mutually exclusive Route-A Shell flag remains unchanged.

All text/delete/Enter actions continue through the one KBD-002 `LatinKeyboardLayout.Listener`, the
existing keyboard façade and sole `EditorTransactionManager`. KBD-004 adds no `InputConnection`
holder or writer.

## Security & privacy

- data sent/stored: none; only `inputType`/classified field kind influence an in-memory enum.
- permissions/components: none.
- threat considerations: password classification wins over numeric/other shapes; no voice/network
  path is enabled. On Xiaomi 10 Ultra, MIUI replaces third-party IMEs with
  `com.miui.securityinputmethod/.latin.LatinIME` for the password field. The device test accepts this
  only after `dumpsys input_method` proves that exact current IME and served `host_password` field.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| KBD-002/003/004 source contracts | PASS | 22/22; KBD-004 hostile fixtures 7/7. |
| `scripts/verify_android.sh preflight` | PASS | 119 script, 11 Android-script, 142 architecture and 10 mobile-voice tests; pinned SDK/AAR checks pass. |
| `scripts/verify_android.sh unit` | PASS | clean 68/68 tasks; app JVM 963/963, architecture-gate JVM 113/113, Debug/Release compiled gate 2/2. |
| strict app + test-host Debug/Release/AndroidTest/lint build | PASS | 173 tasks: 120 executed, 53 up-to-date; dependency verification stayed strict/offline. |
| `LatinKeyboardLayoutInstrumentedTest` | PASS | final exact class 10/10 on API35 ARM64 emulator and Xiaomi 10 Ultra; runner code `-1`. |
| Test Host automatic field-profile exact test | PASS | final 1/1 on both devices; emulator used OpenTypeless for all six profiles, Xiaomi used OpenTypeless for five and its exact security IME for password. |
| KSP-012 resource verifier | PASS | hostile suite 36/36; working tree and all five final APK scans have real Xiaohè 0, forbidden Rime resource 0 and violations 0. |

Initial unconfigured Gradle attempts failed before compilation because `JAVA_HOME`, then
`ANDROID_HOME`, were absent; the pinned environment above passed without changing verification.
The first Xiaomi View run also exposed two English-only assertions, which were replaced with actual
localized resources. A later password-label timeout was retained until `dumpsys input_method`
proved MIUI's secure-keyboard takeover; it was not silently marked as an OpenTypeless pass.

## Evidence

- Debug APK: 56,449,473 bytes, SHA-256
  `58521d59adc55a99c37c87b449bb8832d2712a54013785f7f0540b0ec07f336f`.
- unsigned Release APK: 54,656,865 bytes, SHA-256
  `5710fe4a677c32f7579497a0a4cd171d61731afa768d24df9da8e32b1271feec`.
- app AndroidTest APK: 1,067,147 bytes, SHA-256
  `60f6be2ee18994c028709c6f65ccbfdbe361a491076956c50fe9c49922860439`.
- Test Host Debug APK: 10,485 bytes, SHA-256
  `d37e7495b97adc26668bc2b1eacd6780384a7a1b04f44be25e867ef6d765b8c7`.
- Test Host AndroidTest APK: 1,687,444 bytes, SHA-256
  `6fbfb052b94ebdd5364c3373124b6eba154444c60fe8dcf1e756d987dce95c91`.
- All five ZIP integrity checks pass. Final device defaults are LatinIME on the emulator and PangIME
  on Xiaomi.

## Risks

- Xiaomi's security keyboard intentionally prevents OpenTypeless from rendering or receiving the
  password field; behavior on other OEM security-keyboard implementations remains TST-001 scope.
- Date/number panels provide fixed common punctuation; locale-specific separators and advanced
  signed/scientific modes are later compatibility work, not required for this personal P0.

## Rollback

Revert the profile selector/layout/service wiring and four reviewed artifact identities. The
general KBD-002/KBD-003 QWERTY/symbol route remains usable and no stored data needs migration.

## Follow-ups

- `KBD-006`
- `TST-001`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
