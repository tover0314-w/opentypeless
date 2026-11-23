import { captureCloudSessionOwner } from './cloud-session'

let attempt: { identity: symbol; current: () => boolean } | null = null

export function historySyncAttemptBlocked(): boolean {
  if (attempt && !attempt.current()) attempt = null
  return attempt !== null
}

export function beginHistorySyncAttempt(): (() => void) | null {
  if (historySyncAttemptBlocked()) return null
  const current = captureCloudSessionOwner()
  if (!current()) return null
  const identity = Symbol()
  attempt = { identity, current }
  return () => {
    if (attempt?.identity === identity) attempt = null
  }
}
