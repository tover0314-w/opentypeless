import { createAuthClient } from 'better-auth/client'
import { API_BASE_URL, APP_VERSION_HEADER_VALUE, CLIENT_VERSION_HEADER } from './constants'
import { loadSessionWithSignal } from './load-session-with-signal'
import { cloudFetch } from './cloudTransport'

const fetchWithToken: typeof fetch = async (url, init) => {
  const destination = new URL(url instanceof Request ? url.url : String(url))
  if (
    destination.origin !== new URL(API_BASE_URL).origin ||
    destination.username ||
    destination.password
  ) {
    throw new Error('Untrusted authentication destination')
  }
  const headers = new Headers(url instanceof Request ? url.headers : undefined)
  new Headers(init?.headers).forEach((value, name) => headers.set(name, value))
  if (!headers.has(CLIENT_VERSION_HEADER)) {
    headers.set(CLIENT_VERSION_HEADER, APP_VERSION_HEADER_VALUE)
  }
  const signal =
    init?.signal !== undefined ? init.signal : url instanceof Request ? url.signal : undefined
  signal?.throwIfAborted()
  if (headers.has('Authorization')) {
    return cloudFetch(url, { ...init, signal, headers, redirect: 'error' })
  }
  const token = await loadSessionWithSignal(signal)
  signal?.throwIfAborted()
  if (token) {
    if (!headers.has('Authorization')) {
      headers.set('Authorization', `Bearer ${token}`)
    }
    return cloudFetch(url, { ...init, signal, headers, redirect: 'error' })
  }
  return cloudFetch(url, { ...init, signal, headers, redirect: 'error' })
}

async function readAuthError(response: Response): Promise<unknown> {
  if (!response.body) return null
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let timer: ReturnType<typeof setTimeout> | undefined
  const deadline = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => reject(new Error('Authentication response timed out')), 30_000)
  })
  let bytes = 0
  let text = ''
  try {
    while (true) {
      const chunk = await Promise.race([reader.read(), deadline])
      if (chunk.done) break
      bytes += chunk.value.byteLength
      if (bytes > 64 * 1024) {
        void reader.cancel().catch(() => {})
        return null
      }
      text += decoder.decode(chunk.value, { stream: true })
    }
    text += decoder.decode()
    return JSON.parse(text)
  } catch {
    void reader.cancel().catch(() => {})
    return null
  } finally {
    clearTimeout(timer)
    reader.releaseLock()
  }
}

async function openTypelessAuthRequest(
  path: string,
  body: unknown,
  secret?: string,
): Promise<void> {
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  const deadline = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => {
      const error = new Error('Authentication request timed out')
      controller.abort(error)
      reject(error)
    }, 30_000)
  })
  let response: Response
  try {
    response = await Promise.race([
      fetchWithToken(`${API_BASE_URL}${path}`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: controller.signal,
      }).then((result) => {
        if (controller.signal.aborted) {
          void result.body?.cancel().catch(() => {})
          controller.signal.throwIfAborted()
        }
        return result
      }),
      deadline,
    ]).catch((error: unknown) => {
      if (!secret) throw error
      const message = error instanceof Error ? error.message : 'Authentication request failed'
      throw new Error(message.split(secret).join('[redacted]') || 'Authentication request failed')
    })
  } finally {
    clearTimeout(timer)
  }
  if (response.ok) {
    void response.body?.cancel().catch(() => {})
    return
  }

  const result = (await readAuthError(response)) as {
    error?: unknown
  } | null
  const error = result?.error
  const message =
    typeof error === 'string'
      ? error
      : error !== null &&
          typeof error === 'object' &&
          'message' in error &&
          typeof error.message === 'string'
        ? error.message
        : response.statusText
  const selectedMessage = message.trim() ? message : response.statusText
  const safeMessage = secret ? selectedMessage.split(secret).join('[redacted]') : selectedMessage
  throw new Error(safeMessage.trim() ? safeMessage : 'Authentication request failed')
}

export function requestOpenTypelessPasswordReset(email: string, locale: string): Promise<void> {
  return openTypelessAuthRequest('/api/opentypeless/auth/request-password-reset', { email, locale })
}

export function setOpenTypelessPassword(newPassword: string): Promise<void> {
  return openTypelessAuthRequest(
    '/api/opentypeless/auth/set-password',
    { newPassword },
    newPassword,
  )
}

export const authClient = createAuthClient({
  baseURL: API_BASE_URL,
  fetchOptions: {
    customFetchImpl: fetchWithToken,
  },
})
