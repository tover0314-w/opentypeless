# Issue #83 Recording Startup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the first spoken audio by starting CPAL capture and STT preparation concurrently and publishing recording readiness only after both are ready.

**Architecture:** `AudioCaptureHandle` owns a one-shot backend-readiness signal fired immediately after `stream.play()`. A small audio-module startup barrier awaits audio and STT concurrently with a 30-second timeout. Dictation and Ask use the same barrier and clean up both sides on every failure.

**Tech Stack:** Rust 2021, CPAL 0.15, Tokio, Tauri 2, existing `SttProvider` trait.

## Global Constraints

- Do not add a platform-specific sleep.
- Preserve a bounded 60-second queue at 20 ms chunks; CPAL still uses non-blocking `try_send`.
- Audio readiness means `stream.play()` succeeded.
- Startup timeout is exactly 30 seconds.
- Error order is deterministic: audio error, then STT error, then timeout.
- The same behavior is required for dictation and Ask on Windows, macOS, and Linux.
- Existing uncommitted changes in the four listed Rust files are the starting implementation and must be reviewed rather than overwritten.

---

### Task 1: Make Audio Backend Readiness Explicit

**Files:**
- Modify: `src-tauri/src/audio/capture.rs`
- Test: `src-tauri/src/audio/capture.rs`

**Interfaces:**
- Produces: `AudioCaptureHandle::wait_until_ready(&mut self) -> anyhow::Result<()>`
- Produces: `CaptureState::{Starting, Recording, Idle}`
- Produces: `audio_channel_capacity(&AudioConfig) -> usize`

- [ ] **Step 1: Keep the failing readiness and queue tests**

```rust
#[test]
fn capture_does_not_report_recording_before_the_backend_is_ready() {
    assert_eq!(initial_capture_state(), CaptureState::Starting);
}

#[test]
fn audio_queue_preserves_a_minute_while_the_provider_connects() {
    assert_eq!(audio_channel_capacity(&AudioConfig::default()), 3_000);
}

#[tokio::test]
async fn capture_startup_waits_for_the_backend_ready_signal() {
    let (_notifier, waiter) = capture_startup_channel();
    assert!(tokio::time::timeout(
        std::time::Duration::from_millis(20),
        waiter.wait(),
    ).await.is_err());
}
```

- [ ] **Step 2: Run the focused tests**

Run: `cd src-tauri && cargo test audio::capture::tests -- --nocapture`

Expected before implementation: readiness/state/capacity tests fail to compile or fail assertions.

- [ ] **Step 3: Implement the one-shot backend signal**

```rust
const AUDIO_CHANNEL_BUFFER_DURATION_MS: u32 = 60_000;

fn audio_channel_capacity(config: &AudioConfig) -> usize {
    AUDIO_CHANNEL_BUFFER_DURATION_MS.div_ceil(config.chunk_duration_ms.max(1)) as usize
}

pub async fn wait_until_ready(&mut self) -> Result<()> {
    let waiter = self.startup_waiter.take()
        .ok_or_else(|| anyhow::anyhow!("Audio capture readiness was already consumed"))?;
    waiter.wait().await.map_err(anyhow::Error::msg)
}
```

Set initial state to `Starting`, call `startup_notifier.ready()` only after `stream.play()?`, and set `Idle` plus send the error if the capture thread exits before readiness.

- [ ] **Step 4: Re-run the focused tests**

Run: `cd src-tauri && cargo test audio::capture::tests -- --nocapture`

Expected: all capture readiness tests pass.

### Task 2: Add the Shared Concurrent Startup Barrier

**Files:**
- Modify: `src-tauri/src/audio/mod.rs`
- Test: `src-tauri/src/audio/mod.rs`

**Interfaces:**
- Produces: `STARTUP_TIMEOUT: Duration`
- Produces: `RecordingStartupError<AudioError, SttError>::{Audio, Stt, Timeout}`
- Produces: `await_recording_startup(audio_ready, stt_ready)`

- [ ] **Step 1: Add tests for concurrency, deterministic errors, and timeout**

```rust
#[tokio::test]
async fn startup_times_out_once() {
    let pending = std::future::pending::<Result<(), &'static str>>();
    let result = await_recording_startup(pending, pending).await;
    assert_eq!(result, Err(RecordingStartupError::Timeout));
}

#[tokio::test]
async fn audio_error_wins_when_both_are_ready_with_errors() {
    let result = await_recording_startup(
        async { Err::<(), _>("audio") },
        async { Err::<(), _>("stt") },
    ).await;
    assert_eq!(result, Err(RecordingStartupError::Audio("audio")));
}
```

