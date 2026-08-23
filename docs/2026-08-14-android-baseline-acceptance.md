# Android current baseline acceptance report

Date: 2026-08-14  
Branch: `agent/android-offline-followup`  
Repository HEAD: `80d20496c4eb59e4f27281becfa8a32021212e53`  
Candidate content SHA-256: `f03bddd9627149725ee1095b4c84033b0795cc256a020affe902a80dcebc997d`  
Release decision: **CONDITIONAL — not a releasable or signed candidate**

## Candidate identity

This report describes the exact local candidate content that was verified on 2026-08-14. The
candidate is not represented by HEAD alone: excluding this report, the shared worktree contains 117
modified or untracked status entries and 946 tracked-or-untracked source files. The content digest
above is therefore the authority for this report. It excludes this report itself to avoid a self-referential hash, but
includes every other non-ignored repository file, including the README and specification updates
that point to this report.

The digest is reproducible from the repository root with:

```bash
python3 - <<'PY'
import hashlib
import pathlib
import subprocess

root = pathlib.Path('.').resolve()
excluded = 'docs/2026-08-14-android-baseline-acceptance.md'
paths = subprocess.check_output(
    ['git', 'ls-files', '-co', '--exclude-standard', '-z'], cwd=root
).decode().split('\0')
entries = []
for relative in sorted({path for path in paths if path and path != excluded}):
    data = (root / relative).read_bytes()
    entries.append(
        f'file\0{relative}\0{len(data)}\0{hashlib.sha256(data).hexdigest()}\n'.encode()
    )
print(hashlib.sha256(b''.join(entries)).hexdigest())
PY
```

`gh run list --commit 80d20496c4eb59e4f27281becfa8a32021212e53` now returns successful
historical runs for the immutable base HEAD, including CI run `31538576600`, Typos, PR Labeler and
PR Title checks. Those runs predate and do not contain this dirty candidate digest. GitHub-hosted
checks for the exact candidate are therefore **NOT RUN**, and this report does not attribute the
base-HEAD results to unpushed worktree content.

## Build and runtime identity

| Item | Recorded value |
|---|---|
| Host | macOS 14.8.2 arm64 |
| JDK / Gradle | OpenJDK 17.0.20 / Gradle 8.11.1 |
| Android SDK | compileSdk 35, Build Tools 35.0.0 |
| Android app | versionName `0.3.0`, versionCode `3` |
| Voice editor transaction flag | enabled by default; new and legacy writers must remain mutually exclusive when wired |
| Config formats | GlobalConfig 1; OverrideValueCodec 1; SecretStore format 1 / migration 1 |
| Offline model | `sensevoice-small-int8-2024-07-17`, revision `2365baeacb507f821a0c8120fcee3d484dba7a07` |
| Model artifact | 239,233,841 bytes, SHA-256 `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51` |
| Tokens artifact | 315,894 bytes, SHA-256 `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc` |
| Sherpa Android runtime | `sherpa-onnx-asr-1.13.4.aar`, SHA-256 `35af2790bfcb39a1bfe6d0d495193b7fadc367c5c6f07e5e95996ba210cb9196` |

## Automated verification actually run

| Suite / command | Result | Reproducible evidence |
|---|---|---|
| Root verifier tests | **PASS — 30/30** | `python3 -m unittest discover -s scripts -p 'test_*.py' -v` |
| Android source architecture | **PASS — 96/96** | canonical preflight / architecture suite |
| App JVM tests | **PASS — 781/781** | 122 JUnit XML suites under `android/app/build/test-results/testDebugUnitTest` |
| Compiled architecture gate | **PASS — 95/95** | Debug and Release production variants 2/2 |
| CI-style Unit/Architecture stage | **PASS** | 67 Gradle tasks, 16 seconds |
| CI-style Lint stage | **PASS** | 24 Gradle tasks, 27 seconds; HTML/XML reports generated |
| CI-style Assemble stage | **PASS** | 164 Gradle tasks, 35 seconds; five APKs generated |
| Canonical `scripts/verify_android.sh` | **PASS** | final exact-candidate run: 187 tasks, 184 executed / 3 up-to-date, 55s; 876 XML tests |
| Task-specific Gradle user-home strict verify | **PASS** | dependency cache began empty; pinned Gradle 8.11.1 wrapper distribution was preseeded after two download-only 10s timeouts; final rerun remained strict |
| GitHub Action pinning | **PASS** | 13 workflows, 57 remote uses, 21 exact audited action surfaces |
| Remote `main` branch protection | **PASS** | live REST readback: 15 strict checks, PR/admin enforcement, no force push/delete |
| Base-HEAD GitHub workflows | **PASS** | historical CI run `31538576600` and companion checks succeeded for immutable HEAD only |
| Exact-candidate GitHub workflows | **NOT RUN** | dirty candidate digest was not pushed and is not covered by the base-HEAD runs |

The canonical verifier executes dependency/AAR/SDK/source checks before Gradle and then runs
`clean`, `:architecture-gate:check`, `testDebugUnitTest`, `lintRelease`, `assembleDebug`,
`assembleRelease`, and `assembleDebugAndroidTest` with strict dependency verification. Passing the
local verifier is not a substitute for remote required checks or device instrumentation.

## Generated Android artifacts

