# Android Voice Lab and real-device evaluation — 2026-08-11

## Decision

Build both a user-facing **Voice Lab** and a fixed engineering benchmark. They answer different
questions:

- Voice Lab: “Which mode works best on this phone, for my voice, without hiding fallback?”
- Engineering benchmark: “Did this build regress accuracy, latency, memory, power, or data
  integrity under the same inputs?”

The Voice Lab belongs in the companion app, not on the keyboard surface. The IME must stay small
and focused while recording.

## Route names must be explicit

`Automatic`, `Exact`, `Smart`, and `Translate` are text-processing modes. They are not ASR
providers. The screen must always show:

1. selected recognition backend;
2. actual backend used for this take;
3. every fallback and its reason;
4. whether audio stayed on device;
5. model/service name and version when available.

For example, a run may be displayed as:

> Selected: Android on-device · Actual: OpenTypeless offline SenseVoice · Reason: Xiaomi speech
> service denied microphone access

No result may be included in an A/B report if the actual route is unknown.

## User flow

```text
Voice Lab
┌────────────────────────────────────────────┐
│ Actual route: Android on-device            │
│ Privacy: on device · Model: system managed │
├────────────────────────────────────────────┤
│ Prompt: “没问题”                           │
│ [ Hold to record ]                         │
│ Live: 没问… → 没问题                       │
│ Final: 没问题                              │
├────────────────────────────────────────────┤
│ Ready 86 ms · first text 310 ms            │
│ stop-to-final 190 ms · empty 0/20          │
│ [Correct] [Needs correction]               │
└────────────────────────────────────────────┘
```

Offer two entry points:

- **Quick check**: 3 short prompts, about one minute. This diagnoses permission, microphone-ready,
  fallback, short-utterance, and first-result latency.
- **Full comparison**: 30–50 prompts, about 10–15 minutes. This compares system, local, and cloud
  routes and includes an extended three-to-five-minute thermal/battery run.

For a fair accuracy A/B, record one take and replay that exact WAV to each compatible backend.
Live-take comparisons are labelled interaction tests, not accuracy leaderboards.

## Measurements

### Accuracy and behavior

- Chinese CER, English WER, mixed MER;
- punctuation F1 separately from lexical error;
- explicit dictionary entity recall and negative-control false replacements;
- empty, truncated, repeated, and wrong-field output rates;
- partial revision rate and raw-to-final edit distance;
- stop, normal endpoint, error recovery, fallback, and explicit discard outcome.

### Latency

- gesture to true microphone ready;
- speech start to first visible partial;
- release/end to raw ASR final;
- deterministic processing and optional LLM processing time;
- final editor commit latency.

### Resources

- OpenTypeless IME/app PSS before, peak, and 30 seconds after release;
- recognition-provider PSS when debuggable, otherwise `unknown` rather than zero;
- process CPU time, thermal status, battery delta, network bytes, and estimated cost;
- cold and warm runs reported separately.

System providers run in a different process and may use DSP/NPU services. The OpenTypeless local
model now also runs in a private `:local_asr` process. Comparing only the app/IME PSS against either
provider is not a total-memory comparison. The report must show app and local-model PSS separately,
and mark unavailable system-provider values as unknown rather than zero.

## Privacy

- Raw audio recording is off by default.
- Transcripts and metrics are local-only by default.
- Export is a separate explicit action with a preview of every included field.
- Exports exclude API keys, editor context, package names, and personal dictionary data unless the
  user explicitly includes anonymized variants.
- Password and other sensitive fields cannot launch Voice Lab capture.

## Current product baseline

On Android 12+ devices, a fresh installation selects the privacy-preserving platform on-device
route without synchronously probing an OEM speech service on the IME/UI startup path. The settings
screen then diagnoses real availability in the background and requires the user to choose another
route if the device does not provide it. Older devices default to the platform speech route. The
processing-mode label `Automatic` does not identify either route. Voice Lab also resolves the OEM
system-service identity in the background; its route card never performs provider discovery on the
recording/UI hot path or folds that work into microphone-latency measurements.

The OpenTypeless offline quality tier is SenseVoice Small INT8, downloaded on demand rather than
bundled. The current fixed model is 228.45 MiB. The API 36 arm64 emulator gate observed about
469 MiB process high-water memory during cold load/decode and about 219 MiB after release. These
figures are not Xiaomi 15 measurements.

The full ASCEND test result was 11.4% Mandarin CER, 25.9% English WER, 13.3% mixed MER, and 13.9%
overall micro error. Host RTF p50/p95 was 0.020/0.041 with 554 MiB max RSS. Paraformer Small used a
78.11 MiB model and 262 MiB host max RSS and was faster, but its English and mixed accuracy were
worse. This supports keeping system recognition as the practical default and SenseVoice as an
explicit offline quality/privacy route until Xiaomi physical-device gates pass.

## Implementation stages

1. Instrument current recognition sessions with actual-route and monotonic timestamps.
2. Add the quick-check screen backed by the checked-in `benchmarks/mobile_voice/corpus.jsonl`
   subset; do not retain audio.
3. Add explicit, encrypted one-take replay for opt-in A/B, followed by immediate deletion.
4. Add debug-only PSS/CPU/thermal collection and a redacted JSON export.
5. Run Xiaomi 15 system/on-device, SenseVoice, and one streaming cloud route; establish release
   thresholds from real distributions rather than one successful take.

## Implemented P0 batch 1

The first implementation slice is now in the Android app:

- every `VoicePipeline` session records selected and actual backend, the explicit Xiaomi/system
  microphone fallback reason, privacy boundary, true microphone-ready, first partial, terminal
  latency, audio duration, result length, recovery status, and terminal state;
