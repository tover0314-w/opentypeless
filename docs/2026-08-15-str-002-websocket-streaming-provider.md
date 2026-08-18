# Task Report: STR-002

## Result

DONE

## Scope

- Implemented: package-confined single-session WebSocket `RecognitionProvider`, narrow OkHttp client,
  strict STR-001 event decoding, bounded PCM/queue/session limits, cancellation, ready/final timeouts,
  one safe pre-evidence reconnect, source/compiled gates, JVM fake-server tests, and Android runtime tests.
- Not implemented: production Router/VoiceController selection, DisclosurePlan/EffectiveProfile binding,
  real external ASR service compatibility, UI, new configuration, persistence, or Feature Flag.

## Changes

- `android/app/src/main/java/com/opentypeless/android/net/streaming/StreamingRecognitionWebSocketClient.java`:
  added the reviewed WebSocket transport with redirect/retry disabled and bounded credential/frame handling.
- `android/app/src/main/java/com/opentypeless/android/recognition/WebSocketStreamingProvider.java`:
  added the single-session Provider lifecycle, event mapping, queue/PCM bounds, timeout, cancel, and reconnect policy.
- JVM and Android instrumentation tests: added fake-server/chaos, timeout, bounds, redaction, retry, and terminal cases.
- architecture source/compiled gates: froze exact transport/provider authority, shape, callers, bounds, and failure redaction.

## Architecture

- Contracts: every server text event enters the STR-001 session-bound Stream and REC-002 validator.
- State changes: one active Session; at most one reconnect before server evidence/audio/stop; every terminal releases authority.
- Migration: none.
- Feature flag: none; the Provider is deliberately not production-selected until STR-010.

## Security & privacy

- Data sent/stored: only a bounded credential header, bounded control frames, and caller-supplied PCM can enter the client;
  no production route invokes it and nothing is persisted.
- Permissions/components: no new permission or exported component.
- Threat considerations: redirects and automatic retries are disabled; errors are content-free; PCM copies are cleared;
  queue/session totals and timeout/reconnect counts are bounded; credentials, audio, text, IDs, endpoints, and raw errors are redacted.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| targeted app JVM tests | PASS | client 5/5; Provider 8/8 |
| Python architecture suite and production scan | PASS | 111/111; source scan PASS |
| `:architecture-gate:check --rerun-tasks` | PASS | compiled tests 109/109; Debug/Release 2/2 |
| `scripts/verify_android.sh all` | PASS | 189 tasks; 1024 XML tests; lint and all assemblies passed |
| Xiaomi exact-class instrumentation | PASS | final clean-built APKs: 2/2 on Android 13/API 33 |
| GitHub Actions for current HEAD/dirty tree | NOT RUN | no run exists for uncommitted workspace changes |
| real external streaming ASR service | NOT RUN | production activation belongs to STR-010/STR-003 adapters |

## Evidence

- Final app Debug: 56,397,993 bytes; SHA-256 `4adeab86acdacfb3ae916ac6e52d998d28024aed45182009234182f439ed7c2d`.
- Final app AndroidTest: 1,004,000 bytes; SHA-256 `83f4826cf5bef53e245f12a8e5103f891d8431778e2b7e653ed66e659e1442a0`.
- Xiaomi `be4e2015`, M2007J1SC/cas, Android 13/API 33, HyperOS V816.0.4.0.TJJCNXM: final 2/2 PASS.
- Initial instrumentation run was 1/2 because test-only `Stream.toList()` is unavailable on API 33;
  after replacing it with API 26-compatible collection, rebuilding, and unattended overlay install, final runs were 2/2.

## Risks

- External provider-specific control frames and real network interoperability remain unverified until STR-003/STR-010.
- The Provider must not be production-wired before DisclosurePlan, EffectiveProfile/sensitive-field checks, and one-path Feature Flag selection exist.

## Rollback

- Remove the two STR-002 production classes, their tests/gates/docs, and restore the STR-001-only boundary.
  No persisted data or migration needs reversal.

## Follow-ups

- STR-003
- STR-010

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared dirty/untracked task tree; no commit, push, or PR performed.
