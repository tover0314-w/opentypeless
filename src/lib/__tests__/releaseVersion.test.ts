import { describe, expect, it } from 'vitest'
import ciWorkflowSource from '../../../.github/workflows/ci.yml?raw'
import windowsTestWorkflowSource from '../../../.github/workflows/windows-local-test.yml?raw'
import englishReadme from '../../../README.md?raw'
import chineseReadme from '../../../README_zh.md?raw'
import cargoLock from '../../../src-tauri/Cargo.lock?raw'
import cargoManifestSource from '../../../src-tauri/Cargo.toml?raw'
import windowsPublishScript from '../../../.github/scripts/publish-signpath-windows-release.ps1?raw'
import appImageVerificationScriptSource from '../../../.github/scripts/verify-appimage-runtime-libraries.sh?raw'
import appImagePluginWrapperSource from '../../../.github/scripts/linuxdeploy-plugin-appimage-exclude-wrapper.sh?raw'
import linuxdeployPrepareScriptSource from '../../../.github/scripts/prepare-linuxdeploy-wrapper.sh?raw'
import linuxdeployWrapperSource from '../../../.github/scripts/linuxdeploy-exclude-wrapper.rs?raw'
import constantsSource from '../constants.ts?raw'
import linuxVerificationScriptSource from '../../../.github/scripts/upload-linux-verification-artifacts.sh?raw'
import packageJson from '../../../package.json'
import dependencyLock from '../../../package-lock.json'
import tauriConfig from '../../../src-tauri/tauri.conf.json'
import macArm64DevelopmentConfig from '../../../src-tauri/tauri.macos-arm64-dev.conf.json'
import { APP_VERSION, APP_VERSION_HEADER_VALUE } from '../constants'

