# Task Report: TST-001 IME Test Host field matrix

## Result

PARTIAL — PERSONAL-USE P0 DONE

## Scope

- Implemented: all platform-backed personal-use scenarios F01–F21 and F23–F24: nineteen static
  `EditText` fields, selected/collapsed ranges, two independent fields, dynamic editor destruction and
  recreation, single-line Done, RTL, and a local `WebView contenteditable` editor with a real
  `InputConnection`.
- Not implemented: F22 Compose `TextField`. The product has no Compose runtime and `UI-001` remains
  TODO. Adding Kotlin/Compose only to a test APK would add a dependency and compiler surface without
  improving the current personal-use product, so this case is explicitly deferred to `UI-001`.

## Changes

- `TestHostActivity`: adds visible-password, number-password, single-line Done, RTL and WebView
  fields. The WebView loads only a literal local page, has no INTERNET permission, and blocks
  network, file and content access.
- `TestHostInstrumentedTest`: verifies the complete platform input-type matrix, selected and
  collapsed ranges, text isolation, dynamic editor replacement, Web content/selection/focus and
  the WebView `InputConnection`/text `EditorInfo` contract.
- selected-system-IME matrix: ordinary specialized fields still use the exact OpenTypeless layout;
  password variations either use OpenTypeless or the exact MIUI security-keyboard handoff for the
  currently served password field.
- KSP-012 artifact expectations: replace incremental identities with the two exact clean Test Host
  APK identities; no new resource or engine is allowed.

## Architecture

- contracts: Android platform editor fixtures only; the Test Host is an isolated Debug application
  and is never packaged into the product APK.
- state changes: dynamic removal destroys the old `EditText`; recreation starts empty and produces
  a different View instance.
- migration: none.
- feature flag: none.

Compose remains a later management-UI dependency. This task does not add Compose, Kotlin, a product
WebView, an exported product component, or a second editor writer.

## Security & privacy

- data sent/stored: none; the literal WebView page contains only synthetic `alpha beta` test text.
- permissions/components: no permission added; the existing DUMP-protected Test Host activity is
  unchanged.
- threat considerations: WebView JavaScript is test-only and deterministic; navigation plus network,
  file and content access are disabled. The Test Host does not depend on the product module and owns
  no production data.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `scripts/verify_android.sh` | PASS | clean strict offline graph: 191 tasks, 187 executed / 4 up-to-date; 119 script tests, 176 architecture tests, Debug/Release compiled gate, app JVM, lint and five APKs. |
| KSP-012 post-build APK scan | PASS | 3 product + 2 test APKs; real Xiaohè 0, forbidden Rime resource 0, violations 0. |
| API 35 ARM64 emulator Test Host | PASS | platform matrix 4/4; selected-system-IME specialized/sensitive matrix 2/2; LatinIME restored. |
| Xiaomi 10 Ultra API 33 ARM64 Test Host | PASS | same exact clean APKs: platform matrix 4/4; selected-system-IME matrix 2/2; PangIME restored. |
| Compose `TextField` | NOT RUN | deferred with `UI-001`; no Compose runtime exists in the current product. |

An attempted selected-system-IME WebView window assertion returned no IME accessibility window on
the emulator. It was removed rather than hidden with retries. The retained deterministic contract
directly verifies DOM focus, selected text and a non-null WebView `InputConnection` on both devices;
this report does not claim a full system-selected WebView typing E2E.

The first clean verification deliberately failed its post-build resource gate because the new clean
Test Host APK hashes were not yet reviewed. The policy was rebound to the clean artifacts, not to the
incremental artifacts, and the complete clean command was rerun to PASS.

## Evidence

- Test Host Debug: 13,085 bytes, SHA-256
  `908d7582c6c668466311aeb2c124167eb5985900abcece78e499bc6970e1a4e3`.
- Test Host AndroidTest: 1,692,648 bytes, SHA-256
  `fc947b05dc49d81e54076b8b0da7a7c22b3a780ac2d9716a1e16a12061c45429`.
- unchanged product Debug: 56,465,857 bytes, SHA-256
  `f175075d22efb496954ecbe20b94d9643ecfa26c0233ee9a88de0225aa83761e`.
- unchanged unsigned Release: 54,656,745 bytes, SHA-256
  `2822aa433c5783eb492048bc5e8fe575b8733a5f4756d9cd201df8ba040cbc09`.
- resource-policy canonical SHA-256:
  `6faaaee349b533d0bce91e0d1091ec32ae3fbdb7a85ae63c914a38ac90267a8c`.

## Risks

- F22 remains untested until the product actually adopts Compose under `UI-001`; therefore the
  original full TST-001 backlog item remains PARTIAL even though its personal-use P0 subset is done.
- The WebView result is a stable editor-contract test, not a system-selected IME end-to-end claim.
- Orientation/process-recreation endurance remains later TST coverage; it is not required for this
  bounded personal-use matrix.

## Rollback

Remove the added Test Host fields/tests and restore the previous two reviewed artifact identities.
No product runtime or persisted data needs rollback.

## Follow-ups

- `UI-001`
- `TST-002`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
