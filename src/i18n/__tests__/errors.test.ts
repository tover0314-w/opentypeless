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

const locales = { de, en, es, fr, it: itLocale, ja, ko, pt, ru, zh }

const requiredErrorKeys = [
  'stt_timeout',
  'stt_invalid_key',
  'stt_failed',
  'stt_quota_exceeded',
  'stt_no_speech_detected',
  'output_fallback_clipboard',
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
