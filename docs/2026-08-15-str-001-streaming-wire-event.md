# Task Report: STR-001

## Result

DONE

## Scope

- Implemented: versioned, bounded, package-confined RecognitionEvent JSON codec; Draft 2020-12
  JSON Schema; session-bound sequence/revision/terminal validation; source/compiled architecture
  gates; JVM and Xiaomi 10 Ultra Android-runtime tests.
- Not implemented: WebSocket/SSE connection code, audio frames, reconnect/cancel policy, Provider,
  production recognition routing, DisclosurePlan, persistence, or Feature Flag.

## Changes

- `StreamingRecognitionWireEvent.java`: exact `opentypeless.streaming.v1` mapping for all eight
  REC-002 event variants and one session-bound validator entrypoint.
- `opentypeless-streaming-recognition-event-v1.schema.json`: closed transport-neutral wire schema.
- JVM/Android tests and `android/architecture-{tests,gate}/**`: round-trip, hostile input,
  sequence/final, redaction, caller/scope, schema drift, and Debug/Release binary coverage.
- compatibility matrix, changelog, architecture README, and specification package: protocol
  authority, evidence, security boundary, and Backlog completion.

## Architecture

- contracts: one strict JSON object per WebSocket text frame or SSE data event; exact v1 protocol;
  eight REC-002 variants; positive sequence; existing partial-revision and single-terminal rules.
- state changes: `Stream` owns one existing `RecognitionEventValidator`; malformed/foreign/stale
  input does not advance it, while one accepted terminal closes it.
- migration: none; no persisted format or legacy reader was changed.
- feature flag: none; STR-001 is not connected to production routing.

## Security & privacy

- data sent/stored: none. Bounded transcript/metadata values exist only in process memory while
  encoding or decoding; no log, Bundle, diagnostic, file, database, history, or network sink added.
- permissions/components: none added.
- threat considerations: exact keys/types, 524,288 UTF-16-unit envelope bound, well-formed UTF-16,
  redacted stable errors, default-deny raw decoder callers, no Android/editor/audio/network/
  execution/persistence/serialization/Secret authority.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| STR-001 JVM test | PASS | 7/7 |
| full app JVM suite | PASS | 902/902 |
| Python architecture suite + production scan | PASS | 110/110; production source passed |
| compiled architecture gate + production verify | PASS | 108/108; Debug/Release 2/2 |
| strict `scripts/verify_android.sh all` | PASS | 49s; 189 tasks; 1010 XML tests; lint and five APKs |
| Xiaomi STR-001 instrumentation | PASS | 2/2 on Android 13/API 33 |
| WebSocket/SSE integration | NOT RUN | network/provider implementation belongs to STR-002 |
| current worktree CI | NOT RUN | HEAD has no matching run; STR-001 changes are uncommitted |

## Evidence

- Strict build: `BUILD SUCCESSFUL in 49s`, 189 tasks (186 executed, 3 up-to-date), zero XML
  failures/errors/skips.
- Xiaomi: `be4e2015`, M2007J1SC/cas, Android 13/API 33, HyperOS
  `V816.0.4.0.TJJCNXM`; app and test APK installed unattended; `OK (2 tests)`.
- App Debug SHA-256:
  `ea5ede83632ad9c27c416e565cd3e550c58352be9f287234da49cef42b61f445`.
- App AndroidTest SHA-256:
  `aec7d7997edc6f50aa5aa9f49951d21805b23f2332027c50ef5423087f89e24a`.
- Cleanup force-stopped the targets, left the screen Dozing without keyguard, and preserved
  `com.flypy.input/PangIME.Android.InputService` as the default IME.

## Risks

- STR-001 proves only a transport-neutral event contract. STR-002 must separately enforce TLS,
  redirect, timeout, frame, cancellation, reconnect, disclosure, and server-chaos behavior.
- Unknown future protocol versions intentionally fail closed; compatibility requires a separately
  reviewed protocol version and changelog entry.

## Rollback

- Revert the STR-001 codec/schema/test/gate/compatibility/doc changes and mark STR-001 TODO. No
  persisted data, permission, installed model, or production route requires rollback.

## Follow-ups

- STR-002
- STR-005

## Git

- branch: `agent/android-offline-followup`
- commit: none created
- worktree status: shared dirty/untracked worktree preserved; STR-001 changes are uncommitted