describe('release version wiring', () => {
  it('binds future Windows updater signatures to the manifest version', () => {
    expect(windowsPublishScript).toContain('tauri signer sign --app-version "$version"')
    expect(windowsPublishScript).toContain('node scripts/validate-release-version.mjs "$tagName"')
    expect(windowsPublishScript.indexOf('node scripts/validate-release-version.mjs')).toBeLessThan(
      windowsPublishScript.indexOf('tauri signer sign'),
    )
    expect(windowsPublishScript).not.toContain("$tagName.TrimStart('v')")
  })
  it('stops Windows signing and publishing when release version preflight fails', () => {
    const preflight = windowsPublishScript.indexOf('node scripts/validate-release-version.mjs')
    const failure = windowsPublishScript.indexOf(
      "throw 'Release version preflight failed before signing.'",
      preflight,
    )
    expect(preflight).toBeGreaterThanOrEqual(0)
    expect(failure).toBeGreaterThan(preflight)
    expect(windowsPublishScript.slice(preflight, failure)).toContain('if ($LASTEXITCODE -ne 0)')
    expect(failure).toBeLessThan(windowsPublishScript.indexOf('tauri signer sign'))
    expect(failure).toBeLessThan(windowsPublishScript.indexOf('gh release create'))
    expect(failure).toBeLessThan(windowsPublishScript.indexOf('gh release upload'))
  })
  it('runs the executable release version preflight regressions in CI', () => {
    expect(packageJson.scripts).toHaveProperty(
      'test:release-version',
      'node --test scripts/__tests__/validate-release-version.test.mjs',
    )
    expect(ciWorkflowSource).toContain('- run: npm run test:release-version')
  })
  it('requires an unambiguous Windows installer pair before signing', () => {
    expect(windowsPublishScript).toContain('node scripts/select-windows-release-artifacts.mjs')
    expect(windowsPublishScript).not.toContain('Select-Object -First 1')
    expect(
      windowsPublishScript.indexOf('node scripts/select-windows-release-artifacts.mjs'),
    ).toBeLessThan(windowsPublishScript.indexOf('tauri signer sign'))
    expect(ciWorkflowSource).toContain('- run: npm run test:windows-release-artifacts')
  })
  it('aligns the arm64 development bundle minimum with its observed Mach-O baseline', () => {
    expect(macArm64DevelopmentConfig.bundle.macOS.minimumSystemVersion).toBe('11.0')
    expect(macArm64DevelopmentConfig.bundle.createUpdaterArtifacts).toBe(false)
    expect(tauriConfig.bundle.macOS.minimumSystemVersion).toBe('10.15')
  })

  it('uses the arm64-only development override through the package build entry', () => {
    expect(packageJson.scripts).toHaveProperty(
      'build:mac-dev',
      'tauri build --debug --target aarch64-apple-darwin --bundles app --no-sign --ci --config src-tauri/tauri.macos-arm64-dev.conf.json -- --locked --offline',
    )
  })

  it('keeps Vite on the reviewed server security patch with aligned native bindings', () => {
    expect(dependencyLock.packages['node_modules/vite'].version).toBe('8.1.6')
    expect(dependencyLock.packages['node_modules/rolldown'].version).toBe('1.1.8')
    for (const [path, dependency] of Object.entries(dependencyLock.packages)) {
      if (path.startsWith('node_modules/@rolldown/binding-')) {
        expect(dependency.version, path).toBe('1.1.8')
      }
    }
  })

  it('keeps the Vitest mocker security patch and internal packages aligned', () => {
    for (const [path, dependency] of Object.entries(dependencyLock.packages)) {
      if (path === 'node_modules/vitest' || path.startsWith('node_modules/@vitest/')) {
        expect(dependency.version, path).toBe('4.1.12')
      }
    }
    expect(dependencyLock.packages['node_modules/@vitest/mocker'].integrity).toBe(
      'sha512-fKShNpKZzupWZuY0z7egydvL0sqW4INEZBEExMBMAa6izlENEVHwoXgdaTCaBZ5fwlr4xf2o1wXC83n9RpO+NA==',
    )
  })

  it('keeps Zod on the reviewed oversized-collection security patch', () => {
    expect(dependencyLock.packages['node_modules/zod'].version).toBe('4.6.7')
    expect(dependencyLock.packages['node_modules/zod'].integrity).toBe(
      'sha512-g8W2D2kU6a8untc053fX1uVBKzM7yqAxW/3zwk83+6RyF2+SKOuV+c0wBDhUwzV1PaGapmZH+rmcUzPf+3CGwg==',
    )
  })

  it('uses the rustix patch that fixes zero-length xattr buffer handling', () => {
    const rustix = cargoLock
      .split('[[package]]')
      .find((block) => block.includes('name = "rustix"\n'))
    expect(rustix).toBeDefined()
    expect(rustix).toContain('version = "1.1.6"')
  })

  it('uses the reviewed non-yanked Windows clipboard patch', () => {
    const clipboard = cargoLock
      .split('[[package]]')
      .find((block) => block.includes('name = "clipboard-win"\n'))
    expect(clipboard).toBeDefined()
    expect(clipboard).toContain('version = "5.4.3"')
    expect(clipboard).not.toContain('version = "5.4.1"')
  })

  it('uses the executor patch that fixes the reviewed drop and wake race', () => {
    const executor = cargoLock
      .split('[[package]]')
      .find((block) => block.includes('name = "async-executor"\n'))
    expect(executor).toBeDefined()
    expect(executor).toContain('version = "1.14.1"')
    expect(executor).not.toContain('version = "1.14.0"')
  })

  it('keeps the Wayland scanner on the reviewed XML security patch', () => {
    expect(cargoLock).toContain('name = "wayland-scanner"\nversion = "0.31.11"')
    expect(cargoLock).not.toContain('name = "quick-xml"\nversion = "0.39.4"')
    expect(cargoLock).toContain('name = "quick-xml"\nversion = "0.41.0"')
  })

  it.each([englishReadme, chineseReadme])(
    'keeps installation guidance from recommending platform security bypasses',
    (readme) => {
      expect(readme).not.toMatch(/xattr\s+-|Run anyway|仍要运行|解除锁定/)
      expect(readme).toContain('arm64')
      expect(readme).toContain('Gatekeeper')
      expect(readme).toContain('SmartScreen')
    },
  )

  it('requires executed tests and strict checks before producing an internal Windows installer', () => {
    const installer = windowsTestWorkflowSource.indexOf('- name: Build unsigned internal installer')
    expect(installer).toBeGreaterThan(0)
    const checks = windowsTestWorkflowSource.slice(0, installer)
    for (const command of [
      'npm ci --engine-strict --ignore-scripts',
      'npm test',
      'npm run lint',
      'npm run format:check',
      'cargo fmt --check --manifest-path src-tauri/Cargo.toml',
      'cargo clippy --locked --all-targets --target x86_64-pc-windows-msvc --manifest-path src-tauri/Cargo.toml -- -D warnings',
      'cargo test --locked --target x86_64-pc-windows-msvc --manifest-path src-tauri/Cargo.toml',
    ]) {
      expect(checks).toContain(`run: ${command}`)
    }
    expect(checks).not.toContain('--no-run')
    const buildCommand = windowsTestWorkflowSource
      .split('\n')
      .find((line) => line.includes('run: npx tauri build'))
    expect(buildCommand).toBeDefined()
    expect(buildCommand).toContain('--ci')
    expect(buildCommand).toMatch(/ -- --locked\s*$/)
    expect(windowsTestWorkflowSource).not.toContain('continue-on-error')
    expect(windowsTestWorkflowSource).not.toContain('always()')
    expect(windowsTestWorkflowSource).toContain("branches:\n      - 'test/windows-local-*'")
    expect(windowsTestWorkflowSource).toContain('contents: read')
    expect(windowsTestWorkflowSource).toContain(
      'Internal unsigned test build. Not a stable release.',
    )
  })

  it('ships license texts and hashes every collected Windows package file', () => {
    const collection = windowsTestWorkflowSource
      .split('- name: Collect package and checksums')[1]
      ?.split('- uses: actions/upload-artifact')[0]
    expect(collection).toBeDefined()
    expect(collection).toContain('Copy-Item LICENSE, THIRD_PARTY_NOTICES.md windows-test-package/')
    expect(collection).toContain(
      'Copy-Item src/assets/fonts/prompt/OFL.txt windows-test-package/Prompt-OFL.txt',
    )
    expect(collection).toContain('Get-ChildItem windows-test-package/ -File')
    expect(collection).not.toContain('Get-ChildItem windows-test-package/*.exe')
  })

  it('runs npm and Rust dependency audits independently without suppressing failures', () => {
    const frontendAudit = ciWorkflowSource
      .split('\n  audit-frontend:')[1]
      ?.split('\n  audit-rust:')[0]
    const rustAudit = ciWorkflowSource.split('\n  audit-rust:')[1]
    expect(frontendAudit).toBeDefined()
    expect(rustAudit).toBeDefined()
    expect(frontendAudit).toContain('npm audit --audit-level=high')
    expect(frontendAudit).not.toContain('cargo audit')
    expect(rustAudit).toContain('cargo audit --file src-tauri/Cargo.lock')
    expect(rustAudit).not.toContain('npm audit')
    expect(rustAudit).not.toContain('needs:')
    expect(`${frontendAudit}${rustAudit}`).not.toContain('continue-on-error')
  })

  it('audits all locked Rust advisories without excluding unresolved dependency findings', () => {
    const audit = ciWorkflowSource
      .split('\n')
      .find((line) => line.includes('- run: cargo audit --file'))
    expect(audit).toBeDefined()
    expect(audit).not.toContain('--ignore')
    expect(audit).toContain('--deny unsound')
    expect(audit).not.toContain('--target-os')
  })

  it('updates Enigo without silently switching the existing Linux input backend', () => {
    expect(cargoManifestSource).toContain(
      'enigo = { version = "=0.6.1", default-features = false, features = ["xdo"] }',
    )
  })

  it('uses Node 24 in every CI job and rejects incompatible dependency engines during installation', () => {
    expect(ciWorkflowSource.match(/node-version: ['"]?24['"]?/g)).toHaveLength(3)
    expect(ciWorkflowSource).not.toMatch(/node-version: ['"]?20['"]?/)
    expect(ciWorkflowSource.match(/npm ci --engine-strict/g)).toHaveLength(2)
    expect(ciWorkflowSource.match(/npm ci --engine-strict --ignore-scripts/g)).toHaveLength(2)
  })

  it('checks production frontend builds and locked Rust test targets in CI', () => {
    expect(ciWorkflowSource).toContain('- run: npm run build')
    expect(ciWorkflowSource).toContain('cargo clippy --locked --all-targets --target')
    expect(ciWorkflowSource).toContain('cargo test --locked --target')
  })

  it('uses the current package version for local builds and cloud version headers', () => {
    expect(packageJson.version).toBe(tauriConfig.version)
    expect(dependencyLock.version).toBe(packageJson.version)
    expect(dependencyLock.packages[''].version).toBe(packageJson.version)
    const nativePackage = cargoLock
      .split('[[package]]')
      .find((block) => block.includes('name = "opentypeless"\n'))
    expect(nativePackage).toContain(`version = "${packageJson.version}"`)
    const nativeManifestPackage = cargoManifestSource
      .split(/^\[package\]\s*$/m)[1]
      ?.split(/^\[/m)[0]
    expect(nativeManifestPackage).toContain(`version = "${packageJson.version}"`)
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
