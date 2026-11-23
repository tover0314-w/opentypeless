import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useHistorySyncScheduler } from '../useHistorySyncScheduler'
import {
  getHistorySyncConsentView,
  getHistorySyncConflictCount,
  historySyncOnceBlocked,
  syncHistoryOnce,
} from '../../lib/historySyncConsent'
import { useAuthStore } from '../../stores/authStore'
import { setAutomaticHistorySync } from '../../lib/historySyncSchedule'

vi.mock('../../lib/historySyncConsent', () => ({
  getHistorySyncConsentView: vi.fn(),
  getHistorySyncConflictCount: vi.fn(),
  syncHistoryOnce: vi.fn(),
  historySyncOnceBlocked: vi.fn(() => false),
}))

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  useAuthStore.setState({
    user: {
      id: 'synthetic-user',
      email: 'user@example.invalid',
      name: 'Synthetic',
      emailVerified: true,
    },
  })
  vi.mocked(getHistorySyncConsentView).mockResolvedValue({
    active: true,
    sessionEpoch: 'synthetic-epoch',
    generation: 'synthetic-generation',
  })
  vi.mocked(getHistorySyncConflictCount).mockResolvedValue(0)
  vi.mocked(syncHistoryOnce).mockResolvedValue({
    pushed: 0,
    pulled: 0,
    pending: 0,
    hasMore: false,
  })
  setAutomaticHistorySync('synthetic-user', true)
})

afterEach(() => {
  cleanup()
  setAutomaticHistorySync('synthetic-user', false)
  vi.useRealTimers()
})

it('runs only with active authorization and stops after unmount', async () => {
  const { unmount } = renderHook(() => useHistorySyncScheduler())
  await act(async () => vi.advanceTimersByTimeAsync(1000))
  expect(syncHistoryOnce).toHaveBeenCalledOnce()
  unmount()
  await act(async () => vi.advanceTimersByTimeAsync(60000))
  expect(syncHistoryOnce).toHaveBeenCalledOnce()
})

it('never starts synchronization without authorization', async () => {
  vi.mocked(getHistorySyncConsentView).mockResolvedValue({
    active: false,
    sessionEpoch: 'synthetic-epoch',
    generation: null,
  })
  renderHook(() => useHistorySyncScheduler())
  await act(async () => vi.advanceTimersByTimeAsync(31000))
  expect(syncHistoryOnce).not.toHaveBeenCalled()
})

it('does not start background synchronization until explicitly enabled', async () => {
  setAutomaticHistorySync('synthetic-user', false)
  renderHook(() => useHistorySyncScheduler())
  await act(async () => vi.advanceTimersByTimeAsync(61000))
  expect(getHistorySyncConsentView).not.toHaveBeenCalled()
  expect(syncHistoryOnce).not.toHaveBeenCalled()
})

it('stops on recorded conflicts without attempting to push again', async () => {
  vi.mocked(getHistorySyncConflictCount).mockResolvedValue(1)
  renderHook(() => useHistorySyncScheduler())
  await act(async () => vi.advanceTimersByTimeAsync(61000))
  expect(syncHistoryOnce).not.toHaveBeenCalled()
})

it('does not use an authorization response after the account changes', async () => {
  let finish: ((view: Awaited<ReturnType<typeof getHistorySyncConsentView>>) => void) | undefined
  vi.mocked(getHistorySyncConsentView).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  renderHook(() => useHistorySyncScheduler())
  await act(async () => vi.advanceTimersByTimeAsync(1000))
  await act(async () => {
    useAuthStore.setState({ user: null })
    finish?.({ active: true, sessionEpoch: 'synthetic-epoch', generation: 'synthetic-generation' })
  })
  expect(syncHistoryOnce).not.toHaveBeenCalled()
})

it('does not apply a late old-account failure to the new account backoff', async () => {
  let rejectOld: ((error: Error) => void) | undefined
  vi.mocked(getHistorySyncConsentView).mockImplementationOnce(
    () =>
      new Promise((_resolve, reject) => {
        rejectOld = reject
      }),
  )
  renderHook(() => useHistorySyncScheduler())
  await act(async () => vi.advanceTimersByTimeAsync(1000))
  act(() => {
    useAuthStore.setState({
      user: {
        id: 'replacement-user',
        email: 'replacement@example.invalid',
        name: 'Replacement',
        emailVerified: true,
      },
    })
  })
  act(() => setAutomaticHistorySync('replacement-user', true))
  await act(async () => vi.advanceTimersByTimeAsync(1000))
  expect(syncHistoryOnce).toHaveBeenCalledOnce()
  await act(async () => {
    rejectOld?.(new Error('Synthetic old-account failure'))
  })
  await act(async () => {
    window.dispatchEvent(new Event('focus'))
    await vi.advanceTimersByTimeAsync(6999)
  })
  expect(syncHistoryOnce).toHaveBeenCalledOnce()
  await act(async () => vi.advanceTimersByTimeAsync(1))
  expect(syncHistoryOnce).toHaveBeenCalledTimes(2)
})

