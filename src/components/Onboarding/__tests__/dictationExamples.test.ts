import { describe, expect, it } from 'vitest'
import { UI_LANGUAGES } from '../../../lib/constants'
import { DICTATION_EXAMPLES, dictationExampleText } from '../dictationExamples'

describe('localized dictation exercises', () => {
  it('normalizes locale variants and falls back safely for unknown languages', () => {
    expect(dictationExampleText(0, 'ja-JP')).toBe(DICTATION_EXAMPLES[0].ja)
    expect(dictationExampleText(1, 'pt_BR')).toBe(DICTATION_EXAMPLES[1].pt)
    expect(dictationExampleText(2, 'constructor')).toBe(DICTATION_EXAMPLES[2].en)
    expect(dictationExampleText(3, 'id')).toBe(DICTATION_EXAMPLES[3].en)
    expect(dictationExampleText(99, 'en')).toBe('')
  })
  it.each(UI_LANGUAGES)(
    'provides four distinct native-language examples for $label',
    (language) => {
      const texts = DICTATION_EXAMPLES.map(
        (example) => (example as unknown as Record<string, string>)[language.value],
      )
      expect(texts).toHaveLength(4)
      expect(texts.every((text) => typeof text === 'string' && text.trim().length > 20)).toBe(true)
      expect(new Set(texts).size).toBe(4)
      if (language.value !== 'en') {
        expect(texts.every((text, index) => text !== DICTATION_EXAMPLES[index].en)).toBe(true)
      }
    },
  )
})
