# Changelog

OpenTypeless records user-visible changes and every persisted-format, schema, or protocol version
change here. Android and desktop have independent application versions; a change to one does not
implicitly version the other. Release entries must name an immutable tag and source commit. The
`Unreleased` section describes repository candidates only and is not release evidence.

## [Unreleased]

### Added

- `RIM-001` adds a pure, bounded and capability-free Rime engine contract for lifecycle, key
  processing, snapshots and revision-bound candidate operations. It deliberately adds no JNI,
  Schema, UserDB or product wiring, so the current product remains Latin-only until RIM-002..005.
- `RIM-002` pins librime 1.17.0 and its JNI adapter as an exact two-ABI, resource-free local AAR,
  exposes the reviewed notices, adds fail-closed source/AAR/APK gates, and passes clean strict builds
  plus native load/version/finalize tests on Xiaomi 10 Ultra and API 35 arm64. It still packages no
  Schema or vocabulary and does not activate Rime before RIM-003/004.
- `RIM-003-RIME-RESOURCE-MANIFEST-V1-2026-08-16` makes
  `opentypeless.rime-resource-manifest:1` a bounded Android reader and adds explicit SAF local
  selection, no-backup copy-once staging, strict ZIP/manifest/YAML validation, librime dry deploy,
  atomic rollback and clear. Final device tests pass on Xiaomi 10 Ultra and API 35 arm64; the product
  still bundles no real Xiaohè resource and remains Latin-only until RIM-004/005.
- `RIM-004` activates a verified local Rime package in the product IME, routes printable keys and
  backspace through actual librime, and maps bounded preedit through the generation/selection-bound
  composition transaction path. Final native and exact-target tests pass on Xiaomi 10 Ultra and an
  API35 arm64 emulator; the emulator also passes a system-selected real-keyboard `a -> an -> ani ->
  an` touch chain. Candidate selection and real Xiaohè data remain RIM-005/RIM-008.
- `RIM-005` adds bounded five-item candidate pages, next/previous navigation and exact one-shot
  selection bound to the displayed candidate identity and original editor target. Actual librime,
  system-selected IME tests and an external touch run pass on the final APKs; the touch run enters
  `ni`, opens page two and commits `庚`. Real Xiaohè data, persistent UserDB and Voice arbitration
  remain later tasks.
- `RIM-006` adds installed-Schema selection plus simplified-output, ASCII-punctuation and full-shape
  options with private persistence, removed-Schema repair and native read-back. Source-first arm64
  and x86_64 builds and actual-librime restart tests pass on Xiaomi 10 Ultra and API35 arm64; no real
  Xiaohè resource, UserDB lifecycle or network path is added.
- `RIM-007` adds a bounded, no-backup Rime UserDB lifecycle with terminal native synchronization,
  atomic local recovery checkpoints, one-shot restore and explicit restore/clear controls. Actual
  learning survives a forced process restart on Xiaomi 10 Ultra and API35 arm64, while resource
  clearing, export, network and Android backup remain separate or unavailable.
- `RIM-009` adds deterministic Rime-to-Voice arbitration: the default path commits the exact visible
  Rime composition before Voice acquires ownership, while the explicit cancel path clears that same
  revision. Rejection, pending candidate work and uncertain release fail closed without a current-
  cursor fallback; the final two-class device matrix passes 32/32 on Xiaomi and API35 arm64.
- `RIM-008` validates a user-provided, officially obtained Xiaohè 4.2 package through the existing
  local-only SAF path on Xiaomi 10 Ultra. Real resource bytes remain outside source and APKs. The
  product now reopens an exact deployed private package and keeps Rime revisions monotonic across
  independent compositions, so two consecutive candidate commits, editor switching and host restart
  all pass without weakening stale-event rejection. A follow-up fixes fixed-length Rime auto-commit
  delivery and makes Space select the exact first displayed candidate; both paths pass actual-librime
  and system-selected real-touch checks with the user's local-only package.
