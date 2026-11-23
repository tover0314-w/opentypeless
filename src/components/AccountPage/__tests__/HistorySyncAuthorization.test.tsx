import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAuthStore, type AuthUser } from '../../../stores/authStore'
import {
  getHistorySyncConsentView,
  revokeHistorySyncConsentView,
  syncHistoryOnce,
  HistorySyncResultUnknownError,
  historySyncOnceBlocked,
  subscribeHistorySyncOnceResult,
  getHistorySyncAttempt,
  getHistorySyncConflictCount,
  listHistorySyncConflicts,
  previewHistorySyncRemoteRecord,
  previewHistorySyncLocalRecord,
  keepLocalHistorySyncConflict,
  keepRemoteHistorySyncConflict,
  acknowledgeHistorySyncAttempt,
  type HistorySyncOnceResult,
  type HistorySyncConsentView,
} from '../../../lib/historySyncConsent'
import { HistorySyncAuthorization } from '../HistorySyncAuthorization'
import { HistorySyncAuthorizationFlow } from '../HistorySyncAuthorizationFlow'
import { setAutomaticHistorySync } from '../../../lib/historySyncSchedule'

vi.mock('../../../lib/historySyncConsent', () => ({
  getHistorySyncConsentView: vi.fn(),
  revokeHistorySyncConsentView: vi.fn(),
  syncHistoryOnce: vi.fn(),
  HistorySyncResultUnknownError: class extends Error {},
  historySyncOnceBlocked: vi.fn(() => false),
  subscribeHistorySyncOnceResult: vi.fn(() => () => {}),
  getHistorySyncAttempt: vi.fn(),
  getHistorySyncConflictCount: vi.fn(),
  listHistorySyncConflicts: vi.fn(),
  previewHistorySyncRemoteRecord: vi.fn(),
  previewHistorySyncLocalRecord: vi.fn(),
  keepLocalHistorySyncConflict: vi.fn(),
  keepRemoteHistorySyncConflict: vi.fn(),
  acknowledgeHistorySyncAttempt: vi.fn(),
}))
vi.mock('../../../stores/authStore', () => ({
  useAuthStore: { getState: vi.fn() },
}))
vi.mock('../HistorySyncAuthorizationFlow', () => ({
  HistorySyncAuthorizationFlow: vi.fn(({ onAuthorizationChanged }) => (
    <button type="button" onClick={onAuthorizationChanged}>
      synthetic-enable-flow
    </button>
  )),
}))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const owner = { id: 'synthetic-user', email: 'user@example.invalid', name: 'Synthetic' } as AuthUser
const active = {
  sessionEpoch: '11111111-1111-4111-8111-111111111111',
  active: true,
  generation: '22222222-2222-4222-8222-222222222222',
}

beforeEach(() => {
  setAutomaticHistorySync(owner.id, false)
  vi.resetAllMocks()
  vi.mocked(subscribeHistorySyncOnceResult).mockReturnValue(() => {})
  vi.mocked(historySyncOnceBlocked).mockReturnValue(false)
  vi.mocked(getHistorySyncAttempt).mockResolvedValue(null)
  vi.mocked(getHistorySyncConflictCount).mockResolvedValue(0)
  vi.mocked(listHistorySyncConflicts).mockResolvedValue([])
  vi.mocked(acknowledgeHistorySyncAttempt).mockResolvedValue(undefined)
  vi.mocked(useAuthStore.getState).mockReturnValue({ user: owner } as ReturnType<
    typeof useAuthStore.getState
  >)
  vi.mocked(getHistorySyncConsentView).mockResolvedValue(active)
  vi.mocked(revokeHistorySyncConsentView).mockResolvedValue(undefined)
  vi.mocked(syncHistoryOnce).mockResolvedValue({
    pushed: 1,
    pulled: 2,
    hasMore: false,
    pending: 3,
  })
})

it('keeps automatic sync off until the user explicitly enables it', async () => {
  render(<HistorySyncAuthorization owner={owner} />)
  const automatic = await screen.findByRole('checkbox', {
    name: 'historySyncAuthorization.automatic',
  })
  expect(automatic).not.toBeChecked()
  fireEvent.click(automatic)
  expect(automatic).toBeChecked()
  expect(syncHistoryOnce).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.disable' }))
  await waitFor(() => expect(revokeHistorySyncConsentView).toHaveBeenCalledOnce())
  expect(automatic).not.toBeChecked()
})

it('shows confirmed conflicts without offering a blind sync retry', async () => {
  vi.mocked(getHistorySyncConflictCount).mockResolvedValue(2)
  render(<HistorySyncAuthorization owner={owner} />)
  await screen.findByText('historySyncAuthorization.conflictWarning')
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.syncNow' })).toBeDisabled()
  expect(
    screen.getByRole('checkbox', { name: 'historySyncAuthorization.automatic' }),
  ).toBeDisabled()
  expect(syncHistoryOnce).not.toHaveBeenCalled()
})

it('lists conflict identities only after the user opens the review', async () => {
  vi.mocked(getHistorySyncConflictCount).mockResolvedValue(1)
  vi.mocked(listHistorySyncConflicts).mockResolvedValue([
    {
      recordId: active.sessionEpoch,
      mutationId: active.generation,
      clientRevision: 1,
      serverRevision: 2,
      result: 'conflict',
    },
  ])
  render(<HistorySyncAuthorization owner={owner} />)
  const review = await screen.findByRole('button', {
    name: 'historySyncAuthorization.reviewConflicts',
  })
  expect(listHistorySyncConflicts).not.toHaveBeenCalled()
  fireEvent.click(review)
  expect(await screen.findByText(active.sessionEpoch)).toBeInTheDocument()
  expect(listHistorySyncConflicts).toHaveBeenCalledOnce()
  expect(syncHistoryOnce).not.toHaveBeenCalled()
})

