# Speech Core v2 implementation plan

Date: 2026-08-11
Authority: `2026-08-11-speech-core-v2-architecture.md`

## Delivery rule

Build v2 beside the current Voice Core. Do not rewrite `VoicePipeline` or
`OpenTypelessImeService` in place before the pure v2 core and replay gates pass. Existing uncommitted
work remains user-owned and must not be overwritten.

Every milestone has three conditions:

1. its API and invariants are documented;
2. its deterministic tests pass from a clean compile;
3. the current v1 JVM/lint/build baseline remains green.

## Planned package boundaries

```text
com.opentypeless.android.speech.core
  VoiceDraft, VoiceSegment, SegmentRevision, VoiceDraftReducer
  CaptureState, SegmentStage, DeliveryState, TerminalReason

com.opentypeless.android.speech.engine
  SpeechEngine, EngineCapabilities, EngineEvent, EngineSession
  adapters/system, adapters/local, adapters/dashscope, adapters/batch

com.opentypeless.android.speech.audio
  CaptureController, EndpointDetector, SegmentAudio, BoundaryEvent

com.opentypeless.android.speech.transform
  SegmentTransformPipeline, PunctuationTransform, PersonalizationTransform

com.opentypeless.android.speech.delivery
  EditorProjection, ProjectionTarget, ProjectionResult, SessionUndoLedger

com.opentypeless.android.speech.journal
  VoiceDraftJournal, JournalEntry, JournalRecovery

com.opentypeless.android.speech.runtime
  SpeechCoreCoordinator, ModelScheduler, RuntimePolicy
```

The package names are intentionally separate from `ime.VoicePipeline`; integration imports only
stable public interfaces.

## Milestone 0 — baseline and architecture

- Record branch, HEAD, dirty-file inventory and current test counts.
- Add architecture, implementation and acceptance documents.
- Record rejected alternatives and migration/rollback policy.
- Keep the existing path unchanged.

Exit gate:

- current debug JVM, lint and APK assembly pass;
- `git diff --check` passes;
- no existing dirty file is reverted.

## Milestone 1 — immutable document core

Implement Android-free Java types:

- opaque `SessionId` and monotonic segment/revision identifiers;
- `VoiceDraft`, `VoiceSegment`, `SegmentRevision`;
- independent capture, recognition and delivery state enums;
- `VoiceDraftReducer` returning a new immutable state and explicit disposition;
- bounded text/segment/revision limits;
- deterministic rendering of ordered segment text.

Reducer events:

- session prepared/ready/stop/ended/failed/discarded;
- segment opened/soft-boundary/reopened/hard-boundary;
- live/provisional/refined/user revision;
- segment sealed;
- delivery composing/frozen/committed/recoverable;
- target detached and explicit discard.

Exit gate:

- exhaustive transition table tests;
- duplicate/out-of-order/late event property tests;
- blank revision never erases non-blank text;
- sealed/user-locked segments reject model changes;
- no Android dependency in the package.

## Milestone 2 — replay and provider capability contract

- Define `EngineCapabilities` and normalized engine events.
- Add a deterministic trace/replay format with bounded JSON fixtures.
- Adapt recorded system/Paraformer/local event traces without touching production routing.
- Add a provider adapter compatibility matrix and fail-closed defaults.
- Feed the same fixed trace to v1 diagnostics and v2 reducer for comparison.

Exit gate:

- malformed, oversized, duplicate and out-of-order provider events are rejected;
- missing stability/timestamps remain absent rather than inferred;
- actual route and provenance survive normalization;
- no network or microphone required for the gate.

## Milestone 3 — segment audio and durable journal

- Separate continuous capture from endpoint decisions.
- Implement soft and hard boundary events with bounded pre/post overlap.
- Add a session journal with encrypted segment records, atomic generation checks, TTL and quotas.
- Serialize writes off the IME main thread.
- Migrate only v2 sessions; do not reinterpret the existing v1 single-slot format.
- Add explicit discard tombstones and exactly-once acknowledgement.

Exit gate:

- crash/reopen recovers ordered segments;
- discard races cannot resurrect audio/text;
- disk-full/Keystore failure preserves the current in-process draft and reports reduced durability;
- DB/files/WAL/temporary files contain no seeded plaintext;
- journal corruption is bounded and does not block new safe sessions indefinitely.

## Milestone 4 — local model scheduler and two-pass reference path

- Prewarm the streaming model outside the hold gesture path.
- Keep one continuous capture while creating hard-bounded quality segments.
- Run quality refinement under a memory-aware scheduler.
- Permit concurrent workers only on profiles that pass the resource gate.
- Keep sequential and streaming-only policies as explicit local strategies, not silent provider
  fallbacks.
- Integrate punctuation and deterministic personalization with provenance.
- Keep punctuation in a private text-only process, include its conservative PSS in scheduling, and
  terminate that process at the end of every dictation lease.

Exit gate:

- one quality segment may complete after the next live segment without reordering text;
- a slow/failed quality pass retains the safe live segment;
- no model callback can target another session generation;
- cancellation/discard terminates or detaches native work without killing the IME;
- cold/warm and sequential/concurrent resource metrics are emitted separately.

## Milestone 5 — EditorProjection and IME integration

- Implement `EditorProjection` with a fake `InputConnection` test harness.
- Bind a projection to editor epoch, connection identity, package, field, selection and context
  fingerprints.