- `KBD-009` begins the portrait QWERTY sizing slice: 50dp key rows and tighter 1dp horizontal
  margins preserve the existing Xiaohe-like row offsets and bottom-row proportions. Landscape
  compression and Xiaomi 15 acceptance remain pending.

- `TST-001` completes the lightweight personal-use Test Host matrix for every current platform
  input type, selected/collapsed ranges, dynamic editor replacement, RTL and local WebView
  contenteditable on both an API35 ARM64 emulator and Xiaomi 10 Ultra. Compose TextField remains
  explicitly deferred to `UI-001`, so the original broader task is PARTIAL rather than overstated.
- `TST-002` freezes the normative R01-R20 editor-race matrix against the existing production-path
  JVM and Android tests, with fail-closed removal/assertion-drift checks. Xiaomi and API35 arm64 each
  pass the final 32-test editor/Voice matrix plus four real Test Host field-transition tests.

- `STR-002` adds a package-confined, single-session WebSocket recognition Provider with bounded
  copied PCM frames, explicit queue/session limits, strict STR-001 event decoding, ready/final
  timeouts, cancellation, and at most one reconnect before any server evidence or accepted audio.
  Redirects and automatic retry are disabled; production routing and disclosure remain STR-010.
- `DOC-003` adds the repository compatibility inventory, its fail-closed verifier, and fault-injection
  tests. This is documentation and release-process work; it does not change a runtime format.
- `KSP-001` adds the Proposed keyboard-base evaluation ADR with evidence-weighted scoring, license and
  editor-safety hard gates, and a pinned-upstream/patch-queue contract. It selects or imports no
  keyboard implementation; only `KSP-010` may accept the final base after both spikes are verified.
- `KSP-002` validates pinned FlorisBoard `v0.5.2` source as an isolated, strict-offline, reproducible
  arm64-v8a/x86_64 Debug build and verifies install plus same-package overlay install on Xiaomi 10
  Ultra and an API 26 x86_64 guest. No third-party source, APK, dependency, or runtime path is added
  to OpenTypeless; ADR-0011 remains Proposed.
- `KSP-003` validates an isolated Floris/Dictate vertical slice whose QWERTY, candidate, toolbar and
  deterministic Voice partial/final/exact-Undo routes all use the real OpenTypeless editor transaction
  authority. Xiaomi 10 Ultra instrumentation passes 3/3 before and after unattended overlay install;
  no candidate source or APK is added to the production tree, and ADR-0011 remains Proposed.
- `KSP-010` accepts the pinned Route-A restricted Shell source boundary plus OpenTypeless/self-built
  librime adapter contract and rejects the current fcitx5 GPL payload. The rejected whole-App
  evidence artifact keeps its direct-writer and privacy failures; a separate `:route-a-safety-eval`
  module closes the selected boundary with exact ETM authority, deny-all backup/transfer, strict
  Debug/Release replay and Xiaomi API33/API26 x86_64 12/12. ADR-0011 is Accepted; KBD-001 was still
  unimplemented at that decision point and is completed separately below. This is not full-product integration, system-selected IME E2E, signed
  Release or real Xiaohè authorization, and changes no product runtime or persisted format.
- `KSP-011` adds a trusted offline Route-A upstream replay contract: exact official source/archive
  locks, a three-patch source-only restricted queue, legal/path/tree gates, safe extraction and
  deterministic `.git`-free export. Two fresh replays produce the same 972-file tree and report;
  44 adversarial fixtures pass. The historical binary evidence patch is explicitly rejected as a
  maintained input because it carries generated binaries, whole-App scope and deleted unknown-data
  preimages. This adds no runtime, permission, network fetch, product keyboard or real Xiaohè data.