it('loads the next conflict page only when requested and preserves the first page', async () => {
  const first = Array.from({ length: 200 }, (_, index) => ({
    recordId: `33333333-3333-4333-8333-${String(index + 1).padStart(12, '0')}`,
    mutationId: active.generation,
    clientRevision: 1,
    serverRevision: 2,
    result: 'conflict' as const,
  }))
  const last = { ...first[0], recordId: '33333333-3333-4333-8333-000000000201' }
  vi.mocked(getHistorySyncConflictCount).mockResolvedValue(201)
  vi.mocked(listHistorySyncConflicts).mockResolvedValueOnce(first).mockResolvedValueOnce([last])
  render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(
    await screen.findByRole('button', { name: 'historySyncAuthorization.reviewConflicts' }),
  )
  const more = await screen.findByRole('button', { name: 'history.loadMore' })
  expect(listHistorySyncConflicts).toHaveBeenCalledOnce()
  fireEvent.click(more)
  expect(await screen.findByText(last.recordId)).toBeInTheDocument()
  expect(screen.getByText(first[0].recordId)).toBeInTheDocument()
  expect(listHistorySyncConflicts).toHaveBeenLastCalledWith(active, first[199].recordId)
  expect(screen.queryByRole('button', { name: 'history.loadMore' })).toBeNull()
  expect(syncHistoryOnce).not.toHaveBeenCalled()
})

it('reads the remote version only after the user requests its preview', async () => {
  vi.mocked(getHistorySyncConflictCount).mockResolvedValue(1)
  const summary = {
    recordId: active.sessionEpoch,
    mutationId: active.generation,
    clientRevision: 1,
    serverRevision: 2,
    result: 'conflict' as const,
  }
  vi.mocked(listHistorySyncConflicts).mockResolvedValue([summary])
  vi.mocked(previewHistorySyncRemoteRecord).mockResolvedValue({
    recordId: summary.recordId,
    serverRevision: 2,
    deleted: false,
    payload: {
      rawText: 'Synthetic remote version',
      polishedText: '',
      mode: 'dictate',
      answerText: null,
      createdAt: '2026-11-22T10:00:00',
      durationMs: null,
      language: null,
    },
  })
  render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(
    await screen.findByRole('button', { name: 'historySyncAuthorization.reviewConflicts' }),
  )
  const preview = await screen.findByRole('button', {
    name: 'historySyncAuthorization.previewRemote',
  })
  expect(previewHistorySyncRemoteRecord).not.toHaveBeenCalled()
  fireEvent.click(preview)
  expect(await screen.findByText('Synthetic remote version')).toBeInTheDocument()
  expect(previewHistorySyncRemoteRecord).toHaveBeenCalledWith(active, summary)
  expect(syncHistoryOnce).not.toHaveBeenCalled()
})

it('shows the local conflict version only after a local preview request', async () => {
  vi.mocked(getHistorySyncConflictCount).mockResolvedValue(1)
  const summary = {
    recordId: active.sessionEpoch,
    mutationId: active.generation,
    clientRevision: 1,
    serverRevision: 2,
    result: 'conflict' as const,
  }
  vi.mocked(listHistorySyncConflicts).mockResolvedValue([summary])
  vi.mocked(previewHistorySyncLocalRecord).mockResolvedValue({
    recordId: summary.recordId,
    clientRevision: 1,
    deleted: false,
    payload: {
      rawText: 'Synthetic local conflict version',
      polishedText: '',
      mode: 'dictate',
      answerText: null,
      createdAt: '2026-11-22T10:00:00',
      durationMs: null,
      language: null,
    },
  })
  render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(
    await screen.findByRole('button', { name: 'historySyncAuthorization.reviewConflicts' }),
  )
  const preview = await screen.findByRole('button', {
    name: 'historySyncAuthorization.previewLocal',
  })
  expect(previewHistorySyncLocalRecord).not.toHaveBeenCalled()
  fireEvent.click(preview)
  expect(await screen.findByText('Synthetic local conflict version')).toBeInTheDocument()
  expect(previewHistorySyncLocalRecord).toHaveBeenCalledWith(active, summary)
  expect(previewHistorySyncRemoteRecord).not.toHaveBeenCalled()
  expect(syncHistoryOnce).not.toHaveBeenCalled()
})

