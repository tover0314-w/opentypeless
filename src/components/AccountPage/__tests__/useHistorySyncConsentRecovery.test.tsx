import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAuthStore, type AuthUser } from '../../../stores/authStore'
import { getHistorySyncConsentView } from '../../../lib/historySyncConsent'
import { useHistorySyncConsentRecovery } from '../useHistorySyncConsentRecovery'

vi.mock('../../../lib/historySyncConsent', () => ({ getHistorySyncConsentView: vi.fn() }))
vi.mock('../../../stores/authStore', () => ({ useAuthStore: { getState: vi.fn() } }))

const owner = { id: 'synthetic-user', email: 'user@example.invalid' } as AuthUser
const epoch = '11111111-1111-4111-8111-111111111111'
const view = {
  sessionEpoch: epoch,
  active: true,
  generation: '22222222-2222-4222-8222-222222222222',
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(useAuthStore.getState).mockReturnValue({ user: owner } as ReturnType<
    typeof useAuthStore.getState
  >)
  vi.mocked(getHistorySyncConsentView).mockResolvedValue(view)
})
afterEach(cleanup)

it.each([true, false])('only reads an explicit current snapshot with active=%s', async (active) => {
  const snapshot = { ...view, active, generation: active ? view.generation : null }
  vi.mocked(getHistorySyncConsentView).mockResolvedValue(snapshot)
  const { result } = renderHook(() => useHistorySyncConsentRecovery(owner, epoch))
  expect(getHistorySyncConsentView).not.toHaveBeenCalled()
  await act(async () => result.current.read())
  expect(getHistorySyncConsentView).toHaveBeenCalledTimes(1)
  expect(result.current.view).toEqual(snapshot)
  expect(result.current.error).toBe(false)
})

it('blocks duplicate reads while pending and clears a previous snapshot on failure', async () => {
  const { result } = renderHook(() => useHistorySyncConsentRecovery(owner, epoch))
  await act(async () => result.current.read())
  let fail!: (error: Error) => void
  vi.mocked(getHistorySyncConsentView).mockImplementation(
    () => new Promise((_resolve, reject) => (fail = reject)),
  )
  let pending!: Promise<void>
  act(() => {
    pending = result.current.read()
    void result.current.read()
  })
  expect(getHistorySyncConsentView).toHaveBeenCalledTimes(2)
  expect(result.current.busy).toBe(true)
  expect(result.current.view).toBeNull()
  await act(async () => {
    fail(new Error('Synthetic read failure'))
    await pending
  })
  expect(result.current.error).toBe(true)
  expect(result.current.view).toBeNull()
  expect(result.current.busy).toBe(false)
})

it('rejects a snapshot from a different native session', async () => {
  vi.mocked(getHistorySyncConsentView).mockResolvedValue({ ...view, sessionEpoch: view.generation })
  const { result } = renderHook(() => useHistorySyncConsentRecovery(owner, epoch))
  await act(async () => result.current.read())
  expect(result.current.error).toBe(true)
  expect(result.current.view).toBeNull()
})

it.each(['owner', 'epoch', 'unmount'] as const)(
  'ignores a late snapshot after %s changes',
  async (change) => {
    let finish!: (value: typeof view) => void
    vi.mocked(getHistorySyncConsentView).mockImplementation(
      () => new Promise((resolve) => (finish = resolve)),
    )
    const { result, rerender, unmount } = renderHook(
      ({ currentOwner, currentEpoch }) => useHistorySyncConsentRecovery(currentOwner, currentEpoch),
      { initialProps: { currentOwner: owner, currentEpoch: epoch } },
    )
    let pending!: Promise<void>
    act(() => {
      pending = result.current.read()
    })
    if (change === 'owner') {
      const replacement = { ...owner }
      vi.mocked(useAuthStore.getState).mockReturnValue({ user: replacement } as ReturnType<
        typeof useAuthStore.getState
      >)
      rerender({ currentOwner: replacement, currentEpoch: epoch })
    }
    if (change === 'epoch') rerender({ currentOwner: owner, currentEpoch: view.generation })
    if (change === 'unmount') unmount()
    await act(async () => {
      finish(view)
      await pending
    })
    expect(result.current.view).toBeNull()
    expect(result.current.error).toBe(false)
  },
)

it('does not let an old read settle a replacement session read', async () => {
  const completions: Array<(value: typeof view) => void> = []
  vi.mocked(getHistorySyncConsentView).mockImplementation(
    () => new Promise((resolve) => completions.push(resolve)),
  )
  const { result, rerender } = renderHook(
    ({ currentEpoch }) => useHistorySyncConsentRecovery(owner, currentEpoch),
    { initialProps: { currentEpoch: epoch } },
  )
  let oldPending!: Promise<void>
  let newPending!: Promise<void>
  act(() => {
    oldPending = result.current.read()
  })
  rerender({ currentEpoch: view.generation })
  act(() => {
    newPending = result.current.read()
  })
  await act(async () => {
    completions[0](view)
    await oldPending
  })
  expect(result.current.busy).toBe(true)
  expect(result.current.view).toBeNull()
  const replacement = { ...view, sessionEpoch: view.generation }
  await act(async () => {
    completions[1](replacement)
    await newPending
  })
  expect(result.current.view).toEqual(replacement)
  expect(result.current.busy).toBe(false)
})
