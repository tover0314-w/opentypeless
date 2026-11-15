import { afterEach, describe, expect, it, vi } from 'vitest'
import { textPreview } from '../textPreview'

afterEach(() => vi.unstubAllGlobals())

describe('text preview', () => {
  it('preserves text at the limit and truncates only longer content', () => {
    expect(textPreview('', 32)).toBe('')
    expect(textPreview('你好', 2)).toBe('你好')
    expect(textPreview('你好呀', 2)).toBe('你好…')
  })

  it('falls back to intact code points when grapheme segmentation is unavailable', () => {
    vi.stubGlobal('Intl', { Segmenter: undefined })
    expect(textPreview('😀more', 1)).toBe('😀…')
  })
})