it.each(['queued', 'unknown', 'remote'] as const)(
  'requires both previews and explicit confirmation, preserving the %s outcome',
  async (outcome) => {
    const summary = {
      recordId: active.sessionEpoch,
      mutationId: active.generation,
      clientRevision: 1,
      serverRevision: 2,
      result: 'conflict' as const,
    }
    const payload = {
      rawText: 'Synthetic reviewed text',
      polishedText: '',
      mode: 'dictate' as const,
      answerText: null,
      createdAt: '2026-11-22T10:00:00',
      durationMs: null,
      language: null,
    }
    vi.mocked(getHistorySyncConflictCount).mockResolvedValue(1)
    vi.mocked(listHistorySyncConflicts).mockResolvedValue([summary])
    vi.mocked(previewHistorySyncLocalRecord).mockResolvedValue({
      recordId: summary.recordId,
      clientRevision: 1,
      deleted: false,
      payload,
    })
    vi.mocked(previewHistorySyncRemoteRecord).mockResolvedValue({
      recordId: summary.recordId,
      serverRevision: 2,
      deleted: false,
      payload,
    })
    if (outcome === 'remote') {
      vi.mocked(keepRemoteHistorySyncConflict).mockResolvedValue(undefined)
    } else if (outcome === 'queued') {
      vi.mocked(keepLocalHistorySyncConflict).mockResolvedValue(undefined)
    } else {
      vi.mocked(keepLocalHistorySyncConflict).mockRejectedValue(
        new Error('Synthetic unconfirmed resolution'),
      )
    }
    render(<HistorySyncAuthorization owner={owner} />)
    fireEvent.click(
      await screen.findByRole('button', { name: 'historySyncAuthorization.reviewConflicts' }),
    )
    expect(screen.queryByRole('button', { name: 'historySyncAuthorization.keepLocal' })).toBeNull()
    fireEvent.click(
      await screen.findByRole('button', { name: 'historySyncAuthorization.previewLocal' }),
    )
    await waitFor(() => expect(previewHistorySyncLocalRecord).toHaveBeenCalledOnce())
    fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.previewRemote' }))
    if (outcome === 'remote') {
      fireEvent.click(
        await screen.findByRole('button', { name: 'historySyncAuthorization.keepRemote' }),
      )
      expect(keepRemoteHistorySyncConflict).not.toHaveBeenCalled()
      fireEvent.click(
        screen.getByRole('button', { name: 'historySyncAuthorization.confirmRemote' }),
      )
      await waitFor(() =>
        expect(keepRemoteHistorySyncConflict).toHaveBeenCalledExactlyOnceWith(active, summary, 2),
      )
      expect(await screen.findByText('historySyncAuthorization.remoteApplied')).toBeInTheDocument()
      expect(keepLocalHistorySyncConflict).not.toHaveBeenCalled()
      return
    }
    fireEvent.click(
      await screen.findByRole('button', { name: 'historySyncAuthorization.keepLocal' }),
    )
    expect(keepLocalHistorySyncConflict).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.previewRemote' }))
    await waitFor(() =>
      expect(
        screen.queryByRole('button', { name: 'historySyncAuthorization.confirmLocal' }),
      ).toBeNull(),
    )
    expect(keepLocalHistorySyncConflict).not.toHaveBeenCalled()
    fireEvent.click(
      await screen.findByRole('button', { name: 'historySyncAuthorization.keepLocal' }),
    )
    fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
    expect(keepLocalHistorySyncConflict).not.toHaveBeenCalled()
    fireEvent.click(
      screen.getByRole('button', { name: 'historySyncAuthorization.reviewConflicts' }),
    )
    await waitFor(() =>
      expect(
        screen.queryByRole('button', { name: 'historySyncAuthorization.keepLocal' }),
      ).toBeNull(),
    )
    fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.previewLocal' }))
    await waitFor(() => expect(previewHistorySyncLocalRecord).toHaveBeenCalledTimes(2))
    fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.previewRemote' }))
    await screen.findByRole('button', { name: 'historySyncAuthorization.keepLocal' })
    fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.keepLocal' }))
    const confirm = screen.getByRole('button', { name: 'historySyncAuthorization.confirmLocal' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    await waitFor(() =>
      expect(keepLocalHistorySyncConflict).toHaveBeenCalledExactlyOnceWith(active, summary, 2),
    )
    if (outcome === 'queued') {
      expect(
        await screen.findByText('historySyncAuthorization.resolutionQueued'),
      ).toBeInTheDocument()
    } else {
      expect(
        await screen.findByText('historySyncAuthorization.resolutionUnknown'),
      ).toBeInTheDocument()
      expect(screen.queryByText('historySyncAuthorization.resolutionQueued')).toBeNull()
      vi.mocked(getHistorySyncAttempt).mockRejectedValueOnce(new Error('Synthetic read failure'))
      fireEvent.click(
        screen.getByRole('button', { name: 'historySyncAuthorization.checkLastResult' }),
      )
      await screen.findByText('historySyncAuthorization.readError')
      expect(screen.getByText('historySyncAuthorization.resolutionUnknown')).toBeInTheDocument()
      expect(
        screen.getByRole('button', { name: 'historySyncAuthorization.syncNow' }),
      ).toBeDisabled()
    }
    expect(syncHistoryOnce).not.toHaveBeenCalled()
  },
)

it('discards remote preview text returned after authorization is revoked', async () => {
  const summary = {
    recordId: active.sessionEpoch,
    mutationId: active.generation,
    clientRevision: 1,
    serverRevision: 2,
    result: 'conflict' as const,
  }
  let finish:
    | ((record: Awaited<ReturnType<typeof previewHistorySyncRemoteRecord>>) => void)
    | undefined
  vi.mocked(getHistorySyncConflictCount).mockResolvedValue(1)
  vi.mocked(listHistorySyncConflicts).mockResolvedValue([summary])
  vi.mocked(previewHistorySyncRemoteRecord).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(
    await screen.findByRole('button', { name: 'historySyncAuthorization.reviewConflicts' }),
  )
  fireEvent.click(
    await screen.findByRole('button', { name: 'historySyncAuthorization.previewRemote' }),
  )
  await waitFor(() => expect(previewHistorySyncRemoteRecord).toHaveBeenCalledOnce())
  vi.mocked(getHistorySyncConsentView).mockResolvedValue({
    ...active,
    active: false,
    generation: null,
  })
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.disable' }))
  await waitFor(() =>
    expect(screen.queryByRole('button', { name: 'historySyncAuthorization.disable' })).toBeNull(),
  )
  finish?.({
    recordId: summary.recordId,
    serverRevision: 2,
    deleted: false,
    payload: {
      rawText: 'Synthetic late remote content',
      polishedText: '',
      mode: 'dictate',
      answerText: null,
      createdAt: '2026-11-22T10:00:00',
      durationMs: null,
      language: null,
    },
  })
  await new Promise((resolve) => setTimeout(resolve, 0))
  expect(screen.queryByText('Synthetic late remote content')).toBeNull()
  expect(syncHistoryOnce).not.toHaveBeenCalled()
})

it('discards a pending conflict list after authorization is revoked', async () => {
  let finish:
    | ((summaries: Awaited<ReturnType<typeof listHistorySyncConflicts>>) => void)
    | undefined
  vi.mocked(getHistorySyncConflictCount).mockResolvedValue(1)
  vi.mocked(listHistorySyncConflicts).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(
    await screen.findByRole('button', { name: 'historySyncAuthorization.reviewConflicts' }),
  )
  await waitFor(() => expect(listHistorySyncConflicts).toHaveBeenCalledOnce())
  vi.mocked(getHistorySyncConsentView).mockResolvedValue({
    ...active,
    active: false,
    generation: null,
  })
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.disable' }))
  await waitFor(() => expect(revokeHistorySyncConsentView).toHaveBeenCalledOnce())
  finish?.([
    {
      recordId: active.sessionEpoch,
      mutationId: active.generation,
      clientRevision: 1,
      serverRevision: 2,
      result: 'conflict',
    },
  ])
  await new Promise((resolve) => setTimeout(resolve, 0))
  expect(screen.queryByText(active.sessionEpoch)).toBeNull()
})

it('rechecks conflict state after a failed read without starting synchronization', async () => {
  vi.mocked(getHistorySyncConflictCount)
    .mockRejectedValueOnce(new Error('Synthetic conflict read failure'))
    .mockResolvedValue(0)
  render(<HistorySyncAuthorization owner={owner} />)
  await waitFor(() => expect(getHistorySyncConflictCount).toHaveBeenCalledOnce())
  const sync = screen.getByRole('button', { name: 'historySyncAuthorization.syncNow' })
  expect(sync).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.checkLastResult' }))
  await waitFor(() => expect(sync).toBeEnabled())
  expect(getHistorySyncConflictCount).toHaveBeenCalledTimes(2)
  expect(syncHistoryOnce).not.toHaveBeenCalled()
})

it('runs exactly one explicit sync and reports bounded progress without disabling authorization', async () => {
  render(<HistorySyncAuthorization owner={owner} />)
  const sync = await screen.findByRole('button', {
    name: 'historySyncAuthorization.syncNow',
  })
  const disable = screen.getByRole('button', { name: 'historySyncAuthorization.disable' })
  await waitFor(() => expect(sync).toBeEnabled())
  fireEvent.click(sync)
  await waitFor(() => expect(syncHistoryOnce).toHaveBeenCalledOnce())
  expect(syncHistoryOnce).toHaveBeenCalledTimes(1)
  expect(await screen.findByRole('status')).toHaveTextContent('historySyncAuthorization.syncResult')
  expect(disable).toBeEnabled()
  expect(revokeHistorySyncConsentView).not.toHaveBeenCalled()
})

it('blocks duplicate sync but permits authorization revocation during the attempt', async () => {
  let finish!: (value: {
    pushed: number
    pulled: number
    hasMore: boolean
    pending: number
  }) => void
  vi.mocked(syncHistoryOnce).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  render(<HistorySyncAuthorization owner={owner} />)
  const sync = await screen.findByRole('button', {
    name: 'historySyncAuthorization.syncNow',
  })
  const disable = screen.getByRole('button', { name: 'historySyncAuthorization.disable' })
  fireEvent.click(sync)
  await waitFor(() => expect(sync).toBeDisabled())
  expect(disable).toBeEnabled()
  fireEvent.click(sync)
  expect(syncHistoryOnce).toHaveBeenCalledTimes(1)
  expect(revokeHistorySyncConsentView).not.toHaveBeenCalled()
  finish({ pushed: 0, pulled: 0, hasMore: true, pending: 1 })
  expect(await screen.findByRole('status')).toHaveTextContent(
    'historySyncAuthorization.morePending',
  )
  await waitFor(() => expect(disable).toBeEnabled())
})

it('blocks retry for an unknown sync result while allowing authorization revocation', async () => {
  vi.mocked(syncHistoryOnce).mockRejectedValue(new HistorySyncResultUnknownError())
  render(<HistorySyncAuthorization owner={owner} />)
  const sync = await screen.findByRole('button', { name: 'historySyncAuthorization.syncNow' })
  await waitFor(() => expect(sync).toBeEnabled())
  fireEvent.click(sync)
  expect(await screen.findByRole('alert')).toHaveTextContent('historySyncAuthorization.syncUnknown')
  const retry = screen.getByRole('button', { name: 'historySyncAuthorization.syncNow' })
  expect(retry).toBeDisabled()
  fireEvent.click(retry)
  expect(syncHistoryOnce).toHaveBeenCalledTimes(1)
  const disable = screen.getByRole('button', { name: 'historySyncAuthorization.disable' })
  expect(disable).toBeEnabled()
  fireEvent.click(disable)
  await waitFor(() => expect(revokeHistorySyncConsentView).toHaveBeenCalledOnce())
})

it('allows turning off authorization before a pending sync resolves', async () => {
  let finish!: (value: {
    pushed: number
    pulled: number
    hasMore: boolean
    pending: number
  }) => void
  vi.mocked(syncHistoryOnce).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  vi.mocked(getHistorySyncConsentView)
    .mockResolvedValueOnce(active)
    .mockResolvedValueOnce({ ...active, active: false, generation: null })
  render(<HistorySyncAuthorization owner={owner} />)
  const sync = await screen.findByRole('button', { name: 'historySyncAuthorization.syncNow' })
  await waitFor(() => expect(sync).toBeEnabled())
  fireEvent.click(sync)
  const disable = screen.getByRole('button', { name: 'historySyncAuthorization.disable' })
  expect(disable).toBeEnabled()
  fireEvent.click(disable)
  await waitFor(() => expect(revokeHistorySyncConsentView).toHaveBeenCalledOnce())
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'synthetic-enable-flow' })).toBeInTheDocument(),
  )
  finish({ pushed: 1, pulled: 0, hasMore: false, pending: 0 })
  await waitFor(() =>
    expect(screen.queryByRole('button', { name: 'historySyncAuthorization.syncNow' })).toBeNull(),
  )
})

