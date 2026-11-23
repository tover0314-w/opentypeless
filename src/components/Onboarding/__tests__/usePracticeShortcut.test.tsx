import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { usePracticeShortcut } from '../usePracticeShortcut'

const mocks = vi.hoisted(() => ({
  invoke: vi.fn(),
  channels: [] as Array<{ onmessage?: (payload: unknown) => void }>,
}))

vi.mock('@tauri-apps/api/core', () => ({
  invoke: mocks.invoke,
  Channel: class {
    onmessage?: (payload: unknown) => void
    constructor() {
      mocks.channels.push(this)
    }
  },
}))

beforeEach(() => {
  mocks.invoke.mockReset().mockResolvedValue(undefined)
  mocks.channels.length = 0
})

afterEach(cleanup)

it('replaces the lease when its shortcut identity changes and rejects retained old edges', async () => {
  const edge = vi.fn()
  const { rerender } = renderHook(
    ({ shortcut }) => usePracticeShortcut('translate', edge, undefined, shortcut),
    { initialProps: { shortcut: 'Fn+LeftShift' } },
  )
  await act(async () => {})
  const oldRegistration = mocks.invoke.mock.calls.find(
    ([command]) => command === 'register_practice_shortcut',
  )![1]
  const oldChannel = mocks.channels[0]
  rerender({ shortcut: 'Ctrl+K' })
  await act(async () => {})
  const registrations = mocks.invoke.mock.calls.filter(
    ([command]) => command === 'register_practice_shortcut',
  )
  expect(registrations).toHaveLength(2)
  expect(registrations[1][1].leaseId).not.toBe(oldRegistration.leaseId)
  act(() => oldChannel.onmessage?.({ leaseId: oldRegistration.leaseId, pressed: true }))
  expect(edge).not.toHaveBeenCalled()
  act(() => mocks.channels[1].onmessage?.({ leaseId: registrations[1][1].leaseId, pressed: true }))
  expect(edge).toHaveBeenCalledExactlyOnceWith(true)
})

it('accepts only its registered lease and stops handling input after unmount', async () => {
  const edge = vi.fn()
  const { unmount } = renderHook(() => usePracticeShortcut('translate', edge))
  await act(async () => {})
  const args = mocks.invoke.mock.calls.find(
    ([command]) => command === 'register_practice_shortcut',
  )![1]
  const channel = mocks.channels[0]
  act(() => channel.onmessage?.({ leaseId: 'foreign', pressed: true }))
  expect(edge).not.toHaveBeenCalled()
  act(() => channel.onmessage?.({ leaseId: args.leaseId, pressed: true }))
  expect(edge).toHaveBeenCalledExactlyOnceWith(true)
  unmount()
  act(() => channel.onmessage?.({ leaseId: args.leaseId, pressed: false }))
  expect(edge).toHaveBeenCalledOnce()
})

it('does not revoke the focused replacement lease when a blurred registration resolves late', async () => {
  let finishRegistration: (() => void) | undefined
  mocks.invoke.mockImplementationOnce(
    () =>
      new Promise<void>((resolve) => {
        finishRegistration = resolve
      }),
  )
  const edge = vi.fn()
  renderHook(() => usePracticeShortcut('ask', edge))
  act(() => window.dispatchEvent(new Event('blur')))
  act(() => window.dispatchEvent(new Event('focus')))
  await act(async () => {})
  const registrations = mocks.invoke.mock.calls.filter(
    ([command]) => command === 'register_practice_shortcut',
  )
  expect(registrations).toHaveLength(2)
  expect(registrations[0][1].leaseId).not.toBe(registrations[1][1].leaseId)
  await act(async () => finishRegistration?.())
  const unregisters = mocks.invoke.mock.calls.filter(
    ([command]) => command === 'unregister_practice_shortcut',
  )
  expect(unregisters.every(([, args]) => args.leaseId !== registrations[1][1].leaseId)).toBe(true)
})
