import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { invoke } from '@tauri-apps/api/core'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAppStore, type HistoryEntry } from '../../../stores/appStore'
import { History } from '../index'
import { toast } from '../../toast-service'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-i18next')>()),
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'en' } }),
}))
vi.mock('../../toast-service', () => ({
  toast: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() }),
}))

const entry: HistoryEntry = {
  id: 1,
  created_at: '2026-11-20T00:00:00',
  context_profile_id: 'general.native',
  context_label: 'General',
  context_icon_key: 'general',
  context_family: 'general',
  browser_access_status: 'not_applicable',
  provider_kind: 'local',
  raw_text: 'Synthetic original question',
  polished_text: 'Synthetic display question',
  language: null,
  duration_ms: null,
  active_scene_id: null,
  active_scene_source: null,
  active_scene_name: null,
  active_scene_prompt_chars: null,
  active_scene_prompt_truncated: false,
  output_status: null,
  output_error: null,
  mode: 'ask',
  answer_text: '## Synthetic saved answer',
}
const snapshot = {
  historyId: entry.id,
  question: entry.raw_text,
  answer: entry.answer_text,
  citations: [{ url: 'https://example.invalid/source', title: 'Saved source' }],
}

beforeEach(() => {
  vi.resetAllMocks()
  useAppStore.setState({ history: [entry] })
  useAppStore.getState().setSavedConfig(useAppStore.getState().config)
  vi.mocked(invoke).mockImplementation(async (command) => {
    if (command === 'search_history_page') return [entry]
    if (command === 'read_saved_ask_answer') return snapshot
    if (command === 'get_config') return useAppStore.getState().config
    if (command === 'get_correction_rules') return []
    return null
  })
})
afterEach(cleanup)

it('shows stored selection-task classification without claiming selection recovery', async () => {
  vi.mocked(invoke).mockImplementation(async (command) => {
    if (command === 'read_saved_ask_answer') return { ...snapshot, askIntent: 'ask_selection' }
    if (command === 'search_history_page') return [entry]
    return null
  })
  render(<History />)
  fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
  expect(await screen.findByText('history.savedSelectionQuestion')).toBeInTheDocument()
  expect(screen.getByText('history.savedSelectionNotRetained')).toBeInTheDocument()
})

it('starts one read for repeated clicks on the same record and shows busy state', async () => {
  let finish!: (value: typeof snapshot) => void
  vi.mocked(invoke).mockImplementation((command) =>
    command === 'read_saved_ask_answer'
      ? new Promise((resolve) => (finish = resolve))
      : Promise.resolve(command === 'search_history_page' ? [entry] : null),
  )
  render(<History />)
  const button = screen.getByRole('button', { name: 'history.viewAnswer' })
  fireEvent.click(button)
  fireEvent.click(button)
  expect(
    vi.mocked(invoke).mock.calls.filter(([command]) => command === 'read_saved_ask_answer'),
  ).toHaveLength(1)
  expect(button).toBeDisabled()
  expect(button).toHaveAttribute('aria-busy', 'true')
  await act(async () => finish(snapshot))
  expect(screen.getByRole('dialog')).toHaveTextContent('Synthetic saved answer')
  expect(button).toBeEnabled()
})

it.each(['tab', 'search'] as const)(
  'ignores a pending answer after the history %s changes',
  async (change) => {
    let finish!: (value: typeof snapshot) => void
    vi.mocked(invoke).mockImplementation((command) =>
      command === 'read_saved_ask_answer'
        ? new Promise((resolve) => (finish = resolve))
        : Promise.resolve(command === 'search_history_page' ? [entry] : null),
    )
    render(<History />)
    fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
    if (change === 'tab') {
      fireEvent.click(screen.getByRole('tab', { name: 'history.tabs.dictate' }))
    } else {
      fireEvent.click(screen.getByRole('button', { name: 'history.search' }))
      fireEvent.change(screen.getByPlaceholderText('history.searchPlaceholder'), {
        target: { value: 'Other query' },
      })
    }
    await act(async () => finish(snapshot))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(toast.error).not.toHaveBeenCalled()
  },
)

it('passes actual IPC material through strict parsing into the real history answer modal', async () => {
  render(<History />)
  fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
  expect(await screen.findByRole('dialog')).toHaveTextContent('Synthetic saved answer')
  expect(screen.getByRole('link', { name: 'Saved source' })).toHaveAttribute(
    'href',
    'https://example.invalid/source',
  )
  expect(invoke).toHaveBeenCalledWith('read_saved_ask_answer', {
    historyId: entry.id,
    question: entry.raw_text,
    answer: entry.answer_text,
  })
  expect(
    vi.mocked(invoke).mock.calls.some(([command]) => command === 'show_saved_ask_answer'),
  ).toBe(false)
})

it('contains keyboard focus and restores it to the history trigger after Escape', async () => {
  render(<History />)
  const trigger = screen.getByRole('button', { name: 'history.viewAnswer' })
  trigger.focus()
  fireEvent.click(trigger)
  const dialog = await screen.findByRole('dialog')
  expect(dialog.contains(document.activeElement)).toBe(true)
  const close = screen.getByRole('button', { name: 'ask.close' })
  const source = screen.getByRole('link', { name: 'Saved source' })
  close.focus()
  fireEvent.keyDown(close, { key: 'Tab', shiftKey: true })
  expect(source).toHaveFocus()
  fireEvent.keyDown(source, { key: 'Tab' })
  expect(close).toHaveFocus()
  fireEvent.keyDown(close, { key: 'Escape' })
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(trigger).toHaveFocus()
})

