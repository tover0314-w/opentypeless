# Task Report: REC-003

## Result

DONE

## Scope

- Implemented: bounded process-local `ProviderRegistry`; exact-ID register/enable/lookup/probe;
  lock-free callback execution with identity/generation revalidation; closed redacted results;
  JVM tests; source and compiled architecture gates; Debug/Release verification; strict full build;
  Xiaomi 10 Ultra app and Test Host regression runs; specification/backlog/test-matrix updates.
- Not implemented: Android/System/HTTP/local-model Provider adapters, Provider lifecycle contract,
  network/audio execution, health/circuit breaker, RecognitionRouter, persistence, or UI wiring.

## Changes

- `android/app/src/main/java/com/opentypeless/android/recognition/ProviderRegistry.java`: adds the
  package-confined 32-entry registry and probe lease/result model.
- `android/app/src/test/java/com/opentypeless/android/recognition/ProviderRegistryTest.java`: adds
  eight deterministic contract/race/privacy tests.
- `android/architecture-tests/**`: freezes source shape, dependencies, capacity, sequencing,
  result vocabulary, and redaction with a hostile fixture.
- `android/architecture-gate/**`: freezes the same boundary in Debug/Release bytecode and adds a
  malicious compiled fixture.
- `docs/opentypeless_specs/**`: records the architecture/security/test contract and marks REC-003
  DONE without claiming adapter/router integration.

## Architecture

- contracts: exact descriptor ID; duplicate/capacity rejection; canonical descriptor lookup;
  provider-level unavailable failure only; exact declared capability equality.
- state changes: one bounded map plus a non-wrapping generation; mutation/lookup synchronized;
  callback outside the monitor; returned observation accepted only for the same enabled entry and
  generation.
- migration: none.
- feature flag: none.

## Security & privacy

- data sent/stored: no network, disk, database, audio, transcript, endpoint, or Secret access.
  Registry state is process-local and bounded to reviewed descriptors/probe references.
- permissions/components: none added.
- threat considerations: duplicate overwrite, callback reentrancy, disable/re-enable ABA,
  generation wraparound, callback exception/null, capability drift, session-only failure misuse,
  unbounded storage, and diagnostic identity leakage all fail closed and have tests/gates.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `:app:testDebugUnitTest --tests ...ProviderRegistryTest` | PASS | 8/8 |
| Python architecture suite | PASS | 101/101; production scan PASS |
| `:architecture-gate:test` | PASS | 99/99 |
| `:architecture-gate:verifyCompiledArchitecture` | PASS | Debug/Release 2/2 |
| fresh-home `scripts/verify_android.sh all` | PASS | 187 tasks; 906 XML tests; strict dependency verification; lint and 5 APKs |
| Xiaomi app instrumentation | PASS | `OK (85 tests)`; 5 optional-fixture assumption-skips; 0 failures |
| Xiaomi Test Host instrumentation | PASS | `OK (4 tests)`; 3 executed + 1 unrequested candidate-IME assumption-skip |
| GitHub Actions for current HEAD | NOT RUN | no matching workflow run |

## Evidence

- HEAD: `80d20496c4eb59e4f27281becfa8a32021212e53`.
- Debug APK: 56,314,835 bytes,
  `7bdc4e0daa4b3d239be0351b6e4f473bf6be870e249350b48d937dc3e8ab8357`.
- AndroidTest APK: 990,776 bytes,
  `c6e99b4a44650b79bbb635e802914f6bde4502d1b6a4d68a53fc191fe0e37b82`.
- Device: Xiaomi 10 Ultra / Android 13 API 33 / HyperOS OS1.0; current four APKs installed
  unattended. Test-only MIUI app-op was restored to `ignore`; processes were force-stopped;
  screen is off, keyguard is not showing, and the previous default IME is unchanged.

## Risks

- Registry remains intentionally package-confined and unwired until REC-004 adapters. Its
  descriptor/probe result is not route or privacy authorization; REC-009 must revalidate both.
- Provider callback latency is not timed here; lifecycle/timeouts belong to adapter contracts.

## Rollback

- Remove the registry source/test and REC-003 gate/docs additions. No persisted data, schema,
  permission, Android component, or external service state requires migration.

## Follow-ups

- REC-004
- REC-005
- REC-006
- REC-008
- REC-009
- REC-011

## Git

- branch: `agent/android-offline-followup`
- commit: not created
- worktree status: dirty shared worktree; unrelated and prior-task changes preserved
