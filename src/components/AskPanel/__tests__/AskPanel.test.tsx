import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../../i18n'
import { AskPanel } from '../AskPanel'
import {
  abortAskDictation,
  startAskDictation,
  stopAskDictation,
  takePendingAskMessage,
} from '../../../lib/tauri'
import type { AskDictationResult } from '../../../lib/tauri'

const tauriEventMock = vi.hoisted(() => {
  type Listener = (event: { payload: unknown }) => void
  const listeners = new Map<string, Listener[]>()
  return {
    listeners,
    listen: vi.fn((event: string, callback: Listener) => {
      const current = listeners.get(event) ?? []
      current.push(callback)
      listeners.set(event, current)
      return Promise.resolve(() => {
        listeners.set(
          event,
          (listeners.get(event) ?? []).filter((listener) => listener !== callback),
        )
      })
    }),
    emit(event: string, payload?: unknown) {
      for (const listener of listeners.get(event) ?? []) {
        listener({ payload })
      }
    },
  }
})

const tauriWindowMock = vi.hoisted(() => {
  type FocusListener = (event: { payload: boolean }) => void
  const focusListeners: FocusListener[] = []
  return {
    focusListeners,
    hide: vi.fn().mockResolvedValue(undefined),
    onFocusChanged: vi.fn((callback: FocusListener) => {
      focusListeners.push(callback)
      return Promise.resolve(() => {
        const index = focusListeners.indexOf(callback)
        if (index >= 0) focusListeners.splice(index, 1)
      })
    }),
    emitFocus(focused: boolean) {
      for (const listener of [...focusListeners]) {
        listener({ payload: focused })
      }
    },
  }
})

vi.mock('../../../lib/tauri', () => ({
  startAskDictation: vi.fn(),
  stopAskDictation: vi.fn(),
  abortAskDictation: vi.fn(),
  takePendingAskMessage: vi.fn(),
  resizeAskWindow: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn().mockResolvedValue(undefined) }))

vi.mock('@tauri-apps/api/event', () => ({
  listen: tauriEventMock.listen,
}))

vi.mock('@tauri-apps/api/window', () => ({
  getCurrentWindow: () => ({
    hide: tauriWindowMock.hide,
    onFocusChanged: tauriWindowMock.onFocusChanged,
  }),
}))

async function flushAsyncEffects() {
  await Promise.resolve()
  await Promise.resolve()
  await new Promise((resolve) => setTimeout(resolve, 0))
}

function askResult(
  overrides: Partial<Awaited<ReturnType<typeof stopAskDictation>>> = {},
): AskDictationResult {
  return {
    question: 'What is OpenTypeless?',
    answer: 'It turns speech into useful text.',
    intent: 'open_question' as const,
    output: 'popupAnswer' as const,
    usedSelectedText: false,
    selectedTextTruncated: false,
    searchProvider: null,
    requestedPlacement: 'popup_answer' as const,
    actualPlacement: 'popup_answer' as const,
    fallbackReason: null,
    ...overrides,
  }
}

function recordingStarted(overrides: Partial<Awaited<ReturnType<typeof startAskDictation>>> = {}) {
  return {
    usedSelectedText: false,
    selectedTextTruncated: false,
    ...overrides,
  }
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  tauriEventMock.listeners.clear()
  tauriWindowMock.focusListeners.splice(0)
})

