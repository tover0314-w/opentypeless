import { describe, expect, it } from 'vitest'
import cargoLock from '../../../src-tauri/Cargo.lock?raw'
import notices from '../../../THIRD_PARTY_NOTICES.md?raw'
import tauriConfig from '../../../src-tauri/tauri.conf.json'
import fontProvenance from '../../assets/fonts/prompt/provenance.json'
import fontLicense from '../../assets/fonts/prompt/OFL.txt?raw'
import frontendLock from '../../../package-lock.json'

describe('macOS clipboard and credential distribution notices', () => {
  it.each([
    ['arboard', 'Copyright (c) 2022 The Arboard contributors'],
    ['security-framework', 'Copyright (c) 2015 Steven Fackler'],
    ['png', 'Copyright (c) 2015 nwin'],
    ['miniz_oxide', 'Copyright 2013-2014 RAD Game Tools and Valve Software'],
    ['flate2', 'Copyright (c) 2014-2026 Alex Crichton'],
    ['keyring', 'Copyright (c) 2016 keyring Developers'],
    ['native-tls', 'Copyright (c) 2016 The rust-native-tls Developers'],
    ['rustls-platform-verifier', 'Copyright (c) 2022 1Password'],
    ['core-foundation', 'Copyright (c) 2012-2013 Mozilla Foundation'],
    ['security-framework-sys', 'Copyright (c) 2015 Steven Fackler'],
    ['core-foundation-sys', 'Copyright (c) 2012-2013 Mozilla Foundation'],
    ['dirs', 'Copyright (c) 2018-2019 dirs-rs contributors'],
    ['dirs-sys', 'Copyright (c) 2018-2019 dirs-rs contributors'],
    ['tokio', 'Copyright (c) Tokio Contributors'],
    ['tracing', 'Copyright (c) 2019 Tokio Contributors'],
    ['tracing-subscriber', 'Copyright (c) 2019 Tokio Contributors'],
    ['tokio-tungstenite', 'Copyright (c) 2017 Daniel Abramov'],
    ['chrono', 'Copyright (c) 2014, Kang Seonghoon.'],
    ['plist', 'Copyright (c) 2015 Edward Barnard'],
    ['dasp_sample', 'Copyright (c) 2016 RustAudio Developers'],
  ])('retains the distinct %s copyright and complete MIT option', (name, copyright) => {
    const versions = Array.from(
      cargoLock.matchAll(
        new RegExp(`\\[\\[package\\]\\]\\s+name = "${name}"\\s+version = "([^"]+)"`, 'g'),
      ),
      (match) => match[1],
    )
    expect(versions.length).toBeGreaterThan(0)
    const section =
      notices.split(`## ${name} ${versions.join(' and ')}\n`)[1]?.split('\n## ')[0] ?? ''
    expect(section).toContain(copyright)
    expect(section).toContain('MIT')
    expect(section).toContain('THE SOFTWARE IS PROVIDED "AS IS"')
    expect(section).toContain('SOFTWARE.')
  })
})

