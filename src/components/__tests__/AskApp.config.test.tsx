import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../../App'
import i18n from '../../i18n'
import { useAppStore } from '../../stores/appStore'
import { getConfig, startAskDictation, takePendingAskMessage } from '../../lib/tauri'
import { UI_LANGUAGES } from '../../lib/constants'

const events = vi.hoisted(() => ({
  callbacks: new Map<string, (event: { payload: unknown }) => void>(),
}))

vi.mock('@tauri-apps/api/event', () => ({
  listen: vi.fn((name: string, callback: (event: { payload: unknown }) => void) => {
    events.callbacks.set(name, callback)
    return Promise.resolve(() => events.callbacks.delete(name))
  }),
}))
vi.mock('@tauri-apps/api/window', () => ({
  getCurrentWindow: () => ({ hide: vi.fn().mockResolvedValue(undefined) }),
}))
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn() }))
vi.mock('../../lib/tauri', () => ({
  getConfig: vi.fn(),
  takePendingAskMessage: vi.fn(),
  resizeAskWindow: vi.fn().mockResolvedValue(undefined),
  startAskDictation: vi.fn(),
  stopAskDictation: vi.fn(),
  abortAskDictation: vi.fn(),
}))

beforeEach(async () => {
  events.callbacks.clear()
  vi.clearAllMocks()
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  )
  window.location.hash = '#ask'
  useAppStore.setState(useAppStore.getInitialState())
  vi.mocked(getConfig).mockResolvedValue(useAppStore.getState().config)
  vi.mocked(takePendingAskMessage).mockResolvedValue(null)
  await i18n.changeLanguage('en')
})

afterEach(async () => {
  cleanup()
  events.callbacks.clear()
  vi.unstubAllGlobals()
  window.location.hash = ''
  localStorage.removeItem('ui_language')
  document.documentElement.classList.remove('dark')
  useAppStore.setState(useAppStore.getInitialState())
  await i18n.changeLanguage('en')
})

describe('Ask window configuration integration', () => {
  it('preserves unknown provider error text while translating surrounding controls', async () => {
    render(<App />)
    await waitFor(() => expect(events.callbacks.has('ask:error')).toBe(true))
    const providerMessage = 'Synthetic provider diagnostic — 原始说明'
    await act(async () => events.callbacks.get('ask:error')?.({ payload: providerMessage }))
    for (const language of UI_LANGUAGES) {
      await act(async () =>
        events.callbacks.get('config:patch')?.({ payload: { ui_language: language.value } }),
      )
      expect(screen.getByText(providerMessage)).toBeVisible()
      expect(screen.getByText(i18n.t('ask.errorTitle'))).toBeVisible()
      expect(screen.getByRole('button', { name: i18n.t('ask.close') })).toBeVisible()
      expect(screen.queryByRole('button', { name: i18n.t('ask.copyAnswer') })).toBeNull()
    }
  })

  it('localizes a busy notice from the native window and subsequent language switches', async () => {
    await i18n.changeLanguage('zh')
    vi.mocked(getConfig).mockResolvedValue({
      ...useAppStore.getState().config,
      ui_language: 'zh',
    })
    render(<App />)
    await waitFor(() => expect(events.callbacks.has('ask:error')).toBe(true))
    await act(async () => events.callbacks.get('ask:error')?.({ payload: 'Ask is already busy' }))
    expect(screen.getByText(i18n.t('history.retryBusy'))).toBeVisible()
    for (const language of UI_LANGUAGES) {
      await act(async () =>
        events.callbacks.get('config:patch')?.({ payload: { ui_language: language.value } }),
      )
      expect(screen.getByText(i18n.t('history.retryBusy'))).toBeVisible()
    }
  })

  it('updates the real answer card from configuration events without replacing its answer', async () => {
    const { unmount } = render(<App />)
    await waitFor(() => {
      expect(events.callbacks.has('config:patch')).toBe(true)
      expect(events.callbacks.has('ask:result')).toBe(true)
    })
    act(() =>
      events.callbacks.get('ask:result')?.({
        payload: {
          question: 'Synthetic question',
          answer: 'Synthetic retained answer',
          intent: 'open_question',
          output: 'popupAnswer',
          usedSelectedText: false,
          selectedTextTruncated: false,
          searchProvider: null,
          requestedPlacement: 'popup_answer',
          actualPlacement: 'popup_answer',
          fallbackReason: null,
        },
      }),
    )
    expect(await screen.findByText('Synthetic retained answer')).toBeInTheDocument()
    for (const [index, language] of UI_LANGUAGES.entries()) {
      const theme = index % 2 === 0 ? 'dark' : 'light'
      await act(async () =>
        events.callbacks.get('config:patch')?.({
          payload: { ui_language: language.value, theme },
        }),
      )
      expect(screen.getByText(i18n.t('ask.answerTitle'))).toBeInTheDocument()
      expect(screen.getByRole('button', { name: i18n.t('ask.close') })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: i18n.t('ask.copyAnswer') })).toBeInTheDocument()
      expect(screen.getByText('Synthetic retained answer')).toBeInTheDocument()
      expect(document.documentElement.classList.contains('dark')).toBe(theme === 'dark')
      expect(localStorage.getItem('ui_language')).toBe(language.value)
    }
    expect(startAskDictation).not.toHaveBeenCalled()
    unmount()
    expect(events.callbacks.has('config:patch')).toBe(false)
    expect(events.callbacks.has('ask:result')).toBe(false)
  })
})
