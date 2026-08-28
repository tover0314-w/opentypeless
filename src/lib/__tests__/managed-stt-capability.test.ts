import { beforeEach, describe, expect, it, vi } from 'vitest'

const invoke = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))
vi.mock('@tauri-apps/api/core', () => ({ invoke }))

import { syncManagedSttCapability } from '../managed-stt-capability'

const snapshot = {
  schemaVersion: 1 as const,
  userId: 'user-1',
  managedSttCapabilities: null,
  generatedAt: '2026-07-22T08:00:00.000Z',
}

describe('managed STT capability sync', () => {
  beforeEach(() => invoke.mockClear())

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
