import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAppStore, type HistoryEntry } from '../../../stores/appStore'
import {
  addCorrectionRule,
  clearHistory,
  deleteHistoryEntry,
  getCorrectionRules,
  showSavedAskAnswer,
} from '../../../lib/tauri'
import { History } from '../index'

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

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'en' } }),
}))

vi.mock('../../../lib/tauri', () => ({
  addCorrectionRule: vi.fn().mockResolvedValue(undefined),
  clearHistory: vi.fn().mockResolvedValue(undefined),
  deleteHistoryEntry: vi.fn().mockResolvedValue(true),
  getCorrectionRules: vi.fn().mockResolvedValue([]),
  showSavedAskAnswer: vi.fn().mockResolvedValue(undefined),
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
  beforeEach(() => {
    useAppStore.setState({ history: [entry, askEntry] })
    vi.clearAllMocks()
  })

  afterEach(() => {
    cleanup()
    useAppStore.setState(useAppStore.getInitialState())
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

  it('deletes a single entry after confirmation', async () => {
    render(<History />)
    fireEvent.click(rowMenuButton())
    fireEvent.click(screen.getByRole('menuitem', { name: 'history.deleteEntry' }))
    expect(deleteHistoryEntry).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'history.deleteEntry' }))
    await waitFor(() => expect(deleteHistoryEntry).toHaveBeenCalledWith(1))
    expect(useAppStore.getState().history.map((item) => item.id)).toEqual([2])
  })

  it('maps the keep-history choice onto retention settings', () => {
    render(<History />)
    const select = screen.getByLabelText('history.keepHistory')
    expect(select).toHaveValue('forever')

    fireEvent.change(select, { target: { value: 'week' } })
    expect(useAppStore.getState().config.history_enabled).toBe(true)
    expect(useAppStore.getState().config.history_retention_days).toBe(7)

    fireEvent.change(select, { target: { value: 'never' } })
    expect(useAppStore.getState().config.history_enabled).toBe(false)
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
})
