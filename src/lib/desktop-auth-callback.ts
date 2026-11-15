import { API_BASE_URL } from './constants'
import { cloudFetch } from './cloudTransport'
import {
  clearOAuthState,
  claimOAuthState,
  generateOAuthState,
  getPendingOAuthVerifier,
  OAUTH_STATE_TTL_MS,
  captureOAuthFlowOwner,
} from './deep-link'

function callbackURLForState(state: string, locale?: string): string {
  const callbackURL = new URL('/auth/callback', API_BASE_URL)
  callbackURL.searchParams.set('desktop', state)
  const normalizedLocale = locale?.trim().toLowerCase().replace(/_/g, '-').split('-')[0]
  if (normalizedLocale && /^[a-z]{2,3}$/.test(normalizedLocale)) {
    callbackURL.searchParams.set('locale', normalizedLocale)
  }
  return callbackURL.toString()
}

function normalizedAuthLocale(locale?: string): string {
  const normalized = locale?.trim().toLowerCase().replace(/_/g, '-').split('-')[0]
  return normalized && /^[a-z]{2,3}$/.test(normalized) ? normalized : 'en'
}

async function pkceChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
  return btoa(String.fromCharCode(...new Uint8Array(digest)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

export type DesktopAuthErrorReason = 'network' | 'http'

export class DesktopAuthError extends Error {
  constructor(
    public reason: DesktopAuthErrorReason,
    public status: number | null,
    message: string,
  ) {
    super(message)
    this.name = 'DesktopAuthError'
  }
}

async function registerDesktopAuthFlow(
  state: string,
  ttlMs: number,
  locale?: string,
  isCurrentFlow: () => boolean = captureOAuthFlowOwner(state),
): Promise<string> {
  const verifier = getPendingOAuthVerifier(state)
  if (!verifier) throw new Error('Desktop authentication proof is unavailable')
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  const deadline = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => {
      const error = new Error('Desktop authentication request timed out')
      controller.abort(error)
      reject(error)
    }, 30000)
  })
  let response: Response
  try {
    const registration = async () => {
      const challenge = await pkceChallenge(verifier)
      controller.signal.throwIfAborted()
      if (!isCurrentFlow() || getPendingOAuthVerifier(state) !== verifier) {
        throw new Error('Desktop authentication was cancelled or expired')
      }
      return cloudFetch(`${API_BASE_URL}/api/auth/desktop-handoff/initiate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          state,
          challenge,
          ttlSeconds: ttlMs >= 60 * 60 * 1000 ? 3_600 : 600,
        }),
        signal: controller.signal,
      }).then((result) => {
        if (controller.signal.aborted) {
          void result.body?.cancel().catch(() => {})
          controller.signal.throwIfAborted()
        }
        return result
      })
    }
    response = await Promise.race([registration(), deadline])
  } catch {
    throw new DesktopAuthError('network', null, 'Unable to reach the sign-in service')
  } finally {
    clearTimeout(timer)
  }
  void response.body?.cancel().catch(() => {})
  if (!response.ok) {
    throw new DesktopAuthError('http', response.status, 'Unable to initiate desktop authentication')
  }
  if (!isCurrentFlow() || getPendingOAuthVerifier(state) !== verifier) {
    throw new Error('Desktop authentication was cancelled or expired')
  }
  return callbackURLForState(state, locale)
}

export async function createDesktopAuthCallbackURL(
  stateTtlMs = OAUTH_STATE_TTL_MS,
  locale?: string,
): Promise<string> {
  const state = generateOAuthState(stateTtlMs)
  const isCurrentFlow = captureOAuthFlowOwner(state)
  try {
    return await registerDesktopAuthFlow(state, stateTtlMs, locale, isCurrentFlow)
  } catch (error) {
    if (isCurrentFlow()) clearOAuthState(state)
    throw error
  }
}

export type DesktopWebAuthMode = 'signup' | 'verify' | 'forgot'

/**
 * Route desktop actions that can create accounts or send email through the
 * browser page protected by Cloudflare Turnstile. Password reset does not need
 * a desktop session handoff, so it deliberately avoids creating a database row.
 */
export async function createDesktopWebAuthURL(
  mode: DesktopWebAuthMode,
  stateTtlMs = OAUTH_STATE_TTL_MS,
  locale?: string,
): Promise<string> {
  const url = new URL(`/${normalizedAuthLocale(locale)}/login`, API_BASE_URL)
  if (mode === 'forgot') {
    url.searchParams.set('mode', 'forgot')
    return url.toString()
  }

  const callbackURL = await createDesktopAuthCallbackURL(stateTtlMs, locale)
  const state = new URL(callbackURL).searchParams.get('desktop')
  if (!state) {
    throw new Error('Desktop authentication state is unavailable')
  }
  url.searchParams.set(mode === 'signup' ? 'tab' : 'mode', mode)
  url.searchParams.set('desktop', state)
  return url.toString()
}

/** Return null while another desktop OAuth or verification flow is pending. */
export async function claimDesktopAuthCallbackURL(
  stateTtlMs = OAUTH_STATE_TTL_MS,
  locale?: string,
): Promise<string | null> {
  const state = claimOAuthState(stateTtlMs)
  if (!state) return null
  const isCurrentFlow = captureOAuthFlowOwner(state)
  try {
    return await registerDesktopAuthFlow(state, stateTtlMs, locale, isCurrentFlow)
  } catch (error) {
    if (isCurrentFlow()) clearOAuthState(state)
    throw error
  }
}