it('does not overlap requests while a synchronization is pending', async () => {
  let finish: ((result: Awaited<ReturnType<typeof syncHistoryOnce>>) => void) | undefined
  vi.mocked(syncHistoryOnce).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  renderHook(() => useHistorySyncScheduler())
  await act(async () => vi.advanceTimersByTimeAsync(1000))
  await act(async () => {
    window.dispatchEvent(new Event('focus'))
    window.dispatchEvent(new Event('online'))
    await vi.advanceTimersByTimeAsync(60000)
  })
  expect(syncHistoryOnce).toHaveBeenCalledOnce()
  await act(async () => {
    finish?.({ pushed: 1, pulled: 0, pending: 1, hasMore: false })
  })
  await act(async () => vi.advanceTimersByTimeAsync(6999))
  expect(syncHistoryOnce).toHaveBeenCalledOnce()
  await act(async () => vi.advanceTimersByTimeAsync(1))
  expect(syncHistoryOnce).toHaveBeenCalledTimes(2)
})

it('does not automatically retry an unresolved native result', async () => {
  vi.mocked(historySyncOnceBlocked).mockReturnValue(true)
  try {
    renderHook(() => useHistorySyncScheduler())
    await act(async () => vi.advanceTimersByTimeAsync(61000))
    expect(syncHistoryOnce).not.toHaveBeenCalled()
  } finally {
    vi.mocked(historySyncOnceBlocked).mockReturnValue(false)
  }
})

it('does not let focus events bypass the minimum synchronization interval', async () => {
  vi.mocked(syncHistoryOnce).mockResolvedValue({
    pushed: 1,
    pulled: 0,
    pending: 1,
    hasMore: false,
  })
  renderHook(() => useHistorySyncScheduler())
  await act(async () => vi.advanceTimersByTimeAsync(1000))
  expect(syncHistoryOnce).toHaveBeenCalledOnce()
  await act(async () => {
    window.dispatchEvent(new Event('focus'))
    window.dispatchEvent(new Event('online'))
    await vi.advanceTimersByTimeAsync(6999)
  })
  expect(syncHistoryOnce).toHaveBeenCalledOnce()
  await act(async () => vi.advanceTimersByTimeAsync(1))
  expect(syncHistoryOnce).toHaveBeenCalledTimes(2)
})

it('preserves the minimum interval when automatic synchronization is toggled', async () => {
  renderHook(() => useHistorySyncScheduler())
  await act(async () => vi.advanceTimersByTimeAsync(1000))
  expect(syncHistoryOnce).toHaveBeenCalledOnce()
  act(() => setAutomaticHistorySync('synthetic-user', false))
  act(() => setAutomaticHistorySync('synthetic-user', true))
  await act(async () => vi.advanceTimersByTimeAsync(6999))
  expect(syncHistoryOnce).toHaveBeenCalledOnce()
  await act(async () => vi.advanceTimersByTimeAsync(1))
  expect(syncHistoryOnce).toHaveBeenCalledTimes(2)
})

it('preserves authorization-read backoff when the window repeatedly regains focus', async () => {
  vi.mocked(getHistorySyncConsentView).mockRejectedValueOnce(
    new Error('Synthetic authorization read failure'),
  )
  renderHook(() => useHistorySyncScheduler())
  await act(async () => vi.advanceTimersByTimeAsync(1000))
  expect(getHistorySyncConsentView).toHaveBeenCalledOnce()
  await act(async () => {
    window.dispatchEvent(new Event('focus'))
    window.dispatchEvent(new Event('online'))
    await vi.advanceTimersByTimeAsync(29999)
  })
  expect(getHistorySyncConsentView).toHaveBeenCalledOnce()
  await act(async () => vi.advanceTimersByTimeAsync(1))
  expect(getHistorySyncConsentView).toHaveBeenCalledTimes(2)
})

it('turns off automatic sync after an uncertain failed operation rather than retrying it', async () => {
  vi.mocked(syncHistoryOnce).mockRejectedValueOnce(new Error('Synthetic unresolved operation'))
  renderHook(() => useHistorySyncScheduler())
  await act(async () => vi.advanceTimersByTimeAsync(1000))
  await act(async () => vi.advanceTimersByTimeAsync(300000))
  expect(syncHistoryOnce).toHaveBeenCalledOnce()
})
