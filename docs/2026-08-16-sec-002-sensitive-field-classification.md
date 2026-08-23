# Task Report: SEC-002 Sensitive-field classification

## Result

DONE

## Scope

- Implemented: fail-closed classification for password, OTP, payment and identity fields, plus
  Android no-personalized-learning state; expanded Test Host fields and selected-IME device checks.
- Not implemented: toolbar policy application, network/persistence changes, App/user rule UI, or a
  generic all-App semantic database. SEC-005 owns the toolbar response.

## Changes

- `android/app/src/main/java/com/opentypeless/android/context/InputContextClassifier.java`: closed
  privacy vocabulary, bounded metadata normalization and conservative classification.
- `android/app/src/test/java/com/opentypeless/android/context/InputContextClassifierTest.java`: nine
  JVM cases for platform passwords, OTP/payment/identity, no-learning, near misses and malformed data.
- `android/test-host`: adds OTP, payment-card, identity-number and no-learning fields plus the real
  selected-IME matrix.
- `android/architecture-tests/sensitive_field_contract.py` and tests: nine hostile source fixtures.
- `scripts/verify_android.sh`: SEC-002 source contract is part of preflight.
- `third_party/rime/resource-policy.v1.json`: pins the exact final APKs; no Xiaohè resource is added.

## Architecture

- contracts: `EditorInfo -> PrivacyClassification`, projected to the existing `FieldKind` only after
  the privacy decision.
- state changes: none; classification is pure and recomputed for each editor target.
- migration: none.
- feature flag: none.

Password variations remain authoritative. OTP, payment/card and identity hints are read only from
four bounded Android metadata channels, normalized with NFKC and matched against a fixed English/
Chinese marker set. Each field is limited to 128 code points and the combined normalized input to
256 code points. Null, malformed Unicode, controls, bidi metadata and over-limit values fail closed.
Ordinary number, phone and person-name fields remain usable rather than being classified wholesale
as sensitive. `IME_FLAG_NO_PERSONALIZED_LEARNING` disables learning without pretending the whole
field is necessarily sensitive.

## Security & privacy

- data sent/stored: none.
- permissions/components: none added.
- threat considerations: no package-name heuristic, editor capability, network, native, reflection,
  persistence or plaintext diagnostic. Classification can only tighten the prior field result.

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| SEC-002 hostile source contract | PASS | 9/9. |
| targeted classifier JVM | PASS | 9/9. |
| `scripts/verify_android.sh preflight` | PASS | 119 script, 11 Android-script, 167 architecture and 10 mobile-voice tests. |
| `scripts/verify_android.sh unit` | PASS | clean 68/68 tasks; app JVM 984/984; architecture-gate 114/114; compiled Debug/Release 2/2. |
| strict offline assemble/lint | PASS | 173-task graph; 119 executed and 54 up-to-date; Debug, unsigned Release, both AndroidTest packages and `lintRelease`. |
| KSP-012 resource contract/artifact scan | PASS | 36/36; repository, 3 product APKs and 2 test APKs all have real Xiaohè 0, forbidden resource 0, violations 0. |
| API 35 ARM64 emulator | PASS | Test Host structure 1/1 and selected-IME OTP/payment/identity 1/1; LatinIME restored. |
| Xiaomi 10 Ultra API 33 ARM64 | PASS | Test Host structure 1/1 and selected-IME OTP/payment/identity 1/1; PangIME restored. |
| docs/ADR/compatibility and package validation | PASS | docs 4/4 + 3 entrypoints/16 specs; ADR 4/4 + 12 decisions; compatibility 5/5 + 25 rows/18 authorities; FULL 12/12; manifest 15/15. |

The first Test Host compile failed because its new assertion omitted the `EditorInfo` import; the
import was added and the complete strict build/device matrix then passed. An initial final artifact
scan rejected the changed APK as `UNREVIEWED_ARTIFACT`; the exact bytes were reviewed, pinned and the
full policy tests/scans rerun successfully.

## Evidence

- Debug APK: 56,465,857 bytes, SHA-256
  `082c6dc18d4833eba199e245fc42a2ba55810d7e75791160839787b624f28648`.
- unsigned Release APK: 54,656,745 bytes, SHA-256
  `3602a5181857a2e2985e13a578b6a74f0830ffda998e9932f5700487fe120449`.
- app AndroidTest: 1,071,591 bytes, SHA-256
  `0c3d95fdf7a491b1ecc7e7a846af7854a4a42e30dad779fcb173eef1f01dfae1`.
- Test Host Debug: 11,077 bytes, SHA-256
  `2031e3d3faa7bc5b3b59fa8de5642479d7c2b072a95dba3724fb674cef357f76`.
- Test Host AndroidTest: 1,689,232 bytes, SHA-256
  `1380bc16293e595e6665378d32aad56aef021429e41002d33a3b78bbff1a7822`.
- final resource policy canonical SHA-256:
  `133ab4581727d87c1f5c46fd0363250a3419d074a85693ec7433f3485ddf57a2`.

## Risks

- Apps that publish no trustworthy semantic metadata cannot be identified as OTP/payment/identity
  by this bounded heuristic. Unknown or malformed metadata fails closed, but empty ordinary metadata
  remains ordinary to preserve personal typing usability.
- SEC-005 must still hide and restore toolbar capabilities on field transitions; SEC-002 only
  supplies the classification and proves the password-layout projection.

## Rollback

Remove the added privacy classification, Test Host fields/tests and source gate, then restore the
prior classifier and artifact policy identities. No stored state or migration requires rollback.

## Follow-ups

- `SEC-005`
- `TST-001`

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared and dirty with pre-existing work; nothing staged, committed or pushed.
