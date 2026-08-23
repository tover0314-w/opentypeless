# STR-004 local streaming candidate benchmark

This benchmark combines two deliberately separate evidence layers:

1. the pinned 200-case ASCEND/FLEURS accuracy report produced by
   `benchmarks/offline_asr/run_sherpa_streaming_paraformer.py` on macOS; and
2. fresh-process plus warm-session latency and isolated-process PSS measured by the exact Android
   runtime on one explicitly selected physical device.

The layers are never presented as one-platform measurements. The device run uses the exact
revision-pinned INT8 Streaming Paraformer candidate and upstream public `test_wavs/0.wav`; it does
not record the microphone or export a transcript. Model weights and WAV files remain outside Git.

The selected candidate is Apache-2.0 and is documented by upstream sherpa-onnx as a bilingual
Chinese/English online Paraformer. STR-004 evaluates it; STR-005 is responsible for any new
Provider integration. The runner does not change the default IME or production recognition route.

## Prepare pinned external artifacts

Download the three files from revision
`8e40c43232a1c5c66c82111efc5820d3accca11b` of
`csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en` and verify the constants in
`run_android_candidate.py`. Download `test_wavs/0.wav` from the same revision and pass it as
`--audio`; the runner rejects any size/hash drift.

## Run

```bash
python3 benchmarks/streaming_asr/run_android_candidate.py \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --serial '<exact adb serial>' \
  --app-apk android/app/build/outputs/apk/debug/app-debug.apk \
  --test-apk android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --model-dir /path/to/pinned-model-files \
  --audio /path/to/pinned/test_wavs/0.wav \
  --output /tmp/str004-android-report.json
```

The runner installs the supplied APKs, refuses to replace a mismatching private model, stages the
exact model only when absent, runs one fresh-process and five warm 10-second public-WAV sessions,
and writes a redacted atomic report. It leaves the verified optional model installed and removes
only its own `/data/local/tmp/opentypeless-str004-*` staging directory.

## Tool tests

```bash
python3 -m unittest discover -s benchmarks/streaming_asr -p 'test_*.py' -v
```

This is a screening result, not a claim of universal model quality. English WER, unseen phone-
microphone data, accents, sustained battery/thermal behavior, and earlier-visible-text revision
remain explicit limits in the generated report.
