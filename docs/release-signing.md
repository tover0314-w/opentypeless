# Release Signing Setup

## Checkout And Authorization Boundary

The production workflow names described below refer to the separate CI/CD
repository, not to workflows implemented in every application checkout.
In the parity worktree reviewed on 2026-11-20, the workflow directory contains
CI, a notarization-status query, and an unsigned Windows local-test workflow;
it does not contain the production macOS/Linux release, Windows release,
stapling, or finalization workflows described below. Their current remote
contents and configured secrets have not been verified in this review.

A configured updater endpoint or public key does not establish possession of
the corresponding private signing key, a matching signed release, or a
successful installation. Likewise, a locally available Developer ID identity
does not authorize its use. Obtain explicit approval before signing, uploading
to Apple, dispatching release workflows, pushing source, or publishing assets.
Do not treat local test packages or documentation as evidence that these
production gates have passed.

## Local macOS Review Candidates

Local review packages must preserve a stable signing identity across builds.
Do not substitute an ad-hoc signature for a certificate-backed identity when
testing Accessibility permission continuity. A successful `codesign --verify`
checks signature integrity, not whether existing TCC grants match the new build.

Before handing off a candidate:

1. Copy the built app into a distinct candidate directory; do not overwrite a
   package the user is testing.
2. Select the exact available Developer ID Application identity fingerprint,
   rather than the first certificate with a matching display name. Keep the
   bundle identifier and team identity consistent.
3. Sign with the project entitlements, hardened runtime and a secure timestamp.
   Read back the entitlements and designated requirement. Reject an ad-hoc
   candidate for cross-version permission testing.
4. With explicit upload authorization, submit the archive to Apple using a
   Keychain credential profile. Record the submission ID immediately; resume
   that submission after a timeout instead of uploading it again.
5. Require an `Accepted` result, staple and validate the ticket, then verify the
   signature and require `spctl --assess --type execute` to accept the app.
6. Generate the delivery ZIP after stapling. Record the source/build identity,
   app and ZIP hashes, architecture, version and remaining failed gates.
7. Test the actual app process. The first transition from ad-hoc to Developer ID
   may require a new Accessibility grant. Verify recovery without relaunching,
   then verify permission continuity with a subsequent distinct build.

Never put passwords or private keys in repository files or command arguments.
Use secure local credential input. Do not reset TCC, weaken Gatekeeper, remove
quarantine attributes, or add private Apple entitlements to make a candidate
appear accepted.

Signing and notarization do not certify feature parity, automatic text receipt,
dependency security or cross-platform release readiness. Those gates remain
independent.

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
build. The Windows job verifies the installer signatures and checksums on a
native runner; the complete immutable asset snapshot is checked by the other
platform jobs and once more immediately before promotion.

Freeze the official repository's `main` branch from the moment the official tag
is created until `Finalize Release` completes. Every stage pins and rechecks the
official Tag SHA and the CI/CD build SHA. `Finalize Release` normally runs from
that exact CI/CD SHA; a later CI/CD commit is accepted only when the build SHA is
its ancestor and every intervening change is limited to release automation or
release documentation. Any application, dependency, or packaging-input change
after the build intentionally stops the release.

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