Use a test-only timeout of 20 ms through an internal `await_recording_startup_with_timeout` helper so the public production constant remains 30 seconds.

- [ ] **Step 2: Run the audio-module tests**

Run: `cd src-tauri && cargo test audio::tests -- --nocapture`

Expected before timeout support: timeout/variant tests fail.

- [ ] **Step 3: Implement the timeout barrier**

```rust
pub(crate) const STARTUP_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum RecordingStartupError<A, S> {
    Audio(A),
    Stt(S),
    Timeout,
}

async fn await_recording_startup_with_timeout<AF, SF, A, S>(
    audio_ready: AF,
    stt_ready: SF,
    timeout: Duration,
) -> Result<(), RecordingStartupError<A, S>>
where
    AF: Future<Output = Result<(), A>>,
    SF: Future<Output = Result<(), S>>,
{
    tokio::time::timeout(timeout, async {
        tokio::try_join!(
            async { audio_ready.await.map_err(RecordingStartupError::Audio) },
            async { stt_ready.await.map_err(RecordingStartupError::Stt) },
        )?;
        Ok(())
    }).await.unwrap_or(Err(RecordingStartupError::Timeout))
}
```

The public crate helper delegates with `STARTUP_TIMEOUT`.

- [ ] **Step 4: Re-run the tests**

Run: `cd src-tauri && cargo test audio::tests -- --nocapture`

Expected: concurrency, error-order, and timeout tests pass.

### Task 3: Use the Barrier in Dictation and Ask

**Files:**
- Modify: `src-tauri/src/pipeline.rs`
- Modify: `src-tauri/src/commands/ask.rs`
- Test: existing Rust unit tests in both modules

**Interfaces:**
- Consumes: `AudioCaptureHandle::wait_until_ready`
- Consumes: `await_recording_startup`
- Produces: unchanged Tauri commands and event names

- [ ] **Step 1: Confirm both call sites fail to handle `Timeout`**

Run: `cd src-tauri && cargo test --lib`

Expected after Task 2 and before call-site updates: exhaustive-match compile failures for `RecordingStartupError::Timeout`.

- [ ] **Step 2: Map timeout and clean up both sides**

Use this mapping in dictation and Ask:

```rust
match error {
    RecordingStartupError::Audio(error) => map_audio_capture_error(&error.to_string()),
    RecordingStartupError::Stt(error) => error.to_string(),
    RecordingStartupError::Timeout =>
        "Recording startup timed out after 30 seconds. Please try again.".to_string(),
}
```

On every startup error: call `handle.stop()`, drop/cancel the provider connection future, clear preloaded state, and restore `PipelineState::Idle`. Do not emit duplicate errors.

- [ ] **Step 3: Run the complete Rust suite**

Run: `cd src-tauri && cargo test --lib`

Expected: all Rust tests pass.

- [ ] **Step 4: Run formatting and lint checks**

Run: `cd src-tauri && cargo fmt --check`

Run: `cd src-tauri && cargo clippy --all-targets --all-features -- -D warnings`

Expected: both exit 0.

- [ ] **Step 5: Verify the frontend contract remains intact**

Run: `npm test -- --run`

Run: `npm run build`

Expected: Vitest and TypeScript/Vite build pass without event-contract changes.

- [ ] **Step 6: Commit only the four #83 files**

```bash
git add src-tauri/src/audio/capture.rs src-tauri/src/audio/mod.rs \
  src-tauri/src/commands/ask.rs src-tauri/src/pipeline.rs
git commit -m "fix: preserve audio during recording startup"
```

### Task 4: Cross-Platform Runtime Gate

**Files:**
- Modify only if needed: `.github/workflows/ci.yml`
- Evidence: release/check logs, not committed audio

**Interfaces:**
- Produces: runtime evidence required to close Issue #83

- [ ] **Step 1: Confirm CI compilation on all three OS families**

Run the existing `check-rust` matrix for `windows-latest`, `macos-latest`, and `ubuntu-latest`.

Expected: all targets compile and test; a missing local cross SDK is not treated as a platform pass.

- [ ] **Step 2: Run real-device first-recording checks**

On each OS, test clean launch, immediate speech after hotkey press, hold/toggle modes, Ask recording, device denial, and provider failure.

Expected: first syllable is present, UI never remains stuck in preparing/recording, and one error is shown.

- [ ] **Step 3: Record issue evidence**

Attach OS/build/device matrix results to Issue #83 and close only after a published desktop release contains the commit.