- `KSP-012` accepts the zero-bundle Xiaohè resource policy. Complete Xiaohè Sound-and-Shape data and
  the official GPL Rime Xiaohè double-pinyin Schema/dependency closure remain absent from repository,
  every build/test/package, patch/snapshot/export/backup and CI surface. A future implementation may
  accept only explicit local user imports under the closed manifest v1 contract; self-declared rights
  remain `USER_PROVIDED_UNVERIFIED` / `LOCAL_ONLY`, with no auto download, update or re-export. This
  documentation task implements no Rime importer, real Xiaohè test package or runtime format.
- `KBD-001` integrates the product-owned restricted Route-A keyboard Shell into
  `OpenTypelessImeService`. The service freezes exactly one Shell for its lifetime, defaults to the
  new Route-A frame, and retains the legacy voice frame behind a synchronous, mutually exclusive
  rollback flag with no failure fallback. The Shell owns only view slots; existing live keys remain
  behind the single editor transaction authority. Debug/Release source, compiled-writer and merged-
  manifest gates pass, as do the exact three-test matrix on an ARM64 emulator and Xiaomi 10 Ultra.
  This task supplies the Shell/root and slots only; QWERTY, symbols, field layouts, toolbar placement,
  candidates and Rime remain their owning tasks.
- `KBD-002` adds the product Route-A four-row ASCII QWERTY layer with one-shot Shift, double-tap
  Caps Lock, delete, space and semantic Enter. A 2026-08-17 interaction follow-up adds separate
  Voice/QWERTY tabs, starts ordinary fields on a Typeless-inspired central microphone surface,
  keeps sensitive fields on QWERTY, tightens the rounded/staggered key layout and adds bounded
  press-and-hold backspace repeat that stops on release, cancellation and editor lifecycle. The View layer remains
  capability-free and every mutation uses the existing sole editor transaction authority. Exact
  four-test matrices pass on an ARM64 emulator and Xiaomi 10 Ultra; a system-selected emulator smoke
  wrote `abcD ` into a real Test Host field. Symbols, field layouts and toolbar placement remain
  KBD-003/004/006.
- `KBD-003` adds user-invoked `123`/`ABC` switching, two fixed symbol pages and one deterministic
  long-press alternate for every ASCII letter. Symbol and long-press output reuse the same bounded
  callback and sole editor transaction authority as QWERTY. Exact seven-test matrices pass on an
  ARM64 emulator and Xiaomi 10 Ultra; the system-selected final APK wrote `1@?[1` to a real Test
  Host field, with the final `1` produced only by long-pressing `q`. Field-specific automatic
  layouts, long-press previews/haptics and toolbar placement remain KBD-004/005/006.
- `KBD-004` adds automatic email, URL, phone, number, date and password profiles while keeping all
  writes on the existing single editor transaction path. Exact View tests pass on the ARM64 emulator
  and Xiaomi 10 Ultra; Test Host system-IME switching passes on both, with Xiaomi's own security IME
  explicitly verified for password fields. Toolbar/candidates/Rime remain later tasks.
- `KBD-005` adds immediate, text-free key feedback to the product QWERTY layer: Android-system key
  sound with configurable volume, system/forced/off haptics with three strengths, and distinct
  long-press feedback without an ordinary-click callback. The private versioned settings page adds
  no exported surface or permission; Xiaomi 10 Ultra passes the 12-case real-View keyboard matrix
  plus the persisted-settings test, and is configured to light haptics with 35% key sound.
- `KBD-006` adds a bounded Route-A toolbar with fixed mode/voice controls, one overflow anchor,
  48dp touch targets and narrow-landscape measurement. Undo remains in More, and both the ARM64
  emulator and Xiaomi 10 Ultra pass the direct View and selected-system-IME toolbar checks.
- `KBD-007` adds one engine-independent, revision-bound candidate page model and a real horizontal
  48dp candidate bar with numbered accessibility labels, stable selection/paging callbacks and
  destructive sensitive-field clearing. Latin and Rime can reuse the same contract, while actual
  candidate generation and commit remain in their owning follow-up tasks.
