import { describe, expect, it } from 'vitest'
import { isAskWebSearchCapability, isAskWebQuoteFresh } from '../askWebSearchCapability'

const capability = {
  version: 1,
  outputTokenLimit: 1024,
  maxResults: 3,
  additionalCloudWords: 25,
  additionalLlmTokens: 100,
  selectedTextAllowed: false,
}

describe('Ask web search capability', () => {
  it('accepts only recent non-future verification times', () => {
    const now = Date.parse('2026-11-19T00:05:00Z')
    expect(isAskWebQuoteFresh('2026-11-19T00:04:00Z', now)).toBe(true)
    expect(isAskWebQuoteFresh('2026-11-19T00:00:00Z', now)).toBe(false)
    expect(isAskWebQuoteFresh('2026-11-19T00:06:00Z', now)).toBe(false)
    expect(isAskWebQuoteFresh(null, now)).toBe(false)
    expect(isAskWebQuoteFresh('invalid', now)).toBe(false)
  })
  it('accepts the bounded server contract without treating it as account entitlement', () => {
    expect(isAskWebSearchCapability(capability)).toBe(true)
  })

  it.each([
    null,
    undefined,
    {},
    { ...capability, version: 2 },
    { ...capability, outputTokenLimit: 0 },
    { ...capability, outputTokenLimit: 10001 },
    { ...capability, maxResults: 21 },
    { ...capability, additionalCloudWords: 0 },
    { ...capability, additionalLlmTokens: 1.5 },
    { ...capability, additionalCloudWords: 100001 },
    { ...capability, selectedTextAllowed: true },
  ])('rejects malformed or unsupported capabilities: %j', (value) => {
    expect(isAskWebSearchCapability(value)).toBe(false)
  })
})
