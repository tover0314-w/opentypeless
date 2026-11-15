import { describe, expect, it } from 'vitest'
import { createInstance } from 'i18next'
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

const locales = { de, en, es, fr, it: itLocale, ja, ko, pt, ru, zh }
const accountMessageKeys = [
  'networkError',
  'verifyEmailDesc',
  'license',
  'lifetime',
  'resets',
  'cloudWordsThisMonth',
  'cloudWords',
  'quotaKWords',
  'byokUnlimited',
  'cloudQuotaWarning',
]

function leafKeys(value: unknown, prefix = ''): string[] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return prefix ? [prefix] : []
  }

  return Object.entries(value as Record<string, unknown>).flatMap(([key, child]) =>
    leafKeys(child, prefix ? `${prefix}.${key}` : key),
  )
}

describe('locale message coverage', () => {
  it('localizes the approved upgrade allowance and preview boundary in every language', () => {
    expect(zh.upgrade.monthlyAllowance).toBe('每月 10 万字')
    expect(en.upgrade.monthlyAllowance).toBe('100,000 characters/month')
    for (const [locale, messages] of Object.entries(locales)) {
      for (const key of [
        'serviceSubtitle',
        'monthlyAllowance',
        'serviceBenefit',
        'allowancePending',
      ] as const) {
        expect(messages.upgrade[key].trim(), `${locale}: upgrade.${key}`).not.toBe('')
      }
      expect(messages.upgrade.planDescriptions.pro, locale).not.toMatch(/cloud words/i)
      expect(messages.upgrade.planDescriptions.lifetime, locale).not.toMatch(/cloud words/i)
    }
  })

  it('uses user-facing home service titles instead of backend acronyms', () => {
    expect(zh.home.sttProvider).toBe('语音转文字')
    expect(zh.home.llmProvider).toBe('文字润色')
    for (const [locale, messages] of Object.entries(locales)) {
      for (const key of ['sttProvider', 'llmProvider'] as const) {
        expect(messages.home[key], `${locale}: home.${key}`).not.toMatch(/\b(STT|LLM)\b/i)
      }
    }
  })

  it('includes existing account messages instead of relying on English defaults', () => {
    for (const [locale, messages] of Object.entries(locales)) {
      const account = messages.account as Record<string, unknown>
      for (const key of accountMessageKeys) {
        expect(account[key], `${locale}: account.${key}`).toEqual(expect.any(String))
        expect((account[key] as string).trim(), `${locale}: account.${key}`).not.toBe('')
      }
    }
  })

  it('resolves account messages without fallback or an English default', async () => {
    const instance = createInstance()
    await instance.init({
      resources: Object.fromEntries(
        Object.entries(locales).map(([locale, messages]) => [locale, { translation: messages }]),
      ),
      lng: 'en',
      fallbackLng: false,
      interpolation: { escapeValue: false },
    })

    for (const [locale, messages] of Object.entries(locales)) {
      await instance.changeLanguage(locale)
      const account = messages.account as Record<string, unknown>
      for (const key of accountMessageKeys) {
        expect(
          instance.t(`account.${key}`, { defaultValue: 'UNEXPECTED_FALLBACK' }),
          `${locale}: account.${key}`,
        ).toBe(account[key])
      }
    }
  })

  it('keeps every locale aligned with English leaf keys', () => {
    const expectedKeys = leafKeys(en).sort()

    for (const [locale, messages] of Object.entries(locales)) {
      expect(leafKeys(messages).sort(), locale).toEqual(expectedKeys)
    }
  })

  it('preserves interpolation variables in every locale', () => {
    const variables = (message: string) =>
      [
        ...new Set(
          [...message.matchAll(/\{\{\s*-?\s*([^},]+)(?:,[^}]*)?\}\}/g)].map((match) =>
            match[1].trim(),
          ),
        ),
      ].sort()
    const readMessage = (messages: unknown, key: string): string =>
      key.split('.').reduce<unknown>((value, part) => {
        return (value as Record<string, unknown>)[part]
      }, messages) as string

    for (const key of leafKeys(en)) {
      const expected = variables(readMessage(en, key))
      for (const [locale, messages] of Object.entries(locales)) {
        expect(variables(readMessage(messages, key)), `${locale}: ${key}`).toEqual(expected)
      }
    }
  })
})
