# Maintainer handoff

Updated: 2026-08-09

OpenTypeless is maintained at `dengxuezhao/opentypeless`. Desktop release numbering continues at
1.2.0 above the inherited 1.1.x tag line. Android Voice Studio has its own 0.2.0 application version.

## Product boundaries

- Desktop is BYOK/local-provider only. Account, subscription, checkout, quota, donation,
  managed-cloud proxy, and upstream automatic-update paths are not product surfaces in this fork.
- Android is a native voice-input layer with an IME, `RecognitionService`, and
  `RecognizerIntent` Activity. It is not a full QWERTY/swipe keyboard.
- Android AI editing is optional. Structured fields use exact transcription; password fields are
  blocked; selected text is preserved whenever an AI operation fails or violates integrity checks.
- Personal terms and corrections are learned only after explicit user confirmation.

## Release gates

Run the checks documented in `README.md` and
[`docs/2026-08-09-byok-android-acceptance.md`](docs/2026-08-09-byok-android-acceptance.md).
Do not distribute the unsigned Android release APK. A public binary release additionally requires:

- a maintainer-controlled Android release keystore and reproducible signing configuration;
- published SHA-256 checksums and signature verification;
- signed/notarized desktop packages for each target platform;
- successful GitHub Actions checks on the exact release commit.

The locked desktop runtime dependency inventory is generated with:

```bash
python3 scripts/generate_third_party_inventory.py
python3 scripts/generate_third_party_inventory.py --check
```

`LICENSE`, `THIRD_PARTY_NOTICES.md`, `THIRD_PARTY_INVENTORY.md`, and
`THIRD_PARTY_LICENSES.txt` are bundled in desktop distributions and available offline from About.
Android exposes its MIT and build/test notices from the settings screen.

## Honest limitations

- No bundled Android speech model; on-device language/device availability belongs to the installed
  Android recognition provider.
- No published cross-device CER/WER, latency, battery, or blind Typeless benchmark yet.
- Standard Android speech-service discovery and behavior can vary across OEM keyboards.
- External BYOK provider smoke tests require maintainer-supplied credentials and are never run in CI.
