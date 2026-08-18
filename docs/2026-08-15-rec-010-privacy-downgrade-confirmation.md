# Task Report: REC-010

## Result

DONE

## Scope

- Implemented: profile-bound privacy preauthorization and one-time confirmation inside the
  package-confined `RecognitionRouter`; exact pending request/registry lease validation; cancel,
  stale, foreign, ABA, sensitive, mismatch and generation fail-closed behavior; JVM and source/
  compiled architecture gates; strict build and Xiaomi 10 Ultra regression.
- Not implemented: UI, persistence, provider execution, circuit breaker, production
  VoicePipeline/VoiceController migration, or a network route switch.

## Changes

- `RecognitionRouter.java`: binds one exact `EffectiveProfile` and `PrivacyAuthorization`, exposes
  one-time `onConfirmation`, reuses the original pending lease, and keeps diagnostics content-free.
- `RecognitionRouterTest.java`: 14 table/identity/privacy/ABA/overflow tests.
- `android/architecture-tests/**`, `android/architecture-gate/**`: REC-010 source and Debug/Release
  binary shape, policy, redaction, lease reuse, scope and caller checks with hostile fixtures.
- `docs/opentypeless_specs/**`: architecture, security, Backlog, validation and full-spec evidence.

## Architecture

- contracts: an authorization belongs to one exact immutable profile and has either a confirmation
  requirement or an explicit maximum privacy exposure; a one-time approval belongs to one exact
  pending request and registry lease.
- state changes: `AWAITING_CONFIRMATION` moves to `ACTIVE` only after profile and lease revalidation;
  cancel moves to terminal failure; stale/foreign/replayed requests do not advance state.
- migration: none.
- feature flag: none; the router remains an unwired package-confined primitive.

## Security & privacy

- data sent/stored: none. Authorization/request tokens contain no endpoint, secret, audio or text
  and are not serialized or persisted.
- permissions/components: none added.
- threat considerations: sensitive/Disabled profiles fail before registry access; route mismatch,
  over-bound privacy, disable-enable ABA, foreign profile/token, replay and generation overflow fail
  closed. Approval publishes the request's original lease rather than acquiring a newer one.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `RecognitionRouterTest` | PASS | 14/14 |
| Python architecture suite + production scan | PASS | 107/107; production source passed |
| `:architecture-gate:test :architecture-gate:verifyCompiledArchitecture` | PASS | 105/105; Debug/Release 2/2 |
| fresh strict `scripts/verify_android.sh all` | PASS | 2m22s; 187 tasks; 978 XML tests; lint and five APKs |
| Xiaomi app instrumentation | PASS | `OK (90 tests)`; 0 failed/ignored in TestRunner |
| Xiaomi Test Host instrumentation | PASS | `OK (4 tests)`; candidate-only accessibility check assumption-skipped |
| Current worktree CI | NOT RUN | existing successful HEAD runs predate the uncommitted REC-010 changes |

## Evidence

- Fresh Gradle home: `/tmp/opentypeless-rec010-gradle.xLKMqq`; `BUILD SUCCESSFUL in 2m 22s`,
  187 tasks (184 executed, 3 up-to-date), 133 XML reports / 978 tests / zero failures, errors or
  skips.
- Xiaomi: `be4e2015`, M2007J1SC/cas, Android 13/API 33, HyperOS
  `V816.0.4.0.TJJCNXM`; all four debug/test APKs installed unattended.
- App Debug SHA-256:
  `6e4396d1b5cac1d299db380fdceb5c17ca18f7969a2f6912b5b16ba9d0ff71dd`.
- Test cleanup restored AppOp 10021 to `ignore`, RECORD_AUDIO to `ignore`, force-stopped targets,
  left the screen Dozing, and preserved `com.flypy.input/PangIME.Android.InputService` as default.

## Risks

- The confirmation seam is intentionally not wired to production UI or provider execution. That
  integration must preserve exact-profile/lease identity and update the default-deny caller gate.
- `Attempt` remains a decision token, not execution authority; the future bridge must revalidate the
  lease immediately before provider execution.

## Rollback

- Remove the REC-010 profile/authorization/confirmation additions and matching tests/gates/docs,
  restore the REC-009 pending-only router contract, and mark REC-010 TODO. No data migration or
  persisted state requires rollback.

## Follow-ups

- REC-011
- REC-012
- STR-010

## Git

- branch: `agent/android-offline-followup`
- commit: none created
- worktree status: shared dirty/untracked worktree preserved; REC-010 changes are uncommitted
