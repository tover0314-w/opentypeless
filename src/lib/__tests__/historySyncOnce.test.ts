import { invoke } from '@tauri-apps/api/core'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import {
  HistorySyncResultUnknownError,
  historySyncOnceBlocked,
  subscribeHistorySyncOnceResult,
  syncHistoryOnce as requestSyncHistoryOnce,
} from '../historySyncConsent'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
const session = vi.hoisted(() => ({ generation: 0 }))
const displayedAuthorization = {
  active: true,
  sessionEpoch: '11111111-1111-4111-8111-111111111111',
  generation: '22222222-2222-4222-8222-222222222222',
}
const syncHistoryOnce = () => requestSyncHistoryOnce(displayedAuthorization)
vi.mock('../cloud-session', () => ({
  captureCloudSessionOwner: () => {
    const generation = session.generation
    return () => generation === session.generation
  },
}))

beforeEach(() => {
  session.generation += 1
  vi.mocked(invoke).mockReset()
})
afterEach(() => vi.useRealTimers())

it('reports timeout as unknown without cancelling or retrying native work', async () => {
  vi.useFakeTimers()
  vi.mocked(invoke).mockReturnValue(new Promise(() => {}))
  const result = syncHistoryOnce().catch((error: unknown) => error)
  await vi.advanceTimersByTimeAsync(65000)
  expect(await result).toBeInstanceOf(HistorySyncResultUnknownError)
  expect(historySyncOnceBlocked()).toBe(true)
  await expect(syncHistoryOnce()).rejects.toBeInstanceOf(HistorySyncResultUnknownError)
  expect(invoke).toHaveBeenCalledExactlyOnceWith('sync_history_once', {
    expectedSessionEpoch: displayedAuthorization.sessionEpoch,
    expectedGeneration: displayedAuthorization.generation,
  })
})

it('returns one bounded explicit sync result without retrying', async () => {
  const result = { pushed: 2, pulled: 3, hasMore: true, pending: 4 }
  vi.mocked(invoke).mockResolvedValue(result)
  await expect(syncHistoryOnce()).resolves.toEqual(result)
  expect(invoke).toHaveBeenCalledExactlyOnceWith('sync_history_once', {
    expectedSessionEpoch: displayedAuthorization.sessionEpoch,
    expectedGeneration: displayedAuthorization.generation,
  })
})

it('releases only the matching request gate when a valid native result arrives after timeout', async () => {
  vi.useFakeTimers()
  let finish!: (value: unknown) => void
  vi.mocked(invoke).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const pending = syncHistoryOnce().catch((error: unknown) => error)
  const received = vi.fn()
  const unsubscribe = subscribeHistorySyncOnceResult(displayedAuthorization, received)
  await vi.advanceTimersByTimeAsync(65000)
  expect(await pending).toBeInstanceOf(HistorySyncResultUnknownError)
  expect(historySyncOnceBlocked()).toBe(true)
  finish({ pushed: 1, pulled: 2, hasMore: false, pending: 0 })
  await vi.advanceTimersByTimeAsync(0)
  expect(historySyncOnceBlocked()).toBe(false)
  expect(invoke).toHaveBeenCalledTimes(1)
  expect(received).toHaveBeenCalledExactlyOnceWith({
    pushed: 1,
    pulled: 2,
    hasMore: false,
    pending: 0,
  })
  unsubscribe()
})

it('keeps a timed-out request blocked when its late native response is malformed', async () => {
  vi.useFakeTimers()
  let finish!: (value: unknown) => void
  vi.mocked(invoke).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const pending = syncHistoryOnce().catch((error: unknown) => error)
  await vi.advanceTimersByTimeAsync(65000)
  await pending
  finish({ pushed: 1, pulled: 2, hasMore: false, pending: 0, token: 'private' })
  await vi.advanceTimersByTimeAsync(0)
  expect(historySyncOnceBlocked()).toBe(true)
  expect(invoke).toHaveBeenCalledTimes(1)
})

