# Task Report: REC-001

## Result

DONE

## Scope

- Implemented: immutable `ProviderDescriptor`; complete `ProviderCapabilities`; closed audio-format
  vocabulary; explicit declarations for all five built-in recognition backends; source and compiled
  architecture gates; JVM tests; specification and validation evidence.
- Not implemented: `RecognitionProvider`, `RecognitionEvent`, registry/probe, router, network adapters,
  health/circuit-breaker logic, or UI. These remain REC-002 onward.

## Changes

- `android/app/src/main/java/com/opentypeless/android/recognition/ProviderDescriptor.java`: bounded,
  redacted provider identity plus capabilities.
- `android/app/src/main/java/com/opentypeless/android/recognition/ProviderCapabilities.java`: ten feature
  flags, privacy, bounded duration, immutable formats, contradiction checks, explicit backend matrix.
- `android/app/src/main/java/com/opentypeless/android/ime/VoicePipelineRuntime.java`: consumes the explicit
  capability declaration instead of the former partial model.
- Recognition JVM tests: exact shape, declaration matrix, invalid combinations, Unicode bounds,
  defensive copies, and redaction.
- `android/architecture-tests/**` and `android/architecture-gate/**`: source and Debug/Release compiled
  rules with hostile fixtures for partial/name-inferred/leaky declarations.
- `docs/opentypeless_specs/**`: architecture, security, backlog, test evidence, full mirror, package
  validation, and manifest.

## Architecture

- contracts: descriptor is exactly `(id, displayName, capabilities)`; capabilities are exactly ten
  booleans plus `PrivacyClass`, optional bounded duration, and closed `AudioFormat` set.
- state changes: none; these are immutable values.
- migration: existing five backend cases now use an exhaustive enum bridge with explicit declarations;
  no persisted format changes.
- feature flag: none.

## Security & privacy

- data sent/stored: no new network, disk, preference, database, Bundle, or export data.
- permissions/components: none added.
- threat considerations: names cannot infer capability; contradictory streaming/keyterm/on-device/
  privacy/upload combinations fail at construction; diagnostic text omits ID/display/format details;
  the model carries no Android, Secret, Endpoint, callback, thread, or execution capability.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| targeted `ProviderCapabilitiesTest` + `ProviderDescriptorTest` | PASS | 8/8 |
| Python architecture contract suite and production scan | PASS | 99/99 plus production scan |
| `:architecture-gate:test :architecture-gate:verifyCompiledArchitecture --rerun-tasks` | PASS | 97/97 and Debug/Release 2/2 |
| fresh-home `scripts/verify_android.sh all` with strict dependency verification | PASS | 187 tasks; app JVM 789/789; gate 97/97; 886 XML tests; lint and all APK assemblies |
| Xiaomi exact `EditorTransactionManagerInstrumentedTest` | PASS | 26/26 |
| Xiaomi full app AndroidJUnitRunner | PASS | `OK (85 tests)`; 5 optional-fixture assumption skips; 0 failure |

An intermediate compiled-gate run failed because JDK 17 creates one synthetic enum-switch nestmate
for each explicit backend switch. The final gate now allows exactly those compiler-generated `$1`
members and no other nestmate; both hostile fixtures and both production variants pass afterward.

## Evidence

- Git HEAD: `80d20496c4eb59e4f27281becfa8a32021212e53`
- Debug APK: 56,314,835 bytes,
  `9948f51cd3c675d324a2bb6d4966b7f6897793ab2f8c23c7e061b4a3e1eb5e73`
- AndroidTest APK: 990,776 bytes,
  `c6e99b4a44650b79bbb635e802914f6bde4502d1b6a4d68a53fc191fe0e37b82`
- Xiaomi: `be4e2015`, M2007J1SC/cas, Android 13/API 33, HyperOS OS1.0,
  V816.0.4.0.TJJCNXM.
- Post-test device state: screen off (`Dozing`), keyguard not showing, temporary MIUI app-op restored,
  default IME unchanged.

## Risks

- Static capability declarations are not runtime probe results. REC-003/REC-009 must resolve exact
  descriptors and revalidate route/privacy requirements.
- The model currently has one PCM16 mono 16 kHz audio format. New formats require an explicit closed-
  vocabulary and gate update.

## Rollback

- Revert the REC-001 model, its two VoicePipelineRuntime accessor changes, tests, gates, and matching
  documentation together. No data rollback or migration is required.

## Follow-ups

- REC-002
- REC-003
- STR-004

## Git

- branch: `agent/android-offline-followup`
- commit: not created
- worktree status: dirty shared worktree; unrelated existing changes preserved
