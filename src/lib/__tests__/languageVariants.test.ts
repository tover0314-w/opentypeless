import { describe, expect, it } from 'vitest'
import { normalizeLanguageVariants } from '../languageVariants'

describe('normalizeLanguageVariants', () => {
  it('preserves recognized regional variants only under the correct language', () => {
    expect(
      normalizeLanguageVariants({ en: 'en-GB', zh: 'en-US', de: 'de-DE', pt: 'pt-BR' }),
    ).toEqual({
      en: 'en-GB',
      pt: 'pt-BR',
    })
  })

  it('rejects malformed values without inventing preferences', () => {
    expect(normalizeLanguageVariants(null)).toEqual({})
    expect(normalizeLanguageVariants(['en-GB'])).toEqual({})
    expect(normalizeLanguageVariants({ en: '<instructions>' })).toEqual({})
  })
})
