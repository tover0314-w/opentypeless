# Task Report: KBD-007 Unified candidate bar model

## Result

DONE

## Scope

- Implemented: immutable engine-independent `CandidatePage`; stable candidate selection and page
  request values; horizontally scrollable numbered candidate bar; previous/next paging; 48dp
  targets; stale-View rejection; destructive sensitive-field clearing; Route-A composition-slot
  wiring; English and Chinese accessibility text.
- Not implemented: Latin suggestion generation, Rime native/session/preedit, candidate commit,
  expanded full-screen candidate panel, physical-key candidate selection or user dictionary. Those
  remain the owning RIM/Latin follow-up tasks.

## Changes

- `android/app/src/main/java/com/opentypeless/android/keyboard/candidate/CandidatePage.java`:
  bounded immutable page/item/selection/page-request model. A selection carries producer,
  generation, page revision, page index, candidate index, stable ID and expected text; diagnostic
  strings redact text.
- `android/app/src/main/java/com/opentypeless/android/keyboard/candidate/KeyboardCandidateBar.java`:
  real horizontal View with numbered accessible candidate buttons and bounded previous/next
  controls. Replaced, cleared, disabled or privacy-hidden pages cannot dispatch stale callbacks.
- `android/app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java`: attaches one
  initially empty bar to the Route-A composition slot; clears it on every input target change and
  finish; disables it while editor keys are unavailable. Until an engine is bound, an event is
  rejected and never becomes an editor write.
- unit/Android tests and `android/architecture-tests/candidate_bar_contract.py`: reuse, bounds,
  identity, redaction, paging, stale callbacks, privacy, accessibility and capability gates.
- KSP-012 resource policy: registers only the three exact clean KBD-007 APK identities after the
  recursive product/test scans passed.

## Architecture

- contracts: `CandidatePage` is a pure Java value. The View receives only the immutable page and
  data-only callbacks; it never receives an engine, JNI adapter, editor manager or InputConnection.
- state changes: one in-memory current page. `showPage` replaces it atomically on the UI thread;
  `clear` removes child Views and plaintext. Privacy denial never caches a page for restoration.
- migration: none; no persisted format or preference was added.
- feature flag: none; the existing mutually exclusive Route-A Shell flag remains unchanged.

The page revision and expected candidate identity are deliberately part of the callback so RIM-005
can reject a candidate-page race instead of selecting whatever later occupies the same numeric
index. KBD-007 itself performs no selection commit.

## Security & privacy

- data sent/stored: none; candidate strings exist only in the current in-memory View page.
- permissions/components: none.
- threat considerations: candidate/page counts, IDs and text are bounded; controls and duplicate
  IDs fail closed; diagnostic `toString()` output redacts candidate text. Password and other
  sensitive field transitions destructively remove the page and all candidate Views. Source and
  compiled architecture checks report no new editor writer, JNI, reflection, storage or network
  capability.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| KBD-007 hostile source contract | PASS | 8/8; full architecture Python suite 184/184. |
| `CandidatePageTest` | PASS | 6/6; Latin/Rime reuse, immutable paging identity, bounds and redaction. |
| clean strict Debug/Release/AndroidTest/lint graph | PASS | 191 tasks: 188 executed, 3 up-to-date; app JVM 994/994, architecture-gate 114/114, compiled Debug/Release 2/2. |
| `KeyboardCandidateBarInstrumentedTest` | PASS | final exact APKs, 6/6 on API35 ARM64 emulator and 6/6 on Xiaomi 10 Ultra API33. |
| KSP-012 resource verifier | PASS | 36/36 hostile tests; final 3 product + 2 test APK scan: real Xiaohè 0, forbidden Rime resource 0, violations 0. |
| docs / ADR / compatibility validators | PASS | 13/13 unit tests; 3 entrypoints/16 specs; 12 ADRs; 25 compatibility rows; FULL mirror 12/12 and file manifest 15/15. |

The first full preflight after wiring failed two existing KBD-002/003 source fixtures because their
parser treated the new earlier Route-A conditional as the QWERTY block. The candidate condition was
rewritten without weakening either gate; KBD-002, KBD-003 and the full 184-test architecture suite
then passed. No runtime test was relabeled.

One initial documentation-unit invocation from the repository root failed because sibling verifier
modules were not on Python's import path. The documented `scripts/` working-directory invocation
then ran all 13 tests successfully; no validator or fixture was changed to obtain the pass.

## Evidence

- Debug APK: 56,483,037 bytes, SHA-256
  `4590779d59d2b4c68e710f042acefc8ea94eb1cefbe092170695cf24e28282bb`.
- unsigned Release APK: 54,657,185 bytes, SHA-256
  `fd090e0da829d89128cfb7d2474d9272329cd333d0a2065d5b30e9d0eb3bad01`.
- app AndroidTest APK: 1,075,435 bytes, SHA-256
  `2d8721fea8e8f431e24f4901b329f0755c31a7261cc4d398ffdd6065feb8d4a8`.
- unchanged Test Host Debug/AndroidTest APKs: 13,085 / 1,692,648 bytes, SHA-256
  `908d7582c6c668466311aeb2c124167eb5985900abcece78e499bc6970e1a4e3` /
  `fc947b05dc49d81e54076b8b0da7a7c22b3a780ac2d9716a1e16a12061c45429`.
- product/test resource scan manifests: `e0bbfe2e42b2de9d2300cf136fd3d53646efeb3f93d721213a53bbc1a1182ab9` /
  `44fc4c3ab1b2d9239474f022a1ef8e3fac5fa773798625738ec8e4766ce0a6a5`;
  policy canonical SHA-256 `e57a307c4ba56bdad33a7a40a04f427def427be0a815a9a38c020e11281cfe9c`.
- final device defaults remained `com.android.inputmethod.latin/.LatinIME` and
  `com.flypy.input/PangIME.Android.InputService`.

## Risks

- The bar is intentionally empty until a trusted Latin or Rime producer is connected; KBD-007
  proves the shared UI/identity contract, not candidate quality or an end-user Rime path.
- Candidate interaction is tested through the production View class on both devices, not through a
  system-selected IME with a real engine page; RIM-005 must add that end-to-end proof.
- A full expanded candidate panel remains a usability enhancement after the horizontal personal-P0
  bar; it is not required for the current bounded page contract.

## Rollback

Remove the two candidate classes, service composition-slot wiring, tests/gate/strings and the three
reviewed artifact identities. The empty bar stores no data, so rollback requires no migration.

## Follow-ups

- `RIM-001`
- `RIM-005`
- `TST-005`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
