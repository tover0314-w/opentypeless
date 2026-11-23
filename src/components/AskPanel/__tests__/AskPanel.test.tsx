import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../../i18n'
import { AskPanel } from '../AskPanel'
import {
  abortAskDictation,
  dismissAskWindow,
  startAskDictation,
  stopAskDictation,
  takePendingAskMessage,
} from '../../../lib/tauri'
import type { AskDictationResult } from '../../../lib/tauri'
import { UI_LANGUAGES } from '../../../lib/constants'
import { openUrl } from '@tauri-apps/plugin-opener'
import { ToastContainer } from '../../Toast'
import { toast } from '../../toast-service'
import { useAuthStore } from '../../../stores/authStore'
import { useAppStore } from '../../../stores/appStore'

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
  dismissAskWindow: vi.fn(async () => {
    await tauriWindowMock.hide()
  }),
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
  useAuthStore.setState({ askWebSearchCapability: null, askWebSearchAccountId: null })
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

  it.each(['rejected', 'throws', 'unavailable'] as const)(
    'reports embedded copy failure when clipboard %s without discarding the answer',
    async (scenario) => {
      const writeText = vi.fn(() => {
        if (scenario === 'throws') throw new Error('synthetic private clipboard detail')
        return Promise.reject(new Error('synthetic private clipboard detail'))
      })
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: scenario === 'unavailable' ? undefined : { writeText },
      })
      const feedback = vi.spyOn(toast, 'error').mockImplementation(() => {})
      try {
        render(<AskPanel embedded />)
        fireEvent.click(screen.getByRole('button', { name: 'Record question' }))
        await waitFor(() => expect(startAskDictation).toHaveBeenCalledOnce())
        await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Stop and ask' })))
        fireEvent.click(screen.getByRole('button', { name: 'Copy answer' }))
        await waitFor(() =>
          expect(feedback).toHaveBeenCalledExactlyOnceWith(i18n.t('history.failedToCopy')),
        )
        expect(screen.getByText('It turns speech into useful text.')).toBeInTheDocument()
        expect(screen.queryByText('Copied')).toBeNull()
      } finally {
        feedback.mockRestore()
      }
    },
  )

  it.each(['unmount', 'replacement'] as const)(
    'ignores a late embedded copy failure after %s',
    async (scenario) => {
      let rejectCopy: (error: Error) => void = () => {}
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: {
          writeText: vi.fn(
            () =>
              new Promise<void>((_, reject) => {
                rejectCopy = reject
              }),
          ),
        },
      })
      const feedback = vi.spyOn(toast, 'error').mockImplementation(() => {})
      try {
        const view = render(<AskPanel embedded />)
        fireEvent.click(screen.getByRole('button', { name: 'Record question' }))
        await waitFor(() => expect(startAskDictation).toHaveBeenCalledOnce())
        await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Stop and ask' })))
        fireEvent.click(screen.getByRole('button', { name: 'Copy answer' }))
        if (scenario === 'unmount') view.unmount()
        else {
          vi.mocked(stopAskDictation).mockResolvedValueOnce(askResult({ answer: 'New answer' }))
          fireEvent.click(screen.getByRole('button', { name: 'Record question' }))
          await waitFor(() => expect(startAskDictation).toHaveBeenCalledTimes(2))
          await act(async () =>
            fireEvent.click(screen.getByRole('button', { name: 'Stop and ask' })),
          )
        }
        await act(async () => rejectCopy(new Error('synthetic private clipboard detail')))
        expect(feedback).not.toHaveBeenCalled()
      } finally {
        feedback.mockRestore()
      }
    },
  )

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

  it.each(['button', 'native'] as const)(
    'does not restore a delayed pending answer after %s dismissal',
    async (dismissal) => {
      let finish!: (message: Awaited<ReturnType<typeof takePendingAskMessage>>) => void
      vi.mocked(takePendingAskMessage).mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve
          }),
      )
      render(<AskPanel />)
      await waitFor(() => expect(takePendingAskMessage).toHaveBeenCalledOnce())
      if (dismissal === 'native') act(() => tauriEventMock.emit('ask:close-requested'))
      else fireEvent.click(screen.getByRole('button', { name: 'Close' }))
      await act(async () =>
        finish({ kind: 'result', payload: askResult({ answer: 'Dismissed pending answer' }) }),
      )
      expect(screen.queryByText('Dismissed pending answer')).toBeNull()
      expect(tauriWindowMock.hide).toHaveBeenCalledOnce()
      act(() => tauriEventMock.emit('ask:result', askResult({ answer: 'Next live answer' })))
      expect(await screen.findByText('Next live answer')).toBeInTheDocument()
    },
  )

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

  it('keeps result and error listeners when optional native close registration fails', async () => {
    const releaseResult = vi.fn()
    const releaseError = vi.fn()
    let resultListener!: (event: { payload: unknown }) => void
    let errorListener!: (event: { payload: unknown }) => void
    tauriEventMock.listen
      .mockImplementationOnce((_event, callback) => {
        resultListener = callback
        return Promise.resolve(releaseResult)
      })
      .mockImplementationOnce((_event, callback) => {
        errorListener = callback
        return Promise.resolve(releaseError)
      })
      .mockRejectedValueOnce(new Error('Synthetic close registration failure'))
    const { unmount } = render(<AskPanel />)
    await waitFor(() => expect(takePendingAskMessage).toHaveBeenCalledOnce())
    expect(releaseResult).not.toHaveBeenCalled()
    expect(releaseError).not.toHaveBeenCalled()
    act(() => resultListener({ payload: askResult({ answer: 'Current answer' }) }))
    expect(await screen.findByText('Current answer')).toBeInTheDocument()
    act(() => errorListener({ payload: 'Current error' }))
    expect(await screen.findByText('Current error')).toBeInTheDocument()
    unmount()
    expect(releaseResult).toHaveBeenCalledOnce()
    expect(releaseError).toHaveBeenCalledOnce()
  })

  it('reads the pending answer without waiting for optional close registration', async () => {
    let finish!: (unlisten: () => void) => void
    const releaseClose = vi.fn()
    tauriEventMock.listen
      .mockResolvedValueOnce(vi.fn())
      .mockResolvedValueOnce(vi.fn())
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve
          }),
      )
    vi.mocked(takePendingAskMessage).mockResolvedValueOnce({
      kind: 'result',
      payload: askResult({ answer: 'Pending answer without close listener' }),
    })
    const { unmount } = render(<AskPanel />)
    expect(await screen.findByText('Pending answer without close listener')).toBeInTheDocument()
    unmount()
    await act(async () => finish(releaseClose))
    expect(releaseClose).toHaveBeenCalledOnce()
  })

  it('removes the native close listener on unmount', async () => {
    const { unmount } = render(<AskPanel />)
    await waitFor(() => expect(tauriEventMock.listeners.get('ask:close-requested')).toHaveLength(1))
    unmount()
    expect(tauriEventMock.listeners.get('ask:close-requested')).toHaveLength(0)
    act(() => tauriEventMock.emit('ask:close-requested'))
    expect(tauriWindowMock.hide).not.toHaveBeenCalled()
  })

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

  it('starts explicit Cloud search only after charge confirmation', async () => {
    const previousConfig = useAppStore.getState().config
    const previousAuth = useAuthStore.getState()
    useAppStore.getState().updateConfig({ llm_provider: 'cloud' })
    useAuthStore.setState({
      user: {
        id: 'synthetic',
        email: 'synthetic@example.invalid',
        name: null,
        emailVerified: true,
      },
      askWebSearchAccountId: 'synthetic',
      subscriptionRefreshState: 'fresh',
      subscriptionLastVerifiedAt: new Date().toISOString(),
      subscriptionRefreshLoading: false,
      askWebSearchCapability: {
        version: 1,
        outputTokenLimit: 1024,
        maxResults: 3,
        additionalCloudWords: 25,
        additionalLlmTokens: 100,
        selectedTextAllowed: false,
      },
    })
    try {
      render(<AskPanel embedded />)
      const start = screen.getByRole('button', { name: i18n.t('ask.webStart') })
      expect(start).toBeDisabled()
      expect(startAskDictation).not.toHaveBeenCalled()
      fireEvent.click(screen.getByRole('checkbox'))
      await act(async () => fireEvent.click(start))
      expect(startAskDictation).toHaveBeenCalledExactlyOnceWith(
        true,
        expect.objectContaining({ additionalCloudWords: 25, additionalLlmTokens: 100 }),
      )
    } finally {
      useAppStore.setState({ config: previousConfig })
      useAuthStore.setState(previousAuth)
    }
  })

  it('does not log raw link-opening errors or discard the answer', async () => {
    const address = 'https://example.com/docs?private=synthetic-value'
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(openUrl).mockRejectedValueOnce(new Error(`Unable to open ${address}`))
    try {
      render(
        <>
          <AskPanel />
          <ToastContainer />
        </>,
      )
      await waitFor(() =>
        expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function)),
      )
      act(() => tauriEventMock.emit('ask:result', askResult({ answer: `[Reference](${address})` })))
      fireEvent.click(await screen.findByRole('link', { name: 'Reference' }))
      await waitFor(() => expect(log).toHaveBeenCalledExactlyOnceWith('Failed to open link'))
      expect(openUrl).toHaveBeenCalledExactlyOnceWith(address)
      expect(screen.getByRole('link', { name: 'Reference' })).toBeInTheDocument()
      expect(await screen.findByText('Could not open the link. Try again.')).toBeInTheDocument()
    } finally {
      log.mockRestore()
    }
  })

  it('renders embedded answer formatting without interpreting raw HTML', async () => {
    const answer =
      '## Summary\n\n| Item | Value |\n| --- | --- |\n| A | 1 |\n\n- First\n- Second\n\n<img src=x onerror=alert(1)>'
    vi.mocked(stopAskDictation).mockResolvedValueOnce(askResult({ answer }))
    render(<AskPanel embedded />)
    fireEvent.click(screen.getByRole('button', { name: 'Record question' }))
    await waitFor(() => expect(startAskDictation).toHaveBeenCalledOnce())
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Stop and ask' })))
    expect(await screen.findByRole('heading', { name: 'Summary' })).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    expect(document.querySelector('img')).toBeNull()
  })

  it('keeps embedded copy as the original Markdown without adding sources', async () => {
    const answer = '## Summary\n\n**Synthetic answer**'
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    vi.mocked(stopAskDictation).mockResolvedValueOnce(
      askResult({
        answer,
        citations: [{ url: 'https://example.invalid/', title: 'Synthetic source' }],
      }),
    )
    render(<AskPanel embedded />)
    fireEvent.click(screen.getByRole('button', { name: 'Record question' }))
    await waitFor(() => expect(startAskDictation).toHaveBeenCalledOnce())
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Stop and ask' })))
    fireEvent.click(screen.getByRole('button', { name: 'Copy answer' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledExactlyOnceWith(answer))
    expect(screen.getByRole('link', { name: 'Synthetic source' })).toBeInTheDocument()
  })

  it.each(['unmount', 'replacement', 'dismiss'] as const)(
    'does not display a late link failure after %s',
    async (scenario) => {
      let rejectOpen: (error: Error) => void = () => {}
      vi.mocked(openUrl).mockReturnValueOnce(
        new Promise<void>((_, reject) => {
          rejectOpen = reject
        }),
      )
      const feedback = vi.spyOn(toast, 'error').mockImplementation(() => {})
      const log = vi.spyOn(console, 'error').mockImplementation(() => {})
      try {
        const view = render(<AskPanel />)
        await waitFor(() =>
          expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function)),
        )
        act(() =>
          tauriEventMock.emit(
            'ask:result',
            askResult({ answer: '[Reference](https://example.invalid/)' }),
          ),
        )
        fireEvent.click(await screen.findByRole('link', { name: 'Reference' }))
        if (scenario === 'unmount') view.unmount()
        if (scenario === 'replacement') {
          act(() => tauriEventMock.emit('ask:result', askResult({ answer: 'New answer' })))
        }
        if (scenario === 'dismiss') {
          fireEvent.click(screen.getByRole('button', { name: i18n.t('ask.close') }))
        }
        await act(async () => rejectOpen(new Error('synthetic private detail')))
        expect(feedback).not.toHaveBeenCalled()
      } finally {
        feedback.mockRestore()
        log.mockRestore()
      }
    },
  )

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

  it('keeps a popup-only draft answer copyable without claiming clipboard success', async () => {
    render(<AskPanel />)
    await waitFor(() => {
      expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function))
    })
    tauriEventMock.emit(
      'ask:result',
      askResult({
        question: 'draft a note',
        answer: 'Recoverable answer',
        intent: 'draft_insert',
        output: 'popupAnswer',
        requestedPlacement: 'insert_at_cursor',
        actualPlacement: 'popup_answer',
        fallbackReason: 'focus_restore_failed',
      }),
    )
    expect(
      await screen.findByText('Not inserted or copied; copy the answer manually'),
    ).toBeDefined()
    expect(screen.getByText('Recoverable answer')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Copy answer' })).toBeDefined()
    expect(screen.queryByText('Target changed; result copied')).toBeNull()
  })

  it('labels selection recovery and keeps captured text available for manual copying', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    render(<AskPanel />)
    await waitFor(() => {
      expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function))
    })
    tauriEventMock.emit(
      'ask:result',
      askResult({
        recovery: true,
        question: '',
        answer: 'Recovered transcript',
        selectedText: 'Captured selection',
        output: 'deliveryPrevented',
      }),
    )
    expect(
      await screen.findByText('Automatic output stopped; recover the text manually'),
    ).toBeDefined()
    expect(screen.getByText('Recovered transcript')).toBeDefined()
    expect(screen.getByText('Captured selection')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Copy question' }))
    expect(writeText).toHaveBeenCalledWith('Captured selection')
  })

  it('clears recovery text when closed without automatically copying it', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    render(<AskPanel />)
    await waitFor(() => {
      expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function))
    })
    tauriEventMock.emit(
      'ask:result',
      askResult({
        recovery: true,
        question: '',
        answer: 'Temporary recovered transcript',
        selectedText: 'Temporary captured selection',
        output: 'deliveryPrevented',
      }),
    )
    expect(await screen.findByText('Temporary captured selection')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    await waitFor(() => {
      expect(screen.queryByText('Temporary recovered transcript')).toBeNull()
      expect(screen.queryByText('Temporary captured selection')).toBeNull()
      expect(tauriWindowMock.hide).toHaveBeenCalled()
    })
    expect(writeText).not.toHaveBeenCalled()
  })

  it.each(['deliveryFailed', 'deliveryPrevented'] as const)(
    'retains a %s draft for manual recovery without a copy-success label',
    async (output) => {
      const writeText = vi.fn().mockResolvedValue(undefined)
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: { writeText },
      })
      render(<AskPanel />)
      await waitFor(() => {
        expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function))
      })
      tauriEventMock.emit(
        'ask:result',
        askResult({
          answer: 'Recoverable answer',
          intent: 'draft_insert',
          output,
          requestedPlacement: 'insert_at_cursor',
          actualPlacement: null,
          fallbackReason: 'output_failed',
        }),
      )
      expect(await screen.findByText('Recoverable answer')).toBeDefined()
      expect(
        screen.getByText(
          output === 'deliveryFailed'
            ? 'Not inserted or copied; copy the answer manually'
            : 'Automatic delivery prevented; answer retained',
        ),
      ).toBeDefined()
      expect(screen.getByRole('button', { name: 'Copy answer' })).toBeDefined()
      expect(screen.queryByText('Copied instead of inserting')).toBeNull()
      expect(screen.queryByText('Copied')).toBeNull()
      fireEvent.click(screen.getByRole('button', { name: 'Copy answer' }))
      expect(writeText).toHaveBeenCalledExactlyOnceWith('Recoverable answer')
      await waitFor(() => {
        expect(screen.getByRole('button', { name: 'Copy answer' }).getAttribute('title')).toBe(
          'Copied',
        )
      })
    },
  )

  it('never shows copy success when manual recovery of a failed draft also fails', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('Synthetic clipboard failure'))
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    render(<AskPanel />)
    await waitFor(() => {
      expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:result', expect.any(Function))
    })
    tauriEventMock.emit(
      'ask:result',
      askResult({
        answer: 'Recoverable answer',
        intent: 'draft_insert',
        output: 'deliveryFailed',
        requestedPlacement: 'insert_at_cursor',
        actualPlacement: null,
        fallbackReason: 'output_failed',
      }),
    )
    expect(await screen.findByText('Recoverable answer')).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Copy answer' }))
    await flushAsyncEffects()
    expect(writeText).toHaveBeenCalledExactlyOnceWith('Recoverable answer')
    expect(screen.queryByText('Copied')).toBeNull()
    expect(screen.getByRole('button', { name: 'Copy answer' }).getAttribute('title')).not.toBe(
      'Copied',
    )
    expect(screen.getByText('Recoverable answer')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Copy answer' })).toBeDefined()
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

  it('requests native target restoration when Escape dismisses the answer card', async () => {
    render(<AskPanel />)
    fireEvent.keyDown(window, { key: 'Escape' })
    await waitFor(() => expect(dismissAskWindow).toHaveBeenCalledOnce())
  })

  it('clears the displayed answer on a native window close request', async () => {
    render(<AskPanel />)
    await waitFor(() => expect(tauriEventMock.listeners.has('ask:result')).toBe(true))
    act(() => tauriEventMock.emit('ask:result', askResult()))
    expect(await screen.findByText('It turns speech into useful text.')).toBeInTheDocument()
    act(() => tauriEventMock.emit('ask:close-requested'))
    await waitFor(() => expect(screen.queryByText('It turns speech into useful text.')).toBeNull())
    expect(tauriWindowMock.hide).toHaveBeenCalled()
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

  it.each([
    'ask_web_search_unavailable',
    'ask_web_search_quote_changed',
    'ask_web_search_no_sources',
  ])('localizes reviewed web failures in the answer card: %s', async (code) => {
    render(<AskPanel />)
    await waitFor(() =>
      expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:error', expect.any(Function)),
    )
    await act(async () => tauriEventMock.emit('ask:error', code))
    expect(screen.getByText(i18n.t(`capsule.errors.${code}`))).toBeInTheDocument()
    expect(screen.queryByText(code)).toBeNull()
  })

  it('shows an incomplete-answer explanation without exposing provider details', async () => {
    render(<AskPanel />)
    await waitFor(() =>
      expect(tauriEventMock.listen).toHaveBeenCalledWith('ask:error', expect.any(Function)),
    )
    await act(async () => {
      tauriEventMock.emit('ask:error', 'LLM response was truncated')
    })
    expect(screen.getByText(i18n.t('capsule.errors.llm_answer_incomplete'))).toBeInTheDocument()
    expect(screen.queryByText('LLM response was truncated')).toBeNull()
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

  it('reports startup, recording and processing as busy until the answer finishes', async () => {
    let resolveStop!: (value: AskDictationResult) => void
    vi.mocked(stopAskDictation).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveStop = resolve
      }),
    )
    const busy = vi.fn()
    render(<AskPanel embedded onBusyChange={busy} />)
    expect(busy).toHaveBeenLastCalledWith(false)
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Record question' })))
    expect(busy).toHaveBeenLastCalledWith(true)
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Stop and ask' })))
    expect(busy).toHaveBeenLastCalledWith(true)
    await act(async () => resolveStop(askResult()))
    expect(busy).toHaveBeenLastCalledWith(false)
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
    expect(stopAskDictation).toHaveBeenCalledWith(true)
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
