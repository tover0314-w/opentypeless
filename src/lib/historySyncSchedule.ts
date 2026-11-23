import type { HistorySyncOnceResult } from './historySyncConsent'
import { useSyncExternalStore } from 'react'

const enabledAccounts = new Set<string>()
const subscribers = new Set<() => void>()

export function setAutomaticHistorySync(accountId: string, enabled: boolean) {
  if (enabled) enabledAccounts.add(accountId)
  else enabledAccounts.delete(accountId)
  for (const subscriber of subscribers) subscriber()
}

export function useAutomaticHistorySync(accountId: string | undefined): boolean {
  return useSyncExternalStore(
    (subscriber) => {
      subscribers.add(subscriber)
      return () => subscribers.delete(subscriber)
    },
    () => Boolean(accountId && enabledAccounts.has(accountId)),
  )
}

export function nextHistorySyncDelay(
  result: HistorySyncOnceResult | null,
  failures: number,
): number {
  if (failures > 0) return Math.min(300000, 30000 * 2 ** Math.min(failures - 1, 4))
  return result && (result.hasMore || (result.pending > 0 && result.pushed + result.pulled > 0))
    ? 7000
    : 30000
}
