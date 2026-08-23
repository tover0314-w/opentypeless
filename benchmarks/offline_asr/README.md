# Offline ASR benchmark

This benchmark evaluates candidate Android offline recognizers without adding model weights,
generated audio, or public-corpus audio to the repository. Its checked-in synthetic corpus covers
48 source prompts across:

- general Mandarin and English dictation;
- Chinese-English code switching;
- Chinese and English personal entities;
- acoustically related controls that should not be replaced by hotwords;
- selected synthetic pink-noise probes.

The checked-in corpus is suitable both for reproducible macOS TTS smoke tests and as a recording
script for consented human speakers. The public-data adapter adds pinned subsets of ASCEND and
FLEURS. Neither synthetic speech nor a small public subset is sufficient evidence of production
accuracy, cross-device quality, accent robustness, or superiority over another product.

## Candidates

The initial candidate is the Apache-2.0 bilingual Chinese-English streaming Zipformer transducer
published by sherpa-onnx. It remains the streaming baseline. The second round evaluates the
non-streaming SenseVoice Small INT8 and Paraformer Small INT8 exports through the same sherpa-onnx
runtime. Model archives and public audio are intentionally downloaded outside the repository.

```bash
mkdir -p /tmp/opentypeless-asr-bench
curl -L --fail --retry 3 \
  -o /tmp/opentypeless-asr-bench/zipformer-zh-en.tar.bz2 \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2
echo '27ffbd9ee24ad186d99acc2f6354d7992b27bcab490812510665fa8f9389c5f8  /tmp/opentypeless-asr-bench/zipformer-zh-en.tar.bz2' | shasum -a 256 -c -
tar -xjf /tmp/opentypeless-asr-bench/zipformer-zh-en.tar.bz2 \
  -C /tmp/opentypeless-asr-bench
```

The benchmark follows the upstream example: INT8 encoder and joiner with the small FP32 decoder,
plus tokens and BPE vocabulary. Their exact combined size is 199,068,769 bytes (189.85 MiB).
This candidate is evaluated, not bundled into the Android application.

The second-round runner accepts an extracted SenseVoice or Paraformer directory containing
`model.int8.onnx` and `tokens.txt`:

```bash
/tmp/opentypeless-asr-bench-venv/bin/python \
  benchmarks/offline_asr/run_sherpa_offline.py \
  --model-type sense_voice \
  --model-dir /tmp/opentypeless-asr-bench/sensevoice \
  --manifest /tmp/opentypeless-asr-bench/public/manifest.jsonl \
  --output-dir /tmp/opentypeless-asr-bench/sensevoice-results
```

Use `--model-type paraformer` for the lightweight candidate. These recognizers return only a final
result; the runner deliberately reports zero partial-result coverage rather than presenting them as
streaming models.

The Android live-preview model is a different, revision-pinned online Paraformer. Benchmark that
exact encoder/decoder pair with the same 40 ms chunks and explicit final flush used on device:

```bash
uv run --python 3.11 --with-requirements benchmarks/offline_asr/requirements.txt \
  python benchmarks/offline_asr/run_sherpa_streaming_paraformer.py \
  --model-dir /path/to/streaming-paraformer-current \
  --manifest /tmp/opentypeless-asr-bench/public-stratified/manifest.jsonl \
  --output-dir /tmp/opentypeless-asr-bench/streaming-paraformer-results
```

This runner also counts hypotheses that revise earlier text rather than merely appending. Those
revisions are expected input to Speech Core v2's `SegmentRevision` reducer; they must never be
implemented as blind `commitText` appends.

Keep the audited baseline runner unchanged when testing SenseVoice inverse text normalization. Use
the provenance-checked companion instead:

```bash
/tmp/opentypeless-asr-bench-venv/bin/python \
  benchmarks/offline_asr/compare_sensevoice_itn.py \
  --model-dir /tmp/opentypeless-asr-bench/sensevoice \
  --manifest /tmp/opentypeless-asr-bench/public/manifest.jsonl \
  --baseline-results /tmp/opentypeless-asr-bench/sensevoice-results/results.json \
  --output-dir /tmp/opentypeless-asr-bench/sensevoice-itn-results
```

To test whether an explicit keyboard language should override SenseVoice auto detection, use the
same provenance-checked baseline with `compare_sensevoice_language.py`. It accepts only upstream's
documented `zh`, `en`, `yue`, `ja`, and `ko` language tokens and keeps ITN off:

```bash
python benchmarks/offline_asr/compare_sensevoice_language.py \
  --model-dir /path/to/sensevoice \
  --manifest /path/to/manifest.jsonl \
  --baseline-results /path/to/auto/results.json \
  --language zh --language en \
  --output-dir /path/to/language-results
```

For a whisper.cpp candidate, build a pinned release with Metal disabled and run the local,
CPU-only benchmark server through `run_whisper_cpp.py`. The runner starts an ephemeral loopback-only
server, fixes auto-language beam decoding, and records the runtime commit plus exact model, binary,
script, manifest, and audio-set hashes:

