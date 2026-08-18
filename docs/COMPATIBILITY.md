# OpenTypeless compatibility matrix

Baseline: `COMPAT-BASELINE-2026-08-14`  
Repository candidate: `agent/android-offline-followup@80d20496c4eb59e4f27281becfa8a32021212e53`  
Status: source-of-truth inventory, not a release or upgrade certification

This matrix records what the current source actually reads and writes. It does not infer compatibility
from an App version, a file name, or an old Git tag. Android and desktop application versions are
independent. A row marked unversioned or spec-only is an explicit limitation, not a promise that any
shape will remain compatible.

<!-- BEGIN COMPATIBILITY MATRIX -->
| ID | Kind | Current | Read / upgrade | Write | Authority | Change ID |
|---|---|---|---|---|---|---|
| `android-app` | runtime | `0.3.0+3` | Android API 26 and later; target and compile API 35 | versionName `0.3.0`, versionCode 3 | `android/app/build.gradle.kts` | `COMPAT-BASELINE-2026-08-14` |
| `android-platform` | runtime | `min26,compile35,target35` | API 26, 33, 35, and 36 are the declared verification matrix | APK targets API 35 | `android/app/build.gradle.kts`, `.github/workflows/ci.yml` | `COMPAT-BASELINE-2026-08-14` |
| `desktop-app` | runtime | `1.2.0` | package, Rust crate, Tauri bundle, and UI fallback must agree | desktop SemVer is independent of Android | `package.json`, `src-tauri/Cargo.toml`, `src-tauri/tauri.conf.json`, `src/lib/constants.ts` | `COMPAT-BASELINE-2026-08-14` |
| `desktop-platform` | runtime | `macOS>=10.15,Windows,Linux` | macOS minimum is explicit; Windows and Linux have build targets but no declared minimum OS here | platform bundles share desktop `1.2.0` | `src-tauri/tauri.conf.json`, `src-tauri/Cargo.toml` | `COMPAT-BASELINE-2026-08-14` |
| `android-global-config` | config | `1` | exact v1; Android 0.2 settings have a one-time migration; unknown target versions fail closed | v1 only | `android/app/src/main/java/com/opentypeless/android/config/GlobalConfig.java`, `android/app/src/main/java/com/opentypeless/android/settings/LegacyAppSettingsMigration.java` | `COMPAT-BASELINE-2026-08-14` |
| `android-override-value` | config | `1` | exact v1 array or DB row; malformed, contradictory, or unknown rows fail closed | v1 only | `android/app/src/main/java/com/opentypeless/android/config/OverrideValueCodec.java` | `COMPAT-BASELINE-2026-08-14` |
| `android-app-rule-migration` | config | `0.2->1,migration1` | bounded Android 0.2 AppProfile to v1 AppRule migration; unknown targets fail closed | target format v1 | `android/app/src/main/java/com/opentypeless/android/settings/LegacyAppProfileMigration.java` | `COMPAT-BASELINE-2026-08-14` |
| `android-secret-store` | config | `format1,migration1` | exact format and migration pair; legacy encrypted credentials migrate transactionally; unknown versions fail closed | format 1 with migration marker 1 | `android/app/src/main/java/com/opentypeless/android/security/SecretStore.java` | `COMPAT-BASELINE-2026-08-14` |
| `android-personalization-db` | schema | `4` | SQLite versions 1 through 3 upgrade forward to 4; downgrade is unsupported | schema 4 | `android/app/src/main/java/com/opentypeless/android/data/PersonalizationStore.java` | `COMPAT-BASELINE-2026-08-14` |
| `cross-platform-dictionary` | format | `opentypeless_dictionary:1` | Android and desktop accept v1; Android also accepts its bounded legacy v1 and an unmarked desktop subset | both write the v1 JSON envelope; desktop CSV marker is v1 | `android/app/src/main/java/com/opentypeless/android/data/PersonalizationStore.java`, `src-tauri/src/dictionary_io.rs` | `COMPAT-BASELINE-2026-08-14` |
| `android-voice-recovery-journal` | format | `1` | exact authenticated v1 header only; unreadable or mismatched records fail closed | v1 only | `android/app/src/main/java/com/opentypeless/android/security/VoiceRecoveryJournal.java` | `COMPAT-BASELINE-2026-08-14` |
| `android-voice-draft-journal` | format | `1` | exact authenticated v1 file and record headers; unreadable sessions are not guessed or rewritten | v1 only | `android/app/src/main/java/com/opentypeless/android/speech/journal/VoiceDraftJournal.java` | `COMPAT-BASELINE-2026-08-14` |
| `android-engine-trace` | schema | `1` | exact schemaVersion 1 with bounded decode; unknown versions fail closed | schema 1 | `android/app/src/main/java/com/opentypeless/android/speech/engine/EngineTrace.java` | `COMPAT-BASELINE-2026-08-14` |
| `android-editor-fingerprint-frame` | schema | `1` | internal domain-separated hash frame; not a cross-release serialized editor capability | frame 1 | `android/app/src/main/java/com/opentypeless/android/editor/Sha256EditorTextHasher.java` | `COMPAT-BASELINE-2026-08-14` |
| `android-keyboard-shell-route` | config | `route-a-default,legacy-alias-migration1` | missing state defaults to Route A; the bounded legacy `enabled` alias migrates synchronously; one service lifetime freezes one route | canonical boolean `keyboard_shell_route_a`; legacy alias removed on read/write | `android/app/src/main/java/com/opentypeless/android/keyboard/shell/KeyboardShellConfig.java` | `KBD-001-ROUTE-A-SHELL-2026-08-16` |
| `android-keyboard-feedback` | config | `1` | missing state uses bounded system-following defaults; malformed enum values and unknown versions fail closed to defaults | format 1 only; text-free haptic mode/strength, sound enabled and bounded volume | `android/app/src/main/java/com/opentypeless/android/keyboard/feedback/KeyboardFeedbackPreferences.java` | `KBD-005` |
| `android-rime-resource-manifest` | format | `opentypeless.rime-resource-manifest:1` | exact v1 only; unknown/duplicate keys, extra/missing files, incompatible librime range and non-closed dependencies fail closed | reader-only local import; product does not write or export resource packages | `android/app/src/main/java/com/opentypeless/android/rime/importer/RimeResourceManifest.java`, `protocol/opentypeless-rime-import-manifest-v1.schema.json` | `RIM-003-RIME-RESOURCE-MANIFEST-V1-2026-08-16` |
| `desktop-config-store` | config | `legacy-unversioned` | serde defaults and bounded normalization read additive legacy fields; there is no explicit root format version | unversioned `app_config` JSON in `settings.json` | `src-tauri/src/storage/mod.rs` | `COMPAT-BASELINE-2026-08-14` |
| `desktop-history-db` | schema | `legacy-unversioned` | additive column inspection and ALTER statements; no SQLite user_version contract exists | current column set without schema integer | `src-tauri/src/storage/mod.rs` | `COMPAT-BASELINE-2026-08-14` |
| `desktop-scene-export` | format | `1` | v1 envelope and bounded legacy bare array; envelope version is currently advisory to the reader | v1 envelope | `src/lib/scenes/sceneImportExport.ts` | `COMPAT-BASELINE-2026-08-14` |
| `desktop-app-mapping-store` | format | `1` | exact v1; missing, malformed, or unknown versions load an empty mapping collection | v1 only | `src-tauri/src/app_detector/user_mappings.rs` | `COMPAT-BASELINE-2026-08-14` |
| `desktop-credential-payload` | format | `1` | v1 JSON payload; non-v1 or non-JSON value is treated as the bounded legacy secret form | v1 JSON in the OS credential vault | `src-tauri/src/credentials.rs` | `COMPAT-BASELINE-2026-08-14` |
| `desktop-stt-capability-registry` | schema | `1` | exact in-process capability view generated by current code | registryVersion 1 | `src-tauri/src/stt/capabilities.rs` | `COMPAT-BASELINE-2026-08-14` |
| `desktop-context-prompt` | protocol | `context-v1` | model capability checks bind the exact prompt contract | context-v1 | `src-tauri/src/llm/prompt.rs` | `COMPAT-BASELINE-2026-08-14` |
| `action-protocol` | protocol | `opentypeless.action.v1-spec-only` | target schema exists only in the specification; no production executor is claimed | not implemented | `docs/opentypeless_specs/04_ACTION_PROTOCOL_V1.md` | `COMPAT-BASELINE-2026-08-14` |
| `android-paraformer-protocol` | protocol | `external-unversioned` | bounded current DashScope realtime message shape; task identity is checked; no local protocol version exists | current vendor message shape | `android/app/src/main/java/com/opentypeless/android/net/streaming/ParaformerProtocol.java` | `COMPAT-BASELINE-2026-08-14` |
| `android-streaming-recognition-protocol` | protocol | `opentypeless.streaming.v1` | exact v1 only; unknown protocol/type/field, malformed or oversized JSON, foreign Session, non-monotonic sequence, invalid partial revision, and every event after the first terminal fail closed | exact v1 JSON object per WebSocket text frame or SSE data event; no legacy writer | `android/app/src/main/java/com/opentypeless/android/net/streaming/StreamingRecognitionWireEvent.java`, `android/app/src/main/resources/schemas/opentypeless-streaming-recognition-event-v1.schema.json` | `STR-001-STREAMING-PROTOCOL-V1-2026-08-15` |
<!-- END COMPATIBILITY MATRIX -->