describe('AskPanel', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
    vi.mocked(startAskDictation).mockResolvedValue(recordingStarted())
    vi.mocked(stopAskDictation).mockResolvedValue(askResult())
    vi.mocked(abortAskDictation).mockResolvedValue(undefined)
    vi.mocked(takePendingAskMessage).mockResolvedValue(null)
  })

  it('renders standalone Ask as the Typeless-style answer card shell', async () => {
    render(<AskPanel />)

    expect(await screen.findByTestId('ask-floating-note')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Close' })).toBeDefined()
    expect(screen.getByText('OpenTypeless')).toBeDefined()
    expect(screen.queryByRole('button', { name: 'Record question' })).toBeNull()
    expect(screen.queryByText('Ready to ask')).toBeNull()
    expect(screen.queryByRole('textbox')).toBeNull()
  })

  it('renders the spoken prompt and a Markdown answer under an Answer header', async () => {
    render(<AskPanel />)

    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.getByTestId('ask-floating-note')).toBeDefined()

    await waitFor(() => {
      expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function))
    })
    expect(tauriEventMock.listen).not.toHaveBeenCalledWith(
      'ask:recording-started',
      expect.any(Function),
    )
    tauriEventMock.emit(
      'ask:result',
      askResult({
        answer: '**Short version:** it turns speech into useful text.\n\n- Fast\n- Private',
      }),
    )

    await waitFor(() => {
      expect(screen.getByText('What is OpenTypeless?')).toBeDefined()
      expect(screen.getByText('Short version:').tagName).toBe('STRONG')
    })
    expect(screen.getByText('Answer')).toBeDefined()
    expect(screen.getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Fast',
      'Private',
    ])
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.getByRole('button', { name: 'Copy answer' })).toBeDefined()
    expect(screen.getByRole('button', { name: 'Copy question' })).toBeDefined()
    expect(startAskDictation).not.toHaveBeenCalled()
  })

  it('shows the selected text under the prompt and copies both together', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(window.navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    })
    render(<AskPanel />)
    await waitFor(() => {
      expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function))
    })
    tauriEventMock.emit(
      'ask:result',
      askResult({
        question: 'Summarize this',
        usedSelectedText: true,
        selectedText: 'Quarterly revenue grew 12%.',
      }),
    )

    expect(await screen.findByText('Quarterly revenue grew 12%.')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Copy question' }))
    expect(writeText).toHaveBeenCalledWith('Summarize this\nQuarterly revenue grew 12%.')
  })

  it('uses the existing compact context line for draft clipboard fallback', async () => {
    render(<AskPanel />)

    await waitFor(() => {
      expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function))
    })
    tauriEventMock.emit(
      'ask:result',
      askResult({
        question: 'draft a launch note',
        answer: 'Launch note',
        intent: 'draft_insert',
        output: 'copiedFallback',
        requestedPlacement: 'insert_at_cursor',
        actualPlacement: null,
        fallbackReason: 'target_changed',
      }),
    )

    expect(await screen.findByText('Target changed; result copied')).toBeDefined()
    expect(screen.getByText('Launch note')).toBeDefined()
    expect(screen.queryByText(/confidence/i)).toBeNull()
    expect(screen.queryByText(/grammar/i)).toBeNull()
  })

  it('shows provider-only search status and never renders query URL or debug metadata', async () => {
    render(<AskPanel />)

    await waitFor(() => {
      expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function))
    })
    tauriEventMock.emit('ask:result', {
      ...askResult({
        question: 'search private launch plan on Google',
        answer: 'Opened Google search.',
        intent: 'search',
        output: 'openedSearch',
        requestedPlacement: 'open_url',
        actualPlacement: 'open_url',
        fallbackReason: null,
        searchProvider: 'Google',
      }),
      query: 'private launch plan',
      searchUrl: 'https://www.google.com/search?q=private+launch+plan',
      confidence: 1,
      grammarLocale: 'en',
    })

    expect(await screen.findByText('Opened Google search')).toBeDefined()
    expect(screen.queryByText(/private launch plan/i)).toBeNull()
    expect(screen.queryByText(/google\.com/i)).toBeNull()
    expect(screen.queryByText(/^en$/i)).toBeNull()
  })

  it('uses restrained fallback copy for a disabled route', async () => {
    render(<AskPanel />)

    await waitFor(() => {
      expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function))
    })
    tauriEventMock.emit(
      'ask:result',
      askResult({
        fallbackReason: 'feature_disabled',
        requestedPlacement: 'popup_answer',
        actualPlacement: 'popup_answer',
      }),
    )

    expect(await screen.findByText('This route is disabled')).toBeDefined()
  })

  it('hides the standalone answer card from its close button', async () => {
    render(<AskPanel />)

    fireEvent.click(screen.getByRole('button', { name: 'Close' }))

    await waitFor(() => {
      expect(tauriWindowMock.hide).toHaveBeenCalledTimes(1)
    })
  })

  it('hides the standalone answer card on Escape', async () => {
    render(<AskPanel />)

    fireEvent.keyDown(window, { key: 'Escape' })

    await waitFor(() => {
      expect(tauriWindowMock.hide).toHaveBeenCalledTimes(1)
    })
  })

  it('keeps the answer card open when the window loses focus', async () => {
    render(<AskPanel />)
    await flushAsyncEffects()

    await act(async () => {
      tauriWindowMock.emitFocus(false)
    })

    await flushAsyncEffects()
    expect(tauriWindowMock.hide).not.toHaveBeenCalled()
    expect(startAskDictation).not.toHaveBeenCalled()
    expect(abortAskDictation).not.toHaveBeenCalled()
  })

  it('ignores stale global Ask recording metadata in the standalone note', async () => {
    render(<AskPanel />)

    await waitFor(() => expect(tauriEventMock.listen).toHaveBeenCalled())
    await act(async () => {
      tauriEventMock.emit('ask:recording-started', recordingStarted({ usedSelectedText: true }))
    })

    await flushAsyncEffects()
    expect(screen.queryByText('Using selected text')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Stop and ask' })).toBeNull()
    expect(startAskDictation).not.toHaveBeenCalled()
  })

  it('copies the hotkey answer from the popup', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(window.navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    })

    render(<AskPanel />)

    await waitFor(() => {
      expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function))
    })
    tauriEventMock.emit('ask:result', askResult())

    fireEvent.click(await screen.findByRole('button', { name: 'Copy answer' }))

    expect(writeText).toHaveBeenCalledWith('It turns speech into useful text.')
    await waitFor(() => {
      expect(screen.getByTitle('Copied')).toBeDefined()
    })
  })

  it('renders a pending hotkey result when the native event was missed', async () => {
    vi.mocked(takePendingAskMessage).mockResolvedValueOnce({
      kind: 'result',
      payload: askResult(),
    })

    render(<AskPanel />)

    await waitFor(() => {
      expect(screen.getByText('It turns speech into useful text.')).toBeDefined()
    })
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.getByRole('button', { name: 'Copy answer' })).toBeDefined()
    expect(startAskDictation).not.toHaveBeenCalled()
  })

  it('ignores pending global Ask recording metadata when the native event was missed', async () => {
    vi.mocked(takePendingAskMessage).mockResolvedValueOnce({
      kind: 'recordingStarted',
      payload: recordingStarted({ usedSelectedText: true, selectedTextTruncated: true }),
    })

    render(<AskPanel />)
    await flushAsyncEffects()

    expect(screen.queryByText('Using selected text (truncated)')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Stop and ask' })).toBeNull()
    expect(startAskDictation).not.toHaveBeenCalled()
  })

  it('does not let the embedded settings panel consume hotkey popup pending messages', async () => {
    vi.mocked(takePendingAskMessage).mockResolvedValueOnce({
      kind: 'result',
      payload: askResult(),
    })

    render(<AskPanel embedded />)
    await flushAsyncEffects()

    expect(takePendingAskMessage).not.toHaveBeenCalled()
    expect(screen.queryByText('It turns speech into useful text.')).toBeNull()
  })

  it('records a spoken question, asks the model, and renders the answer', async () => {
    render(<AskPanel embedded />)

    fireEvent.click(screen.getByRole('button', { name: 'Record question' }))
    await waitFor(() => expect(startAskDictation).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: 'Stop and ask' }))

    await waitFor(() => {
      expect(screen.getByText('It turns speech into useful text.')).toBeDefined()
    })
    expect(stopAskDictation).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Ask' })).toBeNull()
  })

  it('renders backend errors inside the answer card', async () => {
    render(<AskPanel />)

    await waitFor(() => {
      expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:error', expect.any(Function))
    })
    tauriEventMock.emit('ask:error', 'Cloud AI quota exceeded.')

    await waitFor(() => {
      expect(screen.getByText('Cloud AI quota exceeded.')).toBeDefined()
    })
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.getByRole('button', { name: 'Close' })).toBeDefined()
    expect(screen.getByText("Couldn't get an answer")).toBeDefined()
    expect(screen.queryByRole('button', { name: 'Copy answer' })).toBeNull()
  })

  it('does not abort global Ask when an idle panel unmounts', async () => {
    const { unmount } = render(<AskPanel />)
    await flushAsyncEffects()
    vi.mocked(abortAskDictation).mockClear()

    unmount()

    expect(abortAskDictation).not.toHaveBeenCalled()
  })

  it('aborts local dictation when the panel that started it unmounts', async () => {
    const { unmount } = render(<AskPanel embedded />)

    fireEvent.click(screen.getByRole('button', { name: 'Record question' }))
    await waitFor(() => expect(startAskDictation).toHaveBeenCalledTimes(1))
    vi.mocked(abortAskDictation).mockClear()

    unmount()

    expect(abortAskDictation).toHaveBeenCalledTimes(1)
  })

  it('does not abort after stop has handed the request to Ask processing', async () => {
    let resolveStop: (value: AskDictationResult) => void = () => {}
    vi.mocked(stopAskDictation).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveStop = resolve
      }),
    )
    const { unmount } = render(<AskPanel embedded />)

    fireEvent.click(screen.getByRole('button', { name: 'Record question' }))
    await waitFor(() => expect(startAskDictation).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: 'Stop and ask' }))
    await waitFor(() => expect(stopAskDictation).toHaveBeenCalledTimes(1))
    vi.mocked(abortAskDictation).mockClear()

    unmount()

    expect(abortAskDictation).not.toHaveBeenCalled()
    resolveStop({
      question: 'What is OpenTypeless?',
      answer: 'It turns speech into useful text.',
      intent: 'open_question',
      output: 'popupAnswer',
      usedSelectedText: false,
      selectedTextTruncated: false,
      searchProvider: null,
      requestedPlacement: 'popup_answer',
      actualPlacement: 'popup_answer',
      fallbackReason: null,
    })
  })

  it('uses localized copy for the voice-first ask flow', async () => {
    await i18n.changeLanguage('zh')
    render(<AskPanel embedded />)

    expect(screen.getByText('准备提问')).toBeDefined()
    expect(screen.getByText('说出问题，停止后自动回答')).toBeDefined()

    fireEvent.click(screen.getByRole('button', { name: '录制问题' }))
    await waitFor(() => expect(screen.getByText('正在聆听')).toBeDefined())
    expect(screen.getByRole('button', { name: '停止并提问' })).toBeDefined()
  })

  it('uses the Typeless answer-card copy in Chinese', async () => {
    await i18n.changeLanguage('zh')
    render(<AskPanel />)
    await waitFor(() => {
      expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function))
    })
    tauriEventMock.emit('ask:result', askResult())

    expect(await screen.findByText('回答')).toBeDefined()
    expect(screen.getByRole('button', { name: '关闭' })).toBeDefined()
  })
})
