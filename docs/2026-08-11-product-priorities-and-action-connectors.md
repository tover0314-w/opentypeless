# Product priorities and programmable voice actions — 2026-08-11

## Product thesis

OpenTypeless should not try to win by training a general-purpose ASR foundation model or by
rebuilding a full Pinyin keyboard. Its durable value is a **user-owned, programmable voice layer**
that works across apps:

- use the best available system, local, or user-chosen cloud recognizer;
- retain explicit personal vocabulary, aliases, corrections, and per-app behavior;
- preserve raw text and make every transformation reversible;
- route a voice result either into the current editor or into a user-owned destination.

If the only goal were generic speech-to-text quality, maintaining a standalone IME would not be
worth it: mature keyboards and Android system recognition already solve that more cheaply. The IME
is justified by cross-app personalization, transparent privacy, BYOK/offline control, and
programmable capture workflows.

## Chosen design language

The product language is **calm, native, and state-led**:

- Material-style semantic color, typography, elevation, and 48 dp touch targets;
- Typeless-like hierarchy with one obvious primary voice action, not its large empty layout;
- a stable two-row keyboard with progressive disclosure instead of a grid of every feature;
- color communicates idle, preparing, listening, processing, recoverable, and error states;
- settings use plain-language summaries and current status; advanced provider fields are collapsed;
- motion is short and functional. Glass, decorative gradients, and continuous animation are not
  defaults on the keyboard surface.

The token system, layout hierarchy, accessibility, dark/light behavior, and settings information
architecture are P0. Brand illustration, richer waveform motion, and decorative expression are not.

## P0 — required before calling the Android product stable

### 1. Text and lifecycle integrity

- normal stop, release, endpoint, backend error, app switch, keyboard hide, and late callback never
  delete visible speech;
- only an explicit discard action may remove a draft;
- partial-to-final replacement is exactly once and remains bound to the original editor target;
- final text may inherit punctuation from a partial only when lexical content is identical;
- queued results, recovery, and process teardown cannot duplicate or cross-write text.

### 2. Short and long dictation interaction

- tap space inserts a space; hold starts short dictation; true microphone readiness precedes
  haptic/listening feedback; release finalizes and commits;
- a separate long-text action starts and explicitly ends continuous capture;
- cancellation is contextual and hidden behind an explicit action, not a permanent primary key;
- 320 dp, large font, dark/light, gesture/three-button navigation, and TalkBack gates pass.

### 3. Coherent UI and settings

- one tokenized component system across IME, onboarding, settings, model management, vocabulary,
  history, profiles, and diagnostics;
- first-run path covers enabling the IME, microphone permission, backend choice, privacy, and a
  successful test phrase;
- settings lead with “what is active now” and “where audio goes”; raw endpoint/key fields live under
  Advanced;
- selected processing mode is visually distinct from recognition backend.

### 4. Route truth and Voice Lab

- every session records selected backend, actual backend, fallback reason, model/service identity,
  and on-device/network boundary;
- Quick Check measures microphone-ready, first partial, stop-to-final, empty result, and one short
  Chinese/English phrase without retaining audio;
- redacted diagnostics can be exported without keys, editor context, or personal vocabulary.

### 5. Personal vocabulary MVP

- explicit terms, pronunciations/aliases, deterministic corrections, per-app profiles, import,
  export, search, and usage evidence are reliable and locally encrypted;
- user can see which rule affected a result and undo or disable it;
- no silent automatic learning. Learning from edits remains opt-in and reviewable.

### 6. Privacy and recovery

- credentials never ship in the APK or logs; network destinations and cleartext policy are explicit;
- password/sensitive fields disable recording, history, context, and remote actions;
- failed commits and detached finals remain recoverable without retaining `InputConnection`;
- local drafts, history, and future action outbox use bounded encrypted storage and deletion controls.

### 7. Physical-device performance and release gate

- Xiaomi 15 system/on-device and SenseVoice routes pass the fixed short, normal, long, lifecycle,
  noisy, and dictionary matrix;
- cold/warm latency, peak/steady PSS, CPU, thermal, and battery are reported with process boundaries;
- local ASR cannot take down the IME process under memory pressure. A stable offline tier therefore
  needs a separate model process or an equivalently tested isolation boundary;
- signed upgrade, settings/model migration, and rollback are tested.

## P1 — the differentiating layer after P0 reliability

- a configurable **voice action slot** on the toolbar, with “Append to SiYuan Daily Note” as the
  first connector;
