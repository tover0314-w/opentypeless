import { describe, expect, it } from 'vitest'
import ciWorkflowSource from '../../../.github/workflows/ci.yml?raw'
import appImageVerificationScriptSource from '../../../.github/scripts/verify-appimage-runtime-libraries.sh?raw'
import appImagePluginWrapperSource from '../../../.github/scripts/linuxdeploy-plugin-appimage-exclude-wrapper.sh?raw'
import linuxdeployPrepareScriptSource from '../../../.github/scripts/prepare-linuxdeploy-wrapper.sh?raw'
import linuxdeployWrapperSource from '../../../.github/scripts/linuxdeploy-exclude-wrapper.rs?raw'
import constantsSource from '../constants.ts?raw'
import linuxVerificationScriptSource from '../../../.github/scripts/upload-linux-verification-artifacts.sh?raw'
import windowsCertificateScriptSource from '../../../.github/scripts/import-windows-certificate.ps1?raw'
import releaseWorkflowSource from '../../../.github/workflows/release.yml?raw'

describe('release version wiring', () => {
  it('lets frontend builds read the release tag version from Vite env', () => {
    expect(constantsSource).toContain('import.meta.env.VITE_APP_VERSION')
  })

  it('exports VITE_APP_VERSION during the GitHub release build', () => {
    expect(releaseWorkflowSource).toContain('VITE_APP_VERSION=v$VERSION')
  })

  it('creates cross-repository releases from the owner main branch', () => {
    expect(releaseWorkflowSource).toContain('releaseCommitish: main')
    expect(releaseWorkflowSource).not.toContain('releaseCommitish: ${{ github.sha }}')
  })

  it('requires an explicit opt-in before publishing unsigned Windows installers', () => {
    expect(releaseWorkflowSource).toContain('allow_unsigned_windows:')
    expect(releaseWorkflowSource).toContain(
      'ALLOW_UNSIGNED_WINDOWS: ${{ github.event.inputs.allow_unsigned_windows }}',
    )
    expect(windowsCertificateScriptSource).toContain(
      "$allowUnsigned = $env:ALLOW_UNSIGNED_WINDOWS -eq 'true'",
    )
    expect(windowsCertificateScriptSource).toContain(
      'Unsigned Windows release explicitly allowed for this manual dispatch.',
    )
  })

  it('builds and verifies Linux arm64 release artifacts on a native runner', () => {
    expect(ciWorkflowSource).toContain('platform: ubuntu-22.04-arm')
    expect(ciWorkflowSource).toContain('target: aarch64-unknown-linux-gnu')
    expect(releaseWorkflowSource).toContain('platform: ubuntu-22.04-arm')
    expect(releaseWorkflowSource).toContain('rust_targets: aarch64-unknown-linux-gnu')
    expect(releaseWorkflowSource).toContain("args: '--target aarch64-unknown-linux-gnu'")
    expect(releaseWorkflowSource).toContain('LINUX_ARCH: ${{ matrix.linux_arch }}')
    expect(linuxVerificationScriptSource).toContain(
      'verification_dir="release-verification/linux-${LINUX_ARCH}"',
    )
    expect(linuxVerificationScriptSource).toContain(
      'sha_file="$verification_dir/SHA256SUMS-linux-${LINUX_ARCH}.txt"',
    )
    expect(linuxVerificationScriptSource).toContain(
      'public_key_path="$verification_dir/OpenTypeless-Linux-${LINUX_ARCH}-GPG-KEY.asc"',
    )
  })

  it('excludes the bundled Wayland client from Linux AppImages', () => {
    expect(releaseWorkflowSource).toContain('Prepare Linux AppImage library exclusions')
    expect(releaseWorkflowSource).toContain('./.github/scripts/prepare-linuxdeploy-wrapper.sh')
    expect(releaseWorkflowSource).toContain('LINUXDEPLOY_EXCLUDED_LIBRARIES: libwayland-client.so*')
    expect(releaseWorkflowSource).toContain('xdg-utils')
    expect(releaseWorkflowSource).toContain('command -v xdg-mime >/dev/null')
    expect(ciWorkflowSource).toContain('Test Linux AppImage packaging guards')
    expect(ciWorkflowSource).toContain('./.github/scripts/test-linux-appimage-packaging.sh')
    expect(linuxdeployPrepareScriptSource).toContain('linuxdeploy-exclude-wrapper.rs')
    expect(linuxdeployPrepareScriptSource).toContain(
      'linuxdeploy-plugin-appimage-exclude-wrapper.sh',
    )
    expect(linuxdeployWrapperSource).toContain('--exclude-library')
    expect(linuxdeployWrapperSource).toContain('libwayland-client.so*')
    expect(appImagePluginWrapperSource).toContain("-name 'libwayland-client.so*' -delete")
    expect(linuxVerificationScriptSource).toContain('verify-appimage-runtime-libraries.sh')
  })

  it('rejects a release AppImage that still contains the Wayland client', () => {
    expect(appImageVerificationScriptSource).toContain('--appimage-extract')
    expect(appImageVerificationScriptSource).toContain("-name 'libwayland-client.so*'")
  })
})
