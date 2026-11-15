import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../../App'
import { useAppStore, type HistoryEntry } from '../../stores/appStore'
import { getConfig, getHistory, loadOnboardingCompleted } from '../../lib/tauri'
import i18n from '../../i18n'
import { StrictMode } from 'react'
import { initDeepLinkListener } from '../../lib/deep-link'
import { listen } from '@tauri-apps/api/event'

const authStartup = vi.hoisted(() => ({ initialize: vi.fn() }))

vi.mock('@tauri-apps/api/event', () => ({ listen: vi.fn() }))

vi.mock('../../hooks/useTauriEvents', () => ({ useTauriEvents: () => {} }))
vi.mock('../../hooks/useUpdateEvents', () => ({ useMainUpdateEvents: () => {} }))
vi.mock('../../hooks/useInteractionSounds', () => ({ useInteractionSounds: () => {} }))
vi.mock('../../lib/deep-link', () => ({ initDeepLinkListener: vi.fn(() => vi.fn()) }))
vi.mock('../../lib/router', () => ({
  useRoute: () => ({ page: 'home', route: 'home', navigate: vi.fn(), closeModal: vi.fn() }),
}))
vi.mock('../../stores/authStore', () => ({
  useAuthStore: Object.assign(
    (selector: (state: { user: null; loading: boolean }) => unknown) =>
      selector({ user: null, loading: false }),
    { getState: () => ({ initialize: authStartup.initialize }) },
  ),
}))
vi.mock('../../components/Onboarding', () => ({
  Onboarding: () => <div>onboarding content</div>,
}))
vi.mock('../../components/MainLayout', () => ({
  MainLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))
vi.mock('../../components/PageContent', () => ({
  PageContent: () => <div>home content</div>,
}))
vi.mock('../../components/Settings', () => ({
  SettingsModal: () => null,
  ModalFrame: () => null,
}))
vi.mock('../../components/UpgradePage', () => ({ UpgradePage: () => null }))
vi.mock('../../components/AccountPage', () => ({ AccountPage: () => null }))
vi.mock('../../components/AskPanel', () => ({ AskPanel: () => null }))
vi.mock('../../components/Capsule', () => ({ Capsule: () => null }))
vi.mock('../../components/Toast', () => ({ ToastContainer: () => null }))
vi.mock('../../components/UpdatePrompt', () => ({ UpdatePrompt: () => null }))
vi.mock('../../lib/tauri', () => ({
  loadOnboardingCompleted: vi.fn(),
  getConfig: vi.fn(),
  getHistory: vi.fn().mockResolvedValue([]),
  getDictionary: vi.fn().mockResolvedValue([]),
  getCorrectionRules: vi.fn().mockResolvedValue([]),
  getPlatformCapabilities: vi.fn().mockResolvedValue(null),
  getHotkeyRegistrationError: vi.fn().mockResolvedValue(null),
  checkAccessibilityPermission: vi.fn().mockResolvedValue(false),
}))

beforeEach(async () => {
  vi.clearAllMocks()
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  )
  vi.mocked(listen).mockResolvedValue(vi.fn())
  useAppStore.setState(useAppStore.getInitialState())
  window.location.hash = ''
  await i18n.changeLanguage('en')
  vi.mocked(getConfig).mockResolvedValue(useAppStore.getState().config)
})

afterEach(async () => {
  cleanup()
  vi.unstubAllGlobals()
  document.documentElement.classList.remove('dark')
  useAppStore.setState(useAppStore.getInitialState())
  localStorage.removeItem('ui_language')
  await i18n.changeLanguage('en')
})

