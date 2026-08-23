# ADR-0013: Emoji recents private format and sensitive-field policy

## Status

Accepted

## Background

`KBD-010` requires a categorized Emoji panel and recently used items without adding latency to the ordinary QWERTY/Rime
hot path. A recent list is a persistent format and can reveal a small amount of user behavior. Sensitive and no-learning
fields must therefore remain able to type static local Emoji without exposing or updating the recent list. The existing
application disables backup and excludes every shared-preference domain from cloud backup and device transfer.

## Decision

- Pin a source-level Unicode Emoji 15.1 subset with eight categories and at most 21 entries per rendered page. No font,
  image, runtime parser, network request or new dependency is added.
- Store at most 21 distinct MRU values in private SharedPreferences `opentypeless_emoji_recents_v1` with
  `format_version=1`. The payload is ordered comma-separated entries; each entry is uppercase hexadecimal Unicode scalar
  values separated by `-`. The decoder accepts only the pinned catalog and fails closed on unknown versions, malformed,
  oversized, over-deep or out-of-catalog values.
- Store no timestamp, count, app/package, field identity or surrounding text. `apply()` updates the bounded in-memory
  preference map and schedules disk persistence without a synchronous write on the input path.
- Static Emoji remains visible in every active editor, including sensitive fields. If hard safety denies learning, the
  recent category is neither read nor displayed and a successful insertion is not recorded.
- Emoji insertion reuses the existing `insertKeyboardText` façade and sole EditorTransactionManager. The panel and store
  never receive InputConnection or editor capabilities.

Rejected alternatives: retaining editor context with usage counts (unnecessary privacy exposure), reading recents in
sensitive fields (history disclosure), hiding all Emoji in sensitive fields (breaks ordinary local input), and importing an
upstream keyboard implementation or font (dependency/license and fork-maintenance cost).

## Consequences

The feature is local, bounded, backup-excluded and reversible. Recent ordering survives process restart in ordinary fields
but intentionally disappears from view in sensitive/no-learning fields. The curated first release is not the complete
Unicode Emoji set and does not yet include search, skin-tone variants or long-press variants; those require separate tasks.
Unicode sequence provenance and the Unicode-3.0 notice are retained under `third_party/emoji/`.

## Validation

Accepted on 2026-08-23 after these checks passed:

- `./gradlew testDebugUnitTest --tests 'com.opentypeless.android.keyboard.emoji.*'`: PASS, including multi-code-point v1
  round trip, malformed/version/size rejection, 21-entry MRU bound and sensitive/no-learning policy.
- `android/app/src/main/res/xml/data_extraction_rules.xml`: reviewed; all shared-preference domains are excluded from cloud
  backup and device transfer, and the manifest also sets `allowBackup=false` and `fullBackupContent=false`.
- Unicode Emoji 15.1 source and current Unicode License v3 were checked at the URLs in References; the implementation ships
  no glyph artwork or font.
- `scripts/verify_android.sh all`: PASS; 120 script tests, 269 source architecture tests, compiled debug/release gate and
  191 Gradle tasks passed, followed by exact product/test APK scans with zero violations.
- API 35 arm64 emulator: Emoji store/View tests 5/5 PASS; final APK selected as the system IME and ordinary/password field
  insertion plus sensitive Recent suppression 1/1 PASS. Xiaomi 10 Ultra was not connected, so that device was NOT RUN.

## Rollback

Revert the KBD-010 commit and remove `opentypeless_emoji_recents_v1`. Older builds ignore the private preference file, so
rollback does not affect editor, Rime or voice data. A future incompatible format must use a new version and superseding ADR;
it must not reinterpret v1 payloads.

## References

- Task: `KBD-010`
- Design: `docs/opentypeless_specs/01_PRODUCT_DESIGN.md`, `02_ARCHITECTURE_DEVELOPMENT.md`,
  `06_SECURITY_PRIVACY.md`, `07_IMPLEMENTATION_BACKLOG.md`, `08_TEST_VALIDATION.md`
- Unicode Emoji 15.1: https://www.unicode.org/Public/emoji/15.1/emoji-test.txt
- Unicode License v3: https://www.unicode.org/license.txt
- Related ADR: [ADR-0011](0011-keyboard-base-evaluation.md)