it('blocks another call while native sync is still pending', async () => {
  let finish!: (value: unknown) => void
  vi.mocked(invoke).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const pending = syncHistoryOnce()
  expect(historySyncOnceBlocked()).toBe(true)
  await expect(syncHistoryOnce()).rejects.toThrow('History sync result unknown')
  expect(invoke).toHaveBeenCalledTimes(1)
  finish({ pushed: 0, pulled: 0, hasMore: false, pending: 0 })
  await pending
  expect(historySyncOnceBlocked()).toBe(false)
})

it('notifies only matching active listeners and isolates listener failures', async () => {
  const wrongGeneration = vi.fn()
  const removed = vi.fn()
  const stale = vi.fn()
  const stopStale = subscribeHistorySyncOnceResult(displayedAuthorization, stale)
  session.generation += 1
  const stopWrong = subscribeHistorySyncOnceResult(
    { ...displayedAuthorization, generation: '33333333-3333-4333-8333-333333333333' },
    wrongGeneration,
  )
  const stopRemoved = subscribeHistorySyncOnceResult(displayedAuthorization, removed)
  stopRemoved()
  const stopThrowing = subscribeHistorySyncOnceResult(displayedAuthorization, () => {
    throw new Error('Synthetic subscriber failure')
  })
  const current = vi.fn()
  const stopCurrent = subscribeHistorySyncOnceResult(displayedAuthorization, current)
  const result = { pushed: 1, pulled: 0, hasMore: false, pending: 0 }
  vi.mocked(invoke).mockResolvedValue(result)
  try {
    await expect(syncHistoryOnce()).resolves.toEqual(result)
    expect(current).toHaveBeenCalledExactlyOnceWith(result)
    expect(stale).not.toHaveBeenCalled()
    expect(removed).not.toHaveBeenCalled()
    expect(wrongGeneration).not.toHaveBeenCalled()
  } finally {
    stopStale()
    stopWrong()
    stopThrowing()
    stopCurrent()
  }
})

it('keeps a rejected native operation blocked because partial writes are not known', async () => {
  vi.mocked(invoke).mockRejectedValue(new Error('Synthetic pull failure after push'))
  await expect(syncHistoryOnce()).rejects.toThrow('Synthetic pull failure after push')
  expect(historySyncOnceBlocked()).toBe(true)
  await expect(syncHistoryOnce()).rejects.toBeInstanceOf(HistorySyncResultUnknownError)
  expect(invoke).toHaveBeenCalledTimes(1)
})

it('does not let a late old-session completion release a new-session attempt', async () => {
  let finishOld!: (value: unknown) => void
  let finishNew!: (value: unknown) => void
  vi.mocked(invoke)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishOld = resolve
        }),
    )
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishNew = resolve
        }),
    )
  const old = syncHistoryOnce().catch((error: unknown) => error)
  session.generation += 1
  const current = syncHistoryOnce()
  const response = { pushed: 0, pulled: 0, hasMore: false, pending: 0 }
  finishOld(response)
  expect(await old).toBeInstanceOf(Error)
  expect(historySyncOnceBlocked()).toBe(true)
  finishNew(response)
  await current
  expect(historySyncOnceBlocked()).toBe(false)
})

it.each([
  null,
  {},
  { pushed: -1, pulled: 0, hasMore: false, pending: 0 },
  { pushed: 0, pulled: 501, hasMore: false, pending: 0 },
  { pushed: 0, pulled: 0, hasMore: 'false', pending: 0 },
  { pushed: 0, pulled: 0, hasMore: false, pending: 5001 },
  { pushed: 0, pulled: 0, hasMore: false, pending: 0, token: 'private' },
])('rejects malformed sync result %j', async (response) => {
  vi.mocked(invoke).mockResolvedValue(response)
  await expect(syncHistoryOnce()).rejects.toThrow('Invalid history sync response')
})