describe('TLS and bundled SQLite distribution notices', () => {
  it('retains the allocator repository BSD notice and pinned provenance', () => {
    const section = notices.split('## alloc-stdlib 0.3.0\n')[1]?.split('\n## ')[0] ?? ''
    expect(cargoLock).toContain('name = "alloc-stdlib"\nversion = "0.3.0"')
    expect(section).toContain('0a81fd6928ea3b33c8cd484aa4575d50ffb98012')
    expect(section).toContain('Copyright (c) 2016 Dropbox, Inc.')
    expect(section).toContain('Neither the name of the copyright holder')
    expect(section).toContain('EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.')
  })
  it('attributes the pinned Objective-C bridge versions to their shared repository license', () => {
    for (const [name, version] of [
      ['block2', '0.5.1'],
      ['objc2', '0.5.2'],
      ['objc-sys', '0.3.5'],
    ]) {
      expect(cargoLock).toContain(`name = "${name}"\nversion = "${version}"`)
    }
    const section = notices.split('## block2 0.5.1 and objc2 0.5.2\n')[1]?.split('\n## ')[0] ?? ''
    expect(section).toContain('4fc083f1c6d6784577e38b0ee8dbd344481e2fd2')
    expect(section).toContain('Copyright (c) Steven Sheldon')
    expect(section).toContain('MIT License')
    expect(section).toContain('objc-sys 0.3.5')
    expect(section).toContain('SOFTWARE.')
  })
  it('retains the shared MIT terms and distinct copyrights of the remaining direct crates', () => {
    const section =
      notices.split('## Additional direct dependency MIT notices\n')[1]?.split('\n## ')[0] ?? ''
    for (const name of ['futures-util', 'http', 'url', 'uuid']) {
      const version = cargoLock.match(
        new RegExp(`\\[\\[package\\]\\]\\s+name = "${name}"\\s+version = "([^"]+)"`),
      )?.[1]
      expect(version).toBeDefined()
      expect(section).toContain(`${name} ${version}`)
    }
    for (const copyright of [
      'Copyright (c) 2016 Alex Crichton',
      'Copyright (c) 2017 The Tokio Authors',
      'Copyright (c) 2017 http-rs authors',
      'Copyright (c) 2013-2025 The rust-url developers',
      'Copyright (c) 2014 The Rust Project Developers',
      'Copyright (c) 2018 Ashley Mannix, Christopher Armstrong, Dylan DPC, Hunar Roop Kahlon',
    ])
      expect(section).toContain(copyright)
    expect(section).toContain('DEALINGS IN THE SOFTWARE.')
  })

  it('keeps the distinct copyrights of the locked Base64 versions', () => {
    const older = notices.split('## base64 0.21.7 and 0.22.1\n')[1]?.split('\n## ')[0] ?? ''
    const newer = notices.split('## base64 0.23.1\n')[1]?.split('\n## ')[0] ?? ''
    expect(older).toContain('Copyright (c) 2015 Alice Maz')
    expect(newer).toContain('Copyright (c) 2025 Alice Maz, Marshall Pierce')
    for (const section of [older, newer]) {
      expect(section).toContain('THE SOFTWARE IS PROVIDED "AS IS"')
      expect(section).toContain('THE SOFTWARE.')
    }
  })
  it.each(['serde', 'serde_core', 'serde_json', 'anyhow', 'async-trait'])(
    'retains a distinct locked %s license notice without inventing a copyright',
    (name) => {
      const version = cargoLock.match(
        new RegExp(`\\[\\[package\\]\\]\\s+name = "${name}"\\s+version = "([^"]+)"`),
      )?.[1]
      expect(version).toBeDefined()
      const section = notices.split(`## ${name} ${version}\n`)[1]?.split('\n## ')[0] ?? ''
      expect(section).toContain('MIT')
      expect(section).toContain('Permission is hereby granted')
      expect(section).toContain('DEALINGS IN THE SOFTWARE.')
      expect(section).toContain('supplies no named copyright holder')
    },
  )

  it('identifies the locked MPL component and recipient source requirements', () => {
    const version = cargoLock.match(
      /\[\[package\]\]\s+name = "option-ext"\s+version = "([^"]+)"/,
    )?.[1]
    expect(version).toBeDefined()
    const section = notices.split(`## option-ext ${version}\n`)[1]?.split('\n## ')[0] ?? ''
    expect(section).toContain('MPL-2.0')
    expect(section).toContain('04744f49eae99ab78e0d5c0b603ab218f515ea8cfe5a456d7629ad883a3b6e7d')
    expect(section).toContain('Source Code Form')
    expect(section).toContain('inform recipients')
  })

  it('retains the separate certificate verifier notice and its attribution limitation', () => {
    const version = cargoLock.match(
      /\[\[package\]\]\s+name = "rustls-webpki"\s+version = "([^"]+)"/,
    )?.[1]
    expect(version).toBeDefined()
    const section = notices.split(`## rustls-webpki ${version}\n`)[1]?.split('\n## ')[0]
    expect(section).toContain('Copyright 2015 Brian Smith.')
    expect(section).toContain('copyright notice and this permission notice appear in all copies')
    expect(section).toContain('third-party/chromium/LICENSE')
    expect(section).toContain('absent from the cached crate')
    expect(section).toContain('alg_tests.rs')
    expect(section).toContain('cfg(test)')
    expect(section).toContain('does not establish complete embedded-source attribution')
  })

  it('identifies the locked Rustls and SQLite binding versions with their own notices', () => {
    for (const name of ['rustls', 'libsqlite3-sys']) {
      const version = cargoLock.match(
        new RegExp(`\\[\\[package\\]\\]\\s+name = "${name}"\\s+version = "([^"]+)"`),
      )?.[1]
      expect(version, name).toBeDefined()
      expect(notices).toContain(`## ${name} ${version}\n`)
    }
    const tlsSection = notices.split('## rustls 0.23.46\n')[1]?.split('\n## ')[0]
    expect(tlsSection).toContain('Copyright (c) 2016, Joseph Birr-Pixton')
    expect(tlsSection).toContain('ISC License (ISC)')
    expect(tlsSection).toContain('ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE')
    const bindingSection = notices.split('## libsqlite3-sys 0.39.1\n')[1]?.split('\n## ')[0]
    expect(bindingSection).toContain('Copyright (c) 2014 The rusqlite developers')
    expect(bindingSection).toContain('THE SOFTWARE IS PROVIDED "AS IS"')
  })

  it('distinguishes the statically bundled SQLite source from its MIT Rust binding', () => {
    const section = notices.split('## SQLite 3.54.1\n')[1]?.split('\n## ')[0]
    expect(section).toContain('555e07ef33f69a0957d24d8e555b702464a1')
    expect(section).toContain('The author disclaims copyright to this source code.')
    expect(section).toContain('May you do good and not evil.')
    expect(section).toContain('May you share freely, never taking more than you give.')
  })
})