describe('main startup persisted configuration', () => {
  it('keeps local features available while cloud authentication is waiting', async () => {
    authStartup.initialize.mockReturnValueOnce(new Promise<void>(() => {}))
    vi.mocked(loadOnboardingCompleted).mockResolvedValue(true)
    render(<App />)
    expect(await screen.findByText('home content')).toBeInTheDocument()
    expect(authStartup.initialize).toHaveBeenCalledOnce()
    expect(useAppStore.getState().savedConfig).not.toBeNull()
  })

  it('handles asynchronous Ask listener cleanup rejection', async () => {
    window.location.hash = '#ask'
    const rejection = Promise.reject(new Error('Synthetic cleanup failure'))
    void rejection.catch(() => {})
    const handled = vi.spyOn(rejection, 'catch')
    const release = vi.fn(() => rejection)
    vi.mocked(listen).mockResolvedValueOnce(release)
    try {
      const { unmount } = render(<App />)
      await act(async () => {})
      unmount()
      expect(release).toHaveBeenCalledOnce()
      expect(handled).toHaveBeenCalledWith(expect.any(Function))
    } finally {
      handled.mockRestore()
    }
  })

  it.each(['#capsule', '#ask'])(
    'keeps linked shortcut and translation fields after a stale auxiliary read for %s',
    async (hash) => {
      window.location.hash = hash
      const baseline = useAppStore.getState().config
      let finish!: (config: typeof baseline) => void
      vi.mocked(getConfig).mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve
          }),
      )
      render(<App />)
      act(() => {
        useAppStore.getState().applyPersistedConfigPatch({
          hotkey: 'Ctrl+Shift+F11',
          target_lang: 'ja',
        })
      })
      const current = useAppStore.getState().config
      await act(async () => finish({ ...baseline, llm_model: 'persisted-model' }))
      const merged = useAppStore.getState().config
      expect(merged.hotkey).toBe(current.hotkey)
      expect(merged.hotkeys).toStrictEqual(current.hotkeys)
      expect(merged.target_lang).toBe('ja')
      expect(merged.translation).toStrictEqual(current.translation)
      expect(merged.llm_model).toBe('persisted-model')
    },
  )

  it('releases an Ask configuration listener registered after unmount', async () => {
    window.location.hash = '#ask'
    const release = vi.fn()
    let finish!: (callback: () => void) => void
    vi.mocked(listen).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const { unmount } = render(<App />)
    unmount()
    await act(async () => finish(release))
    expect(release).toHaveBeenCalledOnce()
  })

  it('keeps loaded Ask configuration when listener registration fails without logging details', async () => {
    window.location.hash = '#ask'
    const baseline = useAppStore.getState().config
    vi.mocked(getConfig).mockResolvedValueOnce({ ...baseline, llm_model: 'loaded-model' })
    vi.mocked(listen).mockRejectedValueOnce(new Error('synthetic-private-listener-detail'))
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      render(<App />)
      await waitFor(() =>
        expect(log).toHaveBeenCalledExactlyOnceWith(
          'Failed to listen for Ask configuration changes',
        ),
      )
      expect(useAppStore.getState().config.llm_model).toBe('loaded-model')
    } finally {
      log.mockRestore()
    }
  })

  it('updates the open Ask window language from configuration events and releases its listener', async () => {
    window.location.hash = '#ask'
    const release = vi.fn()
    let receive!: (event: { payload: { ui_language: string; theme?: string } }) => void
    vi.mocked(listen).mockImplementationOnce((name, handler) => {
      expect(name).toBe('config:patch')
      receive = (event) => handler({ ...event, event: name, id: 1 })
      return Promise.resolve(release)
    })
    const { unmount } = render(<App />)
    await waitFor(() => expect(listen).toHaveBeenCalledOnce())
    await act(async () => receive({ payload: { ui_language: 'ja', theme: 'dark' } }))
    expect(useAppStore.getState().config.ui_language).toBe('ja')
    expect(useAppStore.getState().config.theme).toBe('dark')
    expect(document.documentElement).toHaveClass('dark')
    expect(i18n.language).toBe('ja')
    expect(localStorage.getItem('ui_language')).toBe('ja')
    await act(async () => receive({ payload: { ui_language: 'ja', theme: 'light' } }))
    expect(document.documentElement).not.toHaveClass('dark')
    unmount()
    expect(release).toHaveBeenCalledOnce()
    await act(async () => receive({ payload: { ui_language: 'zh' } }))
    expect(i18n.language).toBe('ja')
  })

  it.each(['#capsule', '#ask'])(
    'preserves an auxiliary setting changed back to its initial value for %s',
    async (hash) => {
      window.location.hash = hash
      const baseline = useAppStore.getState().config
      let finish!: (config: typeof baseline) => void
      vi.mocked(getConfig).mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve
          }),
      )
      render(<App />)
      act(() => {
        useAppStore.getState().applyPersistedConfigPatch({
          max_recording_seconds: baseline.max_recording_seconds + 1,
        })
        useAppStore.getState().applyPersistedConfigPatch({
          max_recording_seconds: baseline.max_recording_seconds,
        })
      })
      await act(async () =>
        finish({ ...baseline, max_recording_seconds: baseline.max_recording_seconds + 2 }),
      )
      expect(useAppStore.getState().config.max_recording_seconds).toBe(
        baseline.max_recording_seconds,
      )
    },
  )

  it.each(['success', 'failure', 'unmount'])(
    'releases the auxiliary startup subscription on %s',
    async (outcome) => {
      window.location.hash = '#capsule'
      const baseline = useAppStore.getState().config
      const originalSubscribe = useAppStore.subscribe
      const releases: ReturnType<typeof vi.fn>[] = []
      const subscribe = vi.spyOn(useAppStore, 'subscribe').mockImplementation((listener) => {
        const release = vi.fn(originalSubscribe(listener))
        releases.push(release)
        return release
      })
      const log = vi.spyOn(console, 'error').mockImplementation(() => {})
      let finish!: (config: typeof baseline) => void
      let fail!: (error: Error) => void
      vi.mocked(getConfig).mockImplementationOnce(
        () =>
          new Promise((resolve, reject) => {
            finish = resolve
            fail = reject
          }),
      )
      try {
        const { unmount } = render(<App />)
        expect(releases).toHaveLength(1)
        if (outcome === 'unmount') unmount()
        else {
          await act(async () => {
            if (outcome === 'success') finish(baseline)
            else fail(new Error('Synthetic failure'))
          })
        }
        expect(releases[0]).toHaveBeenCalled()
        if (outcome === 'unmount') await act(async () => finish(baseline))
        unmount()
      } finally {
        subscribe.mockRestore()
        log.mockRestore()
      }
    },
  )

  it.each(['#capsule', '#ask'])(
    'preserves configuration changes while an auxiliary startup read is pending for %s',
    async (hash) => {
      window.location.hash = hash
      const baseline = useAppStore.getState().config
      let finish!: (config: typeof baseline) => void
      vi.mocked(getConfig).mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve
          }),
      )
      render(<App />)
      act(() => {
        useAppStore.getState().applyPersistedConfigPatch({
          max_recording_seconds: 60,
          ui_language: 'ja',
        })
      })
      await act(async () =>
        finish({
          ...baseline,
          max_recording_seconds: 30,
          ui_language: 'zh',
          llm_model: 'persisted-model',
        }),
      )
      expect(useAppStore.getState().config.max_recording_seconds).toBe(60)
      expect(useAppStore.getState().config.ui_language).toBe('ja')
      expect(useAppStore.getState().config.llm_model).toBe('persisted-model')
      expect(i18n.language).toBe('ja')
      expect(localStorage.getItem('ui_language')).toBe('ja')
    },
  )

  it.each(['#capsule', '#ask'])(
    'keeps the active auxiliary configuration during StrictMode replay for %s',
    async (hash) => {
      window.location.hash = hash
      const baseline = useAppStore.getState().config
      const current = { ...baseline, llm_model: 'current-model', ui_language: 'ja' }
      let finishOld!: (config: typeof baseline) => void
      vi.mocked(getConfig)
        .mockImplementationOnce(
          () =>
            new Promise((resolve) => {
              finishOld = resolve
            }),
        )
        .mockResolvedValueOnce(current)
      render(
        <StrictMode>
          <App />
        </StrictMode>,
      )
      await waitFor(() => expect(useAppStore.getState().config.llm_model).toBe('current-model'))
      expect(i18n.language).toBe('ja')
      expect(localStorage.getItem('ui_language')).toBe('ja')
      await act(async () =>
        finishOld({ ...baseline, llm_model: 'discarded-model', ui_language: 'zh' }),
      )
      expect(getConfig).toHaveBeenCalledTimes(2)
      expect(useAppStore.getState().config.llm_model).toBe('current-model')
      expect(i18n.language).toBe('ja')
      expect(localStorage.getItem('ui_language')).toBe('ja')
    },
  )

  it.each(['#capsule', '#ask'])(
    'ignores auxiliary configuration arriving after unmount for %s',
    async (hash) => {
      window.location.hash = hash
      const previous = useAppStore.getState().config
      let finish!: (config: typeof previous) => void
      vi.mocked(getConfig).mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve
          }),
      )
      const { unmount } = render(<App />)
      unmount()
      await act(async () => finish({ ...previous, llm_model: 'late-model', ui_language: 'zh' }))
      expect(useAppStore.getState().config).toBe(previous)
      expect(i18n.language).toBe('en')
      expect(localStorage.getItem('ui_language')).not.toBe('zh')
    },
  )

  it.each([
    ['#capsule', 'Failed to load config in capsule'],
    ['#ask', 'Failed to load config in Ask app'],
  ])('omits raw auxiliary configuration diagnostics for %s', async (hash, message) => {
    window.location.hash = hash
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(getConfig).mockRejectedValueOnce(new Error('synthetic-private-config-detail'))
    try {
      render(<App />)
      await waitFor(() => expect(log).toHaveBeenCalledExactlyOnceWith(message))
    } finally {
      log.mockRestore()
    }
  })

  it('cleans up its own deep-link listener on unmount', () => {
    vi.mocked(loadOnboardingCompleted).mockResolvedValue(false)
    const stop = vi.fn()
    vi.mocked(initDeepLinkListener).mockReturnValueOnce(stop)
    const { unmount } = render(<App />)
    unmount()
    expect(stop).toHaveBeenCalledOnce()
  })

  it('ignores the discarded startup read during StrictMode effect replay', async () => {
    let finishOldMarker: ((done: boolean) => void) | undefined
    vi.mocked(loadOnboardingCompleted)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishOldMarker = resolve
          }),
      )
      .mockResolvedValueOnce(false)
    render(
      <StrictMode>
        <App />
      </StrictMode>,
    )
    await screen.findByText('onboarding content')
    await act(async () => finishOldMarker?.(true))
    expect(useAppStore.getState().onboardingCompleted).toBe(false)
    expect(getConfig).toHaveBeenCalledOnce()
    expect(getHistory).not.toHaveBeenCalled()
  })

  it('does not apply completed-setup collections after unmount', async () => {
    const previous = useAppStore.getState().config
    const persisted = { ...previous, llm_model: 'late-model' }
    let finishRead: ((config: typeof persisted) => void) | undefined
    vi.mocked(loadOnboardingCompleted).mockResolvedValue(true)
    vi.mocked(getConfig).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishRead = resolve
        }),
    )
    const { unmount } = render(<App />)
    await waitFor(() => expect(getHistory).toHaveBeenCalledOnce())
    const newerHistory: HistoryEntry[] = []
    useAppStore.getState().setHistory(newerHistory)
    unmount()
    await act(async () => finishRead?.(persisted))
    expect(useAppStore.getState().config).toBe(previous)
    expect(useAppStore.getState().history).toBe(newerHistory)
    expect(useAppStore.getState().savedConfig).toBeNull()
  })

  it('does not start configuration reads after the startup owner unmounts', async () => {
    let finishMarker: ((done: boolean) => void) | undefined
    vi.mocked(loadOnboardingCompleted).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishMarker = resolve
        }),
    )
    const { unmount } = render(<App />)
    unmount()
    await act(async () => finishMarker?.(true))
    expect(getConfig).not.toHaveBeenCalled()
    expect(getHistory).not.toHaveBeenCalled()
    expect(useAppStore.getState().onboardingCompleted).toBe(false)
  })

  it('does not apply late onboarding settings after unmount', async () => {
    const previous = useAppStore.getState().config
    const persisted = { ...previous, polish_enabled: false, ui_language: 'zh' }
    let finishRead: ((config: typeof persisted) => void) | undefined
    vi.mocked(loadOnboardingCompleted).mockResolvedValue(false)
    vi.mocked(getConfig).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishRead = resolve
        }),
    )
    const { unmount } = render(<App />)
    await waitFor(() => expect(getConfig).toHaveBeenCalledOnce())
    unmount()
    await act(async () => finishRead?.(persisted))
    expect(useAppStore.getState().config).toBe(previous)
    expect(useAppStore.getState().savedConfig).toBeNull()
    expect(i18n.language).toBe('en')
    expect(localStorage.getItem('ui_language')).toBeNull()
  })

  it('keeps onboarding hidden until the existing settings read completes', async () => {
    const persisted = { ...useAppStore.getState().config, polish_enabled: false }
    let finishRead: ((config: typeof persisted) => void) | undefined
    vi.mocked(loadOnboardingCompleted).mockResolvedValue(false)
    vi.mocked(getConfig).mockImplementation(
      () =>
        new Promise((resolve) => {
          finishRead = resolve
        }),
    )
    render(<App />)
    await waitFor(() => expect(getConfig).toHaveBeenCalledOnce())
    expect(screen.queryByText('onboarding content')).not.toBeInTheDocument()
    await act(async () => finishRead?.(persisted))
    expect(screen.getByText('onboarding content')).toBeInTheDocument()
    expect(useAppStore.getState().config.polish_enabled).toBe(false)
  })

  it('loads existing settings before showing unfinished onboarding', async () => {
    const persisted = {
      ...useAppStore.getState().config,
      polish_enabled: false,
      history_enabled: false,
      llm_model: 'existing-user-model',
      ui_language: 'zh',
    }
    vi.mocked(loadOnboardingCompleted).mockResolvedValue(false)
    vi.mocked(getConfig).mockResolvedValue(persisted)
    render(<App />)
    await screen.findByText('onboarding content')
    expect(getConfig).toHaveBeenCalledOnce()
    expect(useAppStore.getState().config).toEqual(persisted)
    expect(useAppStore.getState().savedConfig).toEqual(persisted)
    expect(i18n.language).toBe('zh')
    expect(getHistory).not.toHaveBeenCalled()
  })

  it('blocks onboarding when persisted settings cannot be read', async () => {
    vi.mocked(loadOnboardingCompleted).mockResolvedValue(false)
    vi.mocked(getConfig).mockRejectedValue(new Error('Synthetic unreadable settings'))
    render(<App />)
    await screen.findByRole('button', { name: i18n.t('common.retry') })
    expect(screen.queryByText('onboarding content')).not.toBeInTheDocument()
    expect(useAppStore.getState().savedConfig).toBeNull()
  })

  it('continues loading collections for a completed setup', async () => {
    vi.mocked(loadOnboardingCompleted).mockResolvedValue(true)
    render(<App />)
    await screen.findByText('home content')
    expect(getConfig).toHaveBeenCalledOnce()
    expect(getHistory).toHaveBeenCalledWith(200, 0)
    await waitFor(() => expect(useAppStore.getState().savedConfig).not.toBeNull())
  })
})
