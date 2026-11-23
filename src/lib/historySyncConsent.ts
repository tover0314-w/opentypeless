import { invoke } from '@tauri-apps/api/core'
import { captureCloudSessionOwner } from './cloud-session'
import { parseHistorySyncPage, type HistorySyncTextPayload } from './historySyncPage'

export class HistorySyncCancellationUnknownError extends Error {
  constructor() {
    super('History sync proposal cancellation result unknown')
    this.name = 'HistorySyncCancellationUnknownError'
  }
}

export class HistorySyncConfirmationUnknownError extends Error {
  constructor(message = 'History sync proposal confirmation result unknown') {
    super(message)
    this.name = 'HistorySyncConfirmationUnknownError'
  }
}

async function discardRejectedProposal(requestId: string): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const cancellation = invoke('cancel_history_sync_proposal', { requestId }).catch(() => undefined)
  try {
    await Promise.race([
      cancellation,
      new Promise<void>((resolve) => {
        timer = setTimeout(resolve, 1000)
      }),
    ])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
}

export interface HistorySyncProposal {
  requestId: string
  description: {
    accountId: string
    serviceUrl: string
    sessionEpoch: string
  }
}

export async function confirmAndReadHistorySyncConsent(
  proposal: HistorySyncProposal,
): Promise<HistorySyncConsentView> {
  const validated = parseHistorySyncProposal(proposal, proposal?.description?.sessionEpoch)
  const isCurrent = captureCloudSessionOwner()
  await confirmHistorySyncProposal(validated.requestId, validated.description.sessionEpoch)
  if (!isCurrent()) throw new Error('History sync proposal confirmation superseded')
  let view: HistorySyncConsentView
  try {
    view = await getHistorySyncConsentView()
  } catch {
    if (!isCurrent()) throw new Error('History sync proposal confirmation superseded')
    throw new HistorySyncConfirmationUnknownError()
  }
  if (!isCurrent()) throw new Error('History sync proposal confirmation superseded')
  if (!view.active || view.sessionEpoch !== validated.description.sessionEpoch) {
    throw new HistorySyncConfirmationUnknownError()
  }
  return view
}

export async function prepareHistorySyncProposal(
  expectedSessionEpoch: string,
  signal?: AbortSignal,
): Promise<HistorySyncProposal> {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  if (typeof expectedSessionEpoch !== 'string' || !uuid.test(expectedSessionEpoch)) {
    throw new Error('Invalid history sync session epoch')
  }
  const isCurrent = captureCloudSessionOwner()
  if (!isCurrent()) throw new Error('History sync proposal preparation superseded')
  signal?.throwIfAborted()
  const preparation = invoke<unknown>('prepare_history_sync_proposal', { expectedSessionEpoch })
  let value: unknown
  if (signal) {
    let abort: () => void = () => {}
    const cancelled = new Promise<never>((_resolve, reject) => {
      abort = () => reject(signal.reason ?? new DOMException('Preparation cancelled', 'AbortError'))
      signal.addEventListener('abort', abort, { once: true })
      if (signal.aborted) abort()
    })
    try {
      value = await Promise.race([preparation, cancelled])
    } catch (error) {
      if (signal.aborted) {
        void preparation
          .then(async (late) => {
            if (late && typeof late === 'object' && !Array.isArray(late)) {
              const requestId = (late as Record<string, unknown>).requestId
              if (typeof requestId === 'string' && uuid.test(requestId)) {
                await discardRejectedProposal(requestId)
              }
            }
          })
          .catch(() => undefined)
      }
      throw error
    } finally {
      signal.removeEventListener('abort', abort)
    }
    if (signal.aborted) {
      if (value && typeof value === 'object' && !Array.isArray(value)) {
        const requestId = (value as Record<string, unknown>).requestId
        if (typeof requestId === 'string' && uuid.test(requestId)) {
          await discardRejectedProposal(requestId)
        }
      }
      signal.throwIfAborted()
    }
  } else {
    value = await preparation
  }
  if (!isCurrent()) {
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      const requestId = (value as Record<string, unknown>).requestId
      if (typeof requestId === 'string' && uuid.test(requestId)) {
        await discardRejectedProposal(requestId)
      }
    }
    throw new Error('History sync proposal preparation superseded')
  }
  try {
    return parseHistorySyncProposal(value, expectedSessionEpoch)
  } catch (error) {
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      const requestId = (value as Record<string, unknown>).requestId
      if (typeof requestId === 'string' && uuid.test(requestId)) {
        await discardRejectedProposal(requestId)
      }
    }
    throw error
  }
}

