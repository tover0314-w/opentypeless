# Task Report: SEC-001 PrivacyPolicyEngine

## Result

DONE

## Scope

- Implemented: a pure, fail-closed privacy authority that intersects resolved profile constraints,
  sensitive-field state, Android no-learning, global incognito, App maximums and user choices for
  Voice, context sending, history, Action, clipboard, learning and Teach.
- Not implemented: field classification, toolbar visibility, Android lifecycle wiring, persistence,
  network disclosure, UI or device behavior. Those remain SEC-002/005 and their owning tasks.

## Changes

- `android/app/src/main/java/com/opentypeless/android/security/PrivacyPolicyEngine.java`: closed
  capability/reason vocabulary, immutable bounded inputs and decisions, fixed precedence and
  redacted diagnostics.
- `android/app/src/test/java/com/opentypeless/android/security/PrivacyPolicyEngineTest.java`: twelve
  JVM tests for sensitive/no-learning/incognito/App/profile/UI intersection and invalid inputs.
- `android/architecture-tests/privacy_policy_contract.py` and its tests: source hard gate and eight
  hostile fixtures.
- `android/architecture-gate`: exact compiled exception for the policy engine to consume only
  `EffectiveProfile.ResolvedValue`; resolver source/explanation vocabulary remains forbidden.
- `scripts/verify_android.sh`: SEC-001 source contract is part of preflight.

## Architecture

- contracts: `Request -> Policy`, with one immutable `Decision` for each closed `Capability`.
- state changes: none; evaluation is deterministic and side-effect free.
- migration: none.
- feature flag: none.

Precedence is fixed as sensitive field, no-learning, incognito, App maximum, resolved profile, then
user choice. Every layer can only remove authority. Teach cannot remain enabled when learning is
disabled. The engine deliberately consumes only CFG-005 terminal values and cannot inspect or
recompute resolver provenance.

## Security & privacy

- data sent/stored: none.
- permissions/components: none.
- threat considerations: Android/editor/network/storage/reflection capabilities are absent. Request
  and result diagnostics expose only booleans and allow/deny counts, never package/profile IDs or
  editor text. A profile whose terminal values exactly equal the hard-sensitive shape is treated
  conservatively as sensitive rather than risking a caller downgrade.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| SEC-001 hostile source contract | PASS | 8/8. |
| targeted Gradle policy + architecture tests | PASS | policy 12/12; architecture gate 114/114; Debug/Release compiled gate 2/2. |
| `scripts/verify_android.sh preflight` | PASS | 119 script, 11 Android-script, 158 architecture and 10 mobile-voice tests. |
| `scripts/verify_android.sh unit` | PASS | clean 68/68 tasks; app JVM 975/975, architecture-gate 114/114, compiled Debug/Release 2/2. |
| Android instrumentation/device | NOT RUN | SEC-001 has no Android UI/runtime wiring; SEC-002/005 own those paths. |

An initial targeted Gradle command used the nonexistent `:app:verifyCompiledArchitecture` task and
failed before tests ran. It was corrected to `:architecture-gate:verifyCompiledArchitecture`; the
corrected command and both full gates pass.

## Evidence

- compiled gate output: `compiled architecture gate passed: 2 variant(s)`.
- clean JVM reports: 975 app tests and 114 architecture-gate tests, zero failure/error/skipped.
- preflight source architecture suite: 158/158; SEC-001 hostile suite: 8/8.

## Risks

- The conservative terminal-shape check may deny extra capabilities for a non-sensitive profile
  that independently resolves to the same five hard-safe values. This is fail-closed and does not
  grant authority; exposing resolver provenance outside CFG-005 would be a larger contract change.
- No product behavior changes until SEC-002 classifies fields and SEC-005 applies the policy to the
  toolbar/runtime.

## Rollback

Remove the pure engine, tests and exact gate exception, then remove its preflight hook. There is no
stored state or migration to reverse.

## Follow-ups

- `SEC-002`
- `SEC-005`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