it('keeps authorization visible and shows a retryable error after a failed sync', async () => {
  vi.mocked(syncHistoryOnce).mockRejectedValue(new Error('Synthetic failure'))
  render(<HistorySyncAuthorization owner={owner} />)
  const sync = await screen.findByRole('button', { name: 'historySyncAuthorization.syncNow' })
  await waitFor(() => expect(sync).toBeEnabled())
  fireEvent.click(sync)
  expect(await screen.findByRole('alert')).toHaveTextContent('historySyncAuthorization.syncError')
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.disable' })).toBeEnabled()
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.syncNow' })).toBeEnabled()
})
afterEach(cleanup)

it('does not reblock synchronization when rereading an acknowledged unknown result', async () => {
  vi.mocked(getHistorySyncAttempt).mockResolvedValue({
    requestId: active.sessionEpoch,
    status: 'unknown',
    result: null,
    acknowledged: true,
  })
  render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(
    await screen.findByRole('button', {
      name: 'historySyncAuthorization.checkLastResult',
    }),
  )
  await waitFor(() => expect(getHistorySyncAttempt).toHaveBeenCalledOnce())
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.syncNow' })).toBeEnabled()
  expect(
    screen.queryByRole('button', { name: 'historySyncAuthorization.reviewUnknown' }),
  ).toBeNull()
  expect(screen.queryByRole('status')).toBeNull()
})

