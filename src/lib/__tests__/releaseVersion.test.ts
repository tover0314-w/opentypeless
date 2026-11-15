import { describe, expect, it } from 'vitest'
import ciWorkflowSource from '../../../.github/workflows/ci.yml?raw'
import appImageVerificationScriptSource from '../../../.github/scripts/verify-appimage-runtime-libraries.sh?raw'
import appImagePluginWrapperSource from '../../../.github/scripts/linuxdeploy-plugin-appimage-exclude-wrapper.sh?raw'
import linuxdeployPrepareScriptSource from '../../../.github/scripts/prepare-linuxdeploy-wrapper.sh?raw'
import linuxdeployWrapperSource from '../../../.github/scripts/linuxdeploy-exclude-wrapper.rs?raw'
import constantsSource from '../constants.ts?raw'
import linuxVerificationScriptSource from '../../../.github/scripts/upload-linux-verification-artifacts.sh?raw'
import packageJson from '../../../package.json'
import tauriConfig from '../../../src-tauri/tauri.conf.json'
import { APP_VERSION, APP_VERSION_HEADER_VALUE } from '../constants'

describe('release version wiring', () => {
  it('checks production frontend builds and locked Rust test targets in CI', () => {
    expect(ciWorkflowSource).toContain('- run: npm run build')
    expect(ciWorkflowSource).toContain('cargo clippy --locked --all-targets --target')
    expect(ciWorkflowSource).toContain('cargo test --locked --target')
  })

  it('uses the current package version for local builds and cloud version headers', () => {
    expect(packageJson.version).toBe(tauriConfig.version)
    expect(APP_VERSION).toBe(`v${packageJson.version}`)
    expect(APP_VERSION_HEADER_VALUE).toBe(packageJson.version)
  })

  it('lets frontend builds read the release tag version from Vite env', () => {
    expect(constantsSource).toContain('import.meta.env.VITE_APP_VERSION')
  })

  it('builds and verifies Linux arm64 release artifacts on a native runner', () => {
    expect(ciWorkflowSource).toContain('platform: ubuntu-22.04-arm')
    expect(ciWorkflowSource).toContain('target: aarch64-unknown-linux-gnu')
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
