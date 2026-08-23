# Android engineering metrics baseline

Date: 2026-08-14  
Repository HEAD: `80d20496c4eb59e4f27281becfa8a32021212e53`  
Generated JSON SHA-256: `4efa265bf6b60bff2cbde10d7572cbe4f40cb540393087b4766656a343802ce6`  
Policy: **advisory trend only; no numeric threshold fails CI**

This baseline records deterministic source-size, method-complexity proxy, test-count, and APK-size
signals for the current Android candidate. It complements the
[current acceptance report](2026-08-14-android-baseline-acceptance.md); it is not a quality score
and cannot replace review, architecture gates, tests, or device acceptance.

## Metric definition

- Source lines are physical UTF-8 lines; nonblank lines exclude only empty/whitespace lines.
- Method complexity is a source proxy: `1 + if/for/while/case/catch/ternary/boolean decision
  tokens` after comments and string/character contents are removed. It is deterministic but is not
  claimed to be a formal cyclomatic-complexity implementation.
- XML test totals come only from parseable Gradle JUnit XML currently present under
  `android/**/build/test-results`; declared test counts are separate source inventory.
- APK size and SHA-256 are recorded only when an exact expected artifact exists. Missing artifacts
  are emitted as `available=false` rather than silently omitted or treated as a trend regression.
- Reviewers use changes to ask for decomposition or evidence. CI does not fail because a class,
  complexity proxy, APK, or test count crosses an arbitrary threshold.

## Key source baseline

| Source | Lines | Nonblank | Methods | Max complexity proxy | Current hotspot |
|---|---:|---:|---:|---:|---|
| `OpenTypelessImeService.java` | 4,154 | 3,907 | 189 | 64 | `updateMicrophone` |
| `EditorSessionManager.java` | 2,810 | 2,575 | 109 | 29 | `readUndoEvidence`, `readEvidence` |
| `EditorTransactionManager.java` | 1,654 | 1,536 | 60 | 29 | `policyRejection` |
| `SettingsRepository.java` | 746 | 682 | 50 | 21 | `verifyStoredSettings` |
| `CompositionCoordinator.java` | 666 | 600 | 38 | 9 | `update`, `voicePartial` |
| `VoicePipeline.java` | 165 | 130 | 25 | 1 | `shouldFallbackToLocal` |
| `VoiceController.java` | 38 | 25 | 4 | 1 | `onBeginningOfSpeech` |

The largest review hotspot is the 4,154-line IME service; this report records that risk but does not
authorize a cross-task rewrite. Decomposition must follow the Backlog and architecture contracts.

## Test inventory

| Signal | Count | Interpretation |
|---|---:|---|
| Parseable JUnit XML suites | 123 | app JVM plus compiled architecture suites from the latest build |
| JUnit XML tests | 871 | 0 failures, 0 errors, 0 skipped in the recorded files |
| Android JVM `@Test` declarations | 871 | source inventory, not a separate execution claim |
| Android instrumentation `@Test` declarations | 85 | compiled source inventory; Xiaomi run is not complete |
| Python `test_*` declarations | 197 | repository inventory across Python suites |

## APK baseline

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| App debug | 56,298,223 | `dd5543d598c356d16bcbd5fffcb43d7de100845a484e54b7344341d74c91f9a3` |
| App release unsigned | 54,620,300 | `dea8683974f73978d68cddb45c092d416b2c862018688b608582151c08441f4f` |
| App debug AndroidTest | 988,208 | `5a088c6eff660d5366d5043fbc589532715bd6a2c3c8e451d11edbc7fb623ee0` |
| Test Host debug | 9,821 | `8903e723776793115977612132d97006f0d32f3a8f01eb1a373639b6213bc8b7` |
| Test Host debug AndroidTest | 1,676,502 | `7d9f5df75047aacf2b7654818e35796f3df397f9dcc2b1ae6b991fda2eebf882` |

The release APK is unsigned and is not a trusted distribution artifact.

## Reproduction and CI artifact

After Unit/Lint/Assemble has produced reports and APKs:

```bash
scripts/verify_android.sh metrics
shasum -a 256 android/build/reports/engineering-metrics/engineering-metrics.json
```

`check-android` runs the same `metrics` stage after Assemble and uploads
`android-engineering-metrics` for 14 days with `if-no-files-found: error`. The metrics collector can
fail on malformed inputs or a missing output, but ordinary numeric drift is never a mechanical CI
failure.