it('keeps the reviewed request fixed while acknowledgement is pending', async () => {
  const attempt = { requestId: active.sessionEpoch, status: 'unknown' as const, result: null }
  let finish!: () => void
  vi.mocked(getHistorySyncAttempt).mockResolvedValue(attempt)
  vi.mocked(acknowledgeHistorySyncAttempt).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  render(<HistorySyncAuthorization owner={owner} />)
  const check = await screen.findByRole('button', {
    name: 'historySyncAuthorization.checkLastResult',
  })
  fireEvent.click(check)
  fireEvent.click(
    await screen.findByRole('button', {
      name: 'historySyncAuthorization.reviewUnknown',
    }),
  )
  fireEvent.click(screen.getByRole('checkbox', { name: 'historySyncAuthorization.ackAccept' }))
  const confirm = screen.getByRole('button', {
    name: 'historySyncAuthorization.acknowledge',
  })
  fireEvent.click(confirm)
  await waitFor(() => expect(acknowledgeHistorySyncAttempt).toHaveBeenCalledOnce())
  expect(check).toBeDisabled()
  fireEvent.click(check)
  fireEvent.click(confirm)
  expect(getHistorySyncAttempt).toHaveBeenCalledTimes(1)
  expect(acknowledgeHistorySyncAttempt).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('button', { name: 'common.cancel' })).toBeDisabled()
  finish()
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(syncHistoryOnce).not.toHaveBeenCalled()
})

it('keeps retry blocked when risk acknowledgement fails', async () => {
  vi.mocked(getHistorySyncAttempt).mockResolvedValue({
    requestId: active.sessionEpoch,
    status: 'unknown',
    result: null,
  })
  vi.mocked(acknowledgeHistorySyncAttempt).mockRejectedValue(new Error('Synthetic failure'))
  render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(
    await screen.findByRole('button', { name: 'historySyncAuthorization.checkLastResult' }),
  )
  fireEvent.click(
    await screen.findByRole('button', { name: 'historySyncAuthorization.reviewUnknown' }),
  )
  fireEvent.click(screen.getByRole('checkbox', { name: 'historySyncAuthorization.ackAccept' }))
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.acknowledge' }))
  await screen.findByText('historySyncAuthorization.readError')
  expect(screen.getByRole('dialog')).toBeInTheDocument()
  expect(syncHistoryOnce).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.syncNow' })).toBeDisabled()
})

it('closes the previous owner risk confirmation when the owner changes', async () => {
  vi.mocked(getHistorySyncAttempt).mockResolvedValue({
    requestId: active.sessionEpoch,
    status: 'unknown',
    result: null,
  })
  const { rerender } = render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(
    await screen.findByRole('button', { name: 'historySyncAuthorization.checkLastResult' }),
  )
  fireEvent.click(
    await screen.findByRole('button', { name: 'historySyncAuthorization.reviewUnknown' }),
  )
  const replacement = { ...owner }
  vi.mocked(useAuthStore.getState).mockReturnValue({ user: replacement } as ReturnType<
    typeof useAuthStore.getState
  >)
  rerender(<HistorySyncAuthorization owner={replacement} />)
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(acknowledgeHistorySyncAttempt).not.toHaveBeenCalled()
})

