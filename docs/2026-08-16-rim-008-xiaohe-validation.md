# Task Report: RIM-008 Xiaohè personal local validation

## Result

DONE

## Scope

- Implemented: converted the user's officially obtained local Xiaohè 4.2 Rime archive into one
  bounded `opentypeless.rime-resource-manifest` v1 package, imported it through the real Android SAF
  flow, activated it in the product IME and closed the consecutive-word transaction failure plus two
  real personal-use regressions found on Xiaomi 10 Ultra: fixed-length native auto-commit was lost,
  and Space did not select the visible first candidate.
- Not implemented: bundling or redistributing the resource, automatic download/update/export, a
  public golden corpus, signed Release distribution or the broader `TST-005` matrix.

## Changes

- `RimeResourceActivity`: keeps the SAF import generation alive while the picker is in front, so
  `onResume` cannot invalidate a valid result callback.
- `RimeResourceStore`: derives an exact deployment identity from the installed private manifest.
- `RimeAdapter` / runtime AAR: can reopen one already deployed private resource without repeating a
  full deployment on every word, and now exposes one bounded, consuming read of the native pending
  commit emitted by a fixed-length table Schema.
- `NativeRimeInputEngine`: converts that pending native value into the same bounded `CommitReady`
  contract used by candidate selection, then closes the committed native session before creating the
  local recovery point; full UserDB synchronization is no longer on the per-word keyboard hot path.
- `RimeInputEngine`, `RimeInputController`, `OpenTypelessImeService`: every independent Rime
  composition continues one service-local monotonic revision space. The editor transaction manager
  still retains its stale-event high-watermark; the fix does not weaken replay protection. In Rime
  mode, Space with an active page now submits the exact first displayed selection through the
  existing one-shot identity checks; without a composition it remains an ordinary space.
- focused JVM and local-only instrumentation tests: cover SAF lifecycle, exact deployment
  identity/recovery, reserved Rime revision forwarding, fixed-length native commit and first-page
  candidate selection without committing the user's dictionary or expected plaintext to source.

## Architecture

- contracts: the imported package remains `USER_PROVIDED_UNVERIFIED` / `LOCAL_ONLY`; the service
  allocates a positive Rime revision before acquisition and never reuses an earlier revision in the
  same service lifetime.
- state changes: selected resources live only under app-private no-backup storage. The product and
  test APKs continue to contain zero real Xiaohè resources.
- migration: none. Existing valid private imports are reopened by exact manifest identity; a changed
  package is deployed again.
- feature flag: none added. The existing explicit EN/中文 engine selector remains the user control.

## Security & privacy

- data sent/stored: no network. The source archive and generated personal package remain outside the
  repository; imported bytes remain private on the user's Xiaomi device.
- permissions/components: none added.
- threat considerations: the user's statement that the archive came from the official source is
  recorded as provenance context, not as redistribution permission. Unsafe executable/script rows
  were excluded while 216,722 ordinary dictionary rows were retained. No real Xiaohè payload enters
  source, APK, AndroidTest, logs or shared fixtures.

## Tests actually run

| Command/check | Result | Notes |
|---|---|---|
| targeted Rime/ETM JVM tests | PASS | 23-task Gradle run; reserved revision starts at the requested value and stale high-watermarks remain enforced. |
| `:app:testDebugUnitTest :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest` | PASS | 120 tasks; full app JVM suite, Release lint and all three final APKs. |
| KSP-012 resource-policy unit tests | PASS | 37/37 hostile tests. |
| `verify_android.sh preflight` | PASS | 120 repository script tests, 11 SDK/manifest tests and 247 architecture tests; all Rime/editor/privacy gates passed. |
| final Debug + unsigned Release product scan | PASS | 2 APKs, 117 members, 16 exact native engines, real Xiaohè=0, violations=0. |
| final AndroidTest APK scan | PASS | 1 APK, 19 members, real Xiaohè=0, violations=0. |
| Xiaomi 10 Ultra/API33 real SAF import | PASS | Dry deploy, atomic private activation and selected `flypy` Schema completed. |
| Xiaomi actual-librime local-only hash case | PASS | 1/1; fixed-length auto-commit and first-candidate selection matched the two runtime-supplied expected hashes. |
| Xiaomi system-selected IME regression | PASS | 2/2 real touch cases: the fourth code auto-committed; a three-code composition plus Space committed the displayed first candidate. |
| Xiaomi real-keyboard consecutive composition | PASS | Same editor: two independent four-key cases selected and committed consecutively; final host value matched two expected commits. |
| Xiaomi editor switch and host restart | PASS | Another field committed once; after force-stopping/restarting the host, the private package remained usable and OpenTypeless remained the default IME. |

## Evidence

- user source archive: 5,106,503 bytes, SHA-256
  `564e216e3559f95b056694c3e5f84cd24c082a9ff03f8e25f567684f979d990d`.
- local-only import package: 1,332,063 bytes, SHA-256
  `44ebff5a3125cb6df944c1649f8611c2aa69b13758f5998052ef83212dfca482`.
- final Debug APK: 65,484,973 bytes, SHA-256
  `811d07c33628d7c58f6034eb371f37383cc47ea4a7faae99ac19ab4c39799acc`.
- final unsigned Release APK: 63,607,185 bytes, SHA-256
  `32e39a8b6b659b073a8e79756036fb0e3106553e691ceb884ef5fceeaf200709`.
- final AndroidTest APK: 1,093,646 bytes, SHA-256
  `58f8d3c5967f010350278b2b8cd9acd3581db31a82651b2c87c0e722c12b2605`.
- Xiaomi/Telegram arm64 Debug handoff APK: 32,247,058 bytes, SHA-256
  `b71054689f52d41100e93692349ee82d7c4ba7cbc4b2b4e95d272da0a913aefe`.
- resource-policy canonical SHA-256:
  `b5ac1cbbca36dc793f5e3cdaf269c3c5bc008e3c69c298ca0a0f937b914dc5f7`.

## Risks

- The evidence proves a personal local Xiaohè input path and the blocking consecutive-word fix; it
  is not authorization to redistribute the resource or a complete public compatibility corpus.
- The Release APK is unsigned. System-selected IME behavior was manually verified on one Xiaomi 10
  Ultra/API33; other OEMs remain outside this task.
- Broader paging, word creation, simplified/traditional and punctuation golden coverage remains
  `TST-005`, not part of this minimum personal-use delivery.

## Rollback

Clear the imported resource from the existing settings page and switch the engine to EN. Removing
the monotonic revision seed would restore the prior fail-closed second-word rejection but would not
change persisted resource or UserDB formats.

## Follow-ups

- `TST-005`
- `RIM-010`
- `REL-002`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
