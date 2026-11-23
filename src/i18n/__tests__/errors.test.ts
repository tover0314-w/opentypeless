import { describe, expect, it } from 'vitest'
import { createInstance } from 'i18next'
import ts from 'typescript'
import capsuleErrorSource from '../../lib/capsuleError.ts?raw'
import outputSource from '../../../src-tauri/src/output/mod.rs?raw'
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

const requiredErrorKeys = [
  'stt_timeout',
  'stt_invalid_key',
  'stt_failed',
  'stt_quota_exceeded',
  'stt_no_speech_detected',
  'output_fallback_clipboard',
  'output_target_changed',
  'output_wayland_unsupported',
  'accessibility_required',
  'output_streaming_partial',
  'llm_failed',
  'llm_quota_exceeded',
] as const

const requiredCapsuleErrorKeys = [
  'stt_timeout',
  'stt_invalid_key',
  'stt_failed',
  'stt_quota_exceeded',
  'stt_no_speech_detected',
  'output_fallback_clipboard',
  'output_wayland_unsupported',
  'accessibility_required',
  'llm_failed',
  'llm_quota_exceeded',
  'stt_not_configured',
  'stt_connection_failed',
  'audio_failed',
  'output_failed',
  'unknown',
] as const

describe('localized error messages', () => {
  it('defines localized messages for literal codes in the production output module', () => {
    const production = outputSource.split(/#\[cfg\(test\)\]\s*mod tests\s*\{/)[0]
    const codes = [
      ...new Set(
        [...production.matchAll(/\bcode:\s*"([^"]+)"\.to_string\(\)/g)].map((match) => match[1]),
      ),
    ]
    expect(codes).toContain('output_fallback_clipboard')
    expect(codes).toContain('output_wayland_clipboard_copy_only')
    for (const [locale, messages] of Object.entries(locales)) {
      const errors = messages.errors as Record<string, string>
      for (const code of codes) {
        expect(errors[code], `${locale}: errors.${code}`).toEqual(expect.any(String))
        expect(errors[code].trim(), `${locale}: errors.${code}`).not.toBe('')
      }
    }
  })

  it('resolves target-change warnings in each locale without fallback or raw diagnostics', async () => {
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
      const resolved = instance.t('errors.output_target_changed', {
        details: 'SYNTHETIC_NATIVE_DIAGNOSTIC',
        defaultValue: 'UNEXPECTED_FALLBACK',
        returnDetails: true,
      })
      expect(resolved.usedLng, locale).toBe(locale)
      expect(resolved.res, locale).toBe(messages.errors.output_target_changed)
      expect(resolved.res, locale).not.toContain('SYNTHETIC_NATIVE_DIAGNOSTIC')
      expect(resolved.res, locale).not.toContain('{{')
    }
  })

  it('resolves every production capsule error key in its own locale without fallback', async () => {
    const source = ts.createSourceFile(
      'capsuleError.ts',
      capsuleErrorSource,
      ts.ScriptTarget.Latest,
      true,
    )
    const declaration = source.statements.find(
      (statement): statement is ts.TypeAliasDeclaration =>
        ts.isTypeAliasDeclaration(statement) && statement.name.text === 'CapsuleErrorKey',
    )
    expect(declaration).toBeDefined()
    expect(ts.isUnionTypeNode(declaration!.type)).toBe(true)
    if (!ts.isUnionTypeNode(declaration!.type)) throw new Error('Expected literal error union')
    const keys = declaration!.type.types.map((member) => {
      if (!ts.isLiteralTypeNode(member) || !ts.isStringLiteral(member.literal)) {
        throw new Error('Expected literal capsule error key')
      }
      return member.literal.text
    })
    expect(keys).toContain('unknown')
    expect(keys.length).toBeGreaterThan(requiredCapsuleErrorKeys.length)
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
      const errors = messages.capsule.errors as Record<string, unknown>
      for (const key of keys) {
        expect(errors[key], `${locale}: capsule.errors.${key}`).toEqual(expect.any(String))
        expect((errors[key] as string).trim(), `${locale}: capsule.errors.${key}`).not.toBe('')
        const resolved = instance.t(`capsule.errors.${key}`, {
          defaultValue: 'UNEXPECTED_FALLBACK',
          returnDetails: true,
        })
        expect(resolved.res, `${locale}: capsule.errors.${key}`).toBe(errors[key])
        expect(resolved.usedLng, `${locale}: capsule.errors.${key}`).toBe(locale)
      }
    }
  })

  it('defines all structured error keys for every locale', () => {
    for (const [locale, messages] of Object.entries(locales)) {
      const errors = (messages as { errors?: Record<string, string> }).errors
      expect(errors, `${locale}.errors`).toEqual(expect.any(Object))

      for (const key of requiredErrorKeys) {
        const value = errors?.[key]
        expect(value, `${locale}.${key}`).toEqual(expect.any(String))
        expect(value?.trim(), `${locale}.${key}`).not.toBe('')
      }
    }
  })

  it('defines all short capsule error labels for every locale', () => {
    for (const [locale, messages] of Object.entries(locales)) {
      const capsule = (messages as { capsule?: { errors?: Record<string, string> } }).capsule
      expect(capsule?.errors, `${locale}.capsule.errors`).toEqual(expect.any(Object))

      for (const key of requiredCapsuleErrorKeys) {
        const value = capsule?.errors?.[key]
        expect(value, `${locale}.capsule.errors.${key}`).toEqual(expect.any(String))
        expect(value?.trim(), `${locale}.capsule.errors.${key}`).not.toBe('')
      }
    }
  })

  it('describes the Wayland fallback as session-specific instead of unsupported', () => {
    expect(en.errors.output_wayland_unsupported).toBe(
      'Direct typing is unavailable in this Wayland session. The result was copied to the clipboard.',
    )
    expect(zh.errors.output_wayland_unsupported).toBe(
      '当前 Wayland 会话无法直接输入，结果已复制到剪贴板。',
    )
    expect(en.errors.output_wayland_unsupported).not.toContain('not supported')
    expect(zh.errors.output_wayland_unsupported).not.toContain('暂不支持')
  })
})
