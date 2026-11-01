import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { useAppStore } from '../../../stores/appStore'
import { HomePage } from '../index'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'en' },
  }),
}))

vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn() }))

vi.mock('../../../lib/tauri', () => ({
  getUsageStats: vi.fn().mockResolvedValue(null),
  pauseHotkey: vi.fn().mockResolvedValue(undefined),
  resumeHotkey: vi.fn().mockResolvedValue(undefined),
}))

function dictationBindings() {
  return useAppStore.getState().config.hotkeys.dictationBindings
}

describe('HomePage shortcuts card', () => {
  beforeEach(() => {
    useAppStore.setState(useAppStore.getInitialState())
    const config = useAppStore.getState().config
    useAppStore.setState({
      config: {
        ...config,
        hotkeys: {
          ...config.hotkeys,
          dictation: { primary: 'Fn', modifiers: [] },
          dictationBindings: [
            { primary: 'Fn', modifiers: [] },
            { primary: '9', modifiers: ['Ctrl', 'Option'] },
          ],
          translate: { primary: 'LeftShift', modifiers: ['Fn'] },
          translateBindings: [{ primary: 'LeftShift', modifiers: ['Fn'] }],
          ask: { primary: 'Space', modifiers: ['Fn'] },
          askBindings: [{ primary: 'Space', modifiers: ['Fn'] }],
        },
      },
    })
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
  })

  it('records a new primary shortcut in place and keeps the extra bindings', () => {
    vi.useFakeTimers()
    render(<HomePage />)
    fireEvent.click(
      screen.getByRole('button', { name: 'home.editShortcut: home.modes.dictate.title' }),
    )
    expect(screen.getByText('settings.pressKeyCombination')).toBeInTheDocument()

    fireEvent.keyDown(window, { key: 'k', ctrlKey: true, shiftKey: true })
    vi.advanceTimersByTime(1600)

    expect(dictationBindings()).toEqual([
      { primary: 'K', modifiers: ['Ctrl', 'Shift'] },
      { primary: '9', modifiers: ['Ctrl', 'Option'] },
    ])
    expect(useAppStore.getState().config.hotkeys.dictation).toEqual({
      primary: 'K',
      modifiers: ['Ctrl', 'Shift'],
    })
  })

  it('refuses a shortcut already used by another voice feature', () => {
    vi.useFakeTimers()
    render(<HomePage />)
    fireEvent.click(
      screen.getByRole('button', { name: 'home.editShortcut: home.modes.dictate.title' }),
    )
    fireEvent.keyDown(window, { key: '9', ctrlKey: true, altKey: true })
    vi.advanceTimersByTime(1600)

    expect(dictationBindings()[0]).toEqual({ primary: 'Fn', modifiers: [] })
  })

  it('cancels editing without changing the shortcut', () => {
    render(<HomePage />)
    fireEvent.click(
      screen.getByRole('button', { name: 'home.editShortcut: home.modes.translate.title' }),
    )
    fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))

    expect(screen.queryByText('settings.pressKeyCombination')).toBeNull()
    expect(useAppStore.getState().config.hotkeys.translate).toEqual({
      primary: 'LeftShift',
      modifiers: ['Fn'],
    })
  })
})
