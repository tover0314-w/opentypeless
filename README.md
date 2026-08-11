# OpenTypeless BYOK + Android Voice Studio

[中文说明](README_zh.md)

This independent MIT fork removes the account, subscription, checkout, quota, donation, and
managed-cloud product paths from OpenTypeless. It keeps the desktop BYOK application and adds
OpenTypeless Android 0.3: a local-first voice-input layer designed to be safer and more adaptable
than a mandatory-cloud “ASR + LLM” keyboard.

- Fork: [dengxuezhao/opentypeless](https://github.com/dengxuezhao/opentypeless)
- Upstream project: [tover0314-w/opentypeless](https://github.com/tover0314-w/opentypeless)

## Android 0.3 highlights

- **Three Android entry points:** an independent voice IME, a standard `RecognitionService`, and a
  `RecognizerIntent` activity. The service lets compatible full keyboards keep their letters,
  swipe input, emoji, and clipboard while using OpenTypeless for speech.
- **Local-first routing:** a newly installed app chooses Android on-device recognition when it is
  genuinely available, otherwise the installed system service, otherwise an explicit BYOK or
  self-hosted OpenAI-compatible endpoint. “System service” is labelled separately because its
  network behavior belongs to that provider and is not guaranteed offline.
- **Tested optional offline two-pass models:** non-low-RAM devices may explicitly download the
  pinned 228.45 MiB SenseVoice Small INT8 quality model and a separate 226.21 MiB Streaming
  Paraformer zh/en INT8 live-text model, plus a 72.02 MiB CT-Transformer zh/en INT8 punctuation
  model, into private no-backup storage. Every artifact is size/SHA-256 checked before atomic
  installation and again before first use; no weights are bundled into the APK. Speech Core v2
  requires both ASR artifacts, reports punctuation as an independently repairable capability, and
  never silently drops an upgraded install back to the older final-only implementation.
- **Live text in the editor:** Android partial hypotheses use replaceable IME composing text. Word
  and punctuation revisions replace the previous draft instead of being appended or shown only in
  a status label. Cursor movement created by OpenTypeless is distinguished from a user target
  change, and cancellation removes only the owned composition.
- **True realtime streaming:** the offline Streaming Paraformer consumes bounded 40 ms PCM through
  an anonymous pipe in the private ASR process; the optional DashScope route sends the same bounded
  frames over an allowlisted WSS endpoint. Both emit replaceable partials. The OpenAI-compatible
  completed-WAV route remains clearly labelled as batch; OpenTypeless never silently switches.
- **AI is optional:** Exact mode and structured fields do not require an LLM. Smart editing,
  selected-text editing, and translation run only after the user enables an OpenAI-compatible LLM.
- **Personal names that actually reach ASR:** confirmed canonical spellings, pronunciations,
  aliases, corrections, and app scope are injected as an ASR prompt where supported, as Android
  biasing strings where supported, and as a deterministic one-pass post-ASR correction.
- **Portable vocabulary:** Android imports its earlier personalization backups and desktop
  `opentypeless_dictionary` v1 files. Its export is a desktop-readable v1 superset while retaining
  Android aliases, app scopes, and enable flags for Android-to-Android round trips.
- **Explicit learning:** the keyboard never silently learns everything typed. A correction is saved
  only from the dictionary UI or after the user presses **Teach** and confirms the smallest
  `wrong → correct` pair.
- **Fact-safe, reversible editing:** numbers, amounts, dates, URLs, email addresses, code-shaped
  tokens, negations, and confirmed personal terms are checked after AI editing. Unsafe output is
  rejected in favor of the exact transcript. Every insertion has safe Undo; AI dictation also has
  one-tap Raw restore.
- **Target-bound IME commits:** every recording is bound to the exact editor epoch, app, field,
  `InputConnection`, selection, and text around the cursor. Switching apps, fields, selections,
  password fields, or cursor position never redirects an old result; safe unfinished text remains
  available as a recoverable draft.
- **Private local state:** API keys and opt-in history text use separate non-exportable Android
  Keystore AES-GCM keys. History is off by default, can be deleted entry-by-entry or all at once,
  is capped locally, and is never included in dictionary export.
- **Per-app behavior:** an explicit app profile can choose Auto, Exact, Smart, or Translate mode,
  a target language, a writing preference, and whether limited preceding context may be sent.
- **Voice UX:** tap Space for a space, or hold it to talk and release to finish. Use the separate
  Long action for continuous dictation. Speech Core v2 writes the streaming first pass into the
  host editor as replaceable composition, adds provisional punctuation at a soft pause, and can
  revise a closed segment after the isolated SenseVoice quality pass. OpenAI-compatible WAV remains
  clearly final-only. The authoritative final applies personal rules and accepts ITN punctuation
  only when no word or number changed.
- **Speech Core v2 is the local production route:** continuous capture, soft/hard segmentation,
  immutable `VoiceDraft` revisions, encrypted multi-segment recovery and target-bound
  `EditorProjection` now drive ordinary local-offline keyboard sessions. Streaming Paraformer stays
  warm in `:local_stream`; SenseVoice is loaded on demand in `:local_quality`; semantic punctuation
  runs in the text-only `:local_punctuation` worker. Voice Lab reports the actual route, revisions,
  and aggregate PSS of all three workers. V1 is retained only behind an explicit emergency rollback
  switch.

No model weights are bundled. See [Android third-party notices](android/THIRD_PARTY_NOTICES.md).
The first 189.85 MiB Zipformer was rejected. The next round tested SenseVoice and Paraformer on all
1,315 pinned ASCEND test utterances; SenseVoice reached 11.4% Mandarin CER, 25.9% English WER, and
13.3% mixed MER and passed a real API 36 arm64 download/native-decode smoke gate. The app now uses
a verified ASR-only two-ABI runtime: the clean universal debug APK is 52.54 MiB instead of the
upstream all-feature 120 MiB build. Its measured 457 MiB transient peak still prevents a general
default claim. Same-size Paraformer Large and Whisper Small Q5_1 were also screened and rejected as
bilingual defaults. When the user explicitly configures `zh-*` or `cmn-*`, the offline route now
uses SenseVoice's Mandarin lock; the fixed A/B reduced public Mandarin CER from 10.59% to 10.01%
and mixed MER from 20.37% to 18.31%. English remains auto-detected because forcing `en` regressed.
See
the [round-2 evaluation](docs/2026-08-09-offline-asr-candidate-round-2.md) and
[reproducible harness](benchmarks/offline_asr/README.md).

The exact 226.21 MiB streaming model used by Android has now also been run on the fixed 200-case
ASCEND/FLEURS public subset: Mandarin CER 12.5%, English WER 40.2%, mixed MER 22.9%, 95.5% partial
coverage, and first-partial audio position p50/p95 0.64/3.04 s. Across 1,682 changed hypotheses it
did not revise earlier visible text once. It therefore does not by itself provide Baidu-style
earlier-word correction. Speech Core v2 uses it as the low-latency first pass, then permits
provisional punctuation and per-segment SenseVoice revision without stopping continuous capture.
The independent CT-Transformer produces the punctuation candidate at pauses and after the quality
pass; a case-sensitive lexical/protected-literal gate rejects any candidate that changes words,
numbers, URLs, email, code-shaped text, or paragraphs. The models run in separate private processes;
memory/thermal policy may choose concurrent,
sequential or streaming-only execution. See the
[v2 architecture](docs/2026-08-11-speech-core-v2-architecture.md) and
[pinned streaming result](benchmarks/offline_asr/reports/2026-08-12-streaming-paraformer-summary.json).

## Processing policy

| Field or operation | Auto behavior | Generative AI |
| --- | --- | --- |
| Password / sensitive | Voice disabled | Never |
| URL, email, number, person name, search | Exact transcript + confirmed local rules | Skipped |
| Message, long text, general prose | Conservative Smart edit when enabled; exact fallback | Optional |
| Selected text | Explicit spoken edit with target revalidation | Required, original preserved on failure |
| Translate | Faithful translation to configured target | Required, no raw instruction inserted on failure |

`IME_FLAG_NO_PERSONALIZED_LEARNING` disables context collection, history writes, and usage learning.
Existing confirmed dictionary entries may still help recognition; they are not modified.

## Privacy and network boundaries

- Android on-device recognition is selected only through
  `SpeechRecognizer.createOnDeviceSpeechRecognizer`; availability depends on the device and
  installed language model.
- Android system recognition may be local or cloud-backed. OpenTypeless reports it as a distinct
  route and does not claim it is offline.
- The optional OpenTypeless offline route keeps recognition audio on the device. Only its model
  download uses the network; the fixed download carries no provider credentials.
- BYOK audio goes directly to the configured `/audio/transcriptions` endpoint. Optional Smart,
  Translate, or selected-text content goes directly to `/chat/completions`.
- Paraformer audio goes only to the validated official DashScope WSS inference host selected by
  the user. Its API key is independently encrypted by Android Keystore.
- HTTP redirects are rejected, provider error bodies are not shown, response sizes are bounded,
  and credentials/control characters are validated before request headers are written.
- HTTPS is required for public hosts. Plain HTTP is accepted only for an explicitly configured
  localhost, link-local, or private-LAN self-hosted endpoint. Bearer credentials require HTTPS
  except on a loopback address; use an empty key for cleartext LAN services.
- Password fields never start recording. Android backup and device transfer are disabled for app
  data. Settings, history, and management screens use `FLAG_SECURE`.

## Install and use Android

1. Install `android/app/build/outputs/apk/debug/app-debug.apk` or a properly signed release APK.
2. Open **OpenTypeless Voice Studio**, grant microphone access, and confirm the selected speech
   route. Android on-device is preferred only when the platform reports it available.
3. Optionally download the quality offline model, or configure batch BYOK STT, DashScope
   Paraformer realtime, and an LLM. AI, history,
   and preceding-context sharing start off.
4. Enable the OpenTypeless IME. To use either Android standard speech entry, first configure a
   ready BYOK or streaming STT endpoint, explicitly enable **Standard Android speech entry**, and add the exact
   caller package name to its allowlist. Then select OpenTypeless as the speech-recognition service
   or launch its `RecognizerIntent` activity from that allowed app. Some proprietary keyboards
   hard-code their own speech provider; use the independent IME or system keyboard switcher in that
   case.
5. Select Auto, Exact, Smart, or Translate, then tap **Speak** or hold Space and release to stop.
   Local live text is provisional composing text and may be revised in place by the final pass. For
   selected-text editing, select text before starting voice input; the same selection must still
   exist when the result returns.

Both exported standard speech entries intentionally use an explicitly configured BYOK/streaming
STT route only and are disabled
by default. Their package allowlist and request limiter prevent an arbitrary microphone-enabled app
from spending the user's provider quota. Calling the Android system recognizer from inside a
registered recognition service could resolve back to itself. The independent IME supports all
five recognition backends.

## Build and verify Android

Requirements: JDK 17, Android SDK Platform 35, and Build Tools 35.x.

```bash
cd android
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
python3 scripts/build_sherpa_asr_runtime.py --verify-aar app/libs/sherpa-onnx-asr-1.13.4.aar
./gradlew clean testDebugUnitTest lintRelease assembleDebug assembleRelease assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest  # with an API 35+ emulator/device online
```

The checked-in native runtime supports 64-bit ARM devices and x86_64 emulators. Rebuilding that
AAR from its pinned sources additionally requires Android NDK r27d; use
`scripts/build_sherpa_asr_runtime.py --help` for the audited build command and provenance inputs.

The automated suite covers transcript revisions, editor composition/cancellation races,
Paraformer protocol and transport events, deterministic personalization, NFKC span mapping,
prompt boundaries,
fact integrity, VAD, cancellation state, editor-target identity, HTTP redirects/errors/headers,
RecognitionService contracts, real SQLite import transactions, and Android Keystore history
encryption/migration. The opt-in large-model gate additionally covers a real revision-pinned model
download, exact hashes, native arm64 load/decode, and measured memory. CI runs JVM, lint, APK
assembly, and API 26/33/35/36 emulator tests without real API keys or a 229 MiB model download.
Speech Core v2 adds deterministic trace replay, segment permutation/property tests, continuous
boundary assembly, encrypted multi-segment journal recovery, quality-job generation isolation,
Unicode-safe editor projection, one-session undo, production-route diagnostics, and a tested
emergency rollback boundary.

The 0.3 review, automated evidence, physical-device procedure, and open gates are documented in
[the Android 0.3 review and acceptance report](docs/2026-08-09-android-0.3-review-acceptance.md).
The [0.2 acceptance report](docs/2026-08-09-byok-android-acceptance.md) remains the historical
baseline.

The release build produced by a local checkout is unsigned unless a signing configuration is
provided. Never distribute it as a trusted release without signing and publishing checksums. The
release workflow derives `OpenTypeless-Android-<version>` artifact names from Gradle metadata so an
older hard-coded Android version cannot be published accidentally.

## Desktop

Desktop keeps global dictation, selected-text operations, translation, Ask, dictionary, history,
scenes, and app-aware workflows, routed through local or user-configured providers. Commercial
account/runtime paths are not present in this fork. Build it with:

```bash
npm ci
npm run build
cargo test --manifest-path src-tauri/Cargo.toml
npm run tauri build
```

## Honest scope

Android 0.3 is the installable Voice Core milestone, not yet the promised full Rime keyboard. The
[Android IME 1.0 upgrade specification](docs/2026-08-09-android-ime-v1-upgrade-spec.md) defines the
Fcitx5 Android + Rime integration. The current Voice Core physical gate is the
[Xiaomi 15 P0 acceptance matrix](docs/2026-08-11-xiaomi15-p0-acceptance.md); this repository does not describe
that milestone as complete before its physical-device evidence exists. Android on-device recognition is not available on
every device or for every language, and the project does not yet publish a cross-device CER/WER,
latency, battery, or blind Typeless benchmark. The repository therefore claims verifiable product
advantages—offline-capable routing, provider freedom, explicit term learning, target-bound commits,
fact guards, and reversible AI—not universal recognition accuracy superiority.

## License

MIT licensed; see [LICENSE](LICENSE). This fork preserves upstream copyright and attribution, does
not use Typeless branding/assets/code, and is not the upstream hosted service.