function parseHistorySyncProposal(
  value: unknown,
  expectedSessionEpoch: string,
): HistorySyncProposal {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  const invalid = () => new Error('Invalid history sync proposal response')
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid()
  const proposal = value as Record<string, unknown>
  if (
    Object.keys(proposal).length !== 2 ||
    typeof proposal.requestId !== 'string' ||
    !uuid.test(proposal.requestId) ||
    !proposal.description ||
    typeof proposal.description !== 'object' ||
    Array.isArray(proposal.description)
  ) {
    throw invalid()
  }
  const description = proposal.description as Record<string, unknown>
  if (
    Object.keys(description).length !== 3 ||
    typeof description.accountId !== 'string' ||
    !description.accountId ||
    description.accountId.trim() !== description.accountId ||
    new TextEncoder().encode(description.accountId).length > 200 ||
    Array.from(description.accountId).some((character) => {
      const code = character.codePointAt(0)!
      return code < 32 || (code >= 127 && code <= 159)
    }) ||
    typeof description.sessionEpoch !== 'string' ||
    !uuid.test(description.sessionEpoch) ||
    description.sessionEpoch !== expectedSessionEpoch ||
    typeof description.serviceUrl !== 'string' ||
    Array.from(description.serviceUrl).some(
      (character) => character <= ' ' || character === '\u007f' || character === '\\',
    )
  ) {
    throw invalid()
  }
  let service: URL
  try {
    service = new URL(description.serviceUrl)
  } catch {
    throw invalid()
  }
  if (
    service.protocol !== 'https:' ||
    !service.hostname ||
    service.username ||
    service.password ||
    service.search ||
    service.hash ||
    description.serviceUrl.includes('?') ||
    description.serviceUrl.includes('#') ||
    service.pathname !== '/api/history-sync/status'
  ) {
    throw invalid()
  }
  return {
    requestId: proposal.requestId,
    description: {
      accountId: description.accountId,
      serviceUrl: description.serviceUrl,
      sessionEpoch: expectedSessionEpoch,
    },
  }
}

export async function confirmHistorySyncProposal(
  requestId: string,
  expectedSessionEpoch: string,
): Promise<void> {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  if (
    typeof requestId !== 'string' ||
    !uuid.test(requestId) ||
    typeof expectedSessionEpoch !== 'string' ||
    !uuid.test(expectedSessionEpoch)
  ) {
    throw new Error('Invalid history sync proposal confirmation identity')
  }
  const isCurrent = captureCloudSessionOwner()
  if (!isCurrent()) throw new Error('History sync proposal confirmation superseded')
  let timer: ReturnType<typeof setTimeout> | undefined
  let response: unknown
  try {
    response = await Promise.race([
      invoke<unknown>('confirm_history_sync_proposal', {
        requestId,
        expectedSessionEpoch,
      }),
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => reject(new HistorySyncConfirmationUnknownError()), 5000)
      }),
    ])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
  if (!isCurrent()) throw new Error('History sync proposal confirmation superseded')
  if (response !== null) {
    throw new HistorySyncConfirmationUnknownError(
      'Invalid history sync proposal confirmation response',
    )
  }
}

