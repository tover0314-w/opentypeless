import { onOpenUrl } from '@tauri-apps/plugin-deep-link'
import { useAuthStore } from '../stores/authStore'
import { API_BASE_URL } from './constants'

/** Pending OAuth state for CSRF validation. */
let pendingOAuthState: string | null = null
let pendingOAuthVerifier: string | null = null
let pendingOAuthTimer: ReturnType<typeof setTimeout> | null = null
const OAUTH_STATE_STORAGE_KEY = 'opentypeless.pendingOAuthState'
export const OAUTH_STATE_TTL_MS = 10 * 60 * 1000
export const EMAIL_VERIFICATION_STATE_TTL_MS = 60 * 60 * 1000

function persistOAuthState(state: string, verifier: string, ttlMs: number): void {
  try {
    localStorage.setItem(
      OAUTH_STATE_STORAGE_KEY,
      JSON.stringify({ state, verifier, expiresAt: Date.now() + ttlMs }),
    )
  } catch {
    // localStorage may be unavailable in some webview/test contexts.
  }
}

interface PendingOAuthFlow {
  state: string
  verifier: string
}

function loadPersistedOAuthFlow(): PendingOAuthFlow | null {
  try {
    const raw = localStorage.getItem(OAUTH_STATE_STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as {
      state?: unknown
      verifier?: unknown
      expiresAt?: unknown
    }
    if (
      typeof parsed.state !== 'string' ||
      typeof parsed.verifier !== 'string' ||
      typeof parsed.expiresAt !== 'number'
    ) {
      localStorage.removeItem(OAUTH_STATE_STORAGE_KEY)
      return null
    }
    if (Date.now() > parsed.expiresAt) {
      localStorage.removeItem(OAUTH_STATE_STORAGE_KEY)
      return null
    }
    return { state: parsed.state, verifier: parsed.verifier }
  } catch {
    return null
  }
}

/** Generate and store a random state string for OAuth CSRF protection. */
export function generateOAuthState(ttlMs = OAUTH_STATE_TTL_MS): string {
  clearOAuthState()
  const state = crypto.randomUUID()
  const verifier = `${crypto.randomUUID().replace(/-/g, '')}${crypto.randomUUID().replace(/-/g, '')}`
  pendingOAuthState = state
  pendingOAuthVerifier = verifier
  persistOAuthState(state, verifier, ttlMs)
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
  if (pendingOAuthState ?? loadPersistedOAuthFlow()?.state) return null
  return generateOAuthState(ttlMs)
}

export function getPendingOAuthVerifier(state: string): string | null {
  if (pendingOAuthState === state && pendingOAuthVerifier) return pendingOAuthVerifier
  const persisted = loadPersistedOAuthFlow()
  return persisted?.state === state ? persisted.verifier : null
}

/** Clear pending OAuth state (e.g. user cancelled or timed out). */
export function clearOAuthState(): void {
  pendingOAuthState = null
  pendingOAuthVerifier = null
  try {
    localStorage.removeItem(OAUTH_STATE_STORAGE_KEY)
  } catch {
    // localStorage may be unavailable in some webview/test contexts.
  }
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
    const persisted = loadPersistedOAuthFlow()
    const expectedState = pendingOAuthState ?? persisted?.state ?? null
    const verifier =
      pendingOAuthState === expectedState ? pendingOAuthVerifier : (persisted?.verifier ?? null)

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
