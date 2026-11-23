export interface AskWebSearchCapability {
  version: 1
  outputTokenLimit: number
  maxResults: number
  additionalCloudWords: number
  additionalLlmTokens: number
  selectedTextAllowed: false
}

export type AskWebSearchQuote = AskWebSearchCapability & {
  accountId: string
  quotaModel: 'cloud_words' | 'legacy_dual_meter'
}

export const ASK_WEB_QUOTE_TTL_MS = 5 * 60 * 1000

export function isAskWebQuoteFresh(verifiedAt: string | null, now = Date.now()): boolean {
  if (!verifiedAt) return false
  const verifiedTime = Date.parse(verifiedAt)
  const age = now - verifiedTime
  return Number.isFinite(age) && age >= 0 && age < ASK_WEB_QUOTE_TTL_MS
}

export function isAskWebSearchCapability(value: unknown): value is AskWebSearchCapability {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const capability = value as Record<string, unknown>
  const bounded = (field: string, limit: number) =>
    typeof capability[field] === 'number' &&
    Number.isSafeInteger(capability[field]) &&
    (capability[field] as number) > 0 &&
    (capability[field] as number) <= limit
  return (
    capability.version === 1 &&
    capability.selectedTextAllowed === false &&
    bounded('outputTokenLimit', 10000) &&
    bounded('maxResults', 20) &&
    bounded('additionalCloudWords', 100000) &&
    bounded('additionalLlmTokens', 100000)
  )
}
