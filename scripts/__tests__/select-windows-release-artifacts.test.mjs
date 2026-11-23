import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, symlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { selectWindowsReleaseArtifacts } from '../select-windows-release-artifacts.mjs'

function fixture() {
  const directory = mkdtempSync(join(tmpdir(), 'opentypeless-installers-'))
  writeFileSync(join(directory, 'OpenTypeless_1.1.64_x64_en-US.msi'), 'synthetic MSI')
  writeFileSync(join(directory, 'OpenTypeless_1.1.64_x64-setup.exe'), 'synthetic NSIS')
  return directory
}

test('selects the exact current version installer pair', () => {
  const directory = fixture()
  try {
    assert.deepEqual(selectWindowsReleaseArtifacts(directory, '1.1.64'), {
      msi: join(directory, 'OpenTypeless_1.1.64_x64_en-US.msi'),
      nsis: join(directory, 'OpenTypeless_1.1.64_x64-setup.exe'),
    })
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})

test('rejects old, ambiguous or symlinked installer candidates', () => {
  for (const scenario of ['old', 'duplicate', 'symlink']) {
    const directory = fixture()
    try {
      if (scenario === 'old') writeFileSync(join(directory, 'OpenTypeless_1.1.63_x64-setup.exe'), 'old')
      if (scenario === 'duplicate') {
        mkdirSync(join(directory, 'duplicate'))
        writeFileSync(join(directory, 'duplicate/OpenTypeless_1.1.64_x64_en-US.msi'), 'duplicate')
      }
      if (scenario === 'symlink') symlinkSync(directory, join(directory, 'linked'), 'dir')
      assert.throws(() => selectWindowsReleaseArtifacts(directory, '1.1.64'))
    } finally {
      rmSync(directory, { recursive: true, force: true })
    }
  }
})

test('rejects empty or missing installers and emits no paths on CLI failure', () => {
  const directory = fixture()
  try {
    writeFileSync(join(directory, 'OpenTypeless_1.1.64_x64-setup.exe'), '')
    assert.throws(() => selectWindowsReleaseArtifacts(directory, '1.1.64'))
    rmSync(join(directory, 'OpenTypeless_1.1.64_x64-setup.exe'))
    assert.throws(() => selectWindowsReleaseArtifacts(directory, '1.1.64'))
    const result = spawnSync(process.execPath, [
      fileURLToPath(new URL('../select-windows-release-artifacts.mjs', import.meta.url)),
      directory,
      '1.1.64',
    ], { encoding: 'utf8', timeout: 5000 })
    assert.equal(result.status, 1)
    assert.equal(result.stdout, '')
    assert.match(result.stderr, /Windows installer selection failed/)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})
