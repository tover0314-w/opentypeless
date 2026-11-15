import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { HistoryRetryDialog } from '../HistoryRetryDialog'
import { useAppStore, type HistoryEntry } from '../../../stores/appStore'
import { cancelTranscriptionRetry } from '../../../lib/tauri'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))
vi.mock('../../../lib/tauri', () => ({
  cancelTranscriptionRetry: vi.fn().mockResolvedValue(undefined),
}))

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  useAppStore.setState(useAppStore.getInitialState())
})

const entry = { id: 1, has_audio: true } as HistoryEntry

describe('history recording retry', () => {
  it('waits for cancellation acknowledgement before allowing another retry', async () => {
    useAppStore.setState((state) => ({ config: { ...state.config, stt_provider: 'apple-speech' } }))
    let finishFirst: (text: string) => void = () => {}
    let finishSecond: (text: string) => void = () => {}
    let failCancel: (error: Error) => void = () => {}
    vi.mocked(cancelTranscriptionRetry).mockReturnValueOnce(
      new Promise<void>((_resolve, reject) => {
        failCancel = reject
      }),
    )
    const retry = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<string>((resolve) => {
            finishFirst = resolve
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<string>((resolve) => {
            finishSecond = resolve
          }),
      )
    render(<HistoryRetryDialog entry={entry} onClose={vi.fn()} onRetry={retry} />)
    fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' }))
    fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
    await act(async () => finishFirst('Cancelled words'))
    expect(retry).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' })).toBeDisabled()
    await act(async () => failCancel(new Error('invalid key')))
    expect(screen.getByRole('alert')).toHaveTextContent('capsule.errors.stt_invalid_key')
    fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' }))
    expect(screen.queryByRole('alert')).toBeNull()
    await act(async () => finishSecond('Current words'))
    expect(screen.getByRole('status')).toHaveTextContent('Current words')
  })

  it('removes the previous success when a new retry fails', async () => {
    useAppStore.setState((state) => ({ config: { ...state.config, stt_provider: 'apple-speech' } }))
    const retry = vi
      .fn()
      .mockResolvedValueOnce('Old recovered words')
      .mockRejectedValueOnce('invalid key')
    render(<HistoryRetryDialog entry={entry} onClose={vi.fn()} onRetry={retry} />)
    await act(async () =>
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' })),
    )
    expect(screen.getByRole('status')).toHaveTextContent('Old recovered words')
    await act(async () =>
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' })),
    )
    expect(screen.queryByText('Old recovered words')).toBeNull()
    expect(screen.getByRole('alert')).toHaveTextContent('capsule.errors.stt_invalid_key')
  })

  it('wraps Tab focus inside the retry dialog', () => {
    useAppStore.setState((state) => ({ config: { ...state.config, stt_provider: 'apple-speech' } }))
    render(<HistoryRetryDialog entry={entry} onClose={vi.fn()} onRetry={vi.fn()} />)
    const last = screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' })
    last.focus()
    fireEvent.keyDown(window, { key: 'Tab' })
    expect(screen.getByRole('button', { name: 'common.close' })).toHaveFocus()
  })

  it('shows a localized failure instead of the raw technical error', async () => {
    useAppStore.setState((state) => ({ config: { ...state.config, stt_provider: 'apple-speech' } }))
    render(
      <HistoryRetryDialog
        entry={entry}
        onClose={vi.fn()}
        onRetry={() => Promise.reject('invalid key: technical detail')}
      />,
    )
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' }))
    })
    expect(screen.getByRole('alert')).toHaveTextContent('capsule.errors.stt_invalid_key')
    expect(screen.queryByText('invalid key: technical detail')).toBeNull()
  })

  it.each([
    ['Another voice operation is busy', 'history.retryBusy'],
    ['No saved recording for this entry', 'history.retryMissingAudio'],
    ['Unsupported or incomplete recording format', 'history.retryUnreadableAudio'],
    [
      'Choose Apple Speech or a file-based Whisper provider before retrying history',
      'history.retryUnsupported',
    ],
  ])('explains the history-specific failure %s', async (failure, key) => {
    useAppStore.setState((state) => ({ config: { ...state.config, stt_provider: 'apple-speech' } }))
    render(
      <HistoryRetryDialog
        entry={entry}
        onClose={vi.fn()}
        onRetry={() => Promise.reject(failure)}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(key)
    expect(screen.queryByText(failure)).not.toBeInTheDocument()
  })

  it('closes on Escape before starting and restores focus to the trigger', () => {
    const trigger = document.createElement('button')
    document.body.appendChild(trigger)
    trigger.focus()
    const close = vi.fn()
    const { unmount } = render(
      <HistoryRetryDialog entry={entry} onClose={close} onRetry={vi.fn()} />,
    )
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(close).toHaveBeenCalledTimes(1)
    unmount()
    expect(trigger).toHaveFocus()
    trigger.remove()
  })

  it('cancels only the retry on Escape and suppresses a late result', async () => {
    useAppStore.setState((state) => ({ config: { ...state.config, stt_provider: 'apple-speech' } }))
    let resolve: (text: string) => void = () => {}
    const retry = vi.fn(
      () =>
        new Promise<string>((done) => {
          resolve = done
        }),
    )
    render(<HistoryRetryDialog entry={entry} onClose={vi.fn()} onRetry={retry} />)
    fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' }))
    await act(async () => {
      fireEvent.keyDown(window, { key: 'Escape' })
      resolve('Late recovered words')
    })
    expect(cancelTranscriptionRetry).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('Late recovered words')).toBeNull()
  })

  it('does not submit until the user explicitly confirms', async () => {
    useAppStore.setState((state) => ({ config: { ...state.config, stt_provider: 'apple-speech' } }))
    const retry = vi.fn().mockResolvedValue('Recovered words')
    render(<HistoryRetryDialog entry={entry} onClose={vi.fn()} onRetry={retry} />)
    expect(retry).not.toHaveBeenCalled()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' }))
    })
    expect(retry).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('status')).toHaveTextContent('Recovered words')
  })

  it('disables unsupported services rather than silently using a different one', () => {
    useAppStore.setState((state) => ({ config: { ...state.config, stt_provider: 'cloud' } }))
    render(<HistoryRetryDialog entry={entry} onClose={vi.fn()} onRetry={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'capsule.alerts.retryAndCopy' })).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent('history.retryUnsupported')
  })
})
