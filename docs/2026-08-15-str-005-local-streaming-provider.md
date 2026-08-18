# Task Report: STR-005

## Result

DONE

## Scope

- Implemented: a package-confined, bounded `LocalStreamingProvider` for the exact STR-004-selected
  Streaming Paraformer revision, backed by the existing app-private atomic model store/download seam
  and isolated local recognition process.
- Not implemented: production ProviderRegistry/RecognitionRouter/VoiceController activation,
  microphone or user-audio capture, the STR-006 final authority, UI/configuration/persistence, or a
  new feature flag.

## Changes

- `LocalStreamingProvider.java`: single-active, one-use local streaming lifecycle with Ready,
  monotonic Partial revisions, terminal Final/NoMatch/Failure/Cancelled, bounded copied PCM, timeouts,
  cancellation and deterministic cleanup.
- `ProviderCapabilities.java`: exact on-device native-streaming capability declaration.
- `LocalRealtimeRecognitionClient.java`: explicit Ready callback from the existing private-process
  streaming backend.
- `LocalStreamingProviderTest.java`: deterministic aggregate lifecycle, race, bound, mapping and
  redaction tests.
- `LocalStreamingProviderInstrumentedTest.java`: real Android private-process recognition against the
  revision-pinned upstream public WAV plus empty/cancel coverage.
- source and compiled architecture gates: exact provider/client/model/store/download scope, binary
  shapes, bounded lifecycle and production-routing default deny.

## Architecture

- contracts: provider ID `builtin.local-streaming-paraformer`; PCM16 mono 16 kHz; 64 KiB frame,
  256 KiB queued, 17,280,000 bytes total; 30 second Ready and 35 second finish timeout.
- state changes: one active in-memory session; terminal/cancel/close revoke backend, worker, timer,
  sink and temporary PCM references.
- migration: none.
- feature flag: none. The provider remains unregistered and cannot be selected by production code;
  STR-010 owns activation.

## Security & privacy

- Data sent/stored: no network transfer and no new persistence. Tests use only the pinned upstream
  public WAV and private model files; neither model nor WAV bytes are added to Git.
- Permissions/components: no new permission or exported component; no microphone call.
- Threat considerations: model revision/bytes/SHA-256 must verify before availability; PCM is copied,
  bounded and cleared; all terminal/error/toString paths are content-free; late callbacks cannot
  regain authority.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `LocalStreamingProviderTest` | PASS | 9/9 |
| Python source architecture suite and production scan | PASS | 113/113; production scan passed |
| `:architecture-gate:test` | PASS | 111/111 |
| `:architecture-gate:verifyCompiledArchitecture` | PASS | Debug/Release 2/2 |
| fresh `GRADLE_USER_HOME` `scripts/verify_android.sh all` | PASS | `BUILD SUCCESSFUL in 2m40s`; 189 tasks (185 executed, 4 up-to-date); 1045 XML tests; lint and five APKs |
| first Xiaomi exact-class run | FAIL | recognition completed; test-only `Stream.toList()` is unavailable on API 33 |
| final Xiaomi exact-class run | PASS | 2/2 in 15.368s with API 26-compatible assertion; real private-process model path |
| dynamic Android x86_64 run | NOT RUN | local Apple Silicon SDK has no runnable x86_64 Android system image; AAR/APK packaging is verified |
| GitHub Actions for current worktree | NOT RUN | worktree is uncommitted and current HEAD has no matching run |

## Evidence

- Model: `streaming-paraformer-bilingual-zh-en-int8-2023-08-14`, revision
  `8e40c43232a1c5c66c82111efc5820d3accca11b`; three files total 237,202,501 bytes.
- Xiaomi: `be4e2015`, M2007J1SC/cas, Android 13/API33, HyperOS
  `V816.0.4.0.TJJCNXM`; exact-class instrumentation 2/2 PASS.
- Public WAV: 321,744 bytes, SHA-256
  `7d93384d43b13cb54cd6e5c6ae4e572955aa336801332285c119206b9232f2f5`.
- App Debug: 56,414,377 bytes; SHA-256
  `04153086b5ccac5e92d5e3755f2cdfb8c10b211eec4d4f76f2ec1d20a56f4bd5`.
- App AndroidTest: 1,055,815 bytes; SHA-256
  `a4bdbe6515625607e7ac05d2582353a5a5ff44462c131a3c2c9d37846329c806`.
- Unsigned Release: 54,638,153 bytes; SHA-256
  `1dfde7fa0474e2d216007cf567e1fe315d5a456b64d14ef8735cea8e1553e0bc`.
- Strict preflight and APK inspection verify both `arm64-v8a` and `x86_64` sherpa-onnx/
  onnxruntime native libraries.

## Risks

- The selected candidate remains a non-authoritative first pass; STR-006 must provide the final
  transcript authority.
- Production routing, DisclosurePlan/EffectiveProfile enforcement and old/new path mutual exclusion
  are not exercised until STR-010.
- x86_64 is package-verified but was not dynamically executed on this Apple Silicon host.

## Rollback

- Remove `LocalStreamingProvider`, its capability bridge/tests/gates, and mark STR-005 TODO. No schema,
  permission or user-data migration is required; the optional private model can be removed through
  the existing verified model lifecycle.

## Follow-ups

- STR-006
- STR-010

## Git

- branch: `agent/android-offline-followup`
- commit: none created
- worktree status: shared dirty/untracked task tree preserved; no commit, push or PR performed
