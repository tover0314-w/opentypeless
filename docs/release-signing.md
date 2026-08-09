# Release Signing Setup

OpenTypeless releases are built and published from `dengxuezhao/opentypeless`.

## Required GitHub Secrets

Set these secrets on `dengxuezhao/opentypeless`, because that repository runs
the GitHub Actions workflow.

macOS:

- `APPLE_CERTIFICATE`
- `APPLE_CERTIFICATE_PASSWORD`
- `APPLE_SIGNING_IDENTITY`
- `APPLE_ID`
- `APPLE_PASSWORD`
- `APPLE_TEAM_ID`

Cross-repository publishing:

- `RELEASE_TOKEN`

Desktop auto-update artifacts are intentionally disabled by
`bundle.createUpdaterArtifacts: false`. No `TAURI_SIGNING_PRIVATE_KEY` secret is required, and the
release workflows must not emit updater `.sig` files or `latest.json`. The delayed macOS stapling
workflow accepts the notarization submission ID for the already-published DMG and replaces only
that stapled DMG.

Android:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded maintainer-controlled JKS/PKCS12 keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The release workflow refuses to publish Android without all four signing secrets. It verifies the
APK and AAB signatures and publishes a SHA-256 checksum file. Local builds remain unsigned unless
the corresponding `ANDROID_KEYSTORE_*` environment variables are supplied.

## Third-Party License Material

Desktop releases must include these four files at the top level of the Tauri resource directory:

- `LICENSE`
- `THIRD_PARTY_NOTICES.md`
- `THIRD_PARTY_INVENTORY.md`
- `THIRD_PARTY_LICENSES.txt`

The inventory separates npm browser-runtime packages, Cargo runtime-linked crates, and Cargo
build-only/proc-macro-host crates. Cargo dev dependencies and npm root dev dependencies are
excluded. The full license file deliberately includes build-only license text as a conservative
superset without claiming those crates are linked into the installed executable.

Regenerate and verify the committed material with the pinned extractor:

```bash
cargo install cargo-about --version 0.9.1 --locked --features cli
cargo fetch --locked --manifest-path src-tauri/Cargo.toml
npm ci
python3 scripts/generate_third_party_inventory.py
python3 scripts/generate_third_party_inventory.py --check
```

The generator runs cargo-about in frozen/offline mode after the explicit fetch. This makes the
result depend on the lock files and fetched package sources instead of live license-service output.
Both CI and the release workflow reject stale generated material.

After a macOS bundle build, verify the actual app rather than only the Tauri configuration:

```bash
RESOURCE_DIR="src-tauri/target/release/bundle/macos/OpenTypeless.app/Contents/Resources"
test -f "$RESOURCE_DIR/LICENSE"
test -f "$RESOURCE_DIR/THIRD_PARTY_NOTICES.md"
test -f "$RESOURCE_DIR/THIRD_PARTY_INVENTORY.md"
test -f "$RESOURCE_DIR/THIRD_PARTY_LICENSES.txt"
cmp LICENSE "$RESOURCE_DIR/LICENSE"
cmp THIRD_PARTY_NOTICES.md "$RESOURCE_DIR/THIRD_PARTY_NOTICES.md"
cmp THIRD_PARTY_INVENTORY.md "$RESOURCE_DIR/THIRD_PARTY_INVENTORY.md"
cmp THIRD_PARTY_LICENSES.txt "$RESOURCE_DIR/THIRD_PARTY_LICENSES.txt"
```

For a universal build, use
`src-tauri/target/universal-apple-darwin/release/bundle/macos/OpenTypeless.app/Contents/Resources`.
The About screen opens these bundled files through a fixed document identifier, so they remain
available offline and arbitrary filesystem paths are not accepted from the webview.

The lock-file generator covers npm and Cargo package sources only. Before publishing each target,
also inspect the finished artifact for material introduced by the packager rather than those lock
files. In particular:

- keep `src/assets/app-icons/reference` absent unless every proposed brand image has a recorded
  provenance and redistribution basis; the current UI deliberately uses licensed generic family
  glyphs instead of third-party logo artwork;
- inspect Linux AppImage/RPM contents for bundled system libraries and include any notices their
  licenses require;
- confirm whether fonts, media, installer assets, model files, or service SDK data added outside
  npm/Cargo need separate notices; and
- keep Android's dependency and asset notice process separate from this desktop inventory.

