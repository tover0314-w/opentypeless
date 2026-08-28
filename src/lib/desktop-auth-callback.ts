import { API_BASE_URL } from './constants'
import {
  clearOAuthState,
  claimOAuthState,
  generateOAuthState,
  getPendingOAuthVerifier,
  OAUTH_STATE_TTL_MS,
} from './deep-link'

function callbackURLForState(state: string): string {
  return `${API_BASE_URL}/auth/callback?desktop=${encodeURIComponent(state)}`
}

async function pkceChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
  return btoa(String.fromCharCode(...new Uint8Array(digest)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

async function registerDesktopAuthFlow(state: string, ttlMs: number): Promise<string> {
  const verifier = getPendingOAuthVerifier(state)
  if (!verifier) throw new Error('Desktop authentication proof is unavailable')
  const response = await fetch(`${API_BASE_URL}/api/auth/desktop-handoff/initiate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      state,
      challenge: await pkceChallenge(verifier),
      ttlSeconds: ttlMs >= 60 * 60 * 1000 ? 3_600 : 600,
    }),
  })
  if (!response.ok) throw new Error('Unable to initiate desktop authentication')
  return callbackURLForState(state)
}

export async function createDesktopAuthCallbackURL(
  stateTtlMs = OAUTH_STATE_TTL_MS,
): Promise<string> {
  const state = generateOAuthState(stateTtlMs)
  try {
    return await registerDesktopAuthFlow(state, stateTtlMs)
  } catch (error) {
    clearOAuthState()
    throw error
  }
}

/** Return null while another desktop OAuth or verification flow is pending. */
export async function claimDesktopAuthCallbackURL(
  stateTtlMs = OAUTH_STATE_TTL_MS,
): Promise<string | null> {
  const state = claimOAuthState(stateTtlMs)
  if (!state) return null
  try {
    return await registerDesktopAuthFlow(state, stateTtlMs)
  } catch (error) {
    clearOAuthState()
    throw error
  }
}
