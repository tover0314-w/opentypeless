import { beforeEach, describe, expect, it, vi } from 'vitest'

const invoke = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))
vi.mock('@tauri-apps/api/core', () => ({ invoke }))

import { syncManagedSttCapability } from '../managed-stt-capability'
import {
  enqueueCloudSessionOperation,
  persistSessionToken,
  resetCloudSessionCoordinatorForTests,
} from '../cloud-session'

const snapshot = {
  schemaVersion: 1 as const,
  userId: 'user-1',
  managedSttCapabilities: null,
  generatedAt: '2026-07-22T08:00:00.000Z',
}

describe('managed STT capability sync', () => {
  beforeEach(() => {
    invoke.mockReset()
    invoke.mockResolvedValue(undefined)
    resetCloudSessionCoordinatorForTests()
  })

  it('skips an unstarted capability write when logout supersedes its session', async () => {
    let release!: () => void
    const blocker = enqueueCloudSessionOperation(
      () =>
        new Promise<void>((resolve) => {
          release = resolve
        }),
    )
    await vi.waitFor(() => expect(release).toBeTypeOf('function'))
    const write = syncManagedSttCapability(snapshot, 'user-1')
    const logout = persistSessionToken(null)
    release()
    await Promise.all([blocker, write, logout])
    expect(invoke).not.toHaveBeenCalledWith('cache_managed_stt_capability', expect.anything())
    expect(invoke).toHaveBeenCalledWith('clear_managed_stt_capability')
    expect(invoke).toHaveBeenLastCalledWith('set_session_token', { token: '' })
    await persistSessionToken('new-session-token')
    const currentSnapshot = { ...snapshot, userId: 'user-2' }
    await syncManagedSttCapability(currentSnapshot, 'user-2')
    expect(
      invoke.mock.calls.filter(([command]) => command === 'cache_managed_stt_capability'),
    ).toEqual([
      [
        'cache_managed_stt_capability',
        { accountSnapshot: currentSnapshot, expectedUserId: 'user-2' },
      ],
    ])
  })

  it('does not allow an older capability write to finish after logout clearing', async () => {
    let finishWrite!: () => void
    let cachedUser: string | null = null
    invoke.mockImplementation(async (command: string) => {
      if (command === 'cache_managed_stt_capability') {
        await new Promise<void>((resolve) => {
          finishWrite = resolve
        })
        cachedUser = 'user-1'
      } else if (command === 'clear_managed_stt_capability') {
        cachedUser = null
      }
    })
    const write = syncManagedSttCapability(snapshot, 'user-1')
    await vi.waitFor(() => expect(finishWrite).toBeTypeOf('function'))
    const logout = persistSessionToken(null)
    await Promise.resolve()
    await Promise.resolve()
    finishWrite()
    await Promise.all([write, logout])
    expect(cachedUser).toBeNull()
  })

  it('continues logout clearing after a queued capability write rejects', async () => {
    let failWrite!: (error: Error) => void
    const commands: string[] = []
    invoke.mockImplementation(async (command: string) => {
      commands.push(command)
      if (command === 'cache_managed_stt_capability') {
        await new Promise<void>((_resolve, reject) => {
          failWrite = reject
        })
      }
    })
    const write = syncManagedSttCapability(snapshot, 'user-1')
    const rejected = expect(write).rejects.toThrow('Synthetic cache failure')
    await vi.waitFor(() => expect(failWrite).toBeTypeOf('function'))
    const logout = persistSessionToken(null)
    expect(commands).toEqual(['cache_managed_stt_capability'])
    failWrite(new Error('Synthetic cache failure'))
    await rejected
    await expect(logout).resolves.toBeUndefined()
    expect(commands).toEqual([
      'cache_managed_stt_capability',
      'clear_managed_stt_capability',
      'set_session_token',
    ])
    expect(invoke).toHaveBeenLastCalledWith('set_session_token', { token: '' })
  })

  it('caches only a matching authenticated account snapshot', async () => {
    await syncManagedSttCapability(snapshot, 'user-1')

    expect(invoke).toHaveBeenCalledWith('cache_managed_stt_capability', {
      accountSnapshot: snapshot,
      expectedUserId: 'user-1',
    })
  })

  it('clears cached capability when the snapshot is absent or belongs to another user', async () => {
    await syncManagedSttCapability(null, 'user-1')
    await syncManagedSttCapability(snapshot, 'user-2')

    expect(invoke).toHaveBeenNthCalledWith(1, 'clear_managed_stt_capability')
    expect(invoke).toHaveBeenNthCalledWith(2, 'clear_managed_stt_capability')
  })
})
