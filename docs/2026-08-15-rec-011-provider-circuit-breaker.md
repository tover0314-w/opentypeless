# Task Report: REC-011

## Result

DONE

## Scope

- Implemented: bounded process-local Provider circuit breaker integrated into the package-confined
  `RecognitionRouter`; exact canonical-descriptor identity tracking; three-failure threshold,
  30-second open window, one half-open probe, recovery/reopen/abandon semantics, JVM and source/
  compiled architecture gates, strict build, and Xiaomi 10 Ultra runtime validation.
- Not implemented: Provider execution, UI, persistence, configuration, networking, or production
  VoicePipeline/VoiceController migration.

## Changes

- `ProviderCircuitBreaker.java`: fixed-capacity identity state, one-shot owner/entry/epoch permits,
  closed health-failure table, fail-closed clock/deadline/generation handling, and redacted state.
- `RecognitionRouter.java`: exact breaker acquire/failure/success/abandon settlement around validated
  registry leases, including half-open liveness across registry ABA.
- `ProviderCircuitBreakerTest.java`, `RecognitionRouterTest.java`, and
  `AndroidRecognitionContractsInstrumentedTest.java`: deterministic threshold, timer, concurrency,
  identity, failure-table, overflow, ABA, redaction, shared-router and Android-runtime coverage.
- `android/architecture-tests/**`, `android/architecture-gate/**`: REC-011 source and Debug/Release
  binary shape, policy, call-edge, scope, caller and redaction gates with hostile fixtures.
- `docs/opentypeless_specs/**`: architecture, security, Backlog, validation and full-spec evidence.

## Architecture

- contracts: at most 32 canonical descriptor identities; three consecutive provider-health
  failures open the identity for 30 seconds; expiry admits one exact half-open probe.
- state changes: success, `NO_MATCH`, or `SPEECH_TIMEOUT` closes/resets; a health failure or
  unresolved route-lease abandonment reopens; neutral failures do not accumulate.
- migration: none; breaker state is process-only and non-persistent.
- feature flag: none; this remains an unwired package-confined router decision seam.

## Security & privacy

- data sent/stored: none. State contains no endpoint, Secret, audio, transcript, callback, Provider,
  Android object, editor capability, route text, or persisted identifier.
- permissions/components: none added.
- threat considerations: canonical identity prevents same-name state injection; permit identity and
  one-shot consumption reject foreign/stale/replayed outcomes; privacy/profile/route validation
  precedes acquire; clock rollback, exceptions, deadline overflow and generation exhaustion fail
  closed; diagnostics do not reveal provider/route/profile identity.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| breaker + router JVM tests | PASS | 24/24 |
| full app JVM suite | PASS | 883/883 |
| Python architecture suite + production scan | PASS | 108/108; production source passed |
| `:architecture-gate:check` | PASS | compiled 106/106; Debug/Release production 2/2 |
| fresh strict `scripts/verify_android.sh all` | PASS | 2m11s; 187 tasks; 989 XML tests; lint and five APKs |
| Xiaomi REC-011 exact instrumentation | PASS | 1/1 |
| Xiaomi app instrumentation | PASS | `OK (91 tests)`; 5 optional model/WAV assumptions skipped |
| Xiaomi Test Host instrumentation | PASS | `OK (4 tests)`; candidate-only accessibility assumption skipped |
| Current worktree CI | NOT RUN | HEAD has no run and the REC-011 worktree is uncommitted |

## Evidence

- Fresh Gradle home: `/tmp/opentypeless-rec011-gradle.AgYHKc`; `BUILD SUCCESSFUL in 2m 11s`,
  187 tasks (184 executed, 3 up-to-date), 134 XML reports / 989 tests / zero failures, errors or
  skips.
- Xiaomi: `be4e2015`, M2007J1SC/cas, Android 13/API 33, HyperOS
  `V816.0.4.0.TJJCNXM`; all four debug/test APKs installed unattended.
- App Debug SHA-256:
  `78755e77a944632d2e9cfdfb1bb32d2be1047c678922032c52dbcabaea29d976`.
- Test cleanup restored MIUI AppOp 10021 and UID `RECORD_AUDIO` to `ignore`, force-stopped targets,
  left the screen Dozing without keyguard, preserved the ten-minute screen timeout/no-lock policy,
  and kept `com.flypy.input/PangIME.Android.InputService` as default IME.

## Risks

- The breaker does not execute a Provider. The future production bridge must preserve exact
  registry/profile/privacy identity and settle every permit on success, failure, or abandonment.
- Process restart intentionally clears health state; adding persistence would require a separate
  privacy/schema/migration task.

## Rollback

- Remove the REC-011 breaker, Router integration, tests/gates/docs, restore the REC-010 Router
  constructor/shape, and mark REC-011 TODO. No data migration or persisted state needs rollback.

## Follow-ups

- REC-012
- REC-013
- STR-010

## Git

- branch: `agent/android-offline-followup`
- commit: none created
- worktree status: shared dirty/untracked worktree preserved; REC-011 changes are uncommitted
