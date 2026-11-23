export interface HistorySyncCapability {
  version: 1
  maxPushRecords: number
  maxPullRecords: number
  maxPayloadBytes: number
  maxRequestBytes: number
  maxResponseBytes: number
  audioSupported: false
  citationsSupported: false
}

export function parseHistorySyncCapability(
  value: unknown,
  accountId: string,
): HistorySyncCapability | null {
  const invalid = (): never => {
    throw new Error('Invalid history sync capability')
  }
  if (
    typeof accountId !== 'string' ||
    !accountId.trim() ||
    accountId.trim() !== accountId ||
    accountId.length > 200 ||
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value)
  ) {
    invalid()
  }
  const snapshot = value as Record<string, unknown>
  if (snapshot.version !== 1 || snapshot.accountId !== accountId) invalid()
  if (snapshot.capability === null) return null
  if (
    !snapshot.capability ||
    typeof snapshot.capability !== 'object' ||
    Array.isArray(snapshot.capability)
  ) {
    invalid()
  }
  const capability = snapshot.capability as Record<string, unknown>
  const bounded = (key: string, maximum: number): number => {
    const field = capability[key]
    if (typeof field !== 'number' || !Number.isSafeInteger(field) || field < 1 || field > maximum) {
      return invalid()
    }
    return field
  }
  if (
    capability.version !== 1 ||
    capability.audioSupported !== false ||
    capability.citationsSupported !== false
  ) {
    invalid()
  }
  return {
    version: 1,
    maxPushRecords: bounded('maxPushRecords', 200),
    maxPullRecords: bounded('maxPullRecords', 500),
    maxPayloadBytes: bounded('maxPayloadBytes', 1048576),
    maxRequestBytes: bounded('maxRequestBytes', 4194304),
    maxResponseBytes: bounded('maxResponseBytes', 4194304),
    audioSupported: false,
    citationsSupported: false,
  }
}
