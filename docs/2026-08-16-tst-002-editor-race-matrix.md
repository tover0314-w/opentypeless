# Task Report: TST-002 Editor race matrix

## Result

DONE

## Scope

- Implemented: fail-closed R01-R20 matrix binding, hostile removal/assertion-drift tests, canonical
  preflight wiring and dual-device editor/field smoke validation.
- Not implemented: Xiaomi 15 certification, live network-provider fault injection, release signing
  or real Xiaohè golden data. Those belong to their own tasks.

## Changes

- `editor_race_matrix_contract.py`: maps every normative R01-R20 scenario to concrete production-path
  JVM or Android instrumentation assertions. Missing files, methods, scenarios or critical assertions
  fail the preflight.
- `test_editor_race_matrix_contract.py`: removes each scenario's primary test in isolation and proves
  all twenty omissions are rejected; a separate mutation proves assertion drift is rejected.
- `verify_android.sh`: executes the TST-002 matrix after the editor/Rime contracts and before privacy
  gates. The matrix reuses the existing real tests instead of adding duplicate fake editor logic.

## Architecture

- contracts: scenario IDs are the exact closed set R01 through R20 from the test specification.
- state changes: none in production.
- migration: none.
- feature flag: none.

## Security & privacy

- data sent/stored: none.
- permissions/components: none added.
- threat considerations: the suite freezes switch-App/field/cursor/fingerprint, Rime/Voice ownership,
  late partial/final, cancellation, screen-off, process/session rotation, Undo/Raw tamper, no-learning,
  provider busy/fallback and route ABA behavior. Test output contains no editor body.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| TST-002 focused Python contract | PASS | 3/3; includes twenty per-scenario removal subtests and assertion drift. |
| `scripts/verify_android.sh preflight` | PASS | 120 script tests and 247 architecture tests; direct gate reports `R01-R20`. |
| immediately preceding final strict clean graph | PASS | Same source/runtime APKs: 186 tasks; Debug/Release JVM 1049/1049, lint 0 errors/8 warnings. |
| Xiaomi 10 Ultra/API33 app race classes | PASS | EditorTransactionManager + Voice session `OK (32 tests)`, code `-1`. |
| Xiaomi 10 Ultra/API33 Test Host fields | PASS | field switch, dynamic field, representative input types and WebView `OK (4 tests)`, code `-1`. |
| API35 arm64 emulator, same two matrices | PASS | Same APK bytes; 32/32 plus 4/4, both code `-1`. |

## Evidence

- Debug/Test APKs are unchanged from final RIM-009: Debug SHA-256
  `8cac35ab55e4f9d9e6705a08c41844acc1475973cf86a09c43eed9f78dc11cdd`, AndroidTest SHA-256
  `28ab8e06a1c22b4d9d1bfb9f7ddc7671c3ab92d4ad3a3c3df37f019d9e19a2aa`.
- Test Host Debug SHA-256
  `908d7582c6c668466311aeb2c124167eb5985900abcece78e499bc6970e1a4e3`; Test Host AndroidTest
  SHA-256 `a81b63a0b2eefe07b410869028a4f118d3c3a0a04941a38db8a4de554cd8cb74`.
- Default IMEs remained PangIME on Xiaomi and LatinIME on the emulator.

## Risks

- The matrix combines deterministic JVM tests with controlled editable Android instrumentation;
  it does not claim a live microphone/network outage or Xiaomi 15 manual certification.
- A Python contract proves the required tests and assertions remain present; correctness is supplied
  by the actually executed Java/Android suites, not by source-string matching alone.

## Rollback

Remove the matrix gate and its preflight hook. Production behavior and stored data are unchanged.

## Follow-ups

- `TST-005`
- `TST-010`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
