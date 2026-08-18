# Task Report: KSP-010 Keyboard Base Decision

## Result

DONE

The product owner confirmed the Route A direction and exclusion of the currently validated Route B GPL payload on 2026-08-15.
The original whole Route-A evidence artifact closed selected-path function/resource/build gates but failed editor authority and
privacy/permissions. That artifact remains explicitly **not selected**; its two P0 findings are retained below.

The KSP-009 safety follow-up now supplies the previously missing buildable boundary: independent application module
`:route-a-safety-eval`, with no `:app` dependency, no packaged legacy editor classes, one editor-host authority enclave, exact ETM
writer baseline, capability-free producers, mutually exclusive/no-fallback route spies and a fail-closed one-service manifest. The
same final main/test artifacts pass exact 12/12 on Xiaomi API33 and API26 x86_64; strict clean and fresh replay produce exact Debug,
AndroidTest, unsigned Release and merged manifests. Independent red-team review reports residual P0/P1 = 0 for the safety artifact
and the two KSP-010 gates. Therefore Route A's five hard gates have explicit results with no remaining selected-boundary failure:
ADR-0011 is `Accepted`, KSP-010 is `DONE`, and KBD-001 may start while remaining `TODO`.

This decision accepts only the restricted Route-A source/adapter contract. It does not complete KBD-001, integrate a full product
keyboard, prove a system-selected IME E2E, create a signed Release, authorize real Xiaohè resources or satisfy KSP-011/012 and the
SEC/TST/REL release gates.

## Scope

- Implemented: formal five-gate adjudication; fixed Route-A direction and inputs; Route B GPL exclusion; exact current P0 editor/
  privacy failures; minimum evidence contract for a safety follow-up.
- Not implemented: third-party source/runtime integration, KSP-011 upstream automation, KSP-012 language-resource licensing,
  production QWERTY/Rime, final integrated performance, signed distribution Release, formal release NOTICE/SBOM packaging, or the
  KBD-001 Debug/Release merged-manifest gate.

## Decision

Accept **Route A: restricted FlorisBoard-style Shell source boundary + OpenTypeless capability layer + self-built librime Adapter
contract** as the implementation direction. Keep **Route B: fcitx5-android + official Rime plugin** excluded from the primary product unless a future ADR is
supported by either a proven GPL-free rebuild or an explicit decision to accept and operate a GPL/LGPL distribution.

The selected fixed inputs are:

- FlorisBoard `v0.5.2` commit `2e82060251897226c0739b9f52d1d051b02305fb`;
- JetPref source commit `d6e12dda6517345dacc3682aa476a8448a71c34b`;
- librime `1.17.0` commit `33e78140250125871856cdc5b42ddc6a5fcd3cd4` plus the KSP-004 recorded recursive gitlinks;
- Boost `1.89.0` archive and digest recorded by KSP-004.

KBD-001 is now authorized to start but remains `TODO`; no KBD/RIM implementation is completed by this decision. Its production
slice must preserve the proven restricted boundary, upstream copyright/licenses, unknown/real-Xiaohè exclusion, mutually exclusive
Shell route and the editor/manifest gates below. No third-party payload may be formally released until KSP-011/012 plus the
applicable SEC/TST/REL gates are complete.

## Hard-gate result

