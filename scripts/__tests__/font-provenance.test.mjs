import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'

test('every bundled Prompt font matches its recorded source digest', () => {
  const root = new URL('../../src/assets/fonts/prompt/', import.meta.url)
  const provenance = JSON.parse(readFileSync(new URL('provenance.json', root), 'utf8'))
  assert.deepEqual(Object.keys(provenance.files).sort(), [
    'Prompt-Bold.ttf',
    'Prompt-Medium.ttf',
    'Prompt-Regular.ttf',
    'Prompt-SemiBold.ttf',
  ])
  for (const [file, expected] of Object.entries(provenance.files)) {
    const bytes = readFileSync(new URL(file, root))
    assert.equal(createHash('sha256').update(bytes).digest('hex'), expected, file)
  }
})
