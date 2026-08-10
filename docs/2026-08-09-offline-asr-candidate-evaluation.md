# Offline ASR candidate evaluation — 2026-08-09

## Decision

Do not bundle `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20` as the
default OpenTypeless Android recognizer.

The candidate is fast enough on an Apple ARM desktop and its Mandarin result is useful as an
engineering baseline, but English, natural code switching, named entities, partial-result coverage,
and observed memory are not strong enough for a bilingual input method. The current Android system
on-device recognizer and explicit BYOK routes remain the honest product paths while newer offline
candidates are compared.

This is a candidate-rejection result, not a claim that every Zipformer, sherpa-onnx, or offline ASR
model has the same quality.

## Exact candidate and setup

- Model archive: `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2`
- Archive SHA-256: `27ffbd9ee24ad186d99acc2f6354d7992b27bcab490812510665fa8f9389c5f8`
- License: Apache-2.0
- Runtime: sherpa-onnx `1.13.4`, CPU, four threads, 100 ms chunks
- Decoder configuration: INT8 encoder and joiner, FP32 decoder, `cjkchar+bpe`
- Selected model/tokenizer files: 199,068,769 bytes (189.85 MiB)
- Host: macOS 14.8.2, arm64

The upstream card reports good Chinese results on AISHELL-1 and WenetSpeech, but does not publish a
corresponding English or natural code-switch test result for this exported 2023 candidate. The local
evaluation therefore treats the public and blind-product sets as authoritative for product
selection.

## Reproducible synthetic smoke result

The 48 checked-in prompts were rendered with two macOS voices. Marked probes and every hotword
control received deterministic 15 dB SNR pink-noise pairs, producing 142 audio cases. All active
dictionary phrases were applied globally to positives and controls; no sample received an oracle
per-row correction.

| Mode | Mandarin CER | English WER | Mixed MER | Entity recall | False hotword / correction insertions |
| --- | ---: | ---: | ---: | ---: | ---: |
| Beam baseline | 4.1% | 33.3% | 42.7% | 11/60 (18.3%) | 0 / 0 |
| Hotword score 1.5 | 3.8% | 31.4% | 39.4% | 21/60 (35.0%) | 0 / 0 |
| Hotword score 3.0 | 4.1% | 32.0% | 45.1% | 25/60 (41.7%) | 0 / 0 |

Score 1.5 improves recall without the larger mixed-speech regression at score 3, but 35% entity
recall is far below input-method quality. The zero false-insertion result covers only 32 deliberately
similar clean/noisy controls and must not be extrapolated to unrestricted dictation. Synthetic TTS
is a deterministic regression test, not evidence of field accuracy.

## Pinned public real-speech result

The public subset contains 200 unique cases and 1,259.79 seconds of audio:

