# Task Report: REC-004

## Result

DONE

## Scope

- Implemented: package-confined `RecognitionProvider<R>` lifecycle contract; final Android System/on-device adapter;
  bounded request bridge to the existing `SystemSpeechRecognizer`; REC-002 event/terminal mapping; source and compiled
  architecture gates; JVM and Android instrumentation coverage.
- Not implemented: production `VoicePipeline` migration, other recognition providers, Router, health/circuit breaker,
  unified failure vocabulary, or model-download orchestration. These remain REC-005..012.

## Changes

- `android/app/src/main/java/com/opentypeless/android/recognition/RecognitionProvider.java`: closed runtime lifecycle.
- `android/app/src/main/java/com/opentypeless/android/recognition/AndroidSystemRecognitionProvider.java`: single-session,
  main-thread Android adapter with bounded least-authority input and terminal reference cleanup.
- `android/app/src/main/java/com/opentypeless/android/recognition/SystemSpeechRecognizer.java`: package-only bounded bridge and
  endpoint callback while preserving the legacy API.
- `android/app/src/main/java/com/opentypeless/android/recognition/SystemRecognitionIntentFactory.java`: shared bounded
  system/on-device Intent construction and sanitized bias extraction.
- recognition JVM/instrumentation tests: lifecycle, ordering, bounds, redaction, failure mapping, real Android Intent/factory.
- `android/architecture-tests/**`, `android/architecture-gate/**`: REC-004 source/compiled rules and malicious fixtures.
- specs and this report: implementation boundary and executed evidence.

## Architecture

- Contracts: descriptor/probe/prepare/start/session stop/cancel/close; one active session; monotonic REC-002 events; one
  terminal; late callbacks ignored.
- State changes: provider-owned session state is process-only; request and sink references are cleared at terminal.
- Migration: none. The existing `VoicePipeline` continues to use its legacy recognizer route.
- Feature flag: none added; production writer/router selection is intentionally unchanged.

## Security & privacy

- Data sent/stored: no new persistence or network endpoint. The system recognizer receives only bounded language,
  result count, partial flag and at most 50 sanitized bias terms; prompt, caller package, AppSettings, Secret and the full
  personalization snapshot are not retained by the adapter.
- Permissions/components: no new Android permission or exported component.
- Threat considerations: all backend lifecycle/callback work is main-thread linearized; raw Android/OEM error text is mapped
  to a stable failure class; diagnostics redact SessionId, descriptor, bias and transcript; terminal paths revoke authority
  and release content references.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `:app:testDebugUnitTest` in fresh strict full verify | PASS | 818/818, 0 failure/error/skipped |
| `python3 -m unittest discover -s android/architecture-tests -p 'test_*.py' -v` | PASS | 102/102 |
| `python3 android/architecture-tests/architecture_contracts.py --android-root android` | PASS | production source scan |
| `:architecture-gate:test` | PASS | 100/100 malicious/positive fixtures |
| `:architecture-gate:verifyCompiledArchitecture` | PASS | Debug/Release 2/2 |
| fresh `GRADLE_USER_HOME` `scripts/verify_android.sh all` | PASS | 187 tasks; 918 XML tests; lint and five APK artifacts |
| Xiaomi app instrumentation | PASS | `OK (86 tests)`, 5 documented optional-fixture assumption skips |
| Xiaomi Test Host instrumentation | PASS | `OK (4 tests)`, 1 unrequested candidate-IME assumption skip |
| GitHub Actions for current HEAD | NOT RUN | no matching run because the shared worktree has not been pushed |

## Evidence

- Device: Xiaomi 10 Ultra `be4e2015`, M2007J1SC/cas, Android 13/API 33, HyperOS OS1.0 /
  V816.0.4.0.TJJCNXM.
- Debug APK: 56,331,219 bytes,
  `b2a3b6ec99cb6ec24ee1de89871a089307f6bc42380be12a30474a3c620780d8`.
- AndroidTest APK: 991,712 bytes,
  `088465640f799068d79a412411ecdc49971fa7265f2c86a947ab50f280059e16`.
- Device cleanup verified: MIUI AppOp 10021 restored to `ignore`, processes stopped, screen Dozing, lock disabled, default
  IME still `com.flypy.input/PangIME.Android.InputService`.

## Risks

- The adapter is not yet selected by production `VoicePipeline`; REC-009/012 must re-check route privacy and platform
  capabilities before production wiring.
- Android System default recognition may be implemented by an OEM/network service; the declared privacy class and user
  route remain mandatory and are not replaced by this adapter.

## Rollback

- Remove the new provider/adapter and REC-004 gate rules, then restore the two legacy helper changes. No data or schema
  rollback is required because REC-004 adds no persistence or migration.

## Follow-ups

- REC-005
- REC-006
- REC-008
- REC-009
- REC-012

## Git

- branch: `agent/android-offline-followup`
- commit: none created for this task
- worktree status: shared dirty worktree; unrelated and earlier task changes preserved
