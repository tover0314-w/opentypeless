# Mobile voice evaluation

This directory is the product-level evaluation layer for OpenTypeless on real phones. It is
deliberately separate from `benchmarks/offline_asr`: the offline benchmark compares recognizer
models on fixed WAV files, while this suite measures the complete keyboard path from the user's
gesture to visible and committed text.

## Two complementary gates

1. **Replay gate** — feed the same recorded WAV to every ASR backend. Use this for fair CER/WER,
   entity, punctuation, latency, CPU, and memory comparisons.
2. **Interaction gate** — speak the checked-in prompts through the real IME. Use this to catch
   microphone readiness, short-utterance, partial-result, stop, lifecycle, fallback, and editor
   mutation failures that a WAV runner cannot reproduce.

Never compare backends using separate live takes and call the result an accuracy comparison. A
different take changes timing, pronunciation, level, and noise.

## Checked-in prompt set

`corpus.jsonl` is an authored Chinese-English recording script. It contains no user audio and no
third-party corpus text. It covers:

- one-to-four-character Chinese and one-to-three-word English utterances;
- normal Mandarin, English, and code switching;
- numbers, dates, money, email, and URLs;
- punctuation, pauses, and self-correction;
- explicit personal-dictionary entities and confusable negative controls.

The `repetitions` value is part of the test. In particular, each very short prompt is repeated 20
times because a single success does not catch microphone-readiness and tail-frame races.

Validate the corpus with no external dependencies:

```bash
python3 -m unittest discover -s benchmarks/mobile_voice -p 'test_*.py' -v
```

Before the spoken Xiaomi 15 matrix, collect a redacted startup/PSS/device baseline. The script
requires an exact serial so it can never select or mutate an unintended connected phone. It only
force-stops/launches OpenTypeless and reads platform diagnostics; it never installs an APK, changes
permissions, records audio, or reads transcripts:

```bash
python3 benchmarks/mobile_voice/device_preflight.py \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --serial '<exact adb serial>' \
  --apk android/app/build/outputs/apk/debug/app-debug.apk \
  --output /tmp/opentypeless-xiaomi15-preflight.json
```

The output hashes the serial, records the APK hash/version and device build, reports five cold
activity launches, foreground-process PSS/RSS, battery state, and whether the isolated local-ASR
process is active. It deliberately contains no audio, transcript, API key, editor context, or
personal dictionary data.

## Required per-run evidence

Record both the configured backend and the backend actually used after fallback. A result without
the actual route is invalid. For each utterance capture:

- device/build/model identifiers and language;
- microphone-ready, speech-start, first-partial, stop, ASR-final, and committed timestamps;
- every partial revision and the final raw ASR text;
- final displayed text after deterministic and optional AI processing;
- whether fallback, timeout, recovery, or user cancellation occurred;
- app/IME PSS before, peak, and after release when available;
- CPU time, thermal status, battery delta, network bytes, and provider cost for extended runs.

System speech services often run outside the OpenTypeless process. App-only PSS must therefore be
labelled `opentypeless_process_only`; it is not a measurement of total system recognition memory.

## Metrics

- Mandarin CER, English WER, and mixed MER;
- punctuation precision/recall/F1 as a separate metric;
- entity recall and false entity/correction replacements on controls;
- first-partial and stop-to-final p50/p95;
- empty, truncated, repeated, and cross-field transcript rates;
- partial revision rate and final correction distance;
- peak/steady PSS, CPU time, thermal level, battery drain, bytes, and monetary cost.

## Xiaomi 15 short-utterance release gate

For each `short` case, run 20 repetitions in a quiet room on every shipping route. At least 19/20
must produce a non-empty, non-truncated result. No late callback may write into a different field,
and only an explicit discard action may remove a visible draft. Silence and a single tap/cough
control must still be rejected.

Audio recording is opt-in. Raw audio stays disabled by default in the product Voice Lab; export
requires a separate explicit action and must strip API keys and editor context.
