# Maintainer handoff

Updated: 2026-08-11

OpenTypeless is maintained at `dengxuezhao/opentypeless`. Desktop release numbering continues at
1.2.0 above the inherited 1.1.x tag line. Android Voice Studio 0.3.0 is the live-composition and
Paraformer realtime Voice Core milestone; Android IME 1.0 remains gated on the full Rime keyboard
integration and Xiaomi 15 physical acceptance.

## Product boundaries

- Desktop is BYOK/local-provider only. Account, subscription, checkout, quota, donation,
  managed-cloud proxy, and upstream automatic-update paths are not product surfaces in this fork.
- Android 0.3 is a native voice-input layer with an IME, `RecognitionService`, and
  `RecognizerIntent` Activity. Its realtime transcript work is intended to move into the Fcitx5
  Android + Rime 1.0 base; 0.3 itself is not a full QWERTY/swipe keyboard.
- Android 0.3 includes a pinned sherpa-onnx ASR-only runtime and can explicitly download a verified
  SenseVoice Small INT8 model into private no-backup storage. The APK contains no model weights.
  Settings also offers Android on-device/system routes, batch BYOK, and DashScope Paraformer
  realtime; no provider switch is silent.
- Android AI editing is optional. Structured fields use exact transcription; password fields are
  blocked; selected text is preserved whenever an AI operation fails or violates integrity checks.
- Personal terms and corrections are learned only after explicit user confirmation.

## Release gates

Run the checks documented in `README.md` and
[`docs/2026-08-09-android-0.3-review-acceptance.md`](docs/2026-08-09-android-0.3-review-acceptance.md).
Use
[`docs/2026-08-11-xiaomi15-p0-acceptance.md`](docs/2026-08-11-xiaomi15-p0-acceptance.md)
as the current physical Voice Core release authority.
Do not distribute the unsigned Android release APK. A public binary release additionally requires:

- a maintainer-controlled Android release keystore and reproducible signing configuration;
- published SHA-256 checksums and signature verification;
- signed/notarized desktop packages for each target platform;
- successful GitHub Actions checks on the exact release commit.

The release workflow derives the Android version and signed APK filename from Gradle's release
metadata. It publishes `OpenTypeless-Android-<version>.apk`, the matching AAB, and a checksum file;
artifact names must never be copied forward from an older Android release.

The locked desktop runtime dependency inventory is generated with:

```bash
python3 scripts/generate_third_party_inventory.py
python3 scripts/generate_third_party_inventory.py --check
```

`LICENSE`, `THIRD_PARTY_NOTICES.md`, `THIRD_PARTY_INVENTORY.md`, and
`THIRD_PARTY_LICENSES.txt` are bundled in desktop distributions and available offline from About.
Android exposes its MIT license, runtime dependency licenses, and build/test notices from the
settings screen.

## Honest limitations

- No model weights are bundled. The optional OpenTypeless offline model is a separate 228.45 MiB
  user-approved download and has a measured high transient memory peak; Android provider models
  remain owned by the installed recognition service.
- No published cross-device CER/WER, latency, battery, or blind Typeless benchmark yet.
- Standard Android speech-service discovery and behavior can vary across OEM keyboards.
- External BYOK provider smoke tests require maintainer-supplied credentials and are never run in CI.