- the private diagnostics store contains no transcript, audio, editor context, package name,
  endpoint, API key, or personal vocabulary;
- Settings starts with a compact three-step enablement check, then exposes the latest actual route
  and a non-exported Voice Lab entry;
- Voice Lab reuses the production pipeline in Exact + hold-to-talk mode, waits for real microphone
  readiness before haptic feedback, includes three short prompts, reports CER or WER, and offers an
  explicit redacted JSON share action;
- the performance card samples app-process PSS, isolated local-ASR-process PSS, app CPU time,
  UID network bytes, and thermal status while explicitly marking Android system-provider resources
  as outside that boundary.

## Implemented P0 batch 2

- SenseVoice native loading and final decode now run in a non-exported `:local_asr` service rather
  than the IME/app process;
- WAV bytes cross the process boundary through an anonymous `ParcelFileDescriptor` pipe, not a
  Binder byte array or temporary audio file;
- the private service checks same-UID callers, enforces an 18 MB input and 20,000-code-point output
  bound, supports one active request, and releases native model memory after every turn;
- explicit cancellation terminates only the local-model process because the pinned native decoder
  has no cooperative cancellation primitive; the keyboard process and its visible draft survive;
- SenseVoice is now reported honestly as final-only. The former repeated-prefix pseudo-streaming
  decoder was removed from the production route because it loaded the large model in the IME and
  froze after a bounded prefix;
- API 36 instrumentation verifies that the service is non-exported and has a PID distinct from the
  app process. Voice Lab measures its PSS separately when Android exposes process memory data.

The quick check deliberately does not record or retain audio. Exact one-take backend replay, full
30–50 prompt sessions, encrypted opt-in audio, and Xiaomi 15 physical measurements remain later
stages rather than being simulated by this screen.

## Implemented P0 batch 3

- personal terms, aliases, pronunciations, and deterministic corrections are now encrypted at
  rest with Android Keystore; exact lookup uses keyed digests and Unicode search decrypts only a
  bounded local snapshot;
- the v3-to-v4 migration encrypts existing dictionary rows transactionally, sanitizes SQLite WAL
  residue, and was verified on API 36 by scanning the database, WAL, and SHM for seeded plaintext;
- finished OpenAI-compatible and local-offline captures are written to a bounded, AES-GCM,
  no-backup recovery journal before network/model processing; the journal atomically becomes final
  text before result delivery and is deleted only after an editor commit or encrypted draft save;
- explicit user/caller cancellation is linearized against that journal write: if discard wins while
  capture is returning, the same critical section removes the just-written checkpoint. Voice Lab
  test takes and standard Android recognition cancels therefore cannot strand private audio or
  block the next recognition session;
- recovery never writes into a stale editor. It opens as a regular recoverable draft and keeps the
  original encrypted backend/language/model routing metadata; a mismatched network endpoint is
  rejected rather than silently uploading elsewhere;
- settings no longer query OEM speech services while loading. Live service identity and
  availability are background diagnostics, and the large provider/key forms are constructed only
  after the user expands Advanced;
- the redacted API 36 arm64 preflight measured five force-stop cold settings launches at
  1,107 ms p50 / 1,373 ms p95, with 69,930 KiB foreground-process PSS and 155,096 KiB RSS. These
  are emulator/app-process figures, not Xiaomi 15 or total recognition-provider memory;
- upgrade testing installed the 0.2.0 base, seeded settings and plaintext dictionary data, upgraded
  in place to 0.3.0, verified values and encrypted migration, and confirmed Android rejected a
  downgrade while retaining 0.3.0 data.

The recovery guarantee has a precise boundary: once a batch/local capture has returned from the
microphone it survives process death during transcription or post-processing. Android can still
kill an IME process while PCM is actively being captured, before a complete recoverable WAV exists;
system and streaming providers also own audio outside this journal. Closing that rare mid-capture
window requires a chunk-authenticated PCM spool plus a recoverable background job, and must be
evaluated for I/O, battery, and microphone-dropout cost on Xiaomi 15 before it can become a default.

## Implemented P0 batch 4

- first-run completion now accepts only a non-empty successful test from the currently saved
  backend and language; an older successful diagnostic cannot make a newly changed route appear
  ready;
- Voice Lab's hold control exposes a stateful TalkBack click action in addition to the press/release
  gesture, reports start/finish semantics dynamically, retains a 48 dp target, and treats pointer
  cancellation as a normal finish rather than a silent discard;
- performance sampling uses fixed-delay scheduling so a slow memory/thermal sample cannot queue a
  burst of catch-up work on a constrained phone;
- the release workflow reads Android `versionName` and the signed APK name from Gradle metadata,
  validates both, and publishes matching APK/AAB/checksum artifacts instead of a hard-coded older
  version;
- the current local gate passes 253 JVM tests, 27 API 36 instrumentation tests with one explicit
  real-model skip, Debug/Release lint with no issues, APK assembly, and AAB bundling.

## Initial release gates

- each short prompt succeeds at least 19/20 times on Xiaomi 15 for every shipping route;
- silence and isolated tap/cough controls remain rejected;
- first partial p50 under 350 ms and p95 under 700 ms for routes advertised as live;
- no visible draft disappears on normal stop, endpoint, error, app switch, or keyboard hide;
- no duplicate or cross-field commit in lifecycle tests;
- any LLM timeout or guard failure falls back to raw ASR text;
- after an explicit personal dictionary is supplied, entity recall target exceeds 95% while
  negative-control false replacement remains zero.

Public ASCEND/FLEURS results remain candidate evidence. The final release authority is a consented,
unseen, multi-speaker phone-microphone set with quiet, noise, distance, accent, short utterance,
pause, entity, and code-switch strata.