| Gate | Route A | Route B |
|---|---|---|
| License/source | **PASS** for the selected fixed source boundary — exact source/dependency identity, selected license branches, copyright, redistribution obligations and provenance are recorded; unknown resources are excluded. Formal `THIRD_PARTY_NOTICES`/SBOM/source bundle/drift enforcement remains SEC/REL release scope | **FAIL** for current artifact — GPL-2.0-or-later `pinyin.lua`, GPL-3.0-only octagram in static `librime.so`, incomplete corresponding-source/relink package |
| Reproducible dual-ABI build/install | PASS — exact replay, strict Debug/AndroidTest and byte-identical unsigned Release pass; packaged arm64-v8a/x86_64, both arm64 devices and disposable API26 x86_64 install/core/Latin/UserDB restart pass dynamically | PASS for the earlier isolated Route-B spike only |
| Editor authority | **PASS for the selected restricted boundary** — independent `:route-a-safety-eval` has no `:app` dependency/legacy editor classes; producers have zero IC/manager/registry capability; real View/Rime/Voice/Undo/QuickAction cross one route and capability-free `EditorPort`; exact ETM seven-edge baseline and D/R whole-APK gates pass. The rejected whole upstream artifact's 32+ writer/5-IC-file failure remains historical evidence. | PASS in isolated vertical slice |
| Privacy/permissions | **PASS for the selected restricted boundary** — `allowBackup=false`, all base/cloud/device-transfer domains excluded, one BIND_INPUT_METHOD service and zero permission/query/profileable/other component; source and D/R merged-manifest gates pass. The rejected whole App surface remains excluded. | **FAIL** for current defaults — clipboard history defaults on; whole App surface is not selected |
| Common vertical slice | PASS — final restricted Debug artifact's real View covers Latin/Rime/Voice/exact Undo/QuickAction/sensitive/lifecycle/generation cases; Xiaomi API33 and API26 x86_64 both exact 12/12 | PASS including integrated official Rime |

ADR-0011 forbids a numeric total from overriding a failed hard gate. Acceptance is based on the built/tested restricted boundary,
not on rewriting the rejected whole-App failures. Both the older KSP-009 APK and the new safety APK remain evidence artifacts, not
production candidates.

## Route A preliminary worksheet

| Dimension | Weight | Score | Weighted | Evidence basis |
|---|---:|---:|---:|---|
| License/distribution freedom | 20 | 4/5 | 16 | No inherent GPL dependency in the selected path; unknown bundled resources removed, but final Xiaohè and release NOTICE/SBOM remain gated |
| Rime readiness | 20 | 5/5 | 20 | The same artifact passes synthetic Schema, candidate, UserDB and fresh-process restart; this does not authorize real Xiaohè resources or claim production RIM integration |
| Full keyboard maturity | 20 | 4/5 | 16 | Field layouts, landscape, theme and clipboard surface pass; one accessibility action lacks a label |
| Voice/Action extensibility | 15 | 4/5 | 12 | Voice/Undo vertical slice uses exact OpenTypeless transaction authority; base-specific patch remains isolated |
| Upstream activity/sync | 10 | 3/5 | 6 | Exact fixed-upstream replay passes; automated patch-queue sync remains KSP-011 |
| Current migration cost | 10 | 3/5 | 6 | Reversible flag/adapter route is proven, but Shell+librime integration is not yet production code |
| Performance control | 5 | 4/5 | 4 | Xiaomi QWERTY P95 5.649 ms, Activity initial-display P95 437 ms, post-launch PSS 78,573 KB; final integrated Rime figures still required |
| **Total** | **100** |  | **80/100** | Rubric-correct worksheet; usable only after the restricted boundary's five hard gates passed |

## Decision consequences

- Preserve the current permissive OpenTypeless distribution direction; do not copy HeliBoard, Trime, fcitx GPL plugins, `pinyin.lua`,
  octagram or other GPL payload into the MIT product tree.
- KSP-011 must establish the exact Floris upstream remote, finite patch queue, clean replay, copyright retention and drift checks
  after KSP-010 is accepted and before formal distribution.
- KSP-007 addendum closes the current Debug `data.json`/Han/CLDR/native provenance findings. KSP-012 must still decide whether
  Xiaohè/Rime resources are distributable; until then they are user-import-only and do not enter release variants.
- The KSP-009 safety artifact proves the required real View/Rime/Voice/Undo/ordinary/QuickAction one-ETM route, mutually exclusive
  spies, zero-fallback and Debug/Release source/compiled gates. KBD-001 may now start, but must preserve these gates while importing
  only reviewed Shell source; it cannot infer that a full product keyboard or system-selected IME E2E already passed.
- The same artifact proves `allowBackup=false`, deny-all backup/device-transfer rules and removal of inherited SpellChecker,
  URI/content/SEND, alias, clipboard SEND, profileable, notification/query and extra exported surfaces. KBD-001 must keep both
  merged-manifest gates non-disableable; new dangerous permission or broader boundary still requires a separate Accepted ADR.
