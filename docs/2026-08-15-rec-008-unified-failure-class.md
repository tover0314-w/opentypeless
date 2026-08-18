# Task Report: REC-008

## Result

DONE

## Scope

- Implemented: one package-confined, content-free mapping boundary for Android System/OEM, OpenAI-compatible upload,
  SenseVoice/PrefixReplay local availability/runtime and legacy pipeline failures; stable 19-value FailureClass coverage;
  Android compatibility error-code bridge; bounded/redacted legacy failure view; source/compiled architecture gates; JVM,
  strict build and Xiaomi instrumentation coverage.
- Not implemented: RecognitionRouter, provider selection, retry/fallback, privacy-downgrade confirmation, circuit breaker,
  route/config migration or production VoicePipeline switching. These remain REC-009..011 and later wiring tasks.

## Changes

- `android/app/src/main/java/com/opentypeless/android/recognition/RecognitionFailureMapper.java`: single closed mapper for
  Android/OEM codes, typed upload failures, shared local availability/runtime and bounded legacy message classification.
- `android/app/src/main/java/com/opentypeless/android/recognition/RecognitionFailure.java`: stable FailureClass plus Android
  compatibility code, 300-code-point/well-formed message boundary and redacted `toString()`.
- `android/app/src/main/java/com/opentypeless/android/recognition/RecognitionErrors.java`: legacy factories delegate to the
  mapper while the standard-speech endpoint configuration failure deliberately preserves `ERROR_CLIENT` compatibility.
- Android System, upload, SenseVoice Final and PrefixReplay Preview providers: removed split failure switches and delegate
  exact failure/availability mapping to the shared boundary.
- JVM and Android instrumentation: 19-class vocabulary, OEM sentinel, transport/request/local/legacy matrices, unknown
  fail-closed behavior, redaction, compatibility code and on-device regression coverage.
- `android/architecture-tests/**`, `android/architecture-gate/**`: REC-008 exact source/binary shape, caller/delegate,
  availability uniqueness and raw-message/redaction rules plus malicious fixtures.
- specs and this report: task boundary, safety contract, executed evidence and the initial/final Xiaomi compatibility result.

## Architecture

- Contracts: every observed failure becomes one of ADR-0002's 19 FailureClass values; unknown code/type/message becomes
  `INTERNAL_ERROR`; only the exact microphone-block sentinel can refine Android permission failure to `OEM_MIC_BLOCKED`.
- State changes: none. Mapping is synchronous and stateless; raw error inputs are not retained.
- Migration: none. Existing Android-facing callers keep `errorCode`, while route-facing code receives stable
  `failureClass`; no persistent schema or user setting changes.
- Feature flag: none added. REC-008 does not select a route or change the active recognizer.

## Security & privacy

- Data sent/stored: no new network or persistence. OEM/provider/transport/legacy messages are transient classification
  inputs and are excluded from fields, events, logs, diagnostics, exceptions and `toString()`.
- Permissions/components: no new Android permission or exported component.
- Threat considerations: closed request/availability types prevent provider message spoofing; unknown inputs fail closed;
  legacy display messages are bounded and well-formed; source/compiled gates reject duplicate mappers, raw-message leaks,
  unauthorized consumers and Provider delegate drift. FailureClass is a classification value, not retry/fallback authority.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| targeted mapper/errors/four-provider JVM suites | PASS | 48/48 |
| clean `:app:testDebugUnitTest` in final strict verify | PASS | 858/858, 0 failure/error/skipped |
| `python3 -m unittest discover -s android/architecture-tests -p 'test_*.py' -v` | PASS | 106/106 |
| `python3 android/architecture-tests/architecture_contracts.py --android-root android` | PASS | production source scan |
| `:architecture-gate:test` | PASS | 104/104 positive/malicious fixtures |
| `:architecture-gate:verifyCompiledArchitecture` | PASS | Debug/Release 2/2 |
| fresh `GRADLE_USER_HOME` `scripts/verify_android.sh all` | PASS | 2m12s; 187 tasks; 962 XML tests; lint and five APK artifacts |
| Xiaomi REC-008 exact-method instrumentation | PASS | 1/1 on the final APK |
| Xiaomi app full instrumentation | PASS | `OK (90 tests)`; five documented fixture-dependent assumption-skips |
| GitHub Actions for current HEAD | NOT RUN | no matching run because the shared worktree has not been pushed |

## Evidence

- Device: Xiaomi 10 Ultra `be4e2015`, M2007J1SC/cas, Android 13/API 33, HyperOS OS1.0 /
  V816.0.4.0.TJJCNXM.
- Strict verification: `BUILD SUCCESSFUL in 2m 12s`; 187 tasks (184 executed, 3 up-to-date); 132 XML reports;
  962 tests; 0 failure/error/skipped.
- Debug APK: 56,347,603 bytes,
  `055e59752946e0c40a7be5d7ed33b002d71bf39bd1c212df87acd5d77898dd27`.
- AndroidTest APK: 997,928 bytes,
  `cdd716ae887716cbfdb033259af6e0463e1cbd4cdab7d5eca4a05c3ae6c20be2`.
- Release APK: 54,636,916 bytes,
  `6b656e5df5dfa23e44578a18320026e36769093238bd92c7893d382a24c2fa9f`.
- Initial Xiaomi full-run evidence: the first run caught an endpoint-not-configured binder compatibility regression
  (`ERROR_SERVER` instead of legacy `ERROR_CLIENT`). The factory and JVM assertion were corrected; the binder exact test,
  REC-008 exact test and final 90-test runner all passed after rebuild/reinstall.
- Device cleanup verified: app/test MIUI AppOp 10021 restored to `ignore`; test processes stopped; screen Dozing;
  keyguard not showing; screen timeout 600,000 ms; lock-after 2,147,483,647; lock disabled; default IME still
  `com.flypy.input/PangIME.Android.InputService`.

## Risks

- The mapper does not decide whether a failure is retryable or permits fallback. REC-009..011 must consume the stable
  class together with route privacy/capability policy and must preserve ADR-0002's terminal-error restrictions.
- Legacy message classification remains a transitional compatibility seam. New providers must emit closed typed failures,
  not add new raw-message token matching.
- Android compatibility codes and route FailureClass intentionally serve different consumers; future edits must preserve
  explicit compatibility exceptions such as endpoint-not-configured `ERROR_CLIENT`.

## Rollback

- Restore the provider-local mapping switches and the prior two-field RecognitionFailure surface, remove the REC-008
  gate/test rules and mark REC-008 TODO. No data/schema rollback is needed because this task adds no persistence or route
  migration.

## Follow-ups

- REC-009
- REC-010
- REC-011

## Git

- branch: `agent/android-offline-followup`
- commit: none created for this task
- worktree status: shared dirty worktree; unrelated and earlier task changes preserved
