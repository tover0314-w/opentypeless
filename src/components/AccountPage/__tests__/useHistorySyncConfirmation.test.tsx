import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAuthStore, type AuthUser } from '../../../stores/authStore'
import {
  cancelHistorySyncProposal,
  confirmAndReadHistorySyncConsent,
  HistorySyncConfirmationUnknownError,
} from '../../../lib/historySyncConsent'
import { useHistorySyncConfirmation } from '../useHistorySyncConfirmation'

vi.mock('../../../lib/historySyncConsent', async (importOriginal) => {
  const original = await importOriginal<typeof import('../../../lib/historySyncConsent')>()
  return {
    ...original,
    confirmAndReadHistorySyncConsent: vi.fn(),
    cancelHistorySyncProposal: vi.fn(),
  }
})
vi.mock('../../../stores/authStore', () => ({
  useAuthStore: { getState: vi.fn() },
}))

const owner = { id: 'synthetic-user', email: 'user@example.invalid' } as AuthUser
const proposal = {
  requestId: '11111111-1111-4111-8111-111111111111',
  description: {
    accountId: 'synthetic-account',
    serviceUrl: 'https://example.invalid/api/history-sync/status',
    sessionEpoch: '22222222-2222-4222-8222-222222222222',
  },
}
const view = {
  sessionEpoch: proposal.description.sessionEpoch,
  active: true,
  generation: '33333333-3333-4333-8333-333333333333',
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(useAuthStore.getState).mockReturnValue({ user: owner } as ReturnType<
    typeof useAuthStore.getState
  >)
  vi.mocked(confirmAndReadHistorySyncConsent).mockResolvedValue(view)
  vi.mocked(cancelHistorySyncProposal).mockResolvedValue(true)
})
afterEach(cleanup)

it('cancels the displayed proposal once and blocks confirmation afterward', async () => {
  const { result } = renderHook(() => useHistorySyncConfirmation(owner, proposal))
  await act(async () => result.current.cancel())
  expect(cancelHistorySyncProposal).toHaveBeenCalledExactlyOnceWith(proposal.requestId)
  expect(result.current.cancelled).toBe(true)
  await act(async () => result.current.confirm())
  expect(confirmAndReadHistorySyncConsent).not.toHaveBeenCalled()
})

it('does not interpret a false cancellation result as confirmed or safely cancelled', async () => {
  vi.mocked(cancelHistorySyncProposal).mockResolvedValue(false)
  const { result } = renderHook(() => useHistorySyncConfirmation(owner, proposal))
  await act(async () => result.current.cancel())
  expect(result.current.cancelled).toBe(false)
  expect(result.current.confirmed).toBe(false)
  expect(result.current.resultUnknown).toBe(true)
  await act(async () => result.current.confirm())
  expect(confirmAndReadHistorySyncConsent).not.toHaveBeenCalled()
})

it('confirms the displayed proposal only once and waits for native readback', async () => {
  let finish!: (value: typeof view) => void
  vi.mocked(confirmAndReadHistorySyncConsent).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const { result } = renderHook(() => useHistorySyncConfirmation(owner, proposal))
  let pending!: Promise<void>
  act(() => {
    pending = result.current.confirm()
    void result.current.confirm()
  })
  expect(result.current.busy).toBe(true)
  expect(result.current.confirmed).toBe(false)
  expect(confirmAndReadHistorySyncConsent).toHaveBeenCalledExactlyOnceWith(proposal)
  await act(async () => {
    finish(view)
    await pending
  })
  expect(result.current.confirmed).toBe(true)
  expect(result.current.busy).toBe(false)
  await act(async () => result.current.confirm())
  expect(confirmAndReadHistorySyncConsent).toHaveBeenCalledTimes(1)
})

it('keeps unknown confirmation blocked until a fresh proposal is provided', async () => {
  vi.mocked(confirmAndReadHistorySyncConsent).mockRejectedValueOnce(
    new HistorySyncConfirmationUnknownError(),
  )
  const { result, rerender } = renderHook(
    ({ current }) => useHistorySyncConfirmation(owner, current),
    { initialProps: { current: proposal } },
  )
  await act(async () => result.current.confirm())
  expect(result.current.resultUnknown).toBe(true)
  expect(result.current.error).toBe(true)
  await act(async () => result.current.confirm())
  expect(confirmAndReadHistorySyncConsent).toHaveBeenCalledTimes(1)
  rerender({ current: { ...proposal, requestId: '44444444-4444-4444-8444-444444444444' } })
  expect(result.current.resultUnknown).toBe(false)
  await act(async () => result.current.confirm())
  expect(confirmAndReadHistorySyncConsent).toHaveBeenCalledTimes(2)
})