describe('CSV and normalization distribution notices', () => {
  it('keeps independent tinyvec and macro notices for the locked versions', () => {
    for (const name of ['tinyvec', 'tinyvec_macros']) {
      const version = cargoLock.match(
        new RegExp(`\\[\\[package\\]\\]\\s+name = "${name}"\\s+version = "([^"]+)"`),
      )?.[1]
      expect(version, name).toBeDefined()
      expect(notices).toContain(`## ${name} ${version}\n`)
    }
    const macroSection = notices.split('## tinyvec_macros 0.1.1\n')[1]?.split('\n## ')[0]
    expect(macroSection).toContain('Copyright (c) 2020 Soveu')
    expect(macroSection).toContain('THE SOFTWARE IS PROVIDED "AS IS"')
  })

  it('identifies the locked CSV and normalization versions with their distinct copyrights', () => {
    for (const name of ['csv', 'csv-core', 'unicode-normalization']) {
      const version = cargoLock.match(
        new RegExp(`\\[\\[package\\]\\]\\s+name = "${name}"\\s+version = "([^"]+)"`),
      )?.[1]
      expect(version, name).toBeDefined()
      expect(notices).toContain(`${name} ${version}`)
    }
    expect(notices).toContain('Copyright (c) 2015 Andrew Gallant')
    expect(notices).toContain('Copyright (c) 2015 The Rust Project Developers')
  })
})

describe('frontend Tauri distribution notices', () => {
  it('keeps the API SPDX copyright separate from the plugin copyright', () => {
    const section = notices.split('## @tauri-apps/api ')[1]?.split('\n## ')[0] ?? ''
    expect(section).toContain(
      'PackageCopyrightText: 2019-2025, The Tauri Programme in the Commons Conservancy',
    )
    expect(section).toContain(
      'PackageCopyrightText: 2019-2022, The Tauri Programme in the Commons Conservancy',
    )
  })
  it('lists every locked frontend plugin in the frontend notice rather than the native crate list', () => {
    const section = notices.split('## @tauri-apps/api ')[1]?.split('\n## ')[0] ?? ''
    const plugins = Object.entries(frontendLock.packages).filter(([name]) =>
      name.startsWith('node_modules/@tauri-apps/plugin-'),
    )
    expect(plugins.length).toBeGreaterThan(0)
    for (const [path, metadata] of plugins) {
      const identifier = `${path.replace('node_modules/', '')} ${metadata.version}`
      expect(section, identifier).toContain(`- ${identifier}\n`)
    }
    expect(section).toContain('PackageCopyrightText: 2019-2022')
    expect(section).toContain('Copyright (c) 2017 - Present Tauri Apps Contributors')
    expect(section).toContain('The above copyright notice and this permission notice')
  })
})

