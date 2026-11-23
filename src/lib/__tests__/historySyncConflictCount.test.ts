import { beforeEach, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import {
  getHistorySyncConflictCount,
  listHistorySyncConflicts,
  previewHistorySyncRemoteRecord,
  previewHistorySyncLocalRecord,
  keepLocalHistorySyncConflict,
  keepRemoteHistorySyncConflict,
  historySyncOnceBlocked,
} from '../historySyncConsent'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
const sessionOwner = vi.hoisted(() => ({ current: true }))
vi.mock('../cloud-session', () => ({ captureCloudSessionOwner: () => () => sessionOwner.current }))

const view = {
  active: true,
  sessionEpoch: '11111111-1111-4111-8111-111111111111',
  generation: '22222222-2222-4222-8222-222222222222',
}
beforeEach(() => {
  sessionOwner.current = true
  vi.mocked(invoke).mockReset()
})

it('rejects out-of-order conflict pages instead of advancing an unsafe cursor', async () => {
  const summary = {
    mutationId: view.generation,
    clientRevision: 3,
    serverRevision: 2,
    result: 'conflict',
  }
  vi.mocked(invoke).mockResolvedValueOnce([
    { ...summary, recordId: '44444444-4444-4444-8444-444444444444' },
    { ...summary, recordId: '33333333-3333-4333-8333-333333333333' },
  ])
  await expect(listHistorySyncConflicts(view)).rejects.toThrow('Invalid history sync conflict list')
})

it('does not invoke an old owner resolution when the account changes before dispatch', async () => {
  const summary = {
    recordId: view.sessionEpoch,
    mutationId: view.generation,
    clientRevision: 3,
    serverRevision: 2,
    result: 'conflict' as const,
  }
  vi.mocked(invoke).mockResolvedValue(null)
  const pending = keepLocalHistorySyncConflict(view, summary, 2)
  sessionOwner.current = false
  await expect(pending).rejects.toThrow('History sync resolution superseded')
  expect(invoke).not.toHaveBeenCalled()
  expect(historySyncOnceBlocked()).toBe(false)
})

it('dispatches the validated confirmation snapshot rather than later parameter mutations', async () => {
  const capturedView = { ...view }
  const summary = {
    recordId: view.sessionEpoch,
    mutationId: view.generation,
    clientRevision: 3,
    serverRevision: 2,
    result: 'conflict' as const,
  }
  vi.mocked(invoke).mockResolvedValue(null)
  const pending = keepLocalHistorySyncConflict(capturedView, summary, 2)
  summary.recordId = '33333333-3333-4333-8333-333333333333'
  summary.mutationId = '44444444-4444-4444-8444-444444444444'
  capturedView.generation = '55555555-5555-4555-8555-555555555555'
  await pending
  expect(invoke).toHaveBeenCalledExactlyOnceWith('keep_local_history_sync_conflict', {
    recordId: view.sessionEpoch,
    mutationId: view.generation,
    expectedRemoteRevision: 2,
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
  })
})

it.each([
  { clientRevision: undefined },
  { clientRevision: 0 },
  { clientRevision: 1.5 },
  { serverRevision: undefined },
  { serverRevision: -1 },
  { serverRevision: 1.5 },
  { recordId: undefined },
  { mutationId: undefined },
])('rejects malformed conflict metadata before queueing: %j', async (override) => {
  const summary = {
    recordId: view.sessionEpoch,
    mutationId: view.generation,
    clientRevision: 3,
    serverRevision: 2,
    result: 'conflict' as const,
    ...override,
  }
  vi.mocked(invoke).mockResolvedValue(null)
  await expect(
    keepLocalHistorySyncConflict(
      view,
      summary as Parameters<typeof keepLocalHistorySyncConflict>[1],
      2,
    ),
  ).rejects.toThrow('Invalid history sync resolution')
  expect(invoke).not.toHaveBeenCalled()
})

it('keeps synchronization blocked after a UI timeout until the native resolution ends', async () => {
  vi.useFakeTimers()
  let finish: ((value: unknown) => void) | undefined
  const summary = {
    recordId: view.sessionEpoch,
    mutationId: view.generation,
    clientRevision: 3,
    serverRevision: 2,
    result: 'conflict' as const,
  }
  vi.mocked(invoke).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  try {
    const pending = keepLocalHistorySyncConflict(view, summary, 2)
    const rejected = expect(pending).rejects.toThrow('History sync resolution result unknown')
    await vi.advanceTimersByTimeAsync(60000)
    await rejected
    expect(historySyncOnceBlocked()).toBe(true)
    await expect(keepLocalHistorySyncConflict(view, summary, 2)).rejects.toThrow(
      'History sync resolution result unknown',
    )
    expect(invoke).toHaveBeenCalledOnce()
    finish?.(null)
    await vi.advanceTimersByTimeAsync(0)
    expect(historySyncOnceBlocked()).toBe(false)
  } finally {
    finish?.(null)
    await vi.advanceTimersByTimeAsync(0)
    vi.useRealTimers()
  }
})

it('queues a reviewed local choice without treating arbitrary native responses as success', async () => {
  const summary = {
    recordId: view.sessionEpoch,
    mutationId: view.generation,
    clientRevision: 3,
    serverRevision: 2,
    result: 'conflict' as const,
  }
  vi.mocked(invoke).mockResolvedValueOnce(null)
  await expect(keepLocalHistorySyncConflict(view, summary, 2)).resolves.toBeUndefined()
  expect(invoke).toHaveBeenCalledWith('keep_local_history_sync_conflict', {
    recordId: summary.recordId,
    mutationId: summary.mutationId,
    expectedRemoteRevision: 2,
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
  })
  vi.mocked(invoke).mockResolvedValueOnce({ success: true })
  await expect(keepLocalHistorySyncConflict(view, summary, 2)).rejects.toThrow(
    'History sync resolution result unknown',
  )
})

it('releases the operation lock after explicit native rejection without reporting success', async () => {
  const summary = {
    recordId: view.sessionEpoch,
    mutationId: view.generation,
    clientRevision: 3,
    serverRevision: 2,
    result: 'conflict' as const,
  }
  const rejection = new Error('Synthetic remote version changed')
  vi.mocked(invoke).mockRejectedValueOnce(rejection)
  await expect(keepLocalHistorySyncConflict(view, summary, 2)).rejects.toBe(rejection)
  expect(historySyncOnceBlocked()).toBe(false)
})

it('applies a reviewed cloud choice through the remote command with the same ownership guard', async () => {
  const summary = {
    recordId: view.sessionEpoch,
    mutationId: view.generation,
    clientRevision: 3,
    serverRevision: 2,
    result: 'conflict' as const,
  }
  vi.mocked(invoke).mockResolvedValueOnce(null)
  await expect(keepRemoteHistorySyncConflict(view, summary, 2)).resolves.toBeUndefined()
  expect(invoke).toHaveBeenCalledWith('keep_remote_history_sync_conflict', {
    recordId: summary.recordId,
    mutationId: summary.mutationId,
    expectedRemoteRevision: 2,
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
  })
  sessionOwner.current = false
  await expect(keepRemoteHistorySyncConflict(view, summary, 2)).rejects.toThrow(
    'History sync resolution superseded',
  )
  expect(invoke).toHaveBeenCalledTimes(1)
})

it('handles a late native rejection after the UI timeout and releases the lock', async () => {
  vi.useFakeTimers()
  let rejectNative: ((error: Error) => void) | undefined
  const summary = {
    recordId: view.sessionEpoch,
    mutationId: view.generation,
    clientRevision: 3,
    serverRevision: 2,
    result: 'conflict' as const,
  }
  vi.mocked(invoke).mockImplementationOnce(
    () =>
      new Promise((_resolve, reject) => {
        rejectNative = reject
      }),
  )
  try {
    const pending = keepLocalHistorySyncConflict(view, summary, 2)
    const rejection = expect(pending).rejects.toThrow('History sync resolution result unknown')
    await vi.advanceTimersByTimeAsync(60000)
    await rejection
    expect(historySyncOnceBlocked()).toBe(true)
    rejectNative?.(new Error('Synthetic late native rejection'))
    await vi.advanceTimersByTimeAsync(0)
    expect(historySyncOnceBlocked()).toBe(false)
  } finally {
    rejectNative?.(new Error('Synthetic test cleanup'))
    await vi.advanceTimersByTimeAsync(0)
    vi.useRealTimers()
  }
})

it('passes a conflict page cursor and rejects records before that cursor', async () => {
  const summary = {
    recordId: '33333333-3333-4333-8333-333333333333',
    mutationId: view.generation,
    clientRevision: 1,
    serverRevision: 2,
    result: 'conflict',
  }
  vi.mocked(invoke).mockResolvedValueOnce([summary])
  expect(await listHistorySyncConflicts(view, view.sessionEpoch)).toEqual([summary])
  expect(invoke).toHaveBeenCalledWith('list_history_sync_conflicts', {
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
    afterRecordId: view.sessionEpoch,
  })
  vi.mocked(invoke).mockResolvedValueOnce([{ ...summary, recordId: view.sessionEpoch }])
  await expect(listHistorySyncConflicts(view, view.sessionEpoch)).rejects.toThrow(
    'Invalid history sync conflict list',
  )
})

it('binds the local preview to the requested conflict revision', async () => {
  const summary = {
    recordId: view.sessionEpoch,
    mutationId: view.generation,
    clientRevision: 3,
    serverRevision: 2,
    result: 'conflict' as const,
  }
  const local = {
    recordId: summary.recordId,
    clientRevision: 3,
    deleted: true,
    payload: null,
  }
  vi.mocked(invoke).mockResolvedValueOnce(local)
  expect(await previewHistorySyncLocalRecord(view, summary)).toEqual(local)
  expect(invoke).toHaveBeenCalledWith('preview_history_sync_local_record', {
    recordId: summary.recordId,
    mutationId: summary.mutationId,
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
  })
  vi.mocked(invoke).mockResolvedValueOnce({ ...local, clientRevision: 4 })
  await expect(previewHistorySyncLocalRecord(view, summary)).rejects.toThrow(
    'Invalid local history sync record',
  )
})

it('previews only the requested remote record and distinguishes a tombstone from absence', async () => {
  const summary = {
    recordId: view.sessionEpoch,
    mutationId: view.generation,
    clientRevision: 1,
    serverRevision: 2,
    result: 'conflict' as const,
  }
  const remote = {
    recordId: summary.recordId,
    serverRevision: 2,
    deleted: true,
    payload: null,
  }
  vi.mocked(invoke).mockResolvedValueOnce(remote)
  expect(await previewHistorySyncRemoteRecord(view, summary)).toEqual(remote)
  expect(invoke).toHaveBeenCalledWith('preview_history_sync_remote_record', {
    recordId: summary.recordId,
    mutationId: summary.mutationId,
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
  })
  vi.mocked(invoke).mockResolvedValueOnce(null)
  expect(await previewHistorySyncRemoteRecord(view, summary)).toBeNull()
  vi.mocked(invoke).mockResolvedValueOnce({ ...remote, recordId: view.generation })
  await expect(previewHistorySyncRemoteRecord(view, summary)).rejects.toThrow(
    'Invalid remote history sync record',
  )
  vi.mocked(invoke).mockResolvedValueOnce({ ...remote, serverRevision: 1 })
  await expect(previewHistorySyncRemoteRecord(view, summary)).rejects.toThrow(
    'Invalid remote history sync record',
  )
})

it('reads bounded conflict summaries without including history text', async () => {
  const summary = {
    recordId: view.sessionEpoch,
    mutationId: view.generation,
    clientRevision: 1,
    serverRevision: 2,
    result: 'conflict',
  }
  vi.mocked(invoke).mockResolvedValueOnce([summary])
  expect(await listHistorySyncConflicts(view)).toEqual([summary])
  expect(invoke).toHaveBeenCalledWith('list_history_sync_conflicts', {
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
  })
  vi.mocked(invoke).mockResolvedValueOnce([{ ...summary, rawText: 'Unexpected history' }])
  await expect(listHistorySyncConflicts(view)).rejects.toThrow('Invalid history sync conflict list')
})

it('reads the count for the currently authorized session', async () => {
  vi.mocked(invoke).mockResolvedValueOnce(2)
  expect(await getHistorySyncConflictCount(view)).toBe(2)
  expect(invoke).toHaveBeenCalledWith('get_history_sync_conflict_count', {
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
  })
})

it.each([-1, 1.5, null, '2', Number.MAX_SAFE_INTEGER + 1])(
  'rejects invalid conflict count %j',
  async (response) => {
    vi.mocked(invoke).mockResolvedValueOnce(response)
    await expect(getHistorySyncConflictCount(view)).rejects.toThrow(
      'Invalid history sync conflict count',
    )
  },
)
