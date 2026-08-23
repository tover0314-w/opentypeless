# Task Report: EDT-008 Xiaomi 10 Ultra validation refresh

## Result

DONE

## Scope

- Implemented: refreshed the real-device evidence for the already completed EDT-008 Host core and
  its selected-origin Undo/Raw/rollback regression surface.
- Not implemented: no editor behavior, UI, persistence, permission, dependency, Feature Flag, or
  production routing change.

## Changes

- `docs/opentypeless_specs/08_TEST_VALIDATION.md`: records the current Xiaomi directed and full
  instrumentation runs and supersedes the historical `NOT RUN` device snapshot.
- `docs/opentypeless_specs/OpenTypeless_FULL_SPEC.md`: mirrors the same validation evidence.
- `docs/opentypeless_specs/PACKAGE_VALIDATION.md` and `FILE_MANIFEST.md`: regenerated package metrics
  and hashes after the documentation update.

## Architecture

- contracts: no contract change; all editor writes remain inside `EditorTransactionManager`.
- state changes: none.
- migration: none.
- feature flag: no change; EDT-017 remains the production writer-route authority.

## Security & privacy

- data sent/stored: no user editor text, audio, Secret, token, or package-private record was exported.
- permissions/components: no manifest or runtime permission change. HyperOS `MIUIOP(10021)` was
  temporarily changed from `ignore` to `allow` only for the target/test Activity launch, then restored
  to `ignore` for both packages.
- threat considerations: exact-ID, live-selection, full-span, lifecycle and sensitive-field cases ran
  on the real Android 13 framework. The default IME was not changed.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `adb -s be4e2015 install --no-streaming -r -t app-debug.apk` | PASS | unattended overlay install |
| `adb -s be4e2015 install --no-streaming -r -t app-debug-androidTest.apk` | PASS | unattended overlay install |
| exact `EditorTransactionManagerInstrumentedTest` | PASS | 26/26, 0 failure |
| full app `AndroidJUnitRunner` | PASS | `OK (85 tests)`, 5 optional-model/official-audio assumption skips, 0 failure |

## Evidence

- device: Xiaomi 10 Ultra `be4e2015`, M2007J1SC/cas, Android 13/API 33, HyperOS OS1.0,
  build `V816.0.4.0.TJJCNXM`.
- app-debug SHA-256:
  `bf343282ca7843d3726b337133b186c572d7c7f6fa33cd1fd9044dee469367c7`.
- app androidTest SHA-256:
  `c6e99b4a44650b79bbb635e802914f6bde4502d1b6a4d68a53fc191fe0e37b82`.
- directed class includes real `BaseInputConnection + Editable` coverage for forward/reverse/empty/
  emoji ReplaceSelection, selected-origin exact Undo/Raw, verified rollback, long-window tampering,
  sensitive zero-plaintext evidence, slot consume and lifecycle revoke.
- final device state: `mWakefulness=Dozing`, keyguard `showing=false`,
  `screen_off_timeout=600000`, `lock_screen_lock_after_timeout=2147483647`,
  `stay_on_while_plugged_in=0`, and `MIUIOP(10021)=ignore`.

## Risks

- Five optional offline-model/official-audio scenarios remain assumption-skipped because their external
  assets/capabilities are intentionally absent from this test install; they are not EDT-008 failures.
- HyperOS requires its package-specific background Activity app-op for UI instrumentation. The app-op
  is not needed by production behavior and was restored after testing.

## Rollback

- Revert only this evidence/report update. No code, schema, device security setting, or editor data
  rollback is required.

## Follow-ups

- `REC-001`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: dirty shared worktree; no commit, stage, push, or reset performed.
