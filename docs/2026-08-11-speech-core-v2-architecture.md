# Speech Core v2 architecture decision

Date: 2026-08-11
Status: Implemented; engineering default since 2026-08-12
Scope: Android local-offline dictation core; the existing Voice Core remains an explicit emergency fallback

## Decision summary

Speech Core v2 is a **Voice Draft document engine**, not a collection of provider-specific partial
strings and not a model-specific two-pass wrapper. Streaming ASR, quality ASR, punctuation,
personalization and optional AI produce ordered revisions of a session-local document. A separate
delivery layer projects that document into the original Android editor or into encrypted recovery.

The reference local strategy is segment-level two-pass recognition:

1. one continuous microphone capture feeds a warm streaming recognizer;
2. a soft pause may produce provisional punctuation but never seals text;
3. a hard audio boundary closes one segment without stopping capture;
4. a quality recognizer refines the closed segment while the next segment can remain live;
5. deterministic personalization and punctuation produce a refined segment;
6. only the delivery layer decides whether text is composing, committed, frozen or recoverable.

This combines compatible principles, not every feature of every vendor:

- Google-like monotonic session/result identifiers and explicit provisional/final provider events;
- FunASR-like online first pass plus sentence/segment quality pass;
- Gboard-like inline editor projection and explicit separation of dictation from proofreading;
- optional AWS-like token stability only when an engine supplies it natively.

Speech Core v2 does **not** fabricate token stability for models that lack timestamps or stability,
does not run an LLM on every partial, and does not equate a model final with an editor commit.

## Why the earlier design was rejected

The earlier proposal placed recognition and editor states in one chain:

```text
OPEN -> STREAM_FINAL -> QUALITY_FINAL -> COMMITTED
```

That chain is invalid. A quality result may arrive after the target editor changed, an
`InputConnection` may reject the mutation, or Android may destroy the IME. Recognition finality and
delivery success are independent.

It also treated a natural pause as a sentence boundary. Pauses can be hesitation, thinking time,
disfluency or an accessibility need. A pause can trigger provisional work, but only a hard boundary
may close audio and only a refined result may seal a segment.

Finally, provider semantics conflict. AWS-style stable tokens promise that a token will not change;
a second-pass recognizer exists specifically to change first-pass text. Token stability therefore
remains an optional engine capability rather than a product-wide guarantee.

## Product and safety invariants

1. Only an explicit, confirmed discard may delete a visible or durably captured voice draft.
2. A stale session, segment or revision can never mutate the active editor.
3. Recognition state never implies delivery success.
4. Selected-text operations remain fail-closed: spoken instructions never enter the selected body
   before the requested transform passes its integrity gate.
5. User-authored edits are authoritative and cannot be overwritten by a late model result.
6. Duplicate, out-of-order and late events are idempotently ignored.
7. A provider or quality failure preserves the best safe revision or an encrypted recovery record.
8. Route, model, fallback, processing location and transformation provenance remain inspectable.
9. Password and sensitive fields never start dictation or retain context/history.
10. No model, punctuation layer, rule or LLM may silently change numbers, URLs, email, code or
    protected entities without evidence and a reversible edit record.

## Orthogonal state machines

### Capture session

```text
IDLE -> PREPARING -> LISTENING -> STOPPING -> ENDED
                    |              |
                    +-> FAILED <---+
                    +-> DISCARDED
```

`DISCARDED` is entered only by an explicit user/caller action. Lifecycle loss requests a safe stop,
freeze or recovery transition; it is not discard.

### Recognition segment

```text
OPEN -> SOFT_BOUNDARY -> OPEN
  |          |
  |          +-> HARD_BOUNDARY -> REFINING -> SEALED
  +------------------------------> REFINING -> SEALED
```

- `SOFT_BOUNDARY` may add or revise provisional punctuation and can reopen.
- `HARD_BOUNDARY` closes an audio segment but does not stop the microphone session.
- `REFINING` permits a quality model to replace the full segment.
- `SEALED` is the final model-owned text for that segment. User edits can subsequently create a
  locked user revision but no model may revise it.

### Editor delivery

```text
NOT_PROJECTED -> COMPOSING -> COMMITTED
                     |          |
                     +-> FROZEN +-> RECOVERABLE
                     +----------------> RECOVERABLE
```

