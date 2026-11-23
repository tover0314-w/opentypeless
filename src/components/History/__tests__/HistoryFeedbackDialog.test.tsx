import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAppStore, type HistoryEntry } from '../../../stores/appStore'
import { HistoryFeedbackDialog } from '../HistoryFeedbackDialog'

const mocks = vi.hoisted(() => ({
  openUrl: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: mocks.openUrl }))
vi.mock('@tauri-apps/api/app', () => ({ getVersion: vi.fn().mockResolvedValue('1.1.64') }))
vi.mock('../../toast-service', () => ({
  toast: { success: mocks.toastSuccess, error: mocks.toastError },
}))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'en' } }),
}))

const entry: HistoryEntry = {
  id: 3,
  created_at: '2026-11-03T08:00:00.000Z',
  context_profile_id: 'chat.slack',
  context_label: 'Slack',
  context_icon_key: 'slack',
  context_family: 'work_chat',
  browser_access_status: 'not_applicable',
  provider_kind: 'local',
  raw_text: 'open type less',
  polished_text: 'OpenTypeless rocks',
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

function renderDialog(target: HistoryEntry = entry) {
  const onClose = vi.fn()
  render(<HistoryFeedbackDialog entry={target} onClose={onClose} />)
  return { onClose, textarea: screen.getByRole('textbox', { name: 'history.feedback.title' }) }
}

const sendButton = () => screen.getByRole('button', { name: 'history.feedback.send' })

beforeEach(() => {
  mocks.openUrl.mockResolvedValue(undefined)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('HistoryFeedbackDialog', () => {
  it('does not open an external draft when mounted or when feedback is typed', () => {
    const { textarea, onClose } = renderDialog()
    fireEvent.change(textarea, { target: { value: 'synthetic local feedback' } })
    expect(mocks.openUrl).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
  })

  it('blocks dismissal and duplicate sends while the external draft is opening', async () => {
    let finishOpening: () => void = () => {}
    mocks.openUrl.mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishOpening = resolve
      }),
    )
    const { textarea, onClose } = renderDialog()
    fireEvent.change(textarea, { target: { value: 'synthetic pending feedback' } })
    fireEvent.click(sendButton())

    expect(sendButton()).toBeDisabled()
    expect(screen.getByRole('button', { name: 'common.close' })).toBeDisabled()
    fireEvent.keyDown(window, { key: 'Escape' })
    fireEvent.keyDown(textarea, { key: 'Enter', ctrlKey: true })
    expect(onClose).not.toHaveBeenCalled()
    expect(mocks.openUrl).toHaveBeenCalledTimes(1)

    finishOpening()
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
    expect(mocks.toastSuccess).toHaveBeenCalledWith('history.feedback.opened')
  })

  it('shows the raw transcript when polished text is whitespace', () => {
    renderDialog({ ...entry, raw_text: 'Raw words', polished_text: ' \n ' })
    expect(screen.getByTestId('history-feedback-transcript')).toHaveTextContent('Raw words')
  })

  it('quotes the transcript and focuses an empty feedback box with Send disabled', () => {
    const { textarea } = renderDialog()
    expect(screen.getByRole('dialog', { name: 'history.feedback.title' })).toBeInTheDocument()
    expect(screen.getByText('history.feedback.transcript')).toBeInTheDocument()
    expect(screen.getByTestId('history-feedback-transcript')).toHaveTextContent(
      'OpenTypeless rocks',
    )
    expect(screen.getByRole('tooltip')).toHaveTextContent('history.feedback.includedTooltip')
    expect(textarea).toHaveFocus()
    expect(textarea).toHaveAttribute('placeholder', 'history.feedback.placeholder')
    expect(sendButton()).toBeDisabled()
    fireEvent.change(textarea, { target: { value: '   ' } })
    expect(sendButton()).toBeDisabled()
  })

  it('labels an Ask entry as a question', () => {
    renderDialog({ ...entry, mode: 'ask' })
    expect(screen.getByText('history.feedback.question')).toBeInTheDocument()
  })

  it('shows the muted state copy for a take without text', () => {
    renderDialog({ ...entry, raw_text: '', polished_text: '', output_status: 'dismissed' })
    expect(screen.getByTestId('history-feedback-transcript')).toHaveTextContent(
      'history.item.dismissed',
    )
    expect(screen.getByTestId('history-feedback-transcript')).toHaveClass('text-text-tertiary')
  })

  it('opens a prefilled GitHub issue draft and closes on send', async () => {
    useAppStore.setState({
      config: { ...useAppStore.getState().config, stt_api_key: 'sk-never-shared' },
    })
    const { textarea, onClose } = renderDialog()
    fireEvent.change(textarea, { target: { value: 'Brand name was split' } })
    fireEvent.click(sendButton())
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    const url = new URL(mocks.openUrl.mock.calls[0][0] as string)
    expect(url.pathname).toBe('/tover0314-w/opentypeless/issues/new')
    expect(url.searchParams.get('summary')).toBe('Brand name was split')
    expect(url.searchParams.get('actual')).toContain('OpenTypeless rocks')
    expect(url.searchParams.get('version')).toBe('1.1.64')
    expect(url.toString()).not.toContain('sk-never-shared')
    expect(mocks.toastSuccess).toHaveBeenCalledWith('history.feedback.opened')
  })

  it('sends with Cmd/Ctrl+Enter', async () => {
    const { textarea } = renderDialog()
    fireEvent.change(textarea, { target: { value: 'fix it' } })
    fireEvent.keyDown(textarea, { key: 'Enter', metaKey: true })
    await waitFor(() => expect(mocks.openUrl).toHaveBeenCalledTimes(1))
  })

  it.each([
    { metaKey: true, isComposing: true },
    { ctrlKey: true, isComposing: true },
    { metaKey: true, keyCode: 229 },
    { ctrlKey: true, keyCode: 229 },
  ])('preserves IME input instead of opening a draft for %j', (keyboardState) => {
    const { textarea, onClose } = renderDialog()
    fireEvent.change(textarea, { target: { value: '合成反馈草稿' } })
    fireEvent.keyDown(textarea, { key: 'Enter', ...keyboardState })
    expect(mocks.openUrl).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
    expect(textarea).toHaveValue('合成反馈草稿')
    expect(sendButton()).toBeEnabled()
  })

  it('keeps the draft and reports an error when the browser cannot open', async () => {
    mocks.openUrl.mockRejectedValueOnce(new Error('denied'))
    const { textarea, onClose } = renderDialog()
    fireEvent.change(textarea, { target: { value: 'keep me' } })
    fireEvent.click(sendButton())
    await waitFor(() => expect(mocks.toastError).toHaveBeenCalledWith('history.feedback.failed'))
    expect(onClose).not.toHaveBeenCalled()
    expect(textarea).toHaveValue('keep me')
    expect(sendButton()).toBeEnabled()
  })

  it('does not log private feedback URLs when opening the draft fails', async () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      mocks.openUrl.mockRejectedValueOnce(
        new Error('https://example.invalid/issues/new?actual=synthetic-private-transcript'),
      )
      const { textarea, onClose } = renderDialog()
      fireEvent.change(textarea, { target: { value: 'synthetic private feedback' } })
      fireEvent.click(sendButton())
      await waitFor(() => expect(mocks.toastError).toHaveBeenCalled())
      expect(log).toHaveBeenCalledWith('Failed to open feedback issue')
      expect(log.mock.calls.flat().join(' ')).not.toContain('synthetic-private-transcript')
      expect(textarea).toHaveValue('synthetic private feedback')
      expect(onClose).not.toHaveBeenCalled()
    } finally {
      log.mockRestore()
    }
  })

  it('ignores backdrop clicks so typed feedback is not lost, but closes on Escape', () => {
    const { textarea, onClose } = renderDialog()
    fireEvent.change(textarea, { target: { value: 'draft' } })
    const backdrop = document.querySelector('.fixed.inset-0 > .fixed.inset-0') as HTMLElement
    fireEvent.click(backdrop)
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
