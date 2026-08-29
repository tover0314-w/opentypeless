import { beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import {
  clearSessionTokenFromMemory,
  invalidateCloudSessionOnce,
  loadSessionToken,
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

  it('writes the bearer token to the system vault and memory, not localStorage', async () => {
    await persistSessionToken('session-token')

    expect(invoke).toHaveBeenCalledWith('set_session_token', { token: 'session-token' })
    expect(await loadSessionToken()).toBe('session-token')
    expect(localStorage.getItem('session_token')).toBeNull()
  })

  it('restores a persisted system-vault token after a renderer restart', async () => {
    vi.mocked(invoke).mockResolvedValueOnce('vault-token')

    await expect(loadSessionToken()).resolves.toBe('vault-token')

    expect(invoke).toHaveBeenCalledWith('get_session_token')
    expect(localStorage.getItem('session_token')).toBeNull()
  })

  it('migrates a legacy localStorage token only after the vault write succeeds', async () => {
    localStorage.setItem('session_token', 'legacy-token')

    await expect(loadSessionToken()).resolves.toBe('legacy-token')

    expect(invoke).toHaveBeenCalledWith('set_session_token', { token: 'legacy-token' })
    expect(localStorage.getItem('session_token')).toBeNull()
  })

  it('keeps the legacy token available for a later retry when migration fails', async () => {
    localStorage.setItem('session_token', 'legacy-token')
    vi.mocked(invoke).mockRejectedValueOnce(new Error('Rust unavailable'))

    await expect(loadSessionToken()).rejects.toThrow('Rust unavailable')

    expect(localStorage.getItem('session_token')).toBe('legacy-token')
  })

  it('keeps the previous in-memory token when the vault rejects a rotation', async () => {
    await persistSessionToken('previous-token')
    vi.mocked(invoke).mockRejectedValueOnce(new Error('Rust unavailable'))

    await expect(persistSessionToken('rotated-token')).rejects.toThrow('Rust unavailable')

    expect(await loadSessionToken()).toBe('previous-token')
    expect(localStorage.getItem('session_token')).toBeNull()
  })

  it('serializes concurrent vault writes and keeps the newest token in memory', async () => {
    let releaseFirst!: () => void
    const firstWrite = new Promise<void>((resolve) => {
      releaseFirst = resolve
    })
    const storedTokens: string[] = []
    vi.mocked(invoke).mockImplementation((command, args) => {
      if (command !== 'set_session_token') return Promise.resolve(undefined)
      const token = (args as { token: string }).token
      storedTokens.push(token)
      return token === 'first-token' ? firstWrite : Promise.resolve(undefined)
    })

    const first = persistSessionToken('first-token')
    await Promise.resolve()
    const second = persistSessionToken('second-token')
    await Promise.resolve()

    expect(storedTokens).toEqual(['first-token'])
    releaseFirst()
    await Promise.all([first, second])
    expect(storedTokens).toEqual(['first-token', 'second-token'])
    expect(await loadSessionToken()).toBe('second-token')
  })

  it('clears user-bound managed capability before signing out', async () => {
    await persistSessionToken('previous-token')
    vi.mocked(invoke).mockClear()

    await persistSessionToken(null)

    expect(invoke).toHaveBeenNthCalledWith(1, 'clear_managed_stt_capability')
    expect(invoke).toHaveBeenNthCalledWith(2, 'set_session_token', { token: '' })
    expect(await loadSessionToken()).toBeNull()
  })

  it('clears the session even when managed capability cleanup reports an error', async () => {
    await persistSessionToken('session-token')
    vi.mocked(invoke)
      .mockRejectedValueOnce(new Error('capability cleanup failed'))
      .mockResolvedValueOnce(undefined)

    await expect(persistSessionToken(null)).rejects.toThrow('capability cleanup failed')

    expect(invoke).toHaveBeenLastCalledWith('set_session_token', { token: '' })
    expect(await loadSessionToken()).toBeNull()
  })

  it('can fail closed in renderer memory after a backend clear error', async () => {
    await persistSessionToken('session-token')
    vi.mocked(invoke)
      .mockRejectedValueOnce(new Error('capability unavailable'))
      .mockRejectedValueOnce(new Error('vault unavailable'))

    await expect(persistSessionToken(null)).rejects.toThrow('vault unavailable')
    clearSessionTokenFromMemory()

    expect(await loadSessionToken()).toBeNull()
    expect(localStorage.getItem('session_token')).toBeNull()
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
