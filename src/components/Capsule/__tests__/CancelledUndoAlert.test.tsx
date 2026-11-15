import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAppStore } from '../../../stores/appStore'
import { useCapsuleAlertStore } from '../../../stores/capsuleAlertStore'
import { Capsule } from '../index'
import { recoverUnfinishedTake } from '../../../lib/tauri'

vi.mock('@tauri-apps/api/event', () => ({
  listen: vi.fn().mockResolvedValue(() => {}),
  emit: vi.fn().mockResolvedValue(undefined),
  emitTo: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('../../../lib/tauri', () => ({
  recoverUnfinishedTake: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('../../../hooks/useCapsuleResize', () => ({ useCapsuleResize: vi.fn() }))
vi.mock('../../../hooks/useCapsuleClickThrough', () => ({ useCapsuleClickThrough: vi.fn() }))
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn() }))
vi.mock('@tauri-apps/plugin-clipboard-manager', () => ({ writeText: vi.fn() }))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.clearAllMocks()
  useAppStore.setState(useAppStore.getInitialState())
  useCapsuleAlertStore.getState().dismiss()
})

const undoButton = () => screen.queryByRole('button', { name: 'capsule.alerts.undo' })

describe('transcript cancelled alert', () => {
  it('does not restore an obsolete take after a newer cancellation replaces its alert', () => {
    render(<Capsule />)
    act(() => useCapsuleAlertStore.getState().show('transcript_cancelled', undefined, 7))
    const oldUndo = undoButton()!
    act(() => {
      useCapsuleAlertStore.getState().show('transcript_cancelled', undefined, 8)
      fireEvent.click(oldUndo)
    })
    expect(recoverUnfinishedTake).not.toHaveBeenCalled()
    expect(useCapsuleAlertStore.getState().alert).toMatchObject({
      kind: 'transcript_cancelled',
      entryId: 8,
    })
  })

  it('offers Undo only once the cancelled take is stored, without restarting the 5s timer', async () => {
    render(<Capsule />)
    act(() => useCapsuleAlertStore.getState().show('transcript_cancelled'))
    expect(screen.getByText('capsule.alerts.cancelled')).toBeTruthy()
    expect(undoButton()).toBeNull()

    await act(async () => {
      vi.advanceTimersByTime(3000)
    })
    act(() => useCapsuleAlertStore.getState().attachCancelledTake(41))
    expect(undoButton()).toBeTruthy()

    await act(async () => {
      vi.advanceTimersByTime(2000)
    })
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('ignores a stored take when another alert replaced the cancelled one', () => {
    act(() => useCapsuleAlertStore.getState().show('ready'))
    act(() => useCapsuleAlertStore.getState().attachCancelledTake(41))
    expect(useCapsuleAlertStore.getState().alert).toMatchObject({ kind: 'ready' })
    expect(useCapsuleAlertStore.getState().alert?.entryId).toBeUndefined()
  })

  it('re-runs the take on Undo and closes the alert', async () => {
    render(<Capsule />)
    act(() => useCapsuleAlertStore.getState().show('transcript_cancelled', undefined, 7))

    await act(async () => {
      fireEvent.click(undoButton()!)
    })

    expect(recoverUnfinishedTake).toHaveBeenCalledWith(7)
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('explains why Undo failed in the capsule error alert', async () => {
    vi.mocked(recoverUnfinishedTake).mockRejectedValueOnce(
      'Choose Apple Speech or a file-based Whisper provider before retrying history',
    )
    render(<Capsule />)
    act(() => useCapsuleAlertStore.getState().show('transcript_cancelled', undefined, 7))

    await act(async () => {
      fireEvent.click(undoButton()!)
    })

    expect(useAppStore.getState().pipelineError).toBe('history.retryUnsupported')
    expect(screen.getByRole('alert').textContent).toContain('history.retryUnsupported')
  })

  it('disables Undo while another voice operation runs', () => {
    useAppStore.setState({ pipelineState: 'recording' })
    render(<Capsule />)
    act(() => useCapsuleAlertStore.getState().show('transcript_cancelled', undefined, 7))
    expect((undoButton() as HTMLButtonElement).disabled).toBe(true)
  })

  it('does not reset a new recording when an old Undo click is delivered', () => {
    render(<Capsule />)
    act(() => useCapsuleAlertStore.getState().show('transcript_cancelled', undefined, 7))
    const button = undoButton()!
    act(() => {
      useAppStore.setState({ pipelineState: 'recording', partialTranscript: 'New recording' })
      fireEvent.click(button)
    })
    expect(recoverUnfinishedTake).not.toHaveBeenCalled()
    expect(useAppStore.getState().partialTranscript).toBe('New recording')
  })

  it('ignores old Undo failure after a newer recording starts and finishes', async () => {
    let failRecovery: ((error: Error) => void) | undefined
    vi.mocked(recoverUnfinishedTake).mockImplementationOnce(
      () =>
        new Promise<void>((_resolve, reject) => {
          failRecovery = reject
        }),
    )
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      render(<Capsule />)
      act(() => useCapsuleAlertStore.getState().show('transcript_cancelled', undefined, 7))
      fireEvent.click(undoButton()!)
      act(() => {
        useAppStore.getState().setPipelineState('recording')
        useAppStore.getState().setPipelineState('idle')
      })
      await act(async () => failRecovery?.(new Error('Old recovery failed')))
      expect(useAppStore.getState().pipelineError).toBeNull()
    } finally {
      warning.mockRestore()
    }
  })

  it('does not overwrite a newer capsule error with a late Undo failure', async () => {
    let failRecovery: ((error: Error) => void) | undefined
    vi.mocked(recoverUnfinishedTake).mockImplementationOnce(
      () =>
        new Promise<void>((_resolve, reject) => {
          failRecovery = reject
        }),
    )
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      render(<Capsule />)
      act(() => useCapsuleAlertStore.getState().show('transcript_cancelled', undefined, 7))
      fireEvent.click(undoButton()!)
      act(() => useAppStore.getState().setPipelineError('New microphone error'))
      await act(async () => failRecovery?.(new Error('Old recovery failed')))
      expect(useAppStore.getState().pipelineError).toBe('New microphone error')
    } finally {
      warning.mockRestore()
    }
  })

  it('releases the old recovery subscription as soon as a new recording starts', async () => {
    let finishRecovery: (() => void) | undefined
    vi.mocked(recoverUnfinishedTake).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishRecovery = resolve
        }),
    )
    const originalSubscribe = useAppStore.subscribe
    const released = vi.fn()
    const subscription = vi.spyOn(useAppStore, 'subscribe').mockImplementation((listener) => {
      const unsubscribe = originalSubscribe(listener)
      return () => {
        released()
        unsubscribe()
      }
    })
    try {
      render(<Capsule />)
      act(() => useCapsuleAlertStore.getState().show('transcript_cancelled', undefined, 7))
      fireEvent.click(undoButton()!)
      act(() => useAppStore.getState().setPipelineState('recording'))
      expect(released).toHaveBeenCalledOnce()
      await act(async () => finishRecovery?.())
      expect(released).toHaveBeenCalledOnce()
    } finally {
      subscription.mockRestore()
    }
  })

  it('still reports a current Undo failure after its own transcribing phase', async () => {
    let failRecovery: ((error: Error) => void) | undefined
    vi.mocked(recoverUnfinishedTake).mockImplementationOnce(
      () =>
        new Promise<void>((_resolve, reject) => {
          failRecovery = reject
        }),
    )
    const warning = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      render(<Capsule />)
      act(() => useCapsuleAlertStore.getState().show('transcript_cancelled', undefined, 7))
      fireEvent.click(undoButton()!)
      act(() => {
        useAppStore.getState().setPipelineState('transcribing')
        useAppStore.getState().setPipelineState('idle')
      })
      await act(async () => failRecovery?.(new Error('Current recovery failed')))
      expect(useAppStore.getState().pipelineError).not.toBeNull()
    } finally {
      warning.mockRestore()
    }
  })
})
