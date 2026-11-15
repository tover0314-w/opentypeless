import React from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseOption, selectedValue } from '../../shared/__tests__/selectTestUtils'
import { useAppStore, type HistoryEntry } from '../../../stores/appStore'
import {
  addCorrectionRule,
  clearHistory,
  deleteHistoryEntry,
  exportHistoryAudio,
  getCorrectionRules,
  getConfig,
  pruneHistoryRetention,
  showSavedAskAnswer,
  updateConfig,
} from '../../../lib/tauri'
import { History } from '../index'
import { toast } from '../../toast-service'

vi.mock('../../toast-service', () => ({
  toast: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() }),
}))

vi.mock('framer-motion', () => ({
  AnimatePresence: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  motion: new Proxy(
    {},
    {
      get:
        (_target, tag: string) =>
        ({ children, ...props }: React.HTMLAttributes<HTMLElement>) =>
          React.createElement(tag, props, children),
    },
  ),
}))

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-i18next')>()),
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'en' } }),
}))

vi.mock('../../../lib/tauri', () => ({
  exportHistoryAudio: vi.fn().mockResolvedValue('/Downloads/recording.wav'),
  addCorrectionRule: vi.fn().mockResolvedValue(undefined),
  clearHistory: vi.fn().mockResolvedValue(undefined),
  deleteHistoryEntry: vi.fn().mockResolvedValue(true),
  getCorrectionRules: vi.fn().mockResolvedValue([]),
  showSavedAskAnswer: vi.fn().mockResolvedValue(undefined),
  retryHistoryTranscription: vi.fn().mockResolvedValue('recovered'),
  cancelTranscriptionRetry: vi.fn().mockResolvedValue(true),
  updateConfig: vi.fn().mockResolvedValue(undefined),
  getConfig: vi.fn(),
  pruneHistoryRetention: vi.fn().mockResolvedValue([]),
  getHotkeyRegistrationError: vi.fn().mockResolvedValue(null),
  setAutoStart: vi.fn().mockResolvedValue(undefined),
}))

const entry: HistoryEntry = {
  id: 1,
  created_at: new Date().toISOString(),
  context_profile_id: 'chat.slack',
  context_label: 'Slack',
  context_icon_key: 'slack',
  context_family: 'work_chat',
  browser_access_status: 'not_applicable',
  provider_kind: 'local',
  raw_text: 'open type less',
  polished_text: 'OpenTypeless',
  language: 'en',
  duration_ms: 1000,
  active_scene_id: null,
  active_scene_source: null,
  active_scene_name: null,
  active_scene_prompt_chars: null,
  active_scene_prompt_truncated: false,
  output_status: null,
  output_error: null,
}

function rowMenuButton() {
  return document.querySelector('[data-history-menu-trigger]') as HTMLButtonElement
}

const askEntry: HistoryEntry = {
  ...entry,
  id: 2,
  raw_text: 'What is the weather?',
  polished_text: 'What is the weather?',
  mode: 'ask',
  answer_text: 'Sunny and warm.',
}