export async function cancelHistorySyncProposal(requestId: string): Promise<boolean> {
  if (
    typeof requestId !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(requestId)
  ) {
    throw new Error('Invalid history sync proposal identity')
  }
  const isCurrent = captureCloudSessionOwner()
  if (!isCurrent()) throw new Error('History sync proposal cancellation superseded')
  let timer: ReturnType<typeof setTimeout> | undefined
  let cancelled: unknown
  try {
    cancelled = await Promise.race([
      invoke<unknown>('cancel_history_sync_proposal', { requestId }),
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => reject(new HistorySyncCancellationUnknownError()), 5000)
      }),
    ])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
  if (!isCurrent()) throw new Error('History sync proposal cancellation superseded')
  if (typeof cancelled !== 'boolean') {
    throw new Error('Invalid history sync proposal cancellation response')
  }
  return cancelled
}

export interface HistorySyncConsentView {
  sessionEpoch: string
  active: boolean
  generation: string | null
}

export interface HistorySyncOnceResult {
  pushed: number
  pulled: number
  hasMore: boolean
  pending: number
}

export class HistorySyncResultUnknownError extends Error {
  constructor(message = 'History sync result unknown') {
    super(message)
    this.name = 'HistorySyncResultUnknownError'
  }
}

let syncOnceAttempt: { identity: symbol; current: () => boolean } | null = null
let resolutionAttempt: { identity: symbol; current: () => boolean } | null = null
const syncResultListeners = new Set<{
  epoch: string
  generation: string | null
  current: () => boolean
  receive: (result: HistorySyncOnceResult) => void
}>()

export function subscribeHistorySyncOnceResult(
  view: HistorySyncConsentView,
  receive: (result: HistorySyncOnceResult) => void,
): () => void {
  const listener = {
    epoch: view.sessionEpoch,
    generation: view.generation,
    current: captureCloudSessionOwner(),
    receive,
  }
  syncResultListeners.add(listener)
  return () => {
    syncResultListeners.delete(listener)
  }
}

export function historySyncOnceBlocked(): boolean {
  if (syncOnceAttempt && !syncOnceAttempt.current()) syncOnceAttempt = null
  if (resolutionAttempt && !resolutionAttempt.current()) resolutionAttempt = null
  return syncOnceAttempt !== null || resolutionAttempt !== null
}

export async function syncHistoryOnce(
  view: HistorySyncConsentView,
): Promise<HistorySyncOnceResult> {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  if (
    !view ||
    view.active !== true ||
    typeof view.sessionEpoch !== 'string' ||
    !uuid.test(view.sessionEpoch) ||
    typeof view.generation !== 'string' ||
    !uuid.test(view.generation)
  )
    throw new Error('Invalid history sync authorization view')
  if (historySyncOnceBlocked()) throw new HistorySyncResultUnknownError()
  const isCurrent = captureCloudSessionOwner()
  if (!isCurrent()) throw new Error('History sync request superseded')
  const identity = Symbol()
  syncOnceAttempt = { identity, current: isCurrent }
  let timer: ReturnType<typeof setTimeout> | undefined
  const operation = invoke<unknown>('sync_history_once', {
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
  }).then((response) => {
    if (!isCurrent()) throw new Error('History sync request superseded')
    const result = validateHistorySyncOnceResult(response)
    if (syncOnceAttempt?.identity === identity) {
      syncOnceAttempt = null
      for (const listener of syncResultListeners) {
        if (!listener.current()) {
          syncResultListeners.delete(listener)
          continue
        }
        if (listener.epoch === view.sessionEpoch && listener.generation === view.generation) {
          try {
            listener.receive(result)
          } catch {
            continue
          }
        }
      }
    }
    return result
  })
  try {
    return await Promise.race([
      operation,
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => reject(new HistorySyncResultUnknownError()), 65000)
      }),
    ])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
}

