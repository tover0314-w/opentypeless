import { describe, expect, it } from 'vitest'
import cargoLock from '../../../src-tauri/Cargo.lock?raw'
import tauriConfig from '../../../src-tauri/tauri.conf.json'
import thirdPartyNotices from '../../../THIRD_PARTY_NOTICES.md?raw'
import reactLicense from '../../../node_modules/react/LICENSE?raw'
import reactDomLicense from '../../../node_modules/react-dom/LICENSE?raw'
import lucideLicense from '../../../node_modules/lucide-react/LICENSE?raw'
import zustandLicense from '../../../node_modules/zustand/LICENSE?raw'
import i18nextLicense from '../../../node_modules/i18next/LICENSE?raw'
import reactI18nextLicense from '../../../node_modules/react-i18next/LICENSE?raw'
import framerMotionLicense from '../../../node_modules/framer-motion/LICENSE.md?raw'
import motionDomLicense from '../../../node_modules/motion-dom/LICENSE.md?raw'
import motionUtilsLicense from '../../../node_modules/motion-utils/LICENSE.md?raw'
import betterAuthLicense from '../../../node_modules/better-auth/LICENSE.md?raw'
import betterAuthCoreLicense from '../../../node_modules/@better-auth/core/LICENSE.md?raw'
import betterFetchLicense from '../../../node_modules/@better-fetch/fetch/LICENSE?raw'
import betterCallLicense from '../../../node_modules/better-call/LICENSE?raw'
import defuLicense from '../../../node_modules/defu/LICENSE?raw'
import nanostoresLicense from '../../../node_modules/nanostores/LICENSE?raw'
import schedulerLicense from '../../../node_modules/scheduler/LICENSE?raw'
import tauriApiLicense from '../../../node_modules/@tauri-apps/api/LICENSE_MIT?raw'
import dependencyLock from '../../../package-lock.json'

const installedPackageSources = import.meta.glob<string>(
  [
    '../../../node_modules/react/package.json',
    '../../../node_modules/react-dom/package.json',
    '../../../node_modules/lucide-react/package.json',
    '../../../node_modules/zustand/package.json',
    '../../../node_modules/i18next/package.json',
    '../../../node_modules/react-i18next/package.json',
    '../../../node_modules/framer-motion/package.json',
    '../../../node_modules/motion-dom/package.json',
    '../../../node_modules/motion-utils/package.json',
    '../../../node_modules/better-auth/package.json',
    '../../../node_modules/@better-auth/core/package.json',
    '../../../node_modules/@better-fetch/fetch/package.json',
    '../../../node_modules/better-call/package.json',
    '../../../node_modules/defu/package.json',
    '../../../node_modules/nanostores/package.json',
    '../../../node_modules/scheduler/package.json',
    '../../../node_modules/@tauri-apps/api/package.json',
    '../../../node_modules/@tauri-apps/plugin-clipboard-manager/package.json',
    '../../../node_modules/@tauri-apps/plugin-deep-link/package.json',
    '../../../node_modules/@tauri-apps/plugin-global-shortcut/package.json',
    '../../../node_modules/@tauri-apps/plugin-opener/package.json',
    '../../../node_modules/@tauri-apps/plugin-process/package.json',
    '../../../node_modules/@tauri-apps/plugin-store/package.json',
    '../../../node_modules/@tauri-apps/plugin-updater/package.json',
  ],
  { eager: true, query: '?raw', import: 'default' },
)

