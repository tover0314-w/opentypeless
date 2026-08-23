# Task Report: REC-012

## Result

DONE

## Scope

- Implemented: generation-safe Android system language-support probe and model-download lifecycle;
  least-data capability request; bounded hostile OEM response evaluation; one-shot cancellable
  terminal operations; process-local Activity-rebind coordinator; JVM, source/compiled gates,
  strict build, and Xiaomi 10 Ultra API 33 validation.
- Not implemented: production recognition-route migration, actual third-party model installation,
  a new network client, or API 34 OEM-device download execution.

## Changes

- `SystemRecognitionSupport.java`, `SystemRecognitionSupportApi33.java`, and
  `SystemRecognitionSupportApi34.java`: stable content-free support/download results, exact
  single-terminal/cancel/timeout semantics, monotonic progress, API-level isolation, and raw OEM
  failure reduction to REC-008.
- `SystemRecognitionIntentFactory.java` and `RecognitionLanguageSupportEvaluator.java`: dedicated
  no-personalization capability request and bounded hostile list/tag evaluation.
- `SystemModelDownloadCoordinator.java` and `MainActivity.java`: one opaque generation-bound
  process operation with at most 16 lifecycle subscriptions; Activity subscribe/close wiring.
- recognition JVM/Android tests and `android/architecture-{tests,gate}/**`: deterministic races,
  real API 33 support probe, exact Debug/Release binary contracts, and hostile fixtures.
- `docs/opentypeless_specs/**`: architecture, security, Backlog, validation, and full-spec evidence.

## Architecture

- contracts: capability checks carry only backend/language/offline preference and no learned text;
  support/download callbacks publish at most one stable terminal; progress is monotonic 0..100.
- state changes: coordinator start increments an opaque generation; current request callbacks may
  update progress or terminal state; cancel returns to idle; stale/duplicate callbacks are ignored.
- migration: none; all operation/subscription state is process-only.
- feature flag: none; production recognition routing remains unchanged.

## Security & privacy

- data sent/stored: no transcript, selected text, prompt, bias phrase, history, correction, Secret,
  audio, raw exception, or platform error integer is retained or added to capability requests.
- permissions/components: none added.
- threat considerations: hostile/oversized OEM language lists fail closed; operation and request
  identities prevent callback ABA; subscription capacity is 16; diagnostics redact language and
  raw OEM details; API 33 is reported only as requested, never falsely completed.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| REC-012 JVM tests | PASS | 15/15 |
| full app JVM suite | PASS | 895/895 |
| Python architecture suite + production scan | PASS | 109/109; production source passed |
| `:architecture-gate:test` + production verify | PASS | compiled 107/107; Debug/Release 2/2 |
| strict `scripts/verify_android.sh all` | PASS | 49s; 187 tasks; 1002 XML tests; lint and five APKs |
| Xiaomi real support probe | PASS | 1/1 on API 33 OEM callback path |
| Xiaomi recognition contract class | PASS | 11/11 |
| Xiaomi app instrumentation | PASS | `OK (92 tests)`; 5 optional model/WAV assumptions skipped |
| Xiaomi Test Host instrumentation | PASS | `OK (4 tests)`; candidate-only assumption skipped |
| Actual model download | NOT RUN | avoided unrequested network/storage/model side effects |
| Current worktree CI | NOT RUN | HEAD has no run and REC-012 worktree is uncommitted |

## Evidence

- Strict build: `BUILD SUCCESSFUL in 49s`, 187 tasks (184 executed, 3 up-to-date), app 895 tests,
  compiled gate 107 tests, zero XML failures/errors/skips.
- Xiaomi: `be4e2015`, M2007J1SC/cas, Android 13/API 33, HyperOS
  `V816.0.4.0.TJJCNXM`; all four debug/test APKs installed unattended.
- App Debug SHA-256:
  `e3469e9eb00ce832e6e1dfc57bf59ef66bf4e9f00e6a00c6ce55a30f6dcadb83`.
- Android platform contracts reviewed against
  [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer),
  [RecognitionSupportCallback](https://developer.android.com/reference/android/speech/RecognitionSupportCallback),
  and [ModelDownloadListener](https://developer.android.com/reference/android/speech/ModelDownloadListener).
- Cleanup restored app/Test Host MIUI AppOp 10021 and package/UID `RECORD_AUDIO` to `ignore`,
  force-stopped targets, left the screen Dozing without keyguard, preserved the ten-minute
  screen-off/no-lock policy, and kept `com.flypy.input/PangIME.Android.InputService` as default IME.

## Risks

- API 34 callback mechanics are covered by deterministic JVM/compiled contracts but were not run
  against a physical API 34 OEM device in this task.
- Android 13 offers no model-download listener; `REQUESTED` proves dispatch only, not availability.
- Production provider routing is intentionally unchanged and must not treat this capability seam as
  recognition execution authority.

## Rollback

- Revert REC-012 support/download/coordinator/UI/test/gate/doc changes and mark REC-012 TODO. No
  persisted data, schema, permission, or installed third-party model requires migration rollback.

## Follow-ups

- REC-013
- STR-010

## Git

- branch: `agent/android-offline-followup`
- commit: none created
- worktree status: shared dirty/untracked worktree preserved; REC-012 changes are uncommitted
