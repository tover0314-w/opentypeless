import type { SubscriptionStatus } from './api'
import { isAskWebSearchCapability } from './askWebSearchCapability'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function isNonNegativeNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
}

function isUnsigned32(value: unknown): boolean {
  return isNonNegativeNumber(value) && Number.isInteger(value) && value <= 4294967295
}

function isAccountSnapshot(value: unknown): boolean {
  if (value == null) return true
  if (
    !isRecord(value) ||
    value.schemaVersion !== 1 ||
    typeof value.userId !== 'string' ||
    !value.userId.trim() ||
    typeof value.generatedAt !== 'string'
  ) {
    return false
  }
  const capability = value.managedSttCapabilities
  if (
    value.askWebSearchCapability != null &&
    !isAskWebSearchCapability(value.askWebSearchCapability)
  ) {
    return false
  }
  if (capability === null) return true
  return (
    isRecord(capability) &&
    isUnsigned32(capability.version) &&
    isUnsigned32(capability.maxRecordingSeconds) &&
    isNonNegativeNumber(capability.maxMultipartBytes) &&
    Number.isSafeInteger(capability.maxMultipartBytes) &&
    Array.isArray(capability.formats) &&
    capability.formats.every(
      (format) =>
        isRecord(format) &&
        typeof format.mimeType === 'string' &&
        Boolean(format.mimeType.trim()) &&
        isNonNegativeNumber(format.maxAudioBytes) &&
        Number.isSafeInteger(format.maxAudioBytes) &&
        (format.preferredClientSwitchBytes == null ||
          (isNonNegativeNumber(format.preferredClientSwitchBytes) &&
            Number.isSafeInteger(format.preferredClientSwitchBytes))) &&
        (format.bitrateBitsPerSecond == null || isUnsigned32(format.bitrateBitsPerSecond)),
    )
  )
}

export function accountSnapshotMatches(snapshot: SubscriptionStatus, userId: string): boolean {
  return (
    snapshot.accountSnapshot == null ||
    (isRecord(snapshot.accountSnapshot) &&
      snapshot.accountSnapshot.schemaVersion === 1 &&
      snapshot.accountSnapshot.userId === userId)
  )
}

export function hasValidSubscriptionBillingFields(value: unknown): boolean {
  return (
    isRecord(value) &&
    (value.billingProvider == null ||
      ['stripe', 'creem', 'appsumo'].includes(value.billingProvider as string)) &&
    [value.canManageBilling, value.canMigrateToStripe, value.cancelAtPeriodEnd].every(
      (flag) => flag === undefined || typeof flag === 'boolean',
    ) &&
    (value.entitlementVersion === undefined ||
      (isNonNegativeNumber(value.entitlementVersion) &&
        Number.isSafeInteger(value.entitlementVersion)))
  )
}

export function isSubscriptionStatus(value: unknown): value is SubscriptionStatus {
  if (!isRecord(value) || typeof value.plan !== 'string' || typeof value.source !== 'string') {
    return false
  }
  return (
    ['free', 'pro', 'lifetime_starter', 'appsumo_tier1', 'appsumo_tier2', 'appsumo_tier3'].includes(
      value.plan,
    ) &&
    ['free', 'creem', 'stripe', 'lifetime', 'appsumo'].includes(value.source) &&
    (value.quotaModel === 'legacy_dual_meter' || value.quotaModel === 'cloud_words') &&
    (value.quotaDataAvailable === undefined || typeof value.quotaDataAvailable === 'boolean') &&
    (value.licenseStatus == null ||
      ['pending', 'active', 'refunded', 'deactivated'].includes(value.licenseStatus as string)) &&
    hasValidSubscriptionBillingFields(value) &&
    typeof value.displayName === 'string' &&
    isNullableString(value.subscriptionEnd) &&
    isNullableString(value.subscriptionStatus) &&
    isNullableString(value.displayWordsResetAt) &&
    isNullableString(value.cloudWordsResetAt) &&
    isAccountSnapshot(value.accountSnapshot) &&
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