- RIM-001..009 own the production librime lifecycle, schema staging, candidates, UserDB and Voice conflict work. KSP-004's separate test
  process is evidence, not a production integration shortcut.
- TST-008 must remeasure the final integrated IME; KSP-008's Activity and split-process figures are not release performance claims.

## Rejected alternative for this decision

Route B is not rejected forever. It is rejected as the primary target **in its currently validated artifact form**. Reconsideration requires a
new ADR that either:

1. proves a clean, reproducible GPL-free fcitx/Rime build with final APK/ELF scans and full LGPL materials; or
2. explicitly accepts the GPL/LGPL distribution scope, corresponding-source/relink obligations, release packaging and long-term maintenance.

## Evidence reviewed

- `docs/2026-08-14-ksp-007-license-compliance-analysis.md`;
- `docs/2026-08-14-ksp-008-keyboard-performance-benchmark.md`;
- `docs/benchmarks/ksp-008-xiaomi-10-ultra.json`;
- `docs/2026-08-15-ksp-009-keyboard-function-matrix.md`;
- `docs/benchmarks/ksp-009-xiaomi-10-ultra.json`;
- KSP-007 Route-A addendum: fixed upstream tar
  `ba279c66ad4800b8b6242758e734a2f5852d6c1775cb54a538a497b595215594`; 89-file final patch is 10,214,294 bytes,
  SHA-256 `a04c5fecdadadc49e5f67e09495de6f71219cb02b5e974a80bd9d8fe1d2985a5`; fresh apply/check yields exact tree
  `d99747a43f3c8dcc2a9c70de1f789cce6948af30`;
- source-first script SHA-256 `e9b7fd8603adfc349d0998de0cac9e53fafca99259f8421bd0e97b104823cddf` validates fixed clean
  source/OpenCC patch, rebuilds/strips both ABI librime/JNI outputs and rejects host paths; all four hashes equal the `jniLibs` and
  APK entries, with forbidden/path/GPL/Lua/octagram scan zero;
- candidate/replay 225/225 assets match; candidate and replay strict-offline builds are both 209 tasks with JVM 7/7;
- candidate/replay main APK is byte-identical, 39,136,901 bytes, SHA-256
  `24f43dc77e387bf251cab2c61edaf980e0e73aa3c9420e95fe21e3bbc2c9edd7`; AndroidTest APK is also byte-identical,
  592,323 bytes, SHA-256 `66dd631d44a5a0255e04305ca4a8fa25bc1a015d5f92e4f84ce393c527301091`;
- KSP-009 Release closure final 89-file patch is 10,227,983 bytes, SHA-256
  `81bf81420a4f2ce40461163514f59f583dbe33c0d54d72da8efba7aa4017f9f3`; fresh apply/check yields exact tree
  `001b727d3c6e2cb8b4a2b50fdb12bb9ca6c6a443`, while final verification metadata SHA-256 is
  `6f72a35928022190b243866d17faff1097a895ae14d5938a3ca4048e953ead38`;
- all 29 newly required release-only artifacts were individually authenticated against official repositories; candidate strict
  Release passed in 2m55s/262 tasks and fresh replay passed in 2m44s/262 tasks. Both unsigned Release APKs are byte-identical,
  17,758,708 bytes, SHA-256 `243020c76caaf6f6577f5f14a20a02050a15883ac4ab3dafc919ec2381b94df9`;
- Release audit found 225/225 expected assets plus two baseline profiles, exactly eight expected native entries, four source-built
  Rime SO mappings, zero forbidden markers, `minSdk 26`/`targetSdk 36` and no `INTERNET` permission;
- disposable official API26/default/x86_64 rev1 installed the same final main/test APKs successfully, then passed core 6/6,
  Latin 3/3, seed 1/1, explicit main+test force-stop and fresh restart 1/1. Final readback reported x86_64/API26/boot complete,
  package service and both package paths before clean emulator shutdown;
- read-only candidate source audit found at least 32 production callsites across the six audited mutator families after excluding
  two `commitText` method declarations, plus a separate selection-writer surface and 5 files with `InputConnection` references outside ETM.
  `KeyboardManager` only routes `VOICE_INPUT` through the spike; ordinary QWERTY/delete/enter/space/Undo and QuickAction remain
  legacy. The core QWERTY test directly calls the adapter and does not cover real Shell dispatch;
