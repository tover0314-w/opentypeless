# Issues #81/#83, STT Recording Limits, and Cloud Usage Efficiency Design

- Date: 2026-07-22
- Status: TalkMore compatibility server deployed to Production with capability v2 disabled; desktop/cross-platform rollout pending; atomic quota cutover deferred
- Repositories: `opentypeless` desktop and `talkmore` cloud service
- Issues: [OpenTypeless #81](https://github.com/tover0314-w/opentypeless/issues/81), [OpenTypeless #83](https://github.com/tover0314-w/opentypeless/issues/83)

## 1. Executive Summary

This design addresses three related problems without trading away the current desktop experience:

1. Issue #83: the beginning of a user's first recording can be lost while the microphone and STT provider initialize.
2. Issue #81: the desktop applies one global 30-second recording limit even though STT providers have different constraints.
3. TalkMore cloud usage: the desktop polls subscription status every five minutes, which creates unnecessary Neon database activity and can prevent compute from scaling to zero.

The approved direction is:

- start microphone capture and STT connection concurrently, and do not report recording readiness before the platform audio stream is actually running;
- replace the global 30-second rule with a Rust-owned provider capability resolver and an `Auto (recommended)` or custom user setting;
- make the TalkMore Cloud product limit 10 minutes through a hybrid managed-upload path: preserve WAV for short recordings and use bounded Ogg/Opus for long recordings;
- replace fixed subscription polling with deduplicated refreshes only at meaningful product events; for the current release, refresh status once after managed output is delivered rather than changing billing-response contracts;
- hide Neon scale-to-zero wake-up latency behind active Cloud recording time instead of keeping Neon awake while the desktop is idle;
- preserve Neon and the existing production quota implementation as the billing source of truth for this release;
- defer the atomic/idempotent quota rewrite until an isolated real-PostgreSQL test environment is intentionally added;
- deploy server changes before desktop changes and retain backward-compatible response fields for existing clients;
- require Windows, macOS, and Linux runtime verification before closing the issues.

The desktop must remain usable while TalkMore or Neon is cold, slow, or temporarily unavailable. Cached account data is presentation-only; all managed-cloud authorization and quota decisions remain server-side.

## 2. Current Evidence

### 2.1 Issue #81

The current desktop config stores one `max_recording_seconds` value, defaulting to 30 seconds. `src/components/Capsule/DurationTimer.tsx` uses a WebView interval to call `stop_recording` when that value is reached.

This creates two problems:

- 30 seconds is a real limit for GLM-ASR, but it is not a universal limit for Groq, OpenAI-compatible upload providers, streaming providers, or TalkMore Cloud;
- a frontend timer is not a reliable authority when a window is hidden, throttled, or suspended differently by Windows WebView2, macOS WKWebView, and Linux WebKitGTK.

The current file-upload providers buffer 16 kHz, 16-bit, mono PCM and build a WAV file when recording ends. Both `src-tauri/src/stt/whisper_compat.rs` and `src-tauri/src/stt/cloud.rs` use a 24 MiB audio safety buffer. This client constraint is as important as an upstream provider's published limit.

### 2.2 Issue #83

Issue #83 occurs because the UI can enter a recording state while platform audio capture and the STT connection are still becoming ready. A fixed platform delay would be unreliable and would slow every recording.

The approved behavior is committed on the current desktop feature branch:

- audio capture has `Starting`, `Recording`, and `Idle` states;
- capture reports ready only after CPAL `stream.play()` succeeds;
- audio initialization and STT connection are awaited concurrently;
- the audio channel buffers up to 60 seconds of 20 ms chunks while the provider connects;
- dictation and Ask recording use the same startup barrier.

This spec does not treat those changes as shipped. They still require review, committed tests, and real-device verification on all three supported operating-system families.

### 2.3 TalkMore and Neon

The production TalkMore project is on Vercel Pro. This removes the plan-level blocker for the design's 210-second function-duration requirement: Vercel documents a maximum of at least 300 seconds for Pro without Fluid Compute and up to 800 seconds with Fluid Compute. The STT route must still declare and verify its duration explicitly rather than relying on a project default.

Vercel Pro does not raise the 4.5 MB Function request-body limit. The hybrid managed-upload design remains necessary even though the execution-duration budget is available.

The desktop currently:

- initializes authentication in the main WebView;
- refreshes `/api/subscription/status` every five minutes while a user is signed in;
- also refreshes on focus with a 30-second throttle;
- initializes the same auth store again in the hidden Ask WebView.

The main WebView remains alive while the app is hidden to the tray. Consequently, an idle signed-in desktop can generate approximately 12 status requests per hour or 288 per day.

The TalkMore status route currently performs authentication, effective-entitlement lookups, and `getOrCreateQuota`. The entitlement lookup reads subscription and license state in multiple queries, while `getOrCreateQuota` can write during a GET request.

Read-only production inspection found a small database but very high cumulative statement activity. In particular, a seven-row AppSumo license table had more than one million sequential scans. A sequential scan of seven rows is not itself expensive; the evidence points to excessive request frequency and round trips rather than table size.

Current cloud quota flows also issue several statements for reserve, settle, or release. Legacy quota code uses in-memory entries but explicitly flushes them after requests, removing most batching benefit.

No audio payload is stored in Neon. The dominant optimization target is compute activity and database round trips, not storage volume.

### 2.4 Implementation and Production Checkpoint (2026-07-22)

The implementation has passed its automated, Preview, and compatibility-only Production gates:

- the desktop implementation is committed on `codex/issues-83-startup` and the TalkMore compatibility implementation is committed on `codex/issues-81-cloud-usage`;
- the TalkMore suite passes 121 test files and 717 tests, `tsc --noEmit`, a local Webpack production build, and Vercel's clean-install default Turbopack production build;
- final Vercel Preview `talkmore-opt40ya29-tovers-projects.vercel.app` is `READY`, generated all 5,517 static pages, and produced all Serverless Functions without build or runtime error logs during the verification window;
- the deployed `/api/proxy/stt` Lambda is Node.js 24.x in `iad1`, has 2,048 MB memory, and has an effective 210-second timeout; project Fluid Compute is enabled;
- a pre-production ORM audit found that the deferred Drizzle columns would be selected implicitly by legacy `select()` and `returning()` calls when the migration was not applied; commit `6bb76b5` removed the deferred runtime schema and migration artifacts and added a regression test before Production deployment;
- Production deployment `talkmore-d02ldydr8-tovers-projects.vercel.app` is `READY` and owns the `opentypeless.com`, `www.opentypeless.com`, and `talkmore.vercel.app` aliases; the previous deployment `talkmore-o6mr5rgsa-tovers-projects.vercel.app` is the recorded rollback target;
- public browser verification rendered the homepage and Features route with meaningful content, correct headings, working navigation, no framework error overlay, and no captured console errors;
- unauthenticated application requests returned 401 from `/api/subscription/status` and from `/api/proxy/stt` before media parsing or database-backed quota work;
- neither Preview nor Production defines `MANAGED_STT_V2_ENABLED`, so capability version 2 remains disabled and current cloud users retain the existing compatible recording path;
- the post-deploy verification window contains no Vercel error-level or HTTP 500 logs; no database migration, production environment variable, or production billing path was changed.

The remaining release gates are an authenticated end-to-end managed long-form Ogg session, the 20-trial latency/quality measurements, real Windows/Linux desktop validation, desktop rollout, and 24–48 hour post-rollout Neon/latency observation. The authenticated macOS short-WAV gate is complete, but server deployment and one macOS sample alone do not close either issue.

### 2.5 Desktop Runtime Checkpoint (2026-07-22)

- Apple Silicon macOS successfully compiled and bundled the debug Tauri app at `src-tauri/target/debug/bundle/macos/OpenTypeless.app`; the generated bundle is arm64, ad-hoc signed, and includes the microphone usage description.
- The debug app launched as a real macOS process and rendered Home and Settings without a crash. Speech Recognition displayed `Auto (recommended) — 30 seconds` for OpenTypeless Cloud and the explicit stale/unavailable-capability warning, proving that Production's disabled capability does not expose the 10-minute path.
- The packaging command subsequently failed only when signing the updater archive because `TAURI_SIGNING_PRIVATE_KEY` is unavailable. The local debug `.app` is usable for QA, but it is not a releasable signed updater artifact.
- The owner explicitly approved macOS Accessibility and microphone access for the debug bundle. System Settings shows both permissions enabled, the restarted app no longer reports an Accessibility blocker, and the default input remains the built-in MacBook Air microphone.
- A controlled authenticated managed-Cloud short-WAV run disabled AI Polish, started recording after a local countdown, recorded for eight seconds, and stopped automatically. Capture readiness completed in 107 ms and `stop_recording` completed after the managed STT result in 4,539 ms. The newest local history row records `provider_kind = managed_cloud`, equal raw/polished text, and no output error; the owner confirmed the observed first-audio experience passed. AI Polish was restored to its original enabled setting afterward.
- This is one real Apple Silicon macOS sample, not the required 20-trial latency/quality cohort. It validates the macOS short-recording path and Issue #83 startup experience for this device, but does not validate the capability-v2 long Ogg path or other operating systems.
- A Windows MSVC-target `cargo check` was attempted from macOS but stopped in `ring`'s C build because the Windows MSVC headers/sysroot are unavailable (`assert.h` not found). This is a cross-toolchain limitation and is not counted as a Windows pass or an application-code failure.
- Linux was not run in this macOS environment. Windows and Linux still require their native build/runtime jobs; macOS still requires the broader latency/quality matrix and signed-release verification.

## 3. Goals

### 3.1 Recording and Provider Goals

1. Capture the first spoken audio once the user starts recording, including on a cold first use.
2. Make the displayed and enforced recording limit match the selected STT provider and current transport.
3. Give users a visible `Auto (recommended)` choice and safe custom duration choices.
4. Keep TalkMore Cloud recordings at or below 10 minutes while preserving the current WAV path for ordinary short recordings.
5. Stop gracefully at a limit and submit the recorded audio instead of discarding it.
6. Use the same behavior for hold-to-talk, toggle recording, dictation, and Ask recording.
7. Make limit enforcement independent of WebView visibility and timer throttling.
8. Make a 10-minute managed upload fit below the deployed Vercel Function request-body limit without temporary object storage.

### 3.2 Cloud and Cost Goals

1. Remove the five-minute desktop subscription polling loop for upgraded clients.
2. Let an idle signed-in app stop generating TalkMore database traffic.
3. Show quota changes immediately after successful managed-cloud STT, LLM, or Ask calls.
4. Preserve the current production entitlement and quota behavior while reducing read frequency and status-route round trips.
5. Reduce business-data work for `/api/subscription/status` to one read-only database round trip after authentication.
6. Treat reducing each quota state transition to one atomic business-data statement or transaction round trip as a separate future milestone that requires real-PostgreSQL concurrency evidence.
7. Avoid Redis, personalized CDN caching, and cron jobs that wake Neon while idle.
8. Wake Neon only for real account activity or an in-progress managed-cloud operation, and overlap that wake-up with time the user is already speaking.

### 3.3 User-Experience Goals

1. Never block the local application shell or BYOK recording on a TalkMore status refresh.
2. Never temporarily show a returning paid user as Free merely because Neon is cold or the network is offline.
3. Preserve streaming text latency and already-delivered content when usage synchronization fails late.
4. Reflect purchases and license activations promptly, including when the payment webhook is delayed.
5. Avoid duplicate warnings or inconsistent quota displays across main, capsule, and Ask windows.
6. Keep current clients compatible throughout the rollout.
7. Do not regress common short-recording stop-to-transcript latency, transcription quality, capture reliability, or battery/CPU behavior.

## 4. Non-Goals

- Do not add FLAC encoding, audio chunking, resumable uploads, temporary object-storage handoffs, or recordings longer than the approved provider/product caps in this work.
- Do not transcode BYOK or direct provider uploads in this work. Ogg/Opus is limited to the TalkMore managed-cloud transport.
- Do not store audio or transcripts in Neon as part of usage accounting.
- Do not introduce Redis, a WebSocket entitlement service, or a new always-on worker.
- Do not change plan prices, monthly quota amounts, AppSumo tiers, or the definition of billable words.
- Do not redesign the entire Settings, Account, Capsule, or Ask UI.
- Do not use a Vercel process-local cache as the correctness authority for subscriptions or quota.
- Do not force-upgrade old desktop clients solely to obtain the Compute savings.
- Do not claim Issue #81 or #83 is closed before release and cross-platform runtime verification.
- Do not introduce a project test-database requirement or cut production billing over to the staged atomic quota schema in the current release.

### 4.1 Current Billing-Scope Decision

The owner does not plan to add an isolated database test environment in the current phase. Therefore:

- atomic reserve, settle, release, replay, and reconciliation remain designed but are not current release scope;
- the additive atomic-usage schema and migration artifacts must be excluded from the current release; legacy Drizzle `select()` and `returning()` calls can otherwise reference unapplied columns implicitly even when the new routes do not use those fields directly;
- existing production quota mutations and billing formulas remain unchanged;
- unit and contract tests may cover compatibility, but they must not be represented as proof of PostgreSQL concurrency correctness;
- the atomic quota cutover can resume only when an isolated real PostgreSQL endpoint is available for destructive, concurrent, and replay testing;
- this deferral does not block Issues #81/#83, provider-aware recording limits, managed Ogg/Opus upload, removal of idle polling, or the single-read account-status optimization.

## 5. Product Decisions

### 5.1 Recording Setting

The setting has two modes:

- `Auto (recommended)`: use the current provider's recommended maximum;
- `Custom`: use a user-selected value that is no greater than the provider/client hard maximum.

The Settings UI shows the resolved value next to Auto, for example:

- `Auto (recommended) — 10 minutes` for Groq;
- `Auto (recommended) — 30 seconds` for GLM-ASR;
- `Auto (recommended) — 1 minute` for Apple Speech.

Custom choices include applicable values from 30 seconds, 1 minute, 2 minutes, 5 minutes, 10 minutes, 30 minutes, and 60 minutes, plus a custom numeric entry. Values above the resolved hard maximum are not selectable. The custom field must state the allowed range and clamp invalid persisted values after explaining the correction.

### 5.2 Initial Capability Matrix

| Provider/transport | Auto | Hard maximum | Governing reason |
|---|---:|---:|---|
| GLM-ASR file upload | 30 s | 30 s | Provider duration limit |
| Apple Speech local buffered recognition | 60 s | 60 s | Apple one-minute recognition guidance |
| TalkMore Cloud managed upload | 600 s | 600 s | Managed product policy plus the server-advertised hybrid WAV/Ogg transport |
| Groq upload | 600 s | 720 s | Current 24 MiB client buffer and Groq upload size |
| OpenAI-compatible upload | 600 s | 720 s | Current 24 MiB client buffer |
| SiliconFlow upload | 600 s | 720 s | Current 24 MiB client buffer is lower than provider limit |
| Custom Whisper-compatible upload | 120 s | 720 s | Unknown upstream behavior; client buffer remains authoritative |
| Deepgram streaming | 600 s | 3600 s | Product safety cap for a long-running stream |
| AssemblyAI streaming | 600 s | 3600 s | Product safety cap below the provider's three-hour session maximum |
| Volcengine streaming | 600 s | 3600 s | Product safety cap below current upstream duration resources |

The matrix is a versioned product registry, not an eternal assertion about upstream providers. Provider documentation must be rechecked when this table changes.

Relevant provider references at design time:

- GLM-ASR: <https://docs.bigmodel.cn/cn/guide/models/sound-and-video/glm-asr-2512>
- Apple Speech: <https://developer.apple.com/documentation/speech/sfspeechrecognizer>
- Groq Speech-to-Text: <https://console.groq.com/docs/speech-to-text>
- AssemblyAI streaming sessions: <https://www.assemblyai.com/docs/streaming/message-sequence>
- Deepgram live-stream recovery: <https://developers.deepgram.com/docs/recovering-from-connection-errors-and-timeouts-when-live-streaming-audio>
- SiliconFlow transcription: <https://docs.siliconflow.cn/cn/api-reference/audio/create-audio-transcriptions>
- Volcengine streaming ASR: <https://www.volcengine.com/docs/6348/1807452?lang=zh>

### 5.3 Managed-Cloud Encoding Boundary

Direct/BYOK file-upload providers continue to use the current 16 kHz, 16-bit, mono PCM/WAV path. TalkMore Cloud uses a hybrid transport because the deployed Next.js API runs as a Vercel Function, whose request body has a non-configurable 4.5 MB payload limit. The route's current 25 MiB application constant cannot expand that platform limit; oversized requests are rejected before route code runs.

The approximate WAV payload size is:

```text
bytes = seconds * 16,000 samples/s * 2 bytes/sample + 44-byte header
```

- 600 seconds is approximately 19,200,044 bytes and cannot pass through the current Vercel route;
- 720 seconds is approximately 23,040,044 bytes;
- the current 24 MiB client buffer is 25,165,824 bytes.

Managed Cloud therefore selects its payload by audio-part byte size:

- when the WAV audio part is at most `3,500,000` bytes, send the original WAV exactly as today;
- above that threshold, send Ogg/Opus encoded as mono speech at constant 48 kbit/s and a 10-minute duration cap;
- cap the encoded audio part at `4,000,000` bytes and the complete multipart request at `4,200,000` bytes, leaving explicit headroom below Vercel's 4.5 MB platform boundary;
- a 600-second, 48 kbit/s payload is approximately 3,600,000 bytes before small Ogg overhead and remains below the encoded-file cap when constant bitrate and normal page aggregation are used;
- never select an encoding solely from a filename or MIME string; the server validates the container and duration.

This is deliberately not “compress everything.” Common short recordings retain WAV's current latency and lossless signal. Only recordings that cannot safely traverse the managed route as WAV use Opus. Groq accepts Ogg uploads, while direct/BYOK upload providers keep their existing path and limits.

The desktop must use one pinned, bundled Opus implementation on Windows, macOS, and Linux. It must not depend on ffmpeg, GStreamer, AVFoundation, Media Foundation, or a system-installed `libopus`.

Implementation review selected:

- `opusic-c = 1.6.1` with its default `bundled` feature, resolving `opusic-sys = 0.7.3` and statically building the vendored Opus 1.6.1 source;
- `ogg = 0.9.2` for pure-Rust Ogg packet/page writing and reading;
- Rust 1.82 or newer and CMake 3.16 or newer on build/CI machines; end-user machines receive the statically linked codec and need no codec installation;
- BSD-3-Clause notices for `opusic-c`, `opusic-sys`, the vendored Opus source, and `ogg` in a shipped `THIRD_PARTY_NOTICES.md`;
- CI and release builds for `x86_64-pc-windows-msvc`, `aarch64-apple-darwin`, `x86_64-apple-darwin`, and `x86_64-unknown-linux-gnu` before enabling capability version 2.

TalkMore validates WAV and Ogg/Opus with a focused TypeScript container parser in `src/lib/audio-container.ts`. It does not install ffmpeg or a server-side native codec package: the parser walks RIFF chunks or Ogg pages, verifies page structure and CRC, reconstructs Opus packets, derives packet durations from TOC data, and checks granule/pre-skip consistency before quota reservation.

The 12-minute direct upload-provider hard cap continues to leave headroom under the existing 24 MiB PCM client buffer. Compression and chunking for those providers remain deferred.

Design-time platform references:

- Vercel Function request-body limit: <https://vercel.com/docs/functions/limitations>
- Vercel Function duration limits by plan and Fluid Compute setting: <https://vercel.com/docs/functions/limitations#max-duration>
- Vercel large-upload guidance: <https://vercel.com/kb/guide/how-to-bypass-vercel-body-size-limit-serverless-functions>
- Groq accepted formats and file limits: <https://console.groq.com/docs/speech-to-text>

### 5.4 Managed Upload Alternatives Considered

The selected hybrid path is preferred over the following alternatives:

- **Compress every managed recording:** simpler selection logic, but it spends CPU and introduces lossy input on the short recordings that dominate usage even though they already fit. This conflicts with the no-regression goal.
- **Upload WAV to temporary object storage and pass Groq a signed URL:** preserves lossless audio and bypasses Vercel's body limit, but adds an upload/token/storage lifecycle, temporarily persists user audio, and can add an extra network hop. It remains a future fallback, not the primary path.
- **Operate a dedicated media proxy outside Vercel:** preserves the current WAV client but adds always-on infrastructure, regional routing, security, observability, and operational cost disproportionate to this issue.
- **Cap TalkMore Cloud near 109 seconds:** requires the least engineering, but does not meet the approved 10-minute managed-cloud product behavior.

## 6. Desktop Recording Architecture

### 6.1 Provider Capability Registry

Rust owns one registry and exposes the resolved capability to the frontend. TypeScript must not maintain a duplicate provider limit table.

Conceptual model:

```rust
enum SttTransport {
    FileUpload,
    Streaming,
    LocalBuffered,
    ManagedUpload,
}

enum RecordingLimitSource {
    Provider,
    ManagedProduct,
    ClientBuffer,
    ProductSafety,
    UnknownUpstream,
}

struct SttRecordingCapability {
    provider_id: String,
    transport: SttTransport,
    recommended_max_seconds: u32,
    hard_max_seconds: u32,
    max_upload_bytes: Option<u64>,
    source: RecordingLimitSource,
    explanation_key: String,
}
```

The resolver returns both the raw capability and `effective_max_seconds` for the current config.

```text
effective maximum = min(
  mode-selected duration,
  provider hard maximum,
  client transport safety maximum,
  managed-server maximum when applicable
)
```

For TalkMore Cloud, the desktop has a built-in 600-second product ceiling but does not treat that ceiling as proof that the deployed server supports the version-2 transport. It takes the lower of the local policy and the negotiated server format/duration/byte policy. Missing or incompatible capability metadata resolves to the historical 30-second Cloud fallback; a server response may narrow, but never expand, the local product ceiling.

### 6.2 Config Compatibility and Migration

Add `recording_limit_mode` (`auto` or `custom`) and `custom_recording_limit_seconds`. Keep the existing `max_recording_seconds` as a compatibility mirror of the resolved effective value, not as the new source of user intent. Rust recomputes the mirror whenever the provider, mode, server capability, or custom duration is persisted. An older desktop therefore receives a safe value for the provider that was selected when the config was last saved.

Load rules for configs without `recording_limit_mode`:

- `max_recording_seconds == 30`: migrate to `auto` with a 600-second custom fallback, because 30 seconds was the historical default;
- any other valid value: migrate to `custom` and copy it into `custom_recording_limit_seconds`, preserving clear evidence that the user changed it;
- zero or an invalid value: migrate to `auto` and set the custom fallback to 600 seconds.

New installs store `recording_limit_mode = auto`, `custom_recording_limit_seconds = 600`, and a `max_recording_seconds` compatibility mirror resolved for the default provider.

When a custom value exceeds the newly selected provider's maximum:

- do not silently persist a destructive replacement while the user is recording;
- on provider selection or Settings load, show that the previous value is unavailable for this provider;
- clamp the effective value immediately for safety;
- persist the clamped custom value only when the user saves the setting.

A downgrade to a version that does not know the new fields continues to see a safe provider-resolved `max_recording_seconds`. If that old version changes providers it may remain conservatively capped until upgrading again, but it cannot expand beyond the last safe mirror. The new fields resume as the source of intent after re-upgrade unless the old version replaced the config, in which case the migration rules run again.

### 6.3 Authoritative Deadline

Rust owns the recording deadline. `DurationTimer` displays elapsed and remaining time but is not the only component capable of stopping a recording.

The authoritative start time is the instant when CPAL `stream.play()` succeeds, not the later instant when the STT connection also becomes ready. The capture-ready signal therefore carries or records that monotonic instant. This prevents provider connection time from extending a 30-second or 10-minute limit.

At audio-capture start, Rust snapshots:

- provider id and transport;
- resolved capability;
- selected limit mode;
- effective maximum;
- a monotonic start time and deadline;
- a recording session id.

Rules:

- provider/config changes do not alter an in-progress recording; the sole exception is a newer authenticated managed-server capability that narrows transport safety, which may only shorten the active Cloud deadline;
- a stale timer from a previous session cannot stop a newer session;
- the deadline continues to run while the pipeline is still in `Preparing`, and an expiry during slow STT startup becomes a single deferred/graceful stop as soon as startup ownership is established;
- reaching the deadline performs the same graceful stop/finalization path as a manual stop;
- the existing audio-buffer guard remains a second safety layer;
- if the safety guard is reached first, the pipeline stops and submits buffered audio rather than returning an error that discards the recording;
- after system sleep, an expired monotonic deadline triggers exactly one stop when the process resumes;
- the frontend may request stop at the displayed deadline as a responsiveness optimization, but duplicate stop requests must be idempotent.

User notifications:

- for limits of at least five minutes, notify at 60 seconds and 10 seconds remaining;
- for every supported limit below five minutes, notify once at 10 seconds remaining;
- on automatic stop, identify the source, for example `Reached the Groq recording limit (10 minutes)` or `GLM-ASR supports recordings up to 30 seconds`;
- automatic stop is not presented as an error when finalization succeeds.

### 6.4 Managed-Cloud Payload Builder

Only `CloudSttProvider` creates the additional Ogg payload. The provider continues to retain the 16 kHz mono PCM needed by the current pipeline and feeds a dedicated encoder worker from the same ordered chunks. Encoding must never run in the CPAL callback, on the WebView thread, or as blocking work on a Tauri async executor thread.

The encoder uses 20 ms frames, a constant 48 kbit/s mono speech profile, and an in-memory Ogg container. The muxer aggregates packets into normal Ogg pages instead of flushing one page per 20 ms packet, because per-packet page overhead would consume the Vercel safety margin. The queue between the provider and encoder is bounded. Each recording owns its encoder, payload state, sample count, and cancellation token; no process-global encoder or cross-recording buffer is allowed.

At stop:

1. derive duration from the captured sample count, not wall-clock time or compressed bytes;
2. if the generated WAV audio part is at most `3,500,000` bytes, discard the unused encoded payload and send WAV;
3. otherwise finalize Ogg/Opus and verify both the encoded-file cap and the managed 600-second cap before creating multipart data;
4. select an adaptive request timeout from payload bytes and duration, without changing the current 60-second timeout for recordings of 60 seconds or less;
5. clear both PCM and encoded buffers after success, terminal error, or cancellation.

The long-request timeout is:

```text
timeoutSeconds = if durationSeconds <= 60 {
  60
} else {
  clamp(60 + ceil(payloadBytes / 32,000), 60, 180)
}
```

This gives a 3.6 MB payload approximately 173 seconds on a very slow 256 kbit/s uplink while preserving the current 60-second behavior for ordinary short uploads. The TalkMore route must have at least 210 seconds of deployed function duration, leaving server cleanup/response headroom beyond the 180-second client cap. Vercel Pro supports that setting with or without Fluid Compute, but deployment verification must record the effective route duration and whether Fluid Compute is enabled. Capability version 2 must not be advertised if the deployed environment cannot sustain that duration. Connect/TLS failures remain independently bounded and retry policy remains idempotent through the existing operation/stage key.

Failure rules:

- encoder initialization failure does not prevent recording; it immediately lowers that session's effective Cloud deadline to the 109-second WAV-safe duration and explains that the extended Cloud format is unavailable;
- an encoder queue overflow or mid-recording error is recorded as an encoder failure, never as a silently dropped audio frame;
- below the WAV threshold, an encoder failure still submits WAV normally;
- above the WAV threshold, the provider may perform one bounded re-encode from retained PCM on the dedicated worker; if that also fails, it returns a stable retryable `managed_audio_encode_failed` error and must not send an oversized WAV;
- an unexpectedly early encoded-byte cap performs one graceful automatic stop and submits the valid encoded prefix; it does not produce a Vercel 413 or discard the entire session;
- an automatic stop caused by local transport safety is distinguished from the provider's duration limit in both logs and user copy.

The encoder path is release-blocked unless it can process continuously without causing the existing CPAL `try_send` path to drop chunks on representative low-end Windows, macOS, and Linux devices.

### 6.5 Managed-Cloud Server Validation

TalkMore must not trust the desktop timer, filename, MIME type, or `Content-Length` alone.

`/api/proxy/stt` must:

- replace the misleading 25 MiB application limit with a `4,000,000`-byte audio-part cap and reject a complete request above `4,200,000` bytes when it reaches application code;
- accept only validated 16 kHz mono PCM WAV and Ogg containing a single valid mono Opus logical stream;
- calculate WAV duration from RIFF chunks; for Ogg/Opus, sum validated packet durations from Opus TOC data and require consistency with monotonic granule positions and pre-skip rather than trusting a client-supplied final granule alone;
- reject zero, malformed, truncated, chained, multi-track, or unsupported audio before quota reservation or Groq;
- reject actual duration above 600 seconds with stable code `audio_duration_exceeded` and include `maxRecordingSeconds: 600`;
- reject an oversized audio part with stable code `audio_payload_exceeded` and include `maxUploadBytes: 4000000`;
- forward the validated file to Groq with its real filename and content type instead of naming every payload `audio.wav`;
- use the server-validated duration to size the initial reservation for both legacy dual-meter and cloud-word plans; the current fixed 30-second estimate is not sufficient for a long upload;
- configure and verify a deployed function duration of at least 210 seconds and preserve idempotent reserve/release/settle behavior across timeouts and retries;
- perform byte, container, and duration validation before reserving quota or calling Groq.

The status/account snapshot advertises a static managed capability without an additional database query. Advertisement is controlled by a global server deployment flag so the long-format feature can be disabled without removing parser compatibility:

```json
{
  "managedSttCapabilities": {
    "version": 2,
    "maxRecordingSeconds": 600,
    "maxMultipartBytes": 4200000,
    "formats": [
      {
        "mimeType": "audio/wav",
        "maxAudioBytes": 4000000,
        "preferredClientSwitchBytes": 3500000
      },
      {
        "mimeType": "audio/ogg; codecs=opus",
        "maxAudioBytes": 4000000,
        "bitrateBitsPerSecond": 48000
      }
    ]
  }
}
```

The server's 4.0 MB WAV acceptance cap is intentionally wider than the new desktop's 3.5 MB switch threshold. That preserves existing two-minute WAV clients (approximately 3.84 MB) while giving new clients more multipart and container headroom before choosing Ogg.

Managed capability is a negotiated protocol, not merely presentation metadata. The 600-second Cloud recommendation is enabled only when an authenticated server snapshot generated within the previous 24 hours advertises capability version 2 with a compatible Ogg/Opus policy. If the capability is absent, older than 24 hours, from a version-1 server, malformed, or narrower than the built-in policy, the desktop takes the lower safe result. With no fresh compatible capability, Cloud retains the historical 30-second fallback; it must never assume that an older deployment accepts long uploads.

Once a version-2 desktop has been released, TalkMore rollback policy keeps the Ogg parser and byte-compatible request handling in place even if the global flag stops advertising the extended capability. Capability withdrawal prevents new long sessions after refresh; it is not permission to remove parsing needed by already-released or already-recording clients. A newer authenticated response may shorten an active managed session for transport safety, even though ordinary provider/config changes do not otherwise mutate an in-progress deadline. If the shortened deadline has passed, the desktop performs one graceful stop with the safest payload it can submit.

## 7. Issue #83 Recording Startup Architecture

### 7.1 Startup Barrier

The microphone and STT provider start concurrently:

```text
user starts recording
  ├─ start CPAL input on its platform audio thread
  └─ connect/prepare STT provider
          ↓
wait until both report ready
          ↓
publish the fully-ready recording state
```

Audio readiness means the input stream was built and `stream.play()` succeeded. Merely spawning the capture thread is not readiness.

The existing `Preparing` acknowledgement is emitted immediately after the hotkey transition, so the UI never appears unresponsive while the barrier is pending. The microphone begins capturing as soon as its backend permits, even if the STT connection is still pending. The bounded channel preserves those initial chunks until the provider consumer is ready. The eventual recording event and timer use the original audio-ready timestamp, so elapsed time and provider limits include the buffered startup audio.

### 7.2 Buffer and Failure Rules

- channel capacity is derived from chunk duration and represents 60 seconds at the default 20 ms chunk size;
- the buffer is bounded to prevent an unresponsive provider from growing memory indefinitely;
- the combined startup barrier has a 30-second timeout, after which both sides are cancelled and the existing retryable startup error is shown;
- if audio initialization fails, cancel provider startup and return the existing microphone/device error path;
- if provider initialization fails, stop capture, release the device, and return the provider error;
- if both fail, expose one deterministic primary error and log the secondary failure without duplicate user notifications;
- cancel and stop remain valid while startup is in progress;
- no platform-specific fixed sleep is introduced.

CPAL's `stream.play()` boundary is shared by CoreAudio, WASAPI, ALSA, and PipeWire backends, but runtime behavior must still be validated on representative devices.

## 8. Account and Usage Snapshot Contract

Current scope uses the account snapshot only in the read-only status response and for managed-STT capability negotiation. Operation-returned usage snapshots and the desktop persistence/merge behavior described below are the future contract for the deferred atomic quota phase.

### 8.1 Snapshot Types

The server retains all existing flat status and proxy response fields. New clients prefer optional nested snapshots.

Conceptual account snapshot:

```json
{
  "schemaVersion": 1,
  "userId": "user-id",
  "plan": "appsumo_tier1",
  "source": "appsumo",
  "displayName": "AppSumo Tier 1",
  "subscriptionEnd": null,
  "subscriptionStatus": null,
  "licenseStatus": "active",
  "quotaModel": "cloud_words",
  "usage": {
    "periodStart": "2026-07-01T00:00:00.000Z",
    "revision": "42",
    "displayWordsUsedEstimate": 0,
    "displayWordsLimit": 0,
    "sttSecondsUsed": 0,
    "sttSecondsLimit": 0,
    "llmTokensUsed": 0,
    "llmTokensLimit": 0,
    "cloudWordsUsed": 1200,
    "cloudWordsLimit": 200000,
    "resetAt": "2026-08-01T00:00:00.000Z"
  },
  "managedSttCapabilities": {
    "version": 2,
    "maxRecordingSeconds": 600,
    "maxMultipartBytes": 4200000,
    "formats": [
      {
        "mimeType": "audio/wav",
        "maxAudioBytes": 4000000,
        "preferredClientSwitchBytes": 3500000
      },
      {
        "mimeType": "audio/ogg; codecs=opus",
        "maxAudioBytes": 4000000,
        "bitrateBitsPerSecond": 48000
      }
    ]
  },
  "generatedAt": "2026-07-22T10:00:00.000Z"
}
```

`revision` is serialized as a decimal string to avoid JavaScript integer precision problems. In the current non-atomic release, the status query returns the compatibility value `"0"` and does not reference the staged `usage_revision` column. Real monotonic revisions begin only with the deferred atomic quota cutover.

Cloud operation responses add an optional `usageSnapshot`. They do not need to repeat all entitlement fields on every request.

### 8.2 Snapshot Ordering (Deferred)

The `quota` table adds a non-null `usage_revision` bigint with default zero. Every successful mutation of displayed quota counters or limits increments it in the same transaction.

Client acceptance rules:

1. Reject snapshots whose `userId` does not match the current authenticated user.
2. Prefer a newer `periodStart` over an older period regardless of revision.
3. Within one period, accept only a greater or equal revision.
4. An equal revision may refresh metadata but cannot reduce counters.
5. Clear the current-user association on logout or session invalidation.

These rules prevent an older network response from overwriting a newer concurrent STT/LLM result.

### 8.3 Status Query

After authentication, `/api/subscription/status` performs one read-only business-data query that obtains:

- the latest relevant subscription;
- the preferred active license, or latest license when no active license exists;
- the current-period quota row if present.

Entitlement resolution remains a pure function over the returned rows. A missing quota row is represented as zero usage with entitlement-derived limits. GET must not insert or update quota.

The current query must use only pre-existing production columns and return revision `"0"`; deploying the additive atomic-meter migration is not a prerequisite for this status optimization.

Do not force an entitlement index merely because PostgreSQL chooses a sequential scan for a seven-row table. Check the combined account query with realistic data before changing subscription/license indexes. Add a `(user_id, expires_at)` operation index for the required per-user expiration/reconciliation path and verify that query with `EXPLAIN (ANALYZE, BUFFERS)` in staging.

## 9. Desktop AccountSnapshotCoordinator

The durable Rust coordinator in this section is deferred with the atomic response contract. In the current release, the existing frontend auth store owns status data, deduplicates concurrent refreshes, and refreshes once after managed output; the active-intent warming policy in Section 9.2 is implemented independently in Rust and discards its response after warming.

Rust owns a device-wide `AccountSnapshotCoordinator` shared by every Tauri window.

Responsibilities:

- load the last successful snapshot for the authenticated `userId`;
- persist only plan/quota/capability metadata, never the session token, audio, transcript, or prompt;
- expose a local `get_account_snapshot` command;
- perform `refresh_account_snapshot(reason)` with single-flight deduplication;
- track successful managed-server/Neon activity for the current process without treating a persisted timestamp as proof that Neon is still awake;
- apply newer `usageSnapshot` values received by Rust cloud providers;
- emit one `account:snapshot-updated` event after accepting a snapshot;
- mark a snapshot stale without clearing its last known values;
- clear account-scoped state on logout or invalid session.

The persisted snapshot must be keyed by user id. The UI displays it only after Better Auth identifies the same user. This avoids showing one account's plan during another account's login.

### 9.1 Refresh Policy

Refresh at:

- application startup after session identity is known;
- successful login or deep-link token authentication;
- password/session token rotation when current behavior already refreshes access;
- checkout or license activation return;
- Account/Usage page entry;
- explicit user refresh;
- main-window focus only when the last successful server snapshot is older than 30 minutes;
- the beginning of a managed Cloud recording or Ask interaction, subject to the active-intent policy below.

Do not refresh on:

- a fixed timer;
- every tray show;
- every Ask-window show;
- Capsule show/hide;
- ordinary BYOK recording;
- transport-only prewarm HEAD requests.

All triggers use one coordinator so startup, focus, account-page entry, and deep links cannot produce duplicate in-flight status requests.

### 9.2 Managed-Cloud Intent Warming

Removing five-minute polling allows Neon to suspend, which is necessary for Compute savings but can add a few hundred milliseconds to the first database-backed request after inactivity. The existing startup `HEAD /api/proxy/stt` only warms DNS/TLS/Vercel routing; the route exports no authenticated `HEAD` handler and that request does not touch Neon. It is not a database warm-up mechanism.

The coordinator therefore reuses the authenticated, read-only account snapshot query as an active-intent warm-up:

- when a signed-in user begins a TalkMore Cloud recording, start one background refresh if there has been no successful authenticated status or cloud-operation response in the current process during the previous four minutes;
- never await this refresh before opening the microphone, publishing `Preparing`, or accepting audio;
- use the normal single-flight path, so startup/session refresh and recording intent cannot duplicate the same request;
- update the local snapshot if a newer one is returned, but do not reserve quota or create a quota row;
- while the same Cloud recording remains active, reevaluate at four and eight minutes; issue a refresh only when the last successful managed activity is at least four minutes old;
- cancel future active-recording warm-ups immediately when recording stops, fails, or switches away from managed Cloud;
- when a signed-in user opens a managed Ask interaction, apply the same one-shot four-minute freshness rule; do not introduce an Ask-window interval;
- never run this policy for BYOK, local STT, direct provider uploads, an idle Capsule, tray visibility, or an unauthenticated user.

At most, a ten-minute Cloud recording generates status warm-ups near start, minute four, and minute eight, and some or all are skipped when another real cloud operation has already touched Neon. These requests are attributable to active use; an idle signed-in desktop still generates no periodic database traffic.

Warm-up failure is silent outside Account/Usage: retain cached state and continue recording. The real operation remains server-authoritative and may perform the Neon wake itself. The warm-up is an overlap optimization, never an authorization dependency.

### 9.3 Startup and Offline Behavior

- local application data and the main shell load independently of TalkMore;
- once user identity is known, a matching persisted account snapshot is applied immediately;
- the server refresh runs in the background;
- refresh failure preserves the snapshot and current cloud controls;
- a stale presentation does not authorize a cloud request; the server still decides;
- if no matching snapshot exists, Account/Home plan-dependent sections show an account-loading placeholder and suppress plan-specific upgrade claims until the server or existing authenticated data establishes the plan;
- BYOK features remain available regardless of snapshot freshness.

### 9.4 Window Behavior

- the main window owns threshold warning toasts;
- Capsule and Ask receive operation errors through their existing pipeline events;
- hidden windows may miss or delay JavaScript events without affecting correctness;
- any window that becomes visible reads the current Rust snapshot before rendering account-sensitive UI;
- the Ask WebView no longer calls `useAuthStore.initialize()` because it does not own account presentation or cloud authorization.

## 10. Cloud Quota Service

This entire section is deferred. It remains the required design for a future cutover and must not be treated as current production behavior.

### 10.1 Interface

Both legacy dual-meter plans and cloud-word plans use one conceptual quota interface:

```ts
interface QuotaService {
  reserve(context: OperationContext, estimate: UsageEstimate): Promise<Reservation>
  settle(reservation: Reservation, actual: ActualUsage): Promise<UsageSnapshot>
  release(reservation: Reservation): Promise<UsageSnapshot | null>
}
```

Implementations may differ in counters, but they share:

- server-side entitlement resolution;
- an idempotent operation/stage key;
- atomic conditional reservation;
- monotonic settlement;
- atomic release;
- a returned snapshot from the mutation itself;
- stable error envelopes.

### 10.2 Atomicity and Idempotency

For managed cloud words, `operationId` and `${operationId}:${stage}` remain the idempotency keys.

The future additive schema design includes the following fields. They are intentionally absent from the current runtime schema and migration journal until the isolated-PostgreSQL phase resumes:

- `quota.usage_revision bigint NOT NULL DEFAULT 0`;
- `cloud_usage_operation.quota_model text NOT NULL DEFAULT 'cloud_words'`;
- `cloud_usage_operation_stage.customer_meter text NOT NULL DEFAULT 'cloud_words'`, where values are `cloud_words`, `stt_seconds`, or `llm_tokens`;
- `cloud_usage_operation_stage.reserved_customer_units integer NOT NULL DEFAULT 0`;
- `cloud_usage_operation_stage.settled_customer_units integer NOT NULL DEFAULT 0`;
- `cloud_usage_operation_stage.reservation_expires_at timestamp`;
- `cloud_usage_operation_stage.replay_count integer NOT NULL DEFAULT 0`.

Existing cloud-word stages backfill the generic unit columns from `reserved_cloud_words` and the prior settled delta, then retain the old cloud-word columns for rollback compatibility until a later cleanup release. Existing rows migrate with revision/replay count zero; existing reserved stages receive an expiry derived from their creation time. The operation keeps its existing logical expiry/retention role. A `(user_id, expires_at)` operation index and a partial stage expiry index support user-scoped piggyback reconciliation without global scans.

Reserve must atomically:

- resolve or validate the current entitlement;
- create the current-period quota row when real usage first requires it;
- create or find the operation and stage;
- reject expired or invalid operations;
- conditionally reserve without exceeding the limit;
- increment revision when counters change;
- return the current snapshot.

Settle must atomically:

- find and lock the intended operation/stage;
- return the previous settlement for an already-settled retry;
- calculate only the monotonic delta;
- apply additional usage or refund excess reservation;
- update internal provider/billable counters;
- mark the stage settled;
- increment revision;
- return the current snapshot.

Release must atomically:

- do nothing for an already settled or released stage;
- refund a still-reserved amount exactly once;
- mark the stage released;
- increment revision when displayed usage changes;
- return the current snapshot when available.

The target is one business-data database round trip per state transition. Authentication may still be served by Better Auth or its existing short-lived session cache. Correctness must not depend on the cache.

### 10.3 Abandoned Operations

Do not add a periodic cleanup cron solely for usage operations.

Each reserved stage receives a 15-minute reservation expiry. The parent operation remains available for idempotency for 24 hours, and terminal operation metadata is retained for seven days before bounded piggyback deletion.

At the beginning of a real quota mutation, reconcile expired reserved stages for that user inside the active transaction:

- refund still-reserved amounts exactly once;
- mark them released;
- retain operation/stage idempotency data for the full 24-hour retry window;
- delete terminal operations older than seven days only as bounded piggyback maintenance.

State transitions are explicit:

- `reserved -> settled` applies actual usage once;
- `reserved -> released` refunds once;
- a repeated settle or release of the same terminal state returns its prior outcome;
- retrying a previously released HTTP operation must pass through a conditional `released -> reserved` transition and a new quota check before calling the upstream provider;
- settling a released stage without that re-reservation returns stable HTTP 409 `invalid_quota_transition`;
- an already-settled stage may authorize at most two same-operation transport replays during the first five minutes, matching the desktop's existing three-total-attempt retry ceiling. These replays do not charge the user twice, are still subject to rate limits, and increment a persisted replay counter. Further reuse returns HTTP 409 `operation_already_completed`. A user-initiated retry creates a new operation id.

If a user never returns, old rows do not wake compute. Cleanup may also run during an already-active administrative maintenance operation, but not through a new always-on scheduler.

## 11. Cloud Response Data Flow

This section describes the deferred operation-snapshot contract. The current release preserves existing response payloads and performs its non-blocking status refresh only after primary output is delivered.

### 11.1 Non-Streaming STT and Ask

Successful responses remain backward compatible:

```json
{
  "text": "transcription",
  "usageSnapshot": {
    "userId": "user-id",
    "periodStart": "2026-07-01T00:00:00.000Z",
    "revision": "43",
    "quotaModel": "cloud_words",
    "cloudWordsUsed": 1248,
    "cloudWordsLimit": 200000,
    "resetAt": "2026-08-01T00:00:00.000Z"
  }
}
```

Ask uses `answer` instead of `text`. Old clients ignore the optional field.

Rust parses the snapshot after it has secured the primary content. A malformed optional snapshot is logged and ignored; it cannot turn a successful transcription or answer into a failure.

### 11.2 Streaming LLM

The TalkMore proxy must not forward the upstream final marker before settlement metadata is ready.

Order:

1. forward content deltas without waiting for settlement;
2. intercept the upstream `[DONE]` marker;
3. settle usage;
4. emit an internal SSE usage event;
5. emit the final `[DONE]` marker and close.

Conceptual event:

```text
event: opentypeless_usage
data: {"usageSnapshot":{...}}

data: [DONE]
```

Existing desktop parsers ignore JSON events without an OpenRouter `choices[].delta.content` field. The new parser recognizes `opentypeless_usage`. Content streaming and first-token latency are unchanged because reserve already occurs before the upstream request and settlement remains at stream completion.

If the client cancels a stream, TalkMore settles partial usage as it does today. Because the connection is gone, no usage event can be delivered; the next successful operation or explicit account refresh corrects the device snapshot.

### 11.3 Error Responses

Stable error envelopes may include a `usageSnapshot` when server truth is available:

```json
{
  "code": "cloud_quota_exceeded",
  "error": "Cloud words used up. Please switch to BYOK mode or wait until reset.",
  "usageSnapshot": {
    "userId": "user-id",
    "periodStart": "2026-07-01T00:00:00.000Z",
    "revision": "44",
    "quotaModel": "cloud_words",
    "cloudWordsUsed": 200000,
    "cloudWordsLimit": 200000,
    "resetAt": "2026-08-01T00:00:00.000Z"
  }
}
```

The desktop applies the snapshot before showing the error. Authentication-invalid responses still clear cloud identity through the current typed invalidation path.

## 12. Failure Semantics

Status-refresh failure semantics apply to the current release. Mutation-returned snapshot and settlement synchronization semantics apply only to the deferred quota phase.

### 12.1 Status Refresh

- timeout, offline, 429, or 5xx: retain the current snapshot, mark it stale, and do not show a disruptive global error outside Account/Usage;
- 401 with the stable invalid-session code: clear cloud identity and the matching snapshot, then show the existing sign-in message;
- malformed optional fields: use compatible legacy fields or the last valid value;
- a refresh must never reset quota fields to zero merely because a new field is absent.

### 12.2 Reservation and Upstream Failure

- reservation failure occurs before upstream cost is incurred;
- quota exhaustion is non-retryable until a newer entitlement or reset is observed;
- transient database or provider connection failures use bounded retries with existing user-facing retry behavior;
- upstream failure releases the reservation idempotently;
- a release failure is logged and reconciled on the next real mutation.

### 12.3 Settlement Failure After Content Exists

User content takes priority once the upstream provider has successfully produced it.

- make three total idempotent settlement attempts, with 100 ms and 300 ms delays before the second and third attempts;
- do not discard a transcription, Ask answer, or already-streamed LLM text after those retries fail;
- return/emit `usageSyncPending: true` without presenting a false new snapshot;
- keep the conservative reservation as server truth until a later mutation reconciles it;
- mark the local snapshot stale but retain its displayed values;
- record a metric for pending settlement and operation id, without recording content.

Normal success still waits for settlement exactly as current non-streaming paths do. This fallback exists only for a late infrastructure failure.

### 12.4 Purchase and License Propagation

Checkout and license activation do not use the ordinary 30-minute freshness rule.

On return:

- refresh immediately;
- if the expected entitlement is not visible, retry at 2, 5, and 10 seconds after the immediate attempt;
- stop as soon as the expected plan/license is present;
- stop after the bounded window and keep a visible manual refresh action;
- do not keep polling after the purchase flow ends.

These rare, user-initiated requests are acceptable because they protect a high-value experience and do not create continuous idle compute.

## 13. Warning and Presentation Rules

Existing quota and error presentation remains current. Rules that depend on persisted or operation-returned snapshots are deferred with Sections 9–11.

- use one shared reducer to apply status and operation snapshots to the frontend auth/account store;
- preserve the existing quota model distinction between legacy dual meters and cloud words;
- show a 90% warning only when crossing from below the threshold to at-or-above it;
- loading a persisted snapshot at startup does not repeat the warning;
- only the main window owns proactive threshold toasts;
- a pipeline that receives an exhausted-quota error still shows its current concise Capsule/Ask error;
- Account/Usage may show `Last updated` or `Offline` when stale, but ordinary Home and recording surfaces remain uncluttered;
- no background refresh displays a blocking spinner over local features.

## 14. Cost and Performance Design

### 14.1 Expected Request Reduction

For an upgraded idle desktop:

- before: approximately 12 status calls per hour while signed in;
- after: zero fixed-interval calls;
- typical day: one startup refresh plus only meaningful focus/account/purchase actions;
- managed Cloud use may add a deduplicated status warm-up at user intent and, only for recordings longer than four minutes, at active-use boundaries;
- current-release managed operations trigger one deduplicated status refresh only after output is delivered; this replaces polling while keeping quota UI current without adding to stop-to-output latency.

Old clients continue their existing polling behavior. Compute savings therefore increase with desktop-version adoption. The server cannot safely suppress personalized old-client status responses without either stale authorization data or another durable cache.

### 14.2 Latency Rules

- main application shell readiness does not wait for the status endpoint;
- BYOK record start adds no TalkMore request;
- managed Cloud record start launches any required Neon warm-up in parallel and never waits for it before audio capture;
- managed Cloud recordings whose WAV part is at most 3.5 MB use the current WAV upload; recordings of 60 seconds or less retain the current 60-second timeout, while longer payloads use the byte-derived timeout;
- only larger managed recordings pay Opus container finalization, and normal success must not perform full-recording transcoding after the user stops;
- no object-storage upload/download hop is added to the primary path;
- the current production authorization, reservation, settlement, and release ordering is preserved in this release;
- Cloud LLM first-token and STT/Ask final-output latency are not delayed by account synchronization;
- the one post-use status refresh starts only after managed output has reached its normal completed/idle state and is non-fatal;
- directly returning snapshots from atomic mutations remains part of the deferred quota phase and will eventually remove that post-use read;
- status business data is fetched in one read-only round trip;
- the current production mutation/flush implementation is left unchanged; when the atomic phase resumes, it may remove that implementation only after real-PostgreSQL verification.

Expected managed upload sizes at the current 16 kHz mono input are:

| Duration | WAV | 48 kbit/s Ogg/Opus before small container overhead | Selected managed format |
|---:|---:|---:|---|
| 30 seconds | 0.96 MB | 0.18 MB | WAV |
| 60 seconds | 1.92 MB | 0.36 MB | WAV |
| About 109 seconds | 3.50 MB | 0.66 MB | WAV boundary |
| 10 minutes | 19.20 MB | 3.60 MB | Ogg/Opus |

On a controlled 2 Mbit/s uplink, transferring 3.6 MB takes approximately 14.4 seconds before protocol overhead; 19.2 MB would take approximately 76.8 seconds and would also be rejected by Vercel. The compressed long path therefore reduces rather than increases the dominant user-network component. Groq documents WAV as the lower-latency format, which is why short recordings do not switch.

The intent warm-up deliberately trades a small number of real-use queries for latency. [Neon's scale-to-zero documentation](https://neon.com/docs/introduction/scale-to-zero) states that an inactive compute suspends after five minutes and reactivates within a few hundred milliseconds. A short Cloud recording normally hides that wake during speech. A ten-minute recording can issue warm-ups near start, minute four, and minute eight, keeping the database ready at stop; the five-minute post-activity suspension tail is accepted only for real Cloud use, not idle app presence.

### 14.3 Measurement

Capture a pre-release baseline and compare after deployment:

- `/api/subscription/status` calls by desktop version;
- status and quota route p50/p95 duration;
- business-data statement count per route;
- Neon compute active hours and CU-hours;
- Neon suspend/resume frequency;
- quota reserve/settle/release failure counts;
- `usageSyncPending` count;
- cloud STT/LLM/Ask error rate and p95 latency;
- `record_stop_to_request_start_ms`, upload duration, server auth/quota duration, upstream STT duration, and `record_stop_to_transcript_ms`;
- `record_stop_to_final_output_ms` when Cloud LLM polish follows STT;
- warm versus cold managed request outcome, using infrastructure state only and no user content;
- managed-intent warm-up attempted/skipped/succeeded counts by reason and recording-duration bucket;
- managed encoder queue high-water mark, frame-processing p50/p95/p99, failure count, payload bytes, format selection, and capture dropped-chunk count.

Do not log tokens, audio, transcripts, questions, selected text, or prompts. Client version, route, timing, quota model, stable result code, and anonymous operation outcome are sufficient.

Success indicators:

- no five-minute cadence from upgraded clients;
- more than 90% reduction in status requests for an idle upgraded-client cohort;
- one business query after auth for status;
- no increase in business-data round trips inside the existing production quota state transitions; the one-round-trip mutation target belongs to the deferred atomic phase;
- over a seven-day version-matched cohort, cloud request success rate does not fall by more than 0.5 percentage points;
- for managed recordings of 60 seconds or less, warm and cold `record_stop_to_transcript_ms` p50 do not regress by more than the greater of 50 ms or 5%, and p95 does not regress by more than the greater of 150 ms or 10%;
- over the same cohort, Cloud LLM first-token p95 does not regress by more than the greater of 100 ms or 10% from baseline;
- on controlled 10 Mbit/s and 2 Mbit/s links, a 10-minute encoded upload completes the client-upload phase within 6 and 20 seconds p95 respectively;
- a paired, private long-form speech corpus shows no more than 0.5 percentage-point aggregate WER/CER regression from WAV and no language cohort regresses by more than 1.0 percentage point;
- a ten-minute Cloud recording has zero capture dropped chunks, encoder p99 work per 20 ms frame below 5 ms, and no more than five percentage points of average process CPU increase on the defined low-end device set;
- incremental encoding adds no more than 8 MiB peak resident memory over the same-duration managed WAV baseline, excluding test instrumentation;
- local shell readiness and BYOK recording start add zero new network dependencies;
- Neon active time trends downward as upgraded-client share increases.

The baseline must be captured from the current released desktop against the same TalkMore deployment and network profiles. Warm and scale-to-zero-cold runs are reported separately; averaging them together is not acceptable. If the short-recording, first-token, quality, or capture gates fail, the managed long-format capability remains disabled even if functional tests pass.

## 15. Rollout and Compatibility

This document is the cross-repository contract, not a requirement for one large pull request. Implementation planning must preserve the following independently testable and rollbackable phases.

Because the contract spans three independently reviewable subsystems, execution uses three plans: Issue #83 startup readiness; TalkMore account/quota efficiency; and provider-aware recording limits plus managed audio transport. The server portions of the latter two plans deploy before their desktop consumers.

### Phase 1: Additive TalkMore Compatibility Foundation

- add the nested account/usage snapshot types while retaining all existing response fields;
- add version-2 managed STT capability metadata while leaving missing metadata safe for old clients;
- lower route-level file/request limits below the deployed Vercel boundary;
- accept and validate both existing WAV and the new Ogg/Opus format, forward the correct media type/filename, and reject malformed or over-duration audio before quota work;
- configure and verify the STT function duration for the adaptive client timeout;
- add a global capability-advertisement kill switch that does not remove WAV/Ogg parser compatibility;
- make status read-only and collapse its business reads;
- preserve existing production quota mutation and response behavior;
- let upgraded desktops perform one deduplicated, non-blocking status refresh after managed output is delivered;
- expand server contract tests;
- deploy and verify old desktop clients before changing desktop polling.

Deployment order inside this phase is parser/limits with capability advertisement off, old-client verification, staging format/timeout verification, and only then production capability advertisement before the version-2 desktop release.

Rollback: old fields and route behavior remain available; new fields are optional and can be ignored.

### Phase 2: Desktop Event-Driven Account Sync

- centralize and deduplicate refresh triggers;
- remove hidden Ask auth initialization;
- remove the five-minute interval;
- add deduplicated, non-blocking managed-intent warming at Cloud recording/Ask start and active long-recording boundaries;
- retain the status endpoint for startup, account, pending-checkout focus, purchase flows, and one post-use refresh after managed output;
- make concurrent refreshes singleflight and ensure post-use refresh failure cannot remove or delay user output;
- defer Rust snapshot persistence, managed-response snapshots, and cross-window revision merging with the atomic quota response contract;
- confirm the idle upgraded-client request reduction before starting the quota rewrite.

Rollback: a desktop rollback continues using the unchanged legacy flat status fields. The server compatibility foundation remains safe for old and new clients.

### Deferred Phase 3: Atomic Quota Cutover

- add `usage_revision`, stage reservation expiry/replay count, and the operation reconciliation indexes;
- move both legacy dual-meter and cloud-word paths behind the unified quota interface;
- implement atomic reserve, settle, release, replay, and expired-reservation reconciliation;
- return snapshots directly from mutation results and remove the Phase 1 temporary final reads;
- remove contradictory in-memory batching/explicit-flush behavior;
- run real-PostgreSQL concurrency and idempotency tests before deployment.

This phase is explicitly deferred by the owner because the project is not adding an isolated database test environment now. It is not a release gate for the other phases. Do not implement the service cutover, remove the current billing implementation, or enable routes that depend on the staged schema until the required PostgreSQL tests can run outside production.

Current-release rollback: there are no atomic-usage columns to roll back because this phase is not shipped. After a future isolated-database rollout, its migration must remain additive so route handlers can return to the Phase 1 compatibility implementation without changing desktop contracts.

### Phase 4: Provider-Aware Recording Limits

- add the Rust capability registry and config migration;
- add the managed-only bundled Opus encoder worker and byte-based WAV/Ogg selector;
- expose resolved capabilities to Settings and Capsule;
- move authoritative deadlines to Rust and anchor them to audio readiness;
- add graceful warnings and auto-stop reason;
- apply the same resolver to dictation and Ask;
- negotiate capability version 2 and retain the 30-second Cloud fallback against older/malformed server capability;
- validate the managed-cloud 10-minute contract, adaptive timeout, payload limits, quality, and short-recording latency end to end.

Rollback: the provider-resolved `max_recording_seconds` mirror remains readable and safe for older versions.

### Phase 5: Issue #83 Integration and Release QA

- review and commit the existing startup-barrier implementation separately from the design commit;
- run unit, integration, build, lint, and cross-platform CI;
- test real microphone devices on Windows, macOS, and Linux;
- publish release notes that distinguish provider restrictions from the former global 30-second behavior;
- monitor before closing Issues #81 and #83.

The phases may be separate commits, pull requests, and deployments. Server compatibility must land before a desktop release that consumes snapshots. The deferred atomic quota phase must not be bundled into, or block, the polling removal and idle-Compute improvement.

## 16. Test Strategy

### 16.1 Rust Unit and Integration Tests

Provider limits:

- every supported provider resolves to the approved matrix;
- Auto returns the provider recommendation;
- Custom clamps to the hard maximum;
- Cloud takes the lower of built-in and server values;
- version-2 Cloud capability enables the negotiated Ogg limit;
- version-1, malformed, missing, or incompatible Cloud capability resolves to the historical 30-second fallback and never expands the local limit;
- a version-2 capability older than 24 hours resolves to the fallback until an authenticated refresh succeeds;
- a newer narrower managed capability can shorten, but never lengthen, an active Cloud session and produces at most one graceful stop;
- WAV/Ogg selection changes at the exact `3,500,000`-byte boundary;
- 600 seconds at the pinned encoder settings remains within the `4,000,000`-byte file cap;
- encoded-byte overflow causes one graceful stop before multipart creation;
- encoder initialization, queue, finalization, and re-encode failures follow the defined fallback/error paths without sending oversized WAV;
- adaptive timeout remains 60 seconds through 60 seconds of audio and clamps every longer managed request at 180 seconds;
- provider switches do not mutate an active session deadline;
- stale session timers cannot stop a newer recording;
- sleep/resume expiration produces one graceful stop;
- buffer guard finalizes instead of discarding audio.

Config migration:

- absent mode plus 30 seconds becomes Auto;
- absent mode plus a non-default value becomes Custom;
- invalid values become a safe Auto configuration;
- serialization retains provider-resolved `max_recording_seconds` and the independent custom intent field for downgrade compatibility.

Issue #83:

- state remains `Starting` before the ready signal;
- ready is emitted only after the backend signal;
- audio and STT futures are polled concurrently;
- the 30-second startup timeout cancels audio/provider setup exactly once;
- audio failure, STT failure, and cancellation clean up both sides;
- the bounded queue capacity is derived correctly;
- dictation and Ask share the startup helper.

Account snapshots:

- cross-user snapshots are rejected;
- newer period wins;
- lower revision cannot overwrite a higher revision;
- malformed optional snapshot does not fail successful content;
- logout/session invalidation clears account-scoped state;
- hidden-window getter returns the latest Rust value;
- Cloud recording/Ask intent starts at most one non-blocking warm-up after four minutes of managed inactivity;
- startup/status/intent refreshes are single-flight;
- minute-four and minute-eight warm-ups run only while the same Cloud recording remains active;
- stopping, failing, switching provider, logout, and BYOK sessions cancel or suppress future warm-ups;
- intent warm-up failure cannot block audio readiness or authorize a cloud request.

Cloud protocol:

- STT and Ask parse content before optional usage;
- SSE usage event is applied before final completion;
- old OpenRouter content frames remain unchanged;
- unknown SSE events remain ignorable;
- quota error snapshots are applied before error presentation.

### 16.2 Frontend Tests

Persisted-snapshot and revision-order tests in this subsection belong to the deferred phase. Fixed-polling removal, singleflight refresh, pending-checkout refresh, explicit account refresh, and post-use refresh tests remain current-release requirements.

- Settings shows Auto with the resolved provider value;
- presets above the provider maximum are absent/disabled;
- Custom validation explains and clamps invalid values;
- Cloud shows 10 minutes only with compatible version-2 capability and shows the safe fallback otherwise;
- an encoder-unavailable session explains its lowered WAV-safe limit without presenting it as an upstream provider restriction;
- GLM and Apple limits are labeled as provider/system restrictions;
- automatic stop warnings occur at the approved times;
- startup uses a matching persisted snapshot without a Free/zero flicker;
- refresh failure retains previous values;
- concurrent triggers result in one status request;
- threshold warnings fire once on crossing, not on cache hydration;
- Account entry refreshes in the background;
- checkout retry stops when the expected entitlement arrives;
- no five-minute interval exists;
- no active-recording warm-up survives after recording ends;
- Ask rendering does not initialize the auth store.

### 16.3 TalkMore Contract Tests

- old status fields and plan compatibility behavior remain unchanged;
- status with no quota row returns zero usage and performs no insert;
- the business snapshot query handles free, Pro, direct lifetime, and all AppSumo tiers;
- active, pending, refunded, and deactivated licenses resolve correctly;
- old STT, LLM, and Ask clients ignore new response fields;
- existing short WAV remains accepted with unchanged response semantics;
- WAV or Ogg duration above 600 seconds is rejected before quota or Groq;
- a valid 600-second, 48 kbit/s Ogg file is accepted below byte limits;
- WAV or Ogg above 4.0 MB, and a complete multipart request above 4.2 MB, are rejected with stable size metadata when the request reaches application code;
- malformed/truncated WAV, invalid OpusHead, non-monotonic granules, chained Ogg streams, and unsupported/multi-track audio are rejected without quota usage;
- a validated Ogg payload is forwarded to Groq as Ogg rather than renamed to WAV;
- capability version 2 exactly matches deployed parser and byte policy;
- withdrawing capability advertisement does not remove version-2 parsing required by released clients;
- SSE content order is deltas, usage event, `[DONE]`;
- stream cancellation settles partial usage without duplicate charge.

### 16.4 Real PostgreSQL Concurrency Tests

This subsection is deferred and is not part of the current release gate. It becomes mandatory before any production route switches to the atomic quota service.

Mocks are insufficient for billing correctness. Run integration tests against PostgreSQL for:

- two concurrent reservations near the quota boundary cannot oversubscribe;
- retrying the same operation/stage cannot reserve twice;
- settling twice returns the same result;
- release after settle is a no-op;
- settle after release returns `invalid_quota_transition` unless reserve first completed the conditional `released -> reserved` transition;
- a partial refund and additional charge update counters exactly once;
- revision increases with every visible mutation;
- monthly period rollover cannot accept an old-period response as current;
- expired reservations reconcile exactly once;
- transaction rollback leaves quota, operation, and stage consistent.

### 16.5 Cross-Platform Runtime Matrix

Run at least one signed/debug build on each platform with a real input device:

| Scenario | Windows | macOS | Linux |
|---|---|---|---|
| First recording after clean launch captures first spoken syllable | Required | Required | Required |
| Hold and toggle recording | Required | Required | Required |
| Ask recording startup | Required | Required | Required |
| Window hidden to tray during recording | Required | Required | Required |
| System sleep past deadline, then resume | Required | Required | Required |
| Auto-stop submits audio once | Required | Required | Required |
| Provider switch clamps UI when idle | Required | Required | Required |
| Offline/cold account snapshot behavior | Required | Required | Required |
| Cold Neon wake overlapped with Cloud recording | Required | Required | Required |
| Short Cloud WAV latency baseline | Required | Required | Required |
| Cloud 10-minute Ogg boundary and adaptive timeout | Required | Required | Required |
| Encoder CPU, queue, memory, and zero dropped chunks | Required | Required | Required |

Platform coverage:

- Windows: WASAPI through CPAL and WebView2 window lifecycle;
- macOS: CoreAudio through CPAL, WKWebView hidden-window behavior, and Apple Speech's one-minute rule;
- Linux: ALSA and/or PipeWire through CPAL and WebKitGTK lifecycle on a supported distribution.

Cross-compilation alone is not runtime verification. A Windows check blocked by a missing MSVC SDK header and an absent Linux target must not be reported as passing those platforms; CI runners or real machines are required.

## 17. Acceptance Criteria

### 17.1 Issue #81 May Be Closed When

- a Groq user on Auto is no longer stopped at 30 seconds and can record up to the approved 10-minute recommendation;
- safe custom durations are visible and enforced;
- GLM-ASR remains capped at 30 seconds with a clear provider explanation;
- Apple Speech remains capped at one minute on macOS;
- TalkMore Cloud is enforced at 10 minutes in both desktop and server;
- TalkMore Cloud uses WAV for short recordings, Ogg/Opus only when required by the managed-route byte budget, and falls back to 30 seconds against an incompatible server;
- automatic stop is Rust-authoritative, idempotent, and submits existing audio;
- hold, toggle, dictation, and Ask use the same resolver;
- Windows, macOS, and Linux runtime matrix passes;
- release notes/documentation explain Auto and provider constraints.

### 17.2 Issue #83 May Be Closed When

- the startup barrier implementation is reviewed and committed;
- recording UI does not report ready before the audio backend succeeds;
- first spoken audio is preserved while STT connects;
- device/provider failures clean up without a stuck recording state;
- focused automated tests pass;
- real-device first-recording tests pass on Windows, macOS, and Linux;
- the fixed behavior is included in a published desktop release.

### 17.3 Current Cloud Efficiency Release Is Complete When

- compatible TalkMore status/parser changes are deployed before the desktop consumer;
- upgraded desktop clients generate no fixed five-minute status traffic;
- idle upgraded clients perform no Cloud intent warm-ups, while real Cloud recording/Ask intent overlaps Neon wake-up without blocking capture;
- successful cloud operations trigger at most one deduplicated follow-up status request after output is delivered;
- status GET performs no quota write;
- production quota mutation behavior and billing formulas remain unchanged;
- purchase propagation, session invalidation, refresh singleflight, and multiple-window behavior pass UX tests;
- monitoring shows the expected request reduction without higher user-visible error rates;
- short-recording latency, first-token latency, long-upload, quality, CPU, and dropped-audio release gates pass;
- old supported desktop clients still complete status, STT, LLM, and Ask requests.

## 18. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Old clients continue to wake Neon | Optimize the server query, measure by client version, and let savings grow with upgrade adoption; do not break compatibility. |
| Removing idle polling exposes Neon scale-to-zero wake latency | Start a deduplicated authenticated warm-up at real Cloud intent, overlap it with speech, and renew only during an active long recording. |
| Cached plan is stale after external revocation | Server enforces every cloud call; quota/auth error updates or invalidates the client immediately; focus/account refresh remains. |
| Purchase webhook arrives late | Use bounded checkout-specific retries and a manual refresh path. |
| Concurrent responses regress displayed quota | Compare `periodStart` and atomic `usage_revision`. |
| Hidden WebView misses events | Rust is shared state; visible windows read through a local command. |
| Late settlement failure loses user content | Preserve content, mark usage pending, retain conservative reservation, and reconcile on later mutation. |
| Provider changes published limits | Keep a versioned Rust registry and revalidate documentation when changing it. |
| Custom endpoint has an unknown limit | Use a conservative two-minute Auto value and a client-bound custom maximum with an explicit warning. |
| Vercel rejects a 10-minute managed WAV before route code | Preserve short WAV, negotiate version-2 Ogg/Opus for long managed recordings, and keep complete multipart bodies below 4.2 MB. |
| Opus adds CPU load or changes recognition quality | Encode off the capture callback with one bundled codec, retain WAV for short audio, and block rollout on dropped-chunk, CPU, WER, and CER gates. |
| Codec packaging differs across operating systems | Pin and bundle one implementation, avoid system codecs, build all supported architectures, and run real-device packaging/runtime tests. |
| A larger upload exceeds the desktop's current 60-second timeout | Keep 60 seconds through 60 seconds of audio and use a byte-derived timeout capped at 180 seconds for longer managed uploads. |
| Server/client format rollout is out of order | Deploy parser and capability version 2 first; new desktops retain a 30-second managed fallback until compatible metadata is authenticated. |
| Atomic SQL rewrite changes billing behavior | Do not cut over in the current release; preserve existing billing formulas and operation ids, then require real PostgreSQL concurrency tests and a server-first rollout when the work resumes. |

## 19. Definition of Done

The current Issues #81/#83 and Compute-efficiency release is done only when:

- all approved product limits and migration rules are implemented;
- TalkMore Cloud 10-minute audio crosses the deployed Vercel route within the documented byte budget, while short recordings remain on WAV;
- #83 startup behavior is implemented and cross-platform verified;
- TalkMore is backward compatible and server-first deployed;
- desktop account state is event-driven and locally resilient;
- existing production quota behavior remains compatible and no route accidentally depends on the staged atomic schema;
- no fixed subscription polling remains in the upgraded desktop;
- idle Compute savings do not expose a release-gate regression in Cloud stop-to-transcript or first-token latency;
- cloud content remains responsive and is not discarded by late usage-sync failures;
- no audio/transcript content is added to Neon or telemetry;
- automated suites, production builds, contract tests, and the three-platform manual matrix pass;
- issue closure occurs only after the behavior is present in a published release.

The separate atomic-quota milestone is done only after atomic, idempotent, snapshot-returning mutations pass the specified real-PostgreSQL concurrency and replay tests. That future milestone is not part of the current release definition of done.
