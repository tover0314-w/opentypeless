# Xiaomi 15 Android P0 acceptance — 2026-08-11

This is the physical release authority for OpenTypeless Voice Core 0.3. Emulator and replay tests
remain mandatory, but they cannot substitute for Xiaomi/HyperOS microphone, input-method,
accessibility, power, or process-lifecycle behavior.

## Safety and evidence boundary

- Use a dedicated test field and the checked-in `benchmarks/mobile_voice/corpus.jsonl`; do not speak
  personal messages, credentials, account data, or private names.
- The collector requires the exact ADB serial and never auto-selects, unlocks, enables debugging,
  grants permissions, changes the default IME, or installs unless `--apk` is explicitly supplied.
- The evidence bundle excludes logcat, screenshots, transcripts, clipboard contents, accounts,
  personal vocabulary, and other-app data. Screen recordings used for interaction review stay with
  the tester unless they are separately reviewed and intentionally attached.
- Record the exact APK SHA-256, commit, model/package versions, HyperOS build, battery mode, locale,
  navigation mode, font scale, and selected plus actual recognition route.

Collect the non-content baseline from the repository root:

```bash
python3 benchmarks/mobile_voice/device_preflight.py \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --serial '<exact adb serial>' \
  --apk android/app/build/outputs/apk/debug/app-debug.apk \
  --output /tmp/opentypeless-xiaomi15-preflight.json

android/tools/collect-xiaomi15-acceptance.sh \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --serial '<exact adb serial>' \
  --apk android/app/build/outputs/apk/debug/app-debug.apk \
  --smoke
```

## Fixed P0 matrix

| ID | Scenario | Pass condition |
| --- | --- | --- |
| XM-P0-01 | Identity, install, launch, registration | Exact Xiaomi 15/HyperOS build and APK hash are recorded; install/update, settings launch, IME registration, enable/select, force-stop restart, and one reboot do not crash or loop. |
| XM-P0-02 | Permission and route truth | Denial is actionable; allow-while-in-use works without reinstall; microphone indicator appears only during capture; selected route, actual route, provider identity, privacy boundary, and fallback reason are truthful. |
| XM-P0-03 | Mandarin short hold-to-talk | Run every one-to-four-character `short` prompt 20 times per shipping route. At least 19/20 per prompt are non-empty and untruncated; speech before true-ready feedback is not counted as a valid take. |
| XM-P0-04 | English short hold-to-talk | Run every one-to-three-word English `short` prompt 20 times per shipping route. At least 19/20 per prompt are non-empty and untruncated, with no joined/repeated words. |
| XM-P0-05 | Live partial to final | Tap inserts one space; hold shows revisable composing text; release ends and commits exactly once. Final may revise partial but never flashes empty, duplicates, loses lexically safe punctuation, or writes after explicit discard. |
| XM-P0-06 | Long text | Speak three Chinese/English/mixed paragraphs with 3–5 second thinking pauses. The session stays active until “结束并上屏”, keeps prior segments, respects the total recording limit, and commits once. |
| XM-P0-07 | Normal and abnormal termination | Release, explicit finish, provider endpoint, normal EOF, final-empty, network loss, timeout, and post-processing failure retain the last safe visible text or a recoverable draft. Only explicit confirmed discard removes it. |
| XM-P0-08 | Explicit discard | Discard while preparing, recording, transcribing, and after a detached result. No late audio checkpoint, callback, draft, or text may reappear; a later session starts normally. |
| XM-P0-09 | Editor/lifecycle binding | Move the cursor, change selection, switch fields/apps, hide/switch the keyboard, rotate, Home/resume, and recreate the view at partial and finalizing phases. Old results never reach a new target; safe draft text remains recoverable. |
| XM-P0-10 | Selected-text edit safety | Smart/Translate partials never alter the selection. Success replaces once with Undo; network/integrity/error/discard preserves the original selection and never inserts the spoken instruction as body text. |
| XM-P0-11 | Sensitive/no-learning fields | Password capture is disabled. `IME_FLAG_NO_PERSONALIZED_LEARNING` creates no history, usage-count, context, or Teach update; existing explicitly confirmed vocabulary may still aid recognition. |
| XM-P0-12 | Personal vocabulary | Exercise every authored entity positive plus negative control. Enabled rules are deterministic, evidence is visible, disabled rules do not apply, import/search/export survive restart, and no plaintext term appears in DB/WAL/SHM. |
| XM-P0-13 | Provider/network failure | Revoke connectivity or return auth/malformed/oversize responses. The route and failure are explicit, no secret is logged, redirects are rejected, no implicit provider switch occurs, and usable partial/final text is retained. |
| XM-P0-14 | Local model isolation | Download/verify the pinned model, run cold and warm takes, kill or pressure `:local_asr`, cancel, retry, and remove the model. IME/app remains responsive; model failure cannot take down the keyboard process. |
| XM-P0-15 | Latency | For routes advertised as live, first partial target is p50 <350 ms and p95 <700 ms. Stop-to-final target is p95 <1.5 s. Report microphone-ready separately and mark final-only routes rather than assigning them a false partial time. |
| XM-P0-16 | Memory and CPU | Report app/IME and `:local_asr` separately at idle, cold peak, warm peak, and 30 seconds after release. Provisional guardrails: app steady PSS <=120 MiB, local peak <=650 MiB, local post-release <=300 MiB, no sustained idle CPU, and no growth across ten cancel/retry cycles. |
| XM-P0-17 | Thermal, battery, network | Run five minutes system/on-device and five minutes local. Record start/end battery and thermal status, CPU and network deltas; no severe-or-higher thermal state, microphone lock, runaway retry, or background traffic after completion. |
| XM-P0-18 | Visual and accessibility | Test light/dark, gesture/three-button navigation, portrait/landscape, 320/360 dp, font scale 1.0/1.3/2.0, TalkBack, and high contrast. All actions remain reachable, labels/states are truthful, and touch targets are at least 48 dp. |
| XM-P0-19 | Target apps and upgrade | WeChat/Telegram or equivalent chat, Xiaomi Notes, Chrome, search, and an AOSP text field pass short/long/Enter actions. Signed 0.2→0.3 upgrade retains settings/dictionary/history/model state; downgrade is rejected without data loss. |

## Scoring and release decision

Use one recorded take replayed across compatible routes for accuracy comparison; separately label
live microphone runs as interaction tests. Report Mandarin CER, English WER, mixed MER,
punctuation F1, entity recall, negative-control replacements, empty/truncated/duplicate/wrong-field
rates, ready/partial/final latency, process-bounded memory/CPU, thermal, battery, network bytes, and
cost. A route with an unknown actual provider is not a valid comparison row.

Copy `android/tools/latency-template.csv` for the physical run and summarize it with:

```bash
android/tools/summarize-latency.py xiaomi15-latency.csv \
  --output xiaomi15-latency-summary.json
```

P0 is not accepted while any XM-P0 row is `PENDING` or failed. Attach the completed table,
preflight JSON, collector archive, aggregate metrics, exact commit, and APK checksum to the draft
PR. The known mid-capture process-death boundary remains explicit: completed batch/local captures
are journaled, but PCM that Android never returned before killing the IME cannot be recovered
without a separately validated chunk-authenticated spool.
