import type { useAuthStore } from '../stores/authStore'

type AuthSnapshot = ReturnType<typeof useAuthStore.getState>

/** Cloud word usage, preferring the legacy display estimate when that quota model is active. */
export function usageWords(state: AuthSnapshot) {
  const legacy = state.quotaModel === 'legacy_dual_meter' && state.displayWordsLimit > 0
  return {
    used: legacy ? state.displayWordsUsedEstimate : state.cloudWordsUsed,
    limit: legacy ? state.displayWordsLimit : state.cloudWordsLimit,
    resetAt: legacy ? state.displayWordsResetAt : state.cloudWordsResetAt,
  }
}