- `COMPOSING` is replaceable text owned by the voice session.
- `FROZEN` is safely detached text in the old editor.
- `COMMITTED` means the target editor acknowledged the exact revision.
- `RECOVERABLE` is encrypted text/audio that must be explicitly inserted or discarded.

## Core event model

The pure Java core is immutable and Android-free. Identifiers are opaque and revisions are
monotonic within a segment.

```text
VoiceDraftSession
  sessionId
  captureState
  ordered segments
  activeSegmentId
  terminalReason

SegmentRevision
  sessionId
  segmentId
  revisionId
  stage: LIVE | PROVISIONAL | REFINED | USER_LOCKED
  fullText
  optional token metadata
  audioStartMs / audioEndMs when known
  origin: STREAM_ASR | QUALITY_ASR | PUNCTUATION | PERSONALIZATION | USER
  providerFinal
```

The canonical reducer accepts complete segment text. It does not merge arbitrary token sequences
from models with different tokenizers. Token timestamps, confidences and stability are optional
metadata used only when an engine provides them consistently.

`providerFinal` means only that one provider will not revise its result. It does not mean refined,
sealed, committed or delivered.

## Reducer rules

- Session ID, segment ID and revision ID must match the active document generation.
- A lower/equal revision is ignored without changing observable state.
- Blank non-terminal revisions do not erase non-blank text.
- A provisional punctuation revision may be replaced or removed when the segment reopens.
- A quality revision replaces the complete matching segment, never a neighbouring segment.
- A sealed or user-locked segment rejects later model revisions.
- A segment can be sealed only after a hard boundary and a successful refinement/fallback decision.
- Segment order is stable even when quality results complete out of order.
- Rendering is derived from reducer state; UI callbacks never become the source of truth.

## Audio and segmentation

Audio capture is continuous for long dictation. Endpointing is a signal generator, not an implicit
stop command.

- Frames are written to a bounded in-memory ring and a chunk-authenticated, encrypted session
  journal when durability is required.
- A soft endpoint creates a provisional boundary. It may trigger punctuation but retains audio and
  text in the same open segment.
- A hard endpoint closes a segment with bounded pre/post overlap and starts the next streaming
  segment without releasing `AudioRecord`.
- Hold-to-talk release is a hard endpoint and session stop.
- Long-text pauses close segments but do not stop capture; the explicit Finish action stops it.
- Boundary thresholds are policy inputs measured in Voice Lab, not hard-coded product truths.

## Engine contract

Each engine declares capabilities rather than forcing all providers into one behaviour:

```text
liveRevisions
segmentFinals
tokenTimestamps
tokenStability
confidence
contextBias
hotwords
automaticPunctuation
inverseTextNormalization
onDevice
```

Adapters normalize native callbacks into `SegmentRevision` events. Missing capabilities remain
absent. The core never infers that a provider is offline, stable or punctuated from its name.

The current Streaming Paraformer is an initial adapter. Because it lacks native timestamps and
stability, its active segment remains wholly revisable. Model selection remains an evaluation gate;
the architecture is not coupled to Paraformer, SenseVoice or one cloud provider.

## Personalization

Personalization has four deliberately separate stages:

1. **Decoder context**: weighted terms/hotwords when the selected engine supports biasing.
2. **Canonical display mapping**: exact, idempotent user-confirmed aliases and pronunciations.
3. **Reviewed correction**: explicit raw-to-final correction rules with global/app scope.
4. **User edit lock**: manual edits become authoritative document revisions.

Live preview may apply only bounded, deterministic, idempotent mappings. Full correction evidence is
applied to a refined segment. Automatic fuzzy replacement and unreviewed learning remain rejected.

## Punctuation, ITN and LLM

- Punctuation is a text transformation with its own provenance, not proof of ASR confidence.
- The production local route uses a pinned Chinese/English CT-Transformer punctuation model. Its
  candidate is accepted only when a lexical-content guard proves that words, letter case, numbers,
  URLs, email, code and paragraph structure are unchanged.
- Soft-boundary punctuation is provisional and may be removed when speech resumes.
- Refined punctuation/ITN is sealed with the segment and evaluated separately from lexical error.
- Locale-sensitive ITN remains opt-in until its date/number formatting passes dedicated tests.
- LLM processing is outside the live revision path. Exact dictation never requires it. Smart,
  translation and selected-text operations run only on refined/sealed input with integrity guards.

