# Task Report: KSP-011 Route A Upstream Sync

## Result

DONE

KSP-011 establishes a fail-closed, offline replay contract for the restricted Route A source
boundary accepted by ADR-0011. The maintained queue is three ordered, source-text patches rather
than the historical KSP-009 `final3` binary evidence patch. It starts from the exact FlorisBoard
remote/commit/tree and fixed codeload archive, validates every patch byte/path/tree transition,
retains the upstream and OpenTypeless license mapping, and exports a deterministic `.git`-free
tree.

This does not import the whole FlorisBoard application into OpenTypeless, build or bundle native
libraries, authorize real Xiaohè resources, implement KBD-001, or prove a product/system-selected
IME. REL-009 still owns the first real upstream-version update and conflict resolution.

## Scope

- Implemented: fixed upstream/component lock; restricted source boundary; legal/provenance
  baseline; finite patch series; trusted stdlib verifier; official-source identity check; safe
  archive extraction; exact tree-chain replay; deterministic report/export; adversarial tests.
- Not implemented: network fetch/update automation, product runtime changes, native rebuild,
  Gradle build, APK install, device test, signed Release, SBOM/THIRD_PARTY_NOTICES assembly,
  Xiaohè resource distribution, or a real upstream version bump.

## Fixed identities

- FlorisBoard remote: `https://github.com/florisboard/florisboard.git`;
- commit: `2e82060251897226c0739b9f52d1d051b02305fb`;
- official Git tree: `f1da19f9887f353ada940787387674aad7ab80cd`;
- direct codeload archive: 20,748,703 bytes, SHA-256
  `ba279c66ad4800b8b6242758e734a2f5852d6c1775cb54a538a497b595215594`;
- normalized archive tree: `5a911de0dc2242f146b3cfcc47c2b8b1dc90f7e5`, 896 files;
- upstream Apache-2.0 `LICENSE`: SHA-256
  `b7eb0e4356678f0fa7fb53a35c864ec0b3dca6c6602a26c3cb972c13e2041fcf`;
- JetPref canonical remote: `https://github.com/patrickgold/jetpref.git`, commit
  `d6e12dda6517345dacc3682aa476a8448a71c34b`;
- librime/recursive dependency and Boost identities are locked in
  `third_party/keyboard/route_a/upstream-lock.v1.json`.

The official Git tree and normalized archive tree are intentionally distinct assertions: the
former proves repository ancestry; the latter proves the byte/mode/line-ending materialization
used by offline patch application. The archive contains three upstream-tracked `.idea` files
which also match upstream ignore rules. An initial ordinary `git add -A` audit silently omitted
them and produced the wrong 893-file tree; that run was rejected. The accepted replay uses
`git add --force -A`, includes all 896 preflighted regular files, and locks the corrected tree.

## Maintained source queue

| Order | Patch | Bytes | SHA-256 | Paths | Output tree |
|---:|---|---:|---|---:|---|
| 1 | `0001-build-wiring.patch` | 417,457 | `253377febdac02b8b13da4a9cfb81bd01c979637eaf0f78a6e2a393487a356fe` | 2 | `47016737db083ccf6dea9e73ce991521507d96df` |
| 2 | `0002-editor-host.patch` | 356,132 | `67550eb75791e22a33f37f3449645cca5a75772a984935a2c928f2367e44b9e8` | 41 | `f58969c3e9b5a192f904fd7dae7e8bd98cda80ef` |
| 3 | `0003-safety-eval.patch` | 255,390 | `0f36e9dc2b2553241e7d1312568a1a1262fd168cb226ccec8d662ca0b07f785e` | 34 | `179eca9923d2e93af0acdadde454d901d58bf8c0` |

Total: 1,028,979 bytes and 77 declared source paths. The final export has 972 files and index
manifest SHA-256 `7bf514b8018e93010f74148b8b347cbc93877487400fee867a75bd83a317fc0d`.

The queue accepts only exact build-wiring files, `opentypeless-editor-host/**`, the isolated
`route-a-safety-eval/**`, and two manifest-gate scripts. It rejects `app/**`, `.github/**`, native
or packaged binaries, databases, archives, models, Git metadata/gitlinks, symlink/executable mode
changes, rename/copy operations, extra patches and path/case/Unicode collisions.

The historical `final3` patch remains external evidence only: 10,501,449 bytes, SHA-256
`13a0073967cc8fe9e61fe31ffc46b3110ef9e60cb629faa5ba172d33d79a38b0`.
It is forbidden as a maintained queue input because it contains generated `.so` files,
unselected whole-App changes, and the reversible preimages of deleted, source-unverified
resources. The three maintained patches contain no such resource path or payload.

## Architecture

- contracts: offline by default; no implicit fetch; fixed literal HTTPS identities; strict JSON
  keys; exact finite patch set/order/size/hash/path/tree; index-aware apply without 3-way/reject;
  `.git`-free deterministic export.
- state changes: KSP-011 becomes DONE. No product runtime or persistent state changes.
- migration: none.
- feature flag: none.

The trusted verifier lives in the OpenTypeless repository, outside the replayed source tree. It
validates the queue before executing any patched-tree gate. Git invocations use fixed
`/usr/bin/git`, discard inherited `GIT_*` injection, disable system/global config, prompts, hooks
and `file` protocol, and reject dirty official sources including untracked/ignored files.

## Security & privacy

