import { expect, it } from 'vitest'
import { UI_LANGUAGES } from '../../../lib/constants'
import { ASK_EXAMPLES } from '../askExamples'

it.each(UI_LANGUAGES)('localizes Ask instructions and supplied selection in $label', (language) => {
  for (const example of ASK_EXAMPLES) {
    const localized = example as unknown as Record<string, unknown>
    expect(typeof localized[language.value]).toBe('string')
    if (language.value !== 'en') expect(localized[language.value]).not.toBe(example.en)
    if (example.selectedText) {
      const selection = example.selectedText as unknown as Record<string, string>
      expect(typeof selection[language.value]).toBe('string')
      expect(selection[language.value].length).toBeLessThanOrEqual(4000)
      if (language.value !== 'en') expect(selection[language.value]).not.toBe(selection.en)
    }
  }
})
