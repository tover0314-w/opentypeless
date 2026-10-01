import { describe, expect, it } from 'vitest'
import de from '../locales/de.json'
import en from '../locales/en.json'
import es from '../locales/es.json'
import fr from '../locales/fr.json'
import itLocale from '../locales/it.json'
import ja from '../locales/ja.json'
import ko from '../locales/ko.json'
import pt from '../locales/pt.json'
import ru from '../locales/ru.json'
import zh from '../locales/zh.json'
import zhTW from '../locales/zh-TW.json'

const locales = { de, en, es, fr, it: itLocale, ja, ko, pt, ru, zh, 'zh-TW': zhTW }

function leafKeys(value: unknown, prefix = ''): string[] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return prefix ? [prefix] : []
  }

  return Object.entries(value as Record<string, unknown>).flatMap(([key, child]) =>
    leafKeys(child, prefix ? `${prefix}.${key}` : key),
  )
}

describe('locale message coverage', () => {
  it('keeps every locale aligned with English leaf keys', () => {
    const expectedKeys = leafKeys(en).sort()

    for (const [locale, messages] of Object.entries(locales)) {
      expect(leafKeys(messages).sort(), locale).toEqual(expectedKeys)
    }
  })
})
