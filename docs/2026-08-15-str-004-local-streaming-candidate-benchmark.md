# Task Report: STR-004

## Result

DONE

## Scope

- Implemented: a reproducible, revision-pinned local streaming candidate benchmark that combines
  the existing 200-public-utterance accuracy screening with cold/warm Android latency and actual
  isolated-process PSS on Xiaomi 10 Ultra; selected the candidate to enter STR-005.
- Not implemented: production Provider/Router/VoiceController integration, microphone recording,
  model bundling or download UI, long-duration battery testing, or STR-005 integration.

## Changes

- `StreamingCandidateBenchmarkInstrumentedTest.java`: feeds the exact upstream public WAV in
  real-time 40 ms frames, runs one fresh-process and five warm sessions, and samples the live
  `:local_stream` process PSS while decoding.
- `benchmarks/streaming_asr/run_android_candidate.py`: installs exact APKs, validates and stages only
  revision-pinned public artifacts, runs one explicitly selected device, rejects model drift, and
  writes an atomic content-free report.
- `benchmarks/streaming_asr/test_run_android_candidate.py`: verifies report parsing, content export
  rejection, artifact hashing, pinned report loading, and atomic report writes.
- `docs/benchmarks/str-004-xiaomi-10-ultra.json`: final redacted Xiaomi measurement record.

## Architecture

- contracts: the candidate is
  `streaming-paraformer-bilingual-zh-en-int8-2023-08-14` at exact upstream revision
  `8e40c43232a1c5c66c82111efc5820d3accca11b`; all three model files and the public WAV are
  size/hash-pinned before execution.
- state changes: none in production routing. The optional model remains in app-private
  `no_backup/offline_models`; the benchmark app process is force-stopped on success and failure.
- migration: none.
- feature flag: none; STR-004 only selects a candidate for STR-005 and does not activate it.

## Candidate decision

The exact INT8 Streaming Paraformer candidate advances to STR-005 as a replaceable, non-authoritative
on-device first pass. It is not accepted as the final transcript authority.

Accuracy and device-performance evidence remain deliberately separate:

| Evidence layer | Platform/corpus | Result |
|---|---|---|
| accuracy screening | macOS arm64; 200 pinned public ASCEND/FLEURS utterances | Mandarin CER 0.1248; English WER 0.4018; mixed MER 0.2288; partial coverage 0.955 |
| streaming screening | same 200 public utterances | first-partial audio p50 0.64 s / p95 3.04 s; processing RTF p50 0.0425 / p95 0.0565; earlier-visible-text revisions 0 |
| Xiaomi fresh process | Android 13/API 33; exact upstream 10.053 s WAV | first partial 2.803 s; stop-to-final 106 ms; total 10.492 s; peak PSS 343,013 KiB |
| Xiaomi warm sessions | five runs of the same public WAV | first partial p50 1.327 s / p95 1.333 s; stop-to-final p50 102 ms / p95 266 ms; peak PSS max 334,024 KiB |

The prior bilingual Zipformer baseline stays rejected as the bundled default. The chosen candidate's
English WER and absence of earlier-visible-text revision remain explicit quality limitations.

## Security & privacy

- Data sent/stored: only Apache-2.0 upstream model bytes and the pinned upstream public test WAV are
  staged. The committed report contains metrics and hashes, not audio, transcript, user text,
  microphone data, raw ADB serial, or secrets.
- Permissions/components: no production permission or exported component was added; the benchmark
  runs only through Android instrumentation.
- Threat considerations: the runner refuses mismatched existing private models, uses exact-device
  ADB selection, atomically stages a previously absent model, removes its temporary ADB directory,
  never changes the default IME, and force-stops the app even if parsing/reporting fails.
- License/source: candidate provenance is the official
  [sherpa-onnx online Paraformer documentation](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-paraformer/paraformer-models.html)
  and [revision-pinned Hugging Face repository](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/tree/8e40c43232a1c5c66c82111efc5820d3accca11b),
  under Apache-2.0. No model or WAV bytes were added to Git.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `python3 -m unittest discover -s benchmarks/streaming_asr -p 'test_*.py' -v` | PASS | 5/5 |
| preliminary Xiaomi benchmark runner | FAIL | shell quoting dropped the remote `mkdir` argument; failed before inference and before replacing any model; runner fixed |
| `:app:compileDebugAndroidTestJavaWithJavac` and Debug/AndroidTest assembly | PASS | benchmark compiled and APKs assembled |
| fresh `GRADLE_USER_HOME` `scripts/verify_android.sh all` | PASS | `BUILD SUCCESSFUL in 2m 11s`; 189 tasks (185 executed, 4 up-to-date); 1035 XML tests; lint and five APKs |
| source architecture suite and production scan | PASS | 112/112; production source passed |
| compiled architecture gate | PASS | 110/110; Debug/Release production variants 2/2 |
| final Xiaomi exact-class benchmark | PASS | 1/1; one fresh + five warm sessions with final strict-built APKs |
| emulator candidate benchmark | NOT RUN | STR-004 requires physical-device latency/PSS; the 237 MB optional model was not duplicated onto the emulator |
| GitHub Actions for current worktree | NOT RUN | current changes are uncommitted and no run exists for HEAD |

## Evidence

- Redacted report: `docs/benchmarks/str-004-xiaomi-10-ultra.json`; SHA-256
  `21d6374c593ab8b7da126ca4a49854fb668898404e49681ba5cdadeccd9aa74c`.
- Xiaomi: M2007J1SC/cas, Android 13/API 33, HyperOS `V816.0.4.0.TJJCNXM`; final benchmark 1/1 PASS.
- App Debug: 56,414,377 bytes; SHA-256
  `e90d39cea6559745dddb279bb2b0518bbfb563f9a48adc0e0a078bb48fe2ea88`.
- App AndroidTest: 1,052,271 bytes; SHA-256
  `7368d3d48d7766b9b193458dd57ac236814bf5a98f89780f1bf0b0492a2f9377`.
- Release unsigned: 54,638,153 bytes; SHA-256
  `767ad3740ed2287e74862293db0b5ec50d34632d72c68737303de958c82cd772`.
- Model files total 237,202,501 bytes. The final run observed battery level 100 before/after
  and temperature 39.3 C to 39.5 C; this short-run delta is observational only.
- Device cleanup preserved the ten-minute screen-off/no-auto-lock policy, left the display Dozing
  without keyguard, and kept `com.flypy.input/PangIME.Android.InputService` as default IME.

## Risks

- English WER is materially weaker than Mandarin CER; STR-005 must keep the candidate replaceable
  and the final transcript authority separate.
- The device run uses one clean upstream WAV, not a phone-microphone corpus, accents, noise, or a
  sustained thermal/battery workload.
- The model produced no earlier-visible-text rewrite in the 200-case screening, so UI/correction
  logic must not assume that this candidate demonstrates revision behavior.

## Rollback

- Remove the STR-004 instrumentation/runner/tests/report and mark STR-004 TODO. The private optional
  model can be removed through the existing verified model-delete path; no schema or user data needs
  migration.

## Follow-ups

- STR-005

## Git

- branch: `agent/android-offline-followup`
- commit: none created
- worktree status: shared dirty/untracked task tree preserved; no commit, push, or PR performed
