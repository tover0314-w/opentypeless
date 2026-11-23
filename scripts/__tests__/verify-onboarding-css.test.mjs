import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const script = fileURLToPath(new URL('../verify-onboarding-css.mjs', import.meta.url))
const fallback =
  '@supports not (container-type:inline-size){@media (min-width:988px){.onboarding-split-panel{width:55%}.onboarding-split-surface{display:flex}}}'

function verify(css, extraCss = '') {
  const directory = mkdtempSync(join(tmpdir(), 'opentypeless-css-'))
  try {
    mkdirSync(join(directory, 'assets'))
    writeFileSync(
      join(directory, 'index.html'),
      '<link rel="stylesheet" href="./assets/current.css">',
    )
    writeFileSync(join(directory, 'assets/current.css'), css)
    writeFileSync(join(directory, 'assets/stale.css'), extraCss)
    return spawnSync(process.execPath, [script, directory], { encoding: 'utf8' })
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
}

test('accepts the compatible fallback in the stylesheet actually referenced by HTML', () => {
  const result = verify(fallback)
  assert.equal(result.status, 0, result.stderr)
})

test('rejects a modern range-only fallback', () => {
  const result = verify(fallback.replace('(min-width:988px)', '(width>=988px)'))
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /fallback/i)
})

test('does not let an unreferenced old stylesheet hide a missing fallback', () => {
  const result = verify('.unrelated{display:block}', fallback)
  assert.notEqual(result.status, 0)
})

test('rejects a fallback with no working right surface display', () => {
  const result = verify(fallback.replace('display:flex', 'display:none'))
  assert.notEqual(result.status, 0)
})