- `KBD-008` adds a short-press next-IME request with a fail-closed system-picker fallback, a direct
  long-press picker and a bounded LATIN/RIME engine selector. HyperOS picker switching was verified
  on Xiaomi 10 Ultra; the EN/中文 control remains hidden until Rime is genuinely registered.
- `SEC-001` adds a pure fail-closed privacy policy authority for Voice, context, history, Action,
  clipboard, learning and Teach. Sensitive fields, Android no-learning, incognito, App maximums and
  resolved profile constraints always precede UI choices, so UI state can only remove authority.
  This contract task adds no Android component, permission, persistence, network path or UI wiring.
- `SEC-002` extends the product field classifier with bounded, fail-closed password, OTP, payment and
  identity recognition and a distinct Android no-personalized-learning result. The final selected IME
  matrix passes on the ARM64 emulator and Xiaomi 10 Ultra, with both original input methods restored.
  It adds no permission, component, persistence or network path.
- `SEC-005` applies a deny-only privacy projection to the existing keyboard toolbar. Sensitive fields
  hide mode/long-dictation and suppress Teach; no-learning suppresses Teach; ordinary fields restore
  the toolbar on transition. Emulator and Xiaomi selected-system-IME tests pass, with no new
  permission, component, persistence or network path. Action/clipboard panels remain later tasks.

### Compatibility

- `KBD-001-ROUTE-A-SHELL-2026-08-16` introduces the Android-only
  `keyboard_shell_route_a` boolean preference. Missing state and the bounded legacy `enabled` alias
  both migrate synchronously to the canonical key; the canonical default is Route A. A service
  freezes the selected route at `onCreate`, so changing it requires an IME process restart and can
  never make one service lifetime dispatch to both Shells.
- `STR-001-STREAMING-PROTOCOL-V1-2026-08-15` introduces the exact
  `opentypeless.streaming.v1` RecognitionEvent JSON envelope and its Draft 2020-12 schema. WebSocket
  text frames and SSE data events share the same closed eight-event payload; unknown versions,
  fields, sequence/revision violations, and post-terminal events fail closed. No Provider or network
  route is migrated by this contract-only task.
- `COMPAT-BASELINE-2026-08-14` records the current authorities in
  [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md): Android `0.3.0` (code 3), desktop `1.2.0`, all
  explicit Android/desktop data-format versions, the shared dictionary v1 envelope, and the honest
  unversioned/spec-only protocol boundaries.
- This baseline is not a release tag. Existing historical tags predate the machine-checked matrix and
  are not reconstructed into invented compatibility promises. Every future version change must add a
  new changelog ID, update the matrix and authority tests in the same change, and provide migration or
  explicit incompatibility evidence.

### Fixed

- `CMP-005` keeps the keyboard and next voice action ahead of encrypted recovery plumbing: a key
  explicitly cancels a late Voice Final after resolving any visible partial, recoverable voice
  items stay optional under More, and starting a new recording replaces the old recovery item
  instead of forcing an insert/discard detour. Editor target proof and uncertain-release fail-closed
  behavior are unchanged.
- `BLD-011` adds the independently verified SHA-256 for the `kotlinx-coroutines-bom:1.6.4` POM that
  AndroidTest's UTP dependency configuration resolves in clean CI. Strict Gradle dependency
  verification remains enabled; no dependency version or runtime behavior changes.
- `REC-005` now completes all caller-controlled STT model, language and prompt validation before
  opening a network connection. Oversized prompts fail with zero requests; no provider route,
  permission, endpoint policy or persisted format changes.

## Maintenance contract

- Keep changes under `Unreleased` until the exact release tag and commit are known.
- A persisted-format or protocol authority may not change without a matching compatibility row and
  changelog ID.
- A forward migration must state its readable source versions, written target version, downgrade
  behavior, rollback boundary, and executable test evidence.
- Unknown versions fail closed unless the matrix explicitly documents a bounded legacy reader.
- Never describe an assembled, unsigned, unpushed, or device-untested candidate as released.
