import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  act,
  cleanup,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
} from '@testing-library/react'
import type { HistoryEntry } from '../../../stores/appStore'
import type { UsageStats } from '../../../lib/tauri'
import { useAppStore } from '../../../stores/appStore'
import { getConfig, getUsageStats, resumeHotkey, updateConfig } from '../../../lib/tauri'
import { HomePage } from '../index'
import { openUrl } from '@tauri-apps/plugin-opener'
import * as capture from '../../../hooks/useHotkeyCapture'
import { useAutoSaveConfig } from '../../Settings/shared/useAutoSaveConfig'
import volcengineLogo from '../../../assets/provider-icons/volcengine.svg'
import openrouterLogo from '../../../assets/provider-icons/openrouter.svg'

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-i18next')>()),
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'en' },
  }),
}))

vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn().mockResolvedValue(undefined) }))

vi.mock('../ShareInsightsDialog', () => ({
  ShareInsightsDialog: () => <div data-testid="share-dialog" />,
}))

vi.mock('../../AskPanel', () => ({
  AskPanel: ({
    embedded,
    onBusyChange,
  }: {
    embedded?: boolean
    onBusyChange?: (busy: boolean) => void
  }) => (
    <div data-testid="home-ask-panel">
      {embedded ? 'Embedded Ask' : 'Standalone Ask'}
      <button onClick={() => onBusyChange?.(true)}>Synthetic Ask busy</button>
      <button onClick={() => onBusyChange?.(false)}>Synthetic Ask finished</button>
    </div>
  ),
}))

vi.mock('../../../hooks/useAppVersion', () => ({
  useAppVersion: () => '1.1.64',
}))

vi.mock('../../Settings/CheckUpdateDialog', () => ({
  CheckUpdateDialog: ({ onClose }: { onClose: () => void }) => (
    <div role="dialog" aria-label="Update check">
      <button type="button" onClick={onClose}>
        Close update check
      </button>
    </div>
  ),
}))

vi.mock('../../../lib/tauri', () => ({
  getUsageStats: vi.fn().mockResolvedValue(null),
  pauseHotkey: vi.fn().mockResolvedValue(undefined),
  resumeHotkey: vi.fn().mockResolvedValue(undefined),
  updateConfig: vi.fn().mockResolvedValue(undefined),
  getConfig: vi.fn(),
  getHotkeyRegistrationError: vi.fn().mockResolvedValue(null),
  setAutoStart: vi.fn().mockResolvedValue(undefined),
  listMicrophones: vi.fn().mockResolvedValue([]),
  startMicrophonePreview: vi.fn(),
  getMicrophonePreviewLevel: vi.fn(),
  stopMicrophonePreview: vi.fn(),
}))

function dictationBindings() {
  return useAppStore.getState().config.hotkeys.dictationBindings
}

