import { afterEach, describe, expect, it } from 'vitest'
import i18n from '../index'

const originalLanguage = i18n.language

afterEach(async () => {
  await i18n.changeLanguage(originalLanguage)
})

describe('document language accessibility', () => {
  it.each(['en', 'zh', 'ja', 'ko', 'fr', 'de', 'es', 'pt', 'ru', 'it'])(
    'updates the document language when switching to %s',
    async (language) => {
      document.documentElement.lang = 'invalid'
      await i18n.changeLanguage(language)
      expect(document.documentElement.lang).toBe(language)
    },
  )

  it('uses the resolved fallback instead of an unsupported language', async () => {
    document.documentElement.lang = 'invalid'
    await i18n.changeLanguage('unsupported')
    expect(document.documentElement.lang).toBe('en')
  })
})
