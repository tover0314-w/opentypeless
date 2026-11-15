import { beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import {
  enqueueCloudSessionOperation,
  invalidateOwnedCloudSession,
  clearSessionTokenFromMemory,
  invalidateCloudSessionOnce,
  loadSessionToken,
  markCloudSessionAuthenticated,
  persistSessionToken,
  persistOwnedSessionToken,
  registerCloudSessionInvalidation,
  resetCloudSessionCoordinatorForTests,
} from '../cloud-session'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

describe('cloud session coordinator', () => {
  it.each([undefined, 7, {}, [], ''])(
    'rejects a malformed snapshot token before attempting persistence: %j',
    async (token) => {
      vi.mocked(invoke).mockImplementation(async (command) => {
        if (command === 'get_cloud_session_snapshot')
          return { token, sessionEpoch: crypto.randomUUID() }
        if (command === 'set_cloud_session_snapshot')
          return { token: 'candidate-token', sessionEpoch: crypto.randomUUID() }
      })
      await expect(persistOwnedSessionToken('candidate-token', () => true)).rejects.toThrow(
        'Invalid cloud session snapshot',
      )
      expect(invoke).not.toHaveBeenCalledWith('set_cloud_session_snapshot', expect.anything())
    },
  )

  it('does not retain an old token when failed OAuth recovery has an invalid epoch', async () => {
    await persistSessionToken('existing-token')
    let snapshots = 0
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_cloud_session_snapshot') {
        snapshots += 1
        return {
          token: 'existing-token',
          sessionEpoch: snapshots === 1 ? crypto.randomUUID() : '',
        }
      }
      if (command === 'set_cloud_session_snapshot') throw new Error('Synthetic commit failure')
    })
    await expect(persistOwnedSessionToken('candidate-token', () => true)).rejects.toThrow(
      'Cloud session disabled',
    )
    expect(await loadSessionToken()).toBeNull()
  })

  it('clears legacy renderer credentials when a rejected write disabled native recovery', async () => {
    await persistSessionToken('existing-token')
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'set_session_token') throw new Error('Synthetic protected write failure')
      if (command === 'get_cloud_session_snapshot')
        return { token: null, sessionEpoch: crypto.randomUUID() }
    })
    await expect(persistSessionToken('candidate-token')).rejects.toThrow('Cloud session disabled')
    expect(await loadSessionToken()).toBeNull()
  })

  it('reconciles native identity when a successful commit returns malformed data', async () => {
    await persistSessionToken('existing-token')
    let snapshots = 0
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_cloud_session_snapshot') {
        snapshots += 1
        return {
          token: snapshots === 1 ? 'existing-token' : 'candidate-token',
          sessionEpoch: crypto.randomUUID(),
        }
      }
      if (command === 'set_cloud_session_snapshot')
        return { token: 'candidate-token', sessionEpoch: '' }
    })
    await expect(persistOwnedSessionToken('candidate-token', () => true)).rejects.toThrow(
      'Cloud session disabled',
    )
    expect(await loadSessionToken()).toBeNull()
  })

  it('does not clear a newer session when failed commit recovery returns late', async () => {
    await persistSessionToken('existing-token')
    let snapshots = 0
    let finishRecovery!: (value: unknown) => void
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_cloud_session_snapshot') {
        snapshots += 1
        if (snapshots === 1) return { token: 'existing-token', sessionEpoch: crypto.randomUUID() }
        return new Promise((resolve) => {
          finishRecovery = resolve
        })
      }
      if (command === 'set_cloud_session_snapshot') throw new Error('Synthetic commit failure')
    })
    const old = persistOwnedSessionToken('candidate-token', () => true)
    const rejected = expect(old).rejects.toThrow('Synthetic commit failure')
    await vi.waitFor(() => expect(finishRecovery).toBeTypeOf('function'))
    const newer = persistSessionToken('new-token')
    finishRecovery({ token: null, sessionEpoch: crypto.randomUUID() })
    await rejected
    await newer
    expect(await loadSessionToken()).toBe('new-token')
  })

  it('disables the previous renderer identity when failed commit recovery returns another token', async () => {
    await persistSessionToken('existing-token')
    let snapshots = 0
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_cloud_session_snapshot') {
        snapshots += 1
        return {
          token: snapshots === 1 ? 'existing-token' : 'different-token',
          sessionEpoch: crypto.randomUUID(),
        }
      }
      if (command === 'set_cloud_session_snapshot') throw new Error('Synthetic commit failure')
    })
    await expect(persistOwnedSessionToken('candidate-token', () => true)).rejects.toThrow(
      'Cloud session disabled',
    )
    expect(await loadSessionToken()).toBeNull()
  })

  it('disables cached credentials when failed commit recovery cannot read native state', async () => {
    await persistSessionToken('existing-token')
    let snapshots = 0
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_cloud_session_snapshot') {
        snapshots += 1
        if (snapshots > 1) throw new Error('Synthetic snapshot unavailable')
        return { token: 'existing-token', sessionEpoch: crypto.randomUUID() }
      }
      if (command === 'set_cloud_session_snapshot') throw new Error('Synthetic commit failure')
    })
    await expect(persistOwnedSessionToken('candidate-token', () => true)).rejects.toThrow(
      'Cloud session disabled',
    )
    expect(await loadSessionToken()).toBeNull()
    expect(invoke).not.toHaveBeenCalledWith('set_session_token', { token: '' })
  })

  it('disables renderer credentials when a failed native commit disabled its session', async () => {
    await persistSessionToken('existing-token')
    let snapshots = 0
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_cloud_session_snapshot') {
        snapshots += 1
        return {
          token: snapshots === 1 ? 'existing-token' : null,
          sessionEpoch: crypto.randomUUID(),
        }
      }
      if (command === 'set_cloud_session_snapshot') throw new Error('Synthetic vault failure')
    })
    await expect(persistOwnedSessionToken('candidate-token', () => true)).rejects.toThrow()
    expect(await loadSessionToken()).toBeNull()
  })

  it.each([undefined, null, '', 'not-an-attempt', 1])(
    'rejects malformed native attempt identity before saving: %j',
    async (attempt) => {
      vi.mocked(invoke).mockImplementation(async (command) => {
        if (command === 'get_cloud_session_snapshot')
          return { token: null, sessionEpoch: crypto.randomUUID() }
        if (command === 'begin_cloud_session_commit') return attempt
        if (command === 'set_cloud_session_snapshot')
          return { token: 'candidate-token', sessionEpoch: crypto.randomUUID() }
      })
      await expect(
        persistOwnedSessionToken('candidate-token', () => true, new AbortController().signal),
      ).rejects.toThrow('Invalid cloud session commit attempt')
      expect(invoke).not.toHaveBeenCalledWith('set_cloud_session_snapshot', expect.anything())
    },
  )

  it('cancels a submitted native attempt when OAuth aborts before commit', async () => {
    const controller = new AbortController()
    const attempt = crypto.randomUUID()
    let rejectCommit!: (error: Error) => void
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_cloud_session_snapshot')
        return { token: null, sessionEpoch: crypto.randomUUID() }
      if (command === 'begin_cloud_session_commit') return attempt
      if (command === 'set_cloud_session_snapshot') {
        return new Promise((_resolve, reject) => {
          rejectCommit = reject
        })
      }
      if (command === 'cancel_cloud_session_commit') {
        rejectCommit(new Error('Cloud session commit cancelled'))
        return true
      }
    })
    const pending = persistOwnedSessionToken('candidate-token', () => true, controller.signal)
    const rejected = expect(pending).rejects.toThrow('Cloud session commit cancelled')
    await vi.waitFor(() => expect(rejectCommit).toBeTypeOf('function'))
    controller.abort()
    await rejected
    expect(invoke).toHaveBeenCalledWith('cancel_cloud_session_commit', { attemptId: attempt })
    expect(
      vi.mocked(invoke).mock.calls.filter(([command]) => command === 'cancel_cloud_session_commit'),
    ).toHaveLength(1)
    expect(await loadSessionToken()).toBeNull()
  })

  it('does not let an old cleanup rejection reset the newer cleanup lock', async () => {
    let rejectOld!: (error: Error) => void
    let finishCurrent!: () => void
    const invalidate = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<void>((_resolve, reject) => {
            rejectOld = reject
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<void>((resolve) => {
            finishCurrent = resolve
          }),
      )
    registerCloudSessionInvalidation(invalidate)
    const old = invalidateCloudSessionOnce()
    const oldRejected = expect(old).rejects.toThrow('old cleanup failed')
    markCloudSessionAuthenticated()
    const current = invalidateCloudSessionOnce()
    rejectOld(new Error('old cleanup failed'))
    await oldRejected
    const duplicate = invalidateCloudSessionOnce()
    expect(duplicate).toBe(current)
    expect(invalidate).toHaveBeenCalledTimes(2)
    finishCurrent()
    await current
  })

  it('rejects a malformed native commit identity instead of accepting the candidate', async () => {
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_cloud_session_snapshot')
        return { token: null, sessionEpoch: crypto.randomUUID() }
      if (command === 'set_cloud_session_snapshot')
        return { token: 'candidate-token', sessionEpoch: '' }
    })
    await expect(persistOwnedSessionToken('candidate-token', () => true)).rejects.toThrow(
      'Invalid cloud session commit result',
    )
  })

  it('rejects an OAuth commit superseded while its native snapshot is loading', async () => {
    let finish!: (value: unknown) => void
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_cloud_session_snapshot') {
        return new Promise((resolve) => {
          finish = resolve
        })
      }
      if (command === 'set_cloud_session_snapshot')
        return { token: 'old-candidate', sessionEpoch: crypto.randomUUID() }
    })
    const pending = persistOwnedSessionToken('old-candidate', () => true)
    const rejected = expect(pending).rejects.toThrow('Session token write superseded')
    await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
    const newer = persistSessionToken('new-token')
    finish({ token: null, sessionEpoch: crypto.randomUUID() })
    await rejected
    await newer
    expect(invoke).not.toHaveBeenCalledWith('set_cloud_session_snapshot', expect.anything())
    expect(await loadSessionToken()).toBe('new-token')
  })

  it('binds an OAuth commit to the native snapshot captured before persistence', async () => {
    const epoch = crypto.randomUUID()
    const committedEpoch = crypto.randomUUID()
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_cloud_session_snapshot') return { token: null, sessionEpoch: epoch }
      if (command === 'set_cloud_session_snapshot')
        return { token: 'candidate-token', sessionEpoch: committedEpoch }
    })
    await persistOwnedSessionToken('candidate-token', () => true)
    expect(invoke).toHaveBeenCalledWith('set_cloud_session_snapshot', {
      token: 'candidate-token',
      expectedSessionEpoch: epoch,
    })
    expect(invoke).not.toHaveBeenCalledWith('set_session_token', expect.anything())
    expect(await loadSessionToken()).toBe('candidate-token')
  })

  it('does not commit when OAuth is cancelled during native snapshot loading', async () => {
    let active = true
    let finish!: (value: unknown) => void
    vi.mocked(invoke).mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const pending = persistOwnedSessionToken('candidate-token', () => active)
    const rejected = expect(pending).rejects.toThrow('Session token write cancelled')
    await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
    active = false
    finish({ token: null, sessionEpoch: crypto.randomUUID() })
    await rejected
    expect(invoke).not.toHaveBeenCalledWith('set_cloud_session_snapshot', expect.anything())
  })

  it('validates a native invalidation received before renderer session recovery', async () => {
    const epoch = crypto.randomUUID()
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_session_token') return 'restored-token'
      if (command === 'get_cloud_session_snapshot')
        return { token: 'restored-token', sessionEpoch: epoch }
    })
    const invalidate = vi.fn().mockResolvedValue(undefined)
    registerCloudSessionInvalidation(invalidate)
    await invalidateOwnedCloudSession({ sessionEpoch: epoch })
    expect(invalidate).toHaveBeenCalledOnce()
  })

  it('ignores an initialization event superseded while the vault is recovering', async () => {
    const epoch = crypto.randomUUID()
    let finishRecovery!: (value: unknown) => void
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_session_token') {
        return new Promise((resolve) => {
          finishRecovery = resolve
        })
      }
      if (command === 'get_cloud_session_snapshot')
        return { token: 'new-token', sessionEpoch: epoch }
    })
    const invalidate = vi.fn().mockResolvedValue(undefined)
    registerCloudSessionInvalidation(invalidate)
    const event = invalidateOwnedCloudSession({ sessionEpoch: epoch })
    await vi.waitFor(() => expect(finishRecovery).toBeTypeOf('function'))
    const login = persistSessionToken('new-token')
    finishRecovery('old-token')
    await Promise.all([event, login])
    expect(invalidate).not.toHaveBeenCalled()
    expect(invoke).not.toHaveBeenCalledWith('get_cloud_session_snapshot')
    expect(await loadSessionToken()).toBe('new-token')
  })

  it.each([null, [], {}, { sessionEpoch: '' }, { sessionEpoch: 'secret' }])(
    'ignores malformed native session identity: %j',
    async (payload) => {
      await persistSessionToken('current-token')
      vi.mocked(invoke).mockClear()
      const invalidate = vi.fn().mockResolvedValue(undefined)
      registerCloudSessionInvalidation(invalidate)
      await invalidateOwnedCloudSession(payload)
      expect(invoke).not.toHaveBeenCalled()
      expect(invalidate).not.toHaveBeenCalled()
    },
  )

  it('ignores an old native epoch but invalidates the current epoch', async () => {
    await persistSessionToken('current-token')
    const epoch = crypto.randomUUID()
    vi.mocked(invoke).mockResolvedValue({ token: 'current-token', sessionEpoch: epoch })
    const invalidate = vi.fn().mockResolvedValue(undefined)
    registerCloudSessionInvalidation(invalidate)
    await invalidateOwnedCloudSession({ sessionEpoch: crypto.randomUUID() })
    expect(invalidate).not.toHaveBeenCalled()
    await invalidateOwnedCloudSession({ sessionEpoch: epoch })
    expect(invalidate).toHaveBeenCalledOnce()
  })

  it('does not invalidate after a newer login supersedes native snapshot validation', async () => {
    await persistSessionToken('old-token')
    const epoch = crypto.randomUUID()
    let finish!: (value: unknown) => void
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_cloud_session_snapshot') {
        return new Promise((resolve) => {
          finish = resolve
        })
      }
    })
    const invalidate = vi.fn().mockResolvedValue(undefined)
    registerCloudSessionInvalidation(invalidate)
    const pending = invalidateOwnedCloudSession({ sessionEpoch: epoch })
    await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
    await persistSessionToken('new-token')
    finish({ token: 'old-token', sessionEpoch: epoch })
    await pending
    expect(invalidate).not.toHaveBeenCalled()
  })

  it('rejects an OAuth write cancelled while waiting in the persistence queue', async () => {
    await persistSessionToken('existing-token')
    let release!: () => void
    const blocker = enqueueCloudSessionOperation(
      () =>
        new Promise<void>((resolve) => {
          release = resolve
        }),
    )
    await vi.waitFor(() => expect(release).toBeTypeOf('function'))
    let active = true
    const write = persistSessionToken('cancelled-token', () => active)
    const rejected = expect(write).rejects.toThrow('Session token write cancelled')
    active = false
    release()
    await blocker
    await rejected
    expect(invoke).not.toHaveBeenCalledWith('set_session_token', { token: 'cancelled-token' })
    expect(await loadSessionToken()).toBe('existing-token')
  })

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

  it('does not start legacy migration when a new session supersedes the queued restore', async () => {
    localStorage.setItem('session_token', 'legacy-token')
    const restore = loadSessionToken()
    const login = persistSessionToken('new-token')
    await Promise.all([restore, login])
    expect(invoke).not.toHaveBeenCalledWith('set_session_token', { token: 'legacy-token' })
    expect(await loadSessionToken()).toBe('new-token')
  })

  it('serializes a started legacy migration before the new session write', async () => {
    localStorage.setItem('session_token', 'legacy-token')
    let finishMigration: () => void = () => {}
    const migration = new Promise<void>((resolve) => {
      finishMigration = resolve
    })
    const writes: string[] = []
    vi.mocked(invoke).mockImplementation((command, args) => {
      if (command !== 'set_session_token') return Promise.resolve(undefined)
      const token = (args as { token: string }).token
      writes.push(token)
      return token === 'legacy-token' ? migration : Promise.resolve(undefined)
    })
    const restore = loadSessionToken()
    await Promise.resolve()
    const login = persistSessionToken('new-token')
    await Promise.resolve()
    expect(writes).toEqual(['legacy-token'])
    finishMigration()
    await Promise.all([restore, login])
    expect(writes).toEqual(['legacy-token', 'new-token'])
    expect(await loadSessionToken()).toBe('new-token')
  })

  it('keeps the previous in-memory token only when native state confirms it after rejection', async () => {
    await persistSessionToken('previous-token')
    vi.mocked(invoke).mockRejectedValueOnce(new Error('Rust unavailable'))
    vi.mocked(invoke).mockResolvedValueOnce({
      token: 'previous-token',
      sessionEpoch: crypto.randomUUID(),
    })

    await expect(persistSessionToken('rotated-token')).rejects.toThrow('Rust unavailable')

    expect(await loadSessionToken()).toBe('previous-token')
    expect(localStorage.getItem('session_token')).toBeNull()
  })

  it('disables the old renderer token when a legacy write and recovery both fail', async () => {
    await persistSessionToken('previous-token')
    vi.mocked(invoke).mockRejectedValue(new Error('Synthetic IPC unavailable'))
    await expect(persistSessionToken('candidate-token')).rejects.toThrow('Cloud session disabled')
    expect(await loadSessionToken()).toBeNull()
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

  it('skips a queued token when sign-out supersedes it before the write starts', async () => {
    let releaseFirst: () => void = () => {}
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
    const queued = persistSessionToken('queued-token')
    const rejection = expect(queued).rejects.toThrow('superseded')
    const signOut = persistSessionToken(null)
    releaseFirst()
    await Promise.all([first, rejection, signOut])
    expect(storedTokens).toEqual(['first-token', ''])
    expect(await loadSessionToken()).toBeNull()
  })

  it('skips a queued token after the renderer has invalidated its session', async () => {
    const queued = persistSessionToken('queued-token')
    clearSessionTokenFromMemory()
    await expect(queued).rejects.toThrow('superseded')
    expect(invoke).not.toHaveBeenCalledWith('set_session_token', { token: 'queued-token' })
    expect(await loadSessionToken()).toBeNull()
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
