import { createAuthClient } from 'better-auth/client'
import { API_BASE_URL, APP_VERSION_HEADER_VALUE, CLIENT_VERSION_HEADER } from './constants'
import { loadSessionToken } from './cloud-session'

const fetchWithToken: typeof fetch = async (url, init) => {
  const headers = new Headers(init?.headers)
  if (!headers.has(CLIENT_VERSION_HEADER)) {
    headers.set(CLIENT_VERSION_HEADER, APP_VERSION_HEADER_VALUE)
  }
  const token = await loadSessionToken()
  if (token) {
    if (!headers.has('Authorization')) {
      headers.set('Authorization', `Bearer ${token}`)
    }
    return fetch(url, { ...init, headers })
  }
  return fetch(url, { ...init, headers })
}

async function openTypelessAuthRequest(path: string, body: unknown): Promise<void> {
  const response = await fetchWithToken(`${API_BASE_URL}${path}`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (response.ok) return

  const result = (await response.json().catch(() => null)) as {
    error?: string | { message?: string }
  } | null
  const message =
    typeof result?.error === 'string'
      ? result.error
      : (result?.error?.message ?? response.statusText)
  throw new Error(message || 'Authentication request failed')
}

export function requestOpenTypelessPasswordReset(email: string, locale: string): Promise<void> {
  return openTypelessAuthRequest('/api/opentypeless/auth/request-password-reset', { email, locale })
}

export function setOpenTypelessPassword(newPassword: string): Promise<void> {
  return openTypelessAuthRequest('/api/opentypeless/auth/set-password', { newPassword })
}

export const authClient = createAuthClient({
  baseURL: API_BASE_URL,
  fetchOptions: {
    customFetchImpl: fetchWithToken,
  },
})
