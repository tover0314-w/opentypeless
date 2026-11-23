import { beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import {
  cancelTranscriptionRetry,
  recoverUnfinishedTake,
  retryHistoryTranscription,
} from '../tauri'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn().mockResolvedValue(undefined) }))

beforeEach(() => vi.clearAllMocks())

describe('history retry cancellation ordering', () => {
  it.each([
    { command: 'retry_history_transcription', start: retryHistoryTranscription },
    { command: 'recover_unfinished_take', start: recoverUnfinishedTake },
  ])('waits for an earlier cancellation before $command', async ({ command, start }) => {
    let finishCancel: () => void = () => {}
    vi.mocked(invoke).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishCancel = resolve
      }),
    )
    const cancellation = cancelTranscriptionRetry()
    const retry = start(2)
    const commandsBeforeAcknowledgement = vi.mocked(invoke).mock.calls.map(([command]) => command)
    finishCancel()
    await Promise.all([cancellation, retry])
    expect(commandsBeforeAcknowledgement).toEqual(['cancel_transcription_retry'])
    expect(invoke).toHaveBeenLastCalledWith(command, { id: 2 })
  })

  it.each([
    { command: 'retry_history_transcription', start: retryHistoryTranscription },
    { command: 'recover_unfinished_take', start: recoverUnfinishedTake },
  ])('does not invoke $command if its cancellation barrier fails', async ({ command, start }) => {
    let failCancel: (error: Error) => void = () => {}
    vi.mocked(invoke).mockReturnValueOnce(
      new Promise<void>((_resolve, reject) => {
        failCancel = reject
      }),
    )
    const cancellation = cancelTranscriptionRetry()
    const retry = start(2)
    const cancellationFailure = expect(cancellation).rejects.toThrow('cancel failed')
    const retryFailure = expect(retry).rejects.toThrow('cancel failed')
    failCancel(new Error('cancel failed'))
    await Promise.all([cancellationFailure, retryFailure])
    expect(invoke).not.toHaveBeenCalledWith(command, { id: 2 })
  })
})
