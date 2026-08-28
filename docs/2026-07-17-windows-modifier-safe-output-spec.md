# Windows Modifier-Safe Output Repair Specification and Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent Windows text injection from activating Ctrl/Shift/Alt/Win shortcuts when a dictation hotkey is still physically held as output begins.

**Architecture:** Add one platform-aware modifier-release guard under `src-tauri/src/output/`. On Windows it polls the high-order `GetAsyncKeyState` bit for Shift, Control, Alt, and both Windows keys for at most 500 ms; on other platforms it is a no-op. Every path that synthesizes text or a paste shortcut calls the guard immediately before injection, and a timeout degrades to the existing clipboard fallback instead of emitting corrupted keystrokes.

**Tech Stack:** Rust 2021, Tauri 2, `windows-sys` 0.59, Enigo 0.2.1, Cargo unit tests.

## Global Constraints

- Preserve existing hotkey semantics: toggle mode still starts and stops on `ShortcutState::Pressed`; hold mode still stops on release.
- Do not synthesize modifier key-up events, because doing so would override a modifier the user is intentionally holding.
- Wait at most 500 ms, polling every 10 ms, before choosing the safe fallback.
- Inspect only the high-order `GetAsyncKeyState` bit; the low-order “pressed since last query” bit is explicitly unreliable.
- Guard all automated Windows input paths: Enigo text, explicit Windows SendInput, and simulated clipboard paste.
- Keep macOS and Linux behavior unchanged through a non-Windows no-op implementation.
- Add no new dependency; `windows-sys` already enables `Win32_UI_Input_KeyboardAndMouse`.

---

## Problem Statement and Root Cause

GitHub issue [#80](https://github.com/tover0314-w/opentypeless/issues/80) reports intermittent corrupted text on Windows 11 with toggle mode, keyboard simulation, and `Ctrl+Space`. The transcript stored in history is correct, while Firefox/Zen Browser receives Markdown shortcut effects such as bold and italic markers.

The relevant event sequence is:

1. The second `Ctrl+Space` produces a Windows `WM_HOTKEY` pressed event.
2. `recording_shortcut_action()` maps a toggle-mode pressed event to `RecordingShortcutAction::Stop` without waiting for release.
3. A fast STT response reaches `KeyboardOutput::type_text()` while Ctrl can still be physically down.
4. Enigo 0.2.1 emits Windows Unicode keyboard events through `SendInput` without checking existing modifier state.
5. The target application interprets affected letters as Ctrl shortcuts even though the Unicode payload itself is correct.

This is consistent with Microsoft's `SendInput` contract: the API does not reset current keyboard state, already-pressed keys can interfere, and callers should inspect `GetAsyncKeyState`. The reference is <https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput>.

The existing 60 ms delay in `PipelineHandle::stop()` is conditional on `selected_text_enabled`; the default is `false`, so it does not protect normal keyboard output. Moving a fixed sleep into the pipeline would also leave explicit SendInput, clipboard paste, streaming insertion, and future direct callers vulnerable.

## Acceptance Criteria

- When Ctrl is down at the start of Windows output and becomes up within 500 ms, no synthetic text/paste event is sent until it is up.
- Shift, Alt, left Win, and right Win receive the same protection.
- When a modifier remains down for 500 ms, direct keyboard/SendInput output returns an `AppError::Output`; the existing strategy fallback copies the full text rather than typing it under the modifier.
- When clipboard paste is the active/fallback strategy and a modifier remains down, the paste shortcut is skipped and the existing `CopiedFallback` result leaves the full text on the clipboard.
- When no modifier is down, the guard does not sleep.
- macOS and Linux retain their current behavior.
- Existing Rust tests, Clippy, formatting, and the frontend test suite continue to pass.

### Task 1: Shared Windows modifier-release guard

**Files:**
- Create: `src-tauri/src/output/windows_modifier_guard.rs`
- Modify: `src-tauri/src/output/mod.rs`
- Test: `src-tauri/src/output/windows_modifier_guard.rs`

**Interfaces:**
- Consumes: `crate::error::AppError`; Windows `GetAsyncKeyState`, `VK_SHIFT`, `VK_CONTROL`, `VK_MENU`, `VK_LWIN`, and `VK_RWIN`.
- Produces: `pub fn wait_for_modifier_release() -> Result<(), AppError>` for runtime callers; private `wait_until_modifiers_released()` for deterministic unit tests.

- [ ] **Step 1: Add tests before the helper exists**

Add `pub mod windows_modifier_guard;` beside the existing output modules in `src-tauri/src/output/mod.rs`. Create `src-tauri/src/output/windows_modifier_guard.rs` with only this test module:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    #[test]
    fn waits_until_pressed_modifiers_are_released() {
        let mut states = VecDeque::from([true, true, false]);
        let mut sleeps = 0;

        let released = wait_until_modifiers_released(
            || states.pop_front().expect("probe called too many times"),
            || sleeps += 1,
            50,
        );

        assert!(released);
        assert_eq!(sleeps, 2);
    }

    #[test]
    fn times_out_when_modifier_remains_pressed() {
        let mut checks = 0;
        let mut sleeps = 0;

        let released = wait_until_modifiers_released(
            || {
                checks += 1;
                true
            },
            || sleeps += 1,
            2,
        );

        assert!(!released);
        assert_eq!(checks, 3);
        assert_eq!(sleeps, 2);
    }

    #[test]
    fn returns_immediately_when_no_modifier_is_pressed() {
        let mut sleeps = 0;

        let released = wait_until_modifiers_released(|| false, || sleeps += 1, 50);

        assert!(released);
        assert_eq!(sleeps, 0);
    }
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
cargo test --manifest-path src-tauri/Cargo.toml output::windows_modifier_guard::tests
```

Expected: compilation fails because `wait_until_modifiers_released` does not exist. This is the expected missing-behavior failure, not a syntax or fixture failure.

- [ ] **Step 3: Implement the smallest shared guard**

Replace the new file with:

```rust
use crate::error::AppError;

#[cfg(target_os = "windows")]
const MODIFIER_RELEASE_POLL_INTERVAL_MS: u64 = 10;
#[cfg(target_os = "windows")]
const MODIFIER_RELEASE_MAX_WAIT_CYCLES: usize = 50;

#[cfg(any(target_os = "windows", test))]
fn wait_until_modifiers_released(
    mut any_modifier_is_down: impl FnMut() -> bool,
    mut pause: impl FnMut(),
    max_wait_cycles: usize,
) -> bool {
    for cycle in 0..=max_wait_cycles {
        if !any_modifier_is_down() {
            return true;
        }
        if cycle < max_wait_cycles {
            pause();
        }
    }
    false
}

#[cfg(target_os = "windows")]
fn any_modifier_is_down() -> bool {
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
        GetAsyncKeyState, VK_CONTROL, VK_LWIN, VK_MENU, VK_RWIN, VK_SHIFT,
    };

    [VK_SHIFT, VK_CONTROL, VK_MENU, VK_LWIN, VK_RWIN]
        .into_iter()
        .any(|virtual_key| unsafe { GetAsyncKeyState(virtual_key as i32) } < 0)
}

