import { describe, expect, it } from 'vitest'
import { valuesEqual } from '../configValuesEqual'

describe('configuration value comparison', () => {
  it('compares nested configuration values independently of object key order', () => {
    expect(
      valuesEqual(
        { enabled: true, targets: ['en', 'ja'], scene: { id: 'notes', prompt: 'Keep wording' } },
        { scene: { prompt: 'Keep wording', id: 'notes' }, targets: ['en', 'ja'], enabled: true },
      ),
    ).toBe(true)
  })

  it.each([
    [{ enabled: true }, { enabled: false }],
    [{ field: undefined }, {}],
    [
      ['en', 'ja'],
      ['ja', 'en'],
    ],
    [null, {}],
    [[], {}],
    [0, false],
    [0, -0],
  ])('distinguishes different configuration values', (left, right) => {
    expect(valuesEqual(left, right)).toBe(false)
  })

  it('preserves Object.is equality for unchanged scalar values', () => {
    expect(valuesEqual(Number.NaN, Number.NaN)).toBe(true)
    expect(valuesEqual(undefined, undefined)).toBe(true)
  })
})