it('requires an unchecked risk confirmation before acknowledging an unresolved request', async () => {
  const attempt = { requestId: active.sessionEpoch, status: 'unknown' as const, result: null }
  vi.mocked(getHistorySyncAttempt).mockResolvedValue(attempt)
  render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(
    await screen.findByRole('button', {
      name: 'historySyncAuthorization.checkLastResult',
    }),
  )
  fireEvent.click(
    await screen.findByRole('button', {
      name: 'historySyncAuthorization.reviewUnknown',
    }),
  )
  expect(screen.getByRole('dialog')).toBeInTheDocument()
  expect(
    screen.getByRole('checkbox', { name: 'historySyncAuthorization.ackAccept' }),
  ).not.toBeChecked()
  const confirm = screen.getByRole('button', { name: 'historySyncAuthorization.acknowledge' })
  expect(confirm).toBeDisabled()
  expect(screen.getByRole('button', { name: 'common.cancel' })).toHaveFocus()
  fireEvent.click(screen.getByRole('checkbox', { name: 'historySyncAuthorization.ackAccept' }))
  fireEvent.click(confirm)
  await waitFor(() =>
    expect(acknowledgeHistorySyncAttempt).toHaveBeenCalledExactlyOnceWith(active, attempt),
  )
  expect(syncHistoryOnce).not.toHaveBeenCalled()
  expect(screen.queryByRole('status')).toBeNull()
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
})

it('checks a persisted result without initiating a new sync', async () => {
  vi.mocked(getHistorySyncAttempt).mockResolvedValue({
    requestId: active.sessionEpoch,
    status: 'confirmed',
    result: { pushed: 1, pulled: 2, hasMore: false, pending: 0 },
  })
  render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(
    await screen.findByRole('button', {
      name: 'historySyncAuthorization.checkLastResult',
    }),
  )
  expect(await screen.findByRole('status')).toHaveTextContent('historySyncAuthorization.syncResult')
  expect(getHistorySyncAttempt).toHaveBeenCalledOnce()
  expect(syncHistoryOnce).not.toHaveBeenCalled()
})

it('discards an old-owner result read and resets the read gate for the replacement owner', async () => {
  let finish!: (value: null) => void
  vi.mocked(getHistorySyncAttempt).mockReturnValue(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const { rerender } = render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(
    await screen.findByRole('button', { name: 'historySyncAuthorization.checkLastResult' }),
  )
  const replacement = { ...owner }
  vi.mocked(useAuthStore.getState).mockReturnValue({ user: replacement } as ReturnType<
    typeof useAuthStore.getState
  >)
  rerender(<HistorySyncAuthorization owner={replacement} />)
  const check = await screen.findByRole('button', {
    name: 'historySyncAuthorization.checkLastResult',
  })
  expect(check).toBeEnabled()
  finish(null)
  await waitFor(() => expect(check).toBeEnabled())
  expect(screen.queryByRole('status')).toBeNull()
})

it('keeps synchronization blocked after reading an interrupted persisted attempt', async () => {
  vi.mocked(getHistorySyncAttempt).mockResolvedValue({
    requestId: active.sessionEpoch,
    status: 'running',
    result: null,
  })
  render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(
    await screen.findByRole('button', {
      name: 'historySyncAuthorization.checkLastResult',
    }),
  )
  expect(await screen.findByRole('alert')).toHaveTextContent('historySyncAuthorization.syncUnknown')
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.syncNow' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.disable' })).toBeEnabled()
  expect(syncHistoryOnce).not.toHaveBeenCalled()
})
it.each(['confirmed', 'unknown'])(
  'does not retain %s sync feedback when the owner object changes',
  async (outcome) => {
    if (outcome === 'unknown') {
      vi.mocked(syncHistoryOnce).mockRejectedValue(new HistorySyncResultUnknownError())
    }
    const { rerender } = render(<HistorySyncAuthorization owner={owner} />)
    const sync = await screen.findByRole('button', { name: 'historySyncAuthorization.syncNow' })
    await waitFor(() => expect(sync).toBeEnabled())
    fireEvent.click(sync)
    await screen.findByRole(outcome === 'confirmed' ? 'status' : 'alert')
    const replacement = { ...owner }
    vi.mocked(useAuthStore.getState).mockReturnValue({ user: replacement } as ReturnType<
      typeof useAuthStore.getState
    >)
    rerender(<HistorySyncAuthorization owner={replacement} />)
    await screen.findByRole('button', { name: 'historySyncAuthorization.syncNow' })
    expect(screen.queryByRole('status')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'historySyncAuthorization.syncNow' }),
      ).toBeEnabled(),
    )
  },
)

it('replaces an unknown status with a validated matching late result', async () => {
  vi.mocked(historySyncOnceBlocked).mockReturnValue(true)
  let receive!: (result: HistorySyncOnceResult) => void
  vi.mocked(subscribeHistorySyncOnceResult).mockImplementation((_view, listener) => {
    receive = listener
    return () => {}
  })
  render(<HistorySyncAuthorization owner={owner} />)
  await screen.findByRole('button', { name: 'historySyncAuthorization.syncNow' })
  expect(screen.getByRole('alert')).toHaveTextContent('historySyncAuthorization.syncUnknown')
  await waitFor(() => expect(subscribeHistorySyncOnceResult).toHaveBeenCalled())
  receive({ pushed: 1, pulled: 2, hasMore: false, pending: 0 })
  await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
  expect(screen.getByRole('status')).toHaveTextContent('historySyncAuthorization.syncResult')
})

