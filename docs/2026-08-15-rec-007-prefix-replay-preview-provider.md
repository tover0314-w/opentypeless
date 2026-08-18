# Task Report: REC-007

## Result

DONE

## Scope

- Implemented: package-confined PrefixReplay Provider; explicit non-streaming/revisable capability kind; bounded one-use
  request and PCM handoff; hardened 750 ms single-worker preview; closed device/model probe; source/compiled gates; JVM,
  strict build and Xiaomi instrumentation coverage.
- Not implemented: production VoicePipeline/RecognitionRouter wiring, true streaming, authoritative final recognition,
  model provisioning, unified FailureClass, fallback/circuit breaking, or a fixed-model latency/quality benchmark. These
  remain REC-008..011, SEC-007, STR-004..006 and later wiring tasks.

## Changes

- `android/app/src/main/java/com/opentypeless/android/recognition/ProviderCapabilities.java`: closed
  `BATCH_FINAL` / `NATIVE_STREAMING` / `PREFIX_REPLAY` implementation vocabulary and exact prefix-replay declaration,
  while preserving the five legacy backend declarations.
- `android/app/src/main/java/com/opentypeless/android/recognition/PrefixReplayPreviewProvider.java`: canonical
  `builtin.local-prefix-replay` adapter, one active session, copied/capped PCM, fully revisable Partial events, cancellation
  and closed device/model failure mapping.
- `android/app/src/main/java/com/opentypeless/android/offline/LocalRealtimePreview.java`: worker-owned lazy native session,
  30-second fixed buffer, 750 ms coalescing, nonblocking cancel and explicit PCM/WAV/snapshot/reference cleanup.
- JVM and Android instrumentation: capability, lifecycle, revision, bounds, coalescing, zeroing, cancellation, availability,
  production probe and redaction coverage.
- `android/architecture-tests/**`, `android/architecture-gate/**`: REC-007 source/compiled rules, exact Debug/Release
  binaries and malicious fixtures; REC-001 bytecode shape updated for implementation kind.
- specs and this report: task boundary, safety contract, executed evidence and explicit missing-model limitation.

## Architecture

- Contracts: `supportsStreaming=false`, `supportsPartialRevision=true`, `PREFIX_REPLAY`, ON_DEVICE, no upload;
  Preparing→Ready→fully revisable Partial or stable Failure/Cancelled; never Final/Endpoint/SpeechStarted.
- State changes: one-use request claim, one provider session, one coalescing worker and at most 960,000 PCM bytes; all
  content is process-memory-only and cleared or released at cancellation/terminal cleanup.
- Migration: none. Existing provider descriptors remain semantically unchanged and production voice routing is untouched.
- Feature flag: none added; the provider is deliberately not selected by the current production route.

## Security & privacy

- Data sent/stored: copied PCM remains on-device and is passed only to the existing private-process SenseVoice session;
  no new network or persistence path. Chunk copies, fixed PCM buffer, generated WAV and decode snapshots are zeroed.
- Permissions/components: no new Android permission or exported component.
- Threat considerations: language and PCM are bounded, request/session authority is one-use, late callbacks are dropped,
  device/model states map to content-free failures, and provider internals cannot escape their reviewed package. Audio,
  transcript, SessionId, model path/hash, native/OEM message and exception cause are excluded from diagnostics.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| targeted capability/provider/preview JVM tests | PASS | 18/18 |
| clean `:app:testDebugUnitTest` in final strict verify | PASS | 853/853, 0 failure/error/skipped |
| `python3 -m unittest discover -s android/architecture-tests -p 'test_*.py' -v` | PASS | 105/105 |
| `python3 android/architecture-tests/architecture_contracts.py --android-root android` | PASS | production source scan |
| `:architecture-gate:test` | PASS | 103/103 positive/malicious fixtures |
| `:architecture-gate:verifyCompiledArchitecture` | PASS | Debug/Release 2/2 |
| fresh `GRADLE_USER_HOME` `scripts/verify_android.sh all` | PASS | 187 tasks; 956 XML tests; lint and five APK artifacts |
| Xiaomi REC-007 exact-method instrumentation | PASS | 1/1 |
| Xiaomi app full instrumentation | PASS | `OK (89 tests)`; five documented fixture-dependent assumption-skips |
| fixed-model prefix replay decode/latency on Xiaomi | NOT RUN | no verified model/WAV fixture and no `offline_models` directory |
| GitHub Actions for current HEAD | NOT RUN | no matching run because the shared worktree has not been pushed |

## Evidence

- Device: Xiaomi 10 Ultra `be4e2015`, M2007J1SC/cas, Android 13/API 33, HyperOS OS1.0 /
  V816.0.4.0.TJJCNXM.
- Strict verification: `BUILD SUCCESSFUL in 2m 18s`; 187 tasks (184 executed, 3 up-to-date); 131 XML reports;
  956 tests; 0 failure/error/skipped.
- Debug APK: 56,347,603 bytes,
  `3a278c4838c255ce1671dfc43e57471880d8a82e4694860027151984eb4d1730`.
- AndroidTest APK: 997,472 bytes,
  `b01a5559f3acc24416fd46ecfcc96e1d31441667a7692863a0c2d2481ad3ec0f`.
- Release APK: 54,620,532 bytes,
  `9227b4b98592d73af7220b9f478b049a1a860665efb37f2214398d498aa71788`.
- Device cleanup verified: app/test MIUI AppOp 10021 restored to `ignore`; test processes stopped; screen Dozing;
  keyguard not showing; screen timeout 600,000 ms; lock-after 2,147,483,647; default IME still
  `com.flypy.input/PangIME.Android.InputService`.

## Risks

- The provider is not selected by production VoicePipeline/RecognitionRouter. Later wiring must retain single recognizer,
  single editor-writer and final-authority rules; prefix replay must not be advertised as true streaming.
- Real prefix inference remains unverified on the Xiaomi because a redistributable, pinned-hash model/WAV fixture is
  absent. The executed production probe proves the missing-model branch, not model quality, thermal behavior or latency.
- A running native decode cannot be forcibly preempted inside the native call; cancel revokes authority immediately and
  releases references after the worker returns, so callers never receive late text but native return latency is model-bound.

## Rollback

- Remove the PrefixReplay provider and REC-007 gate rules, restore the prior LocalRealtimePreview constructors/cleanup,
  and remove `PREFIX_REPLAY` from ProviderCapabilities. No data/schema rollback is needed because this task adds no
  persistence or production route migration.

## Follow-ups

- REC-008
- REC-009
- REC-010
- SEC-007
- STR-004
- STR-006

## Git

- branch: `agent/android-offline-followup`
- commit: none created for this task
- worktree status: shared dirty worktree; unrelated and earlier task changes preserved
