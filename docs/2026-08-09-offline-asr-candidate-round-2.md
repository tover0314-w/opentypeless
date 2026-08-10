# Offline ASR candidate round 2 — 2026-08-09

## Decision

SenseVoice Small INT8 remains the Android opt-in, quality-first model. Its fixed weights are a
user-initiated download into private storage and are not bundled in the APK. Keep Paraformer Small
INT8 as a lightweight comparison candidate. Do not present the offline route as the universal
default until representative physical-device and unseen mobile-speech gates pass.

SenseVoice is the stronger bilingual choice. Paraformer is substantially smaller and faster, and
its Mandarin result is better on the complete ASCEND split, but its English and mixed-speech
accuracy are not as balanced. A same-size Paraformer Large and whisper.cpp Small Q5_1 were added to
the screen and rejected as defaults: the former remains weak in English and names, while the latter
is much worse on Mandarin, code switching, and short-utterance latency. None provides a native
hotword path in the tested configuration.

## Exact candidates

| Candidate | Selected files | Model SHA-256 | Token SHA-256 | Intended tier |
| --- | ---: | --- | --- | --- |
| SenseVoice Small INT8 2024-07-17 | 228.45 MiB | `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51` | `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc` | quality |
| Paraformer Small INT8 2024-03-09 | 78.11 MiB | `3ef6c19369b912f7caf3cef8e545c5ccd1a33d9d7ec792a46668dc41c4b229ec` | `4b2d964e18b9cf139b473003b6698fb2ed9a2a5ec55b93daa677b28f578897aa` | lightweight |
| Paraformer Large INT8 2024-03-09 | 216.79 MiB | `16843a29d12d3780ccaf9ed3514450cce0d96d8003ab5e08925f3fa6a1fd5d80` | `6c0e3b35cece259829e6cb5b8d90d13db88f61ea3a2953d11898e4b2bfd7a2e2` | rejected default |
| Whisper Small Q5_1 | 181.28 MiB | `ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb` | n/a | rejected default |

The first three were run with sherpa-onnx 1.13.4 on CPU with four threads. whisper.cpp was pinned to
commit `306c88f4d1286aec1bf96e544632897886af5501`, built CPU-only from v1.9.2, and used auto-language
beam-5 decoding. sherpa-onnx is Apache-2.0 and whisper.cpp is MIT; model weights retain their own
terms rather than inheriting the runtime license. The sherpa conversion repository for Paraformer
Large identifies its source model but does not carry a model-license file, so it cannot be shipped
without resolving that provenance. A downloadable
Android model must show its source, license, version, size, and hashes before consent, retain the
required attribution, and pass release legal review.

## Complete ASCEND test result

The pinned complete ASCEND test split contains 1,315 spontaneous Chinese-English conversation
utterances: 685 Mandarin, 257 English, and 373 mixed. No item received hotwords, corrections, or a
language label at inference time.

| Candidate | Mandarin CER | English WER | Mixed MER | Overall micro error | Processing RTF p50 / p95 | Host max RSS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| SenseVoice | 11.4% | 25.9% | 13.3% | 13.9% | 0.020 / 0.041 | 554 MiB |
| Paraformer | 9.5% | 34.2% | 16.3% | 15.5% | 0.009 / 0.018 | 262 MiB |
| Paraformer Large | 9.3% | 31.8% | 14.6% | 14.3% | 0.015 / 0.029 | 631 MiB |

Paraformer Large improves Mandarin over SenseVoice but still loses on English, mixed speech,
overall error, entity recovery, and observed memory; it does not replace the selected model.
SenseVoice's 13.3% mixed MER is comfortably below the ASCEND project's published 27.05% best MER
baseline, but the comparison is contextual rather than a leaderboard claim: normalization,
decoder, and training overlap may differ. Public-set overlap cannot be excluded, so an unseen
mobile blind set remains the release authority.

The two test speakers are also uneven: 861 cases come from speaker 17 and 454 from speaker 3.
SenseVoice overall error is 17.9% versus 9.1% for those speakers respectively; the headline number
must not hide that speaker sensitivity.

Reproducibility identifiers:

- manifest SHA-256: `71a695d71e283c598be55d1131f47c8960e560f54d09bdb48d097fdc73fcab63`
- audio-set SHA-256: `20688ea10accde8dcf4b3bdda878cf259e5566d8efb74ba543f8e38829547734`
- runner SHA-256: `df0ad9ddf97cd14c89f2d3866e3cacec26395c119d975e87147612b7810df140`
- metrics SHA-256: `251fe56062ddcab15d3252c16429738247c70d36143a126190fef094dde606bf`

## Screening and personalization probes

The fixed 200-case public subset adds 50 Mandarin and 50 English FLEURS development examples to a
speaker/language-balanced 100-case ASCEND slice.

| Candidate | Mandarin CER | English WER | Mixed MER |
| --- | ---: | ---: | ---: |
| SenseVoice | 10.6% | 8.7% | 20.4% |
| Paraformer | 13.3% | 27.4% | 19.5% |
| Paraformer Large | 10.0% | 25.8% | 17.4% |
| Whisper Small Q5_1 | 29.1% | 8.4% | 42.1% |
| 2023 streaming Zipformer baseline | 12.6% | 59.6% | 24.5% |

On the 142-case deterministic synthetic entity/control corpus, SenseVoice reached 16/60 entity
hits, Paraformer Small 11/60, and Paraformer Large 12/60. SenseVoice's mixed MER was 48.0%; the two
Paraformers reached 39.0% and 37.0%, but their English WER was 27.6% and 22.7% versus SenseVoice's
10.0%. Whisper was stopped after the complete public screen because it had already failed the
bilingual accuracy and latency gates; running synthetic TTS would not change the default decision.
These synthetic
voices exaggerate some code-switch failures but correctly expose that raw recognition does not yet
solve arbitrary personal names. The product's explicit, globally applied correction dictionary
remains necessary, and its false replacements must continue to be measured on negative controls.

Whisper's public-set overall error was 23.9%; its 100 spontaneous ASCEND cases were 39.9%, versus
19.3% on 100 read-speech FLEURS cases. RTF p50/p95 was 0.418/2.662, compared with SenseVoice's
0.021/0.041. Very short utterances were especially unsafe: Chinese fillers were sometimes emitted
as English or Korean text. The outcome rejects this exact Small Q5_1 configuration for a bilingual
mobile keyboard; it is not a claim about every Whisper size, fine-tune, language lock, or platform.

## Android integration gate

The quality route is now implemented as a user-initiated model download into app-private, no-backup
storage, not an APK asset. It pins the model revision and the sherpa-onnx source commit, enforces exact
byte counts and SHA-256 before an atomic install, never attaches API credentials, and exposes model
status and deletion in the settings UI. A full hash is repeated before the first decode in each
process; failure invalidates the install marker. The IME refuses to start recording if the selected
model is missing, corrupt, or on an Android low-RAM device.

The explicit API 36 arm64 emulator gate used 2.5 GB RAM and a real ASCEND utterance. After replacing
the upstream all-feature AAR with the OpenTypeless ASR-only runtime, a fresh 239,549,735-byte model
download, two-file verification, cold native load, decode, and exact-reference assertion completed
in 25.537 seconds. Cold load plus decode took 1.054 seconds. Process `VmHWM` reached 468,608 KiB;
RSS after releasing the recognizer was 219,112 KiB. The high transient peak is why Android low-RAM
devices are blocked from this quality tier.

The ASR-only AAR is built from sherpa-onnx commit
`142807252687d81b40d6315f23470a1512a00de3` with Android NDK r27d and disables TTS, speaker
diarization, the C API, and WebSocket support. It packages only `arm64-v8a` and `x86_64`, contains no
eSpeak/Piper/TTS symbols, and has SHA-256
`35af2790bfcb39a1bfe6d0d495193b7fadc367c5c6f07e5e95996ba210cb9196`. The AAR is 19,539,693 bytes;
a clean universal debug APK is 55,093,123 bytes and the unsigned release APK is 54,219,813 bytes.
Play App Bundles can still split the two ABIs for delivery.

