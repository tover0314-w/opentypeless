import { describe, expect, it } from 'vitest'
import { estimateLegacyWords } from '../legacy-word-estimate'

const free = {
  sttSecondsUsed: 0,
  sttSecondsLimit: 900,
  llmTokensUsed: 0,
  llmTokensLimit: 100_000,
}

describe('legacy word display estimate', () => {
  it('accepts a full account snapshot without validating unrelated properties as quota numbers', () => {
    const snapshot = { ...free, plan: 'free', user: { id: 'example' }, loading: false }
    expect(estimateLegacyWords(snapshot)).toEqual({ limit: 2000, remaining: 2000, used: 0 })
  })

  it('never increases remaining words as either usage meter increases', () => {
    for (const meter of ['sttSecondsUsed', 'llmTokensUsed'] as const) {
      let previousRemaining = 2000
      const maximum = meter === 'sttSecondsUsed' ? free.sttSecondsLimit : free.llmTokensLimit
      for (let step = 0; step <= 100; step += 1) {
        const estimate = estimateLegacyWords({ ...free, [meter]: (maximum * step) / 100 })!
        expect(estimate.remaining).toBeLessThanOrEqual(previousRemaining)
        expect(estimate.remaining).toBeGreaterThanOrEqual(0)
        expect(Number.isInteger(estimate.remaining)).toBe(true)
        expect(estimate.used + estimate.remaining).toBe(estimate.limit)
        previousRemaining = estimate.remaining
      }
      expect(previousRemaining).toBe(0)
    }
  })

  it('rounds fractional remaining allowances down instead of overstating availability', () => {
    expect(estimateLegacyWords({ ...free, llmTokensUsed: 1 })).toEqual({
      limit: 2000,
      remaining: 1999,
      used: 1,
    })
  })

  it('derives Free from the existing Pro reference without granting more quota', () => {
    expect(estimateLegacyWords(free)).toEqual({ limit: 2000, remaining: 2000, used: 0 })
  })

  it('uses the tighter remaining meter rather than adding the allowances', () => {
    expect(estimateLegacyWords({ ...free, sttSecondsUsed: 300, llmTokensUsed: 25_000 })).toEqual({
      limit: 2000,
      remaining: 1500,
      used: 500,
    })
    expect(estimateLegacyWords({ ...free, sttSecondsUsed: 720 })).toEqual({
      limit: 2000,
      remaining: 500,
      used: 1500,
    })
  })

  it.each([
    { sttSecondsUsed: 900 },
    { llmTokensUsed: 100_000 },
    { sttSecondsUsed: 1200 },
    { llmTokensUsed: 150_000 },
  ])('shows zero when either meter is exhausted: %j', (patch) => {
    expect(estimateLegacyWords({ ...free, ...patch })?.remaining).toBe(0)
  })

  it('retains the existing Pro baseline', () => {
    expect(
      estimateLegacyWords({
        sttSecondsUsed: 0,
        sttSecondsLimit: 36_000,
        llmTokensUsed: 0,
        llmTokensLimit: 5_000_000,
      }),
    ).toEqual({ limit: 100_000, remaining: 100_000, used: 0 })
  })

  it.each([
    { sttSecondsLimit: 0 },
    { llmTokensLimit: 0 },
    { sttSecondsUsed: -1 },
    { llmTokensUsed: Number.NaN },
    { sttSecondsLimit: Number.POSITIVE_INFINITY },
  ])('does not invent an estimate from invalid or missing meters: %j', (patch) => {
    expect(estimateLegacyWords({ ...free, ...patch })).toBeNull()
  })
})