- encrypted offline action outbox, retry, result receipt, and undo when the destination supports it;
- complete Voice Lab A/B using one recorded take replayed across compatible routes;
- a lightweight native-streaming local model for partials, with SenseVoice as an optional final
  second pass;
- reviewed learning suggestions derived from raw ASR, final result, and explicit user correction;
- optional user-controlled synchronization of vocabulary and rules.

## Current implementation status

The code now covers the P0 software boundary for normal lifecycle operation: short/long gestures,
true-ready feedback, safe partial-to-final replacement, punctuation retention, target binding,
explicit-only discard, encrypted draft/audio recovery, encrypted vocabulary migration, route truth,
Voice Lab, isolated local ASR, unified UI tokens, first-run checklist, progressive settings, and
signed in-place upgrade verification. API 36 automated gates cover these paths.

Two release-authority items remain intentionally open rather than being inferred from an emulator:

1. the full Xiaomi 15 microphone, accuracy, latency, PSS, thermal, battery, navigation-mode,
   large-font, and TalkBack matrix;
2. a decision on chunk-authenticated PCM spooling if the product promise must include Android
   killing the IME in the middle of an active recording. The current encrypted journal guarantees
   completed batch/local captures, not audio that the OS never returned to the process.

Those are distinct from the SiYuan action slot and richer connectors, which remain P1 product
differentiation and must not delay correctness of ordinary dictation.

## Long-term evolution

- connector SDK for webhooks, SiYuan, task managers, calendars, and user-hosted agents;
- small local text model that emits bounded edit operations, not unrestricted rewritten prose;
- hardware-specific GPU/NPU acceleration and memory-aware model tiers;
- multilingual downloadable ASR packs and domain-specific lexicons;
- long-form segmentation, speaker/noise adaptation, and semantic endpointing;
- aggregate, consented, privacy-preserving quality telemetry and a larger unseen phone-microphone
  evaluation set;
- richer themes, waveform motion, and brand expression after accessibility and performance remain
  within budget.

## Generic voice-action architecture

SiYuan must be a connector, not a dependency of the IME core:

```text
gesture -> ASR -> safe text result -> VoiceActionDispatcher
                                      ├─ InsertAtCursor
                                      ├─ AppendToDailyNote (connector)
                                      ├─ CopyToClipboard (connector)
                                      └─ SignedWebhook (connector)

AppendToDailyNote -> encrypted outbox -> scoped HTTPS relay -> SiYuan adapter -> Docker SiYuan
```

The keyboard exposes one configurable action slot. When configured for SiYuan it says “记到日记”
and changes the recording destination before capture; it is not a permanent SiYuan-branded key and
does not silently send ordinary dictation elsewhere.

Each action carries an opaque UUID, final text, creation time, language, format, and connector ID.
Package name, surrounding editor context, raw audio, raw ASR, and personal dictionary are excluded
by default. The UUID is an idempotency key so network retries cannot append the same note twice.

## SiYuan connector boundary

The recommended deployment is a very small relay beside the user's Docker SiYuan instance:

1. Android authenticates with a revocable, device-scoped token that is allowed only to append a
   daily-note entry.
2. The relay owns the broad SiYuan API token and never returns it to Android.
3. It calls SiYuan's daily-note creation route for the configured notebook, then appends one
   Markdown block to the returned document ID.
4. It stores the action UUID and returned block ID, making retries idempotent and enabling undo.
5. HTTPS, a narrow allowlist, rate limits, bounded body size, audit timestamps, and token rotation
   are mandatory.

SiYuan's official API uses JSON POST requests and `Authorization: Token ...`; the current official
kernel exposes `/api/filetree/createDailyNote` and `/api/block/appendBlock`. Giving that broad API
token directly to a keyboard APK would create unnecessary coupling and compromise impact. The
relay reduces the mobile contract to one product-owned operation and lets the SiYuan adapter change
without releasing a new keyboard.

Offline behavior is explicit: the encrypted outbox shows a pending count and keeps the final text
until confirmed, retried, copied, or discarded. A network failure must never report success or
erase the only copy.

## Non-goals

- training a proprietary base ASR model;
- replacing a complete touch keyboard;
- hard-coding third-party service APIs or administrator tokens into the IME;
- sending ordinary dictation to cloud destinations by default;
- automatic unreviewed vocabulary learning;
- allowing generic plugins to run inside the latency- and privacy-sensitive IME process.
