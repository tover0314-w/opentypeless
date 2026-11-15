import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { useAppStore } from '../../stores/appStore'
import { useRecording } from '../useRecording'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn().mockResolvedValue(undefined) }))

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  useAppStore.setState(useAppStore.getInitialState())
})

describe('recording stop ownership', () => {
  it('allows a new session to stop while an earlier session is still finishing', async () => {
    let finishPrevious: () => void = () => {}
    vi.mocked(invoke).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishPrevious = resolve
      }),
    )
    const deadline = {
      sessionId: 1,
      recordingKind: 'dictation' as const,
      startedAtUnixMs: 100,
      deadlineAtUnixMs: 200,
      effectiveMaxSeconds: 60,
    }
    useAppStore.setState({ pipelineState: 'recording', recordingDeadline: deadline })
    const { result } = renderHook(() => useRecording())
    let previous: Promise<void>
    act(() => {
      previous = result.current.stopRecording()
    })
    act(() => useAppStore.setState({ recordingDeadline: { ...deadline, sessionId: 2 } }))
    await act(async () => result.current.stopRecording())
    await act(async () => {
      finishPrevious()
      await previous
    })
    expect(invoke).toHaveBeenCalledTimes(2)
  })
})
