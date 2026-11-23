import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { listen } from '@tauri-apps/api/event'
import { invoke } from '@tauri-apps/api/core'
import { useCapsuleUpdateNotice, useMainUpdateEvents } from '../useUpdateEvents'
import { installAndRestart } from '../../lib/appUpdater'
import {
  UPDATE_INSTALL_EVENT,
  UPDATE_NOTICE_IDLE_DELAY_MS,
  UPDATE_READY_EVENT,
} from '../../lib/appUpdaterEvents'
import { useAppStore, type PipelineState } from '../../stores/appStore'
import { useCapsuleAlertStore } from '../../stores/capsuleAlertStore'

vi.mock('@tauri-apps/api/event', () => ({ listen: vi.fn() }))
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
vi.mock('../../lib/appUpdater', () => ({
  installAndRestart: vi.fn().mockResolvedValue(undefined),
}))

beforeEach(() => {
  vi.mocked(listen).mockReset().mockResolvedValue(vi.fn())
  vi.mocked(installAndRestart).mockClear()
  vi.mocked(invoke).mockReset().mockResolvedValue(false)
})

afterEach(() => cleanup())

describe('update event listener ownership', () => {
  it.each(['registration', 'authorization', 'installation'])(
    'does not log private %s errors',
    async (stage) => {
      const log = vi.spyOn(console, 'error').mockImplementation(() => {})
      try {
        const error = new Error('https://example.invalid/?token=synthetic-private-update')
        if (stage === 'registration') vi.mocked(listen).mockRejectedValueOnce(error)
        else if (stage === 'authorization') vi.mocked(invoke).mockRejectedValueOnce(error)
        else {
          vi.mocked(invoke).mockResolvedValueOnce(true)
          vi.mocked(installAndRestart).mockRejectedValueOnce(error)
        }
        renderHook(() => useMainUpdateEvents())
        await act(async () => {})
        if (stage !== 'registration') {
          const registration = vi
            .mocked(listen)
            .mock.calls.find(([event]) => event === UPDATE_INSTALL_EVENT)
          await act(async () =>
            registration?.[1]({ event: UPDATE_INSTALL_EVENT, id: 1, payload: 'native-request' }),
          )
        }
        expect(log).toHaveBeenCalledWith(
          stage === 'registration'
            ? `Failed to listen for ${UPDATE_INSTALL_EVENT}`
            : 'Failed to install update',
        )
        expect(log.mock.calls.flat().join(' ')).not.toContain('synthetic-private-update')
      } finally {
        log.mockRestore()
      }
    },
  )

  it('does not log a late registration failure after unmount', async () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => {})
    let fail: (error: Error) => void = () => {}
    try {
      vi.mocked(listen).mockImplementationOnce(
        () =>
          new Promise((_, reject) => {
            fail = reject
          }),
      )
      const { unmount } = renderHook(() => useMainUpdateEvents())
      unmount()
      await act(async () => fail(new Error('synthetic late listener failure')))
      expect(log).not.toHaveBeenCalled()
    } finally {
      log.mockRestore()
    }
  })

  it.each([null, 'synthetic-untrusted-request', { window: 'ask', version: 'unknown' }])(
    'rejects install broadcasts without native one-time authorization: %j',
    async (payload) => {
      renderHook(() => useMainUpdateEvents())
      await act(async () => {})
      const registration = vi
        .mocked(listen)
        .mock.calls.find(([event]) => event === UPDATE_INSTALL_EVENT)
      expect(registration).toBeDefined()
      await act(async () => registration?.[1]({ event: UPDATE_INSTALL_EVENT, id: 1, payload }))
      expect(installAndRestart).not.toHaveBeenCalled()
    },
  )

  it('contains asynchronous cleanup rejection after unmount', async () => {
    const unlisten = vi.fn(async () => {
      throw new Error('Synthetic async update cleanup failure')
    })
    vi.mocked(listen).mockResolvedValue(unlisten)
    const { unmount } = renderHook(() => useMainUpdateEvents())
    await act(async () => {})
    unmount()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(unlisten).toHaveBeenCalledTimes(2)
    expect(installAndRestart).not.toHaveBeenCalled()
  })

  it('releases a late listener registration and ignores its install callback', async () => {
    let finishRegistration: ((stop: () => void) => void) | undefined
    const unlisten = vi.fn()
    vi.mocked(listen).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishRegistration = resolve
        }),
    )
    const { unmount } = renderHook(() => useMainUpdateEvents())
    const registration = vi
      .mocked(listen)
      .mock.calls.find(([event]) => event === UPDATE_INSTALL_EVENT)
    unmount()
    await act(async () => finishRegistration?.(unlisten))
    expect(unlisten).toHaveBeenCalledOnce()
    await act(async () => registration?.[1]({ event: UPDATE_INSTALL_EVENT, id: 1, payload: null }))
    expect(installAndRestart).not.toHaveBeenCalled()
  })

  it('ignores an install event after its hook unmounts', async () => {
    const { unmount } = renderHook(() => useMainUpdateEvents())
    await act(async () => {})
    const registration = vi
      .mocked(listen)
      .mock.calls.find(([event]) => event === UPDATE_INSTALL_EVENT)
    expect(registration).toBeDefined()
    unmount()
    await act(async () => registration?.[1]({ event: UPDATE_INSTALL_EVENT, id: 1, payload: null }))
    expect(installAndRestart).not.toHaveBeenCalled()
  })

  it('does not throw when native listener cleanup fails', async () => {
    const unlisten = vi.fn(() => {
      throw new Error('Synthetic update listener cleanup failure')
    })
    vi.mocked(listen).mockResolvedValue(unlisten)
    const { unmount } = renderHook(() => useMainUpdateEvents())
    await act(async () => {})
    expect(() => unmount()).not.toThrow()
    expect(unlisten).toHaveBeenCalledTimes(2)
  })

  it('preserves install handling while the hook is active', async () => {
    vi.mocked(invoke).mockResolvedValueOnce(true)
    renderHook(() => useMainUpdateEvents())
    await act(async () => {})
    const registration = vi
      .mocked(listen)
      .mock.calls.find(([event]) => event === UPDATE_INSTALL_EVENT)
    await act(async () =>
      registration?.[1]({ event: UPDATE_INSTALL_EVENT, id: 1, payload: 'native-request-id' }),
    )
    expect(invoke).toHaveBeenCalledWith('consume_update_install_request', {
      requestId: 'native-request-id',
    })
    expect(installAndRestart).toHaveBeenCalledOnce()
  })

  it('rejects replayed authorization and ignores nonboolean native replies', async () => {
    vi.mocked(invoke)
      .mockResolvedValueOnce(true)
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce('true')
    renderHook(() => useMainUpdateEvents())
    await act(async () => {})
    const registration = vi
      .mocked(listen)
      .mock.calls.find(([event]) => event === UPDATE_INSTALL_EVENT)
    for (const payload of ['native-request-id', 'native-request-id', 'forged-request-id']) {
      await act(async () => registration?.[1]({ event: UPDATE_INSTALL_EVENT, id: 1, payload }))
    }
    expect(installAndRestart).toHaveBeenCalledOnce()
  })

  it('does not install when authorization resolves after unmount', async () => {
    let resolveAuthorization: ((value: boolean) => void) | undefined
    vi.mocked(invoke).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveAuthorization = resolve
        }),
    )
    const { unmount } = renderHook(() => useMainUpdateEvents())
    await act(async () => {})
    const registration = vi
      .mocked(listen)
      .mock.calls.find(([event]) => event === UPDATE_INSTALL_EVENT)
    act(() =>
      registration?.[1]({ event: UPDATE_INSTALL_EVENT, id: 1, payload: 'native-request-id' }),
    )
    unmount()
    await act(async () => resolveAuthorization?.(true))
    expect(installAndRestart).not.toHaveBeenCalled()
  })

  it('contains native authorization failure without installing', async () => {
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.mocked(invoke).mockRejectedValueOnce(new Error('Synthetic authorization failure'))
    renderHook(() => useMainUpdateEvents())
    await act(async () => {})
    const registration = vi
      .mocked(listen)
      .mock.calls.find(([event]) => event === UPDATE_INSTALL_EVENT)
    await act(async () =>
      registration?.[1]({ event: UPDATE_INSTALL_EVENT, id: 1, payload: 'native-request-id' }),
    )
    expect(installAndRestart).not.toHaveBeenCalled()
    expect(errorLog).toHaveBeenCalledOnce()
    errorLog.mockRestore()
  })
})

