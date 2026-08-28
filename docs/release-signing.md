# Release Signing Setup

OpenTypeless releases are built in `toverwu-qaq/opentypeless` and published to
`tover0314-w/opentypeless`.

## Required GitHub Secrets

Set these secrets on `toverwu-qaq/opentypeless`, because that repository runs
the GitHub Actions workflow.

macOS:

- `APPLE_CERTIFICATE`
- `APPLE_CERTIFICATE_PASSWORD`
- `APPLE_SIGNING_IDENTITY`
- `APPLE_ID`
- `APPLE_PASSWORD`
- `APPLE_TEAM_ID`

Tauri updater:

- `TAURI_SIGNING_PRIVATE_KEY`
- `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`

Cross-repository publishing:

- `RELEASE_TOKEN`

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
`toverwu-qaq/opentypeless`, because that repository runs the GitHub Actions
workflow and owns the GitHub artifact submitted to SignPath. Signed Windows
artifacts are still published to `tover0314-w/opentypeless`.

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