function hasComponentNotice(notices: string, component: string, license: string): boolean {
  const escapedComponent = component.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const componentPattern = new RegExp(`(?:^|\\s)${escapedComponent}(?=\\s|,|$)`, 'm')
  return notices
    .split(/^## /m)
    .slice(1)
    .some((section) => componentPattern.test(section) && section.includes(license.trim()))
}

describe('third-party notices', () => {
  it('preserves both required unicode-ident license texts', async () => {
    const section = thirdPartyNotices
      .split(/^## /m)
      .find((text) => text.startsWith('unicode-ident 1.0.24\n'))
    expect(section).toBeDefined()
    expect(cargoLock).toContain('name = "unicode-ident"\nversion = "1.0.24"')
    const mitStart = section!.indexOf('Permission is hereby granted')
    const unicodeStart = section!.indexOf('UNICODE LICENSE V3')
    expect(mitStart).toBeGreaterThanOrEqual(0)
    expect(unicodeStart).toBeGreaterThan(mitStart)
    for (const [text, expected] of [
      [
        section!.slice(mitStart, unicodeStart).trim(),
        '23f18e03dc49df91622fe2a76176497404e46ced8a715d9d2b67a7446571cca3',
      ],
      [
        section!.slice(unicodeStart).trim(),
        'f7db81051789b729fea528a63ec4c938fdcb93d9d61d97dc8cc2e9df6d47f2a1',
      ],
    ]) {
      const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${text}\n`))
      const fingerprint = Array.from(new Uint8Array(digest), (byte) =>
        byte.toString(16).padStart(2, '0'),
      ).join('')
      expect(fingerprint).toBe(expected)
    }
  })

  it('preserves the inspected Unicode license and its locked component versions', async () => {
    const section = thirdPartyNotices
      .split(/^## /m)
      .find((text) => text.startsWith('ICU4X and Unicode support crates\n'))
    expect(section).toBeDefined()
    const license = section!.slice(section!.indexOf('UNICODE LICENSE V3')).trim()
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`${license}\n`))
    const fingerprint = Array.from(new Uint8Array(digest), (byte) =>
      byte.toString(16).padStart(2, '0'),
    ).join('')
    expect(fingerprint).toBe('f367c1b8e1aa262435251e442901da4607b4650e0e63a026f5044473ecfb90f2')
    const components = [
      ['icu_collections', '2.2.0'],
      ['icu_locale_core', '2.2.0'],
      ['icu_normalizer', '2.2.0'],
      ['icu_normalizer_data', '2.2.0'],
      ['icu_properties', '2.2.0'],
      ['icu_properties_data', '2.2.0'],
      ['icu_provider', '2.2.0'],
      ['litemap', '0.8.2'],
      ['potential_utf', '0.1.5'],
      ['tinystr', '0.8.3'],
      ['writeable', '0.6.3'],
      ['yoke', '0.8.3'],
      ['yoke-derive', '0.8.2'],
      ['zerofrom', '0.1.8'],
      ['zerofrom-derive', '0.1.7'],
      ['zerotrie', '0.2.4'],
      ['zerovec', '0.11.6'],
      ['zerovec-derive', '0.11.3'],
    ]
    for (const [name, version] of components) {
      expect(cargoLock).toContain(`name = "${name}"\nversion = "${version}"`)
      expect(hasComponentNotice(thirdPartyNotices, `${name} ${version}`, license)).toBe(true)
    }
  })

  it('rejects a component whose license only appears in another notice section', () => {
    const notices = '## alpha 1.0.0\n\nLicense Alpha\n\n## beta 2.0.0\n\nLicense Beta\n'
    expect(hasComponentNotice(notices, 'alpha 1.0.0', 'License Beta')).toBe(false)
  })

  it('rejects a component version that only matches a longer version', () => {
    expect(
      hasComponentNotice('## alpha 1.0.01\n\nLicense Alpha\n', 'alpha 1.0.0', 'License Alpha'),
    ).toBe(false)
  })

  it('accepts components sharing an explicitly named notice section', () => {
    const notices = '## alpha 1.0.0 and beta 2.0.0\n\nShared License\n'
    expect(hasComponentNotice(notices, 'beta 2.0.0', 'Shared License')).toBe(true)
  })

  it.each([
    ['react', reactLicense],
    ['react-dom', reactDomLicense],
    ['lucide-react', lucideLicense],
    ['zustand', zustandLicense],
    ['i18next', i18nextLicense],
    ['react-i18next', reactI18nextLicense],
    ['framer-motion', framerMotionLicense],
    ['motion-dom', motionDomLicense],
    ['motion-utils', motionUtilsLicense],
    ['better-auth', betterAuthLicense],
    ['@better-auth/core', betterAuthCoreLicense],
    ['@better-fetch/fetch', betterFetchLicense],
    ['better-call', betterCallLicense],
    ['defu', defuLicense],
    ['nanostores', nanostoresLicense],
    ['scheduler', schedulerLicense],
    ['@tauri-apps/api', tauriApiLicense],
    ['@tauri-apps/plugin-clipboard-manager', tauriApiLicense],
    ['@tauri-apps/plugin-deep-link', tauriApiLicense],
    ['@tauri-apps/plugin-global-shortcut', tauriApiLicense],
    ['@tauri-apps/plugin-opener', tauriApiLicense],
    ['@tauri-apps/plugin-process', tauriApiLicense],
    ['@tauri-apps/plugin-store', tauriApiLicense],
    ['@tauri-apps/plugin-updater', tauriApiLicense],
  ])('includes the installed %s license verbatim with its locked version', (name, license) => {
    const packages = dependencyLock.packages as Record<string, { version?: string }>
    const version = packages[`node_modules/${name}`]?.version
    expect(version).toBeTruthy()
    const installedSource = installedPackageSources[`../../../node_modules/${name}/package.json`]
    expect(installedSource).toBeDefined()
    const installed: { name?: string; version?: string } = JSON.parse(installedSource)
    expect(installed.name).toBe(name)
    expect(installed.version).toBe(version)
    expect(hasComponentNotice(thirdPartyNotices, `${name} ${version}`, license)).toBe(true)
  })

  it.each([
    ['reqwest', '0.13.4', 'Copyright (c) 2016-2026 Sean McArthur'],
    ['rusqlite', '0.31.0', 'Copyright (c) 2014-2021 The rusqlite developers'],
    ['cpal', '0.18.2', 'END OF TERMS AND CONDITIONS'],
    ['ring', '0.17.14', 'Copyright 2015-2025 Brian Smith.'],
  ])('includes a locked %s component notice with its copyright', (name, version, copyright) => {
    expect(cargoLock).toContain(`name = "${name}"\nversion = "${version}"`)
    expect(hasComponentNotice(thirdPartyNotices, `${name} ${version}`, copyright)).toBe(true)
  })

  it('includes the embedded fiat-crypto attribution without inventing a package version', () => {
    expect(
      hasComponentNotice(
        thirdPartyNotices,
        'fiat-crypto embedded in ring 0.17.14',
        'Copyright 2015-2020 the fiat-crypto authors (see the AUTHORS file)',
      ),
    ).toBe(true)
  })

  it.each([
    [
      'reqwest 0.13.4',
      'Copyright (c) 2016-2026 Sean McArthur',
      null,
      'bde59a7e336fcb583f03ede4e7ae63fed99f1148dcee7a7717904d76f116cb84',
    ],
    [
      'rusqlite 0.31.0',
      'Copyright (c) 2014-2021 The rusqlite developers',
      null,
      '1b22c746764567f538d003a5ed8abf27e3957939727be7e896f928a37724c40f',
    ],
    [
      'cpal 0.18.2',
      'Apache License\n',
      null,
      '283ea6cc2997a1a70da0049e09adf9317bb60ca1b51279b65196b83a69e1996b',
    ],
    [
      'ring 0.17.14',
      'Copyright 2015-2025 Brian Smith.',
      "The locked crate's once_cell LICENSE-MIT",
      '07414b6c33f30acfd5e0dda512e77ddbeb9f8a37290568fd056a01a0b50244c1',
    ],
    [
      'ring 0.17.14',
      'Permission is hereby granted',
      'The following copyright declarations',
      '740a360e4d9c99a258a4a870097a4641af896babd62ca7ab2a2c6de4d5ada151',
    ],
    [
      'fiat-crypto embedded in ring 0.17.14',
      'The Apache License, Version 2.0 (Apache-2.0)',
      'The referenced AUTHORS file',
      'f1954d2b51ee6ac825defdc6ee947930241269d40ad6082b9d9e758613ce7b61',
    ],
  ])(
    'preserves the inspected full license text in %s from %s',
    async (component, start, end, expected) => {
      const section = thirdPartyNotices
        .split(/^## /m)
        .find((text) => text.startsWith(`${component}\n`))
      expect(section).toBeDefined()
      const startOffset = section!.indexOf(start!)
      expect(startOffset).toBeGreaterThanOrEqual(0)
      const endOffset = end ? section!.indexOf(end, startOffset) : section!.length
      expect(endOffset).toBeGreaterThan(startOffset)
      const text = section!.slice(startOffset, endOffset).trim()
      const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
      const fingerprint = Array.from(new Uint8Array(digest), (byte) =>
        byte.toString(16).padStart(2, '0'),
      ).join('')
      expect(fingerprint).toBe(expected)
    },
  )

  it('includes existing audio dependency notices in distributed bundle resources', () => {
    const bundle = tauriConfig.bundle as { resources?: Record<string, string> }
    expect(bundle.resources?.['../THIRD_PARTY_NOTICES.md']).toBe('THIRD_PARTY_NOTICES.md')
    expect(bundle.resources?.['../LICENSE']).toBe('LICENSE')
    expect(thirdPartyNotices).toContain('opusic-c 1.6.1')
    expect(thirdPartyNotices).toContain('opusic-sys 0.7.3')
    expect(thirdPartyNotices).toContain('ogg 0.9.2')
    for (const reference of ['1524', '1914', '1526']) {
      expect(thirdPartyNotices).toContain(`https://datatracker.ietf.org/ipr/${reference}/`)
    }
  })
})
