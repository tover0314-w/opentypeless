import { invoke } from '@tauri-apps/api/core'
import { beforeEach, expect, it, vi } from 'vitest'
import { acknowledgeHistorySyncAttempt, getHistorySyncAttempt } from '../historySyncConsent'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
vi.mock('../cloud-session', () => ({ captureCloudSessionOwner: () => () => true }))
beforeEach(() => {
  vi.mocked(invoke).mockReset()
})

const requestId = '11111111-1111-4111-8111-111111111111'
const view = {
  active: true,
  sessionEpoch: requestId,
  generation: '22222222-2222-4222-8222-222222222222',
}

it('acknowledges only the displayed unresolved request without starting sync', async () => {
  vi.mocked(invoke).mockResolvedValue(null)
  await acknowledgeHistorySyncAttempt(view, { requestId, status: 'unknown', result: null })
  expect(invoke).toHaveBeenCalledExactlyOnceWith('acknowledge_history_sync_attempt', {
    requestId,
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
  })
})

it('rejects an unconfirmed acknowledgement response', async () => {
  vi.mocked(invoke).mockResolvedValue({ success: true })
  await expect(
    acknowledgeHistorySyncAttempt(view, { requestId, status: 'unknown', result: null }),
  ).rejects.toThrow()
})
it.each([
  null,
  { requestId, status: 'running', result: null },
  { requestId, status: 'unknown', result: null },
  { requestId, status: 'unknown', result: null, acknowledged: true },
  { requestId, status: 'confirmed', result: { pushed: 1, pulled: 2, hasMore: false, pending: 0 } },
])('reads a bounded native attempt without starting synchronization: %j', async (response) => {
  vi.mocked(invoke).mockResolvedValue(response)
  await expect(getHistorySyncAttempt(view)).resolves.toEqual(response)
  expect(invoke).toHaveBeenCalledExactlyOnceWith('get_history_sync_attempt', {
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
  })
})

it.each([
  {},
  { requestId: 'invalid', status: 'unknown', result: null },
  { requestId, status: 'confirmed', result: null },
  { requestId, status: 'running', result: { pushed: 1 } },
  { requestId, status: 'failed', result: null },
  { requestId, status: ['unknown'], result: null },
  { requestId, status: ['running'], result: null },
  { requestId, status: 'unknown', result: null, token: 'private' },
  { requestId, status: 'unknown', result: null, acknowledged: 'true' },
  { requestId, status: 'running', result: null, acknowledged: true },
])('rejects malformed persisted attempt: %j', async (response) => {
  vi.mocked(invoke).mockResolvedValue(response)
  await expect(getHistorySyncAttempt(view)).rejects.toThrow()
})