Two independent clean work directories produced byte-identical AARs. The builder maps source and
build prefixes out of debug metadata and disables the Android linker's nondeterministic ELF Build
ID; per-ABI JNI and complete AAR SHA-256 values remain embedded in the provenance manifest.

## Post-integration accuracy probes

The low-amplitude failure seen in the original streaming baseline did not transfer to SenseVoice.
On the same 26 FLEURS English recordings below RMS 0.002, SenseVoice produced 36 edits over 516
reference words (6.98% WER); the other 174 public cases were 377/3,191 (11.81%). Automatic gain was
therefore rejected: it would amplify noise without addressing the selected model's error pattern.

A provenance-checked A/B changed only SenseVoice inverse text normalization (ITN):

| Corpus | ITN | Overall | zh CER | en WER | mixed MER | Improved / worsened / tied |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Public 200 | off | 11.14% | 10.59% | 8.74% | 20.37% | — |
| Public 200 | on | 9.98% | 8.56% | 9.07% | 19.22% | 26 / 28 / 146 |
| Synthetic 142 | off | 12.28% | 4.16% | 10.00% | 47.97% | — |
| Synthetic 142 | on | 12.69% | 5.97% | 11.57% | 40.65% | 13 / 26 / 103 |

ITN helps dates and written numbers in FLEURS and lowers mixed MER, but it also converts dictated
phrases such as “three thirty” to locale-inappropriate forms such as `3,30`. It remains off by
default until formatting can be locale-aware and separately controllable. The companion script
rejects any baseline runner, manifest, audio-set, or model hash drift; its compact result is
[`2026-08-09-sensevoice-itn-summary.json`](../benchmarks/offline_asr/reports/2026-08-09-sensevoice-itn-summary.json).

An exploratory fuzzy alias matcher increased synthetic canonical-name recovery but also rewrote the
negative control “soup base” as `Supabase`. Automatic fuzzy replacement was rejected. OpenTypeless
continues to require exact user-confirmed aliases/corrections, which keeps control-set false
replacements at zero.

A second provenance-checked A/B tested SenseVoice's documented language parameter while keeping
ITN off and every other input fixed:

| Corpus | Language | Overall | zh CER | en WER | mixed MER | Entity hits |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Public 200 | auto | 11.14% | 10.59% | 8.74% | 20.37% | n/a |
| Public 200 | zh | 11.09% | 10.01% | 10.32% | 18.31% | n/a |
| Public 200 | en | 19.05% | 20.45% | 9.15% | 39.59% | n/a |
| Synthetic 142 | auto | 12.28% | 4.16% | 10.00% | 47.97% | 16/60 |
| Synthetic 142 | zh | 11.16% | 4.05% | 9.61% | 41.46% | 19/60 |

The Android offline route now maps only an explicit `zh-*` or `cmn-*` setting to SenseVoice `zh`.
English and all unmeasured language families remain `auto`: forcing `en` regressed the public
English result, and untested language locks are not enabled by assumption. The API 36 arm64 gate
then repeated a fresh model download with `zh-CN` and produced the exact reference in 1.082 seconds
for cold load plus decode (`VmHWM` 467,212 KiB). The compact A/B is
[`2026-08-09-sensevoice-language-lock-summary.json`](../benchmarks/offline_asr/reports/2026-08-09-sensevoice-language-lock-summary.json).

Before the quality tier can be called fully usable, it still needs:

1. physical low/mid/high arm64 device cold-start, peak RSS, stop-to-final latency, battery, and
   thermal measurements (the emulator gate is evidence, not a physical-device substitute);
2. safe cancellation and model deletion while a decode is queued or running;
3. an unseen multi-speaker phone-microphone set covering accents, noise, low level, long pauses,
   short utterances, names, and natural code switching;
4. a defined low-memory fallback when model initialization fails without killing the IME process;
5. final legal review of the downloadable model's historical license metadata (runtime dependency
   licenses and attributions are now complete and reachable in-app).

The machine-readable aggregate is
[`benchmarks/offline_asr/reports/2026-08-09-offline-candidates-summary.json`](../benchmarks/offline_asr/reports/2026-08-09-offline-candidates-summary.json).
