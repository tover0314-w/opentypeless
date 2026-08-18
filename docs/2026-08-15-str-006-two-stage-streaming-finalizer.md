# Task Report: STR-006

## Result

DONE

## Scope

- Implemented: a package-confined `TwoStageStreamingProvider` that combines the STR-005 local
  Streaming Paraformer preview with the REC-006 SenseVoice authoritative final in one bounded
  session, with fact-guarded final replacement and deterministic cleanup.
- Not implemented: production ProviderRegistry/RecognitionRouter/VoiceController registration,
  microphone or user-audio capture, DisclosurePlan/EffectiveProfile activation, UI/configuration,
  persistence, a feature flag, or old/new production-route migration. Those remain STR-010.

## Changes

- `TwoStageStreamingProvider.java`: one-active composite lifecycle, bounded copied PCM, one finalizer
  worker, preview-only streaming child, authoritative SenseVoice terminal, fact guard fallback,
  single terminal and lock-order-safe child cancellation.
- `ProviderCapabilities.java`: exact local two-stage capability declaration.
- `TwoStageStreamingProviderTest.java`: deterministic lifecycle, bounds, failures, fact protection,
  cleanup and two-thread lock-order coverage.
- `TwoStageStreamingProviderInstrumentedTest.java`: real pinned Android Streaming Paraformer plus
  SenseVoice final execution and empty/cancel terminal coverage.
- `OfflineModelPinnedImportInstrumentedTest.java`: test-only explicit staging bridge that invokes the
  production model verifier/atomic commit after both sides verify exact pinned files.
- source and compiled architecture gates: exact composite/child/finalizer/session/worker scope,
  bounded audio, event/failure redaction, call graph and production-registration default deny.

## Architecture

- contracts: PCM16 mono 16 kHz; 64 KiB frame; 17,280,000 bytes per session; one-use StartRequest;
  one active session; one worker; Streaming Paraformer owns preview events and SenseVoice owns the
  only terminal transcript.
- state changes: streaming terminal degrades to final-only; final terminal passes
  `TranscriptIntegrityGuard` or falls back to the latest nonblank safe preview; terminal/cancel/close
  detach child/session/sink references and clear PCM/WAV.
- migration: none.
- feature flag: none. The provider remains unregistered; STR-010 owns production activation.

## Security & privacy

- Data sent/stored: no production network transfer or new persistence. Tests use the exact public
  upstream WAV and app-private pinned models; no user audio or transcript is recorded or exported.
- Permissions/components: no new permission, exported component or microphone access.
- Threat considerations: bounded defensive audio copies, one active/one terminal, sequence/session
  checks, content-free failures, guard fallback, no editor/network authority, and child cancel/close
  outside the composite lock to prevent callback lock inversion.
- The device could not reach Hugging Face over IPv6. The fallback import was test-only: Mac fetched
  exact pinned public files, both sides verified SHA-256, and production `commitVerifiedStaging`
  performed the app-private atomic install. This is not a production arbitrary-import surface.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `TwoStageStreamingProviderTest` | PASS | 10/10, 0 failure/error/skipped |
| Python source architecture suite and production scan | PASS | 114/114; production scan passed |
| `:architecture-gate:test --rerun-tasks` | PASS | 112/112 |
| `:architecture-gate:verifyCompiledArchitecture --rerun-tasks` | PASS | Debug/Release 2/2 |
| first current-snapshot compiled gate rerun without `--rerun-tasks` | FAIL | strict clean had removed exported artifacts while Gradle reported export tasks UP-TO-DATE; no architecture violation |
| standard `scripts/verify_android.sh all` | PASS | `BUILD SUCCESSFUL in 53s`; 189 tasks (186 executed, 3 up-to-date); 1056 XML tests; five APKs |
| fresh `GRADLE_USER_HOME` `scripts/verify_android.sh all` | PASS | `BUILD SUCCESSFUL in 2m40s`; 189 tasks (185 executed, 4 up-to-date); 1056 XML tests; five APKs |
| `OfflineModelDownloader` on Xiaomi | FAIL | exact pinned download reached 164.833s then Hugging Face IPv6 port 443 timed out |
| `OfflineModelPinnedImportInstrumentedTest` on Xiaomi | PASS | 1/1 in 0.647s; production verification plus atomic app-private commit |
| `TwoStageStreamingProviderInstrumentedTest` on Xiaomi | PASS | 2/2 in 17.858s; real private-process preview plus SenseVoice final |
| documentation mirror/metrics/manifest and canonical preflight | PASS | four FULL sections exact; 16 files, 1,474,128 bytes, 38 Mermaid, 1586 headings, 196 tasks, manifest 15/15; preflight 114/114 architecture tests |
| dynamic Android x86_64 run | NOT RUN | Apple Silicon host has no runnable x86_64 Android runtime; packaging/support remains verified |
| production microphone/VoiceController route | NOT RUN | STR-010 scope; this provider is deliberately unregistered |
| GitHub Actions for current worktree | NOT RUN | worktree is uncommitted; current HEAD has no matching run |

## Evidence

- SenseVoice model: `sensevoice-small-int8-2024-07-17`, revision
  `2365baeacb507f821a0c8120fcee3d484dba7a07`; model 239,233,841 bytes, SHA-256
  `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51`; tokens
  315,894 bytes, SHA-256
  `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc`.
- Xiaomi: `be4e2015`, M2007J1SC/cas, Android 13/API33, HyperOS
  `V816.0.4.0.TJJCNXM`; installed marker currently retains the exact revision and hashes above.
- Public WAV: 321,744 bytes, SHA-256
  `7d93384ca14702cc584a7a33fe2fed92e89e708549161cb12ea38c916882103b`.
- Device-tested app Debug SHA-256:
  `1a7432ecfa2c808432932ca9d513ab0627ce132f67abe77d4b104100f4b2c6d8`.
- Device-tested app AndroidTest SHA-256:
  `a07276812b53e50bd019ff7a3d8b4d792d2d9a9eca7dd286006724f826c845ee`.
- Later strict clean rebuilt local output files, so the two APK hashes identify this device run rather
  than the current output directory.
- OpenTypeless remains installed, while the default IME remains
  `com.flypy.input/PangIME.Android.InputService`; the test did not switch user preference.

## Risks

- The composite remains unreachable from the production microphone path until STR-010; this task
  proves the provider primitive, not end-to-end dictation.
- Direct model download on the Xiaomi remains unavailable on the observed device network path; the
  already verified app-private install is sufficient for current tests but downloader resilience is
  not proven by the import fallback.
- x86_64 is package-verified but not dynamically executed.
- The older STR-005 standalone report contains a public-WAV hash typo; the executable benchmark,
  Android tests, benchmark JSON and this report agree on the exact hash above. Correct it in the
  next documentation-maintenance task rather than altering an adjacent completed task here.

## Rollback

- Remove `TwoStageStreamingProvider`, its capability bridge/tests/gates and test-only pinned import
  bridge, then mark STR-006 TODO. No schema, permission, route, feature-flag or user-data migration is
  required. The optional private model remains governed by the existing verified model lifecycle.

## Follow-ups

- STR-010

## Git

- branch: `agent/android-offline-followup`
- commit: none created
- worktree status: shared dirty/untracked task tree preserved; no staging, commit, push or PR performed
