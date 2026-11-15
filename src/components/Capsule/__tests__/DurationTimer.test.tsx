import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { useAppStore } from '../../../stores/appStore'
import { DurationTimer } from '../DurationTimer'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

describe('DurationTimer countdown', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(1_000_000)
    useAppStore.setState(useAppStore.getInitialState())
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('stays hidden until the final minute of the recording limit', () => {
    useAppStore.setState({
      recordingDeadline: {
        sessionId: 7,
        recordingKind: 'dictation',
        startedAtUnixMs: 940_000,
        deadlineAtUnixMs: 1_539_750,
        effectiveMaxSeconds: 600,
      },
    })

    const { container } = render(<DurationTimer />)
    expect(container.textContent).toBe('')
  })

  it('counts down the remaining time from the Rust deadline', () => {
    useAppStore.setState({
      recordingDeadline: {
        sessionId: 7,
        recordingKind: 'dictation',
        startedAtUnixMs: 940_000,
        deadlineAtUnixMs: 1_045_000,
        effectiveMaxSeconds: 105,
      },
    })

    render(<DurationTimer />)
    expect(screen.getByText('00:45')).toBeInTheDocument()

    act(() => {
      vi.advanceTimersByTime(2_000)
    })
    expect(screen.getByText('00:43')).toBeInTheDocument()
  })

  it('never acts as the recording stop authority', () => {
    useAppStore.setState({
      recordingDeadline: {
        sessionId: 8,
        recordingKind: 'dictation',
        startedAtUnixMs: 970_000,
        deadlineAtUnixMs: 999_750,
        effectiveMaxSeconds: 30,
      },
    })

    render(<DurationTimer />)
    act(() => {
      vi.advanceTimersByTime(5_000)
    })

    expect(invoke).not.toHaveBeenCalled()
    expect(screen.getByText('00:00')).toBeInTheDocument()
  })
})
