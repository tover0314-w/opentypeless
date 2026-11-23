import { afterEach, describe, expect, it, vi } from 'vitest'

const originalPlatform = window.navigator.platform

async function loadConfigForPlatform(platform: string) {
  vi.resetModules()
  Object.defineProperty(window.navigator, 'platform', {
    configurable: true,
    value: platform,
  })

  const { useAppStore } = await import('../appStore')
  return useAppStore.getInitialState().config
}

afterEach(() => {
  Object.defineProperty(window.navigator, 'platform', {
    configurable: true,
    value: originalPlatform,
  })
  vi.resetModules()
})

describe('appStore platform default shortcuts', () => {
  it('uses the Fn shortcut family on macOS', async () => {
    const config = await loadConfigForPlatform('MacIntel')

    expect(config.hotkey).toBe('Fn')
    expect(config.ask_hotkey).toBe('Fn+Space')
    expect(config.hotkey_mode).toBe('toggle')
    expect(config.hotkeys.dictation).toEqual({ primary: 'Fn', modifiers: [] })
    expect(config.hotkeys.ask).toEqual({ primary: 'Space', modifiers: ['Fn'] })
    expect(config.hotkeys.translate).toEqual({ primary: 'LeftShift', modifiers: ['Fn'] })
  })

  it('uses the approved right-side voice shortcuts on Windows', async () => {
    const config = await loadConfigForPlatform('Win32')

    expect(config.hotkey).toBe('RightAlt')
    expect(config.ask_hotkey).toBe('RightAlt+Space')
    expect(config.hotkey_mode).toBe('hold')
    expect(config.hotkeys.dictation).toEqual({ primary: 'RightAlt', modifiers: [] })
    expect(config.hotkeys.ask).toEqual({ primary: 'Space', modifiers: ['RightAlt'] })
    expect(config.hotkeys.translate).toEqual({ primary: 'RightShift', modifiers: ['RightAlt'] })
  })

  it('keeps conservative Ctrl shortcuts on Linux', async () => {
    const config = await loadConfigForPlatform('Linux x86_64')

    expect(config.hotkey).toBe('Ctrl+/')
    expect(config.ask_hotkey).toBe('Ctrl+.')
    expect(config.hotkey_mode).toBe('hold')
    expect(config.hotkeys.dictation).toEqual({ primary: '/', modifiers: ['Ctrl'] })
    expect(config.hotkeys.ask).toEqual({ primary: '.', modifiers: ['Ctrl'] })
    expect(config.hotkeys.translate).toEqual({ primary: '/', modifiers: ['Ctrl', 'Shift'] })
  })
})
