import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAppStore } from '../../../stores/appStore'
import { useCapsuleAlertStore } from '../../../stores/capsuleAlertStore'
import { Capsule } from '../index'
import { openCapsuleSettings } from '../../../lib/capsuleNavigation'
import { recoverUnfinishedTake, retryTranscription } from '../../../lib/tauri'
import { openUrl } from '@tauri-apps/plugin-opener'

vi.mock('../../../lib/tauri', () => ({
  retryTranscription: vi.fn().mockResolvedValue('Recovered text'),
  recoverUnfinishedTake: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('../../../hooks/useCapsuleResize', () => ({
  useCapsuleResize: vi.fn(),
}))
vi.mock('../../../hooks/useCapsuleClickThrough', () => ({
  useCapsuleClickThrough: vi.fn(),
}))
vi.mock('../../../hooks/useUpdateEvents', () => ({
  useCapsuleUpdateNotice: vi.fn(),
}))
vi.mock('../../../hooks/useSceneShortcutNotice', () => ({
  useSceneShortcutNotice: vi.fn(),
}))
vi.mock('../../../lib/capsuleNavigation', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../lib/capsuleNavigation')>()),
  openCapsuleSettings: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@tauri-apps/plugin-opener', () => ({
  openUrl: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@tauri-apps/plugin-clipboard-manager', () => ({
  writeText: vi.fn(),
}))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: { language?: string }) =>
      options?.language ? `${key}:${options.language}` : key,
  }),
}))

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.clearAllMocks()
  useAppStore.setState(useAppStore.getInitialState())
  useCapsuleAlertStore.getState().dismiss()
})

