# Task Report: SEC-005 Sensitive-field toolbar policy

## Result

DONE

## Scope

- Implemented: a deny-only toolbar privacy projection; sensitive fields hide the mode and long-
  dictation controls and suppress Teach; no-learning fields suppress Teach; ordinary fields restore
  the prior toolbar visibility on the next editor transition.
- Not implemented: Action and clipboard toolbar panels, because they do not yet exist in this
  product UI. Their closed policy bits are present for their owning tasks, but SEC-005 does not add
  either feature.

## Changes

- `PrivacyPolicyEngine.HardSafety`: closed sensitive/no-learning denial input that cannot grant a
  capability.
- `KeyboardToolbarPrivacyPolicy`: pure projection for Voice, Action, clipboard and Teach visibility.
- `KeyboardToolbarLayout`: exact ID visibility changes using `GONE`/`VISIBLE`, with unknown IDs
  rejected.
- `OpenTypelessImeService`: applies the policy on toolbar creation, every `onStartInput`, and
  `onFinishInput`; the More anchor remains available while forbidden child actions stay absent.
- app JVM/View and Test Host instrumentation: sensitive/no-learning/ordinary restoration and real
  selected-system-IME transitions.
- `sensitive_toolbar_contract.py`: nine hostile source fixtures, wired into Android preflight.
- KSP-012 artifact policy: pins the exact final SEC-005 product and test APK identities.

## Architecture

- contracts: `PrivacyClassification -> HardSafety -> KeyboardToolbarPrivacyPolicy.State`.
- state changes: toolbar visibility is recomputed from the current editor; no prior field policy is
  reused as authority.
- migration: none.
- feature flag: none; hard privacy rules cannot be disabled by a flag.

The policy contains no Android View, editor, network, native, reflection or persistence capability.
The service consumes the precomputed state and changes only the existing bounded toolbar controls.
Unknown action IDs fail closed. Teach remains dependent on learning authorization.

## Security & privacy

- data sent/stored: none.
- permissions/components: none added.
- threat considerations: sensitive fields hide Voice controls rather than merely disabling them;
  no-learning cannot reopen Teach; leaving a restricted field restores only the policy-defined
  ordinary state. Diagnostics contain only booleans and never field text or metadata.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| SEC-005 source contract | PASS | 9/9 hostile fixtures. |
| toolbar/privacy JVM | PASS | new toolbar policy 4/4; existing privacy engine 12/12. |
| final clean strict offline Gradle graph | PASS | 191 tasks: 187 executed, 4 up-to-date; app JVM 988/988, architecture gate 114/114, Debug/Release compiled gate 2/2, `lintRelease`, all app/Test Host APKs. |
| final Test Host clean rebuild | PASS | 59/59 tasks. |
| KSP-012 resource contract and scans | PASS | 36/36; repository plus 3 product and 2 test APKs: real Xiaohè 0, forbidden resource 0, violations 0. |
| API 35 ARM64 emulator | PASS | direct toolbar 1/1 and selected-system-IME transition 1/1; LatinIME restored. |
| Xiaomi 10 Ultra API 33 ARM64 | PASS | direct toolbar 1/1 and selected-system-IME transition 1/1; PangIME restored. |

The selected-IME test initially returned an empty accessibility window after a freshly installed IME
was selected. Android instrumentation still returned shell status 0 even though JUnit failed, so the
final runner wrapper explicitly requires `OK (1 test)`. The test now establishes the served editor
before reading windows and retries `showSoftInput` every 500ms only while no input-method window
exists. A visible but incorrect toolbar is never retried away. Three consecutive fresh-overlay
emulator runs and the final emulator/Xiaomi runs passed.

## Evidence

- Debug APK: 56,465,857 bytes, SHA-256
  `f175075d22efb496954ecbe20b94d9643ecfa26c0233ee9a88de0225aa83761e`.
- unsigned Release APK: 54,656,745 bytes, SHA-256
  `2822aa433c5783eb492048bc5e8fe575b8733a5f4756d9cd201df8ba040cbc09`.
- app AndroidTest: 1,072,027 bytes, SHA-256
  `30324672b0ebe2649ee501c0cfe35bcf11ff507c06af5bc7d6c1af2a1c158d66`.
- Test Host Debug: 11,077 bytes, SHA-256
  `2031e3d3faa7bc5b3b59fa8de5642479d7c2b072a95dba3724fb674cef357f76`.
- Test Host AndroidTest: 1,690,500 bytes, SHA-256
  `82d27af25772b963f98992970ecabf705f7d0cc3cdd7f6e10e3150dbc6a430ff`.
- final resource-policy canonical SHA-256:
  `43bdcea83efe62a4a87d56f1b855bce9ea0e4f40058e71e4b3e93c16660a5466`.

## Risks

- Action and clipboard controls must consume the existing deny-only state when their own tasks add
  UI; their absence here is not evidence that those later surfaces are implemented.
- The system test intentionally retries only an absent IME window. It does not convert a wrong
  visible state into PASS.

## Rollback

Remove the toolbar projection, service visibility application, tests and source gate, then restore
the prior artifact identities. No stored state or migration requires rollback.

## Follow-ups

- `TST-001`
- `KBD-007`
- `KBD-008`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