it('rejects extra private source fields before rendering an answer modal', async () => {
  vi.mocked(invoke).mockImplementation(async (command) =>
    command === 'read_saved_ask_answer'
      ? { ...snapshot, citations: [{ ...snapshot.citations[0], content: 'Private source body' }] }
      : command === 'search_history_page'
        ? [entry]
        : null,
  )
  const logging = vi.spyOn(console, 'error').mockImplementation(() => {})
  try {
    render(<History />)
    fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('history.failedToOpenAnswer'))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.queryByText('Private source body')).toBeNull()
  } finally {
    logging.mockRestore()
  }
})

it('does not show a late valid native result after the history page unmounts', async () => {
  let finish!: (value: typeof snapshot) => void
  vi.mocked(invoke).mockImplementation((command) =>
    command === 'read_saved_ask_answer'
      ? new Promise((resolve) => (finish = resolve))
      : Promise.resolve(command === 'search_history_page' ? [entry] : null),
  )
  const { unmount } = render(<History />)
  fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
  unmount()
  await act(async () => finish(snapshot))
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(toast.error).not.toHaveBeenCalled()
})

it('keeps the newer answer when an older valid read finishes later', async () => {
  const newer = {
    ...entry,
    id: 2,
    raw_text: 'New question',
    polished_text: 'New question',
    answer_text: 'New answer',
  }
  const newerSnapshot = {
    historyId: newer.id,
    question: newer.raw_text,
    answer: newer.answer_text,
    citations: [{ url: 'https://example.invalid/new', title: 'New source' }],
  }
  useAppStore.setState({ history: [entry, newer] })
  let finish!: (value: typeof snapshot) => void
  vi.mocked(invoke).mockImplementation((command, args) => {
    if (command === 'search_history_page') return Promise.resolve([entry, newer])
    if (command === 'read_saved_ask_answer') {
      if ((args as { historyId: number }).historyId === entry.id) {
        return new Promise((resolve) => (finish = resolve))
      }
      return Promise.resolve(newerSnapshot)
    }
    return Promise.resolve(null)
  })
  render(<History />)
  const buttons = screen.getAllByRole('button', { name: 'history.viewAnswer' })
  fireEvent.click(buttons[0])
  fireEvent.click(buttons[1])
  expect(await screen.findByRole('dialog')).toHaveTextContent('New answer')
  await act(async () => finish(snapshot))
  expect(screen.getByRole('dialog')).toHaveTextContent('New answer')
  expect(screen.getByRole('link', { name: 'New source' })).toBeInTheDocument()
  expect(screen.queryByRole('link', { name: 'Saved source' })).toBeNull()
  expect(toast.error).not.toHaveBeenCalled()
})

it('does not fall back to a cached answer when the native record is absent', async () => {
  vi.mocked(invoke).mockImplementation(async (command) =>
    command === 'search_history_page' ? [entry] : null,
  )
  render(<History />)
  fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
  await waitFor(() => expect(toast.error).toHaveBeenCalledWith('history.failedToOpenAnswer'))
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(useAppStore.getState().history[0].answer_text).toBe(entry.answer_text)
})

it('keeps a replacement read busy when an older read finishes', async () => {
  const newer = {
    ...entry,
    id: 2,
    raw_text: 'New question',
    polished_text: 'New question',
    answer_text: 'New answer',
  }
  useAppStore.setState({ history: [entry, newer] })
  const completions: Array<(value: unknown) => void> = []
  vi.mocked(invoke).mockImplementation((command) =>
    command === 'read_saved_ask_answer'
      ? new Promise((resolve) => completions.push(resolve))
      : Promise.resolve(command === 'search_history_page' ? [entry, newer] : null),
  )
  render(<History />)
  const buttons = screen.getAllByRole('button', { name: 'history.viewAnswer' })
  fireEvent.click(buttons[0])
  fireEvent.click(buttons[1])
  await act(async () => completions[0](snapshot))
  expect(buttons[1]).toBeDisabled()
  expect(buttons[1]).toHaveAttribute('aria-busy', 'true')
  expect(screen.queryByRole('dialog')).toBeNull()
  await act(async () =>
    completions[1]({
      historyId: newer.id,
      question: newer.raw_text,
      answer: newer.answer_text,
      citations: [],
    }),
  )
  expect(buttons[1]).toBeEnabled()
  expect(screen.getByRole('dialog')).toHaveTextContent('New answer')
})

it('releases the same-record read gate after failure and permits an explicit retry', async () => {
  let reads = 0
  vi.mocked(invoke).mockImplementation(async (command) => {
    if (command === 'search_history_page') return [entry]
    if (command === 'read_saved_ask_answer') {
      reads += 1
      if (reads === 1) throw new Error('Synthetic read failure')
      return snapshot
    }
    return null
  })
  const logging = vi.spyOn(console, 'error').mockImplementation(() => {})
  try {
    render(<History />)
    fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'history.viewAnswer' })).toBeEnabled(),
    )
    expect(toast.error).toHaveBeenCalledWith('history.failedToOpenAnswer')
    expect(reads).toBe(1)
    fireEvent.click(screen.getByRole('button', { name: 'history.viewAnswer' }))
    expect(await screen.findByRole('dialog')).toHaveTextContent('Synthetic saved answer')
    expect(reads).toBe(2)
  } finally {
    logging.mockRestore()
  }
})
