import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { useAppStore } from '../../../stores/appStore'
import { DurationTimer } from '../DurationTimer'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

describe('DurationTimer', () => {
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

  it('renders elapsed time from the Rust capture-ready timestamp', () => {
    useAppStore.setState({
      recordingDeadline: {
        sessionId: 7,
        recordingKind: 'dictation',
        startedAtUnixMs: 940_000,
        deadlineAtUnixMs: 1_539_750,
        effectiveMaxSeconds: 600,
      },
    })

    render(<DurationTimer />)
    expect(screen.getByText('01:00')).toBeInTheDocument()

    act(() => {
      vi.advanceTimersByTime(2_000)
    })
    expect(screen.getByText('01:02')).toBeInTheDocument()
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
    expect(screen.getByText('00:29')).toBeInTheDocument()
  })
})