function validateHistorySyncOnceResult(response: unknown): HistorySyncOnceResult {
  if (!response || typeof response !== 'object' || Array.isArray(response)) {
    throw new HistorySyncResultUnknownError('Invalid history sync response')
  }
  const result = response as Record<string, unknown>
  const keys = ['pushed', 'pulled', 'hasMore', 'pending']
  if (
    Object.keys(result).length !== keys.length ||
    keys.some((key) => !(key in result)) ||
    typeof result.pushed !== 'number' ||
    !Number.isSafeInteger(result.pushed) ||
    result.pushed < 0 ||
    result.pushed > 200 ||
    typeof result.pulled !== 'number' ||
    !Number.isSafeInteger(result.pulled) ||
    result.pulled < 0 ||
    result.pulled > 500 ||
    typeof result.pending !== 'number' ||
    !Number.isSafeInteger(result.pending) ||
    result.pending < 0 ||
    result.pending > 5000 ||
    typeof result.hasMore !== 'boolean'
  ) {
    throw new HistorySyncResultUnknownError('Invalid history sync response')
  }
  return result as unknown as HistorySyncOnceResult
}

export interface HistorySyncAttempt {
  requestId: string
  status: 'running' | 'confirmed' | 'unknown'
  result: HistorySyncOnceResult | null
  acknowledged?: boolean
}

export async function getHistorySyncConflictCount(view: HistorySyncConsentView): Promise<number> {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  if (
    !view?.active ||
    !uuid.test(view.sessionEpoch) ||
    typeof view.generation !== 'string' ||
    !uuid.test(view.generation)
  )
    throw new Error('Invalid history sync authorization view')
  const isCurrent = captureCloudSessionOwner()
  if (!isCurrent()) throw new Error('History sync conflict read superseded')
  const count = await invoke<unknown>('get_history_sync_conflict_count', {
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
  })
  if (!isCurrent()) throw new Error('History sync conflict read superseded')
  if (typeof count !== 'number' || !Number.isSafeInteger(count) || count < 0)
    throw new Error('Invalid history sync conflict count')
  return count
}

export interface HistorySyncConflictSummary {
  recordId: string
  mutationId: string
  clientRevision: number
  serverRevision: number
  result: 'conflict' | 'rejected'
}

export async function keepLocalHistorySyncConflict(
  view: HistorySyncConsentView,
  summary: HistorySyncConflictSummary,
  expectedRemoteRevision: number,
): Promise<void> {
  return resolveHistorySyncConflict(
    view,
    summary,
    expectedRemoteRevision,
    'keep_local_history_sync_conflict',
  )
}

export async function keepRemoteHistorySyncConflict(
  view: HistorySyncConsentView,
  summary: HistorySyncConflictSummary,
  expectedRemoteRevision: number,
): Promise<void> {
  return resolveHistorySyncConflict(
    view,
    summary,
    expectedRemoteRevision,
    'keep_remote_history_sync_conflict',
  )
}

