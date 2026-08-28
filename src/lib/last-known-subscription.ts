import type { SubscriptionStatus } from './api'

export interface LastKnownSubscription {
  verifiedAt: string
  snapshot: SubscriptionStatus
}

const CACHE_VERSION = 1
const CACHE_PREFIX = 'opentypeless.desktop-subscription.v1:'
export const LAST_KNOWN_SUBSCRIPTION_TTL_MS = 24 * 60 * 60 * 1000
const MAX_CLOCK_SKEW_MS = 5 * 60 * 1000

function keyFor(userId: string) {
  return `${CACHE_PREFIX}${userId}`
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function isNonNegativeNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
}

function isSubscriptionStatus(value: unknown): value is SubscriptionStatus {
  if (!isRecord(value) || typeof value.plan !== 'string' || typeof value.source !== 'string') {
    return false
  }
  return (
    typeof value.displayName === 'string' &&
    isNullableString(value.subscriptionEnd) &&
    isNullableString(value.subscriptionStatus) &&
    isNonNegativeNumber(value.displayWordsUsedEstimate) &&
    isNonNegativeNumber(value.displayWordsLimit) &&
    isNonNegativeNumber(value.sttSecondsUsed) &&
    isNonNegativeNumber(value.sttSecondsLimit) &&
    isNonNegativeNumber(value.llmTokensUsed) &&
    isNonNegativeNumber(value.llmTokensLimit) &&
    isNonNegativeNumber(value.cloudWordsUsed) &&
    isNonNegativeNumber(value.cloudWordsLimit) &&
    typeof value.byokUnlimited === 'boolean'
  )
}

export function loadLastKnownSubscription(
  storage: Pick<Storage, 'getItem'>,
  userId: string,
  now = Date.now(),
): LastKnownSubscription | null {
  try {
    const raw = storage.getItem(keyFor(userId))
    if (!raw) return null
    const cached: unknown = JSON.parse(raw)
    const verifiedAtMs =
      isRecord(cached) && typeof cached.verifiedAt === 'string'
        ? Date.parse(cached.verifiedAt)
        : Number.NaN
    if (
      !isRecord(cached) ||
      cached.version !== CACHE_VERSION ||
      cached.userId !== userId ||
      typeof cached.verifiedAt !== 'string' ||
      !Number.isFinite(verifiedAtMs) ||
      verifiedAtMs > now + MAX_CLOCK_SKEW_MS ||
      now - verifiedAtMs > LAST_KNOWN_SUBSCRIPTION_TTL_MS ||
      !isSubscriptionStatus(cached.snapshot)
    ) {
      return null
    }
    return { verifiedAt: cached.verifiedAt, snapshot: cached.snapshot }
  } catch {
    return null
  }
}

export function isLastKnownSubscriptionUsable(verifiedAt: string | null, now = Date.now()) {
  if (!verifiedAt) return false
  const verifiedAtMs = Date.parse(verifiedAt)
  return (
    Number.isFinite(verifiedAtMs) &&
    verifiedAtMs <= now + MAX_CLOCK_SKEW_MS &&
    now - verifiedAtMs <= LAST_KNOWN_SUBSCRIPTION_TTL_MS
  )
}

export function saveLastKnownSubscription(
  storage: Pick<Storage, 'setItem'>,
  userId: string,
  snapshot: SubscriptionStatus,
  verifiedAt: string,
) {
  try {
    storage.setItem(
      keyFor(userId),
      JSON.stringify({ version: CACHE_VERSION, userId, verifiedAt, snapshot }),
    )
  } catch {
    // The live in-memory entitlement remains usable when storage is unavailable.
  }
}

export function clearLastKnownSubscription(storage: Pick<Storage, 'removeItem'>, userId: string) {
  try {
    storage.removeItem(keyFor(userId))
  } catch {
    // Best effort only; sign-out still invalidates the authenticated session.
  }
}
