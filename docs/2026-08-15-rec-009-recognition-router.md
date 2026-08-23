# Task Report: REC-009

## Result

DONE

## Scope

- Implemented: package-confined finite RecognitionRouter; exact registry route lease; capability/privacy cross-check;
  bounded retry/fallback; terminal-failure handling; opaque Attempt and pending-confirmation tokens; source/compiled gates;
  JVM, strict build and Xiaomi regression evidence.
- Not implemented: confirmation approval/resume, circuit breaker, EffectiveProfile/sensitive-field integration, Provider
  execution, production VoicePipeline/VoiceController migration or persistent route configuration. These remain REC-010,
  REC-011 and later wiring tasks.

## Changes

- `android/app/src/main/java/com/opentypeless/android/recognition/RecognitionRouter.java`: finite synchronized route state
  machine with identity/generation-bound Attempt and content-free decisions.
- `android/app/src/main/java/com/opentypeless/android/recognition/ProviderRegistry.java`: opaque route lease and exact
  current-entry/enablement validation resistant to disable-enable ABA.
- Router and Registry JVM tests: exact descriptor, all ten capabilities, privacy, retry/fallback/terminal matrices,
  confirmation pending, stale/foreign/ABA, overflow and diagnostic redaction.
- `android/architecture-tests/**`, `android/architecture-gate/**`: REC-009 source/binary shape, policy, scope/caller and
  Debug/Release rules plus malicious fixtures.
- specs and this report: completed task boundary, security contract and executed evidence.

## Architecture

- Contracts: Router only returns decisions over immutable route and canonical registry descriptors; every attempt is
  bounded, identity-bound and generation-bound; only explicitly configured stable failures can retry/fallback.
- State changes: NEW -> ACTIVE/AWAITING_CONFIRMATION -> COMPLETED/FAILED. Stale events do not advance state; generation
  never wraps.
- Migration: none. No active production route or persisted setting changed.
- Feature flag: none added. Production recognizer selection remains unchanged pending later wiring.

## Security & privacy

- Data sent/stored: none. Router owns no audio, transcript, endpoint, Secret or persistence.
- Permissions/components: no new Android permission or component.
- Threat considerations: canonical descriptor capability/privacy verification blocks name-based claims; exact leases block
  registry ABA; terminal failures never retry/fallback; privacy escalation stops at a content-free pending confirmation;
  tokens and diagnostics redact route/provider/generation identities.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| targeted Router and Registry JVM suites | PASS | 18/18 |
| clean `:app:testDebugUnitTest` | PASS | 868/868 |
| Python source architecture suite and production scan | PASS | 107/107 plus scan |
| `:architecture-gate:test` | PASS | 105/105 positive/malicious fixtures |
| `:architecture-gate:verifyCompiledArchitecture` | PASS | Debug/Release 2/2 |
| fresh `GRADLE_USER_HOME` `scripts/verify_android.sh all` | PASS | 2m14s; 187 tasks; 973 XML tests; lint and five APKs |
| Xiaomi app full instrumentation | PASS | 90/90, 0 failed/ignored |
| GitHub Actions for current HEAD | NOT RUN | no matching run because the shared worktree has not been pushed |

## Evidence

- Device: Xiaomi 10 Ultra `be4e2015`, M2007J1SC/cas, Android 13/API 33, HyperOS OS1.0 /
  V816.0.4.0.TJJCNXM.
- Strict verification: `BUILD SUCCESSFUL in 2m 14s`; 187 tasks (184 executed, 3 up-to-date); 133 XML reports;
  973 tests; 0 failure/error/skipped.
- Debug APK: 56,363,987 bytes,
  `f17617b288a4b2944cd439117745d3beee347e4466f3215fca22bcce936f884f`.
- AndroidTest APK: 997,928 bytes,
  `cdd716ae887716cbfdb033259af6e0463e1cbd4cdab7d5eca4a05c3ae6c20be2`.
- Release APK: 54,636,916 bytes,
  `6b656e5df5dfa23e44578a18320026e36769093238bd92c7893d382a24c2fa9f`.
- Xiaomi full runner: `run finished: 90 tests, 0 failed, 0 ignored`. The first two launches produced no result because
  MIUI blocked background ActivityScenario starts; a temporary target-app AppOp 10021 allowance enabled the formal run.
- Cleanup verified: AppOp 10021 restored to `ignore`; app/test stopped; screen Dozing; screen timeout 600,000 ms;
  lock-after 2,147,483,647; default IME still `com.flypy.input/PangIME.Android.InputService`.

## Risks

- ConfirmationRequest deliberately has no resume path. REC-010 must map reviewed user/preauthorization outcomes without
  turning a stale request into a new route attempt.
- Attempt is not Provider execution authority. The future bridge must revalidate its exact registry lease immediately
  before starting a Provider.
- No circuit breaker or production consumer exists yet; REC-011 and later migration tasks must preserve the terminal and
  privacy boundaries proven here.

## Rollback

- Remove RecognitionRouter and registry route leases, remove REC-009 gate/test rules, and mark REC-009 TODO. No data,
  schema, permission or active-route rollback is required.

## Follow-ups

- REC-010
- REC-011
- STR-010

## Git

- branch: `agent/android-offline-followup`
- commit: none created for this task
- worktree status: shared dirty worktree; unrelated and earlier task changes preserved