describe('capsule update idle notice', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    useAppStore.setState(useAppStore.getInitialState())
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.restoreAllMocks()
    useAppStore.setState(useAppStore.getInitialState())
  })

  async function sendReady(version: unknown) {
    const registration = vi
      .mocked(listen)
      .mock.calls.find(([event]) => event === UPDATE_READY_EVENT)
    expect(registration).toBeDefined()
    await act(async () => registration?.[1]({ event: UPDATE_READY_EVENT, id: 1, payload: version }))
  }

  it.each<PipelineState>([
    'preparing',
    'recording',
    'transcribing',
    'polishing',
    'outputting',
    'ask_recording',
    'ask_thinking',
  ])('waits until idle when the current state is %s', async (state) => {
    const show = vi.spyOn(useCapsuleAlertStore.getState(), 'show').mockImplementation(() => {})
    useAppStore.getState().setPipelineState(state)
    renderHook(() => useCapsuleUpdateNotice())
    await act(async () => {})
    await sendReady('synthetic-version-a')
    await act(async () => vi.advanceTimersByTime(UPDATE_NOTICE_IDLE_DELAY_MS))
    expect(show).not.toHaveBeenCalled()
    act(() => useAppStore.getState().setPipelineState('idle'))
    await act(async () => vi.advanceTimersByTime(UPDATE_NOTICE_IDLE_DELAY_MS - 1))
    expect(show).not.toHaveBeenCalled()
    await act(async () => vi.advanceTimersByTime(1))
    expect(show).toHaveBeenCalledExactlyOnceWith('update_available')
  })

  it.each(['   ', 42, {}, ['synthetic-version'], null])(
    'ignores invalid downloaded-version payload %j',
    async (payload) => {
      const show = vi.spyOn(useCapsuleAlertStore.getState(), 'show').mockImplementation(() => {})
      renderHook(() => useCapsuleUpdateNotice())
      await act(async () => {})
      await sendReady(payload)
      await act(async () => vi.advanceTimersByTime(UPDATE_NOTICE_IDLE_DELAY_MS))
      expect(show).not.toHaveBeenCalled()
    },
  )

  it('does not discard a valid pending notice when an invalid event arrives', async () => {
    const show = vi.spyOn(useCapsuleAlertStore.getState(), 'show').mockImplementation(() => {})
    renderHook(() => useCapsuleUpdateNotice())
    await act(async () => {})
    await sendReady('synthetic-version-a')
    await act(async () => vi.advanceTimersByTime(4000))
    await sendReady(null)
    await act(async () => vi.advanceTimersByTime(4000))
    expect(show).toHaveBeenCalledExactlyOnceWith('update_available')
  })

  it('waits the full idle delay and does not repeat the same version', async () => {
    const show = vi.spyOn(useCapsuleAlertStore.getState(), 'show').mockImplementation(() => {})
    renderHook(() => useCapsuleUpdateNotice())
    await act(async () => {})
    await sendReady('synthetic-version-a')
    await act(async () => vi.advanceTimersByTime(UPDATE_NOTICE_IDLE_DELAY_MS - 1))
    expect(show).not.toHaveBeenCalled()
    await act(async () => vi.advanceTimersByTime(1))
    expect(show).toHaveBeenCalledExactlyOnceWith('update_available')
    await sendReady('synthetic-version-a')
    await act(async () => vi.advanceTimersByTime(UPDATE_NOTICE_IDLE_DELAY_MS))
    expect(show).toHaveBeenCalledOnce()
  })

  it('does not repeat a previously shown version after a different version is shown', async () => {
    const show = vi.spyOn(useCapsuleAlertStore.getState(), 'show').mockImplementation(() => {})
    renderHook(() => useCapsuleUpdateNotice())
    await act(async () => {})
    for (const version of ['synthetic-version-a', 'synthetic-version-b', 'synthetic-version-a']) {
      await sendReady(version)
      await act(async () => vi.advanceTimersByTime(UPDATE_NOTICE_IDLE_DELAY_MS))
    }
    expect(show).toHaveBeenCalledTimes(2)
  })

  it('starts a fresh idle delay after recording interrupts the wait', async () => {
    const show = vi.spyOn(useCapsuleAlertStore.getState(), 'show').mockImplementation(() => {})
    renderHook(() => useCapsuleUpdateNotice())
    await act(async () => {})
    await sendReady('synthetic-version-a')
    await act(async () => vi.advanceTimersByTime(4000))
    act(() => useAppStore.getState().setPipelineState('recording'))
    await act(async () => vi.advanceTimersByTime(UPDATE_NOTICE_IDLE_DELAY_MS))
    expect(show).not.toHaveBeenCalled()
    act(() => useAppStore.getState().setPipelineState('idle'))
    await act(async () => vi.advanceTimersByTime(UPDATE_NOTICE_IDLE_DELAY_MS - 1))
    expect(show).not.toHaveBeenCalled()
    await act(async () => vi.advanceTimersByTime(1))
    expect(show).toHaveBeenCalledOnce()
  })

  it('cancels the pending notice on unmount and ignores late ready events', async () => {
    const show = vi.spyOn(useCapsuleAlertStore.getState(), 'show').mockImplementation(() => {})
    const { unmount } = renderHook(() => useCapsuleUpdateNotice())
    await act(async () => {})
    await sendReady('synthetic-version-a')
    unmount()
    await sendReady('synthetic-version-b')
    await act(async () => vi.advanceTimersByTime(UPDATE_NOTICE_IDLE_DELAY_MS))
    expect(show).not.toHaveBeenCalled()
  })

  it('does not display the notice while a pipeline error is present', async () => {
    const show = vi.spyOn(useCapsuleAlertStore.getState(), 'show').mockImplementation(() => {})
    act(() => useAppStore.getState().setPipelineError('synthetic error'))
    renderHook(() => useCapsuleUpdateNotice())
    await act(async () => {})
    await sendReady('synthetic-version-a')
    await act(async () => vi.advanceTimersByTime(UPDATE_NOTICE_IDLE_DELAY_MS))
    expect(show).not.toHaveBeenCalled()
    act(() => useAppStore.getState().setPipelineError(null))
    await act(async () => vi.advanceTimersByTime(UPDATE_NOTICE_IDLE_DELAY_MS))
    expect(show).toHaveBeenCalledExactlyOnceWith('update_available')
  })

  it('restarts the delay when a different downloaded version replaces the pending one', async () => {
    const show = vi.spyOn(useCapsuleAlertStore.getState(), 'show').mockImplementation(() => {})
    renderHook(() => useCapsuleUpdateNotice())
    await act(async () => {})
    await sendReady('synthetic-version-a')
    await act(async () => vi.advanceTimersByTime(4000))
    await sendReady('synthetic-version-b')
    await act(async () => vi.advanceTimersByTime(UPDATE_NOTICE_IDLE_DELAY_MS - 1))
    expect(show).not.toHaveBeenCalled()
    await act(async () => vi.advanceTimersByTime(1))
    expect(show).toHaveBeenCalledExactlyOnceWith('update_available')
  })
})
