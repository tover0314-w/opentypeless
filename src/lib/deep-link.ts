import { onOpenUrl } from '@tauri-apps/plugin-deep-link'
import { useAuthStore } from '../stores/authStore'
import { API_BASE_URL } from './constants'

/** Pending OAuth state for CSRF validation. */
let pendingOAuthState: string | null = null
let pendingOAuthVerifier: string | null = null
let pendingOAuthTimer: ReturnType<typeof setTimeout> | null = null
export const OAUTH_STATE_TTL_MS = 10 * 60 * 1000
export const EMAIL_VERIFICATION_STATE_TTL_MS = 60 * 60 * 1000

/** Generate and store a random state string for OAuth CSRF protection. */
export function generateOAuthState(ttlMs = OAUTH_STATE_TTL_MS): string {
  clearOAuthState()
  const state = crypto.randomUUID()
  const verifier = `${crypto.randomUUID().replace(/-/g, '')}${crypto.randomUUID().replace(/-/g, '')}`
  pendingOAuthState = state
  pendingOAuthVerifier = verifier
  pendingOAuthTimer = setTimeout(clearOAuthState, ttlMs)
  return state
}

/**
 * Claim the single desktop callback slot without replacing an active flow.
 *
 * Better Auth binds its OAuth state to one browser cookie. Replacing our
 * desktop state while that browser flow is still open can start a second
 * OAuth request, overwrite the cookie, and make the first callback fail.
 */
export function claimOAuthState(ttlMs = OAUTH_STATE_TTL_MS): string | null {
  if (pendingOAuthState) return null
  return generateOAuthState(ttlMs)
}

export function getPendingOAuthVerifier(state: string): string | null {
  if (pendingOAuthState === state && pendingOAuthVerifier) return pendingOAuthVerifier
  return null
}

/** Clear pending OAuth state (e.g. user cancelled or timed out). */
export function clearOAuthState(): void {
  pendingOAuthState = null
  pendingOAuthVerifier = null
  if (pendingOAuthTimer) {
    clearTimeout(pendingOAuthTimer)
    pendingOAuthTimer = null
  }
}

export async function initDeepLinkListener() {
  try {
    await onOpenUrl(async (urls) => {
      for (const rawUrl of urls) {
        await handleDeepLinkUrl(rawUrl)
      }
    })
  } catch {
    // Deep link plugin not available (e.g. web dev mode)
  }
}

/** Basic sanity check: token must be a non-empty alphanumeric/JWT-like string. */
function isValidToken(token: string): boolean {
  return /^[\w\-._~+/]+=*$/.test(token) && token.length >= 10 && token.length <= 4096
}

export async function handleDeepLinkUrl(rawUrl: string): Promise<boolean> {
  let url: URL
  try {
    url = new URL(rawUrl)
  } catch {
    return false
  }

  // Only accept our custom scheme
  if (url.protocol !== 'opentypeless:') return false

  const path = url.hostname ? url.hostname + url.pathname : url.pathname.replace(/^\/+/, '')
  const params = url.searchParams
  console.log('[deep-link] received:', `${url.protocol}${path}`)

  // opentypeless://auth/callback?code=xxx&state=yyy
  if (path === 'auth/callback' || path === 'auth/callback/') {
    const code = params.get('code')
    const state = params.get('state')
    const expectedState = pendingOAuthState
    const verifier = pendingOAuthVerifier

    // Reject tokens when no OAuth flow was initiated (prevents external injection)
    if (!expectedState) {
      return false
    }
    // Validate CSRF state
    if (state !== expectedState) {
      clearOAuthState()
      return false
    }
    if (!code || !verifier) return false
    try {
      const response = await fetch(`${API_BASE_URL}/api/auth/desktop-handoff/exchange`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code, verifier }),
      })
      if (!response.ok) return false
      const result = (await response.json()) as { token?: unknown }
      const token = result.token
      if (typeof token !== 'string' || !isValidToken(token)) return false
      clearOAuthState()
      await useAuthStore.getState().handleDeepLinkToken(token)
      window.location.hash = '#/account'
      return true
    } catch {
      return false
    }
  }

  // opentypeless://checkout/success
  if (path === 'checkout/success' || path === 'checkout/success/') {
    await useAuthStore.getState().refreshSubscription()
    window.location.hash = '#/upgrade'
    return true
  }

  return false
}
