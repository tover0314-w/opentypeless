# Task Report: REC-006

## Result

DONE

## Scope

- Implemented: package-confined final SenseVoice Provider; bounded one-use WAV request; device/model availability
  classification; private-process client lifecycle; stable final-only REC-002 events/failures; source and compiled
  architecture gates; JVM, strict build and Xiaomi instrumentation coverage.
- Not implemented: production VoicePipeline/RecognitionRouter wiring, prefix replay preview, unified FailureClass,
  fallback policy, model installation v2, or a model quality benchmark. These remain REC-007..010, SEC-007 and STR tasks.

## Changes

- `android/app/src/main/java/com/opentypeless/android/recognition/SenseVoiceFinalProvider.java`: canonical LOCAL_OFFLINE
  adapter, one active session, one worker, copied WAV ownership, final-only lifecycle, terminal cleanup and stable
  availability/failure mapping.
- `android/app/src/main/java/com/opentypeless/android/offline/LocalOfflineRecognizer.java`: bounded RAM/ABI/system support
  probe exposed through a stable `DeviceSupport` enum.
- `android/app/src/main/java/com/opentypeless/android/offline/LocalOfflineRecognitionClient.java`: result UTF-16 and
  20,000-code-point validation plus redacted diagnostics.
- recognition JVM tests and Android instrumentation: availability, lifecycle, cancellation, bounds, cleanup, production
  missing-model probe and redaction.
- `android/architecture-tests/**`, `android/architecture-gate/**`: REC-006 source/compiled rules and malicious fixtures.
- specs and this report: task boundary, security contract, executed evidence and explicit model-fixture limitation.

## Architecture

- Contracts: canonical `builtin.local-offline` descriptor; one-use 44..18,000,000-byte WAV StartRequest;
  Preparing→Ready→Final or one stable Failure/Cancelled terminal.
- State changes: copied audio, language and event sink are process-only and released at terminal; no persistence.
- Migration: none. The existing VoicePipeline continues to select its legacy recognizer path.
- Feature flag: none added; production route selection is intentionally unchanged.

## Security & privacy

- Data sent/stored: bounded WAV is copied into process memory and passed only to the existing private-process offline
  client. Nothing new is networked or persisted; provider-owned audio is zeroed and references are released at terminal.
- Permissions/components: no new Android permission or exported component.
- Threat considerations: device support and pinned-model status are checked before claim/decode; missing/corrupt/low-memory/
  unsupported-ABI/system failures are content-free; model paths, hashes, native/OEM messages, audio, transcript, Session ID
  and exception causes are excluded from events/logs/toString. One worker and one active session prevent unbounded work.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| targeted `SenseVoiceFinalProviderTest` + `LocalOfflineRecognizerTest` | PASS | 13/13 |
| clean `:app:testDebugUnitTest` in final strict verify | PASS | 841/841, 0 failure/error/skipped |
| `python3 -m unittest discover -s android/architecture-tests -p 'test_*.py' -v` | PASS | 104/104 |
| `python3 android/architecture-tests/architecture_contracts.py --android-root android` | PASS | production source scan |
| `:architecture-gate:test` | PASS | 102/102 positive/malicious fixtures |
| `:architecture-gate:verifyCompiledArchitecture` | PASS | Debug/Release 2/2 |
| fresh `GRADLE_USER_HOME` `scripts/verify_android.sh all` | PASS | 187 tasks; 943 XML tests; lint and five APK artifacts |
| Xiaomi REC-006 exact-class instrumentation | PASS | 1/1; production probe classified absent model as MODEL_MISSING |
| Xiaomi app full instrumentation | PASS | `OK (88 tests)` |
| fixed-model SenseVoice decode on Xiaomi | NOT RUN | no verified model/WAV fixture and no `offline_models` directory |
| GitHub Actions for current HEAD | NOT RUN | no matching run because the shared worktree has not been pushed |

## Evidence

- Device: Xiaomi 10 Ultra `be4e2015`, M2007J1SC/cas, Android 13/API 33, HyperOS OS1.0 /
  V816.0.4.0.TJJCNXM.
- Strict verification: `BUILD SUCCESSFUL in 2m 15s`; 187 tasks (184 executed, 3 up-to-date); 130 XML reports;
  943 tests; 0 failure/error/skipped.
- Debug APK: 56,347,603 bytes,
  `ee902d989add2462f8fdf587ac16f9919cd8d50dbb84c61431ee08a5dcb3e535`.
- AndroidTest APK: 995,824 bytes,
  `ed6389a9fd3ca20973b32d93872765f001c5f9f0fb58e3217a299da37a966832`.
- Release APK: 54,620,532 bytes,
  `f291ea3cc55115b0d93f16d64fb51f0f38cc6f42958939b683de6dfeb27f2774`.
- Device cleanup verified: app/test MIUI AppOp 10021 restored to `ignore`; test processes stopped; screen Dozing;
  keyguard not showing; screen timeout 600,000 ms; lock-after 2,147,483,647; lock disabled; default IME still
  `com.flypy.input/PangIME.Android.InputService`.

## Risks

- The provider is not selected by production VoicePipeline/RecognitionRouter. REC-007..010 and EDT-017 must enforce
  route, composition and failure policy before this capability can process production audio.
- Real SenseVoice inference remains unverified on the Xiaomi because a redistributable, pinned-hash model/WAV fixture is
  absent. The executed production probe proves only the MODEL_MISSING path, not recognition quality or latency.

## Rollback

- Remove the SenseVoice adapter and REC-006 gate rules, then restore the previous LocalOfflineRecognizer/client result
  surface. No data/schema rollback is needed because REC-006 adds no persistence or production route migration.

## Follow-ups

- REC-007
- REC-008
- REC-009
- SEC-007
- STR-006

## Git

- branch: `agent/android-offline-followup`
- commit: none created for this task
- worktree status: shared dirty worktree; unrelated and earlier task changes preserved