| Artifact | SHA-256 | Status |
|---|---|---|
| `android/app/build/outputs/apk/debug/app-debug.apk` | `03d21497e49d88cbc5d6706aa066cc6261e32303635db681ed47a2e8fc9fa409` | local debug artifact; final package run on Xiaomi |
| `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `64baba34787850e1cb3dc9578f98b44a6e664b14b16238a15cc14cb484ef1ccb` | local instrumentation artifact; final package run on Xiaomi |
| `android/app/build/outputs/apk/release/app-release-unsigned.apk` | `df213d860bfe5e6e2941d0a609be7a1c2a945bb309a101eeb810ae0482594fb0` | **unsigned; not distributable as a trusted release** |
| `android/test-host/build/outputs/apk/debug/test-host-debug.apk` | `8903e723776793115977612132d97006f0d32f3a8f01eb1a373639b6213bc8b7` | local test-host artifact |
| `android/test-host/build/outputs/apk/androidTest/debug/test-host-debug-androidTest.apk` | `7d9f5df75047aacf2b7654818e35796f3df397f9dcc2b1ae6b991fda2eebf882` | local test-host instrumentation artifact |

## Real-device verification

Device under test: Xiaomi 10 Ultra, model M2007J1SC (`cas`), Android 13 / API 33, HyperOS
OS1.0.4.0.TJJCNXM. The ADB serial is not stored; its correlation SHA-256 is
`632b0245195ea6204547f6e9b5fcbd699d5a7350250daecbd5f39c200bb12cd7`.

| Scenario | Result | Evidence / boundary |
|---|---|---|
| ADB identity and authorization | **PASS** | device state `device`; model/OS/API read successfully |
| No-password/no-lock state | **PASS** | `locksettings get-disabled=true`, `deviceLocked=0`, no credential; no swipe/keyguard gate is required for unattended overlays |
| Burn-in protection | **PASS** | 10-minute screen-off retained; stay-awake disabled; device remained Dozing after the run |
| Main App installation | **PASS** | final clean debug APK and AndroidTest APK both installed unattended while the device was Dozing |
| CMP-005/006 device instrumentation | **PASS — 3/3** | final clean APK pair ran `VoiceEditorTransactionSessionInstrumentedTest`; 0 failures/errors, 0.037s; the new test exercises screen-off receiver cancel-once behavior |
| Test Host installation | **FAIL** | the separate new package remains blocked by `INSTALL_FAILED_USER_RESTRICTED`, including a retry after wake and `showing=false`; no Test Host tests ran |
| Full Main App instrumentation | **NOT RUN** | only the exact CMP-005 class ran on the final candidate; an older broad run had stalled at AppPicker and is not attributed to this candidate |
| API 26/33/35/36 emulator matrix | **NOT RUN** | requires a pushed GitHub workflow or explicit local emulators |
| Xiaomi 15 acceptance matrix | **NOT RUN** | attached hardware was Xiaomi 10 Ultra, not Xiaomi 15 |

The remaining HyperOS restriction is per new package: prior attempts blocked Test Host even with no
password, while the signed main/test pair now overlays and runs unattended. Device work must resume
with an explicit OEM confirmation for the Test Host package, followed by a fresh broad instrumentation
run and AppPicker diagnosis. The screen should continue to turn off between runs to avoid OLED
burn-in; the 10-minute timeout and Dozing state remain active.

## Privacy and security invariants

| Invariant | Result |
|---|---|
| Gradle dependency verification remains strict | **PASS** |
| Remote Actions use immutable audited commits and least root permissions | **PASS** |
| Android SDK and emulator package coordinates are pinned and fail closed | **PASS** |
| Editor writes remain behind architecture/source/compiled gates | **PASS** |
| No API keys, transcripts, model payloads, or raw device serial are embedded in this report | **PASS** |
| CMP-005 owner/ticket plus CMP-006 receiver lifecycle on real Xiaomi device | **PASS — 3/3** |
| Sensitive-field, real microphone, and full system-IME lifecycle on Xiaomi | **NOT RUN** |

## Known limitations and blockers

- The exact candidate content is a dirty shared worktree, not an immutable release commit. A clean
  task-scoped commit and fresh verification are required before publication.
- Remote required checks have not run for this candidate.
- Xiaomi 10 Ultra now passes the exact CMP-005/006 runtime tests, but Test Host installation remains
  OEM-restricted and the final candidate has not completed the broad Main App or real microphone/
  system-IME screen-off instrumentation suites.
- The release APK is unsigned. No APK in this report is a trusted distribution artifact.
- The full implementation backlog remains incomplete; passing build gates does not mean the entire
  IME/product roadmap is complete.
- Xiaomi 15, API 26/35/36 emulator behavior, upgrade/migration matrices, signed release rehearsal,
  and full physical privacy/recognition/performance acceptance remain open.

## Release decision

**CONDITIONAL / NOT RELEASE-READY.** The local candidate has a strong reproducible build, JVM,
architecture, lint, supply-chain, and artifact baseline. It must not be published or described as
a complete app release until the candidate is committed cleanly, remote required checks pass, the
remaining Xiaomi/Test Host instrumentation gaps are resolved, the required emulator/real-device matrices pass,
and signed release evidence is produced.

## Reproduction

From the repository root, with JDK 17 and Android SDK 35 configured:

```bash
python3 -m unittest discover -s scripts -p 'test_*.py' -v
scripts/verify_android.sh

# Each CI-style stage can also be reproduced independently:
scripts/verify_android.sh preflight
scripts/verify_android.sh unit
scripts/verify_android.sh lint
scripts/verify_android.sh assemble

# Device-only; replace with the explicitly selected device serial:
ANDROID_SERIAL='<xiaomi-serial>' scripts/verify_android.sh instrumentation
```

Do not interpret an assembled AndroidTest APK, an uploaded report directory, or a started runner as
a device-test pass. Record the runner's completed test count and final exit result.