```bash
python benchmarks/offline_asr/run_whisper_cpp.py \
  --server-binary /path/to/whisper-server \
  --runtime-commit 306c88f4d1286aec1bf96e544632897886af5501 \
  --model-file /path/to/ggml-small-q5_1.bin \
  --manifest /path/to/manifest.jsonl \
  --output-dir /path/to/results
```

## Reproducible synthetic run on macOS

```bash
uv venv /tmp/opentypeless-asr-bench-venv --python python3.11
uv pip install --python /tmp/opentypeless-asr-bench-venv/bin/python \
  -r benchmarks/offline_asr/requirements.txt

/tmp/opentypeless-asr-bench-venv/bin/python \
  benchmarks/offline_asr/generate_macos_tts.py \
  --output-dir /tmp/opentypeless-asr-bench/audio \
  --voice-set all \
  --add-noise

/tmp/opentypeless-asr-bench-venv/bin/python \
  benchmarks/offline_asr/run_sherpa_zipformer.py \
  --model-dir /tmp/opentypeless-asr-bench/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20 \
  --manifest /tmp/opentypeless-asr-bench/audio/manifest.jsonl \
  --output-dir /tmp/opentypeless-asr-bench/results
```

`--add-noise` is an explicit alias for `--noise-policy probes-and-controls`: it creates deterministic
15 dB SNR pink-noise pairs for marked probes and every hotword-control prompt. Every WAV, generation
parameter, tool version, random seed, actual SNR, and SHA-256 is recorded. A non-empty output
directory is rejected unless `--overwrite` is supplied, preventing a new reference from silently
reusing stale audio.

The runner reports greedy search, a modified-beam-search baseline without hotwords, and the same
beam decoder at hotword scores 1.5, 2.0, and 3.0. English hotwords are uppercased for this model's
case-sensitive tokenizer. Pronunciation hints remain separate from explicit, globally applied
deterministic corrections; positive and negative samples receive the same correction map. Headline
scores are corpus-micro WER for English, CER for Mandarin, and MER (Han characters plus contiguous
English words) for code switching. It also reports utterance-macro scores, entity recall, false
hotword/correction insertions, partial-result coverage, full streaming-processing RTF, strata, and
model/audio/script hashes.

## Pinned public real-speech run

The adapter currently prepares:

- 100 examples from the ASCEND test split, deterministically balanced over speaker and the `zh`,
  `en`, and `mixed` labels after scanning all 1,315 pinned metadata rows;
- the first 50 development-audio members encountered in each pinned official FLEURS archive for
  `cmn_hans_cn` and `en_us`, streamed without downloading the complete multi-gigabyte corpora.

ASCEND data is CC-BY-SA-4.0 and FLEURS data is CC-BY-4.0. Downloaded data stays outside the
repository. Upstream identifiers, response schema, audio ETags or archive generation/CRC32C,
formats, durations, levels, and hashes are verified. FLEURS source level is deliberately preserved;
the adapter records low-level audio but does not make an unofficial normalized benchmark.

```bash
/tmp/opentypeless-asr-bench-venv/bin/python \
  benchmarks/offline_asr/prepare_public_corpora.py \
  --output-dir /tmp/opentypeless-asr-bench/public \
  --ascend-count 100 \
  --fleurs-count-per-language 50

/tmp/opentypeless-asr-bench-venv/bin/python \
  benchmarks/offline_asr/run_sherpa_zipformer.py \
  --model-dir /tmp/opentypeless-asr-bench/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20 \
  --manifest /tmp/opentypeless-asr-bench/public/manifest.jsonl \
  --output-dir /tmp/opentypeless-asr-bench/public-results
```

No hotword mode is run when a manifest contains no hotwords. Public subsets are for candidate
screening and regression, not a replacement for the complete official test sets or an unseen
product-specific blind set.

For the complete pinned ASCEND test split, set `--ascend-count 1315` and `--fleurs-count-per-language
0`. The adapter fetches the 1,315 audio objects with a bounded worker pool and commits the output
directory atomically only after every schema, revision, format, duration, and hash check succeeds.

## Tool tests

```bash
/tmp/opentypeless-asr-bench-venv/bin/python -m unittest discover \
  -s benchmarks/offline_asr -p 'test_*.py' -v
```

The 2026-08-09 evaluation and rejection decision for the first Zipformer candidate are recorded in
[`docs/2026-08-09-offline-asr-candidate-evaluation.md`](../../docs/2026-08-09-offline-asr-candidate-evaluation.md).
The SenseVoice/Paraformer comparison and Android promotion gate are recorded in
[`docs/2026-08-09-offline-asr-candidate-round-2.md`](../../docs/2026-08-09-offline-asr-candidate-round-2.md).

## Human recording phase

For release acceptance, replace the generated manifest audio paths with consented, lossless,
single-channel recordings from multiple speakers and devices. At minimum, keep separate cohorts for
quiet rooms, street or transit noise, accented Mandarin, US and UK English, code switching, long
pauses, and personal entities. Report every cohort separately; do not hide a language or device
regression inside an aggregate score. The final gate must also run on representative Android
devices; macOS RTF and memory measurements are only candidate-screening signals.