async function resolveHistorySyncConflict(
  view: HistorySyncConsentView,
  summary: HistorySyncConflictSummary,
  expectedRemoteRevision: number,
  command: 'keep_local_history_sync_conflict' | 'keep_remote_history_sync_conflict',
): Promise<void> {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  if (
    !view?.active ||
    typeof view.sessionEpoch !== 'string' ||
    !uuid.test(view.sessionEpoch) ||
    typeof view.generation !== 'string' ||
    !uuid.test(view.generation) ||
    !summary ||
    !uuid.test(summary.recordId) ||
    !uuid.test(summary.mutationId) ||
    summary.result !== 'conflict' ||
    !Number.isSafeInteger(summary.clientRevision) ||
    summary.clientRevision < 1 ||
    !Number.isSafeInteger(summary.serverRevision) ||
    summary.serverRevision < 0 ||
    !Number.isSafeInteger(expectedRemoteRevision) ||
    expectedRemoteRevision < summary.serverRevision
  )
    throw new Error('Invalid history sync resolution')
  const isCurrent = captureCloudSessionOwner()
  if (!isCurrent()) throw new Error('History sync resolution superseded')
  if (historySyncOnceBlocked()) throw new Error('History sync resolution result unknown')
  const identity = Symbol()
  resolutionAttempt = { identity, current: isCurrent }
  const parameters = {
    recordId: summary.recordId,
    mutationId: summary.mutationId,
    expectedRemoteRevision,
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
  }
  const operation = Promise.resolve().then(() => {
    if (!isCurrent()) throw new Error('History sync resolution superseded')
    return invoke<unknown>(command, parameters)
  })
  const completion = operation.finally(() => {
    if (resolutionAttempt?.identity === identity) resolutionAttempt = null
  })
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    const response = await Promise.race([
      completion,
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => reject(new Error('History sync resolution result unknown')), 60000)
      }),
    ])
    if (!isCurrent()) throw new Error('History sync resolution superseded')
    if (response !== null) throw new Error('History sync resolution result unknown')
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
}

export type HistorySyncRemoteRecord = {
  recordId: string
  serverRevision: number
} & ({ deleted: true; payload: null } | { deleted: false; payload: HistorySyncTextPayload })

export type HistorySyncLocalRecord = {
  recordId: string
  clientRevision: number
} & ({ deleted: true; payload: null } | { deleted: false; payload: HistorySyncTextPayload })

export async function previewHistorySyncLocalRecord(
  view: HistorySyncConsentView,
  summary: HistorySyncConflictSummary,
): Promise<HistorySyncLocalRecord> {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  if (
    !view?.active ||
    typeof view.sessionEpoch !== 'string' ||
    !uuid.test(view.sessionEpoch) ||
    typeof view.generation !== 'string' ||
    !uuid.test(view.generation) ||
    !summary ||
    !uuid.test(summary.recordId) ||
    !uuid.test(summary.mutationId)
  )
    throw new Error('Invalid history sync authorization view')
  const isCurrent = captureCloudSessionOwner()
  if (!isCurrent()) throw new Error('History sync local preview superseded')
  const response = await invoke<unknown>('preview_history_sync_local_record', {
    recordId: summary.recordId,
    mutationId: summary.mutationId,
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
  })
  if (!isCurrent()) throw new Error('History sync local preview superseded')
  const invalid = () => new Error('Invalid local history sync record')
  if (
    !response ||
    typeof response !== 'object' ||
    Array.isArray(response) ||
    Object.keys(response).length !== 4
  )
    throw invalid()
  const record = response as Record<string, unknown>
  if (
    record.recordId !== summary.recordId ||
    record.clientRevision !== summary.clientRevision ||
    !Number.isSafeInteger(record.clientRevision) ||
    Number(record.clientRevision) < 1
  )
    throw invalid()
  const session = { accountId: 'validated-native-preview', sessionEpoch: view.sessionEpoch }
  try {
    const page = parseHistorySyncPage({
      requestSession: session,
      currentSession: session,
      requestedCursor: 0,
      currentCursor: 0,
      response: {
        version: 1,
        accountId: session.accountId,
        fromCursor: 0,
        nextCursor: 1,
        hasMore: false,
        changeSequences: [1],
        records: [
          {
            recordId: record.recordId,
            serverRevision: 1,
            changeSequence: 1,
            deleted: record.deleted,
            payload: record.payload,
          },
        ],
      },
    })
    const validated = page?.records[0]
    if (!validated) throw invalid()
    return {
      recordId: validated.recordId,
      clientRevision: summary.clientRevision,
      deleted: validated.deleted,
      payload: validated.payload,
    } as HistorySyncLocalRecord
  } catch {
    throw invalid()
  }
}

