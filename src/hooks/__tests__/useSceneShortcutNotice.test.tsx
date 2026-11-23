import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { listen } from '@tauri-apps/api/event'
import { useSceneShortcutNotice } from '../useSceneShortcutNotice'
import { useCapsuleAlertStore } from '../../stores/capsuleAlertStore'
import { useAppStore } from '../../stores/appStore'

const epoch = '11111111-1111-4111-8111-111111111111'
const notification = (revision: number) => ({ configEpoch: epoch, revision })

function commit(revision: number, name: string | null) {
  const config = {
    ...useAppStore.getState().config,
    active_scene: name
      ? {
          id: 'custom_synthetic',
          source: 'custom' as const,
          name,
          prompt_template: 'private prompt',
        }
      : null,
  }
  useAppStore.setState({
    configCommit: { configEpoch: epoch, revision, saveId: null, config },
  })
}

vi.mock('@tauri-apps/api/event', () => ({ listen: vi.fn() }))

beforeEach(() => {
  vi.mocked(listen).mockReset().mockResolvedValue(vi.fn())
  useCapsuleAlertStore.setState({ alert: null, queue: [] })
  commit(1, 'Synthetic scene')
})
afterEach(() => cleanup())

it('shows only a validated scene name, including the no-manual-scene state', async () => {
  renderHook(() => useSceneShortcutNotice())
  await act(async () => {})
  const callback = vi.mocked(listen).mock.calls[0][1]
  await act(async () =>
    callback({ event: 'scene:shortcut-changed', id: 1, payload: notification(1) }),
  )
  expect(useCapsuleAlertStore.getState().alert).toMatchObject({
    kind: 'scene_changed',
    text: 'Synthetic scene',
  })
  await act(async () => {
    commit(2, null)
    callback({ event: 'scene:shortcut-changed', id: 2, payload: notification(2) })
  })
  expect(useCapsuleAlertStore.getState().alert).toMatchObject({
    kind: 'scene_changed',
    text: undefined,
  })
  const current = useCapsuleAlertStore.getState().alert
  for (const payload of [{ prompt: 'untrusted' }, 42, '', null, notification(1)]) {
    await act(async () => callback({ event: 'scene:shortcut-changed', id: 3, payload }))
    expect(useCapsuleAlertStore.getState().alert).toBe(current)
  }
})

it('waits for matching persisted configuration and rejects out-of-order notifications', async () => {
  renderHook(() => useSceneShortcutNotice())
  await act(async () => {})
  const callback = vi.mocked(listen).mock.calls[0][1]
  await act(async () =>
    callback({ event: 'scene:shortcut-changed', id: 1, payload: notification(3) }),
  )
  expect(useCapsuleAlertStore.getState().alert).toBeNull()
  await act(async () => {
    callback({ event: 'scene:shortcut-changed', id: 2, payload: notification(2) })
    commit(2, 'Older scene')
  })
  expect(useCapsuleAlertStore.getState().alert).toBeNull()
  await act(async () => commit(3, 'Latest scene'))
  expect(useCapsuleAlertStore.getState().alert?.text).toBe('Latest scene')
  const current = useCapsuleAlertStore.getState().alert
  await act(async () => {
    callback({ event: 'scene:shortcut-changed', id: 3, payload: notification(2) })
    callback({ event: 'scene:shortcut-changed', id: 4, payload: notification(3) })
    callback({
      event: 'scene:shortcut-changed',
      id: 5,
      payload: { ...notification(4), configEpoch: '22222222-2222-4222-8222-222222222222' },
    })
  })
  expect(useCapsuleAlertStore.getState().alert).toBe(current)
})

it('ignores callbacks after unmount and releases a late listener registration', async () => {
  let resolveRegistration: ((stop: () => void) => void) | undefined
  vi.mocked(listen).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        resolveRegistration = resolve
      }),
  )
  const stop = vi.fn()
  const { unmount } = renderHook(() => useSceneShortcutNotice())
  const callback = vi.mocked(listen).mock.calls[0][1]
  unmount()
  await act(async () => {
    resolveRegistration?.(stop)
    callback({ event: 'scene:shortcut-changed', id: 1, payload: 'Late scene' })
  })
  expect(stop).toHaveBeenCalledOnce()
  expect(useCapsuleAlertStore.getState().alert).toBeNull()
})
