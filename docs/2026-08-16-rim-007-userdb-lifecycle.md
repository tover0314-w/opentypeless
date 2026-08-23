# Task Report: RIM-007 UserDB lifecycle

## Result

DONE

## Scope

- Implemented: private Rime UserDB ownership, bounded native synchronization, local recovery
  checkpoint, one-shot restore/retry, clear controls and fresh-process device validation.
- Not implemented: real Xiaohè resources, UserDB export/sync, Voice/Rime arbitration or diagnostics.
  Those remain RIM-008..011.

## Changes

- `RimeUserDataStore`: owns the versioned no-backup UserDB directory, an independent recovery
  checkpoint and atomic restore/clear operations. Only root-level `*.userdb` files are copied.
- `NativeRimeInputEngine`: takes one exclusive UserDB lease, opens native Rime with separate shared
  and user directories, restores at most once after an open failure, and orders candidate commit as
  native select -> terminal synchronize/close -> local checkpoint -> editor delivery.
- `RimeAdapter` and JNI: expose the reviewed shared/user directory split and terminal
  `RimeSyncUserData` edge; sync finalizes the native engine before Java creates a checkpoint.
- `RimeResourceActivity`: shows content-free learning-data status and separate restore/clear
  controls. Clearing imported resources does not silently erase UserDB, and no export/upload exists.
- contract, JVM and device tests: cover bounds, symlink rejection, interrupted recovery, BUSY,
  ordering, one restore only, checkpoint failure and actual librime learning across a process death.

## Architecture

- contracts: a fair process-local semaphore permits one native/UserDB owner; management operations
  return stable `BUSY`, `STORAGE_FAILED`, `LIMIT_EXCEEDED` or `NO_CHECKPOINT` results.
- state changes: `current`, `checkpoint`, `.checkpoint-new/old` and `.restore-new/old` use same-parent
  atomic renames and deterministic interrupted-operation recovery.
- migration: first version uses `noBackupFilesDir/rime_user_data_v1`; no previous product UserDB
  existed, so no plaintext or persistent-format migration is required.
- feature flag: none. Rime remains unavailable until a locally validated resource package exists.

## Security & privacy

- data sent/stored: no network. Only bounded local `*.userdb` files are retained; resources,
  generated cache, logs, diagnostics and Secret data are excluded. UserDB is not exported or backed
  up by Android cloud/device transfer.
- permissions/components: none added.
- threat considerations: maximum 2,048 files, 16 MiB per file, 64 MiB total and depth 16; symlink,
  special file, scope collision, sync failure and checkpoint failure all fail closed before editor
  delivery. Restore is attempted once, never an unbounded corruption loop.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| source-first runtime AAR rebuild | PASS | Fixed arm64-v8a/x86_64 native bytes; terminal-sync Java adapter compiled and repackaged. |
| final strict clean Gradle graph | PASS | 186 tasks: 182 executed, 4 up-to-date; Debug/Release JVM 1044/1044 each; Release lint 0 errors/8 warnings; five APKs built. |
| `scripts/verify_android.sh preflight` | PASS | 120 script tests and 235 architecture tests; all prior P0 plus the RIM-007 boundary passed. |
| KSP-012 resource tests and final APK scans | PASS | 37/37 focused hostile tests; three product and two test APKs contain real Xiaohè=0 and violations=0. |
| actual librime UserDB, Xiaomi 10 Ultra API33 | PASS | Seed 1/1, force-stop main/test, fresh restart/restore/clear 1/1; runner code `-1`. |
| actual librime UserDB, API35 arm64 emulator | PASS | Same final Debug/Test bytes and same two-process 1+1 matrix. |

One early combined Python command used repository-root module names and failed import discovery; the
same suites were rerun from their owning directories and passed 37/37 plus 11/11. Earlier native
sync probes exposed that librime sync closes all sessions; the adapter was changed to make sync an
explicit terminal consistency point, then the full clean/device matrix above was rerun.

## Evidence

- Debug APK: 65,462,940 bytes, SHA-256
  `ffd20e60c005055f778f03e18dad7fa555554219936cd93c03cfae1e7fb9c0fc`.
- unsigned Release APK: 63,585,657 bytes, SHA-256
  `b19ed7d0f78852541606ea98a8dd82cc61f3711f0e148e9ff52d1bff57feed44`.
- app AndroidTest APK: 1,086,291 bytes, SHA-256
  `7fdc61bd7fa7431fb3035c2fa48f985381d536aeb0c80e87ee107685f9dccfe6`.
- final runtime AAR: 8,856,504 bytes, SHA-256
  `51e173946e048554aa0eced183231b3c1e7ccae0ed485027d79312211bc57dd3`.
- Java adapter SHA-256:
  `8c885073b997d86debfadda2135a1551d9f760c35d03d1770cc535a9f150bd13`.
- resource policy canonical SHA-256:
  `6dbbe1771dacdf3d7fe81de501e525e16631811c91506b00e52e586f130685ec`.
- final product APK scan: 3 artifacts, 122 members, 16 exact native engines, real Xiaohè=0,
  violations=0; manifest SHA-256
  `92825c6181e1a2f08c9865ccc7ae49bb2f6d96963436f03223e365733f2b305e`.
- final test APK scan: 2 artifacts, 38 members, real Xiaohè=0, violations=0; manifest SHA-256
  `b3e31a88021d4f9ae927aae75efcd539a381cf234264d08ed3fdfc8781c59621`.
- synthetic local package: 3,175 bytes, SHA-256
  `70e2f801d946713cce45a95163e1007fa675c7012736b9c59be8837326570abd`;
  device tests remove the selected copy, imported resources and UserDB afterward.

## Risks

- The recovery point is local crash/corruption recovery, not cloud backup, cross-device sync or a
  general export format.
- This proves actual synthetic learning and ordering; it does not grant a license to bundle real
  Xiaohè data.
- Release is unsigned verification evidence.

## Rollback

Remove the UserDB store/settings seam and construct Rime with an ephemeral user directory. The
versioned private directory can then be deleted without changing imported resources or editor data.

## Follow-ups

- `RIM-008`
- `RIM-009`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
