# Task Report: RIM-001 RimeInputEngine contract

## Result

DONE

## Scope

- Implemented: a pure Java Rime engine contract covering activation, deactivation, key processing,
  snapshots, candidate-page requests and candidate selection; closed lifecycle/process/failure
  results; generation/revision identity and bounded redacted values.
- Not implemented: JNI, librime loading, Schema deployment, UserDB, product service/UI wiring or
  real Chinese input. Those remain RIM-002..009.

## Changes

- `RimeInputEngine`: capability-free lifecycle and input contract with closed request/result types.
- `RimeEngineSnapshot`: immutable phase/generation/revision/preedit/candidate snapshot that rejects
  producer or identity mismatch.
- deterministic contract fake and JVM tests; production runtime was deliberately not introduced.
- `rime_engine_contract.py`: exact source/surface/capability gate and hostile fixtures, wired into
  the canonical Android preflight.

## Architecture

- contracts: all text and candidate values are bounded; every asynchronous request carries editor
  generation and coordination identity; failures use stable content-free enums.
- state changes: none in production; the contract has no Android lifecycle or persistent state.
- migration: none.
- feature flag: none; KBD-008 remains Latin-only until RIM-004/005 register a real engine.

## Security & privacy

- data sent/stored: none.
- permissions/components: none.
- threat considerations: the interface has no Android, JNI, editor, network, storage or reflection
  capability; diagnostics redact preedit, candidate and commit text; control/bidi and invalid
  Unicode input fail closed.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| RIM-001 hostile source contract | PASS | 9/9; full Android architecture Python suite 202/202. |
| `RimeInputEngineContractTest` | PASS | 8/8 deterministic contract tests. |
| clean `verify_android.sh all` | PASS | scripts 119/119; Android scripts 11/11; Gradle 191 tasks (187 executed, 4 up-to-date); app JVM 1012/1012; architecture-gate 114/114. |
| KSP-012 repository and post-build scans | PASS | 3 product + 2 test APKs; real Xiaohè 0, forbidden Rime resource 0, violations 0. |
| device/instrumentation | NOT RUN | Contract-only task has no runtime or UI path; a device run would not exercise Rime. |

The first Python invocation used paths relative to the wrong directory and failed before running;
the corrected repository-root invocation passed. The initial gate also treated the project's own
`com.opentypeless.android` package name as an Android capability; it was narrowed to actual Android
imports/types. The first targeted JVM compile used a JUnit 5 assertion unavailable in JUnit 4.13,
and the second reflection assertion again confused the project package with the Android SDK; both
test-only mistakes were corrected without weakening the product contract. One final preflight was
also invoked without `JAVA_HOME`; it passed the resource scan and then failed closed before Gradle.
The fixed JDK 17 invocation above is the recorded final result.

## Evidence

- Debug APK: 56,500,945 bytes, SHA-256
  `218fcdd2afb71e50347c8011c112de27156ecc3833d41740645e841a444395ab`.
- unsigned Release APK: 54,658,721 bytes, SHA-256
  `f89c1b42578d20e959f77e70b0881a44ad04473bbf447885758ce73cc2152c0c`.
- app AndroidTest APK: 1,076,039 bytes, SHA-256
  `f65d98894a7ef8cd8ee1c7f820ef196535041789cb3eee33a38272c58e216115`.
- product/test scan manifests: `200e05f8e80f6b2092344e81aca4e4087e0a4092ab474ba7b54bd2d8265dc91a` /
  `53365483d557a58e59e64ac3873075e8791d04554ad7a4b8fff82d9ad6f1b11b`.

## Risks

- This task makes the engine boundary reviewable but adds no user-visible Chinese typing. Personal
  usability still depends on RIM-002..005 and a lawful local Schema path.
- Release R8 removes the unused contract today; that is expected until runtime wiring is added.

## Rollback

Remove the two contract classes, their JVM/static tests and the preflight hook. There is no stored
state, permission, component or migration to unwind.

## Follow-ups

- `RIM-002`
- `RIM-003`
- `RIM-004`
- `RIM-005`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
