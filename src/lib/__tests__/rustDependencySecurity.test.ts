import { describe, expect, it } from 'vitest'
import cargoLock from '../../../src-tauri/Cargo.lock?raw'
import cargoManifest from '../../../src-tauri/Cargo.toml?raw'
import packageManifest from '../../../package.json'

function lockedVersions(packageName: string): string[] {
  return cargoLock
    .split('[[package]]')
    .filter((entry) => entry.includes(`name = "${packageName}"`))
    .map((entry) => entry.match(/^version = "([^"]+)"/m)?.[1] ?? '')
}

describe('Rust dependency security floor', () => {
  it('uses the patched bundled SQLite dependency', () => {
    const versions = lockedVersions('libsqlite3-sys')
    expect(versions).not.toHaveLength(0)
    for (const version of versions) {
      const [major, minor] = version.split('.').map(Number)
      expect(major > 0 || minor >= 39).toBe(true)
    }
  })

  it('uses patched ZIP extraction dependencies without removing the updater', () => {
    const versions = lockedVersions('zip')
    expect(versions).not.toHaveLength(0)
    for (const version of versions) {
      expect(Number(version.split('.')[0])).toBeGreaterThanOrEqual(9)
    }
    expect(lockedVersions('tauri-plugin-updater')).toEqual(['2.14.1'])
  })

  it('declares the Rust floor required by the patched desktop runtime', () => {
    expect(cargoManifest).toMatch(/^rust-version = "1\.90"$/m)
  })

  it('keeps frontend API and updater versions aligned with the Rust packages', () => {
    expect(lockedVersions('tauri')).toEqual([packageManifest.dependencies['@tauri-apps/api']])
    expect(lockedVersions('tauri-plugin-updater')).toEqual([
      packageManifest.dependencies['@tauri-apps/plugin-updater'],
    ])
    expect(packageManifest.devDependencies['@tauri-apps/cli']).toBe('2.12.1')
  })
})