describe('Prompt font distribution provenance', () => {
  it('retains the four-font inventory and independently recorded source', () => {
    expect(fontProvenance.sourceRepository).toBe('https://github.com/google/fonts')
    expect(fontProvenance.directory).toBe('ofl/prompt')
    expect(fontProvenance.modified).toBe(false)
    expect(Object.keys(fontProvenance.files).sort()).toEqual([
      'Prompt-Bold.ttf',
      'Prompt-Medium.ttf',
      'Prompt-Regular.ttf',
      'Prompt-SemiBold.ttf',
    ])
    expect(notices).toContain(`commit \`${fontProvenance.commit}\``)
  })

  it('retains the complete local license and its desktop resource mapping', () => {
    expect(fontProvenance.license).toBe('SIL-OFL-1.1')
    expect(fontLicense).toContain('Copyright (c) 2015, Cadson Demak')
    expect(fontLicense).toContain('SIL OPEN FONT LICENSE Version 1.1')
    expect(fontLicense).toContain('TERMINATION')
    expect(fontLicense).toContain('DISCLAIMER')
    expect(fontLicense).toContain('THE FONT SOFTWARE IS PROVIDED "AS IS"')
    expect(tauriConfig.bundle.resources['../src/assets/fonts/prompt/OFL.txt']).toBe(
      'Prompt-OFL.txt',
    )
    expect(notices).toContain('`Prompt-OFL.txt`')
  })
})

describe('reqwest distribution notices', () => {
  it('includes a distinct notice for every locked reqwest version', () => {
    const versions = Array.from(
      cargoLock.matchAll(/\[\[package\]\]\s+name = "reqwest"\s+version = "([^"]+)"/g),
      (match) => match[1],
    )
    expect(versions.length).toBeGreaterThan(0)
    for (const version of versions) {
      expect(notices).toContain(`## reqwest ${version}\n`)
    }
  })

  it('retains each locked version copyright and bundles the notice file', () => {
    const olderSection = notices.split('## reqwest 0.12.28\n')[1]?.split('\n## ')[0]
    const newerSection = notices.split('## reqwest 0.13.4\n')[1]?.split('\n## ')[0]
    expect(olderSection).toContain('Copyright (c) 2016-2025 Sean McArthur')
    expect(newerSection).toContain('Copyright (c) 2016-2026 Sean McArthur')
    for (const section of [olderSection, newerSection]) {
      expect(section).toContain('The above copyright notice and this permission notice')
      expect(section).toContain('THE SOFTWARE IS PROVIDED "AS IS"')
    }
    expect(tauriConfig.bundle.resources['../THIRD_PARTY_NOTICES.md']).toBe('THIRD_PARTY_NOTICES.md')
  })
})

describe('native Tauri distribution notices', () => {
  it('identifies every locked Tauri crate without treating frontend package versions as native evidence', () => {
    const section = notices.split('## Native Tauri crates\n')[1]?.split('\n## ')[0] ?? ''
    const packages = Array.from(
      cargoLock.matchAll(/\[\[package\]\]\s+name = "(tauri[^"]*)"\s+version = "([^"]+)"/g),
      (match) => `${match[1]} ${match[2]}`,
    )
    expect(packages.length).toBeGreaterThan(0)
    for (const identifier of packages) {
      expect(section).toContain(`- ${identifier}\n`)
    }
    expect(section).toContain('9319e539f34237035cb4693bed9246660bc0a926')
    expect(section).toContain('crates/tauri-plugin/Cargo.toml')
    expect(section).not.toContain('this entry is not a completed notice')
  })

  it('preserves the distinct single-instance and Windows resource copyrights', () => {
    expect(notices).toContain(
      'Copyright (c) 2017 - Present The Tauri Programme in the Commons Conservancy',
    )
    const windowsSection = notices.split('## tauri-winres 0.3.6\n')[1]?.split('\n## ')[0] ?? ''
    expect(windowsSection).toContain('Copyright (c) 2023 - Present Tauri Apps Contributors')
    expect(windowsSection).toContain('Copyright (c) 2016 Max Resch')
    expect(windowsSection).toContain('THE SOFTWARE IS PROVIDED "AS IS"')
  })
})