#[cfg(target_os = "windows")]
pub fn wait_for_modifier_release() -> Result<(), AppError> {
    let released = wait_until_modifiers_released(
        any_modifier_is_down,
        || {
            std::thread::sleep(std::time::Duration::from_millis(
                MODIFIER_RELEASE_POLL_INTERVAL_MS,
            ));
        },
        MODIFIER_RELEASE_MAX_WAIT_CYCLES,
    );

    if released {
        Ok(())
    } else {
        Err(AppError::Output(
            "Windows modifier keys remained pressed for 500 ms; skipped simulated input"
                .to_string(),
        ))
    }
}

#[cfg(not(target_os = "windows"))]
pub fn wait_for_modifier_release() -> Result<(), AppError> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    #[test]
    fn waits_until_pressed_modifiers_are_released() {
        let mut states = VecDeque::from([true, true, false]);
        let mut sleeps = 0;

        let released = wait_until_modifiers_released(
            || states.pop_front().expect("probe called too many times"),
            || sleeps += 1,
            50,
        );

        assert!(released);
        assert_eq!(sleeps, 2);
    }

    #[test]
    fn times_out_when_modifier_remains_pressed() {
        let mut checks = 0;
        let mut sleeps = 0;

        let released = wait_until_modifiers_released(
            || {
                checks += 1;
                true
            },
            || sleeps += 1,
            2,
        );

        assert!(!released);
        assert_eq!(checks, 3);
        assert_eq!(sleeps, 2);
    }

    #[test]
    fn returns_immediately_when_no_modifier_is_pressed() {
        let mut sleeps = 0;

        let released = wait_until_modifiers_released(|| false, || sleeps += 1, 50);

        assert!(released);
        assert_eq!(sleeps, 0);
    }
}
```

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run:

```bash
cargo test --manifest-path src-tauri/Cargo.toml output::windows_modifier_guard::tests
```

Expected: 3 passed, 0 failed.

- [ ] **Step 5: Commit the independently tested guard**

```bash
git add src-tauri/src/output/mod.rs src-tauri/src/output/windows_modifier_guard.rs
git commit -m "fix: wait for Windows modifiers before output"
```

### Task 2: Gate every simulated output path

**Files:**
- Modify: `src-tauri/src/output/keyboard.rs`
- Modify: `src-tauri/src/output/windows_sendinput.rs`
- Modify: `src-tauri/src/output/clipboard.rs`
- Test: `src-tauri/src/output/windows_modifier_guard.rs`

**Interfaces:**
- Consumes: `super::windows_modifier_guard::wait_for_modifier_release() -> Result<(), AppError>`.
- Produces: unchanged `TextOutput` and clipboard APIs; only injection timing and timeout fallback behavior change.

- [ ] **Step 1: Gate Enigo text before creating/sending its first event**

At the first line of `type_text_sync()` in `src-tauri/src/output/keyboard.rs`, add:

```rust
    super::windows_modifier_guard::wait_for_modifier_release()?;