- 100 [ASCEND](https://huggingface.co/datasets/CAiRE/ASCEND) test examples selected after scanning
  all 1,315 pinned metadata rows, balanced across
  the two test-speaker IDs and `zh`/`en`/`mixed` labels (34/33/33);
- the first 50 development-audio members encountered in each pinned official
  [FLEURS](https://huggingface.co/datasets/google/fleurs) archive for Mandarin and US English,
  streamed with original signal level preserved.

No public sample received hotwords or corrections.
These deterministic subsets are candidate-screening fixtures, not random or population-unbiased
estimates of either complete corpus.

| Dataset / cohort | Cases | Metric | Modified-beam result | Partial results |
| --- | ---: | --- | ---: | ---: |
| ASCEND Mandarin | 34 | CER | 10.8% | included in ASCEND aggregate |
| ASCEND English | 33 | WER | 40.8% | included in ASCEND aggregate |
| ASCEND mixed | 33 | MER | 24.5% | 32/33 |
| FLEURS Mandarin | 50 | CER | 12.8% | 50/50 |
| FLEURS English | 50 | WER | 62.6% | 28/50 |

As a post-run diagnostic, the English FLEURS subset contains 26 upstream recordings below RMS
0.002. Source float32 and
converted PCM16 levels match, so this was not a conversion bug. Those cases reached 96.1% WER and
only 4/26 produced a partial result. This is still a product-relevant robustness failure: the
recognizer was given the official unnormalized signal and the input pipeline currently has no
validated gain-normalization stage. More importantly, even after excluding all sub-0.002-RMS
English cases, English WER remained 32.1%; low volume does not explain away the candidate's English
weakness.

Across all public cases, modified beam produced 82.5% partial-result coverage. Full streaming
processing RTF was 0.084 p50 and 0.115 p95 on the host. `/usr/bin/time -l` observed a maximum
resident set of 495,861,760 bytes (about 473 MiB). These are desktop screening figures, not Android
device results.

Reproducibility identifiers:

- public manifest SHA-256: `7c7acf0c11057071c7e6ff4f5e46f641abc982b23dee8c8e5f7a91e98b98bad4`
- public audio-set SHA-256: `9e956237c16c74c263d66bcc930058daeda7c34810acf5de87612269a49ff07c`
- synthetic manifest SHA-256: `28f3d9e6ef199c8fd3b6cdfa86c172da1ed0f6af5110dd0459ae9d80fc35efed`
- runner SHA-256: `918fd012b4c83468b642bcb53f5076cb5ec1deb854fa0703ed8734f3840b73d8`
- metrics SHA-256: `251fe56062ddcab15d3252c16429738247c70d36143a126190fef094dde606bf`

The compact machine-readable aggregate is
[`benchmarks/offline_asr/reports/2026-08-09-zipformer-summary.json`](../benchmarks/offline_asr/reports/2026-08-09-zipformer-summary.json).

## Mature corpus plan

No one corpus represents a mobile input method. The durable suite is:

1. PR screening: pinned ASCEND and FLEURS subsets plus the synthetic entity/control corpus.
2. Nightly: complete ASCEND and FLEURS test splits,
   [LibriSpeech test-clean/test-other](https://www.openslr.org/12/), and an
   [AISHELL-1](https://www.openslr.org/33/) test extraction.
3. Release: [THCHS-30](https://www.openslr.org/18/) noisy Mandarin, accented English such as
   [EdAcc](https://datashare.ed.ac.uk/items/355c07b4-500d-4e80-8f12-225e646293c9), deterministic
   [RIR/noise](https://www.openslr.org/28/) mixes, and a consented 500–1,000-utterance mobile blind
   set that no selected model trained on. WenetSpeech is optional only after its application and
   redistribution/non-commercial terms are reviewed.
4. Android gate: representative low/mid/high devices measuring cold start, peak memory, sustained
   RTF, first partial, stop-to-final latency, battery, thermal throttling, and IME edit correctness.

The open-source [Picovoice speech-to-text benchmark](https://github.com/Picovoice/speech-to-text-benchmark)
is a useful reference for adapters and normalization across several English public sets, but this
project keeps its own Mandarin/MER, personalization, streaming, and Android-specific gates.

Public-set training overlap cannot be excluded because the candidate's complete training inventory
is not published. Public scores are therefore regression and screening evidence; the unseen mobile
blind set remains the release authority.

## Next candidate gate

Before any offline model is bundled or offered as the recommended download, it must improve all of
the following on untouched evaluation data, with no hidden cloud fallback:

- Mandarin CER, English WER, and mixed MER reported separately;
- personal-entity recall and false insertions with a fixed dictionary plus unused distractors;
- clean, low-level, noisy, accented, long-pause, and multiple-device strata;
- Android peak memory and latency within a defined supported-device floor;
- model/license provenance, downloadable size, update strategy, and opt-in storage behavior.

The next comparison should include at least one newer streaming bilingual model and one stronger
multilingual offline model. The current candidate remains only a fast baseline.
