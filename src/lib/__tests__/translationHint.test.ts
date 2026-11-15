import { beforeEach, describe, expect, it, vi } from 'vitest'
import { recordTranslationHint } from '../translationHint'

beforeEach(() => localStorage.clear())

describe('translation mode onboarding', () => {
  it('waits for ten previous dictations and reminds only once', () => {
    for (let count = 0; count < 10; count += 1) {
      expect(recordTranslationHint(true)).toBe(false)
    }
    expect(recordTranslationHint(true)).toBe(true)
    expect(recordTranslationHint(true)).toBe(false)
  })

  it('counts dictations without translation configured and reminds when it becomes available', () => {
    for (let count = 0; count < 12; count += 1) {
      expect(recordTranslationHint(false)).toBe(false)
    }
    expect(recordTranslationHint(true)).toBe(true)
  })

  it('ignores malformed storage and starts a fresh count', () => {
    localStorage.setItem('capsule.translationOnboarding', '{broken')
    expect(recordTranslationHint(true)).toBe(false)
    expect(JSON.parse(localStorage.getItem('capsule.translationOnboarding')!)).toEqual({
      count: 1,
      shown: false,
    })
  })

  it('does not show an unthrottled hint when persistence fails', () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage unavailable')
    })
    try {
      expect(recordTranslationHint(true)).toBe(false)
    } finally {
      storage.mockRestore()
    }
  })
})