```

The function then continues with its existing `Enigo::new()` call.

- [ ] **Step 2: Gate explicit SendInput before handling the first character**

In the Windows implementation of `type_unicode_chunk_with_options()` in `src-tauri/src/output/windows_sendinput.rs`, keep the empty-text fast path first, then add:

```rust
    super::windows_modifier_guard::wait_for_modifier_release()
        .map_err(|error| TypeError::SendInputFailed(error.to_string()))?;
```

This preserves the function's existing `Result<usize, TypeError>` interface and reports zero inserted characters on timeout.

- [ ] **Step 3: Gate simulated clipboard paste while preserving copy-only fallback**

At the first line of the non-macOS `simulate_paste()` implementation in `src-tauri/src/output/clipboard.rs`, add:

```rust
    super::windows_modifier_guard::wait_for_modifier_release()?;
```

On Windows timeout, the caller already logs the paste failure, leaves the requested text on the clipboard, and returns `InsertStatus::CopiedFallback`. On Linux the shared guard returns immediately.

- [ ] **Step 4: Run targeted output tests**

Run:

```bash
cargo test --manifest-path src-tauri/Cargo.toml output::
```

Expected: all output-module tests pass, including 3 modifier-guard tests.

- [ ] **Step 5: Commit the call-site integration**

```bash
git add src-tauri/src/output/keyboard.rs src-tauri/src/output/windows_sendinput.rs src-tauri/src/output/clipboard.rs
git commit -m "fix: guard Windows text injection from held shortcuts"
```

### Task 3: Full regression and platform validation

**Files:**
- Verify: `src-tauri/src/output/windows_modifier_guard.rs`
- Verify: `src-tauri/src/output/keyboard.rs`
- Verify: `src-tauri/src/output/windows_sendinput.rs`
- Verify: `src-tauri/src/output/clipboard.rs`

**Interfaces:**
- Consumes: the repository's existing Cargo, Clippy, Rustfmt, Vitest, ESLint, and frontend build commands.
- Produces: fresh evidence that the fix is formatted, lint-clean, test-clean, and buildable without frontend regressions.

- [ ] **Step 1: Format and verify formatting**

```bash
cargo fmt --manifest-path src-tauri/Cargo.toml --all
cargo fmt --manifest-path src-tauri/Cargo.toml --all -- --check
```

Expected: both commands exit 0; the check prints no diff.

- [ ] **Step 2: Run the full Rust test suite**

```bash
cargo test --manifest-path src-tauri/Cargo.toml
```

Expected: 0 failed.

- [ ] **Step 3: Run Clippy with warnings denied**

```bash
cargo clippy --manifest-path src-tauri/Cargo.toml --all-targets -- -D warnings
```

Expected: exit 0 with no warnings.

- [ ] **Step 4: Type-check the Windows-specific implementation when the target is installed**

First inspect installed targets:

```bash
rustup target list --installed
```

If `x86_64-pc-windows-msvc` is present, run:

```bash
cargo check --manifest-path src-tauri/Cargo.toml --target x86_64-pc-windows-msvc
```

Expected: exit 0. If the target is absent, record that limitation explicitly and rely on the pure guard tests plus the repository's Windows CI for the final platform compile.

- [ ] **Step 5: Run frontend regression checks**

```bash
npm test -- --run
npm run lint
npm run build
```

Expected: all commands exit 0.

- [ ] **Step 6: Inspect the final diff for scope and safety**

```bash
git diff --check
git diff -- src-tauri/src/output/mod.rs src-tauri/src/output/keyboard.rs src-tauri/src/output/windows_sendinput.rs src-tauri/src/output/clipboard.rs
sed -n '1,420p' docs/2026-07-17-windows-modifier-safe-output-spec.md
sed -n '1,220p' src-tauri/src/output/windows_modifier_guard.rs
```

Expected: no whitespace errors; no unrelated tracked files changed; no synthesized modifier key-up events; every automated input call site invokes the shared guard.