- merged Release manifest/backup audit found `allowBackup=true`, backup coverage for root/JetPref/`file/ime`/Floris dictionary,
  profileable, SpellChecker, custom URI/content/SEND import, launcher alias, clipboard SEND, `POST_NOTIFICATIONS`, queries and
  additional exported surfaces;
- KSP-009 safety follow-up independent module `:route-a-safety-eval` has no `:app` dependency and reuses only the fixed
  editor-host contract and source-built Rime native inputs. Source plus final Debug/Release whole-APK gates reject all ETM-external
  writer/IC capability, legacy classes, reflection/dynamic/native delegation, non-host→host façade growth and source/dependency/
  package drift; architecture verifier **30/30 PASS**;
- source/Debug/Release manifest gates prove one `BIND_INPUT_METHOD` service, `allowBackup=false`, deny-all backup/transfer and no
  permission/query/profileable/other component; manifest verifier **23/23 PASS**. One wrong-directory invocation failed with
  `ModuleNotFoundError` and ran 0 tests before the corrected command passed;
- final strict clean passed in 1m21s, 216 tasks (201 executed/15 up-to-date), with Debug/Release JVM each **23/23**, lint,
  AndroidTest compile and actual D/R hard gates. Final Debug is 10,390,848 bytes/SHA-256
  `072873267bf817043bb022eced1a885ec28d6452ac93cf4f48347894430ad1d9`; AndroidTest is 625,336 bytes/
  `fd8f3db9c42b82969961c39c1ff88a6be5c461371ee6e3b4b9da0292796161a1`; unsigned Release is 10,009,905 bytes/
  `75a618a8a78c7ffdcc4d9d3319c5d7d859f3625999a6256b9105375f2c0d247d`;
- final 123-file patch is 10,501,449 bytes/SHA-256
  `13a0073967cc8fe9e61fe31ffc46b3110ef9e60cb629faa5ba172d33d79a38b0`; fresh apply yields exact tree
  `338b3ec42379876cf9091552e492e285eb4382d4`, strict replay passes 216 tasks and all three APKs/merged manifests are
  byte-identical;
- Xiaomi API33 and API26 x86_64 run the same final main/test exact class **12/12 PASS**, zero failure, instrumentation code -1 and
  runner RC0. x86 streamed `Broken pipe` failures remain recorded; stable no-streaming installs succeed before the passing suite,
  final readback and recoverable cleanup;
- independent final review reports fixed candidate/replay/dual-ABI residual P0/P1 = 0 and unconditional GO, limited to KSP-009
  safety evidence and KSP-010 editor/privacy hard gates;
- ADR-0011 validation records for KSP-002..006.

## Owner decision

On 2026-08-15 the product owner explicitly confirmed:

> 选择路线 A，并不接受当前路线 B 的 GPL 载荷作为主产品。

The confirmation establishes Route A as the product direction and excludes the current Route B GPL payload. The rejected whole-App
artifact remains rejected, while the later independent safety artifact supplies the missing same-boundary editor/privacy evidence.
ADR-0011 is now `Accepted`, KSP-010 is `DONE`, and KBD-001 may start while remaining `TODO`.

## Changes

- `docs/adr/0011-keyboard-base-evaluation.md` and `docs/adr/README.md`: ADR Accepted for the restricted source/adapter boundary.
- `docs/opentypeless_specs/02_ARCHITECTURE_DEVELOPMENT.md`: accepted direction and evidence-only safety boundary.
- `docs/opentypeless_specs/06_SECURITY_PRIVACY.md`: retained whole-App failures plus passing restricted manifest/editor boundary.
- `docs/opentypeless_specs/07_IMPLEMENTATION_BACKLOG.md`: KSP-010 DONE; KBD-001 remains TODO but may start.
- `docs/opentypeless_specs/08_TEST_VALIDATION.md`: five-gate adjudication, strict replay and dual-ABI 12/12.
- `docs/opentypeless_specs/09_ADR_RESEARCH.md`: accepted restricted decision and remaining implementation/release limits.
- `docs/opentypeless_specs/OpenTypeless_FULL_SPEC.md`, `PACKAGE_VALIDATION.md` and `FILE_MANIFEST.md`: exact mirrors and metrics.

