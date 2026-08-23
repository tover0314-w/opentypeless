# Task Report: TST-005 Rime golden corpus

## Result

BLOCKED

## Scope

- Implemented: no new test corpus or runtime code. The generic Rime key, preedit, candidate paging,
  selection, options, UserDB and Voice-conflict paths are already independently tested.
- Not implemented: real Xiaohè shape-code golden cases for codes, candidates, paging, word creation,
  simplified/traditional output and punctuation.

## Blocker

TST-005 has a hard dependency on RIM-008. No user-authorized complete Xiaohè shape-code package or
expected golden corpus is present. ADR-0012 requires real Xiaohè to remain zero-bundle and forbids
using PangIME extraction, third-party mirrors or the synthetic `ni -> 甲/乙` fixture as compatibility
evidence.

## Tests actually run

| Check | Result | Notes |
|---|---|---|
| generic Rime regression chain | PASS | RIM-004..007/009 tests key, preedit, candidate paging/selection, options, restart/UserDB and Voice arbitration. |
| repository/product/test resource scan | PASS | Real Xiaohè=0 and violations=0, as required before a user import. |
| real Xiaohè fixed golden corpus | NOT RUN | Required package and expected corpus are absent. |

## Unblock condition

Supply a local manifest-v1 package the user is entitled to use, together with expected Xiaohè codes
and candidate outcomes. The test can then run locally without checking the package or plaintext
golden data into the repository.

## Risks

- Marking this test PASS with synthetic data would falsely claim PangIME/Xiaohè compatibility.

## Rollback

No implementation or data change was made.

## Follow-ups

- `RIM-008`
- `TST-005`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty; nothing staged, committed or pushed.
