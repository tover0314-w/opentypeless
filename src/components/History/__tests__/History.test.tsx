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
  updateConfig,
  searchHistoryPage,
} from '../../../lib/tauri'
import { History } from '../index'
import { toast } from '../../toast-service'
import { readSavedAskAnswer as showSavedAskAnswer } from '../../../lib/savedAskAnswer'
import { openUrl } from '@tauri-apps/plugin-opener'

vi.mock('../../../lib/savedAskAnswer', () => ({ readSavedAskAnswer: vi.fn() }))
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn() }))

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
  searchHistoryPage: vi.fn(),
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
    vi.mocked(openUrl).mockResolvedValue(undefined)
    vi.mocked(searchHistoryPage).mockImplementation(async () => useAppStore.getState().history)
    vi.mocked(showSavedAskAnswer).mockImplementation(async (historyId, question, answer) => ({
      historyId,
      question,
      answer,
      citations: [],
    }))
  })

  it('searches older local records beyond the initial loaded history', async () => {
    vi.mocked(searchHistoryPage).mockImplementation(async (query) =>
      query
        ? [{ ...entry, id: 300, raw_text: 'Older match', polished_text: 'Older match' }]
        : [entry],
    )
    render(<History />)
    fireEvent.click(screen.getByRole('button', { name: 'history.search' }))
    fireEvent.change(screen.getByPlaceholderText('history.searchPlaceholder'), {
      target: { value: 'Older match' },
    })
    expect(await screen.findByText('Older match')).toBeInTheDocument()
    expect(searchHistoryPage).toHaveBeenCalledWith('Older match', 'all', 51, null)
  })

  it('loads the next page without duplicating the lookahead record', async () => {
    const firstPage = Array.from({ length: 51 }, (_, index) => ({
      ...entry,
      id: 100 - index,
      polished_text: `Record ${100 - index}`,
    }))
    vi.mocked(searchHistoryPage).mockImplementation(async (_query, _mode, _limit, beforeId) =>
      beforeId ? [{ ...entry, id: 50, polished_text: 'Record 50' }] : firstPage,
    )
    render(<History />)
    fireEvent.click(await screen.findByRole('button', { name: 'history.loadMore' }))
    expect(await screen.findByText('Record 50')).toBeInTheDocument()
    expect(screen.getAllByText('Record 50')).toHaveLength(1)
    expect(searchHistoryPage).toHaveBeenLastCalledWith('', 'all', 51, 51)
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'history.loadMore' })).toBeNull(),
    )
  })

  it('ignores a late result after the search text changes', async () => {
    let finishOldSearch: (entries: HistoryEntry[]) => void = () => {}
    vi.mocked(searchHistoryPage).mockImplementation(async (query) => {
      if (query === 'old') {
        return new Promise<HistoryEntry[]>((resolve) => {
          finishOldSearch = resolve
        })
      }
      return query === 'new' ? [{ ...entry, polished_text: 'new result' }] : [entry]
    })
    render(<History />)
    fireEvent.click(screen.getByRole('button', { name: 'history.search' }))
    const input = screen.getByPlaceholderText('history.searchPlaceholder')
    fireEvent.change(input, { target: { value: 'old' } })
    await waitFor(() => expect(searchHistoryPage).toHaveBeenCalledWith('old', 'all', 51, null))
    fireEvent.change(input, { target: { value: 'new' } })
    expect(await screen.findByText('new result')).toBeInTheDocument()
    await act(async () => finishOldSearch([{ ...entry, polished_text: 'old result' }]))
    expect(screen.queryByText('old result')).toBeNull()
    expect(screen.getByText('new result')).toBeInTheDocument()
  })

  it('shows a load error instead of an empty result and can retry', async () => {
    vi.mocked(searchHistoryPage).mockRejectedValueOnce(new Error('synthetic failure'))
    render(<History />)
    expect(await screen.findByRole('alert')).toHaveTextContent('common.loadFailed')
    expect(screen.queryByText('history.noResults')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'common.retry' }))
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
    expect(await screen.findByText('OpenTypeless')).toBeInTheDocument()
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

  it('reopens a saved answer from an Ask entry in a local modal', async () => {
    render(<History />)
    fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
    expect(showSavedAskAnswer).toHaveBeenCalledWith(
      expect.any(Number),
      'What is the weather?',
      'Sunny and warm.',
    )
    expect(await screen.findByRole('dialog')).toHaveTextContent('Sunny and warm.')
  })

  it('renders saved sources and reports source link failure without losing the answer', async () => {
    vi.mocked(showSavedAskAnswer).mockResolvedValueOnce({
      historyId: askEntry.id,
      question: askEntry.raw_text,
      answer: askEntry.answer_text!,
      citations: [{ url: 'https://example.invalid/source', title: 'Saved source' }],
    })
    vi.mocked(openUrl).mockRejectedValueOnce(new Error('Synthetic link failure'))
    render(<History />)
    fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
    const source = await screen.findByRole('link', { name: 'Saved source' })
    expect(openUrl).not.toHaveBeenCalled()
    fireEvent.click(source)
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('ask.failedToOpenLink'))
    expect(screen.getByRole('dialog')).toHaveTextContent('Sunny and warm.')
    expect(openUrl).toHaveBeenCalledExactlyOnceWith('https://example.invalid/source')
  })

  it('ignores a source link failure after its saved-answer modal closes', async () => {
    vi.mocked(showSavedAskAnswer).mockResolvedValueOnce({
      historyId: askEntry.id,
      question: askEntry.raw_text,
      answer: askEntry.answer_text!,
      citations: [{ url: 'https://example.invalid/source', title: 'Saved source' }],
    })
    let fail!: (error: Error) => void
    vi.mocked(openUrl).mockImplementationOnce(
      () => new Promise((_resolve, reject) => (fail = reject)),
    )
    render(<History />)
    fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
    fireEvent.click(await screen.findByRole('link', { name: 'Saved source' }))
    fireEvent.click(screen.getByRole('button', { name: 'ask.close' }))
    await act(async () => fail(new Error('Synthetic late link failure')))
    expect(toast.error).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('ignores an older source failure after a newer source click succeeds', async () => {
    vi.mocked(showSavedAskAnswer).mockResolvedValueOnce({
      historyId: askEntry.id,
      question: askEntry.raw_text,
      answer: askEntry.answer_text!,
      citations: [
        { url: 'https://example.invalid/old', title: 'Old source' },
        { url: 'https://example.invalid/new', title: 'New source' },
      ],
    })
    let fail!: (error: Error) => void
    vi.mocked(openUrl).mockImplementationOnce(
      () => new Promise((_resolve, reject) => (fail = reject)),
    )
    render(<History />)
    fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
    fireEvent.click(await screen.findByRole('link', { name: 'Old source' }))
    await act(async () => fireEvent.click(screen.getByRole('link', { name: 'New source' })))
    await act(async () => fail(new Error('Synthetic old source failure')))
    expect(toast.error).not.toHaveBeenCalled()
    expect(openUrl).toHaveBeenCalledTimes(2)
    expect(screen.getByRole('dialog')).toHaveTextContent('Sunny and warm.')
  })

  it('reopens sources with the original Ask question rather than polished display text', () => {
    useAppStore.setState({
      history: [{ ...askEntry, raw_text: 'Original question', polished_text: 'Display question' }],
    })
    render(<History />)
    fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
    expect(showSavedAskAnswer).toHaveBeenCalledExactlyOnceWith(
      askEntry.id,
      'Original question',
      askEntry.answer_text,
    )
  })

  it('ignores a successful read after its history record is removed', async () => {
    let finish!: (answer: {
      historyId: number
      question: string
      answer: string
      citations: Array<{ url: string; title: string }>
    }) => void
    vi.mocked(showSavedAskAnswer).mockImplementationOnce(
      () => new Promise((resolve) => (finish = resolve)),
    )
    render(<History />)
    fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
    act(() => useAppStore.setState({ history: [entry] }))
    await act(async () =>
      finish({
        historyId: askEntry.id,
        question: askEntry.raw_text,
        answer: askEntry.answer_text!,
        citations: [],
      }),
    )
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('closes a saved-answer modal when history content changes', async () => {
    render(<History />)
    fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
    await screen.findByRole('dialog')
    act(() =>
      useAppStore.setState({
        history: [entry, { ...askEntry, answer_text: 'Changed answer' }],
      }),
    )
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('reports a failed read without deleting or retrying the answer', async () => {
    vi.mocked(showSavedAskAnswer).mockRejectedValueOnce(new Error('Saved answer read timed out'))
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      render(<History />)
      fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('history.failedToOpenAnswer'))
      expect(showSavedAskAnswer).toHaveBeenCalledTimes(1)
      expect(deleteHistoryEntry).not.toHaveBeenCalled()
      expect(
        useAppStore.getState().history.find((item) => item.id === askEntry.id)?.answer_text,
      ).toBe('Sunny and warm.')
    } finally {
      log.mockRestore()
    }
  })

  it('ignores an older opening failure after a newer saved answer opens', async () => {
    let failOlder!: (error: Error) => void
    vi.mocked(showSavedAskAnswer).mockImplementationOnce(
      () =>
        new Promise<never>((_resolve, reject) => {
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
        new Promise<never>((_resolve, reject) => {
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
      expect(consoleError).toHaveBeenCalledWith('Failed to delete history entry')
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
      expect(consoleError).toHaveBeenCalledWith('Failed to clear history')
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
  it.each(['newer rules', 'unmount'])(
    'does not apply a late correction refresh after %s',
    async (change) => {
      let finishRefresh: (
        rules: ReturnType<typeof useAppStore.getState>['correctionRules'],
      ) => void = () => {}
      vi.mocked(getCorrectionRules).mockReturnValueOnce(
        new Promise((resolve) => {
          finishRefresh = resolve
        }),
      )
      const { unmount } = render(<History />)
      fireEvent.click(rowMenuButton())
      fireEvent.click(screen.getByRole('menuitem', { name: 'history.createCorrection' }))
      fireEvent.click(screen.getByRole('button', { name: 'history.saveCorrection' }))
      await waitFor(() => expect(getCorrectionRules).toHaveBeenCalledOnce())
      const newer = [{ id: 7, pattern: 'new', replacement: 'New', enabled: false }]
      if (change === 'newer rules') act(() => useAppStore.getState().setCorrectionRules(newer))
      else unmount()
      const expected = useAppStore.getState().correctionRules
      await act(async () =>
        finishRefresh([{ id: 2, pattern: 'old', replacement: 'Old', enabled: true }]),
      )
      expect(useAppStore.getState().correctionRules).toBe(expected)
      expect(addCorrectionRule).toHaveBeenCalledOnce()
    },
  )

  it('does not report a committed correction as failed when refreshing rules fails', async () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      vi.mocked(getCorrectionRules).mockRejectedValueOnce(new Error('synthetic-private-refresh'))
      render(<History />)
      fireEvent.click(rowMenuButton())
      fireEvent.click(screen.getByRole('menuitem', { name: 'history.createCorrection' }))
      fireEvent.click(screen.getByRole('button', { name: 'history.saveCorrection' }))
      await waitFor(() => expect(getCorrectionRules).toHaveBeenCalledOnce())
      await waitFor(() =>
        expect(screen.queryByRole('dialog', { name: 'history.createCorrection' })).toBeNull(),
      )
      expect(addCorrectionRule).toHaveBeenCalledOnce()
      expect(toast.success).toHaveBeenCalledWith('history.correctionCreated')
      expect(toast.error).not.toHaveBeenCalledWith('history.failedToCreateCorrection')
      expect(log).toHaveBeenCalledWith('Failed to refresh correction rules after saving')
      expect(log.mock.calls.flat().join(' ')).not.toContain('synthetic-private-refresh')
      expect(useAppStore.getState().correctionRules).toEqual([])
    } finally {
      log.mockRestore()
    }
  })

  it('locks correction fields and dismissal until the pending save completes', async () => {
    let finishSave: () => void = () => {}
    vi.mocked(addCorrectionRule).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishSave = resolve
      }),
    )
    render(<History />)
    fireEvent.click(rowMenuButton())
    fireEvent.click(screen.getByRole('menuitem', { name: 'history.createCorrection' }))
    const save = screen.getByRole('button', { name: 'history.saveCorrection' })
    fireEvent.click(save)
    try {
      expect(screen.getByLabelText('dictionary.wrongPhrase')).toBeDisabled()
      expect(screen.getByLabelText('dictionary.correctPhrase')).toBeDisabled()
      expect(save).toBeDisabled()
      expect(screen.getByRole('button', { name: 'common.cancel' })).toBeDisabled()
      fireEvent.click(save)
      fireEvent.keyDown(window, { key: 'Escape' })
      expect(screen.getByRole('dialog', { name: 'history.createCorrection' })).toBeInTheDocument()
      expect(addCorrectionRule).toHaveBeenCalledOnce()
      expect(addCorrectionRule).toHaveBeenCalledWith('open type less', 'OpenTypeless')
    } finally {
      await act(async () => finishSave())
    }
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'history.createCorrection' })).toBeNull(),
    )
  })

  it.each([{ isComposing: true }, { keyCode: 229 }])(
    'does not dismiss the correction dialog during IME composition %j',
    (keyboardState) => {
      render(<History />)
      fireEvent.click(rowMenuButton())
      fireEvent.click(screen.getByRole('menuitem', { name: 'history.createCorrection' }))
      fireEvent.keyDown(screen.getByLabelText('dictionary.wrongPhrase'), {
        key: 'Escape',
        ...keyboardState,
      })
      expect(screen.getByRole('dialog', { name: 'history.createCorrection' })).toBeInTheDocument()
      expect(screen.getByLabelText('dictionary.wrongPhrase')).toHaveValue('open type less')
    },
  )

  it('contains correction focus and wraps Tab back to the first input', () => {
    render(<History />)
    fireEvent.click(rowMenuButton())
    fireEvent.click(screen.getByRole('menuitem', { name: 'history.createCorrection' }))
    const save = screen.getByRole('button', { name: 'history.saveCorrection' })
    act(() => save.focus())
    fireEvent.keyDown(save, { key: 'Tab' })
    expect(screen.getByLabelText('dictionary.wrongPhrase')).toHaveFocus()
    const panel = screen.getByRole('dialog', { name: 'history.createCorrection' })
    expect(panel.style.maxHeight).toBe('calc(100dvh - 48px)')
    expect(panel.style.overflowY).toBe('auto')
  })

  it('retains correction fields without logging private native errors', async () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      vi.mocked(addCorrectionRule).mockRejectedValueOnce(new Error('synthetic-private-rule'))
      render(<History />)
      fireEvent.click(rowMenuButton())
      fireEvent.click(screen.getByRole('menuitem', { name: 'history.createCorrection' }))
      fireEvent.click(screen.getByRole('button', { name: 'history.saveCorrection' }))
      await waitFor(() =>
        expect(toast.error).toHaveBeenCalledWith('history.failedToCreateCorrection'),
      )
      expect(log).toHaveBeenCalledWith('Failed to create correction from history')
      expect(log.mock.calls.flat().join(' ')).not.toContain('synthetic-private-rule')
      expect(screen.getByLabelText('dictionary.wrongPhrase')).toHaveValue('open type less')
      expect(screen.getByLabelText('dictionary.correctPhrase')).toHaveValue('OpenTypeless')
      expect(useAppStore.getState().correctionRules).toEqual([])
      expect(screen.getByRole('button', { name: 'history.saveCorrection' })).toBeEnabled()
      fireEvent.click(screen.getByRole('button', { name: 'history.saveCorrection' }))
      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('history.correctionCreated'))
      expect(addCorrectionRule).toHaveBeenCalledTimes(2)
    } finally {
      log.mockRestore()
    }
  })

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