describe('History page', () => {
  it.each(['', ' \n\t'])('does not offer an empty saved answer case %#', (answer) => {
    useAppStore.setState({ history: [{ ...askEntry, answer_text: answer }] })
    render(<History />)
    expect(screen.queryByRole('button', { name: 'history.viewAnswer' })).toBeNull()
    expect(screen.getByText('What is the weather?')).toBeInTheDocument()
    expect(showSavedAskAnswer).not.toHaveBeenCalled()
  })

  it('treats whitespace-only transcripts as silent instead of offering copy', () => {
    useAppStore.setState({
      history: [{ ...entry, raw_text: ' \n ', polished_text: '\t', has_audio: true }],
    })
    render(<History />)
    expect(screen.getByText('history.item.silent')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'history.item.copyTranscript' })).toBeNull()
  })

  it('shows the raw transcript when polished text contains only whitespace', () => {
    useAppStore.setState({
      history: [{ ...entry, raw_text: 'Raw words', polished_text: ' \n ' }],
    })
    render(<History />)
    expect(screen.getByText('Raw words')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'history.item.copyTranscript' })).toBeInTheDocument()
  })

  const recordingOnly = (status: string): HistoryEntry => ({
    ...entry,
    raw_text: '',
    polished_text: '',
    has_audio: true,
    output_status: status,
  })

  it.each([
    ['dismissed', 'history.item.dismissed', 'history.item.dismissedTooltip'],
    ['silent', 'history.item.silent', 'history.item.silentTooltip'],
  ])('renders a %s take as muted state copy with an explanation', (status, text, tip) => {
    useAppStore.setState({ history: [recordingOnly(status)] })
    render(<History />)
    expect(screen.getByText(text)).toHaveClass('min-w-0')
    expect(screen.getByRole('tooltip', { name: tip })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'history.item.copyTranscript' })).toBeNull()
    expect(screen.getByRole('button', { name: 'history.item.retry' })).toBeEnabled()
  })

  it('renders an interrupted take in the error colour without a tooltip', () => {
    useAppStore.setState({ history: [recordingOnly('recognition_failed')] })
    render(<History />)
    expect(screen.getByText('history.item.interrupted')).toHaveClass('text-error')
    expect(
      screen
        .queryAllByRole('tooltip')
        .filter((tooltip) => tooltip.textContent !== 'history.feedback.title'),
    ).toEqual([])
    fireEvent.click(rowMenuButton())
    expect(screen.queryByRole('menuitem', { name: 'history.item.retry' })).toBeNull()
    expect(screen.getByRole('menuitem', { name: 'history.item.downloadAudio' })).toBeEnabled()
  })

  it('opens the retry dialog from the inline Retry button of an unfinished take', () => {
    useAppStore.setState({ history: [recordingOnly('dismissed')] })
    render(<History />)
    fireEvent.click(screen.getByRole('button', { name: 'history.item.retry' }))
    expect(screen.getByRole('dialog', { name: 'history.retryRecording' })).toBeInTheDocument()
  })

  it('keeps Retry for a finished transcript inside the More menu', () => {
    useAppStore.setState({ history: [{ ...entry, has_audio: true }] })
    render(<History />)
    expect(screen.queryByRole('button', { name: 'history.item.retry' })).toBeNull()
    expect(screen.getByRole('button', { name: 'history.item.copyTranscript' })).toBeInTheDocument()
    fireEvent.click(rowMenuButton())
    fireEvent.click(screen.getByRole('menuitem', { name: 'history.item.retry' }))
    expect(screen.getByRole('dialog', { name: 'history.retryRecording' })).toBeInTheDocument()
  })

  it('disables Retry when an unfinished take has no saved recording', () => {
    useAppStore.setState({ history: [{ ...recordingOnly('dismissed'), has_audio: false }] })
    render(<History />)
    expect(screen.getByRole('button', { name: 'history.item.retry' })).toBeDisabled()
  })

  it('exports audio only for entries that have a local recording', async () => {
    useAppStore.setState({ history: [{ ...entry, has_audio: true }] })
    render(<History />)
    fireEvent.click(rowMenuButton())
    fireEvent.click(screen.getByRole('menuitem', { name: 'history.item.downloadAudio' }))
    await waitFor(() => expect(exportHistoryAudio).toHaveBeenCalledWith(entry.id))
  })

  it('does not offer a fake download for old text-only history', () => {
    useAppStore.setState({ history: [{ ...entry, has_audio: false }] })
    render(<History />)
    fireEvent.click(rowMenuButton())
    expect(screen.getByRole('menuitem', { name: 'history.item.downloadAudio' })).toBeDisabled()
    expect(screen.getByText('history.item.noSavedAudio')).toBeInTheDocument()
  })
  beforeEach(() => {
    useAppStore.setState({ history: [entry, askEntry] })
    useAppStore.getState().setSavedConfig(useAppStore.getState().config)
    vi.clearAllMocks()
  })

  afterEach(async () => {
    await act(async () => cleanup())
    useAppStore.setState(useAppStore.getInitialState())
  })

  it('saves confirmed retention before cleanup and refreshes the history list', async () => {
    const { unmount } = render(<History />)
    let finishSave: (() => void) | undefined
    vi.mocked(updateConfig).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishSave = resolve
        }),
    )
    vi.mocked(pruneHistoryRetention).mockResolvedValueOnce([askEntry])
    chooseOption(screen.getByRole('combobox', { name: 'history.keepHistory' }), 'week')
    fireEvent.click(screen.getByRole('button', { name: 'history.confirmRetention' }))
    await waitFor(() => expect(updateConfig).toHaveBeenCalledOnce())
    expect(pruneHistoryRetention).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'history.confirmRetention' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'history.confirmRetention' }))
    fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' })
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    await act(async () => finishSave?.())
    await waitFor(() => expect(pruneHistoryRetention).toHaveBeenCalledWith(true, 7))
    await waitFor(() => expect(useAppStore.getState().history).toEqual([askEntry]))
    await act(async () => unmount())
    expect(updateConfig).toHaveBeenCalledOnce()
    expect(vi.mocked(updateConfig).mock.calls[0][0].history_retention_days).toBe(7)
    expect(clearHistory).not.toHaveBeenCalled()
    expect(useAppStore.getState().history).toEqual([askEntry])
  })

  it('restores persisted privacy settings without clearing history when saving fails', async () => {
    const previous = useAppStore.getState().config
    vi.mocked(updateConfig).mockRejectedValueOnce(new Error('Synthetic privacy save failure'))
    vi.mocked(getConfig).mockResolvedValueOnce(previous)
    render(<History />)
    chooseOption(screen.getByRole('combobox', { name: 'history.keepHistory' }), 'never')
    fireEvent.click(screen.getByRole('button', { name: 'history.confirmRetention' }))
    expect(useAppStore.getState().config.history_enabled).toBe(false)
    await waitFor(() => expect(getConfig).toHaveBeenCalledOnce())
    await waitFor(() => expect(useAppStore.getState().config.history_enabled).toBe(true))
    expect(useAppStore.getState().savedConfig).toEqual(previous)
    expect(useAppStore.getState().history).toEqual([entry, askEntry])
    expect(clearHistory).not.toHaveBeenCalled()
    expect(pruneHistoryRetention).not.toHaveBeenCalled()
    expect(toast).toHaveBeenCalledWith('Synthetic privacy save failure', 'error')
  })

  it('keeps the list and confirmation available when retention cleanup fails', async () => {
    vi.mocked(pruneHistoryRetention).mockRejectedValueOnce(new Error('Synthetic cleanup failure'))
    render(<History />)
    chooseOption(screen.getByRole('combobox', { name: 'history.keepHistory' }), 'never')
    fireEvent.click(screen.getByRole('button', { name: 'history.confirmRetention' }))
    await waitFor(() => expect(pruneHistoryRetention).toHaveBeenCalledWith(false, 0))
    expect(useAppStore.getState().history).toEqual([entry, askEntry])
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(toast.error).toHaveBeenCalledWith('history.failedToClear')
  })

  it('does not clean history when a newer local privacy choice arrives during saving', async () => {
    let finishSave: (() => void) | undefined
    vi.mocked(updateConfig).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishSave = resolve
        }),
    )
    render(<History />)
    chooseOption(screen.getByRole('combobox', { name: 'history.keepHistory' }), 'never')
    fireEvent.click(screen.getByRole('button', { name: 'history.confirmRetention' }))
    await waitFor(() => expect(updateConfig).toHaveBeenCalledOnce())
    act(() => {
      useAppStore.getState().updateConfig({ history_enabled: true, history_retention_days: 0 })
    })
    await act(async () => finishSave?.())
    expect(pruneHistoryRetention).not.toHaveBeenCalled()
    expect(useAppStore.getState().history).toEqual([entry, askEntry])
  })

  it('keeps busy Tab focus inside the confirmation and restores the retention trigger', async () => {
    let finishCleanup: (() => void) | undefined
    vi.mocked(pruneHistoryRetention).mockImplementationOnce(
      () =>
        new Promise<HistoryEntry[]>((resolve) => {
          finishCleanup = () => resolve([])
        }),
    )
    render(<History />)
    const trigger = screen.getByRole('combobox', { name: 'history.keepHistory' })
    chooseOption(trigger, 'never')
    fireEvent.click(screen.getByRole('button', { name: 'history.confirmRetention' }))
    await waitFor(() => expect(pruneHistoryRetention).toHaveBeenCalledOnce())
    const cancel = screen.getByRole('button', { name: 'common.cancel' })
    cancel.focus()
    const prevented = !fireEvent.keyDown(cancel, { key: 'Tab', cancelable: true })
    expect(prevented).toBe(true)
    expect(cancel).toHaveFocus()
    await act(async () => finishCleanup?.())
    await waitFor(() => expect(trigger).toHaveFocus())
  })

  it('clears existing rows only after Never is explicitly confirmed', async () => {
    render(<History />)
    chooseOption(screen.getByRole('combobox', { name: 'history.keepHistory' }), 'never')
    expect(pruneHistoryRetention).not.toHaveBeenCalled()
    expect(useAppStore.getState().history).toEqual([entry, askEntry])
    fireEvent.click(screen.getByRole('button', { name: 'history.confirmRetention' }))
    await waitFor(() => expect(pruneHistoryRetention).toHaveBeenCalledWith(false, 0))
    await waitFor(() => expect(useAppStore.getState().history).toEqual([]))
  })

  it.each([false, true])(
    'does not replace newer history with a late cleanup response after leaving=%s',
    async (leavePage) => {
      let finishCleanup: (() => void) | undefined
      vi.mocked(pruneHistoryRetention).mockImplementationOnce(
        () =>
          new Promise<HistoryEntry[]>((resolve) => {
            finishCleanup = () => resolve([])
          }),
      )
      const { unmount } = render(<History />)
      chooseOption(screen.getByRole('combobox', { name: 'history.keepHistory' }), 'never')
      fireEvent.click(screen.getByRole('button', { name: 'history.confirmRetention' }))
      await waitFor(() => expect(pruneHistoryRetention).toHaveBeenCalledOnce())
      if (leavePage) await act(async () => unmount())
      const newer = { ...entry, id: 99, polished_text: 'Newer history refresh' }
      act(() => useAppStore.getState().setHistory([newer]))
      await act(async () => finishCleanup?.())
      expect(useAppStore.getState().history).toEqual([newer])
    },
  )

  it('does not start cleanup after leaving while the setting is still saving', async () => {
    let finishSave: (() => void) | undefined
    vi.mocked(updateConfig).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishSave = resolve
        }),
    )
    const { unmount } = render(<History />)
    chooseOption(screen.getByRole('combobox', { name: 'history.keepHistory' }), 'never')
    fireEvent.click(screen.getByRole('button', { name: 'history.confirmRetention' }))
    await waitFor(() => expect(updateConfig).toHaveBeenCalledOnce())
    await act(async () => unmount())
    await act(async () => finishSave?.())
    expect(pruneHistoryRetention).not.toHaveBeenCalled()
  })

  it('filters dictations and Ask anything entries with tabs', () => {
    render(<History />)
    expect(screen.getByText('OpenTypeless')).toBeInTheDocument()
    expect(screen.getByText('What is the weather?')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: 'history.tabs.ask' }))
    expect(screen.queryByText('OpenTypeless')).toBeNull()
    expect(screen.getByText('What is the weather?')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: 'history.tabs.dictate' }))
    expect(screen.getByText('OpenTypeless')).toBeInTheDocument()
    expect(screen.queryByText('What is the weather?')).toBeNull()
  })

  it('reopens a saved answer from an Ask entry', () => {
    render(<History />)
    fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
    expect(showSavedAskAnswer).toHaveBeenCalledWith('What is the weather?', 'Sunny and warm.')
  })

  it('ignores an older opening failure after a newer saved answer opens', async () => {
    let failOlder!: (error: Error) => void
    vi.mocked(showSavedAskAnswer).mockImplementationOnce(
      () =>
        new Promise<void>((_resolve, reject) => {
          failOlder = reject
        }),
    )
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      useAppStore.setState({
        history: [
          askEntry,
          {
            ...askEntry,
            id: 3,
            raw_text: 'New question',
            polished_text: 'New question',
            answer_text: 'New answer',
          },
        ],
      })
      render(<History />)
      const buttons = screen.getAllByRole('button', { name: 'history.viewAnswer' })
      fireEvent.click(buttons[0])
      await act(async () => fireEvent.click(buttons[1]))
      await act(async () => failOlder(new Error('Synthetic old failure')))
      expect(showSavedAskAnswer).toHaveBeenCalledTimes(2)
      expect(toast.error).not.toHaveBeenCalled()
      expect(log).not.toHaveBeenCalled()
    } finally {
      log.mockRestore()
    }
  })

  it('ignores a saved-answer opening failure after leaving the history page', async () => {
    let fail!: (error: Error) => void
    vi.mocked(showSavedAskAnswer).mockImplementationOnce(
      () =>
        new Promise<void>((_resolve, reject) => {
          fail = reject
        }),
    )
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      const { unmount } = render(<History />)
      fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
      unmount()
      await act(async () => fail(new Error('Synthetic late opening failure')))
      expect(toast.error).not.toHaveBeenCalled()
      expect(log).not.toHaveBeenCalled()
    } finally {
      log.mockRestore()
    }
  })

  it('keeps a saved answer when reopening fails without logging private diagnostics', async () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(showSavedAskAnswer).mockRejectedValueOnce(
      new Error('synthetic-private-answer-detail'),
    )
    const previous = useAppStore.getState().history
    try {
      render(<History />)
      fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
      await waitFor(() =>
        expect(log).toHaveBeenCalledExactlyOnceWith('Failed to open saved answer'),
      )
      expect(toast.error).toHaveBeenCalledWith('history.failedToOpenAnswer')
      expect(useAppStore.getState().history).toBe(previous)
    } finally {
      log.mockRestore()
    }
  })

  it('deletes a single entry after confirmation', async () => {
    render(<History />)
    fireEvent.click(rowMenuButton())
    fireEvent.click(screen.getByRole('menuitem', { name: 'history.item.deleteTranscript' }))
    expect(deleteHistoryEntry).not.toHaveBeenCalled()

    const dialog = screen.getByRole('alertdialog', { name: 'history.item.deleteTitle' })
    expect(dialog).toHaveTextContent('history.item.deleteDescription')
    fireEvent.click(screen.getByRole('button', { name: 'history.item.delete' }))
    await waitFor(() => expect(deleteHistoryEntry).toHaveBeenCalledWith(1))
    expect(useAppStore.getState().history.map((item) => item.id)).toEqual([2])
  })

  it('clears an already missing entry without claiming a new deletion', async () => {
    vi.mocked(deleteHistoryEntry).mockResolvedValueOnce(false)
    render(<History />)
    fireEvent.click(rowMenuButton())
    fireEvent.click(screen.getByRole('menuitem', { name: 'history.item.deleteTranscript' }))
    fireEvent.click(screen.getByRole('button', { name: 'history.item.delete' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(useAppStore.getState().history.map((item) => item.id)).toEqual([2])
    expect(toast.success).not.toHaveBeenCalled()
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('preserves history and the confirmation when deleting an entry fails', async () => {
    vi.mocked(deleteHistoryEntry).mockRejectedValueOnce(new Error('synthetic delete failure'))
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      render(<History />)
      fireEvent.click(rowMenuButton())
      fireEvent.click(screen.getByRole('menuitem', { name: 'history.item.deleteTranscript' }))
      fireEvent.click(screen.getByRole('button', { name: 'history.item.delete' }))

      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('history.failedToDelete'))
      expect(useAppStore.getState().history).toEqual([entry, askEntry])
      expect(
        screen.getByRole('alertdialog', { name: 'history.item.deleteTitle' }),
      ).toBeInTheDocument()
      expect(toast.success).not.toHaveBeenCalled()
    } finally {
      consoleError.mockRestore()
    }
  })

  it('preserves history and the confirmation when clearing history fails', async () => {
    vi.mocked(clearHistory).mockRejectedValueOnce(new Error('synthetic clear failure'))
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      render(<History />)
      fireEvent.click(screen.getAllByRole('button', { name: 'history.moreActions' })[0])
      fireEvent.click(screen.getByRole('menuitem', { name: 'history.clearAll' }))
      fireEvent.click(screen.getByRole('button', { name: 'history.confirmClear' }))

      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('history.failedToClear'))
      expect(useAppStore.getState().history).toEqual([entry, askEntry])
      expect(screen.getByText('history.clearConfirm')).toBeInTheDocument()
      expect(toast.success).not.toHaveBeenCalled()
    } finally {
      consoleError.mockRestore()
    }
  })

  it('maps the keep-history choice onto retention settings', async () => {
    render(<History />)
    const select = screen.getByRole('combobox', { name: 'history.keepHistory' })
    expect(selectedValue(select)).toBe('forever')

    chooseOption(select, 'week')
    expect(useAppStore.getState().config.history_retention_days).toBe(0)
    fireEvent.click(screen.getByRole('button', { name: 'history.confirmRetention' }))
    expect(useAppStore.getState().config.history_enabled).toBe(true)
    expect(useAppStore.getState().config.history_retention_days).toBe(7)
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())

    chooseOption(select, 'never')
    expect(useAppStore.getState().config.history_enabled).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'history.confirmRetention' }))
    expect(useAppStore.getState().config.history_enabled).toBe(false)
  })

  it('does not change retention when the deletion confirmation is cancelled', () => {
    render(<History />)
    chooseOption(screen.getByRole('combobox', { name: 'history.keepHistory' }), 'never')
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
    expect(useAppStore.getState().config.history_enabled).toBe(true)
    expect(useAppStore.getState().config.history_retention_days).toBe(0)
  })
})