## Editor projection

`EditorProjection` is the sole Android layer allowed to call `InputConnection`.

- Short dictation keeps the current draft composing until release.
- Long dictation may commit an ordered sealed prefix while retaining only the open/refining tail as
  composition; a session undo ledger preserves one logical undo action.
- A manual cursor/selection change freezes the owned projection and detaches the recognition target.
- A late result updates the Voice Draft and recovery journal but never a new editor.
- Editors that reject composing receive a bounded compatibility projection plus a recoverable copy.
- Selected-text commands use a separate command projection and never share ordinary dictation
  insertion semantics.

Typing while the microphone remains active is not a v2 launch guarantee. The core can represent
`USER_LOCKED` spans, but arbitrary-host edit reconciliation must pass its own acceptance gate before
the keyboard advertises Gboard-like concurrent editing.

## Process and memory strategy

One logical local speech service fronts a capability-aware scheduler. Execution can use:

- a warm streaming worker/process while the keyboard is visible;
- an on-demand quality worker/process for closed segments;
- a text-only, session-scoped punctuation worker prewarmed in parallel with capture and terminated
  after the dictation lease;
- a single-process sequential strategy on constrained devices;
- a fast single-pass strategy when memory/thermal pressure prevents quality refinement.

The user-visible integrity contract does not change when quality timing degrades. The scheduler may
delay refinement or retain a safe streaming result, but it cannot discard the draft or silently
switch to a network provider.

The default cap remains two simultaneously resident ASR models. A third, text-only punctuation
worker is permitted only while one high-headroom dictation session owns it, and its measured PSS is
included in the concurrent resource decision. Sequential mode unloads streaming before quality;
low-memory or severe-thermal profiles omit the native punctuation pass and retain the safe ASR
revision. The punctuation worker is killed after each session because native allocators may retain
arenas even after model close. All model workers remain non-exported and same-UID guarded.

## Durable recovery

The existing single-slot utterance journal is retained for Voice Core v1 only. V2 introduces one
encrypted, bounded, append-only session journal containing:

- session and segment IDs;
- chunk-authenticated PCM/WAV segment data where applicable;
- latest accepted revision and stage;
- route/model metadata required for explicit recovery;
- discard tombstones and acknowledgement state;
- expiry and size bounds.

Journal writes are serialized outside the IME main thread. A late callback can update only its own
session/segment generation. Explicit discard is linearized against capture and persistence.

## Components and ownership

```text
CaptureController        owns AudioRecord and explicit stop/discard
EndpointDetector         emits soft/hard boundary signals
StreamingEngine          emits live/provider-final revisions
QualityEngine            refines closed audio segments
TransformPipeline        punctuation, ITN, deterministic personalization
VoiceDraftReducer        authoritative pure state
VoiceDraftJournal        encrypted durable state
EditorProjection         only InputConnection mutator
SpeechCoreCoordinator    orchestration and cancellation tokens
DiagnosticsRecorder      redacted timing/resource/provenance events
```

No generic connector, LLM client or third-party plugin executes inside the IME or reducer process.

## Migration

V2 is built as distinct core packages and is now adapted by `VoicePipeline` as the default
local-offline route. The internal feature gate is rollback-only; model absence does not silently
select v1. The following list records the migration sequence rather than unfinished work:

1. Land the pure event model/reducer and model-based tests.
2. Add replay adapters so fixed WAV/event traces can exercise v1 and v2 without microphone races.
3. Add the session journal and segmenter.
4. Add local streaming and quality adapters behind Voice Lab.
5. Add editor projection and lifecycle tests.
6. Run public corpus, emulator and Xiaomi 15 gates.
7. Switch the default only after v2 is non-inferior on integrity and materially improves latency,
   revision, punctuation or quality metrics.
8. Remove v1 only after one release cycle with a documented rollback path.

## Consequences

The design adds a reducer, journal and orchestration boundary, but removes provider-specific state
from the editor layer and avoids a growing conditional monolith. It deliberately postpones
concurrent manual editing and token-level stability. It increases the amount of deterministic test
code before model integration, which is accepted because data loss and cross-field writes are
release-blocking failures for an input method.
