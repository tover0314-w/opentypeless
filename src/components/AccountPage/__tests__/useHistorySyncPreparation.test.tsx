import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAuthStore, type AuthUser } from '../../../stores/authStore'
import {
  cancelHistorySyncProposal,
  getHistorySyncSessionEpoch,
  prepareHistorySyncProposal,
} from '../../../lib/historySyncConsent'
import { useHistorySyncPreparation } from '../useHistorySyncPreparation'

vi.mock('../../../lib/historySyncConsent', () => ({
  prepareHistorySyncProposal: vi.fn(),
  cancelHistorySyncProposal: vi.fn(),
  getHistorySyncSessionEpoch: vi.fn(),
}))
vi.mock('../../../stores/authStore', () => ({
  useAuthStore: { getState: vi.fn() },
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
  vi.mocked(prepareHistorySyncProposal).mockResolvedValue(proposal)
  vi.mocked(cancelHistorySyncProposal).mockResolvedValue(true)
  vi.mocked(getHistorySyncSessionEpoch).mockResolvedValue(epoch)
})
afterEach(cleanup)

it('reads the native epoch only after explicit start without a supplied epoch', async () => {
  const prepared = vi.fn()
  const { result } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  expect(getHistorySyncSessionEpoch).not.toHaveBeenCalled()
  await act(async () => result.current.start())
  expect(getHistorySyncSessionEpoch).toHaveBeenCalledTimes(1)
  expect(prepareHistorySyncProposal).toHaveBeenCalledExactlyOnceWith(epoch, expect.any(AbortSignal))
  expect(prepared).toHaveBeenCalledExactlyOnceWith(proposal)
})

it('does not discover after cancellation during epoch reading', async () => {
  let finish!: (value: string) => void
  vi.mocked(getHistorySyncSessionEpoch).mockImplementation(
    () => new Promise((resolve) => (finish = resolve)),
  )
  const prepared = vi.fn()
  const { result } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  let pending!: Promise<void>
  act(() => {
    pending = result.current.start()
    result.current.cancel()
  })
  await act(async () => {
    finish(epoch)
    await pending
  })
  expect(prepareHistorySyncProposal).not.toHaveBeenCalled()
  expect(prepared).not.toHaveBeenCalled()
  expect(result.current.busy).toBe(false)
  expect(result.current.error).toBe(false)
})

it('cleans an undelivered proposal and reports error when its receiving callback throws', async () => {
  const prepared = vi.fn(() => {
    throw new Error('Synthetic receiver failure')
  })
  const { result } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  await act(async () => result.current.start(epoch))
  expect(cancelHistorySyncProposal).toHaveBeenCalledExactlyOnceWith(proposal.requestId)
  expect(result.current.error).toBe(true)
  expect(result.current.busy).toBe(false)
})

it('does not prepare on mount and transfers an explicitly requested proposal once', async () => {
  const prepared = vi.fn()
  const { result, unmount } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  expect(prepareHistorySyncProposal).not.toHaveBeenCalled()
  await act(async () => result.current.start(epoch))
  expect(prepared).toHaveBeenCalledExactlyOnceWith(proposal)
  expect(prepareHistorySyncProposal).toHaveBeenCalledExactlyOnceWith(epoch, expect.any(AbortSignal))
  unmount()
  expect(cancelHistorySyncProposal).not.toHaveBeenCalled()
})

it.each(['cancel', 'unmount', 'owner'] as const)(
  'aborts on %s and discards a late proposal without transferring it',
  async (action) => {
    let finish!: (value: typeof proposal) => void
    vi.mocked(prepareHistorySyncProposal).mockImplementation(
      () => new Promise((resolve) => (finish = resolve)),
    )
    const prepared = vi.fn()
    const { result, unmount, rerender } = renderHook(
      ({ currentOwner }) => useHistorySyncPreparation(currentOwner, prepared),
      { initialProps: { currentOwner: owner } },
    )
    let pending!: Promise<void>
    act(() => {
      pending = result.current.start(epoch)
      void result.current.start(epoch)
    })
    expect(prepareHistorySyncProposal).toHaveBeenCalledTimes(1)
    const signal = vi.mocked(prepareHistorySyncProposal).mock.calls[0][1]!
    if (action === 'cancel') act(() => result.current.cancel())
    if (action === 'unmount') unmount()
    if (action === 'owner') {
      const replacement = { ...owner }
      vi.mocked(useAuthStore.getState).mockReturnValue({ user: replacement } as ReturnType<
        typeof useAuthStore.getState
      >)
      rerender({ currentOwner: replacement })
    }
    expect(signal.aborted).toBe(true)
    await act(async () => {
      finish(proposal)
      await pending
    })
    expect(prepared).not.toHaveBeenCalled()
    expect(cancelHistorySyncProposal).toHaveBeenCalledExactlyOnceWith(proposal.requestId)
  },
)

it('does not let an old cancelled preparation finish a new pending preparation', async () => {
  const completions: Array<(value: typeof proposal) => void> = []
  vi.mocked(prepareHistorySyncProposal).mockImplementation(
    () => new Promise((resolve) => completions.push(resolve)),
  )
  const prepared = vi.fn()
  const { result } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  let oldPending!: Promise<void>
  let newPending!: Promise<void>
  act(() => {
    oldPending = result.current.start(epoch)
    result.current.cancel()
    newPending = result.current.start(epoch)
  })
  await act(async () => {
    completions[0](proposal)
    await oldPending
  })
  expect(result.current.busy).toBe(true)
  expect(prepared).not.toHaveBeenCalled()
  const replacement = { ...proposal, requestId: '44444444-4444-4444-8444-444444444444' }
  await act(async () => {
    completions[1](replacement)
    await newPending
  })
  expect(prepared).toHaveBeenCalledExactlyOnceWith(replacement)
  expect(result.current.busy).toBe(false)
})

it('reports a current preparation error without transferring a proposal', async () => {
  vi.mocked(prepareHistorySyncProposal).mockRejectedValue(new Error('Synthetic discovery failure'))
  const prepared = vi.fn()
  const { result } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  await act(async () => result.current.start(epoch))
  expect(result.current.error).toBe(true)
  expect(result.current.busy).toBe(false)
  expect(prepared).not.toHaveBeenCalled()
})

it('ignores abort rejection and does not start for a stale owner', async () => {
  vi.mocked(prepareHistorySyncProposal).mockImplementation(
    (_epoch, signal) =>
      new Promise((_resolve, reject) => {
        signal!.addEventListener('abort', () => reject(signal!.reason), { once: true })
      }),
  )
  const prepared = vi.fn()
  const { result } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  let pending!: Promise<void>
  act(() => {
    pending = result.current.start(epoch)
    result.current.cancel()
  })
  await act(async () => pending)
  expect(result.current.error).toBe(false)
  vi.mocked(useAuthStore.getState).mockReturnValue({ user: null } as ReturnType<
    typeof useAuthStore.getState
  >)
  await act(async () => result.current.start(epoch))
  expect(prepareHistorySyncProposal).toHaveBeenCalledTimes(1)
})

it('observes a stale cleanup rejection without changing the new request state', async () => {
  const completions: Array<(value: typeof proposal) => void> = []
  vi.mocked(prepareHistorySyncProposal).mockImplementation(
    () => new Promise((resolve) => completions.push(resolve)),
  )
  vi.mocked(cancelHistorySyncProposal).mockRejectedValue(new Error('Synthetic cleanup failure'))
  const prepared = vi.fn()
  const { result } = renderHook(() => useHistorySyncPreparation(owner, prepared))
  let oldPending!: Promise<void>
  let newPending!: Promise<void>
  act(() => {
    oldPending = result.current.start(epoch)
    result.current.cancel()
    newPending = result.current.start(epoch)
  })
  await act(async () => {
    completions[0](proposal)
    await oldPending
  })
  expect(result.current.busy).toBe(true)
  expect(result.current.error).toBe(false)
  expect(prepared).not.toHaveBeenCalled()
  await act(async () => {
    completions[1]({ ...proposal, requestId: '44444444-4444-4444-8444-444444444444' })
    await newPending
  })
  expect(prepared).toHaveBeenCalledTimes(1)
})
