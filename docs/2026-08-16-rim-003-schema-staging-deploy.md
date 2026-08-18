# Task Report: RIM-003 Schema staging/deploy

## Result

DONE

## Scope

- Implemented: explicit Android SAF local selection; closed-world manifest v1 and ZIP validation;
  copy-once app-private staging; native librime dry deploy; atomic current/rollback activation;
  preview, confirmation, installed-state display and clear; exact source/artifact gates.
- Not implemented: bundled or downloaded Xiaohè data, composition/preedit, candidates, engine
  selection, UserDB lifecycle, export or automatic update. Those remain RIM-004..009/011.

## Changes

- `RimeResourceManifest`, `StrictBoundedJson`: exact manifest v1 reader with duplicate/unknown-key,
  Unicode, count, size, path, dependency, role and librime compatibility limits.
- `RimeResourceArchive`: ZIP central-directory validation, exact member/hash/size set, compression and
  expansion bounds, traversal/collision/link/special/executable rejection and bounded data-only YAML.
- `RimeResourceStore`: no-backup staging, one active operation, dry deploy, atomic switch/rollback,
  abandoned-stage cleanup and verified clear.
- `RimeResourceActivity`: private `FLAG_SECURE` management UI using only
  `ACTION_OPEN_DOCUMENT`; unverified/local-only preview and explicit install/clear confirmations.
- pinned runtime AAR: adds only `RimeAdapter.dryDeploy()` over the already audited native deploy
  symbol; the four native binaries remain byte-identical.
- source, manifest and KSP-012 gates: exact local-import source identities, non-exported component,
  reviewed AAR/APK identities and no bundled real Xiaohè resources.

## Architecture

- contracts: `opentypeless.rime-resource-manifest` v1 is now a production reader authority. Unknown
  versions/keys, extra/missing members and undeclared dependencies fail closed.
- state changes: selected URI bytes are copied once to `.staging-<UUID>/incoming.zip`, extracted to
  separate `shared`/`user`, dry-deployed, then atomically renamed to `current`; the former current is
  retained only as a bounded rollback until the switch succeeds.
- migration: none. No previous production Rime resource format existed.
- feature flag: none. The importer is available in Settings, while product typing remains Latin-only
  until RIM-004/005 activate a Rime engine.

## Security & privacy

- data sent/stored: no network I/O. User-selected resource bytes stay under
  `noBackupFilesDir/rime_resources`, are not exported, logged, snapshotted or added to tests.
- permissions/components: no permission added; one non-exported activity added. Source and actual
  Debug/Release merged-manifest gates pass; backup and device transfer remain deny-all.
- threat considerations: bounded archive/file/YAML limits; no ZIP64, encryption, symlink, hardlink,
  special/executable entry, Lua/native/script/network reference, alias bomb, extra file or trust
  elevation. Self-declared rights remain `USER_PROVIDED_UNVERIFIED` / `LOCAL_ONLY`.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| RIM-003 JVM contract | PASS | 11/11 manifest, archive, rollback, tamper, bomb and cleanup cases. |
| RIM-003 hostile source gate | PASS | 7/7; SAF/no-backup/atomic/private/no-network invariants. |
| KSP-012 resource policy | PASS | 37/37; exact importer source drift and unreviewed decoder/store paths fail closed. |
| `scripts/verify_android.sh all` | PASS | clean strict dependency verification; 191 Gradle tasks (187 executed, 4 up-to-date), 1,137 XML tests, lint, Debug/Release/AndroidTest and compiled architecture. |
| final product/test APK scan | PASS | product: 3 APKs/16 exact native entries/0 real Xiaohè/0 violations; test: 2 APKs/0 resources/0 violations. |
| API 35 arm64 emulator | PASS | final Debug/Test installed; native dry deploy, full stage/deploy/clear and private activity `OK (3 tests)`. |
| Xiaomi 10 Ultra API 33 arm64 | PASS | same final APKs and synthetic local package; `OK (3 tests)`; PangIME remained default. |

Historical fail-closed probes: the first full run rejected an unlisted private activity, the resource
gate rejected the new decoder/store chain and changed AAR, architecture scanning mistook ZIP
`getMethod()` for reflection, Release lint rejected API-33 `readNBytes` at minSdk 26, and the first
post-build scan rejected new APK hashes. Each was fixed by narrowing or pinning the implementation;
no gate was disabled or baselined away.

## Evidence

- Runtime AAR: 8,848,608 bytes, SHA-256
  `96dc764b2b8a045c7f34e13b969434bf6104fa2414552e79679dc16acd56da76`.
- Debug APK: 65,425,204 bytes, SHA-256
  `e6e3f6fb8bb241e904e653d747e2336577d2c2ab8f853bbbe2b3b15b477fe9ce`.
- unsigned Release APK: 63,563,965 bytes, SHA-256
  `d9bee9258826004a956df0832f504445479a2130650f8efd67401817b4fc24aa`.
- AndroidTest APK: 1,078,727 bytes, SHA-256
  `aeae54fe97edfe14f41842863753f693ec51c85701664e10aa9d7aa2e42ba669`.
- Product/test scan manifests: `4b380194ed5c961dbbb496ed1d006c16d9f5c0247843a92dc95ca1f0448b1c3e` /
  `0fa50416b0bc6dd7a51e5282fb2ddc6cd557c6d025476cbcb66c3799fdb407f8`.
- Device package was generated only in `/private/tmp`, 1,345 bytes, SHA-256
  `130aa0a3a4128a6836b909ddd8b6a55f0011e8529bc59423a4ca54319dfc07ce`;
  tests deleted the device copy and active resource state.

## Risks

- RIM-003 validates deployment but does not yet make Chinese typing visible. RIM-004/005 are the
  next personal-use blockers.
- The unsigned Release is verification evidence, not a distributable signed release.

## Rollback

Remove the importer package/activity/tests/gate, restore the probe-only AAR, exact policy identities
and manifest allowlist. Imported data can be cleared from Settings; failed deployment already retains
the previous current package.

## Follow-ups

- `RIM-004`
- `RIM-005`
- `RIM-006`
- `RIM-007`
- `RIM-008`
- `RIM-009`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