it('ignores a completed confirmation after the account owner changes', async () => {
  let finish!: (value: typeof view) => void
  vi.mocked(confirmAndReadHistorySyncConsent).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const { result } = renderHook(() => useHistorySyncConfirmation(owner, proposal))
  let pending!: Promise<void>
  act(() => {
    pending = result.current.confirm()
  })
  vi.mocked(useAuthStore.getState).mockReturnValue({
    user: { ...owner, id: 'new-user' },
  } as ReturnType<typeof useAuthStore.getState>)
  await act(async () => {
    finish(view)
    await pending
  })
  expect(result.current.confirmed).toBe(false)
  await waitFor(() => expect(confirmAndReadHistorySyncConsent).toHaveBeenCalledTimes(1))
})

it('keeps the pending confirmation owned when an equivalent proposal object is recreated', async () => {
  let finish!: (value: typeof view) => void
  vi.mocked(confirmAndReadHistorySyncConsent).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const { result, rerender } = renderHook(
    ({ current }) => useHistorySyncConfirmation(owner, current),
    { initialProps: { current: proposal } },
  )
  let pending!: Promise<void>
  act(() => {
    pending = result.current.confirm()
  })
  rerender({ current: { ...proposal, description: { ...proposal.description } } })
  expect(result.current.busy).toBe(true)
  await act(async () => result.current.confirm())
  expect(confirmAndReadHistorySyncConsent).toHaveBeenCalledTimes(1)
  await act(async () => {
    finish(view)
    await pending
  })
  expect(result.current.confirmed).toBe(true)
})

it('does not cancel while confirmation is pending', async () => {
  let finish!: (value: typeof view) => void
  vi.mocked(confirmAndReadHistorySyncConsent).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const { result } = renderHook(() => useHistorySyncConfirmation(owner, proposal))
  let pending!: Promise<void>
  act(() => {
    pending = result.current.confirm()
  })
  await act(async () => result.current.cancel())
  expect(cancelHistorySyncProposal).not.toHaveBeenCalled()
  expect(result.current.busy).toBe(true)
  await act(async () => {
    finish(view)
    await pending
  })
  expect(result.current.confirmed).toBe(true)
  expect(result.current.cancelled).toBe(false)
})

it('does not let a late cancellation settle the replacement proposal confirmation', async () => {
  let finishCancellation!: (value: boolean) => void
  let finishConfirmation!: (value: typeof view) => void
  vi.mocked(cancelHistorySyncProposal).mockImplementation(
    () =>
      new Promise((resolve) => {
        finishCancellation = resolve
      }),
  )
  vi.mocked(confirmAndReadHistorySyncConsent).mockImplementation(
    () =>
      new Promise((resolve) => {
        finishConfirmation = resolve
      }),
  )
  const replacement = { ...proposal, requestId: '44444444-4444-4444-8444-444444444444' }
  const { result, rerender } = renderHook(
    ({ current }) => useHistorySyncConfirmation(owner, current),
    { initialProps: { current: proposal } },
  )
  let cancellation!: Promise<void>
  act(() => {
    cancellation = result.current.cancel()
  })
  rerender({ current: replacement })
  let confirmation!: Promise<void>
  act(() => {
    confirmation = result.current.confirm()
  })
  await act(async () => {
    finishCancellation(true)
    await cancellation
  })
  expect(result.current.busy).toBe(true)
  expect(result.current.cancelled).toBe(false)
  expect(result.current.confirmed).toBe(false)
  expect(result.current.resultUnknown).toBe(false)
  expect(cancelHistorySyncProposal).toHaveBeenCalledExactlyOnceWith(proposal.requestId)
  expect(confirmAndReadHistorySyncConsent).toHaveBeenCalledExactlyOnceWith(replacement)
  await act(async () => {
    finishConfirmation(view)
    await confirmation
  })
  expect(result.current.busy).toBe(false)
  expect(result.current.confirmed).toBe(true)
})
