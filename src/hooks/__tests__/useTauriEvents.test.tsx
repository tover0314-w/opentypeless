import { StrictMode } from 'react'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { listen } from '@tauri-apps/api/event'
import { useTauriEvents } from '../useTauriEvents'
import { useAppStore } from '../../stores/appStore'
import { useCloudServiceStore } from '../../stores/cloudServiceStore'
import { toast } from '../../components/toast-service'
import { useCapsuleAlertStore } from '../../stores/capsuleAlertStore'
import { CapsuleAlertBubble } from '../../components/Capsule/CapsuleAlertBubble'
import { getDictionary, getHistory } from '../../lib/tauri'
import type { DictionaryEntry, HistoryEntry } from '../../stores/appStore'

const eventListeners = vi.hoisted(() => new Map<string, (event: { payload: unknown }) => void>())
const invalidateCloudSessionOnce = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))
const invalidateOwnedCloudSession = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))
const refreshSubscription = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))
const translate = vi.hoisted(() => (key: string) => key)
const translationBinding = vi.hoisted(() => ({
  current: (key: string) => key,
}))

vi.mock('@tauri-apps/api/event', () => ({
  listen: vi.fn((event: string, handler: (event: { payload: unknown }) => void) => {
    eventListeners.set(event, handler)
    return Promise.resolve(vi.fn())
  }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: translationBinding.current }),
}))

vi.mock('../../i18n', () => ({
  default: {
    language: 'en',
    changeLanguage: vi.fn(),
  },
}))

vi.mock('../../lib/tauri', () => ({
  getHistory: vi.fn().mockResolvedValue([]),
  getDictionary: vi.fn().mockResolvedValue([{ id: 3, word: 'kubectl', auto: true }]),
}))

vi.mock('../../components/toast-service', () => ({
  toast: vi.fn(),
}))

vi.mock('../../lib/cloud-session', () => ({
  invalidateOwnedCloudSession,
  invalidateCloudSessionOnce,
}))

vi.mock('../../stores/authStore', () => ({
  useAuthStore: {
    getState: () => ({
      user: { id: 'user-1' },
      refreshSubscription,
    }),
  },
}))

function HookHarness() {
  useTauriEvents()
  return null
}

describe('auxiliary history privacy', () => {
  it('does not read history contents from the capsule event listener', async () => {
    const previousHash = window.location.hash
    window.location.hash = '#capsule'
    vi.mocked(getHistory).mockClear()
    try {
      render(<HookHarness />)
      await waitFor(() => expect(eventListeners.has('history:changed')).toBe(true))
      act(() => eventListeners.get('history:changed')?.({ payload: undefined }))
      expect(getHistory).not.toHaveBeenCalled()
    } finally {
      cleanup()
      window.location.hash = previousHash
    }
  })
})

function CapsuleAlertHarness() {
  useTauriEvents()
  return <CapsuleAlertBubble />
}

