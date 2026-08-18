# Task Report: REC-005

## Result

DONE

## Scope

- Implemented: package-confined final OpenAI-compatible batch upload Provider; one-use bounded audio request;
  call-scoped SecretRef credential lease; cancellation/disconnect; stable REC-002 events and failures; source and compiled
  architecture gates; JVM, strict build and Xiaomi instrumentation coverage.
- Not implemented: production VoicePipeline/RecognitionRouter wiring, fallback/circuit breaker, unified FailureClass,
  configuration migration, or offline/streaming providers. These remain REC-006..011.

## Changes

- `android/app/src/main/java/com/opentypeless/android/recognition/OpenAiCompatibleUploadProvider.java`: one active session,
  one bounded worker, copied audio ownership, terminal cleanup and credential lease boundary.
- `android/app/src/main/java/com/opentypeless/android/net/OpenAiCompatibleClient.java`: narrow credential-aware upload seam,
  chunk cancellation, redirect rejection, request/response/text bounds and typed content-free failures.
- recognition/client JVM tests and Android instrumentation: multipart contract, lifecycle, cancellation, failure mapping,
  bounds, cleanup and redaction.
- `android/architecture-tests/**`, `android/architecture-gate/**`: REC-005 source/compiled rules and malicious fixtures.
- specs and this report: task boundary, security contract and executed evidence.

## Architecture

- Contracts: canonical OPENAI_COMPATIBLE descriptor; prepare/start/session stop/cancel/close; one-use StartRequest;
  Preparing→Ready→Endpoint→Final or one stable Failure/Cancelled terminal.
- State changes: copied audio, language, prompt and sink are process-only and released at terminal; no persistence.
- Migration: none. The existing VoicePipeline continues to select its legacy recognizer path.
- Feature flag: none added; production route selection is intentionally unchanged.

## Security & privacy

- Data sent/stored: the selected endpoint receives bounded WAV, model, optional language/prompt and a call-scoped bearer
  credential. Nothing new is persisted; copied audio is zeroed and request references are released after terminal.
- Permissions/components: no new Android permission or exported component.
- Threat considerations: audio <=32 MiB, response <=2 MiB, transcript <=20,000 code points, prompt <=2,000 code points;
  redirects disabled; cancellation is checked during upload/read; SecretRef is resolved only inside a synchronous `char[]`
  lease; provider bodies, headers, credentials, endpoint, transcript and raw exception messages are excluded from failures,
  logs and diagnostics.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| targeted `OpenAiCompatibleClientTest` + `OpenAiCompatibleUploadProviderTest` | PASS | 21/21 |
| clean `:app:testDebugUnitTest` in final strict verify | PASS | 830/830, 0 failure/error/skipped |
| `python3 -m unittest discover -s android/architecture-tests -p 'test_*.py' -v` | PASS | 103/103 |
| `python3 android/architecture-tests/architecture_contracts.py --android-root android` | PASS | production source scan |
| `:architecture-gate:test` | PASS | 101/101 positive/malicious fixtures |
| `:architecture-gate:verifyCompiledArchitecture` | PASS | Debug/Release 2/2 |
| fresh `GRADLE_USER_HOME` `scripts/verify_android.sh all` | PASS | 187 tasks; 931 XML tests; lint and five APK artifacts |
| Xiaomi REC-005 exact-class instrumentation | PASS | 1/1 after fixing test-only Android 13 `Stream.toList()` incompatibility |
| Xiaomi app full instrumentation | PASS | `OK (87 tests)`; 2 designed optional-model assumption skips |
| Xiaomi Test Host instrumentation | PASS | `OK (4 tests)`; 1 unrequested KSP-009 assumption skip |
| GitHub Actions for current HEAD | NOT RUN | no matching run because the shared worktree has not been pushed |

## Evidence

- Device: Xiaomi 10 Ultra `be4e2015`, M2007J1SC/cas, Android 13/API 33, HyperOS OS1.0 /
  V816.0.4.0.TJJCNXM.
- Debug APK: 56,331,219 bytes,
  `bf1cc231b3fcbc7cc92a526b2335893f20084fa4d36cc77b0dc78f72ff8ad804`.
- AndroidTest APK: 993,536 bytes,
  `b519cd28ec36a7fe07d4a72c8824048cfd43460508c8fc7de64539c6d13666c6`.
- Release APK: 54,620,532 bytes,
  `d0aecf209403bb9804c359733e0782d3c5b0454c2f235405c6037ee99aa1a0f1`.
- Device cleanup verified: four MIUI AppOp 10021 entries restored to `ignore`; test processes stopped; screen Dozing;
  keyguard not showing; lock disabled; default IME still `com.flypy.input/PangIME.Android.InputService`.

## Regression closure (2026-08-16)

A later clean verification exposed that the narrow upload seam opened its connection before
validating an oversized STT prompt. The client now validates model, language and prompt before
`open()`, so invalid caller input leaves MockWebServer request count at zero. The exact regression
test passes, followed by a clean 959/959 app JVM run and Debug/Release compiled architecture gate
2/2. No network route, permission, persistence, endpoint policy or response handling changed.

## Risks

- The provider is not selected by production VoicePipeline/RecognitionRouter. REC-008..011 must enforce route privacy,
  fallback and failure policy before it can upload real user audio.
- The two optional offline-model instrumentation gates were not executed because their fixed model/WAV prerequisites were
  absent. They are unrelated to REC-005 and remain explicit assumption skips, not claimed passes.

## Rollback

- Remove the upload provider and REC-005 gate rules, then restore the legacy client method surface. No data/schema rollback
  is needed because REC-005 adds no persistence or production route migration.

## Follow-ups

- REC-006
- REC-008
- REC-009
- REC-010
- REC-011

## Git

- branch: `agent/android-offline-followup`
- commit: none created for this task
- worktree status: shared dirty worktree; unrelated and earlier task changes preserved
