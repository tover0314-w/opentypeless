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
import { loadSessionToken } from '../cloud-session'

beforeEach(() => {
  vi.mocked(loadSessionToken).mockReset().mockResolvedValue('vault-token')
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('null')))
})

afterEach(() => vi.unstubAllGlobals())

describe('auth Request header preservation', () => {
  it('rejects an already cancelled Request before reading credentials', async () => {
    const controller = new AbortController()
    const reason = new Error('Synthetic Request cancellation')
    controller.abort(reason)
    const request = new Request('https://www.opentypeless.com/api/auth/get-session', {
      signal: controller.signal,
    })
    await expect(transport.fetch!(request)).rejects.toBe(reason)
    expect(loadSessionToken).not.toHaveBeenCalled()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('does not send a Request cancelled while its credentials are loading', async () => {
    let finishCredentials: ((value: string) => void) | undefined
    vi.mocked(loadSessionToken).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishCredentials = resolve
        }),
    )
    const controller = new AbortController()
    const request = new Request('https://www.opentypeless.com/api/auth/get-session', {
      signal: controller.signal,
    })
    const outcome = transport.fetch!(request).catch((error: unknown) => error)
    const reason = new Error('Synthetic waiting Request cancellation')
    controller.abort(reason)
    finishCredentials?.('synthetic-late-token')
    expect(await outcome).toBe(reason)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('lets an explicit init signal override a cancelled Request signal', async () => {
    const original = new AbortController()
    original.abort(new Error('Synthetic original cancellation'))
    const replacement = new AbortController()
    const request = new Request('https://www.opentypeless.com/api/auth/get-session', {
      signal: original.signal,
    })
    await transport.fetch!(request, { signal: replacement.signal })
    expect(vi.mocked(fetch).mock.calls[0][1]?.signal).toBe(replacement.signal)
  })

  it.each([
    'https://untrusted.example.test/api/auth/get-session',
    'http://www.opentypeless.com/api/auth/get-session',
    'https://www.opentypeless.com:444/api/auth/get-session',
    'https://user:password@www.opentypeless.com/api/auth/get-session',
  ])(
    'rejects an untrusted authentication destination before reading the vault: %s',
    async (url) => {
      await expect(transport.fetch!(url)).rejects.toThrow('Untrusted authentication destination')
      expect(loadSessionToken).not.toHaveBeenCalled()
      expect(fetch).not.toHaveBeenCalled()
    },
  )

  it('does not allow an explicit bearer or redirect option to bypass the destination policy', async () => {
    await expect(
      transport.fetch!('https://untrusted.example.test/api/auth/get-session', {
        headers: { Authorization: 'Bearer synthetic-explicit-token' },
        redirect: 'follow',
      }),
    ).rejects.toThrow('Untrusted authentication destination')
    expect(loadSessionToken).not.toHaveBeenCalled()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('rejects redirects even when the caller requests automatic following', async () => {
    await transport.fetch!('https://www.opentypeless.com/api/auth/get-session', {
      redirect: 'follow',
    })
    expect(vi.mocked(fetch).mock.calls[0][1]?.redirect).toBe('error')
  })

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