Treat an unverified third-party asset or package-injected binary as a release blocker. The
generated files are a reproducible compliance aid, not legal advice or proof that every
non-package asset is cleared for distribution.

The release workflow extracts each AppImage, compares all four legal documents byte-for-byte with
the committed files, and publishes a signed-checksum-covered
`APPIMAGE-CONTENTS-linux-<architecture>.txt` manifest. Review newly introduced bundled libraries
in that manifest before promoting the prerelease; inventory generation alone does not determine
their license obligations.

Linux:

- `LINUX_GPG_PRIVATE_KEY`: base64-encoded ASCII-armored private GPG key
- `LINUX_GPG_KEY_ID`: GPG key ID or fingerprint
- `LINUX_GPG_PASSPHRASE`: GPG key passphrase

Windows PFX fallback:

- `WINDOWS_CERTIFICATE`: base64-encoded PFX code signing certificate
- `WINDOWS_CERTIFICATE_PASSWORD`: required when `WINDOWS_CERTIFICATE` is set
- `WINDOWS_TIMESTAMP_URL`: optional timestamp server URL; defaults to DigiCert

The general `Release` workflow refuses to build or publish Windows artifacts
when the PFX signing secrets are absent. Use that workflow for Windows only when
a trusted PFX certificate is configured. Otherwise, publish Windows through the
dedicated `Release Windows SignPath` workflow below. Unsigned and test-signed
installers are blocked from public releases by default.

For a deliberate temporary exception, a manual workflow dispatch may set
`allow_unsigned_windows` to `true`. This opt-in is disabled by default and is
the only path that permits the general workflow to publish unsigned Windows
installers. Tag-triggered and ordinary manual releases still fail closed.

Windows SignPath:

- `SIGNPATH_API_TOKEN`: token for a SignPath user that is a submitter for the
  selected signing policy
- `SIGNPATH_ORGANIZATION_ID`: SignPath organization ID
- `SIGNPATH_PROJECT_SLUG`: SignPath project slug
- `SIGNPATH_SIGNING_POLICY_SLUG`: SignPath signing policy slug

The SignPath project and GitHub trusted build system must point to
`dengxuezhao/opentypeless`, because that repository runs the GitHub Actions
workflow and owns the GitHub artifact submitted to SignPath. Signed artifacts
are published to the same repository.

The Windows SignPath workflow uses the project's default artifact
configuration. This default artifact configuration must have a `<zip-file>`
root because GitHub's `actions/upload-artifact` action stores files as a ZIP
archive.

Signing policies whose slug starts with `test-` or `test_` are dry-run only.
They may verify the build-to-SignPath integration, but the workflow refuses to
publish those installers to a production GitHub Release. Publishing requires a
production SignPath policy whose Authenticode result is `Valid`.

For a complete release without a PFX certificate, dispatch the general
`Release` workflow separately for `macos` and `linux`, then dispatch
`Release Windows SignPath` with `publish_release` set to `true`. Do not use the
general workflow's `all` option until a trusted Windows PFX certificate is
configured, because its Windows job will intentionally fail closed.

## Windows Certificate Notes

Use a real code signing certificate. SSL/TLS certificates do not sign Windows
desktop apps. EV certificates get Microsoft SmartScreen reputation immediately;
OV certificates can still show SmartScreen warnings until reputation builds.

If you receive a `.pfx`, encode it before saving it as a GitHub secret:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("certificate.pfx")) |
  Set-Content -NoNewline windows-certificate-base64.txt
```

Save the content of `windows-certificate-base64.txt` as `WINDOWS_CERTIFICATE`.

## Linux GPG Notes

Generate a release-only GPG key, export it, and base64 encode it:

```bash
gpg --full-gen-key
gpg --armor --export-secret-keys "OpenTypeless Release" > opentypeless-linux-private.asc
openssl base64 -A -in opentypeless-linux-private.asc -out opentypeless-linux-private.asc.base64
gpg --list-secret-keys --keyid-format LONG
```

Save `opentypeless-linux-private.asc.base64` as `LINUX_GPG_PRIVATE_KEY`, the
fingerprint/key ID as `LINUX_GPG_KEY_ID`, and the passphrase as
`LINUX_GPG_PASSPHRASE`.

The workflow embeds an AppImage signature, signs RPM bundles through Tauri,
creates detached `.asc` signatures for Linux artifacts, and uploads
architecture-specific checksum manifests such as `SHA256SUMS-linux-x86_64.txt`
and `SHA256SUMS-linux-aarch64.txt`.
