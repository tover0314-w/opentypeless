import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { usePracticeSession } from '../usePracticeSession'

const mocks = vi.hoisted(() => ({ invoke: vi.fn() }))
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }))

afterEach(() => {
  cleanup()
  mocks.invoke.mockReset()
})

describe('isolated practice session', () => {
  it('preserves a stop requested before native startup returns', async () => {
    let finishStart!: (session: string) => void
    mocks.invoke.mockImplementation((command: string) => {
      if (command === 'start_practice')
        return new Promise<string>((resolve) => {
          finishStart = resolve
        })
      if (command === 'get_practice')
        return Promise.resolve({
          session: 'slow-session',
          phase: 'transcribing',
          rawText: '',
          result: '',
          level: 0,
          error: null,
        })
      return Promise.resolve()
    })
    const { result } = renderHook(() => usePracticeSession())
    let startup!: Promise<void>
    act(() => {
      startup = result.current.start()
    })
    await act(() => result.current.stop())
    await act(async () => {
      finishStart('slow-session')
      await startup
    })
    expect(mocks.invoke).toHaveBeenCalledWith('stop_practice', { session: 'slow-session' })
    expect(mocks.invoke.mock.calls.filter(([command]) => command === 'stop_practice')).toHaveLength(
      1,
    )
  })

  it('ignores a malformed late start after cancellation and a new session', async () => {
    let finishOld!: (value: unknown) => void
    let starts = 0
    mocks.invoke.mockImplementation((command: string) => {
      if (command === 'start_practice') {
        starts += 1
        if (starts === 1) {
          return new Promise((resolve) => {
            finishOld = resolve
          })
        }
        return Promise.resolve('new-session')
      }
      if (command === 'get_practice') {
        return Promise.resolve({
          session: 'new-session',
          phase: 'complete',
          rawText: 'New speech',
          result: 'New result',
          polishPreview: '',
          level: 0,
          error: null,
        })
      }
      return Promise.resolve()
    })
    const { result } = renderHook(() => usePracticeSession())
    let oldStart!: Promise<void>
    act(() => {
      oldStart = result.current.start()
    })
    await act(() => result.current.cancel())
    await act(() => result.current.start())
    await waitFor(() => expect(result.current.snapshot?.result).toBe('New result'))
    const callsBeforeOldResponse = mocks.invoke.mock.calls.length
    await act(async () => {
      finishOld(null)
      await oldStart
    })
    expect(result.current.snapshot?.result).toBe('New result')
    expect(result.current.failed).toBe(false)
    expect(result.current.starting).toBe(false)
    expect(mocks.invoke).toHaveBeenCalledTimes(callsBeforeOldResponse)
  })

  it.each([null, undefined, '', ' \n\t', 42, {}, []])(
    'rejects malformed start identities without forwarding them case %#',
    async (identity) => {
      mocks.invoke.mockResolvedValue(identity)
      const { result } = renderHook(() => usePracticeSession())
      await act(() => result.current.start())
      expect(result.current.failed).toBe(true)
      expect(result.current.starting).toBe(false)
      expect(result.current.snapshot).toBeNull()
      expect(mocks.invoke).toHaveBeenCalledTimes(1)
      expect(mocks.invoke).toHaveBeenCalledWith('start_practice')
    },
  )

  it.each(['x'.repeat(32_768), `${'语'.repeat(10_922)}ab`, '😀'.repeat(8_192)])(
    'accepts completed UTF-8 text at the exact byte budget case %#',
    async (text) => {
      expect(new TextEncoder().encode(text).byteLength).toBe(32_768)
      mocks.invoke.mockImplementation(async (command: string) => {
        if (command === 'start_practice') return 'session-one'
        if (command === 'get_practice')
          return {
            session: 'session-one',
            phase: 'complete',
            rawText: text,
            polishPreview: '',
            result: text,
            level: 0,
            error: null,
          }
      })
      const { result } = renderHook(() => usePracticeSession())
      await act(() => result.current.start())
      await waitFor(() => expect(result.current.snapshot?.phase).toBe('complete'))
      expect(result.current.snapshot?.result).toBe(text)
      expect(result.current.failed).toBe(false)
      expect(mocks.invoke).not.toHaveBeenCalledWith('cancel_practice', { session: 'session-one' })
    },
  )
  it.each([
    { phase: 'complete', error: { code: 'llm_failed', details: null, retry_count: 0 } },
    { phase: 'error', error: 'invalid error' },
    { phase: 'error', error: { code: 42, details: null, retry_count: 0 } },
    { phase: 'error', error: { code: 'llm_failed', details: {}, retry_count: 0 } },
    { phase: 'error', error: { code: 'llm_failed', details: null, retry_count: -1 } },
  ])('rejects contradictory or malformed error progress case %#', async (progress) => {
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'session-one'
      if (command === 'get_practice')
        return {
          session: 'session-one',
          rawText: 'confirmed speech',
          polishPreview: '',
          result: progress.phase === 'complete' ? 'Final result.' : '',
          level: 0,
          ...progress,
        }
    })
    const { result } = renderHook(() => usePracticeSession())
    await act(() => result.current.start())
    await waitFor(() => expect(result.current.failed).toBe(true))
    expect(result.current.snapshot).toBeNull()
    expect(mocks.invoke).toHaveBeenCalledWith('cancel_practice', { session: 'session-one' })
  })
  it.each(['', ' \n\t'])(
    'rejects completed progress without a usable result case %#',
    async (text) => {
      mocks.invoke.mockImplementation(async (command: string) => {
        if (command === 'start_practice') return 'session-one'
        if (command === 'get_practice')
          return {
            session: 'session-one',
            phase: 'complete',
            rawText: 'confirmed speech',
            polishPreview: '',
            result: text,
            level: 0,
            error: null,
          }
      })
      const { result } = renderHook(() => usePracticeSession())
      await act(() => result.current.start())
      await waitFor(() => expect(result.current.failed).toBe(true))
      expect(result.current.snapshot).toBeNull()
      expect(mocks.invoke).toHaveBeenCalledWith('cancel_practice', { session: 'session-one' })
    },
  )
  it.each([
    { rawText: { text: 'invalid object' } },
    { result: ['invalid array'] },
    { polishPreview: 123 },
    { rawText: 'x'.repeat(32_769) },
    { result: '语'.repeat(10_923) },
    { polishPreview: 'x'.repeat(32_769) },
  ])('rejects malformed or oversized native text case %#', async (text) => {
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'session-one'
      if (command === 'get_practice')
        return {
          session: 'session-one',
          phase: 'recording',
          rawText: '',
          result: '',
          polishPreview: '',
          level: 0,
          error: null,
          ...text,
        }
    })
    const { result } = renderHook(() => usePracticeSession())
    await act(() => result.current.start())
    await waitFor(() => expect(result.current.failed).toBe(true))
    expect(result.current.snapshot).toBeNull()
    expect(mocks.invoke).toHaveBeenCalledWith('cancel_practice', { session: 'session-one' })
  })

  it.each([
    { phase: 'unsupported', level: 0 },
    { phase: 'recording', level: Number.NaN },
    { phase: 'recording', level: 2 },
    { phase: 'recording', level: -1 },
  ])('rejects invalid native progress $phase with level $level', async (progress) => {
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'session-one'
      if (command === 'get_practice')
        return {
          session: 'session-one',
          ...progress,
          rawText: '',
          polishPreview: '',
          result: '',
          error: null,
        }
    })
    const { result } = renderHook(() => usePracticeSession())
    await act(() => result.current.start())
    await waitFor(() => expect(result.current.failed).toBe(true))
    expect(result.current.snapshot).toBeNull()
    expect(mocks.invoke).toHaveBeenCalledWith('cancel_practice', { session: 'session-one' })
  })
  it.each(['transcribing', 'polishing'])(
    'preserves %s after native progress acknowledges an earlier stop',
    async (phase) => {
      let currentPhase = 'recording'
      let rejectStop!: (error: Error) => void
      mocks.invoke.mockImplementation((command: string) => {
        if (command === 'start_practice') return Promise.resolve('session-one')
        if (command === 'get_practice')
          return Promise.resolve({
            session: 'session-one',
            phase: currentPhase,
            rawText: 'confirmed words',
            result: '',
            level: 0,
            error: null,
          })
        if (command === 'stop_practice')
          return new Promise<void>((_resolve, reject) => {
            rejectStop = reject
          })
        return Promise.resolve()
      })
      const { result } = renderHook(() => usePracticeSession())
      await act(() => result.current.start())
      let pending!: Promise<void>
      act(() => {
        pending = result.current.stop()
      })
      currentPhase = phase
      await waitFor(() => expect(result.current.snapshot?.phase).toBe(phase))
      await act(async () => {
        rejectStop(new Error('Synthetic delayed failure'))
        await pending
      })
      expect(result.current.snapshot?.phase).toBe(phase)
      expect(result.current.failed).toBe(false)
      expect(mocks.invoke).not.toHaveBeenCalledWith('cancel_practice', { session: 'session-one' })
    },
  )
  it('does not resend an acknowledged stop while polling still reports recording', async () => {
    let phase = 'recording'
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'session-one'
      if (command === 'get_practice')
        return {
          session: 'session-one',
          phase,
          rawText: '',
          result: '',
          level: 0,
          error: null,
        }
    })
    const { result } = renderHook(() => usePracticeSession())
    await act(() => result.current.start())
    await act(() => result.current.stop())
    await act(() => result.current.stop())
    expect(mocks.invoke.mock.calls.filter(([command]) => command === 'stop_practice')).toHaveLength(
      1,
    )
    expect(result.current.stopping).toBe(true)
    phase = 'transcribing'
    await waitFor(() => expect(result.current.snapshot?.phase).toBe('transcribing'))
    expect(result.current.stopping).toBe(false)
    await act(() => result.current.cancel())
    expect(result.current.snapshot).toBeNull()
  })
  it('sends one stop request while waiting and still allows cancellation', async () => {
    let finishStop!: () => void
    mocks.invoke.mockImplementation((command: string) => {
      if (command === 'start_practice') return Promise.resolve('session-one')
      if (command === 'get_practice')
        return Promise.resolve({
          session: 'session-one',
          phase: 'recording',
          rawText: '',
          result: '',
          level: 0,
          error: null,
        })
      if (command === 'stop_practice')
        return new Promise<void>((resolve) => {
          finishStop = resolve
        })
      return Promise.resolve()
    })
    const { result } = renderHook(() => usePracticeSession())
    await act(() => result.current.start())
    let first!: Promise<void>
    let second!: Promise<void>
    act(() => {
      first = result.current.stop()
      second = result.current.stop()
    })
    expect(mocks.invoke.mock.calls.filter(([command]) => command === 'stop_practice')).toHaveLength(
      1,
    )
    expect(result.current.stopping).toBe(true)
    await act(() => result.current.cancel())
    expect(result.current.stopping).toBe(false)
    expect(mocks.invoke).toHaveBeenCalledWith('cancel_practice', { session: 'session-one' })
    await act(async () => {
      finishStop()
      await Promise.all([first, second])
    })
    expect(result.current.snapshot).toBeNull()
  })
  it.each(['complete', 'error', 'cancelled'])(
    'preserves %s snapshot when an earlier stop request fails late',
    async (phase) => {
      let rejectStop!: (error: Error) => void
      let terminal = false
      mocks.invoke.mockImplementation((command: string) => {
        if (command === 'start_practice') return Promise.resolve('session-one')
        if (command === 'get_practice')
          return Promise.resolve({
            session: 'session-one',
            phase: terminal ? phase : 'recording',
            rawText: '',
            result: terminal && phase === 'complete' ? 'Final result.' : '',
            level: 0,
            error: null,
          })
        if (command === 'stop_practice')
          return new Promise<void>((_, reject) => {
            rejectStop = reject
          })
        return Promise.resolve()
      })
      const { result } = renderHook(() => usePracticeSession())
      await act(() => result.current.start())
      await waitFor(() => expect(result.current.snapshot?.phase).toBe('recording'))
      let pending!: Promise<void>
      act(() => {
        pending = result.current.stop()
      })
      terminal = true
      await waitFor(() => expect(result.current.snapshot?.phase).toBe(phase))
      await act(async () => {
        rejectStop(new Error('Synthetic delayed stop failure'))
        await pending
      })
      expect(result.current.snapshot?.phase).toBe(phase)
      expect(result.current.failed).toBe(false)
      expect(mocks.invoke).not.toHaveBeenCalledWith('cancel_practice', { session: 'session-one' })
    },
  )
  it('releases the session after a stop command fails so retry can start', async () => {
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'session-one'
      if (command === 'get_practice') {
        return {
          session: 'session-one',
          phase: 'recording',
          rawText: '',
          result: '',
          level: 0,
          error: null,
        }
      }
      if (command === 'stop_practice') throw new Error('Synthetic IPC failure')
    })
    const { result } = renderHook(() => usePracticeSession())
    await act(() => result.current.start())
    await act(() => result.current.stop())
    expect(result.current.failed).toBe(true)
    expect(result.current.snapshot).toBeNull()
    expect(mocks.invoke).toHaveBeenCalledWith('cancel_practice', { session: 'session-one' })
    await act(() => result.current.start())
    expect(
      mocks.invoke.mock.calls.filter(([command]) => command === 'start_practice'),
    ).toHaveLength(2)
  })
  it('clears a recording snapshot when subsequent polling fails', async () => {
    let polls = 0
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'session-one'
      if (command === 'get_practice') {
        if (++polls > 1) throw new Error('Synthetic polling failure')
        return {
          session: 'session-one',
          phase: 'recording',
          rawText: 'temporary text',
          result: '',
          level: 0.5,
          error: null,
        }
      }
    })
    const { result } = renderHook(() => usePracticeSession())
    await act(() => result.current.start())
    await waitFor(() => expect(result.current.snapshot?.phase).toBe('recording'))
    await waitFor(() => expect(result.current.failed).toBe(true))
    expect(result.current.snapshot).toBeNull()
    expect(mocks.invoke).toHaveBeenCalledWith('cancel_practice', { session: 'session-one' })
  })
  it('does not start recording on mount and cancels its session on unmount', async () => {
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'session-one'
      if (command === 'get_practice') {
        return {
          session: 'session-one',
          phase: 'recording',
          rawText: '',
          result: '',
          level: 0.5,
          error: null,
        }
      }
    })
    const { result, unmount } = renderHook(() => usePracticeSession())
    expect(mocks.invoke).not.toHaveBeenCalled()
    await act(() => result.current.start())
    await waitFor(() => expect(result.current.snapshot?.phase).toBe('recording'))
    await act(() => result.current.stop())
    expect(mocks.invoke).toHaveBeenCalledWith('stop_practice', { session: 'session-one' })
    unmount()
    expect(mocks.invoke).toHaveBeenCalledWith('cancel_practice', { session: 'session-one' })
  })

  it('cancels a start that returns after unmount', async () => {
    let finish!: (session: string) => void
    mocks.invoke.mockImplementation((command: string) => {
      if (command === 'start_practice')
        return new Promise<string>((resolve) => {
          finish = resolve
        })
      return Promise.resolve()
    })
    const { result, unmount } = renderHook(() => usePracticeSession())
    let pending!: Promise<void>
    act(() => {
      pending = result.current.start()
    })
    unmount()
    await act(async () => {
      finish('late-session')
      await pending
    })
    expect(mocks.invoke).toHaveBeenCalledWith('cancel_practice', { session: 'late-session' })
    expect(mocks.invoke).not.toHaveBeenCalledWith('get_practice', { session: 'late-session' })
  })

  it('drops a result arriving after cancellation', async () => {
    let finish!: (snapshot: unknown) => void
    mocks.invoke.mockImplementation((command: string) => {
      if (command === 'start_practice') return Promise.resolve('old-session')
      if (command === 'get_practice')
        return new Promise((resolve) => {
          finish = resolve
        })
      return Promise.resolve()
    })
    const { result } = renderHook(() => usePracticeSession())
    await act(() => result.current.start())
    await act(() => result.current.cancel())
    await act(async () => {
      finish({ session: 'old-session', phase: 'complete', result: 'old text' })
    })
    expect(result.current.snapshot).toBeNull()
  })

  it('rejects a snapshot belonging to another session', async () => {
    mocks.invoke.mockImplementation(async (command: string) => {
      if (command === 'start_practice') return 'expected-session'
      if (command === 'get_practice')
        return { session: 'other-session', phase: 'complete', result: 'wrong text' }
    })
    const { result } = renderHook(() => usePracticeSession())
    await act(() => result.current.start())
    await waitFor(() => expect(result.current.failed).toBe(true))
    expect(result.current.snapshot?.result).not.toBe('wrong text')
    expect(mocks.invoke).toHaveBeenCalledWith('cancel_practice', { session: 'expected-session' })
  })
})
