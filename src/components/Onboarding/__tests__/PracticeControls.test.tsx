import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PracticeExercise, QuickTestStep } from '../QuickTestStep'
import i18n from '../../../i18n'
import { UI_LANGUAGES } from '../../../lib/constants'

const mocks = vi.hoisted(() => ({
  invoke: vi.fn(),
  channels: [] as Array<{ onmessage?: (payload: unknown) => void }>,
}))
vi.mock('@tauri-apps/api/core', () => ({
  invoke: mocks.invoke,
  Channel: class {
    onmessage?: (payload: unknown) => void
    constructor() {
      mocks.channels.push(this)
    }
  },
}))

beforeEach(() => {
  mocks.invoke.mockReset().mockResolvedValue(undefined)
})

afterEach(async () => {
  cleanup()
  mocks.invoke.mockReset()
  mocks.channels.length = 0
  await i18n.changeLanguage('en')
})

describe('practice controls', () => {
  it('reports a changed exercise target without changing the global target', async () => {
    const { useAppStore } = await import('../../../stores/appStore')
    const previous = useAppStore.getState().config.translation.active_target
    const onTargetLanguageChange = vi.fn()
    render(
      <PracticeExercise
        mode="translate"
        initialTargetLanguage="en"
        onTargetLanguageChange={onTargetLanguageChange}
      />,
    )
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'fr' } })
    expect(onTargetLanguageChange).toHaveBeenCalledExactlyOnceWith('fr')
    expect(useAppStore.getState().config.translation.active_target).toBe(previous)
  })

  it('does not cancel a completed Ask result when the old long-press timer expires', async () => {
    const { useAppStore } = await import('../../../stores/appStore')
    const previous = useAppStore.getState().config
    useAppStore.getState().updateConfig({ hotkey_mode: 'auto' })
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'fast-ask'
      if (command === 'get_practice')
        return {
          session: 'fast-ask',
          phase: 'complete',
          rawText: 'Synthetic question',
          polishPreview: '',
          result: 'Completed answer',
          level: 0,
          error: null,
        }
    })
    vi.useFakeTimers()
    try {
      render(<PracticeExercise mode="ask" />)
      await act(async () => {})
      const registration = mocks.invoke.mock.calls.find(
        ([command]) => command === 'register_practice_shortcut',
      )![1]
      const channel = mocks.channels[0]
      await act(async () => channel.onmessage?.({ leaseId: registration.leaseId, pressed: true }))
      expect(screen.getByText('Completed answer')).toBeInTheDocument()
      await act(async () => vi.advanceTimersByTime(600))
      act(() => channel.onmessage?.({ leaseId: registration.leaseId, pressed: false }))
      expect(screen.getByText('Completed answer')).toBeInTheDocument()
      expect(mocks.invoke.mock.calls.some(([command]) => command === 'cancel_practice')).toBe(false)
    } finally {
      cleanup()
      vi.useRealTimers()
      useAppStore.getState().setConfig(previous)
    }
  })

  it('cleans a late Ask startup after auto long-press cancellation without polling it', async () => {
    const { useAppStore } = await import('../../../stores/appStore')
    const previous = useAppStore.getState().config
    useAppStore.getState().updateConfig({ hotkey_mode: 'auto' })
    let finishStart!: (session: string) => void
    mocks.invoke.mockImplementation((command: string) => {
      if (command === 'start_practice') {
        return new Promise<string>((resolve) => {
          finishStart = resolve
        })
      }
      return Promise.resolve()
    })
    vi.useFakeTimers()
    try {
      render(<PracticeExercise mode="ask" />)
      await act(async () => {})
      const registration = mocks.invoke.mock.calls.find(
        ([command]) => command === 'register_practice_shortcut',
      )![1]
      const channel = mocks.channels[0]
      await act(async () => channel.onmessage?.({ leaseId: registration.leaseId, pressed: true }))
      await act(async () => vi.advanceTimersByTime(600))
      expect(screen.getByText(i18n.t('settings.autoPressModeDesc'))).toBeInTheDocument()
      await act(async () => finishStart('late-ask-session'))
      expect(mocks.invoke).toHaveBeenCalledWith('cancel_practice', { session: 'late-ask-session' })
      expect(mocks.invoke.mock.calls.some(([command]) => command === 'get_practice')).toBe(false)
      act(() => channel.onmessage?.({ leaseId: registration.leaseId, pressed: false }))
      expect(screen.queryByText(i18n.t('settings.autoPressModeDesc'))).toBeNull()
    } finally {
      cleanup()
      vi.useRealTimers()
      useAppStore.getState().setConfig(previous)
    }
  })

  it('shows the auto guide while held and removes it on release without starting dictation', async () => {
    const { useAppStore } = await import('../../../stores/appStore')
    const previous = useAppStore.getState().config
    useAppStore.getState().updateConfig({ hotkey_mode: 'auto' })
    vi.useFakeTimers()
    try {
      render(<PracticeExercise mode="dictate" />)
      await act(async () => {})
      const registration = mocks.invoke.mock.calls.find(
        ([command]) => command === 'register_practice_shortcut',
      )![1]
      const channel = mocks.channels[0]
      act(() => channel.onmessage?.({ leaseId: registration.leaseId, pressed: true }))
      await act(async () => vi.advanceTimersByTime(599))
      expect(screen.queryByText(i18n.t('settings.autoPressModeDesc'))).toBeNull()
      await act(async () => vi.advanceTimersByTime(1))
      expect(screen.getByText(i18n.t('settings.autoPressModeDesc'))).toBeInTheDocument()
      act(() => channel.onmessage?.({ leaseId: registration.leaseId, pressed: false }))
      expect(screen.queryByText(i18n.t('settings.autoPressModeDesc'))).toBeNull()
      expect(mocks.invoke.mock.calls.some(([command]) => command === 'start_practice')).toBe(false)
    } finally {
      cleanup()
      vi.useRealTimers()
      useAppStore.getState().setConfig(previous)
    }
  })

  it('starts auto dictation on a short release, not on key down or a long hold', async () => {
    const { useAppStore } = await import('../../../stores/appStore')
    const previous = useAppStore.getState().config
    useAppStore.getState().updateConfig({ hotkey_mode: 'auto' })
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'auto-session'
      if (command === 'get_practice')
        return {
          session: 'auto-session',
          phase: 'recording',
          rawText: '',
          polishPreview: '',
          result: '',
          level: 0,
          error: null,
        }
    })
    const clock = vi.spyOn(Date, 'now')
    try {
      render(<PracticeExercise mode="dictate" />)
      await act(async () => {})
      const registration = mocks.invoke.mock.calls.find(
        ([command]) => command === 'register_practice_shortcut',
      )![1]
      const channel = mocks.channels[0]
      clock.mockReturnValue(1000)
      act(() => channel.onmessage?.({ leaseId: registration.leaseId, pressed: true }))
      expect(mocks.invoke.mock.calls.some(([command]) => command === 'start_practice')).toBe(false)
      clock.mockReturnValue(1700)
      act(() => channel.onmessage?.({ leaseId: registration.leaseId, pressed: false }))
      expect(mocks.invoke.mock.calls.some(([command]) => command === 'start_practice')).toBe(false)
      clock.mockReturnValue(2000)
      act(() => channel.onmessage?.({ leaseId: registration.leaseId, pressed: true }))
      clock.mockReturnValue(2100)
      await act(async () => channel.onmessage?.({ leaseId: registration.leaseId, pressed: false }))
      expect(mocks.invoke).toHaveBeenCalledWith('start_practice', { options: { mode: 'dictate' } })
    } finally {
      cleanup()
      clock.mockRestore()
      useAppStore.getState().setConfig(previous)
    }
  })

  it.each(['target', 'selection'] as const)(
    'invalidates a completed translation when its %s changes',
    async (field) => {
      const onReset = vi.fn()
      const onCompleted = vi.fn()
      mocks.invoke.mockImplementation(async (command: string) => {
        if (command === 'start_practice') return 'completed-translation'
        if (command === 'get_practice')
          return {
            session: 'completed-translation',
            phase: 'complete',
            rawText: 'Translate this',
            polishPreview: '',
            result: 'Old translated result',
            level: 0,
            error: null,
          }
      })
      render(
        <PracticeExercise
          mode="ask"
          askAction="translate"
          initialSelectedText="Original context"
          onCompleted={onCompleted}
          onReset={onReset}
        />,
      )
      fireEvent.click(screen.getByRole('button', { name: i18n.t('home.modes.ask.title') }))
      await screen.findByText('Old translated result')
      await waitFor(() => expect(onCompleted).toHaveBeenCalled())
      onReset.mockClear()
      fireEvent.change(screen.getByRole(field === 'target' ? 'combobox' : 'textbox'), {
        target: { value: field === 'target' ? 'fr' : 'Changed context' },
      })
      expect(onReset).toHaveBeenCalledOnce()
      await waitFor(() => expect(screen.queryByText('Old translated result')).toBeNull())
    },
  )

  it('uses only the page-provided context for an explicit rewrite exercise', async () => {
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'rewrite-session'
      if (command === 'get_practice')
        return {
          session: 'rewrite-session',
          phase: 'complete',
          rawText: 'Rewrite this',
          polishPreview: '',
          result: 'Synthetic rewritten text',
          level: 0,
          error: null,
        }
    })
    render(
      <PracticeExercise
        mode="ask"
        askAction="rewrite"
        initialSelectedText="Synthetic page context"
      />,
    )
    expect(screen.getByRole('textbox')).toHaveValue('Synthetic page context')
    fireEvent.click(screen.getByRole('button', { name: i18n.t('home.modes.ask.title') }))
    await screen.findByText('Synthetic rewritten text')
    expect(mocks.invoke).toHaveBeenCalledWith('start_practice', {
      options: {
        mode: 'ask',
        askAction: 'rewrite',
        selectedText: 'Synthetic page context',
      },
    })
  })

  it('sends the explicit selected-text translation action and target to isolated practice', async () => {
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'ask-translation'
      if (command === 'get_practice')
        return {
          session: 'ask-translation',
          phase: 'complete',
          rawText: 'Translate this',
          polishPreview: '',
          result: 'Synthetic translation',
          level: 0,
          error: null,
        }
    })
    render(<PracticeExercise mode="ask" askAction="translate" />)
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Synthetic context' } })
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'fr' } })
    fireEvent.click(screen.getByRole('button', { name: i18n.t('home.modes.ask.title') }))
    await screen.findByText('Synthetic translation')
    expect(mocks.invoke).toHaveBeenCalledWith('start_practice', {
      options: {
        mode: 'ask',
        askAction: 'translate',
        targetLanguage: 'fr',
        selectedText: 'Synthetic context',
      },
    })
  })

  it('publishes actual practice output and clears it when the exercise unmounts', async () => {
    const onProgress = vi.fn()
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'surface-session'
      if (command === 'get_practice')
        return {
          session: 'surface-session',
          phase: 'complete',
          rawText: 'Synthetic speech',
          polishPreview: '',
          result: 'Synthetic final result',
          level: 0,
          error: null,
        }
    })
    const { unmount } = render(<PracticeExercise mode="dictate" onProgress={onProgress} />)
    expect(onProgress).toHaveBeenLastCalledWith('', false)
    fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
    await waitFor(() => expect(onProgress).toHaveBeenLastCalledWith('Synthetic final result', true))
    unmount()
    expect(onProgress).toHaveBeenLastCalledWith('', false)
  })

  it('can render a translation exercise initially without starting a session', () => {
    render(<QuickTestStep initialMode="translate" />)
    expect(screen.getByRole('combobox')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: i18n.t('home.modes.translate.title') })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(mocks.invoke.mock.calls.some(([command]) => command === 'start_practice')).toBe(false)
  })

  it('keeps Ask practice recording after hold release and stops on the next press', async () => {
    const { useAppStore } = await import('../../../stores/appStore')
    const previous = useAppStore.getState().config
    useAppStore.getState().updateConfig({ hotkey_mode: 'hold' })
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'ask-hold-practice'
      if (command === 'get_practice')
        return {
          session: 'ask-hold-practice',
          phase: 'recording',
          rawText: '',
          polishPreview: '',
          result: '',
          level: 0,
          error: null,
        }
    })
    try {
      render(<QuickTestStep />)
      fireEvent.click(screen.getByRole('tab', { name: i18n.t('home.modes.ask.title') }))
      await act(async () => {})
      const registration = [...mocks.invoke.mock.calls]
        .reverse()
        .find(([command]) => command === 'register_practice_shortcut')![1]
      const channel = mocks.channels[mocks.channels.length - 1]
      await act(async () => channel.onmessage?.({ leaseId: registration.leaseId, pressed: true }))
      await waitFor(() => expect(screen.getByRole('meter')).toBeInTheDocument())
      await act(async () => channel.onmessage?.({ leaseId: registration.leaseId, pressed: false }))
      expect(mocks.invoke.mock.calls.some(([command]) => command === 'stop_practice')).toBe(false)
      await act(async () => channel.onmessage?.({ leaseId: registration.leaseId, pressed: true }))
      expect(mocks.invoke).toHaveBeenCalledWith('stop_practice', { session: 'ask-hold-practice' })
      expect(screen.getByText(i18n.t('onboarding.test.toStartStop'))).toBeInTheDocument()
    } finally {
      useAppStore.getState().setConfig(previous)
    }
  })

  it('routes native hold shortcut edges into isolated practice start and stop', async () => {
    const { useAppStore } = await import('../../../stores/appStore')
    const previous = useAppStore.getState().config
    useAppStore.getState().updateConfig({ hotkey_mode: 'hold' })
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'native-practice'
      if (command === 'get_practice')
        return {
          session: 'native-practice',
          phase: 'recording',
          rawText: '',
          polishPreview: '',
          result: '',
          level: 0,
          error: null,
        }
    })
    try {
      render(<QuickTestStep guided />)
      await act(async () => {})
      const registration = mocks.invoke.mock.calls.find(
        ([command]) => command === 'register_practice_shortcut',
      )![1]
      const channel = mocks.channels[mocks.channels.length - 1]
      await act(async () => channel.onmessage?.({ leaseId: registration.leaseId, pressed: true }))
      await waitFor(() => expect(screen.getByRole('meter')).toBeInTheDocument())
      await act(async () => channel.onmessage?.({ leaseId: registration.leaseId, pressed: false }))
      expect(mocks.invoke).toHaveBeenCalledWith('start_practice', {
        options: { mode: 'dictate' },
      })
      expect(mocks.invoke).toHaveBeenCalledWith('stop_practice', { session: 'native-practice' })
      expect(mocks.invoke.mock.calls.some(([command]) => command === 'start_recording')).toBe(false)
    } finally {
      cleanup()
      useAppStore.getState().setConfig(previous)
    }
  })

  it('locks guided progression again when a completed exercise is restarted or cancelled', async () => {
    let starts = 0
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return `guided-${++starts}`
      if (command === 'get_practice')
        return {
          session: `guided-${starts}`,
          phase: starts === 1 ? 'complete' : 'recording',
          rawText: '',
          polishPreview: '',
          result: starts === 1 ? 'Completed result' : '',
          level: 0,
          error: null,
        }
    })
    render(<QuickTestStep guided />)
    fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
    await waitFor(() =>
      expect(screen.getByRole('button', { name: i18n.t('onboarding.layout.next') })).toBeEnabled(),
    )
    fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
    expect(screen.getByRole('button', { name: i18n.t('onboarding.layout.next') })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.cancel') }))
    expect(screen.getByRole('button', { name: i18n.t('onboarding.layout.next') })).toBeDisabled()
  })

  it('unlocks the next guided exercise only after a real completed result', async () => {
    const onComplete = vi.fn()
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'guided-session'
      if (command === 'get_practice')
        return {
          session: 'guided-session',
          phase: 'complete',
          rawText: 'Synthetic speech',
          polishPreview: '',
          result: 'Synthetic result',
          level: 0,
          error: null,
        }
    })
    render(<QuickTestStep guided onComplete={onComplete} />)
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: i18n.t('onboarding.layout.next') })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
    expect(await screen.findByText('Synthetic result')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByRole('button', { name: i18n.t('onboarding.layout.next') })).toBeEnabled(),
    )
    expect(onComplete).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: i18n.t('onboarding.layout.next') }))
    expect(screen.getByRole('combobox')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: i18n.t('onboarding.layout.next') })).toBeDisabled()
  })

  it('starts a real translation practice with a selected target without changing global settings', async () => {
    const { useAppStore } = await import('../../../stores/appStore')
    const previous = useAppStore.getState().config.translation
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'translation-session'
      if (command === 'get_practice')
        return {
          session: 'translation-session',
          phase: 'complete',
          rawText: 'Hello',
          polishPreview: '',
          result: 'Bonjour',
          level: 0,
          error: null,
        }
    })
    render(<QuickTestStep />)
    fireEvent.click(screen.getByRole('tab', { name: i18n.t('home.modes.translate.title') }))
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'fr' } })
    fireEvent.click(screen.getByRole('button', { name: i18n.t('home.modes.translate.title') }))
    expect(await screen.findByText('Bonjour')).toBeInTheDocument()
    expect(mocks.invoke).toHaveBeenCalledWith('start_practice', {
      options: { mode: 'translate', targetLanguage: 'fr' },
    })
    expect(useAppStore.getState().config.translation).toEqual(previous)
  })

  it('passes only the user-provided practice text to the Ask practice', async () => {
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'ask-session'
      if (command === 'get_practice')
        return {
          session: 'ask-session',
          phase: 'complete',
          rawText: 'Summarize this',
          polishPreview: '',
          result: 'A short summary',
          level: 0,
          error: null,
        }
    })
    render(<QuickTestStep />)
    fireEvent.click(screen.getByRole('tab', { name: i18n.t('home.modes.ask.title') }))
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Synthetic selected text' } })
    fireEvent.click(screen.getByRole('button', { name: i18n.t('home.modes.ask.title') }))
    expect(await screen.findByText('A short summary')).toBeInTheDocument()
    expect(mocks.invoke).toHaveBeenCalledWith('start_practice', {
      options: { mode: 'ask', selectedText: 'Synthetic selected text' },
    })
  })

  it.each(UI_LANGUAGES)(
    'shows missing speech credentials as a setup requirement in $label',
    async (language) => {
      await i18n.changeLanguage(language.value)
      mocks.invoke.mockImplementation(async (command: string) => {
        if (command === 'start_practice') return 'test-session'
        if (command === 'get_practice')
          return {
            session: 'test-session',
            phase: 'error',
            rawText: '',
            polishPreview: '',
            result: '',
            level: 0,
            error: {
              code: 'stt_not_configured',
              details: 'synthetic-private-credential-detail',
              retry_count: 0,
            },
          }
      })
      render(<QuickTestStep />)
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
      })
      expect(
        await screen.findByText(i18n.t('capsule.errors.stt_not_configured')),
      ).toBeInTheDocument()
      expect(screen.queryByText(/synthetic-private-credential-detail/)).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: i18n.t('practice.start') })).toBeEnabled()
    },
  )

  it.each(UI_LANGUAGES)(
    'shows an expired cloud session as a login requirement in $label',
    async (language) => {
      await i18n.changeLanguage(language.value)
      mocks.invoke.mockImplementation(async (command: string) => {
        if (command === 'start_practice') return 'test-session'
        if (command === 'get_practice')
          return {
            session: 'test-session',
            phase: 'error',
            rawText: '',
            polishPreview: '',
            result: '',
            level: 0,
            error: {
              code: 'authentication_required',
              details: 'synthetic-private-session-detail',
              retry_count: 0,
            },
          }
      })
      render(<QuickTestStep />)
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
      })
      expect(
        await screen.findByText(i18n.t('capsule.errors.authentication_required')),
      ).toBeInTheDocument()
      expect(screen.queryByText(/synthetic-private-session-detail/)).not.toBeInTheDocument()
      expect(screen.queryByText(i18n.t('capsule.errors.stt_failed'))).not.toBeInTheDocument()
    },
  )

  it.each(
    UI_LANGUAGES.flatMap((language) =>
      ['microphone_permission_required', 'stt_no_speech_detected'].map((code) => ({
        language: language.value,
        label: language.label,
        code,
      })),
    ),
  )(
    'shows the native $code reason without private details in $label',
    async ({ language, code }) => {
      await i18n.changeLanguage(language)
      mocks.invoke.mockImplementation(async (command: string) => {
        if (command === 'start_practice') return 'test-session'
        if (command === 'get_practice')
          return {
            session: 'test-session',
            phase: 'error',
            rawText: '',
            polishPreview: '',
            result: '',
            level: 0,
            error: {
              code,
              details: 'synthetic-private-device-detail',
              retry_count: 0,
            },
          }
      })
      render(<QuickTestStep />)
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
      })
      expect(await screen.findByText(i18n.t(`capsule.errors.${code}`))).toBeInTheDocument()
      expect(screen.queryByText(/synthetic-private-device-detail/)).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: i18n.t('practice.start') })).toBeEnabled()
    },
  )

  it('disables stop while the request is pending without disabling cancel', async () => {
    let finishStop!: () => void
    mocks.invoke.mockImplementation((command: string) => {
      if (command === 'start_practice') return Promise.resolve('test-session')
      if (command === 'get_practice')
        return Promise.resolve({
          session: 'test-session',
          phase: 'recording',
          rawText: '',
          polishPreview: '',
          result: '',
          level: 0.5,
          error: null,
        })
      if (command === 'stop_practice')
        return new Promise<void>((resolve) => {
          finishStop = resolve
        })
      return Promise.resolve()
    })
    render(<QuickTestStep />)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
    })
    await waitFor(() =>
      expect(screen.getByRole('button', { name: i18n.t('practice.stop') })).toBeEnabled(),
    )
    fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.stop') }))
    expect(screen.getByRole('button', { name: i18n.t('practice.stop') })).toBeDisabled()
    expect(screen.getByRole('button', { name: i18n.t('practice.cancel') })).toBeEnabled()
    await act(async () => {
      finishStop()
    })
  })
  it.each(UI_LANGUAGES)(
    'renders native cancellation without a raw key in $label',
    async (language) => {
      await i18n.changeLanguage(language.value)
      mocks.invoke.mockImplementation(async (command: string) => {
        if (command === 'start_practice') return 'test-session'
        if (command === 'get_practice')
          return {
            session: 'test-session',
            phase: 'cancelled',
            rawText: '',
            polishPreview: '',
            result: '',
            level: 0,
            error: null,
          }
      })
      render(<QuickTestStep />)
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
      })
      await waitFor(() =>
        expect(screen.getByText(i18n.t('practice.cancel'), { selector: 'p' })).toBeInTheDocument(),
      )
      expect(screen.queryByText('practice.cancelled')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: i18n.t('practice.start') })).toBeEnabled()
    },
  )
  it('shows streamed polish as a preview without claiming completion', async () => {
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'test-session'
      if (command === 'get_practice')
        return {
          session: 'test-session',
          phase: 'polishing',
          rawText: 'raw speech',
          polishPreview: 'Edited preview',
          result: '',
          level: 0,
          error: null,
        }
    })
    render(<QuickTestStep />)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
    })
    await waitFor(() => expect(screen.getByText('Edited preview')).toBeInTheDocument())
    expect(screen.queryByText(i18n.t('onboarding.test.result'))).not.toBeInTheDocument()
    expect(screen.getByText(i18n.t('onboarding.test.polished'))).toBeInTheDocument()
  })
  it.each(['recording', 'transcribing', 'error'])(
    'labels %s text as a preview rather than a completed result',
    async (phase) => {
      mocks.invoke.mockImplementation(async (command: string) => {
        if (command === 'start_practice') return 'test-session'
        if (command === 'get_practice')
          return {
            session: 'test-session',
            phase,
            rawText: 'spoken preview',
            result: '',
            level: 0.5,
            error: null,
          }
      })
      render(<QuickTestStep />)
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
      })
      await waitFor(() => expect(screen.getByText('spoken preview')).toBeInTheDocument())
      expect(screen.queryByText(i18n.t('onboarding.test.result'))).not.toBeInTheDocument()
      expect(screen.getByText(i18n.t('onboarding.test.transcribing'))).toBeInTheDocument()
    },
  )
  it.each(UI_LANGUAGES)(
    'shows consent and controls without recording in $label',
    async (language) => {
      await i18n.changeLanguage(language.value)
      render(<QuickTestStep />)
      expect(screen.getByRole('button', { name: i18n.t('practice.start') })).toBeInTheDocument()
      expect(screen.getByText(i18n.t('practice.notice'))).toBeInTheDocument()
      expect(
        mocks.invoke.mock.calls.some(([command]) =>
          ['start_practice', 'start_recording', 'start_ask_flow'].includes(command),
        ),
      ).toBe(false)
    },
  )

  it('shows the current result in the existing area and keeps it after completion', async () => {
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'test-session'
      if (command === 'get_practice')
        return {
          session: 'test-session',
          phase: 'complete',
          rawText: 'spoken words',
          result: 'Spoken words.',
          level: 0,
          error: null,
        }
    })
    render(<QuickTestStep />)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: i18n.t('practice.start') }))
    })
    await waitFor(() => expect(screen.getByText('Spoken words.')).toBeInTheDocument())
    expect(screen.queryByText(i18n.t('onboarding.test.demoPolished'))).not.toBeInTheDocument()
  })
})
