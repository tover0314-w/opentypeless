# Task Report: RIM-002 pinned native runtime

## Result

DONE

## Scope

- Implemented: a pinned librime 1.17.0 arm64-v8a/x86_64 runtime AAR, exact source and binary
  provenance, license notice surface, native load/version/finalize probe, architecture gate and
  exact repository/APK resource policy.
- Not implemented: Schema import/deploy, language data, preedit, candidates, product Rime
  activation, UserDB or engine switching. Those remain RIM-003..009.

## Changes

- `third_party/rime/runtime`: fixed source-first recipe, OpenCC Android patch, JNI source, minimal
  capability-free Java probe, notice and deterministic AAR builder.
- `opentypeless-rime-runtime-1.17.0.aar`: four exact source-built native libraries and no assets.
- app Gradle: requires the exact AAR SHA-256 before configuration and packages it locally.
- app legal notices: exposes the fixed native engine notice without introducing an Android
  permission or component.
- KSP-012 policy: exact repository/product identities for the Rime pair on both ABIs; current
  product/test APK hashes are reviewed and unknown drift fails closed.
- `rime_runtime_contract.py`: source/AAR/class/native/Gradle/notice/no-early-activation gate with
  seven hostile fixtures, wired into canonical preflight.

## Architecture

- contracts: `RimeAdapter.probe()` accepts only a bounded private root, creates `shared` and `user`
  children, initializes the fixed engine, requires version `1.17.0`, and always finalizes.
- state changes: probe state is temporary under `noBackupFilesDir`; the production IME service
  does not create or call the adapter in RIM-002.
- migration: none.
- feature flag: none; Rime remains unavailable to users until RIM-003/004 provide lawful data and
  an engine implementation.

## Security & privacy

- data sent/stored: no network data; the native-load probe creates and removes two empty bounded
  private directories. No Schema, dictionary, conversion data or UserDB is packaged.
- permissions/components: none added; Debug and Release manifest allowlists pass unchanged.
- threat considerations: the AAR has exactly eight entries, no assets, no `Context`,
  `InputConnection`, editor writer, network or reflection capability. Four native hashes, AAR hash,
  build recipes and notices are fail-closed. KSP-012 scans report real Xiaohè 0 and violations 0.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| RIM-002 hostile contract | PASS | 7/7; source, AAR, native set, notice and no early activation. |
| KSP-012 policy suite | PASS | 36/36; repository scan 1,141 files, 5 containers, 8 exact native identities, real Xiaohè 0, violations 0. |
| `scripts/verify_android.sh all` | PASS | strict offline clean build; scripts 119/119, Android scripts 11/11, architecture Python 209/209, Gradle 191 tasks (187 executed, 4 up-to-date), metrics reported 1,126 XML tests. |
| clean product/test artifact scan | PASS | product: 3 APKs, 16 exact native members, 0 language assets/violations; test: 2 APKs, 0 resources/violations. |
| Xiaomi 10 Ultra API 33 arm64 | PASS | exact clean Debug/Test installed; `RimeNativeRuntimeInstrumentedTest` `OK (1 test)`; PangIME remained default. |
| Android Emulator API 35 arm64 | PASS | exact clean Debug/Test installed; same test `OK (1 test)`. |
| current product x86_64 instrumentation | NOT RUN | no accelerated x86 guest kept active; exact x86 bytes are identical to the KSP-009 API 26 x86_64 dynamic-pass artifact. |
| new source-first native compilation | NOT RUN | current workstation lacks pinned NDK r26b/CMake 4.0.2; this task recovered and reverified exact prior source-built bytes and does not claim a new native compile. |

Historical fail-closed probes were retained: direct execution of the non-executable AAR builder;
an AAR manifest missing `package`; implicit legacy permissions caused by a missing library
`uses-sdk`; a non-transitive AAR resource symbol; a legal-notice filename colliding with the data
scanner; a wrong architecture-test working directory; and the first clean artifact hash not yet in
the reviewed policy. Each failure occurred before acceptance, was fixed without relaxing a safety
gate, and the final clean command above exited zero.

## Evidence

- RIM-002 accepted probe-only AAR: 8,847,684 bytes, SHA-256
  `cc7e29bd34a65b335c603ad4f2c758f84b68fa336d959811a7da278931ab6bf3`.
- Current RIM-003 AAR extends only the Java façade with bounded `dryDeploy()` while retaining the
  exact four native bytes: 8,848,608 bytes, SHA-256
  `96dc764b2b8a045c7f34e13b969434bf6104fa2414552e79679dc16acd56da76`.
- Debug APK: 65,386,500 bytes, SHA-256
  `151d3357d4257fe6fd2031d800e7b37717aeb28d3fe237b0d4d04c6440165fd9`.
- unsigned Release APK: 63,541,645 bytes, SHA-256
  `a475b525e5109a457b1ff33fa648eab46ceafe5e4a4edf122592c5a24f576540`.
- AndroidTest APK: 1,076,899 bytes, SHA-256
  `c3b736214560b0ff77a5f048958a3dc078fbc98065aafc896e4c95669546d3b5`.
- product/test scan manifests:
  `5bf265a5c6dd9381af7f32a9be1bd73a0106cbe2fb5bc10334d7102a983f7d96` /
  `efe77906f10233bfcd690f7ef68d5f14234397c54891f78f93b27d7bc47a28f2`.
- fixed native identities and source closure: `third_party/rime/runtime/PROVENANCE.md`.

## Risks

- This task proves that the engine can load; it does not yet provide Chinese input. RIM-003..005
  remain the shortest path to user-visible typing.
- Formal release SBOM/source bundle/native-link manifest and signed release distribution remain
  later release gates; they do not block personal debug use.

## Rollback

Remove the local AAR dependency, runtime directory, app notice, probe test and RIM-002 gate; restore
the previous KSP-012 exact baselines. No user data, permission, component or migration is involved.

## Follow-ups

- `RIM-003`
- `RIM-004`
- `RIM-005`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
