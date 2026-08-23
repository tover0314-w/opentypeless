# Task Report: RIM-009 Rime and Voice conflict policy

## Result

DONE

## Scope

- Implemented: deterministic Rime-to-Voice preemption, default commit-visible-Rime policy, explicit
  cancel path, exact owner hand-off, failure classification and dual-device validation.
- Not implemented: a user-facing conflict-policy setting, real Xiaohè corpus validation, network
  recognition changes or general multi-composition arbitration. Those remain separate tasks.

## Changes

- `OpenTypelessImeService`: releases the exact active Rime composition before capturing a Voice
  target. Commit and cancel both use the original generation, selection and composition revision;
  uncertain release remains fail-closed and does not start Voice.
- `RimeVoicePreemption`: owns the single coordinator preemption ticket, publishes Voice exactly once
  only after physical Rime release proof, restores the exact Rime observation when unchanged and
  cancels an unclaimed Voice owner if target capture fails.
- editor-host instrumentation: proves the physical commit result `prenivoice`, cancel result
  `prevoice`, no overlapping composition and one exact Rime-to-Voice coordinator hand-off.
- architecture gate: rejects target capture before Rime release, policy bypass, direct editor
  writers/current-cursor fallback, pending candidate/key preemption and uncertain release presented
  as success.

## Architecture

- contracts: `CompositionConflictPolicy.rimeToVoiceDecision()` is the only policy source;
  `RimeVoicePreemption` is a one-shot transaction over one coordinator ticket.
- state changes: Rime owner -> pending preemption -> either Voice owner, restored Rime owner or
  unresolved fail-closed pending state. Voice and Rime are never published simultaneously.
- migration: none.
- feature flag: none. The existing default policy commits visible Rime text before Voice starts;
  the reviewed cancel directive is retained as an explicit policy branch.

## Security & privacy

- data sent/stored: no new data, network, persistence or diagnostics. The transition only changes
  ownership of already-bounded local composition state.
- permissions/components: none added.
- threat considerations: pending Rime key/candidate work blocks preemption; editor target capture
  happens only after exact release; rejection and uncertainty never fall back to the current cursor.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| final strict offline clean Gradle graph | PASS | 186 tasks: 181 executed, 5 up-to-date; Debug/Release JVM 1049/1049 each; lint 0 errors/8 warnings; five APKs built. |
| `scripts/verify_android.sh preflight` with JDK 17 | PASS | 120 script tests and 244 architecture tests, including the 9-case RIM-009 hostile gate. |
| resource-policy verify and final APK scans | PASS | Repository, 3 product APKs and 2 test APKs: real Xiaohè=0, forbidden resources=0, violations=0. |
| Xiaomi 10 Ultra/API33 instrumentation | PASS | Exact two-class matrix `OK (32 tests)`, runner code `-1`. |
| API35 arm64 emulator instrumentation | PASS | Same final Debug/Test bytes, `OK (32 tests)`, runner code `-1`. |

One early combined Python command used incorrect module search paths and executed zero tests; the
same focused suites were rerun with explicit `PYTHONPATH` and passed 46/46. The first preflight call
also stopped before tests because `JAVA_HOME` was absent; the identical command was rerun with the
project JDK 17 and passed as recorded above.

## Evidence

- Debug APK: 65,462,940 bytes, SHA-256
  `8cac35ab55e4f9d9e6705a08c41844acc1475973cf86a09c43eed9f78dc11cdd`.
- unsigned Release APK: 63,602,041 bytes, SHA-256
  `8d279bcfc3523ac88833fffbafa3e6db3c150e4baa738d16a93a6851b178d39b`.
- app AndroidTest APK: 1,087,383 bytes, SHA-256
  `28ab8e06a1c22b4d9d1bfb9f7ddc7671c3ab92d4ad3a3c3df37f019d9e19a2aa`.
- resource policy canonical SHA-256:
  `d4ebd8d3a62deff52b82b1286d27a677a586e7712ad6c0ee6166d0c1f509e3f1`.
- product scan: 3 artifacts, 122 members, 16 exact native engines, violations=0; manifest SHA-256
  `9ed59173833f2786d7cc7171192ef87b5e744a329fb8eb07cf9cfecc7763a44f`.
- test scan: 2 artifacts, 38 members, violations=0; manifest SHA-256
  `ab7f8bc854791adb147b89099540643b9dab4a3abbad782b047a833b122c3ca4`.
- device default IMEs remained PangIME on Xiaomi and LatinIME on the emulator.

## Risks

- The device matrix uses the production editor-host path with controlled editable connections; it
  does not operate a live microphone or claim a full system-selected IME Voice UX session.
- Release is unsigned validation evidence.
- Real Xiaohè compatibility remains blocked by the missing user-authorized RIM-008 package/corpus.

## Rollback

Remove the Rime preemption seam and restore the previous fail-closed behavior that refuses Voice
while Rime owns composition. This loses the convenience transition but preserves editor safety.

## Follow-ups

- `RIM-008`
- `TST-002`
- `TST-005`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
