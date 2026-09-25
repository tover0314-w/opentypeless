# Release Signing Setup

OpenTypeless releases are built in `toverwu-qaq/opentypeless` and published to
`tover0314-w/opentypeless`.

The legacy Release Drafter workflow is disabled in both repositories. If a
same-tag draft was created before this change landed, release preflight refuses
to mutate it automatically; inspect and remove the stale draft before retrying.

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
- `APPLE_EXPECTED_SIGNER_SHA256`: lowercase SHA-256 digest of the leaf Developer ID Application certificate

Tauri updater:

- `TAURI_SIGNING_PRIVATE_KEY`
- `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`

Cross-repository publishing:

- `RELEASE_TOKEN`

Linux:

- `LINUX_GPG_PRIVATE_KEY`: base64-encoded ASCII-armored private GPG key
- `LINUX_GPG_KEY_ID`: GPG key ID or fingerprint
- `LINUX_GPG_PASSPHRASE`: GPG key passphrase
- `LINUX_EXPECTED_GPG_FINGERPRINT`: full primary-key fingerprint trusted by the final release gate

Windows PFX fallback:

- `WINDOWS_CERTIFICATE`: base64-encoded PFX code signing certificate
- `WINDOWS_CERTIFICATE_PASSWORD`: required when `WINDOWS_CERTIFICATE` is set
- `WINDOWS_TIMESTAMP_URL`: optional timestamp server URL; defaults to DigiCert

These PFX secrets are retained only for historical reference. The current
production release workflow has no PFX path.

Windows installers are published only through the dedicated `Release Windows`
workflow. The general `Release macOS and Linux` workflow has no Windows path.
`signing_mode: signpath` requires the production signer and remains the
recommended default. `signing_mode: unsigned` is an explicit release-operator
exception: the workflow requires both installers to be completely unsigned and
still enforces Tauri updater signatures, checksums, pinned source provenance,
and the final asset gate. Test-signed or invalidly signed installers are never
accepted as unsigned. Unsigned installers may show Windows SmartScreen or
Unknown Publisher warnings.

Windows SignPath:

- `SIGNPATH_API_TOKEN`: token for a SignPath user that is a submitter for the
  selected signing policy
- `SIGNPATH_ORGANIZATION_ID`: SignPath organization ID
- `SIGNPATH_PROJECT_SLUG`: SignPath project slug
- `SIGNPATH_SIGNING_POLICY_SLUG`: SignPath signing policy slug
- `WINDOWS_EXPECTED_SIGNER_THUMBPRINT`: thumbprint of the trusted production Authenticode leaf certificate

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
production SignPath policy, an Authenticode result of `Valid`, and an exact
match with `WINDOWS_EXPECTED_SIGNER_THUMBPRINT`.

For a complete release, dispatch `Release macOS and Linux` separately for
`macos` and `linux`, then dispatch `Release Windows` with the approved
`signing_mode` and `publish_release` set to `true`. Run `Staple macOS Release Assets` if Apple
notarization completes asynchronously. Finally, run `Finalize Release`; it
promotes the prerelease only after all platform signatures, the exact asset
inventory, signed build-provenance manifests, and the immutable asset digest
snapshot pass. Each provenance manifest binds its platform assets to the exact
official Tag SHA and CI/CD SHA used for the build.
`Finalize Release.windows_signing_mode` must match the mode used by the Windows
build.

Freeze both repositories' `main` branches from the moment the official tag is
created until `Finalize Release` completes. Every stage pins and rechecks both
commit SHAs; an intervening merge intentionally stops the release.

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
