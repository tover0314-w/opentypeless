# Task Report: REC-002

## Result

DONE

## Scope

- Implemented: immutable eight-variant `RecognitionEvent`, bounded `RecognitionMetadata`, and a
  synchronized per-session sequence/revision/terminal validator.
- Implemented: JVM contract/race tests, source and Debug/Release compiled architecture gates,
  specification mirrors, and current-build Xiaomi regression evidence.
- Not implemented: Provider callback adapters, ProviderRegistry/probe, Router/fallback, network or
  audio execution, editor delivery, or a provider contract suite.

## Changes

- `android/app/src/main/java/com/opentypeless/android/recognition/RecognitionEvent.java`: closed,
  bounded event vocabulary with redacted diagnostics.
- `android/app/src/main/java/com/opentypeless/android/recognition/RecognitionMetadata.java`: bounded
  optional Final metadata with presence-only diagnostics.
- `android/app/src/main/java/com/opentypeless/android/recognition/RecognitionEventValidator.java`:
  O(1), synchronized, content-free sequence/revision/terminal gate.
- `android/app/src/test/java/com/opentypeless/android/recognition/RecognitionEventTest.java` and
  `RecognitionEventValidatorTest.java`: exact shape, limits, terminal, stale/foreign, revision, and
  deterministic concurrency coverage.
- `android/architecture-tests/**` and `android/architecture-gate/**`: source/compiled shapes,
  dependency boundary, redaction, negative fixtures, required Debug/Release binaries, and sequence
  state-update checks.
- `docs/opentypeless_specs/{02,06,07,08}_*.md`, `OpenTypeless_FULL_SPEC.md`, package manifest files,
  and this report: contract, security, status, and actual test evidence.

## Architecture

- Contracts: `Preparing`, `Ready`, `SpeechStarted`, `Partial`, `Endpoint`, `Final`, `Failure`, and
  `Cancelled`; every event has an opaque `SessionId` and positive sequence.
- State changes: validator accepts only increasing same-session events, exact Partial revision links,
  and permanently closes after the first accepted Final/Failure/Cancelled.
- Migration: none; no persisted format or existing provider callback path changed.
- Feature flag: none; this is a pure domain contract and is not wired to production providers yet.

## Security & privacy

- Data sent/stored: no network or persistence. The validator retains only one SessionId, two sequence
  scalars, and a terminal bit; it never retains an event or transcript.
- Permissions/components: none added.
- Threat considerations: text is well-formed UTF-16 and capped at 20,000 code points; metadata has
  independent bounds; cancellation cannot masquerade as generic failure; diagnostics omit session,
  text, metadata values, provider identity, and raw errors. Gates reject Android, execution,
  persistence, serialization, endpoint, and secret authority.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| Targeted `:app:testDebugUnitTest` for the two REC-002 classes | PASS | 10/10, 0 failure/error/skipped |
| `python3 -m unittest discover -s android/architecture-tests -p 'test_*.py' -v` | PASS | 100/100 |
| `python3 android/architecture-tests/architecture_contracts.py --android-root android` | PASS | Production source scan |
| `./gradlew :architecture-gate:test --rerun-tasks --no-parallel` | PASS | 98/98 |
| `./gradlew :architecture-gate:verifyCompiledArchitecture --rerun-tasks --no-parallel` | PASS | Debug/Release 2/2 |
| Fresh strict `scripts/verify_android.sh all` | PASS | 187 tasks; 897 XML tests; lint and all requested assemblies |
| First Xiaomi full runner while screen was Dozing | FAIL | One Activity stayed STOPPED; run terminated and not counted as passing |
| Xiaomi targeted `AppPickerInstrumentedTest` after wake | PASS | 2/2; proves the first failure was screen state |
| Xiaomi full app runner after wake | PASS | `OK (85 tests)`, 0 failure; 5 optional fixture assumption-skips |
| Final Xiaomi settings/app-op/default-IME readback | PASS | Dozing, keyguard not showing, app-ops ignore, original IME retained |

## Evidence

- Fresh strict build: `BUILD SUCCESSFUL in 2m 23s`; 184 executed, 3 up-to-date.
- App JVM: 799/799; compiled gate: 98/98; source gate: 100/100.
- Debug APK: 56,314,835 bytes,
  `4a45ebc7a2253f85d4c955fcb66396e2ced05a95ee62a2dd38e8ce6a9b0f919b`.
- AndroidTest APK: 990,776 bytes,
  `c6e99b4a44650b79bbb635e802914f6bde4502d1b6a4d68a53fc191fe0e37b82`.
- Device: Xiaomi 10 Ultra `be4e2015`, Android 13/API 33, HyperOS
  `OS1.0.4.0.TJJCNXM`; current packages installed unattended with `--no-streaming -r -t`.
- Current HEAD has no matching GitHub Actions run; CI is NOT RUN.

## Risks

- REC-002 validates sequence/revision/terminal invariants, not provider lifecycle phase ordering; the
  shared provider contract suite remains TST-003 after adapters exist.
- No callback path consumes this model yet. Adapter integration must preserve one SessionId and feed
  every event through this validator before engine publication.

## Rollback

- Remove the three REC-002 production classes, their two tests, and the REC-002 gate/docs additions.
  No database, preference, file, permission, component, or external-service rollback is required.

## Follow-ups

- REC-003
- REC-004
- TST-003

## Git

- branch: `agent/android-offline-followup`
- commit: none created
- worktree status: dirty shared worktree; REC-002 changes are uncommitted alongside prior task work
