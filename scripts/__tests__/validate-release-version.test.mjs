import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { validateReleaseVersion } from '../validate-release-version.mjs'

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'opentypeless-version-'))
  mkdirSync(join(root, 'src-tauri'))
  writeFileSync(join(root, 'package.json'), JSON.stringify({ version: '1.1.64' }))
  writeFileSync(join(root, 'package-lock.json'), JSON.stringify({
    version: '1.1.64', packages: { '': { version: '1.1.64' } },
  }))
  writeFileSync(join(root, 'src-tauri/Cargo.lock'), 'version = 4\n\n[[package]]\nname = "opentypeless"\nversion = "1.1.64"\n')
  writeFileSync(join(root, 'src-tauri/tauri.conf.json'), JSON.stringify({ version: '1.1.64' }))
  writeFileSync(join(root, 'src-tauri/Cargo.toml'), '[package]\nname = "opentypeless"\nversion = "1.1.64"\n\n[dependencies]\n')
  return root
}

test('accepts matching release versions and one optional v prefix', () => {
  const root = fixture()
  try {
    assert.equal(validateReleaseVersion(root, 'v1.1.64'), '1.1.64')
    assert.equal(validateReleaseVersion(root, '1.1.64'), '1.1.64')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('rejects mismatched npm lock root and package versions', () => {
  for (const [version, packageVersion] of [
    ['1.1.65', '1.1.64'], ['1.1.64', '1.1.65'],
  ]) {
    const root = fixture()
    try {
      writeFileSync(join(root, 'package-lock.json'), JSON.stringify({
        version, packages: { '': { version: packageVersion } },
      }))
      assert.throws(() => validateReleaseVersion(root, 'v1.1.64'))
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  }
})

test('rejects missing, duplicate, or mismatched application Cargo lock entries', () => {
  for (const lock of [
    'version = 4\n',
    '[[package]]\nname = "opentypeless"\nversion = "1.1.65"\n',
    '[[package]]\nname = "opentypeless"\nversion = "1.1.64"\n'.repeat(2),
  ]) {
    const root = fixture()
    try {
      writeFileSync(join(root, 'src-tauri/Cargo.lock'), lock)
      assert.throws(() => validateReleaseVersion(root, 'v1.1.64'))
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  }
})

test('rejects malformed or mismatched tags and app configuration', () => {
  const root = fixture()
  try {
    for (const tag of ['vv1.1.64', 'v01.1.64', 'v1.1.65', '', ' v1.1.64', 'v1.1.64\n']) {
      assert.throws(() => validateReleaseVersion(root, tag))
    }
    writeFileSync(join(root, 'src-tauri/tauri.conf.json'), JSON.stringify({ version: '1.1.65' }))
    assert.throws(() => validateReleaseVersion(root, 'v1.1.64'))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('ignores unrelated dependency versions in the Cargo lock', () => {
  const root = fixture()
  try {
    writeFileSync(join(root, 'src-tauri/Cargo.lock'),
      'version = 4\n\n[[package]]\nname = "dependency"\nversion = "9.0.0"\n\n' +
      '[[package]]\nname = "opentypeless"\nversion = "1.1.64"\n')
    assert.equal(validateReleaseVersion(root, 'v1.1.64'), '1.1.64')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('the CLI emits no release version when either lockfile is inconsistent', () => {
  for (const file of ['package-lock.json', 'src-tauri/Cargo.lock']) {
    const root = fixture()
    try {
      writeFileSync(join(root, file), file.endsWith('.json')
        ? JSON.stringify({ version: '1.1.65', packages: { '': { version: '1.1.64' } } })
        : '[[package]]\nname = "opentypeless"\nversion = "1.1.65"\n')
      const result = spawnSync(process.execPath, [
        fileURLToPath(new URL('../validate-release-version.mjs', import.meta.url)),
        'v1.1.64',
      ], { cwd: root, encoding: 'utf8', timeout: 5000 })
      assert.equal(result.status, 1)
      assert.equal(result.stdout, '')
      assert.match(result.stderr, /Release version preflight failed/)
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  }
})

test('rejects a Cargo mismatch and exits before emitting a release version', () => {
  const root = fixture()
  try {
    writeFileSync(join(root, 'src-tauri/Cargo.toml'), '[package]\nversion = "1.1.65"\n')
    assert.throws(() => validateReleaseVersion(root, 'v1.1.64'))
    const result = spawnSync(process.execPath, [
      fileURLToPath(new URL('../validate-release-version.mjs', import.meta.url)),
      'v1.1.64',
    ], { cwd: root, encoding: 'utf8', timeout: 5000 })
    assert.equal(result.status, 1)
    assert.equal(result.stdout, '')
    assert.match(result.stderr, /Release version preflight failed/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
