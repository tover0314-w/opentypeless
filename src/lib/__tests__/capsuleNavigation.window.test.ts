import { beforeEach, expect, it, vi } from 'vitest'
import { openCapsuleSettings } from '../capsuleNavigation'
import { parseHash } from '../router'

const native = vi.hoisted(() => ({
  emitTo: vi.fn().mockResolvedValue(undefined),
  unminimize: vi.fn().mockResolvedValue(undefined),
  show: vi.fn().mockResolvedValue(undefined),
  setFocus: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@tauri-apps/api/webviewWindow', () => ({
  WebviewWindow: {
    getByLabel: vi.fn(async () => native),
  },
}))
vi.mock('@tauri-apps/api/event', () => ({ emitTo: native.emitTo }))
beforeEach(() => vi.clearAllMocks())

it('restores a minimized main window before showing and focusing the destination', async () => {
  await openCapsuleSettings('/history')
  expect(native.emitTo).toHaveBeenCalledWith('main', 'navigate', '#/history')
  expect(native.unminimize).toHaveBeenCalledOnce()
  expect(native.unminimize.mock.invocationCallOrder[0]).toBeLessThan(
    native.show.mock.invocationCallOrder[0],
  )
  expect(native.show.mock.invocationCallOrder[0]).toBeLessThan(
    native.setFocus.mock.invocationCallOrder[0],
  )
})

it.each([
  ['/history', 'history'],
  ['/settings', 'settings'],
  ['/account', 'account'],
  ['/upgrade', 'upgrade'],
  ['/', 'home'],
  ['#/models?section=translation', 'models'],
])('emits a hash the main router can resolve: %s', async (route, expected) => {
  const previousHash = window.location.hash
  try {
    await openCapsuleSettings(route)
    window.location.hash = native.emitTo.mock.calls[0][2]
    expect(parseHash()).toBe(expected)
  } finally {
    window.location.hash = previousHash
  }
})
