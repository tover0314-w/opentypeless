# Task Report: RIM-006 Schema switching and options

## Result

DONE

## Scope

- Implemented: installed-Schema selection plus the closed `simplification`, `ascii_punct` and
  `full_shape` option set; private persistence, removed-Schema repair, settings UI and restoration
  before the first Rime key of a new session.
- Not implemented: persistent UserDB lifecycle, real Xiaohè payload, Voice/Rime arbitration,
  import/export or diagnostics. Those remain RIM-007..011.

## Changes

- `RimeRuntimeConfig` and `RimeRuntimePreferences`: validate the selected Schema against the active
  local package, persist only three booleans plus the Schema ID, and repair a removed selection to
  the first installed Schema.
- `RimeResourceActivity`: lists installed Schemas and exposes simplified output, ASCII punctuation
  and full-shape controls in Chinese and English; ASCII punctuation and full-shape cannot both be
  enabled.
- `NativeRimeInputEngine`, `RimeAdapter` and JNI: open the selected Schema, apply the exact three
  options before the first key, read back every native option write and restore them after session
  recreation.
- `OpenTypelessImeService`: loads the validated package/configuration off the main thread and never
  substitutes an unknown or removed Schema.
- contract and device tests: cover persistence, repair, option ordering, restart and actual-librime
  switching between two local synthetic Schemas.

## Architecture

- contracts: Schema IDs are bounded and must occur in the installed package list. Native option
  names are a three-value allowlist, not caller-provided strings.
- state changes: configuration is applied on the next Rime session; it does not mutate an active
  editor target or bypass the existing generation/composition route.
- migration: none. The private preference file is versioned in its name and stores no user text.
- feature flag: none; Rime remains unavailable until a verified local package exists.

## Security & privacy

- data sent/stored: no network. Preferences contain one Schema ID and three booleans only. Imported
  resources remain in no-backup private storage and are not exported.
- permissions/components: none added.
- threat considerations: unknown Schema/option names, conflicting punctuation modes, native option
  mismatch, removed package entries and corrupt preferences fail closed. The JNI adapter still has
  no Android context, editor capability or resource payload.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| two-ABI source-first native rebuild | PASS | API 26, CMake 4.0.2, NDK r26b; both exact adapter hashes and host-path leak checks passed. |
| final strict clean Gradle graph | PASS | 186 tasks: 182 executed, 4 up-to-date; Debug/Release JVM 1037/1037 each; Release lint 0 errors/8 warnings; all five APKs built. |
| `scripts/verify_android.sh preflight` | PASS | 120 script tests and 224 architecture tests; RIM-001..006 and all prior P0 boundaries passed. |
| KSP-012 resource tests and APK scans | PASS | 37/37 hostile tests; three product and two test APKs contain real Xiaohè=0 and violations=0. |
| actual librime configuration, Xiaomi 10 Ultra API33 | PASS | 1/1; persisted alternate Schema/options, restored them in a new session, then switched to local. |
| actual librime configuration, API35 arm64 emulator | PASS | Same final Debug/Test bytes and same 1/1 matrix. |

## Evidence

- Debug APK: 65,443,068 bytes, SHA-256
  `d9d8c5db6f6c77fda12a511c9e4497c0267f37c86c38c07e757e65f1f1c73e9a`.
- unsigned Release APK: 63,582,173 bytes, SHA-256
  `406f626034bf83dba5af0f320af137ed92d02e5675bcf52ca9928d8218d6fd45`.
- app AndroidTest APK: 1,084,527 bytes, SHA-256
  `a724b601822b0e49b2c720979256508b8bf641e711d54d4a695bb5d31f3b1e7e`.
- final runtime AAR: 8,856,085 bytes, SHA-256
  `51d00e323ac9721b83f835be02608f65ee897fad1faf5449d438f1876292937f`.
- arm64/x86_64 JNI adapter SHA-256:
  `eb68314bbd07a10cdcdb6fcbb158beaec71d24d392f7a6d75c221ac4eed416a3` /
  `7718849a0ac5146f63ed4219ca71c82de8122c0a0fbd808490a3ff70b06ac3e2`.
- Resource policy canonical SHA-256:
  `0cb20656326c0fbed090d7bea42574bcb27e7422f955f1262d9eb8fdf82ccd39`.
- Synthetic local package: 3,175 bytes, SHA-256
  `70e2f801d946713cce45a95163e1007fa675c7012736b9c59be8837326570abd`;
  test-selected package and runtime state were removed afterward.

## Risks

- This proves the configuration seam with two local synthetic Schemas; it does not grant a license
  to bundle a real Xiaohè resource.
- Settings take effect on the next Rime interaction, intentionally avoiding mid-composition Schema
  replacement.
- Release is unsigned verification evidence.

## Rollback

Remove the configuration preference/UI seam and construct the engine with the package's first
Schema plus default options. No user text or UserDB migration is involved.

## Follow-ups

- `RIM-007`
- `RIM-008`
- `RIM-009`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
