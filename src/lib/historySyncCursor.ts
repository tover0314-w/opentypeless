function cursor(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function invalid(): never {
  throw new Error('Invalid history sync cursor')
}

export function validateHistorySyncPageCursor({
  requestSession,
  currentSession,
  requestedCursor,
  currentCursor,
  response,
}: {
  requestSession: HistorySyncSession
  currentSession: HistorySyncSession
  requestedCursor: number
  currentCursor: number
  response: unknown
}): { nextCursor: number; hasMore: boolean; changeCount: number } | null {
  if (
    !isHistorySyncSession(requestSession) ||
    !isHistorySyncSession(currentSession) ||
    !cursor(requestedCursor) ||
    !cursor(currentCursor)
  ) {
    invalid()
  }
  if (
    currentCursor !== requestedCursor ||
    currentSession.accountId !== requestSession.accountId ||
    currentSession.sessionEpoch !== requestSession.sessionEpoch
  ) {
    return null
  }
  if (response === null || typeof response !== 'object' || Array.isArray(response)) invalid()
  const page = response as Record<string, unknown>
  if (
    page.version !== 1 ||
    page.accountId !== requestSession.accountId ||
    page.fromCursor !== requestedCursor ||
    !cursor(page.nextCursor) ||
    typeof page.hasMore !== 'boolean' ||
    !Array.isArray(page.changeSequences) ||
    page.changeSequences.length > 500
  ) {
    invalid()
  }
  let previous = requestedCursor
  for (const sequence of page.changeSequences) {
    if (!cursor(sequence) || sequence <= previous) invalid()
    previous = sequence
  }
  if (page.nextCursor !== previous || (page.hasMore && previous === requestedCursor)) invalid()
  return {
    nextCursor: page.nextCursor,
    hasMore: page.hasMore,
    changeCount: page.changeSequences.length,
  }
}
import { isHistorySyncSession, type HistorySyncSession } from './historySyncAcknowledgements'
