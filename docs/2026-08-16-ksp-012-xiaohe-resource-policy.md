# Task Report: KSP-012 Xiaohè Resource Policy

## Result

DONE

KSP-012 accepts [ADR-0012](adr/0012-xiaohe-resource-distribution-policy.md) and freezes Route A's
Xiaohè resource boundary. The policy forbids bundling either complete Xiaohè Sound-and-Shape
resources or the official GPL Rime Xiaohè double-pinyin Schema/dependency closure in any repository,
build, test, patch, snapshot, export, backup or CI surface. Current evidence covers the working tree,
the trusted Route-A patch queue and replay, and fifteen exact APKs; it does not claim a scan of all Git
history, AABs, exports, backups or CI caches. A future implementation may accept only an explicit
local user import under a versioned, fail-closed manifest; unverified user declarations remain
`USER_PROVIDED_UNVERIFIED` and `LOCAL_ONLY`.

This is a source-rights/import-contract decision with a fail-closed static resource verifier and
pre/post-build gate. It does not implement `RIM-003`, `RIM-008` or `RIM-011`, add a runtime resource,
download a payload, run Gradle, or prove a complete Xiaohè IME.

## Scope

- Implemented: primary-source distinction among Xiaohè double pinyin, Rime's GPL double-pinyin
  Schema and complete Xiaohè Sound-and-Shape resources; pinned official source identities;
  public-rights review scope; accepted zero-bundle/local-import policy; manifest v1 contract;
  exact synthetic-test/native/artifact allowlists; recursive source/archive/APK verifier; import
  manifest semantic validator; pre/post-build Android gate; hostile fixtures; security/release/test/
  backlog documentation.
- Not implemented: resource download/copy/conversion, network lookup/update, import UI, archive/YAML
  parser, staging/deploy, librime integration, real Xiaohè test corpus, UserDB lifecycle, export,
  backup, KBD/RIM/REL tasks, Gradle build, AAB/history/export/backup/CI-cache inventory, emulator or
  device validation.

## Source and rights evidence