## Compatibility rules

1. Change the runtime authority, this matrix, [`CHANGELOG.md`](../CHANGELOG.md), and executable
   migration/contract tests in one task. A version bump without all four is invalid.
2. Additive changes are not automatically compatible. The reader behavior in the corresponding row
   is the boundary; unknown versions fail closed unless a bounded legacy shape is named explicitly.
3. Persisted-format changes require an idempotent old-to-new migration, interruption and disk-full
   handling, downgrade policy, fixtures, and an Accepted ADR when rollback can lose data.
4. Protocol changes require producer and consumer contract tests. External vendor protocols must not
   be assigned a made-up local version.
5. Application SemVer never substitutes for a schema version. Android and desktop may release on
   different cadences while sharing only explicitly named formats such as the dictionary v1 envelope.
6. `legacy-unversioned` rows are migration risks. A future explicit version is a compatibility change,
   not a documentation cleanup, and needs a new changelog ID.

## Not yet runtime authorities

The release specification names future Import Bundle, Model Manifest, Diagnostic Bundle, and
independently versioned ASR streaming surfaces. They do not yet have production version
authorities in this candidate and are therefore not assigned invented current versions. The Action
Protocol row is likewise spec-only until an implementation task adds a producer, consumer, and
contract tests.

ADR-0012 froze `format = "opentypeless.rime-resource-manifest"`, `version = 1`; RIM-003 now provides
the bounded Android reader and authority row above. It remains reader-only and explicit-local:
unknown versions fail closed, real Xiaohè resources and GPL Xiaohè Schemas remain zero-bundle, and
all user imports remain `USER_PROVIDED_UNVERIFIED` / `LOCAL_ONLY` unless a separate trust policy is
accepted. No product writer/export compatibility is implied.

## Validation

Run:

```bash
python3 scripts/test_verify_compatibility.py -v
python3 scripts/verify_compatibility.py --repo-root .
```

The verifier checks this exact row set, source authorities, Android/desktop version alignment,
untracked new version constants, root discovery links, and changelog IDs. It is an offline source
gate; it does not replace old-version upgrade, signed release, emulator, or physical-device tests.