it.each([true, false])(
  'clears a previous read error after retry succeeds with active=%s',
  async (authorized) => {
    vi.mocked(getHistorySyncConsentView)
      .mockRejectedValueOnce(new Error('Synthetic read failure'))
      .mockResolvedValueOnce({
        ...active,
        active: authorized,
        generation: authorized ? active.generation : null,
      })
    render(<HistorySyncAuthorization owner={owner} />)
    fireEvent.click(await screen.findByRole('button', { name: 'common.retry' }))
    await waitFor(() => expect(getHistorySyncConsentView).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByText('historySyncAuthorization.error')).toBeNull())
    expect(
      screen.getByRole('button', {
        name: authorized ? 'historySyncAuthorization.disable' : 'synthetic-enable-flow',
      }),
    ).toBeEnabled()
  },
)

it('keeps a shared unknown attempt blocked after the account page is remounted', async () => {
  vi.mocked(historySyncOnceBlocked).mockReturnValue(true)
  render(<HistorySyncAuthorization owner={owner} />)
  const sync = await screen.findByRole('button', { name: 'historySyncAuthorization.syncNow' })
  expect(sync).toBeDisabled()
  expect(screen.getByRole('alert')).toHaveTextContent('historySyncAuthorization.syncUnknown')
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.disable' })).toBeEnabled()
  fireEvent.click(sync)
  expect(syncHistoryOnce).not.toHaveBeenCalled()
})

it('shows only existing authorization and revokes the displayed generation', async () => {
  vi.mocked(getHistorySyncConsentView)
    .mockResolvedValueOnce(active)
    .mockResolvedValueOnce({ ...active, active: false, generation: null })
  render(<HistorySyncAuthorization owner={owner} />)
  const button = await screen.findByRole('button', { name: 'historySyncAuthorization.disable' })
  expect(screen.getByText('historySyncAuthorization.warning')).toBeInTheDocument()
  fireEvent.click(button)
  await waitFor(() => expect(revokeHistorySyncConsentView).toHaveBeenCalledExactlyOnceWith(active))
  await waitFor(() =>
    expect(screen.queryByText('historySyncAuthorization.title')).not.toBeInTheDocument(),
  )
  expect(getHistorySyncConsentView).toHaveBeenCalledTimes(2)
})

it('keeps a newly granted generation visible after revoking the old one', async () => {
  const newer = { ...active, generation: '33333333-3333-4333-8333-333333333333' }
  vi.mocked(getHistorySyncConsentView).mockResolvedValueOnce(active).mockResolvedValue(newer)
  render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(await screen.findByRole('button', { name: 'historySyncAuthorization.disable' }))
  await waitFor(() => expect(getHistorySyncConsentView).toHaveBeenCalledTimes(2))
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'historySyncAuthorization.disable' })).toBeEnabled(),
  )
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.disable' }))
  await waitFor(() => expect(revokeHistorySyncConsentView).toHaveBeenLastCalledWith(newer))
})

it('keeps the action mounted and disabled while reading back authorization', async () => {
  let finish!: (value: HistorySyncConsentView) => void
  vi.mocked(getHistorySyncConsentView)
    .mockResolvedValueOnce(active)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
  render(<HistorySyncAuthorization owner={owner} />)
  const button = await screen.findByRole('button', { name: 'historySyncAuthorization.disable' })
  button.focus()
  fireEvent.click(button)
  await waitFor(() => expect(getHistorySyncConsentView).toHaveBeenCalledTimes(2))
  expect(button).toBeInTheDocument()
  expect(button).toBeDisabled()
  expect(button).toHaveTextContent('common.loading')
  fireEvent.click(button)
  expect(revokeHistorySyncConsentView).toHaveBeenCalledTimes(1)
  finish({ ...active, generation: '33333333-3333-4333-8333-333333333333' })
  await waitFor(() => expect(button).toBeEnabled())
  expect(button).toHaveTextContent('historySyncAuthorization.disable')
})

