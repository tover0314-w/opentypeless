# Speech Core v2 production-route engineering delivery report

Date: 2026-08-12
Branch: `agent/android-offline-followup`
Baseline commit: `2df68b3b39307835dc26c73d15b27877a172c0b0`
Delivery class: debug-signed engineering build; Xiaomi 15 physical acceptance still pending

## Decision

The local-offline keyboard route now uses Speech Core v2 by default. V1 is retained only behind an
explicit emergency rollback preference. When v2 is enabled, both the pinned Streaming Paraformer
and SenseVoice model sets are required; a missing model is reported as an incomplete installation
and never causes a silent route downgrade.

This is a real production-path cutover inside the engineering APK, not a Voice Lab shadow and not a
renamed v1 callback path. It does **not** authorize a stable-store release or a claim that the current
models outperform Baidu, Typeless, Gboard, or another mature keyboard.

## Delivered behaviour

- One continuous microphone capture feeds a warm first-pass Streaming Paraformer worker in the
  private `:local_stream` process.
- Soft/hard boundaries create ordered segments without treating an ordinary pause as cancellation.
- Safe provisional punctuation appears as a revision; a resumed segment can revise punctuation
  without blindly appending duplicate text.
- SenseVoice quality jobs run on demand in the separate `:local_quality` process. Results are
  generation-bound and may refine an earlier segment while the next segment remains live.
- A memory/thermal policy selects concurrent, sequential, or streaming-only execution explicitly;
  it never silently changes provider/privacy routing.
- `EditorProjection` owns the host composing span, validates connection/editor/field/selection and
  context before every mutation, reads back editor results, freezes on lifecycle loss, and commits
  at most once.
- Blank/stale partials cannot erase visible text. A rejected final becomes a recoverable draft; only
  an explicit discard may intentionally delete the current voice draft.
- Multi-segment text/audio checkpoints use AndroidKeyStore AES-GCM in no-backup storage with quotas,
  TTL, authenticated tail repair, generation handles, discard tombstones, and exactly-once
  acknowledgement.
- Voice Lab reports the selected and actual route, revision trace, first partial/final timings,
  process PSS, CPU/network/thermal bounds, and model provenance without becoming a second editor
  writer.
- The IME uses the revised two-row visual system, keeps live text inside the host editor, preserves
  navigation-bar insets, and keeps settings/advanced actions out of the primary typing surface.

## Automated verification

### JVM, benchmark tools, lint, and builds

Commands were forced to recompile rather than relying on Gradle cache.

```bash
cd android
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/dengxuezhao/Library/Android/sdk \
./gradlew :app:testDebugUnitTest --rerun-tasks

./gradlew :app:lintDebug :app:lintRelease :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest --rerun-tasks

/tmp/opentypeless-asr-bench-venv/bin/python -m unittest discover \
  -s benchmarks/offline_asr -p 'test_*.py' -v
```

Result:

- Android JVM: **410/410**, 83 suites, zero failure/error/skip.
- Offline-ASR tools: **60/60** with Python 3.11, `numpy==2.4.6`, and
  `sherpa-onnx==1.13.4`.
- Debug and Release lint: zero errors and one intentional `ChromeOsAbiSupport` warning for the
  optional arm64-only direct-delivery build. Normal CI/release builds remain universal.
- Debug, minified unsigned Release, and AndroidTest APKs assembled.

### API 36 arm64 emulator

The complete connected suite ran after both exact model sets had been downloaded and SHA-verified:

- **41 total: 40 pass, 1 designed skip, 0 failures/errors**.
- The sole skip is the opt-in external model-download test because ordinary connected runs are not
  allowed to fetch hundreds of MiB from the internet.
- Native Streaming Paraformer/SenseVoice Binder tests ran rather than skipping.
- Real AndroidKeyStore journal, Android `InputConnection`, manifest/service isolation, visual
  navigation, settings migration, recovery, recognition contracts, and lifecycle tests passed.

A deterministic 16 kHz Mandarin smoke WAV produced six non-empty live revisions and non-empty
first/second-pass finals. The most recent emulator diagnostic reported:

- streaming client start: 143 ms;
- session start to first partial: 1,238 ms;
- stream process PSS: 30,121 KiB;
- quality final: 1,408 ms;
- quality process PSS: 76,991 KiB.

`streaming client start` is Binder/session setup, not a claim that native model loading completed at
that point. Emulator timings/PSS are engineering evidence only and are not Xiaomi 15 measurements.

### Exact streaming-model screening

The public benchmark uses the exact Android Streaming Paraformer model, 40 ms chunks, endpointing
off, and explicit final flush over 100 balanced ASCEND test plus 50 Mandarin and 50 US-English
FLEURS examples.

| Metric | Result |
| --- | ---: |
| Mandarin CER | 12.48% |
| English WER | 40.18% |
| Mixed MER | 22.88% |
| Partial coverage | 95.5% |
| First-partial audio position p50 / p95 | 0.64 s / 3.04 s |
| Processing RTF p50 / p95 | 0.042 / 0.056 |
| Changed hypotheses | 1,682 |
| Earlier-visible-text rewrites from this model alone | 0 |

The result is pinned in
`benchmarks/offline_asr/reports/2026-08-12-streaming-paraformer-summary.json`. Speech Core v2 does
not claim that the first-pass model revises earlier words by itself; earlier revisions come from
segment quality/punctuation/personalization stages and remain provenance-labelled.

## Artifact

Debug-only Xiaomi 15 / arm64-v8a APK:

```text
android/app/build/outputs/apk/delivery/
OpenTypeless-0.3.0-SpeechCore-v2-Default-arm64-debug.apk
```

- bytes: 27,300,132 (26.04 MiB);
- SHA-256: `93333b47dcc17a120228aff4eeb05ee217634b837d3a02800712ce0110149d4b`;
- native ABI: `arm64-v8a` only;
- signature: Android Debug certificate, APK Signature Scheme v2.

The APK contains no model weights or credentials. It is an engineering experience build, not a
production-signed store artifact.

## Acceptance

Accepted for this engineering delivery:

- v2 as the default local-offline code route with an explicit v1 rollback switch;
- true streaming first pass plus isolated quality refinement;
- ordered segment revisions and provisional punctuation;
- target-bound editor projection, exactly-once commit/undo, and explicit-only discard;
- encrypted recovery and process-separated local models;
- Voice Lab diagnostics and reproducible model screening;
- JVM, benchmark, lint, build, and API 36 gates;
- installable arm64 debug APK.

Still required before a stable-release claim:

- Xiaomi 15 microphone acceptance for short Chinese/English, 1/5/15-minute long dictation,
  punctuation revisions, cursor/app switching, permissions, TalkBack, navigation modes, large text,
  battery, PSS, CPU, and thermal behaviour using this exact APK hash;
- a signed upgrade/rollback rehearsal with production signing authority;
- unseen phone-microphone accuracy/entity/punctuation evaluation;
- product policy for durable PCM capture if the guarantee must survive Android killing the IME
  before the first authenticated checkpoint.

The current journal recovers completed capture segments and accepted revisions. Audio not yet
returned by Android, or an in-flight segment killed before its first durable boundary, cannot be
recovered; this limitation is explicit rather than hidden behind a success message.

## Rollback

Rollback is a single explicit local preference that selects the retained v1 implementation. It is
never activated automatically by missing weights, a slow model, or recognition failure. Keep this
switch for at least one complete physical-device release cycle; remove it only after Xiaomi and
upgrade/rollback acceptance are recorded.