## Architecture

- contracts: the target restricted Route-A boundary requires all Shell/librime writes behind OpenTypeless ETM; the current whole
  candidate violates that contract and is not selected.
- state changes: ADR-0011 becomes Accepted and KSP-010 becomes DONE; KBD-001 remains TODO with its dependency satisfied.
- migration: none.
- feature flag: KBD-001 must introduce a mutually exclusive new/legacy Shell flag; KSP-010 adds none.

## Security & privacy

- data sent/stored: no user text, audio, clipboard data, credential or new persistent format was sent or stored.
- permissions/components: none added or changed.
- threat considerations: the current Route B GPL payload is fail-closed; unknown language resources remain excluded; route selection
  does not authorize a second editor writer, upstream backup/exported surface, unverified binary, new network destination or lowered
  dependency verification.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `python3 scripts/test_verify_docs.py -v` | PASS | 4/4 tests. |
| `python3 scripts/verify_docs.py --repo-root .` | PASS | 3 entrypoints and 16 specification files. |
| `python3 scripts/test_verify_adrs.py -v` | PASS | 4/4 tests. |
| `python3 scripts/verify_adrs.py --repo-root .` (first post-acceptance run) | FAIL — corrected | The Accepted ADR Validation section still used the literal backlog token `TODO`; the fail-closed verifier rejected the placeholder. It was changed to the equally precise “task itself has not started.” |
| `python3 scripts/verify_adrs.py --repo-root .` | PASS | Template + index and 11 standalone decisions; ADR-0011 is Accepted. |
| First combined mirror/metrics/manifest/diff wrapper | FAIL — corrected shell variable | 12/12 mirror, metrics and manifest comparisons had already passed, but zsh rejected assignment to read-only `status` before the diff loop; the corrected `rc_code` loop then passed. |
| Assigned addendum-range whitespace audit | PASS | No trailing whitespace in the newly synchronized evidence ranges; existing intentional Markdown hard breaks remain unchanged. |
| Exact 12/12 FULL mirror comparison | PASS | All modular source documents match their FULL sections. |
| PACKAGE_VALIDATION metrics comparison | PASS | 1,582,819 bytes excluding self-referential manifest, 38 Mermaid blocks, 1,602 headings and 196 task rows match. |
| FILE_MANIFEST byte/SHA comparison | PASS | Recalculated after source/FULL updates. |
| Route-A exact patch replay | PASS — KSP-007 addendum evidence | 89 files; 10,214,294 bytes; exact tree `d99747a43f3c8dcc2a9c70de1f789cce6948af30`; 225/225 assets match. |
| Route-A source-first native mapping | PASS — KSP-007 addendum evidence | Both ABI librime/JNI rebuilt and stripped; outputs equal `jniLibs`/APK entries; host-path/GPL scan zero. |
| Integrated Route-A strict offline Debug + AndroidTest | PASS — KSP-007 addendum evidence | Candidate/replay both 209 tasks; JVM 7/7; main/test APKs byte-identical. |
| Historical integrated Route-A strict Release probe | FAIL — superseded evidence | 2s, 109 tasks; exposed missing trusted checks and produced no APK; retained as discovery history. |
| Candidate integrated Route-A strict Release | PASS — KSP-009 closure evidence | 2m55s, 262 tasks; all 29 new artifacts individually authenticated; strict verification retained. |
| Fresh-replay integrated Route-A strict Release | PASS — KSP-009 closure evidence | 2m44s, 262/262 tasks; unsigned Release byte-identical to candidate. |
| Integrated Route-A unsigned Release audit | PASS — KSP-009 closure evidence | 17,758,708 bytes; 225 assets + 2 baseline profiles; 8 native entries; forbidden markers zero; no INTERNET. |
| Integrated Route-A emulator/Xiaomi core/resource/restart | PASS — latest frozen APK evidence | Both arm64 devices installed; core 6/6, Latin resource 3/3, seed 1/1, force-stop, fresh-process restart 1/1; all commands exit 0. |
| Integrated Route-A x86_64 main/test install | PASS — KSP-009 closure evidence | API26 x86_64; main/test `Success`; fresh package paths present. |
| Integrated Route-A x86_64 core/Latin/restart | PASS — KSP-009 closure evidence | Core 6/6, Latin 3/3, seed 1/1, explicit force-stop, fresh restart 1/1; final boot/package/ABI readback and cleanup PASS. |
| Candidate production writer/capability scan | **FAIL — P0** | At least 32 ETM-external callsites across six audited mutator families, excluding two declarations; selection writers also remain; 5 `InputConnection` files; ordinary Shell/QuickAction legacy dispatch remains. |
| Final merged manifest/backup audit | **FAIL — P0** | `allowBackup=true`, IME/dictionary backup and the listed profileable/exported/import/SEND/permission/query surfaces. |
| Safety architecture verifier | PASS | 30/30, including reflection/dynamic/native/façade/package/source/dependency bypass corpus. |
| Safety manifest verifier first invocation | FAIL — corrected path error | Wrong tools directory; `ModuleNotFoundError`; 0 tests. |
| Safety manifest verifier corrected invocation | PASS | Correct candidate tools path; 23/23. |
| Safety strict clean/fresh replay | PASS | 216 tasks each; D/R JVM 23/23 each; lint, AndroidTest compile, actual D/R whole-APK/merged-manifest gates; exact tree/APKs/manifests. |
| Safety Xiaomi API33 exact class | PASS | Final exact artifacts; 12/12, zero failure, code -1, runner RC0. |
| Safety API26 x86_64 exact class | PASS with retained install failures | Streamed `Broken pipe` history retained; no-streaming installs Success/RC0; 12/12, zero failure, code -1, runner RC0; final readback/cleanup PASS. |
| Independent final review | PASS | Fixed candidate/replay/dual-ABI residual P0/P1 = 0; unconditional GO for KSP-010 editor/privacy gates. |
| Current HEAD GitHub Actions | NOT RUN — no matching run | KSP-010 is documentation/read-only audit; no remote run is claimed. |

