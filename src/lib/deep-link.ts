import { onOpenUrl } from '@tauri-apps/plugin-deep-link'
import { useAuthStore } from '../stores/authStore'
import { API_BASE_URL } from './constants'
import { cloudFetch } from './cloudTransport'

/** Pending OAuth state for CSRF validation. */
let pendingOAuthState: string | null = null
let pendingOAuthVerifier: string | null = null
let pendingOAuthTimer: ReturnType<typeof setTimeout> | null = null
let pendingOAuthExchange: {
  state: string
  code: string
  result: Promise<boolean>
  owners: Set<() => boolean>
} | null = null
let pendingOAuthFlow: { state: string; cancelExchange?: () => void } | null = null
export const OAUTH_STATE_TTL_MS = 10 * 60 * 1000
export const EMAIL_VERIFICATION_STATE_TTL_MS = 60 * 60 * 1000

/** Generate and store a random state string for OAuth CSRF protection. */
export function generateOAuthState(ttlMs = OAUTH_STATE_TTL_MS): string {
  clearOAuthState()
  const state = crypto.randomUUID()
  const verifier = `${crypto.randomUUID().replace(/-/g, '')}${crypto.randomUUID().replace(/-/g, '')}`
  pendingOAuthState = state
  pendingOAuthVerifier = verifier
  const flow = { state }
  pendingOAuthFlow = flow
  pendingOAuthTimer = setTimeout(() => {
    if (pendingOAuthFlow === flow) clearOAuthState(state)
  }, ttlMs)
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

export function captureOAuthFlowOwner(state: string): () => boolean {
  const flow = pendingOAuthFlow
  return () => flow !== null && flow.state === state && pendingOAuthFlow === flow
}

/** Clear pending OAuth state (e.g. user cancelled or timed out). */
export function clearOAuthState(expectedState?: string): void {
  if (expectedState !== undefined && pendingOAuthFlow?.state !== expectedState) return
  pendingOAuthFlow?.cancelExchange?.()
  pendingOAuthFlow = null
  pendingOAuthState = null
  pendingOAuthVerifier = null
  pendingOAuthExchange = null
  if (pendingOAuthTimer) {
    clearTimeout(pendingOAuthTimer)
    pendingOAuthTimer = null
  }
}

export function initDeepLinkListener(): () => void {
  let active = true
  const ownsListener = () => active
  let unlisten: (() => void) | null = null
  const release = (stop: () => void) => {
    try {
      Promise.resolve(stop()).catch(() => {})
    } catch {
      return
    }
  }
  void onOpenUrl(async (urls) => {
    if (!active) return
    for (const rawUrl of urls) {
      if (!active) return
      try {
        await handleDeepLinkUrl(rawUrl, ownsListener)
      } catch {
        if (active) console.error('[deep-link] callback processing failed')
      }
    }
  })
    .then((stop) => {
      if (!active) release(stop)
      else unlisten = stop
    })
    .catch(() => {})
  return () => {
    if (!active) return
    active = false
    const exchange = pendingOAuthExchange
    if (
      exchange?.owners.delete(ownsListener) &&
      !Array.from(exchange.owners).some((owner) => owner())
    ) {
      clearOAuthState(exchange.state)
    }
    if (unlisten) release(unlisten)
    unlisten = null
  }
}

/** Basic sanity check: token must be a non-empty alphanumeric/JWT-like string. */
function isValidToken(token: string): boolean {
  return /^[\w\-._~+/]+=*$/.test(token) && token.length >= 10 && token.length <= 4096
}

export async function handleDeepLinkUrl(
  rawUrl: string,
  isCurrentOwner: () => boolean = () => true,
): Promise<boolean> {
  if (!isCurrentOwner()) return false
  let url: URL
  try {
    url = new URL(rawUrl)
  } catch {
    return false
  }

  // Only accept our custom scheme
  if (url.protocol !== 'opentypeless:') return false
  if (url.username || url.password || url.port) return false

  const path = url.hostname ? url.hostname + url.pathname : url.pathname.replace(/^\/+/, '')
  const params = url.searchParams

  // opentypeless://auth/callback?code=xxx&state=yyy
  if (path === 'auth/callback' || path === 'auth/callback/') {
    if (params.getAll('code').length !== 1 || params.getAll('state').length !== 1) return false
    const code = params.get('code')
    const state = params.get('state')
    const expectedState = pendingOAuthState
    const verifier = pendingOAuthVerifier
    const flow = pendingOAuthFlow

    // Reject tokens when no OAuth flow was initiated (prevents external injection)
    if (!expectedState) {
      return false
    }
    // A cancelled browser flow may arrive after a new one has started.
    // Reject it without destroying the new flow's CSRF/PKCE proof.
    if (state !== expectedState) {
      return false
    }
    if (!code?.trim() || !verifier || !flow) return false
    if (pendingOAuthExchange) {
      if (pendingOAuthExchange.state !== state || pendingOAuthExchange.code !== code) return false
      pendingOAuthExchange.owners.add(isCurrentOwner)
      const result = await pendingOAuthExchange.result
      return isCurrentOwner() && result
    }
    const owners = new Set([isCurrentOwner])
    const result = exchangeOAuthCode(code, expectedState, verifier, flow, () =>
      Array.from(owners).some((owner) => owner()),
    )
    const exchange = { state: expectedState, code, result, owners }
    pendingOAuthExchange = exchange
    try {
      const completed = await result
      return isCurrentOwner() && completed
    } finally {
      if (pendingOAuthExchange === exchange) pendingOAuthExchange = null
    }
  }

  // opentypeless://checkout/success
  if (path === 'checkout/success' || path === 'checkout/success/') {
    const user = useAuthStore.getState().user
    if (!user) return false
    await useAuthStore.getState().refreshSubscription()
    if (!isCurrentOwner() || useAuthStore.getState().user !== user) return false
    window.location.hash = '#/upgrade'
    return true
  }

  return false
}

async function readOAuthExchange(response: Response, deadline: Promise<never>): Promise<unknown> {
  if (!response.body) throw new Error('Invalid OAuth exchange response')
  const declaredLength = response.headers.get('Content-Length')
  if (declaredLength && /^\d+$/.test(declaredLength) && Number(declaredLength) > 65536) {
    void response.body.cancel().catch(() => {})
    throw new Error('OAuth exchange response too large')
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let bytes = 0
  let text = ''
  try {
    while (true) {
      const chunk = await Promise.race([reader.read(), deadline])
      if (chunk.done) break
      bytes += chunk.value.byteLength
      if (bytes > 65536) throw new Error('OAuth exchange response too large')
      text += decoder.decode(chunk.value, { stream: true })
    }
    text += decoder.decode()
    return JSON.parse(text)
  } catch (error) {
    void reader.cancel().catch(() => {})
    throw error
  } finally {
    reader.releaseLock()
  }
}

async function exchangeOAuthCode(
  code: string,
  state: string,
  verifier: string,
  flow: { state: string; cancelExchange?: () => void },
  isCurrentOwner: () => boolean,
): Promise<boolean> {
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  const deadline = new Promise<never>((_resolve, reject) => {
    flow.cancelExchange = () => {
      const error = new Error('OAuth exchange cancelled')
      controller.abort(error)
      reject(error)
    }
    timer = setTimeout(() => {
      const error = new Error('OAuth exchange timed out')
      controller.abort(error)
      reject(error)
    }, 30_000)
  })
  try {
    const response = await Promise.race([
      cloudFetch(`${API_BASE_URL}/api/auth/desktop-handoff/exchange`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code, verifier }),
        signal: controller.signal,
      }).then((response) => {
        if (controller.signal.aborted) {
          void response.body?.cancel().catch(() => {})
          controller.signal.throwIfAborted()
        }
        return response
      }),
      deadline,
    ])
    if (!response.ok) {
      void response.body?.cancel().catch(() => {})
      return false
    }
    const result = await readOAuthExchange(response, deadline)
    controller.signal.throwIfAborted()
    clearTimeout(timer)
    flow.cancelExchange = () => controller.abort(new Error('OAuth validation cancelled'))
    if (result === null || typeof result !== 'object' || Array.isArray(result) || 'error' in result)
      return false
    if (!('token' in result)) return false
    const token = result.token
    if (typeof token !== 'string' || !isValidToken(token)) return false
    // Ignore an exchange that finished after cancellation, expiry, or a new login.
    if (
      !isCurrentOwner() ||
      pendingOAuthFlow !== flow ||
      pendingOAuthState !== state ||
      pendingOAuthVerifier !== verifier
    )
      return false
    pendingOAuthState = null
    pendingOAuthVerifier = null
    await useAuthStore
      .getState()
      .handleDeepLinkToken(
        token,
        () => isCurrentOwner() && pendingOAuthFlow === flow,
        controller.signal,
      )
    if (!isCurrentOwner() || pendingOAuthFlow !== flow) return false
    window.location.hash = '#/account'
    return true
  } catch {
    return false
  } finally {
    clearTimeout(timer)
    delete flow.cancelExchange
    if (pendingOAuthFlow === flow && pendingOAuthState === null) clearOAuthState(state)
  }
}
