import { beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import {
  invalidateCloudSessionOnce,
  markCloudSessionAuthenticated,
  persistSessionToken,
  registerCloudSessionInvalidation,
  resetCloudSessionCoordinatorForTests,
} from '../cloud-session'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

describe('cloud session coordinator', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.mocked(invoke).mockResolvedValue(undefined)
    resetCloudSessionCoordinatorForTests()
  })

  it('writes the bearer token to browser and Rust storage', async () => {
    await persistSessionToken('session-token')

    expect(localStorage.getItem('session_token')).toBe('session-token')
    expect(invoke).toHaveBeenCalledWith('set_session_token', { token: 'session-token' })
  })

  it('restores the browser token when Rust rejects a token rotation', async () => {
    localStorage.setItem('session_token', 'previous-token')
    vi.mocked(invoke).mockRejectedValueOnce(new Error('Rust unavailable'))

    await expect(persistSessionToken('rotated-token')).rejects.toThrow('Rust unavailable')

    expect(localStorage.getItem('session_token')).toBe('previous-token')
  })

  it('clears user-bound managed capability before signing out', async () => {
    localStorage.setItem('session_token', 'previous-token')

    await persistSessionToken(null)

    expect(invoke).toHaveBeenNthCalledWith(1, 'clear_managed_stt_capability')
    expect(invoke).toHaveBeenNthCalledWith(2, 'set_session_token', { token: '' })
  })

  it('shares one invalidation across concurrent managed-cloud failures', async () => {
    let release!: () => void
    const pending = new Promise<void>((resolve) => {
      release = resolve
    })
    const handler = vi.fn(() => pending)
    registerCloudSessionInvalidation(handler)

    const first = invalidateCloudSessionOnce()
    const second = invalidateCloudSessionOnce()

    expect(handler).toHaveBeenCalledTimes(1)
    expect(first).toBe(second)
    release()
    await Promise.all([first, second])
    await invalidateCloudSessionOnce()
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('allows a new invalidation after successful authentication', async () => {
    const handler = vi.fn().mockResolvedValue(undefined)
    registerCloudSessionInvalidation(handler)

    await invalidateCloudSessionOnce()
    markCloudSessionAuthenticated()
    await invalidateCloudSessionOnce()

    expect(handler).toHaveBeenCalledTimes(2)
  })
})
