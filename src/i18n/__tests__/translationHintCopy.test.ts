import { describe, expect, it } from 'vitest'
import en from '../locales/en.json'
import zh from '../locales/zh.json'

describe('translation hint capability copy', () => {
  it('describes translation after recording rather than live translation', () => {
    expect(en.capsule.alerts.switchTranslationSuffix).toBe(
      'to translate your speech after recording.',
    )
    expect(zh.capsule.alerts.switchTranslationSuffix).toBe('在录音结束后翻译你的口述。')
  })
})