describe('History correction creation', () => {
  beforeEach(() => {
    useAppStore.setState({ history: [entry], correctionRules: [] })
    vi.clearAllMocks()
    vi.mocked(addCorrectionRule).mockResolvedValue(undefined)
    vi.mocked(getCorrectionRules).mockResolvedValue([
      { id: 2, pattern: 'open type less', replacement: 'OpenTypeless', enabled: true },
    ])
  })

  afterEach(() => {
    cleanup()
    useAppStore.setState(useAppStore.getInitialState())
  })

  it('does not infer a correction until the user explicitly opens and saves it', async () => {
    render(<History />)
    expect(addCorrectionRule).not.toHaveBeenCalled()

    const menuButton = rowMenuButton()
    fireEvent.click(menuButton)
    fireEvent.click(screen.getByRole('menuitem', { name: 'history.createCorrection' }))

    const dialog = screen.getByRole('dialog', { name: 'history.createCorrection' })
    expect(dialog).toBeInTheDocument()
    expect(screen.getByLabelText('dictionary.wrongPhrase')).toHaveValue('open type less')
    expect(screen.getByLabelText('dictionary.correctPhrase')).toHaveValue('OpenTypeless')
    expect(addCorrectionRule).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'history.saveCorrection' }))
    await waitFor(() => {
      expect(addCorrectionRule).toHaveBeenCalledWith('open type less', 'OpenTypeless')
      expect(getCorrectionRules).toHaveBeenCalled()
      expect(useAppStore.getState().correctionRules).toHaveLength(1)
    })
  })

  it('leaves fields empty instead of truncating an overlong history source', () => {
    useAppStore.setState({
      history: [{ ...entry, raw_text: 'x'.repeat(121), polished_text: 'y'.repeat(121) }],
    })
    render(<History />)

    fireEvent.click(rowMenuButton())
    fireEvent.click(screen.getByRole('menuitem', { name: 'history.createCorrection' }))

    expect(screen.getByLabelText('dictionary.wrongPhrase')).toHaveValue('')
    expect(screen.getByLabelText('dictionary.correctPhrase')).toHaveValue('')
    expect(screen.getByLabelText('dictionary.wrongPhrase')).toHaveFocus()
  })

  it('closes the row menu on Escape and restores its trigger focus', async () => {
    render(<History />)
    const menuButton = rowMenuButton()

    fireEvent.click(menuButton)
    expect(screen.getByRole('menu')).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Escape' })

    expect(screen.queryByRole('menu')).toBeNull()
    await waitFor(() => expect(rowMenuButton()).toHaveFocus())
  })

  it('clears history through an in-app confirmation instead of window.confirm', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm')
    render(<History />)

    fireEvent.click(screen.getAllByRole('button', { name: 'history.moreActions' })[0])
    fireEvent.click(screen.getByRole('menuitem', { name: 'history.clearAll' }))
    expect(confirmSpy).not.toHaveBeenCalled()
    expect(screen.getByText('history.clearConfirm')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'history.confirmClear' }))

    await waitFor(() => {
      expect(clearHistory).toHaveBeenCalled()
      expect(useAppStore.getState().history).toEqual([])
    })
    confirmSpy.mockRestore()
  })

  it.each([
    ['a finished transcript', entry],
    ['a dismissed take', { ...entry, raw_text: '', polished_text: '', output_status: 'dismissed' }],
    ['an Ask entry', askEntry],
  ])('offers a hover Feedback flag on %s that opens the feedback dialog', (_label, item) => {
    useAppStore.setState({ history: [item as HistoryEntry] })
    render(<History />)
    const flag = screen.getByRole<HTMLButtonElement>('button', { name: 'history.feedback.title' })
    expect(flag).toHaveClass('opacity-0', 'group-hover:opacity-100')
    const actions = Array.from(flag.closest('.shrink-0')!.querySelectorAll('button'))
    expect(actions.indexOf(flag)).toBeLessThan(actions.indexOf(rowMenuButton()))
    fireEvent.click(flag)
    expect(screen.getByRole('dialog', { name: 'history.feedback.title' })).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('dialog', { name: 'history.feedback.title' })).toBeNull()
  })
})