export async function previewHistorySyncRemoteRecord(
  view: HistorySyncConsentView,
  summary: HistorySyncConflictSummary,
): Promise<HistorySyncRemoteRecord | null> {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  if (
    !view?.active ||
    typeof view.sessionEpoch !== 'string' ||
    !uuid.test(view.sessionEpoch) ||
    typeof view.generation !== 'string' ||
    !uuid.test(view.generation) ||
    !summary ||
    !uuid.test(summary.recordId) ||
    !Number.isSafeInteger(summary.serverRevision) ||
    summary.serverRevision < 0
  )
    throw new Error('Invalid history sync authorization view')
  const isCurrent = captureCloudSessionOwner()
  if (!isCurrent()) throw new Error('History sync remote preview superseded')
  const response = await invoke<unknown>('preview_history_sync_remote_record', {
    recordId: summary.recordId,
    mutationId: summary.mutationId,
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
  })
  if (!isCurrent()) throw new Error('History sync remote preview superseded')
  if (response === null) return null
  const invalid = () => new Error('Invalid remote history sync record')
  if (
    !response ||
    typeof response !== 'object' ||
    Array.isArray(response) ||
    Object.keys(response).length !== 4
  )
    throw invalid()
  const record = response as Record<string, unknown>
  if (
    typeof record.recordId !== 'string' ||
    record.recordId.toLowerCase() !== summary.recordId.toLowerCase() ||
    typeof record.serverRevision !== 'number' ||
    record.serverRevision < summary.serverRevision
  )
    throw invalid()
  const session = { accountId: 'validated-native-preview', sessionEpoch: view.sessionEpoch }
  try {
    const page = parseHistorySyncPage({
      requestSession: session,
      currentSession: session,
      requestedCursor: 0,
      currentCursor: 0,
      response: {
        version: 1,
        accountId: session.accountId,
        fromCursor: 0,
        nextCursor: 1,
        hasMore: false,
        changeSequences: [1],
        records: [{ ...record, changeSequence: 1 }],
      },
    })
    const validated = page?.records[0]
    if (!validated) throw invalid()
    return {
      recordId: validated.recordId,
      serverRevision: validated.serverRevision,
      deleted: validated.deleted,
      payload: validated.payload,
    } as HistorySyncRemoteRecord
  } catch {
    throw invalid()
  }
}

export async function listHistorySyncConflicts(
  view: HistorySyncConsentView,
  afterRecordId?: string,
): Promise<HistorySyncConflictSummary[]> {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  if (
    !view?.active ||
    typeof view.sessionEpoch !== 'string' ||
    !uuid.test(view.sessionEpoch) ||
    typeof view.generation !== 'string' ||
    !uuid.test(view.generation)
  )
    throw new Error('Invalid history sync authorization view')
  const isCurrent = captureCloudSessionOwner()
  if (!isCurrent()) throw new Error('History sync conflict read superseded')
  if (afterRecordId !== undefined && !uuid.test(afterRecordId))
    throw new Error('Invalid history sync conflict cursor')
  const summaries = await invoke<unknown>('list_history_sync_conflicts', {
    expectedSessionEpoch: view.sessionEpoch,
    expectedGeneration: view.generation,
    ...(afterRecordId === undefined ? {} : { afterRecordId }),
  })
  if (!isCurrent()) throw new Error('History sync conflict read superseded')
  const records = new Set<string>()
  let previousRecordId = afterRecordId?.toLowerCase()
  if (
    !Array.isArray(summaries) ||
    summaries.length > 200 ||
    !summaries.every((summary) => {
      if (
        !summary ||
        typeof summary !== 'object' ||
        Array.isArray(summary) ||
        Object.keys(summary).length !== 5 ||
        typeof summary.recordId !== 'string' ||
        !uuid.test(summary.recordId) ||
        typeof summary.mutationId !== 'string' ||
        !uuid.test(summary.mutationId) ||
        !Number.isSafeInteger(summary.clientRevision) ||
        summary.clientRevision < 1 ||
        !Number.isSafeInteger(summary.serverRevision) ||
        summary.serverRevision < 0 ||
        !['conflict', 'rejected'].includes(summary.result) ||
        records.has(summary.recordId.toLowerCase()) ||
        (previousRecordId !== undefined && summary.recordId.toLowerCase() <= previousRecordId)
      )
        return false
      records.add(summary.recordId.toLowerCase())
      previousRecordId = summary.recordId.toLowerCase()
      return true
    })
  )
    throw new Error('Invalid history sync conflict list')
  return summaries as HistorySyncConflictSummary[]
}

