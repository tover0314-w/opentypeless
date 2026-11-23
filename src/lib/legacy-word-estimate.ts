export interface LegacyQuotaMeters {
  sttSecondsUsed: number
  sttSecondsLimit: number
  llmTokensUsed: number
  llmTokensLimit: number
}

const REFERENCE_WORDS = 100_000
const REFERENCE_STT_SECONDS = 36_000
const REFERENCE_LLM_TOKENS = 5_000_000

export function estimateLegacyWords(meters: LegacyQuotaMeters) {
  if (
    [
      meters.sttSecondsUsed,
      meters.sttSecondsLimit,
      meters.llmTokensUsed,
      meters.llmTokensLimit,
    ].some((value) => !Number.isFinite(value) || value < 0) ||
    meters.sttSecondsLimit <= 0 ||
    meters.llmTokensLimit <= 0
  ) {
    return null
  }
  const speechLimit = (meters.sttSecondsLimit / REFERENCE_STT_SECONDS) * REFERENCE_WORDS
  const aiLimit = (meters.llmTokensLimit / REFERENCE_LLM_TOKENS) * REFERENCE_WORDS
  const limit = Math.floor(Math.min(speechLimit, aiLimit))
  const speechRemaining =
    (Math.max(0, meters.sttSecondsLimit - meters.sttSecondsUsed) / REFERENCE_STT_SECONDS) *
    REFERENCE_WORDS
  const aiRemaining =
    (Math.max(0, meters.llmTokensLimit - meters.llmTokensUsed) / REFERENCE_LLM_TOKENS) *
    REFERENCE_WORDS
  const remaining = Math.max(0, Math.min(limit, Math.floor(Math.min(speechRemaining, aiRemaining))))
  return { limit, remaining, used: limit - remaining }
}
