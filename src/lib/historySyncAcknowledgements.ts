export interface HistorySyncSession {
  accountId: string
  sessionEpoch: string
}

export interface HistorySyncMutation {
  recordId: string
  mutationId: string
  clientRevision: number
  baseServerRevision: number
}

export interface HistorySyncDecision {
  recordId: string
  mutationId: string
  status: 'accepted' | 'pending' | 'stale' | 'conflict' | 'rejected'
  serverRevision?: number
}

const MAX_BATCH_SIZE = 200

function invalid(): never {
  throw new Error('Invalid history sync acknowledgement')
}

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function identifier(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    value.length > 0 &&
    value.length <= 200 &&
    value.trim() === value &&
    Array.from(value).every((character) => {
      const code = character.charCodeAt(0)
      return code >= 32 && code !== 127
    })
  )
}

function revision(value: unknown, minimum = 0): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum
}

export function isHistorySyncSession(value: unknown): value is HistorySyncSession {
  return record(value) && identifier(value.accountId) && identifier(value.sessionEpoch)
}

function mutationMap(values: HistorySyncMutation[]): Map<string, HistorySyncMutation> {
  if (!Array.isArray(values) || values.length > MAX_BATCH_SIZE) invalid()
  const mutations = new Map<string, HistorySyncMutation>()
  const records = new Set<string>()
  for (const value of values) {
    if (
      !record(value) ||
      !identifier(value.recordId) ||
      !identifier(value.mutationId) ||
      !revision(value.clientRevision, 1) ||
      !revision(value.baseServerRevision) ||
      mutations.has(value.mutationId) ||
      records.has(value.recordId)
    ) {
      invalid()
    }
    mutations.set(value.mutationId, value)
    records.add(value.recordId)
  }
  return mutations
}

export function decideHistorySyncAcknowledgements({
  requestSession,
  currentSession,
  sent,
  current,
  response,
}: {
  requestSession: HistorySyncSession
  currentSession: HistorySyncSession
  sent: HistorySyncMutation[]
  current: HistorySyncMutation[]
  response: unknown
}): HistorySyncDecision[] {
  if (!isHistorySyncSession(requestSession) || !isHistorySyncSession(currentSession)) invalid()
  if (
    requestSession.accountId !== currentSession.accountId ||
    requestSession.sessionEpoch !== currentSession.sessionEpoch
  ) {
    return []
  }
  const submitted = mutationMap(sent)
  const active = mutationMap(current)
  if (
    !record(response) ||
    response.version !== 1 ||
    response.accountId !== requestSession.accountId ||
    !Array.isArray(response.acknowledgements) ||
    response.acknowledgements.length > sent.length
  ) {
    invalid()
  }
  const acknowledgements = new Map<
    string,
    { result: 'accepted' | 'conflict' | 'rejected'; serverRevision: number }
  >()
  for (const value of response.acknowledgements) {
    if (!record(value) || !identifier(value.mutationId)) invalid()
    const mutation = submitted.get(value.mutationId)
    if (
      !mutation ||
      acknowledgements.has(value.mutationId) ||
      value.recordId !== mutation.recordId ||
      value.clientRevision !== mutation.clientRevision ||
      !revision(value.serverRevision) ||
      (value.result !== 'accepted' && value.result !== 'conflict' && value.result !== 'rejected') ||
      (value.result === 'accepted' && value.serverRevision <= mutation.baseServerRevision)
    ) {
      invalid()
    }
    acknowledgements.set(value.mutationId, {
      result: value.result,
      serverRevision: value.serverRevision,
    })
  }
  return sent.map((mutation) => {
    const identity = { recordId: mutation.recordId, mutationId: mutation.mutationId }
    const latest = active.get(mutation.mutationId)
    if (
      !latest ||
      latest.recordId !== mutation.recordId ||
      latest.clientRevision !== mutation.clientRevision ||
      latest.baseServerRevision !== mutation.baseServerRevision
    ) {
      return { ...identity, status: 'stale' }
    }
    const acknowledgement = acknowledgements.get(mutation.mutationId)
    if (!acknowledgement) return { ...identity, status: 'pending' }
    return {
      ...identity,
      status: acknowledgement.result,
      serverRevision: acknowledgement.serverRevision,
    }
  })
}