export async function getHistorySyncAttempt(
  view: HistorySyncConsentView,
): Promise<HistorySyncAttempt | null> {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  if (
    !view?.active ||
    typeof view.sessionEpoch !== 'string' ||
    !uuid.test(view.sessionEpoch) ||
    typeof view.generation !== 'string' ||
    !uuid.test(view.generation)
  )
    throw new Error('Invalid history sync authorization view')
  const isCurrent = captureCloudSessionOwner()
  if (!isCurrent()) throw new Error('History sync result read superseded')
  let timer: ReturnType<typeof setTimeout> | undefined
  let response: unknown
  try {
    response = await Promise.race([
      invoke<unknown>('get_history_sync_attempt', {
        expectedSessionEpoch: view.sessionEpoch,
        expectedGeneration: view.generation,
      }),
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => reject(new Error('History sync result read timed out')), 5000)
      }),
    ])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
  if (!isCurrent()) throw new Error('History sync result read superseded')
  if (response === null) return null
  if (!response || typeof response !== 'object' || Array.isArray(response)) {
    throw new Error('Invalid history sync attempt')
  }
  const attempt = response as Record<string, unknown>
  if (
    Object.keys(attempt).some(
      (key) => !['requestId', 'status', 'result', 'acknowledged'].includes(key),
    ) ||
    typeof attempt.requestId !== 'string' ||
    !uuid.test(attempt.requestId) ||
    typeof attempt.status !== 'string' ||
    !['running', 'confirmed', 'unknown'].includes(attempt.status) ||
    !('result' in attempt)
  )
    throw new Error('Invalid history sync attempt')
  if (
    'acknowledged' in attempt &&
    (typeof attempt.acknowledged !== 'boolean' ||
      (attempt.acknowledged && attempt.status !== 'unknown'))
  ) {
    throw new Error('Invalid history sync attempt')
  }
  if (attempt.status === 'confirmed') {
    return {
      requestId: attempt.requestId,
      status: 'confirmed',
      result: validateHistorySyncOnceResult(attempt.result),
    }
  }
  if (attempt.result !== null) throw new Error('Invalid history sync attempt')
  return {
    requestId: attempt.requestId,
    status: attempt.status as 'running' | 'unknown',
    result: null,
    ...('acknowledged' in attempt ? { acknowledged: attempt.acknowledged as boolean } : {}),
  }
}

export async function acknowledgeHistorySyncAttempt(
  view: HistorySyncConsentView,
  attempt: HistorySyncAttempt,
): Promise<void> {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  if (
    !view?.active ||
    !uuid.test(view.sessionEpoch) ||
    typeof view.generation !== 'string' ||
    !uuid.test(view.generation) ||
    !attempt ||
    !uuid.test(attempt.requestId) ||
    !['running', 'unknown'].includes(attempt.status) ||
    attempt.acknowledged === true ||
    attempt.result !== null
  )
    throw new Error('Invalid history sync acknowledgement')
  const isCurrent = captureCloudSessionOwner()
  if (!isCurrent()) throw new Error('History sync acknowledgement superseded')
  const capturedAttempt = syncOnceAttempt
  let timer: ReturnType<typeof setTimeout> | undefined
  let response: unknown
  try {
    response = await Promise.race([
      invoke<unknown>('acknowledge_history_sync_attempt', {
        requestId: attempt.requestId,
        expectedSessionEpoch: view.sessionEpoch,
        expectedGeneration: view.generation,
      }),
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(
          () => reject(new Error('History sync acknowledgement result unknown')),
          5000,
        )
      }),
    ])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
  if (!isCurrent()) throw new Error('History sync acknowledgement superseded')
  if (response !== null) throw new Error('Invalid history sync acknowledgement response')
  if (syncOnceAttempt === capturedAttempt) syncOnceAttempt = null
}

