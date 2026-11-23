import { act, cleanup, renderHook } from '@testing-library/react'
import { invoke } from '@tauri-apps/api/core'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAuthStore, type AuthUser } from '../../../stores/authStore'
import { useHistorySyncPreparation } from '../useHistorySyncPreparation'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
vi.mock('../../../stores/authStore', () => ({
  useAuthStore: { getState: vi.fn() },
}))
vi.mock('../../../lib/cloud-session', () => ({
  captureCloudSessionOwner: () => () => true,
}))

const owner = { id: 'synthetic-user', email: 'user@example.invalid' } as AuthUser
const epoch = '22222222-2222-4222-8222-222222222222'
const proposal = {
  requestId: '11111111-1111-4111-8111-111111111111',
  description: {
    accountId: 'synthetic-account',
    serviceUrl: 'https://example.invalid/api/history-sync/status',
    sessionEpoch: epoch,
  },
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(useAuthStore.getState).mockReturnValue({ user: owner } as ReturnType<
    typeof useAuthStore.getState
  >)
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

it('ends local preparation after thirty seconds and cleans its late proposal', async () => {
  vi.useFakeTimers()
  let finish!: (value: typeof proposal) => void
  vi.mocked(invoke).mockImplementation((command) => {
    if (command === 'cancel_history_sync_proposal') return Promise.resolve(true)
    return new Promise((resolve) => (finish = resolve))
  })
  const prepared = vi.fn()
  const { result } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  let pending!: Promise<void>
  act(() => {
    pending = result.current.start(epoch)
  })
  await act(async () => {
    await vi.advanceTimersByTimeAsync(30001)
  })
  expect(result.current.busy).toBe(false)
  expect(result.current.error).toBe(true)
  expect(prepared).not.toHaveBeenCalled()
  await act(async () => pending)
  await act(async () => {
    finish(proposal)
    await vi.advanceTimersByTimeAsync(0)
  })
  expect(invoke).toHaveBeenCalledWith('cancel_history_sync_proposal', {
    requestId: proposal.requestId,
  })
  expect(vi.getTimerCount()).toBe(0)
  expect(prepared).not.toHaveBeenCalled()
})

it('clears its deadline on cancellation and successful delivery', async () => {
  vi.useFakeTimers()
  let finish!: (value: typeof proposal) => void
  vi.mocked(invoke).mockImplementation((command) => {
    if (command === 'cancel_history_sync_proposal') return Promise.resolve(true)
    return new Promise((resolve) => (finish = resolve))
  })
  const prepared = vi.fn()
  const { result } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  let pending!: Promise<void>
  act(() => {
    pending = result.current.start(epoch)
  })
  expect(vi.getTimerCount()).toBe(1)
  act(() => result.current.cancel())
  await act(async () => pending)
  expect(vi.getTimerCount()).toBe(0)
  await act(async () => {
    finish(proposal)
    await vi.advanceTimersByTimeAsync(0)
  })
  vi.mocked(invoke).mockResolvedValue(proposal)
  await act(async () => result.current.start(epoch))
  expect(vi.getTimerCount()).toBe(0)
  expect(prepared).toHaveBeenCalledExactlyOnceWith(proposal)
})

it('keeps the replacement deadline active when the old timed-out result arrives', async () => {
  vi.useFakeTimers()
  const completions: Array<(value: typeof proposal) => void> = []
  vi.mocked(invoke).mockImplementation((command) => {
    if (command === 'cancel_history_sync_proposal') return Promise.resolve(true)
    return new Promise((resolve) => completions.push(resolve))
  })
  const prepared = vi.fn()
  const { result } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  let oldPending!: Promise<void>
  act(() => {
    oldPending = result.current.start(epoch)
  })
  await act(async () => vi.advanceTimersByTimeAsync(30001))
  await act(async () => oldPending)
  let newPending!: Promise<void>
  act(() => {
    newPending = result.current.start(epoch)
  })
  await act(async () => {
    completions[0](proposal)
    await vi.advanceTimersByTimeAsync(0)
  })
  expect(result.current.busy).toBe(true)
  expect(result.current.error).toBe(false)
  expect(vi.getTimerCount()).toBe(1)
  expect(prepared).not.toHaveBeenCalled()
  await act(async () => vi.advanceTimersByTimeAsync(30001))
  await act(async () => newPending)
  expect(result.current.busy).toBe(false)
  expect(result.current.error).toBe(true)
  const replacement = { ...proposal, requestId: '44444444-4444-4444-8444-444444444444' }
  await act(async () => {
    completions[1](replacement)
    await vi.advanceTimersByTimeAsync(0)
  })
  expect(
    vi.mocked(invoke).mock.calls.filter(([command]) => command === 'cancel_history_sync_proposal'),
  ).toEqual([
    ['cancel_history_sync_proposal', { requestId: proposal.requestId }],
    ['cancel_history_sync_proposal', { requestId: replacement.requestId }],
  ])
  expect(vi.getTimerCount()).toBe(0)
})

it('releases the deadline on unmount while still cleaning the late native result', async () => {
  vi.useFakeTimers()
  let finish!: (value: typeof proposal) => void
  vi.mocked(invoke).mockImplementation((command) => {
    if (command === 'cancel_history_sync_proposal') return Promise.resolve(true)
    return new Promise((resolve) => (finish = resolve))
  })
  const prepared = vi.fn()
  const { result, unmount } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  let pending!: Promise<void>
  act(() => {
    pending = result.current.start(epoch)
  })
  unmount()
  await act(async () => pending)
  expect(vi.getTimerCount()).toBe(0)
  await act(async () => {
    finish(proposal)
    await vi.advanceTimersByTimeAsync(0)
  })
  expect(invoke).toHaveBeenCalledWith('cancel_history_sync_proposal', {
    requestId: proposal.requestId,
  })
  expect(prepared).not.toHaveBeenCalled()
  expect(vi.getTimerCount()).toBe(0)
})

it('reads epoch then prepares without loading credentials or confirming', async () => {
  vi.mocked(invoke).mockImplementation(async (command) =>
    command === 'get_history_sync_session_epoch' ? epoch : proposal,
  )
  const prepared = vi.fn()
  const { result } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  await act(async () => result.current.start())
  expect(prepared).toHaveBeenCalledExactlyOnceWith(proposal)
  expect(vi.mocked(invoke).mock.calls).toEqual([
    ['get_history_sync_session_epoch'],
    ['prepare_history_sync_proposal', { expectedSessionEpoch: epoch }],
  ])
})

it('cancels waiting immediately and cleans exactly the late native proposal', async () => {
  let finish!: (value: typeof proposal) => void
  vi.mocked(invoke).mockImplementation((command) => {
    if (command === 'cancel_history_sync_proposal') return Promise.resolve(true)
    return new Promise((resolve) => {
      finish = resolve
    })
  })
  const prepared = vi.fn()
  const { result } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  let pending!: Promise<void>
  act(() => {
    pending = result.current.start(epoch)
    result.current.cancel()
  })
  await act(async () => pending)
  expect(result.current.busy).toBe(false)
  expect(prepared).not.toHaveBeenCalled()
  await act(async () => finish(proposal))
  expect(vi.mocked(invoke).mock.calls).toEqual([
    ['prepare_history_sync_proposal', { expectedSessionEpoch: epoch }],
    ['cancel_history_sync_proposal', { requestId: proposal.requestId }],
  ])
  expect(prepared).not.toHaveBeenCalled()
})

it('cleans a malformed native result without delivering or confirming it', async () => {
  vi.mocked(invoke).mockImplementation(async (command) =>
    command === 'prepare_history_sync_proposal'
      ? { ...proposal, description: { ...proposal.description, accountId: '' } }
      : true,
  )
  const prepared = vi.fn()
  const { result } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  await act(async () => result.current.start(epoch))
  expect(prepared).not.toHaveBeenCalled()
  expect(result.current.error).toBe(true)
  expect(vi.mocked(invoke).mock.calls.map(([command]) => command)).toEqual([
    'prepare_history_sync_proposal',
    'cancel_history_sync_proposal',
  ])
})

it('cleans failed delivery using the real cancellation wrapper and exposes an error', async () => {
  vi.mocked(invoke).mockImplementation(async (command) =>
    command === 'prepare_history_sync_proposal' ? proposal : true,
  )
  const prepared = vi.fn(() => {
    throw new Error('Synthetic receiver failure')
  })
  const { result } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  await act(async () => result.current.start(epoch))
  expect(result.current.error).toBe(true)
  expect(vi.mocked(invoke).mock.calls).toEqual([
    ['prepare_history_sync_proposal', { expectedSessionEpoch: epoch }],
    ['cancel_history_sync_proposal', { requestId: proposal.requestId }],
  ])
})
