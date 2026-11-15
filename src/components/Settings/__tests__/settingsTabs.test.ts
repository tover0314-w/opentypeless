import { afterEach, describe, expect, it } from 'vitest'
import { paneFromHash } from '../settingsTabs'

afterEach(() => {
  window.location.hash = ''
})

describe('settings pane link validation', () => {
  it.each(['constructor', 'toString', '__proto__', 'unknown'])(
    'rejects the unrecognized pane %s',
    (pane) => {
      window.location.hash = `#/settings?pane=${pane}`
      expect(paneFromHash()).toBeNull()
    },
  )

  it('keeps the legacy scenes link and current account link', () => {
    window.location.hash = '#/settings?pane=scenes'
    expect(paneFromHash()).toBe('personalization')
    window.location.hash = '#/settings?pane=account'
    expect(paneFromHash()).toBe('account')
  })
})
