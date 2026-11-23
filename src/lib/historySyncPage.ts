import { validateHistorySyncPageCursor } from './historySyncCursor'

export interface HistorySyncTextPayload {
  rawText: string
  polishedText: string
  mode: 'dictate' | 'translate' | 'ask'
  answerText: string | null
  createdAt: string
  durationMs: number | null
  language: string | null
}

export type HistorySyncPageRecord = {
  changeSequence: number
  recordId: string
  serverRevision: number
} & ({ deleted: true; payload: null } | { deleted: false; payload: HistorySyncTextPayload })

export interface ParsedHistorySyncPage {
  accountId: string
  sessionEpoch: string
  fromCursor: number
  nextCursor: number
  hasMore: boolean
  changeSequences: number[]
  records: HistorySyncPageRecord[]
}

export function validateHistorySyncPage(
  input: Parameters<typeof validateHistorySyncPageCursor>[0],
): ReturnType<typeof validateHistorySyncPageCursor> {
  const page = parseHistorySyncPage(input)
  return page
    ? {
        nextCursor: page.nextCursor,
        hasMore: page.hasMore,
        changeCount: page.records.length,
      }
    : null
}

export function parseHistorySyncPage(
  input: Parameters<typeof validateHistorySyncPageCursor>[0],
): ParsedHistorySyncPage | null {
  const invalid = (): never => {
    throw new Error('Invalid history sync page')
  }
  let cursor: ReturnType<typeof validateHistorySyncPageCursor>
  try {
    cursor = validateHistorySyncPageCursor(input)
  } catch {
    return invalid()
  }
  if (!cursor) return null
  const page = input.response as Record<string, unknown>
  if (!Array.isArray(page.records) || page.records.length !== cursor.changeCount) invalid()
  const encoder = new TextEncoder()
  const pageKeys = [
    'version',
    'accountId',
    'fromCursor',
    'nextCursor',
    'hasMore',
    'changeSequences',
    'records',
  ]
  if (
    Object.keys(page).length !== pageKeys.length ||
    pageKeys.some((key) => !Object.prototype.hasOwnProperty.call(page, key))
  ) {
    invalid()
  }
  let serializedBytes = encoder.encode(
    JSON.stringify({
      version: 1,
      accountId: input.requestSession.accountId,
      fromCursor: input.requestedCursor,
      nextCursor: cursor.nextCursor,
      hasMore: cursor.hasMore,
      changeSequences: page.changeSequences,
      records: [],
    }),
  ).byteLength
  const textBytes = (value: string, limit: number) => {
    if (/[\uD800-\uDFFF]/u.test(value) || value.includes('\0') || value.length > limit) invalid()
    const bytes = encoder.encode(value).byteLength
    if (bytes > limit) invalid()
    return bytes
  }
  let totalBytes = 0
  const sequences = page.changeSequences as number[]
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  const recordVersions = new Map<string, { revision: number; deleted: boolean }>()
  const safeRecords: HistorySyncPageRecord[] = []
  for (const [index, value] of (page.records as unknown[]).entries()) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) invalid()
    const change = value as Record<string, unknown>
    const changeKeys = ['changeSequence', 'recordId', 'serverRevision', 'deleted', 'payload']
    if (
      Object.keys(change).length !== changeKeys.length ||
      changeKeys.some((key) => !Object.prototype.hasOwnProperty.call(change, key))
    ) {
      invalid()
    }
    if (
      change.changeSequence !== sequences[index] ||
      typeof change.recordId !== 'string' ||
      !uuid.test(change.recordId) ||
      typeof change.serverRevision !== 'number' ||
      !Number.isSafeInteger(change.serverRevision) ||
      change.serverRevision < 1 ||
      typeof change.deleted !== 'boolean'
    ) {
      invalid()
    }
    const recordId = (change.recordId as string).toLowerCase()
    const previous = recordVersions.get(recordId)
    if (
      previous &&
      ((change.serverRevision as number) <= previous.revision ||
        (previous.deleted && !change.deleted))
    ) {
      invalid()
    }
    recordVersions.set(recordId, {
      revision: change.serverRevision as number,
      deleted: change.deleted as boolean,
    })
    if (change.deleted) {
      if (change.payload !== null) invalid()
      safeRecords.push({
        changeSequence: sequences[index],
        recordId,
        serverRevision: change.serverRevision as number,
        deleted: true,
        payload: null,
      })
    } else {
      if (!change.payload || typeof change.payload !== 'object' || Array.isArray(change.payload)) {
        invalid()
      }
      const payload = change.payload as Record<string, unknown>
      const payloadKeys = [
        'rawText',
        'polishedText',
        'mode',
        'answerText',
        'createdAt',
        'durationMs',
        'language',
      ]
      if (
        Object.keys(payload).length !== payloadKeys.length ||
        payloadKeys.some((key) => !Object.prototype.hasOwnProperty.call(payload, key))
      ) {
        invalid()
      }
      if (
        typeof payload.rawText !== 'string' ||
        typeof payload.polishedText !== 'string' ||
        typeof payload.mode !== 'string' ||
        !['dictate', 'translate', 'ask'].includes(payload.mode) ||
        (payload.answerText !== null && typeof payload.answerText !== 'string') ||
        typeof payload.createdAt !== 'string' ||
        (payload.language !== null && typeof payload.language !== 'string') ||
        (payload.durationMs !== null &&
          (typeof payload.durationMs !== 'number' ||
            !Number.isSafeInteger(payload.durationMs) ||
            payload.durationMs < 0))
      ) {
        invalid()
      }
      const mode = payload.mode as HistorySyncTextPayload['mode']
      if (mode !== 'ask' && payload.answerText !== null) invalid()
      const createdAt = payload.createdAt as string
      textBytes(createdAt, 64)
      if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(createdAt)) invalid()
      const timestamp = Date.parse(`${createdAt}Z`)
      if (
        !Number.isFinite(timestamp) ||
        new Date(timestamp).toISOString().slice(0, 19) !== createdAt
      ) {
        invalid()
      }
      const bytes =
        textBytes(payload.rawText as string, 1024 * 1024) +
        textBytes(payload.polishedText as string, 1024 * 1024) +
        textBytes((payload.answerText as string | null) ?? '', 1024 * 1024) +
        textBytes((payload.language as string | null) ?? '', 200) +
        encoder.encode(createdAt + mode).byteLength
      if (bytes > 1024 * 1024) invalid()
      totalBytes += bytes
      if (totalBytes > 4 * 1024 * 1024) invalid()
      safeRecords.push({
        changeSequence: sequences[index],
        recordId,
        serverRevision: change.serverRevision as number,
        deleted: false,
        payload: {
          rawText: payload.rawText as string,
          polishedText: payload.polishedText as string,
          mode,
          answerText: payload.answerText as string | null,
          createdAt,
          durationMs: payload.durationMs as number | null,
          language: payload.language as string | null,
        },
      })
    }
    serializedBytes +=
      encoder.encode(JSON.stringify(safeRecords[index])).byteLength + (index > 0 ? 1 : 0)
    if (serializedBytes > 4 * 1024 * 1024) invalid()
  }
  return {
    accountId: input.requestSession.accountId,
    sessionEpoch: input.requestSession.sessionEpoch,
    fromCursor: input.requestedCursor,
    nextCursor: cursor.nextCursor,
    hasMore: cursor.hasMore,
    changeSequences: [...sequences],
    records: safeRecords,
  }
}