describe('actionable capsule alerts', () => {
  it.each(['success', 'failure'] as const)(
    'ignores a retry result after its error was replaced and restored: %s',
    async (outcome) => {
      let finish!: (text: string) => void
      let fail!: (error: Error) => void
      vi.mocked(retryTranscription).mockImplementationOnce(
        () =>
          new Promise<string>((resolve, reject) => {
            finish = resolve
            fail = reject
          }),
      )
      useAppStore.setState({
        pipelineState: 'idle',
        pipelineError: 'Previous failure',
        pipelineErrorKey: 'stt_failed',
        retryAvailable: true,
      })
      render(<Capsule />)
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' }))
      await act(async () => {
        useAppStore.setState({ pipelineError: 'Replacement failure' })
        useAppStore.setState({ pipelineError: 'Previous failure' })
        if (outcome === 'success') finish('synthetic old text')
        else fail(new Error('Synthetic old failure'))
      })
      expect(useAppStore.getState().pipelineError).toBe('Previous failure')
      expect(useAppStore.getState().retryAvailable).toBe(true)
      expect(useCapsuleAlertStore.getState().alert).toBeNull()
    },
  )

  it.each(['complete', 'superseded', 'unmount'] as const)(
    'releases the temporary retry subscription on %s',
    async (outcome) => {
      let finish!: (text: string) => void
      vi.mocked(retryTranscription).mockImplementationOnce(
        () =>
          new Promise<string>((resolve) => {
            finish = resolve
          }),
      )
      useAppStore.setState({
        pipelineState: 'idle',
        pipelineError: 'Previous failure',
        pipelineErrorKey: 'stt_failed',
        retryAvailable: true,
      })
      const { unmount } = render(<Capsule />)
      const originalSubscribe = useAppStore.subscribe
      const release = vi.fn()
      const subscribe = vi.spyOn(useAppStore, 'subscribe').mockImplementation((listener) => {
        const unsubscribe = originalSubscribe(listener)
        return () => {
          release()
          unsubscribe()
        }
      })
      try {
        await act(async () => {
          fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' }))
        })
        expect(subscribe).toHaveBeenCalledOnce()
        expect(release).not.toHaveBeenCalled()
        if (outcome === 'complete') await act(async () => finish('synthetic text'))
        if (outcome === 'superseded') {
          act(() => useAppStore.setState({ pipelineState: 'recording' }))
        }
        if (outcome === 'unmount') unmount()
        expect(release).toHaveBeenCalledOnce()
        await act(async () => finish('synthetic cleanup'))
        expect(release).toHaveBeenCalledOnce()
      } finally {
        subscribe.mockRestore()
        unmount()
      }
    },
  )

  it.each(['success', 'failure'] as const)(
    'ignores an old retry result after a new recording returns to idle: %s',
    async (outcome) => {
      let finish!: (text: string) => void
      let fail!: (error: Error) => void
      vi.mocked(retryTranscription).mockImplementationOnce(
        () =>
          new Promise<string>((resolve, reject) => {
            finish = resolve
            fail = reject
          }),
      )
      useAppStore.setState({
        pipelineState: 'idle',
        pipelineError: 'Previous failure',
        pipelineErrorKey: 'stt_failed',
        retryAvailable: true,
      })
      render(<Capsule />)
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' }))
      await act(async () => {
        useAppStore.setState({ pipelineState: 'recording' })
        useAppStore.setState({ pipelineState: 'idle' })
        if (outcome === 'success') finish('synthetic old text')
        else fail(new Error('Synthetic old failure'))
      })
      expect(useAppStore.getState().pipelineError).toBe('Previous failure')
      expect(useAppStore.getState().retryAvailable).toBe(true)
      expect(useCapsuleAlertStore.getState().alert).toBeNull()
    },
  )

  it('does not let an old error timeout clear a replacement before rerender', () => {
    vi.useFakeTimers()
    useAppStore.setState({
      pipelineState: 'idle',
      pipelineError: 'Previous failure',
      pipelineErrorKey: 'microphone_unavailable',
    })
    render(<Capsule />)
    act(() => {
      useAppStore.setState({
        pipelineError: 'New failure',
        pipelineErrorKey: 'stt_timeout',
        failedTakeId: 8,
      })
      vi.advanceTimersByTime(10_000)
    })
    expect(useAppStore.getState().pipelineError).toBe('New failure')
    expect(useAppStore.getState().failedTakeId).toBe(8)
  })

  it('clears its own error normally after the existing timeout', () => {
    vi.useFakeTimers()
    useAppStore.setState({
      pipelineState: 'idle',
      pipelineError: 'Current failure',
      pipelineErrorKey: 'microphone_unavailable',
    })
    render(<Capsule />)
    act(() => vi.advanceTimersByTime(10_000))
    expect(useAppStore.getState().pipelineError).toBeNull()
  })

  it('allows retry-and-copy again after the previous request fails', async () => {
    vi.mocked(retryTranscription)
      .mockRejectedValueOnce(new Error('Synthetic transcription timeout'))
      .mockResolvedValueOnce('synthetic recovered text')
    useAppStore.setState({
      pipelineState: 'idle',
      pipelineError: 'Previous failure',
      pipelineErrorKey: 'stt_failed',
      retryAvailable: true,
    })
    render(<Capsule />)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' }))
    })
    expect(useAppStore.getState().retryAvailable).toBe(true)
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
    expect(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' })).toBeEnabled()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' }))
    })
    expect(retryTranscription).toHaveBeenCalledTimes(2)
    expect(useAppStore.getState().pipelineError).toBeNull()
    expect(useAppStore.getState().retryAvailable).toBe(false)
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('retry_copied')
  })

  it('preserves a newer error when an old take recovery fails', async () => {
    let fail!: (error: Error) => void
    vi.mocked(recoverUnfinishedTake).mockImplementationOnce(
      () =>
        new Promise<void>((_resolve, reject) => {
          fail = reject
        }),
    )
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      useAppStore.setState({
        pipelineState: 'idle',
        pipelineError: 'Previous failure',
        pipelineErrorKey: 'stt_failed',
        failedTakeId: 7,
      })
      render(<Capsule />)
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retry' }))
      await act(async () => {
        useAppStore.setState({
          pipelineError: 'New microphone error',
          pipelineErrorKey: 'microphone_unavailable',
          failedTakeId: 8,
        })
        fail(new Error('Synthetic old recovery failure'))
      })
      expect(useAppStore.getState().pipelineError).toBe('New microphone error')
      expect(useAppStore.getState().pipelineErrorKey).toBe('microphone_unavailable')
      expect(useAppStore.getState().failedTakeId).toBe(8)
    } finally {
      log.mockRestore()
    }
  })

  it('keeps normal retry-and-copy feedback after transcribing and idle events', async () => {
    let finish!: (text: string) => void
    vi.mocked(retryTranscription).mockImplementationOnce(
      () =>
        new Promise<string>((resolve) => {
          finish = resolve
        }),
    )
    useAppStore.setState({
      pipelineState: 'idle',
      pipelineError: 'Previous failure',
      pipelineErrorKey: 'stt_failed',
      retryAvailable: true,
    })
    render(<Capsule />)
    fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' }))
    await act(async () => {
      useAppStore.setState({ pipelineState: 'transcribing', retryAvailable: false })
    })
    await act(async () => {
      useAppStore.setState({ pipelineState: 'idle' })
      finish('synthetic recovered text')
    })
    expect(useAppStore.getState().pipelineError).toBeNull()
    expect(useAppStore.getState().retryAvailable).toBe(false)
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('retry_copied')
  })

  it.each(['success', 'failure'] as const)(
    'preserves a newer error when retry-and-copy ends with %s',
    async (outcome) => {
      let finish!: (text: string) => void
      let fail!: (error: Error) => void
      vi.mocked(retryTranscription).mockImplementationOnce(
        () =>
          new Promise<string>((resolve, reject) => {
            finish = resolve
            fail = reject
          }),
      )
      useAppStore.setState({
        pipelineState: 'idle',
        pipelineError: 'Previous failure',
        pipelineErrorKey: 'stt_failed',
        retryAvailable: true,
      })
      render(<Capsule />)
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' }))
      await act(async () => {
        useAppStore.setState({
          pipelineError: 'New microphone error',
          pipelineErrorKey: 'microphone_unavailable',
        })
        if (outcome === 'success') finish('synthetic old text')
        else fail(new Error('Synthetic old retry failure'))
      })
      expect(useAppStore.getState().pipelineError).toBe('New microphone error')
      expect(useAppStore.getState().pipelineErrorKey).toBe('microphone_unavailable')
      expect(useAppStore.getState().retryAvailable).toBe(true)
      expect(useCapsuleAlertStore.getState().alert).toBeNull()
    },
  )

  it.each(['recording', 'unavailable', 'replacement'] as const)(
    'does not submit an old retry-and-copy after %s arrives before rerender',
    async (change) => {
      useAppStore.setState({
        pipelineState: 'idle',
        pipelineError: 'Previous failure',
        pipelineErrorKey: 'stt_failed',
        retryAvailable: true,
      })
      render(<Capsule />)
      const retry = screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' })
      await act(async () => {
        useAppStore.setState({
          pipelineState: change === 'recording' ? 'recording' : 'idle',
          retryAvailable: change !== 'unavailable',
          pipelineError: 'New operation state',
        })
        fireEvent.click(retry)
      })
      expect(retryTranscription).not.toHaveBeenCalled()
      expect(useAppStore.getState().pipelineError).toBe('New operation state')
      expect(useCapsuleAlertStore.getState().alert).toBeNull()
    },
  )

  it('does not submit retry-and-copy twice before the button rerenders', async () => {
    let finish!: (text: string) => void
    vi.mocked(retryTranscription).mockImplementationOnce(
      () =>
        new Promise<string>((resolve) => {
          finish = resolve
        }),
    )
    useAppStore.setState({
      pipelineState: 'idle',
      pipelineError: 'Recognition failed',
      pipelineErrorKey: 'stt_failed',
      retryAvailable: true,
    })
    render(<Capsule />)
    const retry = screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' })
    await act(async () => {
      fireEvent.click(retry)
      fireEvent.click(retry)
    })
    expect(retryTranscription).toHaveBeenCalledTimes(1)
    await act(async () => finish('synthetic text'))
  })

  it.each(['replacement', 'recording'] as const)(
    'does not submit an old retry after a %s state arrives before rerender',
    async (change) => {
      useAppStore.setState({
        pipelineState: 'idle',
        pipelineError: 'Previous recognition failure',
        pipelineErrorKey: 'stt_failed',
        failedTakeId: 7,
      })
      render(<Capsule />)
      const retry = screen.getByRole('button', { name: 'capsule.alerts.retry' })
      await act(async () => {
        useAppStore.setState({
          pipelineState: change === 'recording' ? 'recording' : 'idle',
          pipelineError: 'New operation state',
          failedTakeId: change === 'replacement' ? 8 : 7,
        })
        fireEvent.click(retry)
      })
      expect(recoverUnfinishedTake).not.toHaveBeenCalled()
      expect(useAppStore.getState().pipelineError).toBe('New operation state')
      expect(useAppStore.getState().failedTakeId).toBe(change === 'replacement' ? 8 : 7)
      expect(useAppStore.getState().pipelineState).toBe(
        change === 'recording' ? 'recording' : 'idle',
      )
    },
  )

  it('does not recover the same failed take twice before the button rerenders', async () => {
    let finish!: () => void
    vi.mocked(recoverUnfinishedTake).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    useAppStore.setState({
      pipelineState: 'idle',
      pipelineError: 'Recognition failed',
      pipelineErrorKey: 'stt_failed',
      failedTakeId: 7,
    })
    render(<Capsule />)
    const retry = screen.getByRole('button', { name: 'capsule.alerts.retry' })
    await act(async () => {
      fireEvent.click(retry)
      fireEvent.click(retry)
    })
    expect(recoverUnfinishedTake).toHaveBeenCalledTimes(1)
    await act(async () => finish())
  })

  it.each(['retry', 'settings'] as const)(
    'does not log private failure details from the %s action',
    async (action) => {
      const log = vi.spyOn(console, 'error').mockImplementation(() => {})
      try {
        const failure = new Error('synthetic-private-history-token-and-path')
        useAppStore.setState({
          pipelineState: 'idle',
          pipelineError: 'Recognition failed',
          pipelineErrorKey: 'stt_failed',
          failedTakeId: action === 'retry' ? 7 : null,
        })
        if (action === 'retry') vi.mocked(recoverUnfinishedTake).mockRejectedValueOnce(failure)
        else vi.mocked(openCapsuleSettings).mockRejectedValueOnce(failure)
        render(<Capsule />)
        await act(async () => {
          fireEvent.click(
            screen.getByRole('button', {
              name: action === 'retry' ? 'capsule.alerts.retry' : 'capsule.menu.settings',
            }),
          )
        })
        expect(log).toHaveBeenCalledExactlyOnceWith(
          action === 'retry' ? 'Failed to retry failed take' : 'Failed to open capsule settings',
        )
      } finally {
        log.mockRestore()
      }
    },
  )

  it('opens the microphone dialog directly from a device recommendation', () => {
    useCapsuleAlertStore.getState().show('better_microphone', 'USB Mic')
    render(<Capsule />)
    fireEvent.click(screen.getByRole('button', { name: 'settings.microphone' }))
    expect(openCapsuleSettings).toHaveBeenCalledWith(
      expect.stringMatching(/^#\/settings\?pane=general&dialog=microphone&open=\d+$/),
    )
  })

  it('opens Speech Recognition privacy settings rather than microphone settings on macOS', () => {
    const platform = navigator.platform
    Object.defineProperty(navigator, 'platform', { value: 'MacIntel', configurable: true })
    try {
      useAppStore.setState({
        pipelineError: 'Speech Recognition permission required',
        pipelineErrorKey: 'speech_permission_required',
      })
      render(<Capsule />)
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.openSystemSettings' }))
      expect(openUrl).toHaveBeenCalledWith(
        'x-apple.systempreferences:com.apple.preference.security?Privacy_SpeechRecognition',
      )
      expect(openCapsuleSettings).not.toHaveBeenCalled()
    } finally {
      Object.defineProperty(navigator, 'platform', { value: platform, configurable: true })
    }
  })

  it('offers microphone selection when no input device is available', () => {
    useAppStore.setState({
      pipelineError: 'No microphone available',
      pipelineErrorKey: 'microphone_unavailable',
    })
    render(<Capsule />)
    fireEvent.click(screen.getByRole('button', { name: 'capsule.menu.settings' }))
    expect(openCapsuleSettings).toHaveBeenCalledWith('#/settings')
    expect(screen.queryByRole('button', { name: 'capsule.alerts.retryAndCopy' })).toBeNull()
  })

  it('does not offer a retry when no audio was retained', () => {
    useAppStore.setState({
      pipelineError: 'Recognition failed',
      pipelineErrorKey: 'stt_failed',
      retryAvailable: false,
    })
    render(<Capsule />)
    expect(screen.queryByRole('button', { name: 'capsule.alerts.retryAndCopy' })).toBeNull()
  })

  it('does not overwrite a new recording when a stale retry result arrives', async () => {
    let resolveRetry: (text: string) => void = () => {}
    vi.mocked(retryTranscription).mockImplementationOnce(
      () =>
        new Promise<string>((resolve) => {
          resolveRetry = resolve
        }),
    )
    useAppStore.setState({
      pipelineError: 'Recognition failed',
      pipelineErrorKey: 'stt_failed',
      retryAvailable: true,
    })
    render(<Capsule />)
    fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' }))
    await act(async () => {
      useAppStore.getState().setPipelineState('recording')
      useAppStore.getState().setPipelineError(null)
      resolveRetry('Old recovered text')
    })
    expect(useAppStore.getState().pipelineState).toBe('recording')
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('offers retry only when the backend has retained failed audio', async () => {
    useAppStore.setState({
      pipelineError: 'Recognition failed',
      pipelineErrorKey: 'stt_failed',
      retryAvailable: true,
    })
    render(<Capsule />)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' }))
    })
    expect(retryTranscription).toHaveBeenCalledTimes(1)
    expect(useAppStore.getState().pipelineError).toBeNull()
    expect(useAppStore.getState().retryAvailable).toBe(false)
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('retry_copied')
  })

  it('shows the Typeless timeout alert and retries the stored take in full', async () => {
    useAppStore.setState({
      pipelineError: 'STT timed out',
      pipelineErrorKey: 'stt_timeout',
      failedTakeId: 9,
      retryAvailable: true,
    })
    render(<Capsule />)
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('capsule.alerts.unstableNetworkTitle')
    expect(alert).toHaveTextContent('capsule.alerts.retryToContinue')
    expect(screen.queryByRole('button', { name: 'capsule.alerts.retryAndCopy' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'capsule.menu.settings' })).toBeNull()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retry' }))
    })
    expect(recoverUnfinishedTake).toHaveBeenCalledWith(9)
    expect(retryTranscription).not.toHaveBeenCalled()
    expect(useAppStore.getState().pipelineError).toBeNull()
    expect(useAppStore.getState().failedTakeId).toBeNull()
  })

  it('titles each retryable failure like Typeless', () => {
    const cases = [
      ['stt_connection_failed', 'capsule.alerts.poorNetworkTitle'],
      ['stt_failed', 'capsule.alerts.somethingWrongTitle'],
    ] as const
    for (const [key, title] of cases) {
      useAppStore.setState({ pipelineError: 'x', pipelineErrorKey: key, failedTakeId: 2 })
      const { unmount } = render(<Capsule />)
      expect(screen.getByRole('alert')).toHaveTextContent(title)
      unmount()
    }
  })

  it('surfaces a failed retry as a new error and keeps Retry off while busy', async () => {
    vi.mocked(recoverUnfinishedTake).mockRejectedValueOnce(
      'Choose Apple Speech or a file-based Whisper provider before retrying history',
    )
    useAppStore.setState({
      pipelineError: 'Recognition failed',
      pipelineErrorKey: 'stt_failed',
      failedTakeId: 3,
      pipelineState: 'transcribing',
    })
    render(<Capsule />)
    expect(screen.getByRole('button', { name: 'capsule.alerts.retry' })).toBeDisabled()
    act(() => useAppStore.setState({ pipelineState: 'idle' }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retry' }))
    })
    expect(useAppStore.getState().pipelineError).toBe('history.retryUnsupported')
    expect(screen.getByRole('alert')).toHaveTextContent('history.retryUnsupported')
    expect(screen.queryByRole('button', { name: 'capsule.alerts.retry' })).toBeNull()
  })

  it('does not offer a take retry for configuration errors', () => {
    useAppStore.setState({
      pipelineError: 'Set up STT',
      pipelineErrorKey: 'stt_not_configured',
      failedTakeId: 4,
    })
    render(<Capsule />)
    expect(screen.queryByRole('button', { name: 'capsule.alerts.retry' })).toBeNull()
    expect(screen.getByRole('button', { name: 'capsule.menu.settings' })).toBeInTheDocument()
  })

  it('explains a take that reached its length limit for five seconds', () => {
    vi.useFakeTimers()
    useCapsuleAlertStore.getState().show('input_duration_exceeded', '10 min')
    render(<Capsule />)
    expect(screen.getByRole('status')).toHaveTextContent('capsule.alerts.sessionEndedTitle')
    expect(screen.getByRole('status')).toHaveTextContent('capsule.alerts.sessionEndedDesc')
    act(() => vi.advanceTimersByTime(5000))
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('opens the account pane for a missing cloud login', () => {
    useAppStore.setState({
      pipelineError: 'Sign in first',
      pipelineErrorKey: 'authentication_required',
    })
    render(<Capsule />)
    fireEvent.click(screen.getByRole('button', { name: 'nav.account' }))
    expect(openCapsuleSettings).toHaveBeenCalledWith('#/settings?pane=account')
    fireEvent.click(screen.getByRole('button', { name: 'common.close' }))
    expect(useAppStore.getState().pipelineError).toBeNull()
    expect(useAppStore.getState().pipelineErrorKey).toBeNull()
  })

  it('keeps a classified error readable for ten seconds', () => {
    vi.useFakeTimers()
    useAppStore.setState({ pipelineError: 'Set up STT', pipelineErrorKey: 'stt_not_configured' })
    render(<Capsule />)
    act(() => vi.advanceTimersByTime(3000))
    expect(screen.getByRole('alert')).toHaveTextContent('Set up STT')
    fireEvent.click(screen.getByRole('button', { name: 'capsule.menu.settings' }))
    expect(openCapsuleSettings).toHaveBeenCalledWith('#/models')
    act(() => vi.advanceTimersByTime(7000))
    expect(useAppStore.getState().pipelineError).toBeNull()
  })

  it('renders the translation target throughout recording', () => {
    vi.useFakeTimers()
    useAppStore.setState((state) => ({
      pipelineState: 'recording',
      activeVoiceMode: 'translate',
      config: {
        ...state.config,
        translation: { targets: ['ja'], active_target: 'ja' },
      },
    }))
    useCapsuleAlertStore.getState().show('translating_to')
    render(<Capsule />)
    expect(screen.getByRole('status')).toHaveTextContent('capsule.alerts.translationLabel日本語')
    act(() => vi.advanceTimersByTime(60000))
    expect(screen.getByRole('status')).toHaveTextContent('日本語')
  })
})
