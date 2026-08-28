export const ACCOUNT_STATUS_FRESHNESS_MS = 30_000

export function shouldRefreshSubscriptionOnFocus(checkoutPending: boolean): boolean {
  return checkoutPending
}

export function shouldRefreshSubscriptionOnAccountOpen(
  lastRefreshedAt: number | null,
  now: number,
): boolean {
  return lastRefreshedAt === null || now - lastRefreshedAt >= ACCOUNT_STATUS_FRESHNESS_MS
}