export async function revokeHistorySyncConsentView(view: HistorySyncConsentView): Promise<void> {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  if (
    !view ||
    view.active !== true ||
    typeof view.sessionEpoch !== 'string' ||
    !uuid.test(view.sessionEpoch) ||
    typeof view.generation !== 'string' ||
    !uuid.test(view.generation)
  ) {
    throw new Error('Invalid history sync consent revocation view')
  }
  const isCurrent = captureCloudSessionOwner()
  if (!isCurrent()) throw new Error('History sync consent revocation superseded')
  let timer: ReturnType<typeof setTimeout> | undefined
  let response: unknown
  try {
    response = await Promise.race([
      invoke<unknown>('revoke_history_sync_consent_generation', {
        expectedSessionEpoch: view.sessionEpoch,
        expectedGeneration: view.generation,
      }),
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(
          () => reject(new Error('History sync consent revocation result unknown')),
          5000,
        )
      }),
    ])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
  if (!isCurrent()) throw new Error('History sync consent revocation superseded')
  if (response !== null) throw new Error('Invalid history sync consent revocation response')
}

export async function getHistorySyncConsentView(): Promise<HistorySyncConsentView> {
  const isCurrent = captureCloudSessionOwner()
  if (!isCurrent()) throw new Error('History sync consent view superseded')
  let timer: ReturnType<typeof setTimeout> | undefined
  let value: unknown
  try {
    value = await Promise.race([
      invoke<unknown>('get_history_sync_consent_view'),
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => reject(new Error('History sync consent view timed out')), 5000)
      }),
    ])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
  if (!isCurrent()) throw new Error('History sync consent view superseded')
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Invalid history sync consent view')
  }
  const view = value as Record<string, unknown>
  if (
    Object.keys(view).length !== 3 ||
    typeof view.sessionEpoch !== 'string' ||
    !uuid.test(view.sessionEpoch) ||
    typeof view.active !== 'boolean' ||
    (view.active
      ? typeof view.generation !== 'string' || !uuid.test(view.generation)
      : view.generation !== null)
  ) {
    throw new Error('Invalid history sync consent view')
  }
  return {
    sessionEpoch: view.sessionEpoch,
    active: view.active,
    generation: view.generation as string | null,
  }
}

export async function getHistorySyncSessionEpoch(): Promise<string> {
  const isCurrent = captureCloudSessionOwner()
  if (!isCurrent()) throw new Error('History sync session epoch superseded')
  let timer: ReturnType<typeof setTimeout> | undefined
  let epoch: unknown
  try {
    epoch = await Promise.race([
      invoke<unknown>('get_history_sync_session_epoch'),
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => reject(new Error('History sync session epoch timed out')), 5000)
      }),
    ])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
  if (!isCurrent()) throw new Error('History sync session epoch superseded')
  if (
    typeof epoch !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(epoch)
  ) {
    throw new Error('Invalid history sync session epoch')
  }
  return epoch
}

export async function getHistorySyncConsentStatus(): Promise<boolean> {
  const isCurrent = captureCloudSessionOwner()
  if (!isCurrent()) throw new Error('History sync consent status superseded')
  const status = await invoke<unknown>('get_history_sync_consent_status')
  if (!isCurrent()) throw new Error('History sync consent status superseded')
  if (typeof status !== 'boolean') throw new Error('Invalid history sync consent status')
  return status
}