it('shows unknown status and retries reading instead of revoking an old snapshot after readback fails', async () => {
  vi.mocked(getHistorySyncConsentView)
    .mockResolvedValueOnce(active)
    .mockRejectedValueOnce(new Error('Synthetic readback failure'))
    .mockResolvedValueOnce({ ...active, active: false, generation: null })
  render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(await screen.findByRole('button', { name: 'historySyncAuthorization.disable' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('historySyncAuthorization.error')
  expect(screen.queryByRole('button', { name: 'historySyncAuthorization.disable' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'common.retry' }))
  await waitFor(() => expect(getHistorySyncConsentView).toHaveBeenCalledTimes(3))
  expect(revokeHistorySyncConsentView).toHaveBeenCalledTimes(1)
})

it('shows the explicit enable flow when no authorization exists', async () => {
  vi.mocked(getHistorySyncConsentView).mockResolvedValue({
    ...active,
    active: false,
    generation: null,
  })
  const { container } = render(<HistorySyncAuthorization owner={owner} />)
  await waitFor(() => expect(getHistorySyncConsentView).toHaveBeenCalledOnce())
  expect(screen.getByRole('button', { name: 'synthetic-enable-flow' })).toBeInTheDocument()
  expect(container).not.toBeEmptyDOMElement()
  expect(revokeHistorySyncConsentView).not.toHaveBeenCalled()
  expect(screen.queryByText('historySyncAuthorization.warning')).toBeNull()
  expect(HistorySyncAuthorizationFlow).toHaveBeenCalledWith(
    expect.objectContaining({ owner, onAuthorizationChanged: expect.any(Function) }),
    undefined,
  )
})

it('never mounts the enable flow before the native inactive state is known', async () => {
  let finish!: (value: HistorySyncConsentView) => void
  vi.mocked(getHistorySyncConsentView).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const { container } = render(<HistorySyncAuthorization owner={owner} />)
  expect(container).toBeEmptyDOMElement()
  expect(HistorySyncAuthorizationFlow).not.toHaveBeenCalled()
  finish({ ...active, active: false, generation: null })
  expect(await screen.findByRole('button', { name: 'synthetic-enable-flow' })).toBeEnabled()
})

it('refreshes to the active-only disable control after successful authorization', async () => {
  vi.mocked(getHistorySyncConsentView)
    .mockResolvedValueOnce({ ...active, active: false, generation: null })
    .mockResolvedValueOnce(active)
  render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(await screen.findByRole('button', { name: 'synthetic-enable-flow' }))
  await waitFor(() => expect(getHistorySyncConsentView).toHaveBeenCalledTimes(2))
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.disable' })).toBeEnabled()
  expect(screen.queryByRole('button', { name: 'synthetic-enable-flow' })).toBeNull()
})

it('keeps retry mounted and blocks duplicate reads until the retry finishes', async () => {
  let finish!: (value: typeof active) => void
  vi.mocked(getHistorySyncConsentView)
    .mockRejectedValueOnce(new Error('Synthetic read failure'))
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
  render(<HistorySyncAuthorization owner={owner} />)
  const button = await screen.findByRole('button', { name: 'common.retry' })
  fireEvent.click(button)
  await waitFor(() => expect(getHistorySyncConsentView).toHaveBeenCalledTimes(2))
  expect(button).toBeInTheDocument()
  expect(button).toBeDisabled()
  expect(button).toHaveTextContent('common.loading')
  fireEvent.click(button)
  expect(getHistorySyncConsentView).toHaveBeenCalledTimes(2)
  finish(active)
  await waitFor(() => expect(button).toBeEnabled())
  expect(button).toHaveTextContent('historySyncAuthorization.disable')
})

it('keeps failure visible and lets the user retry revocation', async () => {
  vi.mocked(revokeHistorySyncConsentView).mockRejectedValueOnce(new Error('Synthetic failure'))
  render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(await screen.findByRole('button', { name: 'historySyncAuthorization.disable' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('historySyncAuthorization.error')
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.disable' }))
  await waitFor(() => expect(revokeHistorySyncConsentView).toHaveBeenCalledTimes(2))
})

it('refreshes a rejected old generation before allowing another revocation', async () => {
  const current = { ...active, generation: '33333333-3333-4333-8333-333333333333' }
  vi.mocked(getHistorySyncConsentView).mockResolvedValueOnce(active).mockResolvedValue(current)
  vi.mocked(revokeHistorySyncConsentView).mockRejectedValueOnce(new Error('Superseded generation'))
  render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(await screen.findByRole('button', { name: 'historySyncAuthorization.disable' }))
  await waitFor(() => expect(getHistorySyncConsentView).toHaveBeenCalledTimes(2))
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'historySyncAuthorization.disable' })).toBeEnabled(),
  )
  expect(screen.getByRole('alert')).toHaveTextContent('historySyncAuthorization.error')
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.disable' }))
  await waitFor(() => expect(revokeHistorySyncConsentView).toHaveBeenLastCalledWith(current))
})

it('does not display a late old-account result or let an old owner revoke', async () => {
  let finish!: (value: typeof active) => void
  vi.mocked(getHistorySyncConsentView).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const { container } = render(<HistorySyncAuthorization owner={owner} />)
  vi.mocked(useAuthStore.getState).mockReturnValue({
    user: { ...owner, id: 'new-user' },
  } as ReturnType<typeof useAuthStore.getState>)
  finish(active)
  await waitFor(() => expect(getHistorySyncConsentView).toHaveBeenCalledOnce())
  expect(container).toBeEmptyDOMElement()
  expect(revokeHistorySyncConsentView).not.toHaveBeenCalled()
})

it('isolates pending revocations when the same account receives a new owner object', async () => {
  let finishOld!: () => void
  let finishNew!: () => void
  vi.mocked(revokeHistorySyncConsentView)
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishOld = resolve
        }),
    )
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishNew = resolve
        }),
    )
  const { rerender } = render(<HistorySyncAuthorization owner={owner} />)
  fireEvent.click(await screen.findByRole('button', { name: 'historySyncAuthorization.disable' }))
  const refreshedOwner = { ...owner }
  vi.mocked(useAuthStore.getState).mockReturnValue({
    user: refreshedOwner,
  } as ReturnType<typeof useAuthStore.getState>)
  rerender(<HistorySyncAuthorization owner={refreshedOwner} />)
  const currentButton = await screen.findByRole('button', {
    name: 'historySyncAuthorization.disable',
  })
  await waitFor(() => expect(currentButton).toBeEnabled())
  fireEvent.click(currentButton)
  await waitFor(() => expect(revokeHistorySyncConsentView).toHaveBeenCalledTimes(2))
  finishOld()
  await waitFor(() => expect(currentButton).toBeDisabled())
  fireEvent.click(currentButton)
  expect(revokeHistorySyncConsentView).toHaveBeenCalledTimes(2)
  finishNew()
  await waitFor(() => expect(currentButton).toBeEnabled())
})
