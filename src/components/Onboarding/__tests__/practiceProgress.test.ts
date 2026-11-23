import { expect, it } from 'vitest'
import { practiceProgress } from '../practiceProgress'

it('maps the taught subpages to their source-backed progress without claiming completion', () => {
  expect(practiceProgress({ mode: 'dictate', stage: 'microphone' })).toBe(50)
  expect(practiceProgress({ mode: 'dictate', stage: 'introduction' })).toBe(56)
  expect(practiceProgress({ mode: 'dictate', stage: 'exercise', exampleIndex: 3 })).toBe(69)
  expect(practiceProgress({ mode: 'translate', stage: 'language' })).toBe(77)
  expect(practiceProgress({ mode: 'ask', stage: 'exercise', exampleIndex: 3 })).toBe(93)
})