## Evidence

- KSP-002..009 reports and redacted Xiaomi 10 Ultra benchmark/function JSON listed above.
- KSP-007 addendum closes the current Debug `data.json`/Han/CLDR/native provenance findings and reproduces the main APK byte-for-byte.
- The 80/100 worksheet is rubric-correct and considered only after the restricted boundary's hard gates passed; the earlier
  72/100 worksheet is superseded because KSP-009 closed the exact Rime Schema/candidate/UserDB/restart matrix.
- The latest frozen APK evidence records arm64 and x86 runs performed by the Route-A validation work; this document-only sync did
  not rerun Gradle, install packages or operate devices.

## Risks

- The whole upstream candidate still fails editor authority and privacy/permissions and remains not selected; future KBD work must
  not re-import those surfaces. The selected restricted evidence boundary has no residual P0/P1 in this adjudication.
- Route A production Shell+librime integration is not implemented; KBD-001 remains TODO and the KSP-009 accessibility defect
  remains for later KBD/TST.
- x86_64 dynamic correctness is verified under software TCG; its long boot/install timing is not product performance evidence.
- KSP-011/012, formal NOTICE/SBOM, release artifact drift and TST-008 remain release-critical. Real Xiaohè stays user-import-only.
- Route B may be reconsidered only through a new ADR with a GPL-free rebuild or explicitly accepted GPL/LGPL obligations.

## Rollback

No OpenTypeless product runtime code, dependency, permission, persistent data or Feature Flag changed, so there is no product-data
rollback. The repository-external safety artifact can be deleted independently. A future KBD-001 integration must use a mutually
exclusive Shell flag and cannot weaken the editor/manifest/privacy negative gates; changing route requires a superseding ADR.

## Follow-ups

- `KSP-011`
- `KSP-012`
- `KBD-001`
- `RIM-001`
- `SEC-010`
- `TST-008`
- `REL-003`
- `REL-007`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared, dirty, with extensive pre-existing and concurrent task changes; KSP-010 does not stage, commit or push.
