import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const transport = vi.hoisted(() => ({ fetch: null as typeof fetch | null }))

vi.mock('better-auth/client', () => ({
  createAuthClient: (options: { fetchOptions: { customFetchImpl: typeof fetch } }) => {
    transport.fetch = options.fetchOptions.customFetchImpl
    return {}
  },
}))
vi.mock('../cloud-session', () => ({
  loadSessionToken: vi.fn().mockResolvedValue('vault-token'),
}))

import '../auth-client'
import { CLIENT_VERSION_HEADER } from '../constants'

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('null')))
})

afterEach(() => vi.unstubAllGlobals())

describe('auth Request header preservation', () => {
  it('keeps headers supplied by a Request without replacing its authorization', async () => {
    const request = new Request('https://www.opentypeless.com/api/auth/get-session', {
      headers: { Authorization: 'Bearer explicit-token', 'X-Request-ID': 'request-1' },
    })
    await transport.fetch!(request)
    const [, options] = vi.mocked(fetch).mock.calls[0]!
    const headers = new Headers(options?.headers)
    expect(headers.get('Authorization')).toBe('Bearer explicit-token')
    expect(headers.get('X-Request-ID')).toBe('request-1')
    expect(headers.has(CLIENT_VERSION_HEADER)).toBe(true)
  })

  it('applies init headers over Request headers and adds the vault token when absent', async () => {
    const request = new Request('https://www.opentypeless.com/api/auth/get-session', {
      headers: { 'X-Request-ID': 'original', Accept: 'application/json' },
    })
    await transport.fetch!(request, { headers: { 'X-Request-ID': 'override' } })
    const [, options] = vi.mocked(fetch).mock.calls[0]!
    const headers = new Headers(options?.headers)
    expect(headers.get('X-Request-ID')).toBe('override')
    expect(headers.get('Accept')).toBe('application/json')
    expect(headers.get('Authorization')).toBe('Bearer vault-token')
  })
})
