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
import { UI_LANGUAGES } from '../../../lib/constants'
import { openUrl } from '@tauri-apps/plugin-opener'

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
  it.each([
    ['startup', 'object'],
    ['stop', 'object'],
    ['startup', 'string'],
    ['stop', 'string'],
  ] as const)(
    'keeps native cancellation silent during %s with %s rejection and permits retry',
    async (stage, rejectionType) => {
      const rejection = rejectionType === 'string' ? 'Ask cancelled' : new Error('Ask cancelled')
      if (stage === 'startup') {
        vi.mocked(startAskDictation).mockRejectedValueOnce(rejection)
      } else {
        vi.mocked(stopAskDictation).mockRejectedValueOnce(rejection)
      }
      render(<AskPanel embedded />)
      await act(async () =>
        fireEvent.click(screen.getByRole('button', { name: 'Record question' })),
      )
      if (stage === 'stop') {
        await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Stop and ask' })))
      }
      expect(screen.queryByText('Ask cancelled')).toBeNull()
      expect(screen.getByRole('button', { name: 'Record question' })).toBeEnabled()
      await act(async () =>
        fireEvent.click(screen.getByRole('button', { name: 'Record question' })),
      )
      await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Stop and ask' })))
      expect(screen.getByText('It turns speech into useful text.')).toBeInTheDocument()
      expect(abortAskDictation).not.toHaveBeenCalled()
    },
  )

  it('does not stop or cancel another operation when local Ask startup is busy', async () => {
    vi.mocked(startAskDictation).mockRejectedValueOnce(new Error('Ask is already busy'))
    const { unmount } = render(<AskPanel embedded />)
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Record question' })))
    expect(screen.getByText(i18n.t('history.retryBusy'))).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Record question' })).toBeEnabled()
    unmount()
    expect(stopAskDictation).not.toHaveBeenCalled()
    expect(abortAskDictation).not.toHaveBeenCalled()
  })

  it.each(['success', 'failure', 'busy'] as const)(
    'waits for startup before stopping and handles startup %s',
    async (outcome) => {
      let finish!: (value: Awaited<ReturnType<typeof startAskDictation>>) => void
      let fail!: (error: Error) => void
      vi.mocked(startAskDictation).mockImplementationOnce(
        () =>
          new Promise((resolve, reject) => {
            finish = resolve
            fail = reject
          }),
      )
      render(<AskPanel embedded />)
      fireEvent.click(screen.getByRole('button', { name: 'Record question' }))
      const stopButton = screen.getByRole('button', { name: 'Stop and ask' })
      act(() => {
        fireEvent.click(stopButton)
        fireEvent.click(stopButton)
      })
      expect(stopAskDictation).not.toHaveBeenCalled()
      expect(screen.getByRole('button', { name: 'Record question' })).toBeDisabled()
      await act(async () => {
        if (outcome === 'success') finish(recordingStarted({ usedSelectedText: true }))
        else fail(new Error(outcome === 'busy' ? 'Ask is already busy' : 'Startup failed'))
      })
      if (outcome === 'success') {
        expect(stopAskDictation).toHaveBeenCalledOnce()
        expect(screen.getByText('It turns speech into useful text.')).toBeInTheDocument()
      } else {
        expect(stopAskDictation).not.toHaveBeenCalled()
        expect(
          screen.getByText(outcome === 'busy' ? i18n.t('history.retryBusy') : 'Startup failed'),
        ).toBeInTheDocument()
        await act(async () =>
          fireEvent.click(screen.getByRole('button', { name: 'Record question' })),
        )
        expect(screen.queryByText('Startup failed')).toBeNull()
        expect(screen.queryByText(i18n.t('history.retryBusy'))).toBeNull()
        await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Stop and ask' })))
        expect(stopAskDictation).toHaveBeenCalledOnce()
        expect(screen.getByText('It turns speech into useful text.')).toBeInTheDocument()
      }
      expect(screen.getByRole('button', { name: 'Record question' })).toBeEnabled()
      expect(abortAskDictation).not.toHaveBeenCalled()
    },
  )

  it('does not restore startup ownership after its panel unmounts', async () => {
    let finish!: (value: Awaited<ReturnType<typeof startAskDictation>>) => void
    vi.mocked(startAskDictation).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const older = render(<AskPanel embedded />)
    fireEvent.click(screen.getByRole('button', { name: 'Record question' }))
    older.unmount()
    expect(abortAskDictation).toHaveBeenCalledOnce()
    render(<AskPanel embedded />)
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Record question' })))
    await act(async () => finish(recordingStarted({ usedSelectedText: true })))
    expect(screen.getByRole('button', { name: 'Stop and ask' })).toBeInTheDocument()
    expect(screen.queryByText('Using selected text')).toBeNull()
    expect(abortAskDictation).toHaveBeenCalledOnce()
  })

  it.each(['success', 'failure'] as const)(
    'cancels queued stop on unmount and ignores late startup %s',
    async (outcome) => {
      let finish!: (value: Awaited<ReturnType<typeof startAskDictation>>) => void
      let fail!: (error: Error) => void
      vi.mocked(startAskDictation).mockImplementationOnce(
        () =>
          new Promise((resolve, reject) => {
            finish = resolve
            fail = reject
          }),
      )
      const { unmount } = render(<AskPanel embedded />)
      fireEvent.click(screen.getByRole('button', { name: 'Record question' }))
      await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Stop and ask' })))
      expect(stopAskDictation).not.toHaveBeenCalled()
      unmount()
      expect(abortAskDictation).toHaveBeenCalledOnce()
      await act(async () => {
        if (outcome === 'success') finish(recordingStarted({ usedSelectedText: true }))
        else fail(new Error('Discarded startup failure'))
      })
      expect(stopAskDictation).not.toHaveBeenCalled()
      expect(screen.queryByText('Discarded startup failure')).toBeNull()
      expect(abortAskDictation).toHaveBeenCalledOnce()
    },
  )

  it.each(['mounted', 'unmounted'] as const)(
    'handles embedded copy completion while %s',
    async (lifecycle) => {
      let finishCopy!: () => void
      const writeText = vi.fn(
        () =>
          new Promise<void>((resolve) => {
            finishCopy = resolve
          }),
      )
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: { writeText },
      })
      const { unmount } = render(<AskPanel embedded />)
      fireEvent.click(screen.getByRole('button', { name: 'Record question' }))
      await waitFor(() => expect(startAskDictation).toHaveBeenCalledOnce())
      await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Stop and ask' })))
      fireEvent.click(screen.getByRole('button', { name: 'Copy answer' }))
      if (lifecycle === 'unmounted') unmount()
      const timer = vi.spyOn(globalThis, 'setTimeout')
      try {
        await act(async () => finishCopy())
        if (lifecycle === 'mounted') {
          expect(screen.getByText('Copied')).toBeInTheDocument()
          expect(timer.mock.calls.filter((call) => call[1] === 1500)).toHaveLength(1)
        } else {
          expect(screen.queryByText('Copied')).toBeNull()
          expect(timer.mock.calls.filter((call) => call[1] === 1500)).toHaveLength(0)
        }
        expect(writeText).toHaveBeenCalledExactlyOnceWith('It turns speech into useful text.')
      } finally {
        timer.mockRestore()
      }
    },
  )

  it('does not mark a newer embedded answer copied when an older copy finishes', async () => {
    let finishCopy!: () => void
    const writeText = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishCopy = resolve
        }),
    )
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    render(<AskPanel embedded />)
    fireEvent.click(screen.getByRole('button', { name: 'Record question' }))
    await waitFor(() => expect(startAskDictation).toHaveBeenCalledOnce())
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Stop and ask' })))
    fireEvent.click(screen.getByRole('button', { name: 'Copy answer' }))
    vi.mocked(stopAskDictation).mockResolvedValueOnce(askResult({ answer: 'New embedded answer' }))
    fireEvent.click(screen.getByRole('button', { name: 'Record question' }))
    await waitFor(() => expect(startAskDictation).toHaveBeenCalledTimes(2))
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Stop and ask' })))
    await act(async () => finishCopy())
    expect(screen.getByText('New embedded answer')).toBeInTheDocument()
    expect(screen.queryByText('Copied')).toBeNull()
    expect(writeText).toHaveBeenCalledExactlyOnceWith('It turns speech into useful text.')
  })

  it.each(['result', 'dismiss'] as const)(
    'does not restore startup cache after %s while listener registration is pending',
    async (action) => {
      let finishRegistration!: (unlisten: () => void) => void
      tauriEventMock.listen.mockImplementationOnce((event, callback) => {
        tauriEventMock.listeners.set(event, [callback])
        return new Promise((resolve) => {
          finishRegistration = resolve
        })
      })
      vi.mocked(takePendingAskMessage)
        .mockResolvedValueOnce(null)
        .mockResolvedValue({
          kind: 'result',
          payload: askResult({ answer: 'Stale startup answer' }),
        })
      render(<AskPanel />)
      await waitFor(() => expect(tauriEventMock.listeners.has('ask:result')).toBe(true))
      if (action === 'result') {
        act(() => tauriEventMock.emit('ask:result', askResult({ answer: 'Early live answer' })))
      } else {
        fireEvent.click(screen.getByRole('button', { name: 'Close' }))
        vi.mocked(takePendingAskMessage).mockResolvedValueOnce({
          kind: 'result',
          payload: askResult({ answer: 'Stale startup answer' }),
        })
      }
      await act(async () => finishRegistration(vi.fn()))
      expect(screen.queryByText('Stale startup answer')).toBeNull()
      if (action === 'result') {
        expect(screen.getByText('Early live answer')).toBeInTheDocument()
        expect(takePendingAskMessage).toHaveBeenCalledTimes(1)
      } else {
        expect(takePendingAskMessage).not.toHaveBeenCalled()
      }
    },
  )

  it('does not restore a delayed pending answer after the card is dismissed', async () => {
    let finish!: (message: Awaited<ReturnType<typeof takePendingAskMessage>>) => void
    vi.mocked(takePendingAskMessage).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    render(<AskPanel />)
    await waitFor(() => expect(takePendingAskMessage).toHaveBeenCalledOnce())
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    await act(async () =>
      finish({ kind: 'result', payload: askResult({ answer: 'Dismissed pending answer' }) }),
    )
    expect(screen.queryByText('Dismissed pending answer')).toBeNull()
    expect(tauriWindowMock.hide).toHaveBeenCalledOnce()
    act(() => tauriEventMock.emit('ask:result', askResult({ answer: 'Next live answer' })))
    expect(await screen.findByText('Next live answer')).toBeInTheDocument()
  })

  it('keeps a live error instead of applying a delayed pending answer', async () => {
    let finish!: (message: Awaited<ReturnType<typeof takePendingAskMessage>>) => void
    vi.mocked(takePendingAskMessage).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    render(<AskPanel />)
    await waitFor(() => expect(takePendingAskMessage).toHaveBeenCalledOnce())
    act(() => tauriEventMock.emit('ask:error', 'Current live error'))
    await act(async () =>
      finish({ kind: 'result', payload: askResult({ answer: 'Old pending answer' }) }),
    )
    expect(screen.getByText('Current live error')).toBeInTheDocument()
    expect(screen.queryByText('Old pending answer')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Copy answer' })).toBeNull()
  })

  it.each(['result', 'error'] as const)(
    'keeps a live answer instead of applying a delayed pending %s',
    async (kind) => {
      let finish!: (message: Awaited<ReturnType<typeof takePendingAskMessage>>) => void
      vi.mocked(takePendingAskMessage).mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve
          }),
      )
      render(<AskPanel />)
      await waitFor(() => expect(takePendingAskMessage).toHaveBeenCalledOnce())
      act(() => tauriEventMock.emit('ask:result', askResult({ answer: 'Current live answer' })))
      await act(async () =>
        finish(
          kind === 'result'
            ? { kind: 'result', payload: askResult({ answer: 'Old pending answer' }) }
            : { kind: 'error', payload: 'Old pending error' },
        ),
      )
      expect(screen.getByText('Current live answer')).toBeInTheDocument()
      expect(screen.queryByText('Old pending answer')).toBeNull()
      expect(screen.queryByText('Old pending error')).toBeNull()
    },
  )

  it.each([
    ['before', 'ask:error'],
    ['after', 'ask:error'],
    ['before', 'ask:result'],
    ['after', 'ask:result'],
  ])(
    'releases a successful listener resolving %s when %s registration fails',
    async (order, failedEvent) => {
      const release = vi.fn()
      let finish!: (callback: () => void) => void
      let fail!: (error: Error) => void
      let retainedCallback!: (event: { payload: unknown }) => void
      const register = (event: string, callback: (event: { payload: unknown }) => void) => {
        if (event !== failedEvent) retainedCallback = callback
        return event === failedEvent
          ? new Promise<() => void>((_resolve, reject) => {
              fail = reject
            })
          : new Promise<() => void>((resolve) => {
              finish = resolve
            })
      }
      tauriEventMock.listen.mockImplementationOnce(register).mockImplementationOnce(register)
      const { unmount } = render(<AskPanel />)
      await flushAsyncEffects()
      if (order === 'before') await act(async () => finish(release))
      await act(async () => fail(new Error('Synthetic listener failure')))
      if (order === 'after') await act(async () => finish(release))
      expect(release).toHaveBeenCalledOnce()
      act(() =>
        retainedCallback({
          payload:
            failedEvent === 'ask:error'
              ? askResult({ answer: 'Discarded late answer' })
              : 'Discarded late error',
        }),
      )
      expect(screen.queryByText('Discarded late answer')).toBeNull()
      expect(screen.queryByText('Discarded late error')).toBeNull()
      expect(takePendingAskMessage).not.toHaveBeenCalled()
      unmount()
      expect(release).toHaveBeenCalledOnce()
    },
  )

  it('handles asynchronous answer listener cleanup rejection', async () => {
    const rejection = Promise.reject(new Error('Synthetic result cleanup failure'))
    void rejection.catch(() => {})
    const handled = vi.spyOn(rejection, 'catch')
    const release = vi.fn(() => rejection)
    tauriEventMock.listen.mockResolvedValueOnce(release)
    try {
      const { unmount } = render(<AskPanel />)
      await flushAsyncEffects()
      unmount()
      expect(release).toHaveBeenCalledOnce()
      expect(handled).toHaveBeenCalledWith(expect.any(Function))
    } finally {
      handled.mockRestore()
    }
  })

  it('does not log raw link-opening errors or discard the answer', async () => {
    const address = 'https://example.com/docs?private=synthetic-value'
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(openUrl).mockRejectedValueOnce(new Error(`Unable to open ${address}`))
    try {
      render(<AskPanel />)
      await waitFor(() =>
        expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function)),
      )
      act(() => tauriEventMock.emit('ask:result', askResult({ answer: `[Reference](${address})` })))
      fireEvent.click(await screen.findByRole('link', { name: 'Reference' }))
      await waitFor(() => expect(log).toHaveBeenCalledExactlyOnceWith('Failed to open link'))
      expect(openUrl).toHaveBeenCalledExactlyOnceWith(address)
      expect(screen.getByRole('link', { name: 'Reference' })).toBeInTheDocument()
    } finally {
      log.mockRestore()
    }
  })

  beforeEach(async () => {
    await i18n.changeLanguage('en')
    vi.mocked(startAskDictation).mockResolvedValue(recordingStarted())
    vi.mocked(stopAskDictation).mockResolvedValue(askResult())
    vi.mocked(abortAskDictation).mockResolvedValue(undefined)
    vi.mocked(takePendingAskMessage).mockReset().mockResolvedValue(null)
  })

  it('updates visible answer controls on language changes without replacing the answer', async () => {
    render(<AskPanel />)
    await waitFor(() =>
      expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function)),
    )
    act(() => tauriEventMock.emit('ask:result', askResult()))
    expect(await screen.findByText('Answer')).toBeInTheDocument()
    const answer = 'It turns speech into useful text.'
    expect(screen.getByText(answer)).toBeInTheDocument()
    for (const { value: locale } of UI_LANGUAGES) {
      await act(() => i18n.changeLanguage(locale))
      expect(screen.getByText(i18n.t('ask.answerTitle'))).toBeInTheDocument()
      expect(screen.getByRole('button', { name: i18n.t('ask.close') })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: i18n.t('ask.copyAnswer') })).toBeInTheDocument()
      expect(screen.getByText(answer)).toBeInTheDocument()
    }
    expect(startAskDictation).not.toHaveBeenCalled()
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
    await act(async () => tauriEventMock.emit('ask:result', askResult()))

    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Copy answer' })))

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
      expect(screen.getByText(i18n.t('capsule.errors.llm_quota_exceeded'))).toBeDefined()
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
