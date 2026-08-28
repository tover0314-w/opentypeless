import { afterEach, describe, expect, it } from 'vitest'
import { parseHash } from '../router'

afterEach(() => {
  window.location.hash = ''
})

describe('desktop route parsing', () => {
  it('keeps direct recovery links inside Settings', () => {
    window.location.hash = '#/settings?pane=stt'
    expect(parseHash()).toBe('settings')
    window.location.hash = '#/settings?pane=llm'
    expect(parseHash()).toBe('settings')
  })
})
