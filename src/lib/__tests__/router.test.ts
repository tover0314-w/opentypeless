import { afterEach, describe, expect, it } from 'vitest'
import { parseHash, parseLocation } from '../router'

afterEach(() => {
  window.location.hash = ''
})

describe('desktop route parsing', () => {
  it('redirects old model deep links to the sidebar Models page', () => {
    for (const pane of ['stt', 'llm', 'models']) {
      window.location.hash = `#/settings?pane=${pane}`
      expect(parseHash()).toBe('models')
    }
    window.location.hash = '#/settings?pane=help'
    expect(parseHash()).toBe('settings')
  })

  it('treats models as a first-class page', () => {
    window.location.hash = '#/models'
    expect(parseLocation()).toEqual({ page: 'models', modal: null })
  })

  it('treats dictionary as a first-class page', () => {
    window.location.hash = '#/dictionary'
    expect(parseLocation()).toEqual({ page: 'dictionary', modal: null })
  })

  it('opens settings, account and upgrade as overlays above the last page', () => {
    window.location.hash = '#/history'
    expect(parseLocation()).toEqual({ page: 'history', modal: null })

    window.location.hash = '#/settings?pane=general'
    expect(parseLocation()).toEqual({ page: 'history', modal: 'settings' })

    window.location.hash = '#/account'
    expect(parseLocation()).toEqual({ page: 'history', modal: 'account' })

    window.location.hash = '#/upgrade'
    expect(parseLocation()).toEqual({ page: 'history', modal: 'upgrade' })
  })

  it('falls back to home for unknown hashes', () => {
    window.location.hash = '#/nope'
    expect(parseLocation().page).toBe('home')
  })
})
