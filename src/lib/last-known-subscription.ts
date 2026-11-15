import type { SubscriptionStatus } from './api'
import { accountSnapshotMatches, isSubscriptionStatus } from './subscription-validation'

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

function cacheableSubscription(snapshot: SubscriptionStatus): SubscriptionStatus {
  const account = snapshot.accountSnapshot
  const capability = account?.managedSttCapabilities
  return {
    plan: snapshot.plan,
    source: snapshot.source,
    displayName: snapshot.displayName,
    subscriptionEnd: snapshot.subscriptionEnd,
    subscriptionStatus: snapshot.subscriptionStatus,
    licenseStatus: snapshot.licenseStatus,
    billingProvider: snapshot.billingProvider,
    cancelAtPeriodEnd: snapshot.cancelAtPeriodEnd,
    canManageBilling: snapshot.canManageBilling,
    canMigrateToStripe: snapshot.canMigrateToStripe,
    entitlementVersion: snapshot.entitlementVersion,
    quotaModel: snapshot.quotaModel,
    ...(snapshot.quotaDataAvailable === undefined
      ? {}
      : { quotaDataAvailable: snapshot.quotaDataAvailable }),
    displayWordsUsedEstimate: snapshot.displayWordsUsedEstimate,
    displayWordsLimit: snapshot.displayWordsLimit,
    displayWordsResetAt: snapshot.displayWordsResetAt,
    sttSecondsUsed: snapshot.sttSecondsUsed,
    sttSecondsLimit: snapshot.sttSecondsLimit,
    llmTokensUsed: snapshot.llmTokensUsed,
    llmTokensLimit: snapshot.llmTokensLimit,
    cloudWordsUsed: snapshot.cloudWordsUsed,
    cloudWordsLimit: snapshot.cloudWordsLimit,
    cloudWordsResetAt: snapshot.cloudWordsResetAt,
    byokUnlimited: snapshot.byokUnlimited,
    ...(account === undefined
      ? {}
      : {
          accountSnapshot: account
            ? {
                schemaVersion: account.schemaVersion,
                userId: account.userId,
                generatedAt: account.generatedAt,
                managedSttCapabilities: capability
                  ? {
                      version: capability.version,
                      maxRecordingSeconds: capability.maxRecordingSeconds,
                      maxMultipartBytes: capability.maxMultipartBytes,
                      formats: capability.formats.map((format) => ({
                        mimeType: format.mimeType,
                        maxAudioBytes: format.maxAudioBytes,
                        preferredClientSwitchBytes: format.preferredClientSwitchBytes,
                        bitrateBitsPerSecond: format.bitrateBitsPerSecond,
                      })),
                    }
                  : null,
              }
            : null,
        }),
  }
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
    if (!accountSnapshotMatches(cached.snapshot, userId)) {
      return null
    }
    return { verifiedAt: cached.verifiedAt, snapshot: cacheableSubscription(cached.snapshot) }
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
    if (!isSubscriptionStatus(snapshot) || !accountSnapshotMatches(snapshot, userId)) return
    storage.setItem(
      keyFor(userId),
      JSON.stringify({
        version: CACHE_VERSION,
        userId,
        verifiedAt,
        snapshot: cacheableSubscription(snapshot),
      }),
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
