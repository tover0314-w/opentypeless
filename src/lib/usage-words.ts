import type { useAuthStore } from '../stores/authStore'
import { estimateLegacyWords } from './legacy-word-estimate'

type AuthSnapshot = ReturnType<typeof useAuthStore.getState>

/** Cloud word usage, preferring the legacy display estimate when that quota model is active. */
export function usageWords(state: AuthSnapshot) {
  if (
    state.quotaModel === 'legacy_dual_meter' &&
    state.displayWordsLimit === 0 &&
    state.cloudWordsLimit === 0 &&
    state.quotaDataAvailable !== false
  ) {
    const estimate = estimateLegacyWords(state)
    if (estimate) {
      return {
        used: estimate.used,
        limit: estimate.limit,
        resetAt: state.cloudWordsResetAt,
        estimated: true,
      }
    }
  }
  const legacy = state.quotaModel === 'legacy_dual_meter' && state.displayWordsLimit > 0
  return {
    estimated: false,
    used: legacy ? state.displayWordsUsedEstimate : state.cloudWordsUsed,
    limit: legacy ? state.displayWordsLimit : state.cloudWordsLimit,
    resetAt: legacy ? state.displayWordsResetAt : state.cloudWordsResetAt,
  }
}
