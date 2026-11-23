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
  it('describes unknown saved-answer opening without asserting a timeout cause', () => {
    expect(en.history.openAnswerUnknown).toContain('Could not confirm whether the answer opened')
    expect(zh.history.openAnswerUnknown).toContain('无法确认答案是否已打开')
    expect(zh.history.openAnswerUnknown).not.toContain('超时')
    for (const [locale, messages] of Object.entries(locales)) {
      expect(messages.history.openAnswerUnknown.trim(), locale).not.toBe('')
      expect(messages.history.openAnswerUnknown, locale).not.toBe(
        messages.history.failedToOpenAnswer,
      )
    }
  })

  it('provides manual recovery copy for failed and prevented delivery in every language', () => {
    for (const [locale, messages] of Object.entries(locales)) {
      expect(messages.ask.deliveryFailed.trim(), `${locale}: ask.deliveryFailed`).not.toBe('')
      expect(messages.ask.deliveryPrevented.trim(), `${locale}: ask.deliveryPrevented`).not.toBe('')
    }
    expect(en.ask.deliveryFailed).toBe('Not inserted or copied; copy the answer manually')
    expect(zh.ask.deliveryFailed).toBe('未插入或复制，请手动复制回答')
  })

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

  it('allows empty copy only for the reviewed Japanese and Korean keycap prefixes', () => {
    for (const [locale, messages] of Object.entries(locales)) {
      const emptyKeys = leafKeys(messages).filter((key) => {
        const value = key.split('.').reduce<unknown>((parent, part) => {
          return (parent as Record<string, unknown>)[part]
        }, messages)
        expect(value, `${locale}: ${key}`).toEqual(expect.any(String))
        return !(value as string).trim()
      })
      expect(emptyKeys, locale).toEqual(
        locale === 'ja' || locale === 'ko' ? ['home.share.shareXPress'] : [],
      )
    }
    expect(ja.home.share.shareXPaste).toBe('を押して画像を投稿に貼り付け')
    expect(ko.home.share.shareXPaste).toBe('를 눌러 게시물에 이미지 붙여넣기')
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