The official [Flypy homepage](https://www.flypy.cc/) distinguishes Xiaohè double pinyin from complete
Xiaohè Sound-and-Shape. Its [about page](https://www.flypy.cc/about/) identifies the designer/maintainer,
and its [download page](https://www.flypy.cc/download/) provides official software/download routes. A
2026-08-16 review of these pages, the public [sitemap](https://www.flypy.cc/sitemap.xml), and their public
help entrypoints found no express public grant for OpenTypeless to copy, convert, modify, bundle or
redistribute complete Xiaohè Sound-and-Shape resources. This scoped negative finding is not proof
that no private agreement exists and is not legal advice. No payload, image table, query result or
netdisk file was downloaded, copied, OCRed or reconstructed.

The separate official Rime object is fixed as follows:

- repository: [rime-double-pinyin](https://github.com/rime/rime-double-pinyin);
- commit: `01a13287cbd27819be1c34fa1ddc1b3643d5001b`;
- tree: `a1c64a175f1d4f79938fa6da560a633933be7c2d`;
- Schema: [fixed `double_pinyin_flypy.schema.yaml`](https://github.com/rime/rime-double-pinyin/blob/01a13287cbd27819be1c34fa1ddc1b3643d5001b/double_pinyin_flypy.schema.yaml),
  blob `4c78a06b5df625c82904ec2a6b07e161c79cf44a`, 3,125 bytes;
- LICENSE: blob `94a9ed024d3859793618152ea559a168bbcbb5e2`, 35,147 bytes; repository
  LICENSE/GitHub SPDX detection is GPL-3.0;
- direct configuration closure: `luna_pinyin`, `stroke`, and `default` preset, with fixed dependency
  commits/trees/blobs recorded in ADR-0012. `rime-essay` is recorded only as a future closure input,
  not asserted as a direct mandatory dependency without closure evidence.

The Rime file is “朙月拼音 + 小鹤双拼”; it is not complete Xiaohè Sound-and-Shape. Rime's official
[Plum](https://github.com/rime/plum) documentation also requires package-by-package license review;
a top-level repository label or self-declared manifest string cannot establish a complete
redistribution grant.

## Architecture

- contracts: `opentypeless.rime-resource-manifest` version 1, closed-world file/dependency manifest,
  explicit local picker, exact hashes, bounded safe staging and fail-closed deployment; first-version
  trust/distribution values are `USER_PROVIDED_UNVERIFIED` and `LOCAL_ONLY`.
- state changes: KSP-012 becomes DONE; ADR-0012 becomes Accepted. All `RIM-*`, `KBD-*`, `SEC-*`,
  `TST-*` and `REL-*` tasks retain their current status.
- migration: none. Manifest v1 is a frozen future import contract, not a current production runtime
  authority or persisted-data migration.
- feature flag: none.

The only repository-fixture exception is OpenTypeless-authored `SYNTHETIC_TEST_ONLY` evidence with
no real Xiaohè name, layout, code table, lexicon, candidates or reconstructable data. Synthetic Rime
tests cannot be reported as real Xiaohè compatibility.

## Security & privacy

- data sent/stored: this task sends or stores no user text, audio, UserDB, dictionary or resource
  payload. Future explicit imports remain app-private and are excluded from log, diagnostic, export,
  backup, transfer, sync, snapshot and CI.
- permissions/components: none changed.
- threat considerations: no auto download/update/redirect, no query reconstruction, no self-declared
  rights escalation, no path traversal/link/special/native/Lua/script payload, no unlisted or
  hash-mismatched file, no failed-deploy replacement of the last working Schema, and no implied
  Flypy/Rime endorsement.

Every real resource and official/third-party GPL Xiaohè Schema has a required bundle count of zero
across repository history, Debug, Release, androidTest, APK, AAB, patch preimage, snapshot, Golden,
export, backup, migration fixture and CI artifact/cache. This is the continuing policy, not a claim
that every historical or future surface was scanned in KSP-012. Metadata URLs, immutable identities
and non-reconstructable hashes are allowed.

## Tests actually run

| Command or review | Result | Notes |
|---|---|---|
| Flypy official public-page review | PASS | Homepage/about/download/sitemap/public help reviewed on 2026-08-16; no express redistribution grant found in that scope; no payload accessed. |
| Rime official source/license/dependency audit | PASS | Fixed commit/tree/blob and direct config references recorded; object correctly classified as GPL double-pinyin Schema, not complete Sound-and-Shape. |
| `python3 -m unittest discover -s scripts -p 'test_*.py' -v` | PASS | 119/119; KSP-012 hostile fixtures are 36/36 and cover full source locations, split byte arrays, recursive codecs/archives, real 7z/zstd rejection, unknown opaque/native/assets/APKs, import semantics, report isolation and app/test-host post-build wiring. |
| `scripts/verify_android.sh preflight` with pinned JDK/SDK environment | PASS | KSP-012 scan plus 119 script, 6 Android-script, 115 architecture and 10 mobile-voice tests passed; pinned Sherpa AAR verification passed. Two earlier invocations without the required JDK/SDK environment failed before tests and were corrected without relaxing verification. |
| `verify_rime_resource_policy.py verify --repo-root .` | PASS | 1,061 enumerated / 1,403 inspected; 3 containers / 166 members; exactly 3 synthetic fixtures and 4 known native engines; real Xiaohè 0, forbidden resource 0, violations 0. Scope is working tree plus trusted patch queue, not all Git history. |
| KSP-011 fixed replay `scan-tree --profile evidence` | PASS | 972 enumerated / 1,005 inspected; 3 synthetic fixtures; real Xiaohè 0, forbidden resource 0, violations 0. |
| Exact current product/test APK scans | PASS | Six product APKs: 279 members, 14 exact native engines, 0 assets/real/forbidden/violations. Two AndroidTest APKs: 38 members, 0 assets/real/forbidden/violations. |
| Exact Route-A safety evidence APK scans | PASS | Debug/Release/AndroidTest hashes are policy-pinned; 73 members, 6 expected fixture occurrences, 8 exact librime/JNI entries, real Xiaohè 0, forbidden resource 0, violations 0. |
| Gradle / Android build | NOT RUN — outside KSP-012 | The pre/post-build hooks were statically tested, but KSP-012 did not invoke Gradle. Existing exact APKs were scanned read-only. |
| Emulator / Xiaomi device | NOT RUN — outside KSP-012 | A rights/import-contract decision cannot establish real Xiaohè runtime behavior. |
| Current-HEAD GitHub Actions | NOT RUN — no matching run claimed | No CI result is inferred from unrelated branch runs. |
| Full Git history, AAB, export, backup and CI-cache inventory | NOT RUN — release/future-surface scope | These remain mandatory zero-bundle surfaces and must fail closed in their owning RIM/SEC/REL tasks; KSP-012 does not report them as scanned. |

## Evidence

- [ADR-0012](adr/0012-xiaohe-resource-distribution-policy.md): accepted decision, immutable source
  identities, manifest fields, import boundary, synthetic exception and future superseding-ADR gate.
- Official URLs above are the primary-source basis. No search snippet or third-party repack is treated
  as authorization.
- `third_party/rime/resource-policy.v1.json` and
  `protocol/opentypeless-rime-import-manifest-v1.schema.json` freeze the canonical policy/schema;
  their SHA-256 values are `1fcf5c042f1087986c3e97aaee5eba0eba386c0ee6cfafbd061699298d9fc518`
  and `5d466e6bf38959deb47fc15bd946e3429e559ad4342367b9435ce1d9330f30cf`.
- The replay and exact APK scans above are content-free evidence. Their reports use stable labels and
  do not contain host paths or payload bodies.
- KBD-004 later registered four changed exact APK identities only after recursive product/test scans
  reported real Xiaohè 0, forbidden Rime resource 0 and violations 0; this changes no allowed payload.
- KBD-006 later registered four changed exact APK identities only after recursive product/test scans
  reported real Xiaohè 0, forbidden Rime resource 0 and violations 0; this changes no allowed payload.

## Changes

- `docs/adr/0012-xiaohe-resource-distribution-policy.md`: accepted source/distribution/import decision.
- `docs/adr/README.md`: ADR-0012 index entry.
- `docs/2026-08-16-ksp-012-xiaohe-resource-policy.md`: task scope and evidence report.
- `docs/opentypeless_specs/02/05/06/07/08/09/10`: architecture, data, security, backlog, test,
  research and release boundaries.
- `docs/COMPATIBILITY.md`: manifest v1 recorded as contract-only, not runtime authority.
- `CHANGELOG.md`: Unreleased KSP-012 documentation decision.
- `third_party/rime/resource-policy.v1.json`: canonical zero-bundle, fixture/native and exact APK policy.
- `protocol/opentypeless-rime-import-manifest-v1.schema.json`: future local-import manifest contract.
- `scripts/verify_rime_resource_policy.py`: recursive source/archive/APK and import-semantic verifier.
- `scripts/test_verify_rime_resource_policy.py`: hostile contract and bypass fixtures.
- `scripts/verify_android.sh`: working-tree preflight plus post-build APK policy gates.

## Risks

- Public-page review cannot rule out a private grant or resolve every copyright/factual-data/layout
  question. A clean-room compatibility project would require separate professional review and ADR.
- Local import can still expose a user to third-party terms; the app must not present an unverified
  declaration as trusted or redistribute the user's package.
- Until `RIM-003` implements the bounded parser/staging tests, manifest v1 is only a contract. Until
  `RIM-008` runs on a locally supplied or newly authorized package, real Xiaohè compatibility remains
  unverified.
- KSP-012 is not release evidence for unscanned Git history, AAB, export, backup or CI-cache surfaces;
  those mandatory zero-bundle checks remain fail-closed follow-ups in their owning tasks.

## Rollback

No runtime or data rollback is required. Reverting these documentation changes restores KSP-012 to
TODO and leaves the stricter no-resource gate in force. Any relaxation of zero-bundle/local-only or
addition of a trusted/bundled resource requires a superseding Accepted ADR with written permission
or explicit acceptance and implementation of complete GPL distribution obligations.

## Follow-ups

- `RIM-003`
- `RIM-008`
- `RIM-011`
- `SEC-010`
- `REL-003`
- `REL-007`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing/concurrent changes; KSP-012 does not stage,
  commit or push.