describe('useTauriEvents', () => {
  it('attaches dismissed history only to the matching recording notice', async () => {
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('pipeline:dismissed')).toBe(true))
    act(() => {
      useCapsuleAlertStore
        .getState()
        .show('transcript_cancelled', undefined, undefined, undefined, 'dictation:2')
      eventListeners.get('pipeline:dismissed')?.({
        payload: { historyId: 41, sessionId: 1 },
      })
    })
    expect(useCapsuleAlertStore.getState().alert?.entryId).toBeUndefined()
    act(() => {
      eventListeners.get('pipeline:dismissed')?.({
        payload: { historyId: 42, sessionId: 2 },
      })
    })
    expect(useCapsuleAlertStore.getState().alert?.entryId).toBe(42)
  })

  it('accepts a null identity as a legacy guide event without numeric coercion', async () => {
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('capsule:alert_dismiss')).toBe(true))
    act(() => {
      eventListeners.get('capsule:alert')?.({
        payload: { kind: 'shortcut_guide', pressId: null },
      })
    })
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('shortcut_guide')
    act(() => {
      eventListeners.get('capsule:alert_dismiss')?.({
        payload: { kind: 'shortcut_guide', pressId: null },
      })
    })
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it.each([-1, 0, 1.5, '12', Number.NaN, Number.POSITIVE_INFINITY])(
    'ignores a malformed guide identity %s without replacing the current guide',
    async (pressId) => {
      render(<HookHarness />)
      await waitFor(() => expect(eventListeners.has('capsule:alert_dismiss')).toBe(true))
      act(() => {
        eventListeners.get('capsule:alert')?.({
          payload: { kind: 'shortcut_guide', pressId: 12 },
        })
      })
      const current = useCapsuleAlertStore.getState().alert
      act(() => {
        eventListeners.get('capsule:alert')?.({
          payload: { kind: 'shortcut_guide', pressId },
        })
        eventListeners.get('capsule:alert_dismiss')?.({
          payload: { kind: 'shortcut_guide', pressId },
        })
      })
      expect(useCapsuleAlertStore.getState().alert).toBe(current)
    },
  )

  it('does not reopen a shortcut guide when its release arrived before its show event', async () => {
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('capsule:alert_dismiss')).toBe(true))
    act(() => {
      eventListeners.get('capsule:alert_dismiss')?.({
        payload: { kind: 'shortcut_guide', pressId: 12 },
      })
      eventListeners.get('capsule:alert')?.({
        payload: { kind: 'shortcut_guide', pressId: 12 },
      })
    })
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('keeps released guide identities when a language change rebinds event listeners', async () => {
    const { rerender } = render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('capsule:alert_dismiss')).toBe(true))
    act(() => {
      eventListeners.get('capsule:alert_dismiss')?.({
        payload: { kind: 'shortcut_guide', pressId: 12 },
      })
    })
    const originalListener = eventListeners.get('capsule:alert')
    try {
      translationBinding.current = (key: string) => `new-language:${key}`
      rerender(<HookHarness />)
      await waitFor(() => expect(eventListeners.get('capsule:alert')).not.toBe(originalListener))
      act(() => {
        eventListeners.get('capsule:alert')?.({
          payload: { kind: 'shortcut_guide', pressId: 12 },
        })
      })
      expect(useCapsuleAlertStore.getState().alert).toBeNull()
      act(() => {
        eventListeners.get('capsule:alert')?.({
          payload: { kind: 'shortcut_guide', pressId: 13 },
        })
        eventListeners.get('capsule:alert_dismiss')?.({
          payload: { kind: 'shortcut_guide', pressId: 13 },
        })
      })
      expect(useCapsuleAlertStore.getState().alert).toBeNull()
    } finally {
      translationBinding.current = translate
    }
  })

  it('does not dismiss the current shortcut guide with an older press identity', async () => {
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('capsule:alert_dismiss')).toBe(true))
    act(() => {
      eventListeners.get('capsule:alert')?.({
        payload: { kind: 'shortcut_guide', pressId: 10 },
      })
      eventListeners.get('capsule:alert')?.({
        payload: { kind: 'shortcut_guide', pressId: 11 },
      })
    })
    act(() => {
      eventListeners.get('capsule:alert_dismiss')?.({
        payload: { kind: 'shortcut_guide', pressId: 10 },
      })
    })
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('shortcut_guide')
    act(() => {
      eventListeners.get('capsule:alert_dismiss')?.({
        payload: { kind: 'shortcut_guide', pressId: 11 },
      })
    })
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('dismisses the original active guide after a language change rebinds listeners', async () => {
    const { rerender } = render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('capsule:alert_dismiss')).toBe(true))
    act(() => {
      eventListeners.get('capsule:alert')?.({
        payload: { kind: 'shortcut_guide', pressId: 21 },
      })
    })
    const originalListener = eventListeners.get('capsule:alert_dismiss')
    try {
      translationBinding.current = (key: string) => `new-language:${key}`
      rerender(<HookHarness />)
      await waitFor(() =>
        expect(eventListeners.get('capsule:alert_dismiss')).not.toBe(originalListener),
      )
      act(() => {
        eventListeners.get('capsule:alert_dismiss')?.({
          payload: { kind: 'shortcut_guide', pressId: 21 },
        })
      })
      expect(useCapsuleAlertStore.getState().alert).toBeNull()
    } finally {
      translationBinding.current = translate
    }
  })

  it.each([
    ['history:changed', 'Failed to refresh history', getHistory],
    ['dictionary:changed', 'Failed to refresh dictionary', getDictionary],
    [
      'auth:session-invalid-owned',
      'Failed to invalidate cloud session',
      invalidateOwnedCloudSession,
    ],
  ] as const)(
    'omits private diagnostics for %s without discarding existing data',
    async (event, message, request) => {
      const previousHash = window.location.hash
      window.location.hash = ''
      const log = vi.spyOn(console, 'error').mockImplementation(() => {})
      vi.mocked(request).mockRejectedValueOnce(new Error('synthetic-private-event-detail'))
      const previousHistory = useAppStore.getState().history
      const previousDictionary = useAppStore.getState().dictionary
      try {
        render(<HookHarness />)
        await waitFor(() => expect(eventListeners.has(event)).toBe(true))
        act(() => eventListeners.get(event)?.({ payload: undefined }))
        await waitFor(() => expect(log).toHaveBeenCalledExactlyOnceWith(message))
        expect(useAppStore.getState().history).toBe(previousHistory)
        expect(useAppStore.getState().dictionary).toBe(previousDictionary)
      } finally {
        log.mockRestore()
        window.location.hash = previousHash
      }
    },
  )

  it('omits private listener registration diagnostics while other listeners remain active', async () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(listen).mockRejectedValueOnce(new Error('synthetic-private-event-detail'))
    try {
      render(<HookHarness />)
      await waitFor(() =>
        expect(log).toHaveBeenCalledExactlyOnceWith(
          'Failed to register listener for "audio:volume"',
        ),
      )
      expect(eventListeners.has('config:patch')).toBe(true)
    } finally {
      log.mockRestore()
    }
  })

  it('applies a persisted theme event without changing model settings', async () => {
    const previous = useAppStore.getState().config
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('config:patch')).toBe(true))
    act(() => eventListeners.get('config:patch')?.({ payload: { theme: 'dark' } }))
    expect(useAppStore.getState().config.theme).toBe('dark')
    expect(useAppStore.getState().config.llm_model).toBe(previous.llm_model)
  })

  it('applies model summary events without changing local credentials', async () => {
    const previous = useAppStore.getState().config
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('config:patch')).toBe(true))
    act(() =>
      eventListeners.get('config:patch')?.({
        payload: {
          stt_provider: 'apple-speech',
          llm_provider: 'ollama',
          llm_model: 'synthetic-local-model',
          polish_enabled: false,
        },
      }),
    )
    const config = useAppStore.getState().config
    expect(config.stt_provider).toBe('apple-speech')
    expect(config.llm_provider).toBe('ollama')
    expect(config.llm_model).toBe('synthetic-local-model')
    expect(config.polish_enabled).toBe(false)
    expect(config.stt_api_key).toBe(previous.stt_api_key)
    expect(config.llm_api_key).toBe(previous.llm_api_key)
  })

  it('clears previous volume across recording states and ignores idle audio events', async () => {
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('audio:volume')).toBe(true))
    act(() => {
      eventListeners.get('pipeline:state')?.({ payload: 'recording' })
      eventListeners.get('audio:volume')?.({ payload: 0.25 })
    })
    expect(useAppStore.getState().audioVolume).toBe(0.25)
    act(() => eventListeners.get('pipeline:state')?.({ payload: 'ask_recording' }))
    expect(useAppStore.getState().audioVolume).toBe(0)
    act(() => eventListeners.get('audio:volume')?.({ payload: 0.15 }))
    expect(useAppStore.getState().audioVolume).toBe(0.15)
    act(() => {
      eventListeners.get('pipeline:state')?.({ payload: 'idle' })
      eventListeners.get('audio:volume')?.({ payload: 0.9 })
    })
    expect(useAppStore.getState().audioVolume).toBe(0)
  })

  it('accepts owned audio only for the current recording session and kind', async () => {
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('audio:volume-owned')).toBe(true))
    useAppStore.setState({
      pipelineState: 'recording',
      audioVolume: 0,
      recordingDeadline: {
        sessionId: 7,
        recordingKind: 'dictation',
        startedAtUnixMs: 1000,
        deadlineAtUnixMs: 61000,
        effectiveMaxSeconds: 60,
      },
    })
    act(() => {
      eventListeners.get('audio:volume-owned')?.({
        payload: { sessionId: 6, recordingKind: 'dictation', level: 0.9 },
      })
      eventListeners.get('audio:volume-owned')?.({
        payload: { sessionId: 7, recordingKind: 'ask', level: 0.8 },
      })
    })
    expect(useAppStore.getState().audioVolume).toBe(0)
    act(() =>
      eventListeners.get('audio:volume-owned')?.({
        payload: { sessionId: 7, recordingKind: 'dictation', level: 0.025 },
      }),
    )
    expect(useAppStore.getState().audioVolume).toBe(0.025)
    const currentDeadline = useAppStore.getState().recordingDeadline
    act(() =>
      eventListeners.get('recording:deadline')?.({
        payload: { sessionId: 'invalid', recordingKind: 'dictation' },
      }),
    )
    expect(useAppStore.getState().recordingDeadline).toBe(currentDeadline)
    expect(useAppStore.getState().audioVolume).toBe(0.025)
    act(() =>
      eventListeners.get('recording:deadline')?.({
        payload: {
          sessionId: 8,
          recordingKind: 'dictation',
          startedAtUnixMs: 2000,
          deadlineAtUnixMs: 62000,
          effectiveMaxSeconds: 60,
        },
      }),
    )
    expect(useAppStore.getState().audioVolume).toBe(0)
    act(() => eventListeners.get('audio:volume')?.({ payload: 0.9 }))
    expect(useAppStore.getState().audioVolume).toBe(0)
    const latest = useAppStore.getState().recordingDeadline
    act(() => eventListeners.get('recording:deadline')?.({ payload: currentDeadline }))
    expect(useAppStore.getState().recordingDeadline).toBe(latest)
    act(() =>
      eventListeners.get('recording:deadline')?.({
        payload: { ...latest, sessionId: 99, recordingKind: 'ask' },
      }),
    )
    expect(useAppStore.getState().recordingDeadline).toBe(latest)
  })

  it('applies the persisted learning toggle without changing models or shortcuts', async () => {
    const previous = useAppStore.getState().config
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('config:patch')).toBe(true))
    act(() => eventListeners.get('config:patch')?.({ payload: { auto_learn_dictionary: false } }))
    expect(useAppStore.getState().config.auto_learn_dictionary).toBe(false)
    expect(useAppStore.getState().config.llm_model).toBe(previous.llm_model)
    expect(useAppStore.getState().config.hotkeys).toEqual(previous.hotkeys)
  })

  it.each([
    'output_streaming_partial',
    'output_fallback_clipboard',
    'output_wayland_clipboard_copy_only',
    'output_target_changed',
  ])('surfaces %s as a warning without replacing the current result', async (code) => {
    const previousResult = useAppStore.getState().polishedText
    const syntheticResult = 'Synthetic result already delivered.'
    useAppStore.setState({ polishedText: syntheticResult })
    try {
      render(<HookHarness />)
      await waitFor(() => expect(eventListeners.has('pipeline:warning')).toBe(true))
      act(() => {
        eventListeners.get('pipeline:warning')?.({
          payload: { code, details: 'synthetic recovery detail' },
        })
      })
      expect(toast).toHaveBeenCalledTimes(1)
      expect(toast).toHaveBeenCalledWith(`errors.${code}`, 'info')
      expect(useAppStore.getState().polishedText).toBe(syntheticResult)
    } finally {
      cleanup()
      useAppStore.setState({ polishedText: previousResult })
    }
  })

  it('ignores an older dictionary refresh after a newer query completes', async () => {
    let finishOlder: (entries: DictionaryEntry[]) => void = () => {}
    vi.mocked(getDictionary).mockReturnValueOnce(
      new Promise((resolve) => {
        finishOlder = resolve
      }),
    )
    const current = [{ id: 7, word: 'New term', pronunciation: null }]
    vi.mocked(getDictionary).mockResolvedValueOnce(current)
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('dictionary:changed')).toBe(true))
    act(() => eventListeners.get('dictionary:changed')?.({ payload: undefined }))
    await act(async () => eventListeners.get('dictionary:changed')?.({ payload: undefined }))
    expect(useAppStore.getState().dictionary).toBe(current)
    await act(async () => finishOlder([{ id: 3, word: 'Old term', pronunciation: null }]))
    expect(useAppStore.getState().dictionary).toBe(current)
  })

  it('keeps local dictionary changes instead of applying a pending query', async () => {
    let finish: (entries: DictionaryEntry[]) => void = () => {}
    vi.mocked(getDictionary).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('dictionary:changed')).toBe(true))
    act(() => eventListeners.get('dictionary:changed')?.({ payload: undefined }))
    const current: DictionaryEntry[] = []
    act(() => useAppStore.getState().setDictionary(current))
    await act(async () => finish([{ id: 3, word: 'Deleted term', pronunciation: null }]))
    expect(useAppStore.getState().dictionary).toBe(current)
  })

  it('does not apply a dictionary refresh after unmounting', async () => {
    let finish: (entries: DictionaryEntry[]) => void = () => {}
    vi.mocked(getDictionary).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const { unmount } = render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('dictionary:changed')).toBe(true))
    act(() => eventListeners.get('dictionary:changed')?.({ payload: undefined }))
    const current = useAppStore.getState().dictionary
    unmount()
    await act(async () => finish([{ id: 3, word: 'Old term', pronunciation: null }]))
    expect(useAppStore.getState().dictionary).toBe(current)
  })

  it('ignores an older history query after a newer refresh completes', async () => {
    let finishOlder: (entries: HistoryEntry[]) => void = () => {}
    vi.mocked(getHistory).mockReturnValueOnce(
      new Promise((resolve) => {
        finishOlder = resolve
      }),
    )
    const newer = [{ id: 2, raw_text: 'New history' }] as HistoryEntry[]
    vi.mocked(getHistory).mockResolvedValueOnce(newer)
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('history:changed')).toBe(true))
    act(() => eventListeners.get('history:changed')?.({ payload: undefined }))
    await act(async () => eventListeners.get('pipeline:state')?.({ payload: 'idle' }))
    expect(useAppStore.getState().history).toBe(newer)
    await act(async () => finishOlder([{ id: 1 }] as HistoryEntry[]))
    expect(useAppStore.getState().history).toBe(newer)
  })

  it('does not overwrite a local history change with a pending query', async () => {
    let finish: (entries: HistoryEntry[]) => void = () => {}
    vi.mocked(getHistory).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('history:changed')).toBe(true))
    act(() => eventListeners.get('history:changed')?.({ payload: undefined }))
    const current: HistoryEntry[] = []
    act(() => useAppStore.getState().setHistory(current))
    await act(async () => finish([{ id: 1 }] as HistoryEntry[]))
    expect(useAppStore.getState().history).toBe(current)
  })

  it('does not apply a history query after its listener unmounts', async () => {
    let finish: (entries: HistoryEntry[]) => void = () => {}
    vi.mocked(getHistory).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    const { unmount } = render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('history:changed')).toBe(true))
    act(() => eventListeners.get('history:changed')?.({ payload: undefined }))
    const current = useAppStore.getState().history
    unmount()
    await act(async () => finish([{ id: 1 }] as HistoryEntry[]))
    expect(useAppStore.getState().history).toBe(current)
  })

  beforeEach(() => {
    useCapsuleAlertStore.getState().dismiss()
    eventListeners.clear()
    useAppStore.setState({
      hotkeyRegistrationError: null,
      lastContext: null,
      activeVoiceMode: null,
    })
    useCloudServiceStore.setState({ incident: null })
  })

  afterEach(() => {
    cleanup()
    useCapsuleAlertStore.getState().dismiss()
    vi.clearAllMocks()
  })

  it('shows and dismisses the long-press guide through capsule events', async () => {
    render(<CapsuleAlertHarness />)
    await waitFor(() => expect(eventListeners.has('capsule:alert_dismiss')).toBe(true))
    act(() => eventListeners.get('capsule:alert')?.({ payload: { kind: 'shortcut_guide' } }))
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('shortcut_guide')
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByText('capsule.alerts.guideTitle')).toBeInTheDocument()
    act(() =>
      eventListeners.get('capsule:alert_dismiss')?.({ payload: { kind: 'shortcut_guide' } }),
    )
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
    expect(useCapsuleAlertStore.getState().queue).toEqual([])
    expect(screen.queryByRole('table')).toBeNull()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('dismisses the long-press guide without removing a newer unrelated alert', async () => {
    render(<CapsuleAlertHarness />)
    await waitFor(() => expect(eventListeners.has('capsule:alert_dismiss')).toBe(true))
    act(() => {
      eventListeners.get('capsule:alert')?.({ payload: { kind: 'shortcut_guide' } })
      eventListeners.get('capsule:alert')?.({ payload: { kind: 'last_minute' } })
    })
    const reminder = useCapsuleAlertStore
      .getState()
      .queue.find((item) => item.kind === 'last_minute')
    expect(reminder).toBeDefined()
    act(() =>
      eventListeners.get('capsule:alert_dismiss')?.({ payload: { kind: 'shortcut_guide' } }),
    )
    expect(useCapsuleAlertStore.getState().alert).toBe(reminder)
    expect(useCapsuleAlertStore.getState().queue).toEqual([reminder])
    expect(screen.queryByRole('table')).toBeNull()
    expect(screen.getByRole('status')).toHaveAttribute('data-capsule-alert', 'last_minute')
  })

  it('ignores late long-press guide events after the listener unmounts', async () => {
    const { unmount } = render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('capsule:alert_dismiss')).toBe(true))
    const showGuide = eventListeners.get('capsule:alert')
    const dismissGuide = eventListeners.get('capsule:alert_dismiss')
    unmount()
    act(() => showGuide?.({ payload: { kind: 'shortcut_guide' } }))
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
    act(() => useCapsuleAlertStore.getState().show('shortcut_guide'))
    const currentGuide = useCapsuleAlertStore.getState().alert
    act(() => dismissGuide?.({ payload: { kind: 'shortcut_guide' } }))
    expect(useCapsuleAlertStore.getState().alert).toBe(currentGuide)
  })

  it('clears all old alerts on preparing', async () => {
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('pipeline:state')).toBe(true))
    act(() => {
      useCapsuleAlertStore.getState().show('weak_network')
      useCapsuleAlertStore.getState().show('ready')
      eventListeners.get('pipeline:state')?.({ payload: 'preparing' })
    })
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
    expect(useCapsuleAlertStore.getState().queue).toEqual([])
  })

  it('replaces previous hints with Ask when its recording starts', async () => {
    useAppStore.setState({ pipelineState: 'idle' })
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('pipeline:state')).toBe(true))
    act(() => {
      useCapsuleAlertStore.getState().show('ready')
      eventListeners.get('pipeline:state')?.({ payload: 'ask_recording' })
    })
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('ask_anything')
    expect(useCapsuleAlertStore.getState().queue).toHaveLength(1)
  })

  it('does not clear an active Ask alert on a duplicate state event', async () => {
    useAppStore.setState({ pipelineState: 'idle' })
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('pipeline:state')).toBe(true))
    act(() => eventListeners.get('pipeline:state')?.({ payload: 'ask_recording' }))
    const hintId = useCapsuleAlertStore.getState().alert?.id
    act(() => {
      useCapsuleAlertStore.getState().show('last_minute')
      eventListeners.get('pipeline:state')?.({ payload: 'ask_recording' })
    })
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('last_minute')
    expect(useCapsuleAlertStore.getState().queue.find((item) => item.id === hintId)?.kind).toBe(
      'ask_anything',
    )
  })

  it.each(['ask_thinking', 'idle', 'preparing'])(
    'removes even a hidden Ask hint on %s',
    async (state) => {
      useAppStore.setState({ pipelineState: 'idle' })
      render(<HookHarness />)
      await waitFor(() => expect(eventListeners.has('pipeline:state')).toBe(true))
      act(() => {
        eventListeners.get('pipeline:state')?.({ payload: 'ask_recording' })
        useCapsuleAlertStore.getState().show('last_minute')
        eventListeners.get('pipeline:state')?.({ payload: state })
      })
      expect(
        useCapsuleAlertStore.getState().queue.some((item) => item.kind === 'ask_anything'),
      ).toBe(false)
    },
  )

  it('clears old hints before showing the cancellation alert', async () => {
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('capsule:alert')).toBe(true))
    act(() => {
      useCapsuleAlertStore.getState().show('ready')
      eventListeners.get('capsule:alert')?.({ payload: { kind: 'transcript_cancelled' } })
    })
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('transcript_cancelled')
    expect(useCapsuleAlertStore.getState().queue).toHaveLength(1)
  })

  it('uses the pasted history text instead of stale recording text for fallback copy', async () => {
    useAppStore.setState({ polishedText: 'Old recording', finalTranscript: 'Old raw text' })
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('history:paste-result')).toBe(true))
    act(() =>
      eventListeners.get('history:paste-result')?.({
        payload: {
          text: 'Actual history text',
          result: {
            status: 'copiedFallback',
            strategyUsed: 'clipboardPaste',
            charsInserted: 0,
            charsCopied: 19,
            warningCode: null,
            message: null,
          },
        },
      }),
    )
    expect(useCapsuleAlertStore.getState().alert).toMatchObject({
      kind: 'copy_last_transcript',
      text: 'Actual history text',
    })
    expect(useAppStore.getState().polishedText).toBe('Old recording')
  })

  it('shows the actual microphone emitted after recording startup', async () => {
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('audio:input-device')).toBe(true))
    act(() => {
      eventListeners.get('pipeline:state')?.({ payload: 'ask_recording' })
      eventListeners.get('audio:input-device')?.({ payload: 'USB Mic' })
    })
    expect(useCapsuleAlertStore.getState().alert).toMatchObject({
      kind: 'microphone_in_use',
      text: 'USB Mic',
    })
  })

  it('shows a microphone recommendation only during recording', async () => {
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('audio:better-microphone')).toBe(true))
    act(() => {
      useAppStore.setState({ pipelineState: 'recording' })
      eventListeners.get('audio:better-microphone')?.({ payload: 'USB Mic' })
    })
    expect(useCapsuleAlertStore.getState().alert).toMatchObject({
      kind: 'better_microphone',
      text: 'USB Mic',
    })
    act(() => {
      useCapsuleAlertStore.getState().dismiss()
      useAppStore.setState({ pipelineState: 'idle' })
      eventListeners.get('audio:better-microphone')?.({ payload: 'USB Mic' })
    })
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('counts dictation once in the capsule and shows the configured translation hint', async () => {
    const previousHash = window.location.hash
    window.location.hash = '#capsule'
    localStorage.setItem(
      'capsule.translationOnboarding',
      JSON.stringify({ count: 10, shown: false }),
    )
    const config = useAppStore.getState().config
    useAppStore.setState({
      pipelineState: 'idle',
      recordingStyle: 'pushToTalk',
      config: {
        ...config,
        translation: { targets: ['en'], active_target: 'en' },
        hotkeys: { ...config.hotkeys, translate: { primary: 'LeftShift', modifiers: ['Fn'] } },
      },
    })
    try {
      render(<HookHarness />)
      await waitFor(() => expect(eventListeners.has('pipeline:voice_mode')).toBe(true))
      act(() => {
        eventListeners.get('pipeline:state')?.({ payload: 'preparing' })
        eventListeners.get('pipeline:state')?.({ payload: 'recording' })
        eventListeners.get('pipeline:voice_mode')?.({ payload: 'dictate' })
      })
      expect(useCapsuleAlertStore.getState().alert?.kind).toBe('switch_translation_mode')
      const hintId = useCapsuleAlertStore.getState().alert?.id
      act(() => eventListeners.get('pipeline:voice_mode')?.({ payload: 'dictate' }))
      expect(useCapsuleAlertStore.getState().alert?.id).toBe(hintId)
    } finally {
      window.location.hash = previousHash
      localStorage.removeItem('capsule.translationOnboarding')
    }
  })

  it.each([
    ['#/', 'dictate', 'pushToTalk'],
    ['#capsule', 'translate', 'pushToTalk'],
    ['#capsule', 'dictate', 'handsFree'],
  ])('does not count %s %s %s as a push-to-talk dictation', async (hash, mode, style) => {
    const previousHash = window.location.hash
    window.location.hash = hash
    localStorage.removeItem('capsule.translationOnboarding')
    useAppStore.setState({
      pipelineState: 'recording',
      recordingStyle: style as 'pushToTalk' | 'handsFree',
    })
    try {
      render(<HookHarness />)
      await waitFor(() => expect(eventListeners.has('pipeline:voice_mode')).toBe(true))
      act(() => eventListeners.get('pipeline:voice_mode')?.({ payload: mode }))
      expect(localStorage.getItem('capsule.translationOnboarding')).toBeNull()
    } finally {
      window.location.hash = previousHash
    }
  })

  it('does not let a home reminder interrupt recording in the capsule window', async () => {
    useAppStore.setState({ pipelineState: 'recording', pipelineError: null })
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('capsule:alert')).toBe(true))
    act(() => eventListeners.get('capsule:alert')?.({ payload: { kind: 'use_voice' } }))
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it.each(['transcribing', 'polishing', 'outputting', 'idle'])(
    'clears a hidden translation hint and menu on %s',
    async (state) => {
      render(<HookHarness />)
      await waitFor(() => expect(eventListeners.has('pipeline:state')).toBe(true))
      act(() => {
        useCapsuleAlertStore.getState().show('translating_to')
        useCapsuleAlertStore.getState().show('last_minute')
        useAppStore.setState({ translationTargetMenuOpen: true })
        eventListeners.get('pipeline:state')?.({ payload: state })
      })
      expect(
        useCapsuleAlertStore.getState().queue.some((item) => item.kind === 'translating_to'),
      ).toBe(false)
      expect(useAppStore.getState().translationTargetMenuOpen).toBe(false)
    },
  )

  it('clears hotkey registration errors when the backend reports recovery', async () => {
    render(<HookHarness />)

    await waitFor(() => {
      expect(eventListeners.has('hotkey:registration-failed')).toBe(true)
    })

    act(() => {
      eventListeners.get('hotkey:registration-failed')?.({ payload: 'Shortcut occupied' })
    })
    expect(useAppStore.getState().hotkeyRegistrationError).toBe('Shortcut occupied')

    await waitFor(() => {
      expect(eventListeners.has('hotkey:registration-recovered')).toBe(true)
    })

    act(() => {
      eventListeners.get('hotkey:registration-recovered')?.({ payload: undefined })
    })
    expect(useAppStore.getState().hotkeyRegistrationError).toBeNull()
  })

  it('lets only the showing error alert retry a stored failed take', async () => {
    render(<HookHarness />)
    await waitFor(() => {
      expect(eventListeners.has('pipeline:failed_take')).toBe(true)
      expect(eventListeners.has('pipeline:error')).toBe(true)
    })

    act(() => {
      eventListeners.get('pipeline:failed_take')?.({ payload: { historyId: 4 } })
    })
    expect(useAppStore.getState().failedTakeId).toBeNull()

    act(() => {
      eventListeners.get('pipeline:error')?.({ payload: { code: 'stt_timeout' } })
      eventListeners.get('pipeline:failed_take')?.({ payload: { historyId: 5 } })
    })
    expect(useAppStore.getState().failedTakeId).toBe(5)

    act(() => {
      eventListeners.get('pipeline:error')?.({ payload: { code: 'llm_failed' } })
    })
    expect(useAppStore.getState().failedTakeId).toBeNull()
    act(() => {
      eventListeners.get('pipeline:failed_take')?.({ payload: { historyId: 6 } })
      useAppStore.getState().setPipelineError(null)
    })
    expect(useAppStore.getState().failedTakeId).toBeNull()
  })

  it('shows the auto-added word alert and refreshes the dictionary', async () => {
    render(<HookHarness />)
    await waitFor(() => {
      expect(eventListeners.has('dictionary:word_auto_added')).toBe(true)
      expect(eventListeners.has('dictionary:changed')).toBe(true)
    })

    act(() => {
      eventListeners.get('dictionary:word_auto_added')?.({ payload: { term: 'kubectl', id: 3 } })
    })
    expect(useCapsuleAlertStore.getState().alert).toMatchObject({
      kind: 'dictionary_auto_added',
      text: 'kubectl',
      entryId: 3,
    })

    act(() => {
      eventListeners.get('dictionary:changed')?.({ payload: undefined })
    })
    await waitFor(() => {
      expect(useAppStore.getState().dictionary).toEqual([{ id: 3, word: 'kubectl', auto: true }])
    })
  })

  it('clears stale capsule errors when a new pipeline run starts preparing', async () => {
    useAppStore.setState({ pipelineError: 'Previous failure' })
    render(<HookHarness />)

    await waitFor(() => {
      expect(eventListeners.has('pipeline:state')).toBe(true)
    })

    act(() => {
      eventListeners.get('pipeline:state')?.({ payload: 'preparing' })
    })

    expect(useAppStore.getState().pipelineError).toBeNull()
  })

  it('ignores the obsolete Strict Mode subscription while the current one still renders', async () => {
    const firstCall = vi.mocked(listen).mock.calls.length
    render(
      <StrictMode>
        <CapsuleAlertHarness />
      </StrictMode>,
    )
    await waitFor(() => expect(eventListeners.has('capsule:alert')).toBe(true))
    const subscriptions = vi
      .mocked(listen)
      .mock.calls.slice(firstCall)
      .filter(([event]) => event === 'capsule:alert')
    expect(subscriptions).toHaveLength(2)
    const obsolete = subscriptions[0][1]
    const current = subscriptions[1][1]
    act(() => obsolete({ event: 'capsule:alert', id: 1, payload: { kind: 'shortcut_guide' } }))
    expect(screen.queryByRole('table')).toBeNull()
    act(() => current({ event: 'capsule:alert', id: 2, payload: { kind: 'shortcut_guide' } }))
    expect(screen.getByRole('table')).toBeInTheDocument()
    const currentGuide = useCapsuleAlertStore.getState().alert
    act(() =>
      obsolete({ event: 'capsule:alert', id: 1, payload: { kind: 'transcript_cancelled' } }),
    )
    expect(useCapsuleAlertStore.getState().alert).toBe(currentGuide)
    expect(screen.getByRole('table')).toBeInTheDocument()
  })

  it('forwards one Rust session-invalid event to the shared coordinator in Strict Mode', async () => {
    render(
      <StrictMode>
        <HookHarness />
      </StrictMode>,
    )

    await waitFor(() => {
      expect(eventListeners.has('auth:session-invalid-owned')).toBe(true)
    })

    act(() => {
      eventListeners.get('auth:session-invalid-owned')?.({ payload: { sessionEpoch: 'synthetic' } })
    })

    expect(invalidateOwnedCloudSession).toHaveBeenCalledWith({ sessionEpoch: 'synthetic' })
    expect(invalidateCloudSessionOnce).not.toHaveBeenCalled()
    expect(eventListeners.has('auth:session-invalid')).toBe(false)
  })

  it('stores only the safe context summary emitted for the completed operation', async () => {
    render(<HookHarness />)

    await waitFor(() => {
      expect(eventListeners.has('pipeline:context')).toBe(true)
    })

    act(() => {
      eventListeners.get('pipeline:context')?.({
        payload: {
          profileId: 'chat.slack',
          family: 'work_chat',
          appLabel: 'Slack',
          iconKey: 'slack',
          overrideId: 'slack',
        },
      })
    })

    expect(useAppStore.getState().lastContext).toEqual({
      profileId: 'chat.slack',
      family: 'work_chat',
      appLabel: 'Slack',
      iconKey: 'slack',
      overrideId: 'slack',
    })
  })

  it('tracks the operation voice mode without inferring it from pipeline state', async () => {
    render(<HookHarness />)

    await waitFor(() => {
      expect(eventListeners.has('pipeline:voice_mode')).toBe(true)
    })

    act(() => {
      eventListeners.get('pipeline:voice_mode')?.({ payload: 'translate' })
    })
    expect(useAppStore.getState().activeVoiceMode).toBe('translate')
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('translating_to')

    act(() => {
      eventListeners.get('pipeline:voice_mode')?.({ payload: null })
    })
    expect(useAppStore.getState().activeVoiceMode).toBeNull()
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('keeps the error classification for actionable floating alerts', async () => {
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('pipeline:error')).toBe(true))
    act(() => {
      eventListeners.get('pipeline:error')?.({
        payload: 'Auth error: Cloud STT: session token is missing. Please sign in first.',
      })
    })
    expect(useAppStore.getState().pipelineErrorKey).toBe('authentication_required')
  })

  it('shows a persistent incident only for a failing managed-cloud provider', async () => {
    useAppStore.setState((state) => ({
      config: { ...state.config, stt_provider: 'cloud', llm_provider: 'deepseek' },
    }))
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('pipeline:error')).toBe(true))

    act(() => {
      eventListeners.get('pipeline:error')?.({
        payload: { code: 'stt_failed', details: 'HTTP 503' },
      })
    })

    expect(useCloudServiceStore.getState().incident).toMatchObject({
      kind: 'stt',
      code: 'stt_failed',
    })
  })

  it('does not mislabel BYOK failures or quota errors as a cloud outage', async () => {
    useAppStore.setState((state) => ({
      config: { ...state.config, stt_provider: 'openai-whisper', llm_provider: 'deepseek' },
    }))
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('pipeline:error')).toBe(true))

    act(() => {
      eventListeners.get('pipeline:error')?.({ payload: { code: 'stt_failed' } })
    })
    expect(useCloudServiceStore.getState().incident).toBeNull()

    useAppStore.setState((state) => ({
      config: { ...state.config, stt_provider: 'cloud' },
    }))
    act(() => {
      eventListeners.get('pipeline:error')?.({ payload: { code: 'stt_quota_exceeded' } })
    })
    expect(useCloudServiceStore.getState().incident).toBeNull()
  })

  it('clears an old incident when the user starts a manual retry', async () => {
    useCloudServiceStore.setState({
      incident: { kind: 'llm', code: 'llm_failed', occurredAt: '2026-08-26T10:00:00.000Z' },
    })
    render(<HookHarness />)
    await waitFor(() => expect(eventListeners.has('pipeline:state')).toBe(true))

    act(() => {
      eventListeners.get('pipeline:state')?.({ payload: 'preparing' })
    })
    expect(useCloudServiceStore.getState().incident).toBeNull()
  })

  it('shows deadline warnings and explains an automatic graceful stop', async () => {
    render(<HookHarness />)

    await waitFor(() => {
      expect(eventListeners.has('recording:deadline-warning')).toBe(true)
      expect(eventListeners.has('recording:deadline-reached')).toBe(true)
    })

    const base = {
      sessionId: 7,
      recordingKind: 'dictation',
      effectiveMaxSeconds: 600,
      providerId: 'cloud',
      explanationKey: 'recordingLimits.reasons.managedCapability',
    }
    act(() => {
      eventListeners.get('recording:deadline-warning')?.({
        payload: { ...base, secondsRemaining: 10 },
      })
      eventListeners.get('recording:deadline-reached')?.({ payload: base })
    })

    expect(toast).toHaveBeenCalledTimes(1)
    expect(toast).toHaveBeenCalledWith('recordingLimits.deadlineWarning', 'info')
    expect(useCapsuleAlertStore.getState().alert).toMatchObject({
      kind: 'input_duration_exceeded',
      text: 'recordingLimits.durationMinutes',
    })
  })

  it('refreshes managed usage after output without polling idle windows', async () => {
    useAppStore.setState({
      config: {
        ...useAppStore.getState().config,
        stt_provider: 'cloud',
      },
    })
    render(<HookHarness />)

    await waitFor(() => expect(eventListeners.has('pipeline:state')).toBe(true))
    act(() => {
      eventListeners.get('pipeline:state')?.({ payload: 'preparing' })
      eventListeners.get('pipeline:state')?.({ payload: 'recording' })
      eventListeners.get('pipeline:state')?.({ payload: 'idle' })
    })

    expect(refreshSubscription).toHaveBeenCalledTimes(1)

    act(() => {
      eventListeners.get('pipeline:state')?.({ payload: 'idle' })
    })
    expect(refreshSubscription).toHaveBeenCalledTimes(1)
  })
})
