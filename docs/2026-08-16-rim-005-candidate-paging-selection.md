# Task Report: RIM-005 candidate paging and selection

## Result

DONE

## Scope

- Implemented: bounded `CandidatePage` projection, next/previous client pages, numbered candidate
  selection, exact generation/page-revision/candidate-ID/text validation, one native selection and
  one ETM composition finish.
- Not implemented: Schema option controls, persistent UserDB, real Xiaohè payload, prediction or
  Voice/Rime arbitration. Those remain RIM-006..009.

## Changes

- `NativeRimeInputEngine`: retains one bounded native candidate snapshot, exposes five candidates
  per client page, maps a validated page item back to one absolute native index and rejects replay.
- `RimeInputController`: serializes key, page and selection requests on the existing bounded worker.
- `OpenTypelessImeService`: binds candidate callbacks to the active editor generation, composition
  revision and exact displayed item; candidate interaction is locked while a key/page/selection is
  pending.
- `RimePreeditInstrumentedTest` and `TestHostInstrumentedTest`: actual-librime page/selection and
  system-selected IME evidence on both target devices.
- architecture/resource gates: candidate single-select invariants and the five final APK identities.

## Architecture

- contracts: a selection contains generation, page revision, candidate ID, index and expected text.
  Native selection is permitted exactly once and its commit text must equal the displayed item.
- state changes: page navigation changes only bounded UI state. Selection writes the chosen text as
  Rime composition and then finishes that same composition through the sole ETM authority.
- migration: none.
- feature flag: none; Rime still requires an explicitly verified local package.

## Security & privacy

- data sent/stored: no network. The synthetic package was local-only, copied to no-backup storage
  for device evidence and removed with its active state after the run.
- permissions/components: none added.
- threat considerations: stale page, duplicate click, mismatched text, editor drift, sensitive or
  no-learning policy and controller closure all fail closed. Neither the bar nor native adapter has
  `InputConnection` authority, and rejected selection never falls back to the current cursor.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| final strict clean Gradle graph | PASS | 186 tasks: 182 executed, 4 up-to-date; Debug/Release JVM each 1032/1032; Release lint and all five APKs built. |
| `scripts/verify_android.sh preflight` | PASS | 120 script tests and 221 architecture tests; RIM-001/RIM-004/RIM-005 boundary passed. |
| KSP-012 policy tests and final APK scans | PASS | 37/37 hostile tests; repository, 3 product APKs and 2 test APKs each report real Xiaohè=0 and violations=0. |
| actual librime page/select, API35 arm64 | PASS | 1/1; 12 candidates, next/previous page, select `庚`, duplicate rejected. |
| actual librime page/select, Xiaomi 10 Ultra API33 | PASS | Same final Debug/Test bytes, 1/1. |
| system-selected IME candidate contract, API35 arm64 | PASS | 1/1 in 3.697s; real test-host field, keyboard View and ETM commit. |
| system-selected IME candidate contract, Xiaomi 10 Ultra API33 | PASS | 1/1 in 3.375s with the final APKs. |
| external ADB physical touch, API35 arm64 | PASS | Fresh lifecycle with paced taps: `ni`, next page, candidate 2; host field read back exact `庚`. |

The first system-test invocation omitted its explicit package argument and was skipped by its
assumption guard; it was rerun with the required argument and the PASS results above. A fast external
tap probe was also rejected by the target guard; the fresh, paced run above is the accepted evidence.

## Evidence

- Debug APK: 65,441,588 bytes, SHA-256
  `c68eafc082f0943d66355708eac270dbd03e9b5e73600c9a0258d68168396dd4`.
- unsigned Release APK: 63,580,693 bytes, SHA-256
  `7fb0d8a15d5385ca325fd963e12f777825f386ad685549da0900a124220b1bfe`.
- app AndroidTest APK: 1,082,563 bytes, SHA-256
  `494fa5d1b767992f8c82db3af60c7d74bf4890a1b4db6a510689441563235c85`.
- test-host/Test APKs: 13,085 / 1,695,548 bytes, SHA-256
  `908d7582c6c668466311aeb2c124167eb5985900abcece78e499bc6970e1a4e3` /
  `a81b63a0b2eefe07b410869028a4f118d3c3a0a04941a38db8a4de554cd8cb74`.
- Resource policy canonical SHA-256:
  `1d5a7c2e7d62011d425fc13ef2e6e6a3365e6db90aebac8573c3330a697fbcc2`.
- Synthetic local device package: 2,238 bytes, SHA-256
  `6be987e8d28a3fe13158972fbba0d041d8187760f75b0ec30eaf2a202f018bf1`;
  device cleanup read back zero retained package/active-resource paths.

## Risks

- Paging is intentionally over the bounded native snapshot (currently at most 16 candidates), not
  an unbounded dictionary result set. Larger native-page traversal can be added only with the same
  identity and backpressure contract.
- Release remains unsigned verification evidence. No real Xiaohè package is bundled; personal use
  still requires a user-selected local package.

## Rollback

Remove the page callbacks and selection route to return to preedit-only RIM-004. No persisted format
or product resource is changed.

## Follow-ups

- `RIM-006`
- `RIM-007`
- `RIM-008`
- `RIM-009`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