- data sent/stored: no user text, audio, dictionary, credential, UserDB or Secret is read or
  written. Replay reads only a fixed local archive and writes disposable source/report outputs.
- permissions/components: no Android permission or component changed in the product repository.
- threat considerations: archive traversal, absolute/backslash/control/bidi/case/NFC collision,
  links/special files, expansion limits, queue tampering, binary payloads, path escape, Git config
  injection, dirty source, tree drift, legal-file loss and nondeterministic `.git` influence all
  fail closed.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `python3 -m unittest -v scripts/test_route_a_upstream.py` | FAIL — wrong launcher | `scripts` was not on the import path, so collection stopped with `ModuleNotFoundError`; the documented direct entry below is the accepted command. |
| `python3 scripts/test_route_a_upstream.py -v` | PASS | 44/44 adversarial/contract tests, including quoted-path and fsmonitor regressions. |
| `python3 -m py_compile scripts/route_a_upstream.py scripts/test_route_a_upstream.py` | PASS | Both trusted Python entrypoints compile. |
| `python3 scripts/route_a_upstream.py verify --repo-root .` | PASS | 3 patches; expected final tree `179eca9923d2e93af0acdadde454d901d58bf8c0`. |
| `python3 scripts/route_a_upstream.py verify-source --repo-root . --upstream-repo <clean-official-checkout>` | PASS | Literal official remote, full commit/tree, 896 tracked files, empty gitlink set and clean status. |
| Two fresh `replay` invocations against the fixed local archive | PASS | Both 972 files, identical final tree/index manifest and byte-identical reports. |
| Replay report comparison | PASS | Both report SHA-256 `8504ce9934898dd16e910bc162e5e95450e724b5dc8ebe3049f76b429c7a711c`. |
| Export file-manifest comparison | PASS | Both canonical manifests SHA-256 `2e22cb495cc08ce286a9fa5239ce6c1e000fccb29073a5b027783236b88715b6`; `.git` count 0. |
| Initial non-force index audit | FAIL — corrected | Omitted 3 upstream-tracked ignored files; rejected before acceptance and locked by regression test. |
| `python3 scripts/test_verify_docs.py -v` + `python3 scripts/verify_docs.py --repo-root .` | PASS | 4/4 tests; 3 entrypoints and 16 specification files. |
| `python3 scripts/test_verify_adrs.py -v` + `python3 scripts/verify_adrs.py --repo-root .` | PASS | 4/4 tests; template/index and 11 standalone ADRs. |
| FULL mirror / package metrics / file manifest | PASS | 12/12 mirrors; 16 files, 1,597,634 bytes excluding the self-referential manifest, 38 Mermaid blocks, 1,610 headings, 196 unique tasks; manifest 15/15. |
| `git diff --check -- <KSP-011 paths>` | PASS | No whitespace errors in the task-scoped implementation and documentation. |
| Gradle/Android build | NOT RUN — outside KSP-011 | No runtime/build input changed by this task. |
| Device/Xiaomi test | NOT RUN — outside KSP-011 | KSP-011 is source-replay tooling only. |
| Current HEAD GitHub Actions | NOT RUN — no matching run claimed | Local trusted tests and replay are the evidence for this task. |

## Evidence

- `docs/benchmarks/ksp-011-route-a-replay.json`: deterministic replay report, 9,009 bytes,
  SHA-256 `8504ce9934898dd16e910bc162e5e95450e724b5dc8ebe3049f76b429c7a711c`.
- Two disposable replay roots under `/private/tmp` produced byte-identical reports and export
  manifests; absolute paths are intentionally absent from the committed report.
- KSP-007/KSP-009/KSP-010 reports remain the runtime/license/editor/privacy evidence. KSP-011 does
  not reinterpret their APK/device results as a product integration.

## Changes

- `third_party/keyboard/route_a/*`: fixed upstream/component, source-boundary and legal locks.
- `third_party/keyboard/route_a/patches/*`: three finite source-text patches and exact series.
- `scripts/route_a_upstream.py`: trusted offline verify/source/replay implementation.
- `scripts/test_route_a_upstream.py`: malicious fixture and determinism suite.
- `docs/benchmarks/ksp-011-route-a-replay.json`: canonical replay evidence.
- `docs/2026-08-14-ksp-002-florisboard-build-validation.md`: corrected JetPref canonical remote.
- specification/backlog/ADR mirrors: KSP-011 status, contract and validation evidence.

## Risks

- The queue is evidence-only. Patch 3 intentionally omits generated Rime `.so` files, so this
  replay is not by itself a buildable production keyboard or native provenance closure.
- REL-009 must exercise an actual upstream version update. No automatic “latest” lookup or silent
  conflict resolution exists.
- KSP-012 still blocks bundled real Xiaohè data. Formal SBOM/notices/source-offer and signed
  artifacts remain SEC/REL gates.
- KBD-001 remains responsible for importing only the reviewed product boundary and preserving the
  single-writer/manifest/privacy gates.

## Rollback

Remove the KSP-011 lock/queue/scripts/report/docs and restore the Backlog row to TODO. Replay
outputs and upstream caches are disposable. No product data, permission, dependency, runtime or
Feature Flag requires rollback.

## Follow-ups

- `KSP-012`
- `KBD-001`
- `REL-009`
- `SEC-010`
- `REL-003`
- `REL-007`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with extensive pre-existing/concurrent changes; KSP-011 does
  not stage, commit or push.