- Support short-dictation whole-draft composition and long-dictation sealed-prefix projection.
- Preserve/freeze on lifecycle loss; never treat it as discard.
- Keep selected-text commands on the existing fail-closed path until a separate command projection
  is accepted.
- Expose the actual production route and revision stream in Voice Lab without making a second
  editor writer.

Exit gate:

- success commits exactly once;
- cursor/app/field/window changes never cross-write;
- commit rejection leaves editor text or a recoverable draft;
- late refined segments cannot overwrite user-locked or new-session content;
- undo restores the complete logical voice insertion.

## Milestone 6 — Voice Lab, evaluation and default decision

- Add v2-specific timeline metrics: soft/hard boundary, first live revision, first earlier-text
  revision, provisional punctuation, quality final, projection and recovery.
- Add one-take replay against compatible v1/v2 routes.
- Run fixed public/synthetic corpora and physical microphone scenarios.
- Measure PSS by process, CPU, network, battery and thermal over cold/warm and sustained sessions.
- Complete the Xiaomi 15 matrix and attach redacted evidence.

Default switch criteria:

- zero integrity regressions;
- v2 meets every mandatory lifecycle/recovery gate;
- v2 materially improves at least one primary metric (hot ready, partial quality, punctuation timing,
  stop-to-final or entity accuracy) without a material regression elsewhere;
- release APK is signed/reproducible and exact commit/hash are recorded;
- rollback to v1 is tested.

## Work sequencing and conflict policy

- New core files are added first; existing dirty files are changed only at the integration milestone.
- Mechanical formatting is isolated from behavioural changes.
- No two Gradle invocations run concurrently against the shared build directory.
- Before every integration edit, re-read the current diff for that file.
- A failing unrelated baseline is diagnosed and recorded; it is not hidden by weakening tests.
- Generated model weights, credentials, transcripts and private device content never enter Git.

## Definition of final delivery

Final delivery includes:

- accepted ADR and up-to-date implementation/acceptance documents;
- source and tests for the v2 core and chosen production integrations;
- migration/rollback notes;
- complete automated test report;
- public/synthetic benchmark report with exact manifests and hashes;
- Xiaomi 15 redacted evidence and manual results;
- signed installable APK/AAB or a clearly labelled debug-only APK when signing authority is absent;
- SHA-256 checksums and exact Git commit/branch;
- honest remaining limitations and no claim of universal superiority without evidence.

## Implementation status — 2026-08-12 production cutover

The local-offline path now uses Speech Core v2 by default. V1 remains code-reachable only through
the explicit emergency rollback preference; missing streaming weights are reported as an incomplete
v2 model installation and never cause a silent fallback. This cutover applies to the engineering
APK. Store-release and Xiaomi 15 physical acceptance remain separate gates.

| Milestone | Status | Evidence / remaining boundary |
| --- | --- | --- |
| 0 — baseline / ADR | Complete | Baseline `2df68b3b39307835dc26c73d15b27877a172c0b0`; existing dirty work preserved; Debug/Release gates pass. |
| 1 — immutable document core | Complete | Android-free `VoiceDraft`, segment/revision model, orthogonal states, bounded reducer, 100-seed duplicate/out-of-order property test. |
| 2 — capability / replay | Complete | Fail-closed engine capability contract, bounded deterministic JSON, system/batch/Paraformer fixtures and replay reports. |
| 3 — segmentation / journal | Production-integrated | One continuous microphone capture feeds soft/hard boundaries and an AES-GCM multi-segment no-backup journal with generation handles, repair, tombstones, quotas and acknowledgement. Completed segment audio and visible revisions are recoverable; process death before the first durable revision/boundary remains a documented limit. |
| 4 — transforms / scheduling | Production-integrated | Streaming Paraformer is retained in private `:local_stream`; SenseVoice runs on demand in private `:local_quality`; pinned Chinese/English CT-Transformer punctuation runs in session-scoped private `:local_punctuation`. The punctuation candidate passes a lexical-integrity gate and its worker PSS participates in the memory/thermal policy; the process is terminated after every dictation. Provisional punctuation and deterministic personalization are revision-producing transforms. |
| 5 — EditorProjection / IME | Production-integrated | Ordinary local dictation projects into the host editor with target validation and readback, short whole-composition or long sealed-prefix/tail delivery, lifecycle freeze, recoverable failure and whole-session Unicode-safe undo. Voice Lab reports the actual route without becoming another writer. |
| 6 — evaluation / switch | Automated cutover gate complete; Xiaomi manual pending | 415 JVM tests, Debug/Release lint and three APK builds pass. With all three hash-verified model sets provisioned, the ordinary API 36 arm64 suite reports 41 pass plus the designed opt-in download skip; the download/hash/native E2E passes separately. Real streaming, quality and punctuation Binder processes plus deterministic punctuation-worker reclamation are covered. The exact streaming model still has no inherent earlier-word rewrite, so v2 obtains earlier revisions from bounded punctuation and segment quality rather than claiming otherwise. Xiaomi 15 remains a manual acceptance item for the final APK hash. |

The resulting deliverable is a real v2 local pipeline with an explicit rollback boundary, not a
renamed v1 path and not an unsupported claim that it already outperforms every mature keyboard. See
`2026-08-12-speech-core-v2-delivery-report.md` for current evidence, limitations and artifact hashes.
