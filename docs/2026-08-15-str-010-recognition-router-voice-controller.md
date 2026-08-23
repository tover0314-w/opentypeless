# Task Report: STR-010

## Result

DONE

## Scope

- Implemented: production `VoiceController` Router decision bridge; exact EffectiveProfile/canonical descriptor/registry/Router attempt binding;
  whole-controller rollback flag; three production caller migrations; source/compiled architecture gates; JVM and Android instrumentation.
- Not implemented: new endpoint or DisclosurePlan; direct production microphone execution by the generic two-stage/WebSocket/Qwen Provider objects;
  dynamic prompt, endpoint/smart-turn, performance collection, standard RecognitionService route configuration.

## Changes

- `android/app/src/main/java/com/opentypeless/android/recognition/RecognitionRouterVoiceController.java`: finite Router decision bridge with
  generation-safe lifecycle and stable redacted failure mapping.
- `android/app/src/main/java/com/opentypeless/android/recognition/RecognitionRouterVoiceConfig.java`: default-on, synchronous whole-controller
  selector/rollback flag.
- `OpenTypelessImeService`, `VoiceLabActivity`, `VoicePipelineRecognitionEngine`: freeze exactly one selected controller at construction.
- JVM/instrumentation tests and source/compiled architecture gates: behavior, call graph, scope, flag, redaction and Debug/Release binaries.
- architecture/spec/test documentation: exact boundary and executed evidence.

## Architecture

- contracts: each start resolves one exact EffectiveProfile, registers one canonical backend descriptor, obtains one identity-bound Router Attempt,
  and only then may invoke the compatibility delegate once.
- state changes: one preparing or active generation; stop/cancel/terminal remove authority; late/foreign callbacks are ignored.
- migration: default-on `recognition_router_v1`; disabling returns the same pre-existing `VoicePipelineAdapter`; no session can use both paths.
- feature flag: private preferences, synchronous commit, three exact production consumers.

## Security & privacy

- data sent/stored: no new endpoint, Secret, audio/transcript persistence or diagnostic content; only the boolean rollback flag is newly stored.
- permissions/components: none added.
- threat considerations: sensitive fields fail before registry/delegate/microphone; descriptor/probe/profile drift fails closed; Router reject cannot
  fall through; errors exclude raw Throwable/OEM/provider content. The bridge does not authorize a new network destination or generic Provider
  execution.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `python3 -m unittest discover -s android/architecture-tests -p 'test_*.py' -v` | PASS | 115/115; production source scan PASS |
| `./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest :architecture-gate:check --rerun-tasks --no-parallel` | PASS | app JVM 950/950; compiled gate 113/113; production variants 2/2 |
| fresh `GRADLE_USER_HOME` `scripts/verify_android.sh all` | PASS | 189 tasks; 1063 XML tests; lint; 5 APKs |
| emulator exact-class instrumentation | PASS | `RecognitionRouterVoiceConfigInstrumentedTest` 1/1 |
| Xiaomi 10 Ultra exact-class instrumentation | PASS | `be4e2015`, Android 13/API33, 1/1 |
| GitHub Actions for current HEAD | NOT RUN | HEAD has no run; shared worktree remains uncommitted |
| real microphone/external service through generic Provider objects | NOT RUN | outside the production execution binding delivered by this task |

## Evidence

- clean Debug APK SHA-256: `3adeb1ce0018e9ce914b813804f985d28aa64620783509ca21f6a82108d74a1b`.
- clean AndroidTest APK SHA-256: `756073d6f4c1f9464a259f7d383914d3b9b9e668ac627f3778b16977f7fd0ab8`.
- clean unsigned Release APK SHA-256: `21c5d02b0181670b421bc618cdae8e82632485301ad33dceb6591ae80d1900eb`.
- Xiaomi remained unlocked, default IME unchanged, screen-off 600,000 ms, lock-after 2,147,483,647 ms.

## Risks

- `VoicePipelineAdapter` remains the compatibility executor; direct generic Provider audio execution needs its own config/disclosure/E2E evidence.
- current HEAD has no GitHub Actions result and the shared worktree is not a reviewable commit.

## Rollback

- Set `recognition_router_v1=false` before constructing a new caller-owned controller; the selector returns the same compatibility delegate.
- The rollback does not modify editor transaction, route configuration, persisted user data or model artifacts.

## Follow-ups

- STR-007
- STR-008
- STR-009
- REC-013
- ACT-009

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: dirty/untracked shared worktree; no commit, push or PR created