describe('HomePage shortcuts card', () => {
  it('does not add an Ask entry or embedded panel to the home page', () => {
    render(<HomePage />)
    expect(screen.queryByRole('button', { name: 'ask.title' })).toBeNull()
    expect(screen.queryByTestId('home-ask-panel')).toBeNull()
  })

  it('shows the configured provider marks beside the service names', () => {
    const previous = useAppStore.getState().config
    try {
      useAppStore.getState().updateConfig({
        stt_provider: 'volcengine-doubao',
        llm_provider: 'openrouter',
      })
      render(<HomePage />)
      const speech = screen.getByRole('button', { name: /home.sttProvider/ })
      const writing = screen.getByRole('button', { name: /home.llmProvider/ })
      expect(speech.querySelector('img')).toHaveAttribute('src', volcengineLogo)
      expect(writing.querySelector('img')).toHaveAttribute('src', openrouterLogo)
    } finally {
      useAppStore.setState({ config: previous })
    }
  })

  it('anchors only the compact actions to the bottom-right without reserving a full-width footer', () => {
    const { container } = render(<HomePage />)
    const footer = container.querySelector('[data-home-footer]')
    expect(footer).toHaveClass('absolute', 'bottom-4', 'right-6', 'w-max')
    expect(footer).not.toHaveClass('shrink-0', 'bg-bg-primary')
    expect(footer?.parentElement).toHaveClass('relative')
    expect(container.querySelector('[data-home-scroll] > div')).toHaveClass('pb-[140px]')
  })

  it('keeps repository and update actions outside the scrolling home content', () => {
    const { container } = render(<HomePage />)
    const footer = container.querySelector('[data-home-footer]')
    const content = container.querySelector('[data-home-scroll]')
    expect(footer).not.toBeNull()
    expect(content).not.toBeNull()
    expect(content?.contains(footer)).toBe(false)
    expect(footer).toContainElement(screen.getByRole('button', { name: 'home.starOnGitHub' }))
    expect(footer).toContainElement(
      screen.getByRole('button', { name: 'settings.aboutPage.checkUpdates' }),
    )
    expect(footer?.querySelector('.home-star-mark')).toBeNull()
  })
  it('exposes the existing microphone, trigger and output controls in current configuration', () => {
    render(<HomePage />)
    expect(screen.getByRole('button', { name: 'settings.microphone' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'generalPane.triggerMode' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'generalPane.outputMode' })).toBeInTheDocument()
  })
  it('shows the version and opens the shared update dialog from the home footer', () => {
    render(<HomePage />)
    expect(screen.getByText('v1.1.64')).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Update check' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'settings.aboutPage.checkUpdates' }))
    expect(screen.getByRole('dialog', { name: 'Update check' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Close update check' }))
    expect(screen.queryByRole('dialog', { name: 'Update check' })).toBeNull()
  })
  it('links both current providers to Models and exposes the repository Star entry', async () => {
    render(<HomePage />)
    fireEvent.click(screen.getByRole('button', { name: /home.sttProvider/ }))
    expect(window.location.hash).toBe('#/models')
    fireEvent.click(screen.getByRole('button', { name: /home.llmProvider/ }))
    expect(window.location.hash).toBe('#/models')
    fireEvent.click(screen.getByRole('button', { name: 'home.starOnGitHub' }))
    await waitFor(() =>
      expect(openUrl).toHaveBeenCalledWith('https://github.com/tover0314-w/opentypeless'),
    )
  })
  it('updates the current provider and model summary from the live config store', () => {
    const config = useAppStore.getState().config
    try {
      useAppStore.setState({
        config: {
          ...config,
          polish_enabled: true,
          llm_provider: 'openai',
          llm_model: 'gpt-4o-mini',
        },
      })
      render(<HomePage />)
      const row = screen.getByRole('button', { name: /home.llmProvider/ })
      expect(row).toHaveTextContent('providers.llm.openai')
      expect(row).not.toHaveTextContent('gpt-4o-mini')
      act(() =>
        useAppStore.getState().updateConfig({
          llm_provider: 'openrouter',
          llm_model: 'anthropic/claude-sonnet',
        }),
      )
      expect(row).toHaveTextContent('providers.llm.openrouter')
      expect(row).not.toHaveTextContent('anthropic/claude-sonnet')
      expect(row).not.toHaveTextContent('gpt-4o-mini')
      act(() => useAppStore.getState().updateConfig({ polish_enabled: false }))
      expect(row).toHaveTextContent('providers.llm.openrouter')
      expect(row).not.toHaveTextContent('anthropic/claude-sonnet')
      expect(row).toHaveTextContent('settings.enableAiPolish')
      expect(row).toHaveTextContent('home.disabled')
    } finally {
      cleanup()
      useAppStore.setState({ config })
    }
  })
  it('does not revert a persisted shortcut when unrelated backend settings change during save', async () => {
    vi.mocked(updateConfig).mockImplementationOnce(async (config) => {
      useAppStore.getState().setSavedConfig({ ...config, theme: 'dark' })
    })
    render(<HomePage />)
    fireEvent.click(
      screen.getByRole('button', { name: 'home.editShortcut: home.modes.dictate.title' }),
    )
    await waitFor(() =>
      expect(document.querySelector('[data-capture-ready="true"]')).not.toBeNull(),
    )
    fireEvent.keyDown(window, { key: 'k', code: 'KeyK', ctrlKey: true, shiftKey: true })
    fireEvent.keyUp(window, { key: 'k', code: 'KeyK', ctrlKey: true, shiftKey: true })
    await waitFor(() =>
      expect(useAppStore.getState().savedConfig?.hotkeys.dictation.primary).toBe('K'),
    )
    await act(async () => {})
    expect(dictationBindings()[0].primary).toBe('K')
    expect(document.querySelector('[data-shortcut-editor]')).toBeNull()
  })

  it('persists the physical shortcut captured while an input method is composing', async () => {
    render(<HomePage />)
    fireEvent.click(
      screen.getByRole('button', { name: 'home.editShortcut: home.modes.dictate.title' }),
    )
    await waitFor(() =>
      expect(document.querySelector('[data-capture-ready="true"]')).not.toBeNull(),
    )
    fireEvent.keyDown(window, {
      key: 'Process',
      code: 'KeyK',
      isComposing: true,
      ctrlKey: true,
      shiftKey: true,
    })
    fireEvent.keyUp(window, { key: 'Process', code: 'KeyK', ctrlKey: true, shiftKey: true })
    await waitFor(() => expect(updateConfig).toHaveBeenCalled())
    expect(useAppStore.getState().savedConfig?.hotkeys.dictation).toEqual({
      primary: 'K',
      modifiers: ['Ctrl', 'Shift'],
    })
    expect(document.querySelector('[data-shortcut-editor]')).toBeNull()
    fireEvent.keyUp(window, { key: 'Process', code: 'KeyK' })
    fireEvent.keyUp(window, { key: 'Control' })
    fireEvent.keyUp(window, { key: 'Shift' })
  })

  it('uses the statistics container width rather than the window breakpoint for four columns', () => {
    render(<HomePage />)
    const section = screen.getByText('home.insights').closest('section')
    expect(section).toHaveClass('@container')
    const grid = section?.querySelector('.grid')
    expect(grid).toHaveClass('grid-cols-2', '@min-[880px]:grid-cols-4')
    expect(grid).not.toHaveClass('lg:grid-cols-4')
    expect(grid?.children).toHaveLength(4)
  })

  it('restores the shortcut draft when persistence reports failure without rejecting', async () => {
    const previous = useAppStore.getState().config
    const queue = vi.spyOn(capture, 'queueShortcutCaptureSave').mockResolvedValueOnce(false)
    try {
      render(<HomePage />)
      fireEvent.click(
        screen.getByRole('button', { name: 'home.editShortcut: home.modes.dictate.title' }),
      )
      await waitFor(() =>
        expect(document.querySelector('[data-capture-ready="true"]')).not.toBeNull(),
      )
      fireEvent.keyDown(window, { key: 'k', ctrlKey: true, shiftKey: true })
      fireEvent.keyUp(window, { key: 'k', ctrlKey: true, shiftKey: true })
      await waitFor(() => expect(dictationBindings()).toEqual(previous.hotkeys.dictationBindings))
      await waitFor(() =>
        expect(document.querySelector('[data-shortcut-editor="dictation"]')).not.toBeNull(),
      )
      expect(screen.getByText('settings.hotkeyRegistrationFailed')).toBeInTheDocument()
    } finally {
      queue.mockRestore()
    }
  })

  it('queues shortcut persistence behind an existing settings save', async () => {
    let finishSave: () => void = () => {}
    vi.mocked(updateConfig).mockClear()
    vi.mocked(updateConfig).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishSave = resolve
      }),
    )
    useAppStore.getState().setSavedConfig(useAppStore.getState().config)
    const autosave = renderHook(() => useAutoSaveConfig())
    try {
      act(() => useAppStore.getState().updateConfig({ theme: 'dark' }))
      await waitFor(() => expect(updateConfig).toHaveBeenCalledOnce())
      render(<HomePage />)
      fireEvent.click(
        screen.getByRole('button', { name: 'home.editShortcut: home.modes.dictate.title' }),
      )
      await waitFor(() =>
        expect(document.querySelector('[data-capture-ready="true"]')).not.toBeNull(),
      )
      fireEvent.keyDown(window, { key: 'k', ctrlKey: true, shiftKey: true })
      fireEvent.keyUp(window, { key: 'k', ctrlKey: true, shiftKey: true })
      await act(async () => {})
      expect(updateConfig).toHaveBeenCalledOnce()
      await act(async () => finishSave())
      await waitFor(() => expect(updateConfig).toHaveBeenCalledTimes(2))
      expect(vi.mocked(updateConfig).mock.calls[1][0].theme).toBe('dark')
      expect(vi.mocked(updateConfig).mock.calls[1][0].hotkeys.dictation.primary).toBe('K')
    } finally {
      await act(async () => finishSave())
      autosave.unmount()
    }
  })

  it('restores an unchanged shortcut draft when its capture save is rejected', async () => {
    const previous = useAppStore.getState().config
    const queue = vi
      .spyOn(capture, 'queueShortcutCaptureSave')
      .mockRejectedValueOnce(new Error('Capture unavailable'))
    try {
      render(<HomePage />)
      fireEvent.click(
        screen.getByRole('button', { name: 'home.editShortcut: home.modes.dictate.title' }),
      )
      await waitFor(() =>
        expect(document.querySelector('[data-capture-ready="true"]')).not.toBeNull(),
      )
      fireEvent.keyDown(window, { key: 'k', ctrlKey: true, shiftKey: true })
      fireEvent.keyUp(window, { key: 'k', ctrlKey: true, shiftKey: true })
      await waitFor(() => expect(dictationBindings()).toEqual(previous.hotkeys.dictationBindings))
    } finally {
      queue.mockRestore()
    }
  })

  it('preserves newer settings while reverting the rejected shortcut', async () => {
    const previous = useAppStore.getState().config
    let rejectSave: (error: Error) => void = () => {}
    const queue = vi.spyOn(capture, 'queueShortcutCaptureSave').mockReturnValueOnce(
      new Promise<void>((_resolve, reject) => {
        rejectSave = reject
      }),
    )
    try {
      render(<HomePage />)
      fireEvent.click(
        screen.getByRole('button', { name: 'home.editShortcut: home.modes.dictate.title' }),
      )
      await waitFor(() =>
        expect(document.querySelector('[data-capture-ready="true"]')).not.toBeNull(),
      )
      fireEvent.keyDown(window, { key: 'k', ctrlKey: true, shiftKey: true })
      fireEvent.keyUp(window, { key: 'k', ctrlKey: true, shiftKey: true })
      act(() => useAppStore.getState().updateConfig({ theme: 'dark' }))
      await act(async () => rejectSave(new Error('Capture unavailable')))
      expect(useAppStore.getState().config.theme).toBe('dark')
      expect(dictationBindings()).toEqual(previous.hotkeys.dictationBindings)
    } finally {
      queue.mockRestore()
    }
  })

  it('keeps a newer shortcut when an older capture save is rejected', async () => {
    let rejectSave: (error: Error) => void = () => {}
    const queue = vi.spyOn(capture, 'queueShortcutCaptureSave').mockReturnValueOnce(
      new Promise<void>((_resolve, reject) => {
        rejectSave = reject
      }),
    )
    try {
      render(<HomePage />)
      fireEvent.click(
        screen.getByRole('button', { name: 'home.editShortcut: home.modes.dictate.title' }),
      )
      await waitFor(() =>
        expect(document.querySelector('[data-capture-ready="true"]')).not.toBeNull(),
      )
      fireEvent.keyDown(window, { key: 'k', ctrlKey: true, shiftKey: true })
      fireEvent.keyUp(window, { key: 'k', ctrlKey: true, shiftKey: true })
      act(() => {
        const hotkeys = useAppStore.getState().config.hotkeys
        const binding = { primary: 'J', modifiers: ['Ctrl', 'Shift'] }
        useAppStore.getState().updateConfig({
          hotkeys: {
            ...hotkeys,
            dictation: binding,
            dictationBindings: [binding, ...hotkeys.dictationBindings.slice(1)],
          },
        })
      })
      await act(async () => rejectSave(new Error('Capture unavailable')))
      expect(dictationBindings()[0].primary).toBe('J')
    } finally {
      queue.mockRestore()
    }
  })

  it('reverts dictation without reverting a newer translation shortcut', async () => {
    const previous = useAppStore.getState().config.hotkeys
    let rejectSave: (error: Error) => void = () => {}
    const queue = vi.spyOn(capture, 'queueShortcutCaptureSave').mockReturnValueOnce(
      new Promise<void>((_resolve, reject) => {
        rejectSave = reject
      }),
    )
    try {
      render(<HomePage />)
      fireEvent.click(
        screen.getByRole('button', { name: 'home.editShortcut: home.modes.dictate.title' }),
      )
      await waitFor(() =>
        expect(document.querySelector('[data-capture-ready="true"]')).not.toBeNull(),
      )
      fireEvent.keyDown(window, { key: 'k', ctrlKey: true, shiftKey: true })
      fireEvent.keyUp(window, { key: 'k', ctrlKey: true, shiftKey: true })
      const translation = { primary: 'T', modifiers: ['Ctrl', 'Shift'] }
      act(() => {
        const hotkeys = useAppStore.getState().config.hotkeys
        useAppStore.getState().updateConfig({
          hotkeys: { ...hotkeys, translate: translation, translateBindings: [translation] },
        })
      })
      await act(async () => rejectSave(new Error('Capture unavailable')))
      expect(dictationBindings()).toEqual(previous.dictationBindings)
      expect(useAppStore.getState().config.hotkeys.translate).toEqual(translation)
      expect(useAppStore.getState().config.hotkeys.translateBindings).toEqual([translation])
    } finally {
      queue.mockRestore()
    }
  })

  it('keeps newer usage stats when an older query returns last', async () => {
    let finishOlder: (stats: UsageStats) => void = () => {}
    const stats: UsageStats = {
      totalWords: 987,
      totalSessions: 1,
      totalAudioSeconds: 5,
      averageWpm: 120,
      minutesSaved: 0,
      activeDays: 1,
      currentStreak: 1,
      longestStreak: 1,
      daily: [],
    }
    vi.mocked(getUsageStats)
      .mockReturnValueOnce(
        new Promise((resolve) => {
          finishOlder = resolve
        }),
      )
      .mockResolvedValueOnce(stats)
    render(<HomePage />)
    act(() => useAppStore.setState({ history: [{ id: 1 } as HistoryEntry] }))
    expect(await screen.findByText('987')).toBeInTheDocument()
    await act(async () => finishOlder({ ...stats, totalWords: 123 }))
    expect(screen.getByText('987')).toBeInTheDocument()
    expect(screen.queryByText('123')).toBeNull()
  })

  it('refreshes usage when history content changes without changing its length', async () => {
    vi.mocked(getUsageStats).mockClear()
    useAppStore.setState({ history: [{ id: 1, raw_text: 'Before retry' } as HistoryEntry] })
    render(<HomePage />)
    await waitFor(() => expect(getUsageStats).toHaveBeenCalledTimes(1))
    act(() =>
      useAppStore.setState({
        history: [
          { id: 1, raw_text: 'Recovered words', polished_text: 'Recovered words' } as HistoryEntry,
        ],
      }),
    )
    await waitFor(() => expect(getUsageStats).toHaveBeenCalledTimes(2))
  })

  beforeEach(() => {
    useAppStore.setState(useAppStore.getInitialState())
    const config = useAppStore.getState().config
    useAppStore.setState({
      config: {
        ...config,
        hotkey: 'Fn',
        ask_hotkey: 'Fn+Space',
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

  beforeEach(() => {
    useAppStore.getState().setSavedConfig(useAppStore.getState().config)
  })

  afterEach(async () => {
    fireEvent.keyUp(window, { key: 'k' })
    fireEvent.keyUp(window, { key: 'Control' })
    fireEvent.keyUp(window, { key: 'Shift' })
    fireEvent.keyUp(window, { key: 'Escape' })
    fireEvent.keyUp(window, { key: '9' })
    await act(async () => cleanup())
    vi.useRealTimers()
  })

  it('records a new primary shortcut in place and keeps the extra bindings', async () => {
    render(<HomePage />)
    fireEvent.click(
      screen.getByRole('button', { name: 'home.editShortcut: home.modes.dictate.title' }),
    )
    expect(document.querySelector('[data-editing]')).not.toBeNull()
    await waitFor(() =>
      expect(document.querySelector('[data-capture-ready="true"]')).not.toBeNull(),
    )

    fireEvent.keyDown(window, { key: 'k', ctrlKey: true, shiftKey: true })

    fireEvent.keyUp(window, { key: 'k', ctrlKey: true, shiftKey: true })
    expect(dictationBindings()).toEqual([
      { primary: 'K', modifiers: ['Ctrl', 'Shift'] },
      { primary: '9', modifiers: ['Ctrl', 'Option'] },
    ])
    expect(useAppStore.getState().config.hotkeys.dictation).toEqual({
      primary: 'K',
      modifiers: ['Ctrl', 'Shift'],
    })
  })

  it.each([
    { label: 'translate', role: 'translate', bindings: 'translateBindings' },
    { label: 'ask', role: 'ask', bindings: 'askBindings' },
  ] as const)('edits $label directly while preserving other voice shortcuts', async (testCase) => {
    const config = useAppStore.getState().config
    const extra = { primary: '8', modifiers: ['Ctrl', 'Option'] }
    useAppStore.setState({
      config: {
        ...config,
        hotkeys: {
          ...config.hotkeys,
          [testCase.bindings]: [...config.hotkeys[testCase.bindings], extra],
        },
      },
    })
    const before = useAppStore.getState().config.hotkeys
    render(<HomePage />)
    fireEvent.click(
      screen.getByRole('button', {
        name: `home.editShortcut: home.modes.${testCase.label}.title`,
      }),
    )
    await waitFor(() =>
      expect(document.querySelector('[data-capture-ready="true"]')).not.toBeNull(),
    )
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true, shiftKey: true })
    fireEvent.keyUp(window, { key: 'k', ctrlKey: true, shiftKey: true })
    const after = useAppStore.getState().config.hotkeys
    const replacement = { primary: 'K', modifiers: ['Ctrl', 'Shift'] }
    expect(after[testCase.role]).toEqual(replacement)
    expect(after[testCase.bindings]).toEqual([replacement, extra])
    expect(after.dictationBindings).toEqual(before.dictationBindings)
    const otherBindings = testCase.role === 'translate' ? 'askBindings' : 'translateBindings'
    expect(after[otherBindings]).toEqual(before[otherBindings])
    expect(document.querySelector('[data-shortcut-editor]')).toBeNull()
  })

  it('ignores composing keys while recording a shortcut', async () => {
    render(<HomePage />)
    fireEvent.click(
      screen.getByRole('button', { name: 'home.editShortcut: home.modes.dictate.title' }),
    )
    await waitFor(() =>
      expect(document.querySelector('[data-capture-ready="true"]')).not.toBeNull(),
    )
    fireEvent.keyDown(window, {
      key: 'k',
      ctrlKey: true,
      shiftKey: true,
      isComposing: true,
    })
    expect(dictationBindings()[0]).toEqual({ primary: 'Fn', modifiers: [] })
    expect(document.querySelector('[data-editing]')).not.toBeNull()
    fireEvent.keyDown(window, { key: 'Escape', isComposing: true })
    expect(document.querySelector('[data-editing]')).not.toBeNull()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(document.querySelector('[data-editing]')).toBeNull()
  })

  it('opens the existing use-case link through the voice hint flow', async () => {
    render(<HomePage />)
    fireEvent.click(
      screen.getByRole('button', {
        name: 'home.useCases.gmail.name home.useCases.gmail.description',
      }),
    )
    await waitFor(() => expect(openUrl).toHaveBeenCalledWith('https://mail.google.com/'))
  })

  it('persists the edited binding before resuming native shortcuts', async () => {
    vi.mocked(updateConfig).mockClear()
    vi.mocked(resumeHotkey).mockClear()
    let finishSave: () => void = () => {}
    vi.mocked(updateConfig).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishSave = resolve
      }),
    )
    render(<HomePage />)
    fireEvent.click(
      screen.getByRole('button', { name: 'home.editShortcut: home.modes.dictate.title' }),
    )
    await waitFor(() =>
      expect(document.querySelector('[data-capture-ready="true"]')).not.toBeNull(),
    )
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true, shiftKey: true })
    fireEvent.keyUp(window, { key: 'k', ctrlKey: true, shiftKey: true })
    await waitFor(() => expect(updateConfig).toHaveBeenCalledOnce())
    expect(resumeHotkey).not.toHaveBeenCalled()
    expect(vi.mocked(updateConfig).mock.calls[0][0].hotkeys.dictation.primary).toBe('K')
    await act(async () => finishSave())
    fireEvent.keyUp(window, { key: 'k' })
    await waitFor(() => expect(resumeHotkey).toHaveBeenCalled())
    expect(dictationBindings()[0]).toEqual({ primary: 'K', modifiers: ['Ctrl', 'Shift'] })
    expect(useAppStore.getState().savedConfig?.hotkeys.dictation.primary).toBe('K')
  })

  it('reloads the previous binding when saving fails before resuming capture', async () => {
    const previous = useAppStore.getState().config
    vi.mocked(updateConfig).mockRejectedValueOnce(new Error('synthetic save failure'))
    vi.mocked(getConfig).mockResolvedValueOnce(previous)
    vi.mocked(resumeHotkey).mockClear()
    render(<HomePage />)
    fireEvent.click(
      screen.getByRole('button', { name: 'home.editShortcut: home.modes.dictate.title' }),
    )
    await waitFor(() =>
      expect(document.querySelector('[data-capture-ready="true"]')).not.toBeNull(),
    )
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true, shiftKey: true })
    fireEvent.keyUp(window, { key: 'k' })
    await waitFor(() => expect(resumeHotkey).toHaveBeenCalled())
    expect(dictationBindings()).toEqual(previous.hotkeys.dictationBindings)
    expect(useAppStore.getState().savedConfig).toEqual(previous)
  })

  it('refuses a shortcut already used by another voice feature', async () => {
    render(<HomePage />)
    fireEvent.click(
      screen.getByRole('button', { name: 'home.editShortcut: home.modes.dictate.title' }),
    )
    await waitFor(() =>
      expect(document.querySelector('[data-capture-ready="true"]')).not.toBeNull(),
    )
    fireEvent.keyDown(window, { key: '9', ctrlKey: true, altKey: true })
    fireEvent.keyUp(window, { key: '9', ctrlKey: true, altKey: true })

    expect(dictationBindings()[0]).toEqual({ primary: 'Fn', modifiers: [] })
    expect(screen.getByText('shortcuts.errorInUse')).toBeInTheDocument()
  })

  it('cancels editing without changing the shortcut', () => {
    render(<HomePage />)
    fireEvent.click(
      screen.getByRole('button', { name: 'home.editShortcut: home.modes.translate.title' }),
    )
    fireEvent.keyDown(window, { key: 'Escape' })

    expect(document.querySelector('[data-editing]')).toBeNull()
    expect(useAppStore.getState().config.hotkeys.translate).toEqual({
      primary: 'LeftShift',
      modifiers: ['Fn'],
    })
  })
})

describe('HomePage share insights', () => {
  afterEach(() => cleanup())

  it('enables Share once stats load and opens the share dialog', async () => {
    vi.mocked(getUsageStats).mockResolvedValueOnce({
      totalWords: 10,
      totalSessions: 1,
      totalAudioSeconds: 5,
      averageWpm: 120,
      minutesSaved: 0,
      activeDays: 1,
      currentStreak: 1,
      longestStreak: 1,
      daily: [],
    })
    render(<HomePage />)
    const share = screen.getByRole('button', { name: 'home.share.button' })
    expect(share).toBeDisabled()
    await waitFor(() => expect(share).toBeEnabled())
    fireEvent.click(share)
    expect(screen.getByTestId('share-dialog')).toBeInTheDocument()
  })
})
