# Task Report: RIM-004 key processing and preedit

## Result

DONE

## Scope

- Implemented: bind the selected local Rime package to the product IME lifecycle; process bounded
  printable ASCII and backspace events; expose Unicode preedit/candidate snapshots; map preedit to
  the existing generation-bound Composition/ETM path; enable the explicit Latin/Rime switch only
  when a verified local package exists.
- Not implemented: candidate paging/selection, Schema/option UI, durable UserDB policy, real Xiaohè
  payload, or Voice/Rime arbitration. Those remain RIM-005..009.

## Changes

- `NativeRimeInputEngine`, `RimeInputController`: actual librime adapter, bounded lifecycle and
  synchronous generation/revision guards with content-free failures.
- `RimeResourceStore`: verified active package projection for runtime use; no bundled resource and
  no network path.
- `OpenTypelessImeService`: Rime activation/deactivation, key/backspace routing, preedit rendering
  and Latin/Rime switch. Expected caret is registered before the framework composing write so an
  OEM synchronous selection callback cannot revoke the IME's own lease.
- editor host façade: Rime composition start/update/finish remains target-bound and writes only
  through the single `EditorTransactionManager` authority.
- contract and instrumentation tests: actual native `n -> ni -> backspace`, synchronous selection
  callbacks, stale target rejection and system-selected IME touch evidence.

## Architecture

- contracts: every engine request carries editor generation and coordination revision; snapshots
  are bounded, immutable and redacted. Candidate identity remains revision-bound for RIM-005.
- state changes: a session activates only from the current verified no-backup package. Switching to
  Latin, finishing input, sensitive/no-learning policy or target drift closes the controller and
  clears preedit.
- migration: none.
- feature flag: none. Rime availability derives only from a verified active local package.

## Security & privacy

- data sent/stored: no network. The temporary synthetic package was copied to no-backup storage,
  used only for device evidence and removed after each run. Diagnostics contain no preedit,
  candidate or native error body.
- permissions/components: none added.
- threat considerations: the adapter never holds `InputConnection`; stale generation, selection,
  synchronous callback, sensitive/no-learning field, closed controller and over-limit text all
  fail closed. Product APKs contain native engine bytes but zero real Xiaohè resources.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| final strict clean Gradle graph | PASS | 186 tasks: 181 executed, 5 up-to-date; Debug/Release JVM each 1029/1029; Release lint and all five APKs built. |
| `scripts/verify_android.sh preflight` | PASS | 120 script tests and 217 architecture tests; RIM-001/RIM-004 source boundary passed. |
| KSP-012 resource policy | PASS | 37/37 hostile tests; repository, 3 product APKs and 2 test APKs all violations=0 and real Xiaohè=0. |
| final API 35 arm64 native/ETM | PASS | actual librime preedit/candidate/backspace 1/1; fresh exact target Rime façade 1/1. |
| final Xiaomi 10 Ultra API 33 native/ETM | PASS | same final Debug/Test APKs; actual librime 1/1 and exact target façade 1/1. |
| final system-selected IME touch chain, API 35 arm64 | PASS | external ADB touch: Latin `a`, switch to Chinese, `an -> ani`, backspace to `an`; served real test-host field and real keyboard View. |
| system touch injection, Xiaomi | NOT RUN | HyperOS rejected ADB touch before even focusing a plain host field (`mInputShown=false`); this is not counted as product PASS. Native and ETM device gates passed. |

## Evidence

- Debug APK: 65,441,588 bytes, SHA-256
  `f5b11a16777c7b40620ef7bc40a6769fdd76ac781c18db69404758c9004c0f72`.
- unsigned Release APK: 63,580,349 bytes, SHA-256
  `aa59203701b1d42873a7b8ea9fcfc5af9413822ec9940d4dc6ccb5c0e1eaecdd`.
- AndroidTest APK: 1,081,423 bytes, SHA-256
  `461b379dc4ae10a002cb9bbc46babc651e03d934410a1c455723d5a4acaf5553`.
- test-host/Test APKs: 13,085 / 1,694,620 bytes, SHA-256
  `908d7582c6c668466311aeb2c124167eb5985900abcece78e499bc6970e1a4e3` /
  `d0b293479a6ad4ef4a15b8c0ec361f6546d6cc5e2f3c908e549bd1c19f28aa08`.
- Resource policy SHA-256: `c9a352ec194924579b34c12faf83287221df8671aaeece9d573a72ddd8e5b466`.
- Synthetic local device package: 2,163 bytes, SHA-256
  `06826aef514b2072073401bc883d7a2fe4fe3ad9a0c3fb3726806e327077ff17`;
  it was never packaged and was deleted from active device storage after validation.

## Risks

- Rime preedit is usable, but candidate selection is not yet connected. RIM-005 remains the next
  personal-use blocker.
- Xiaomi requires one physical user tap run for a full on-device system-UI observation because its
  current developer settings reject injected touch. This does not block the already executed
  native and editor-authority tests.
- Release is unsigned verification evidence, not a distributable release.

## Rollback

Remove the Rime runtime/controller wiring and restore Latin-only engine selection. Imported local
packages remain removable from Settings; no persistent format migration is involved.

## Follow-ups

- `RIM-005`
- `RIM-006`
- `RIM-007`
- `RIM-008`
- `RIM-009`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
