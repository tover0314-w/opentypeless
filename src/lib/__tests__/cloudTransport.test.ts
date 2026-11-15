import { afterEach, describe, expect, it, vi } from 'vitest'
import { cloudFetch, cloudRequestUrl } from '../cloudTransport'

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

describe('development cloud transport', () => {
  it('preserves authentication headers and fetch options in development', async () => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('MODE', 'development')
    vi.stubEnv('VITE_API_BASE_URL', 'https://www.opentypeless.com')
    const fetchMock = vi.fn().mockResolvedValue(new Response('null'))
    vi.stubGlobal('fetch', fetchMock)
    const options: RequestInit = {
      credentials: 'include',
      headers: { Authorization: 'Bearer test-token' },
      signal: new AbortController().signal,
    }
    await cloudFetch('https://www.opentypeless.com/api/auth/get-session', options)
    expect(fetchMock).toHaveBeenCalledWith('/__opentypeless_cloud/api/auth/get-session', options)
  })

  it('leaves the production cloud request URL intact', async () => {
    vi.stubEnv('DEV', false)
    vi.stubEnv('MODE', 'production')
    vi.stubEnv('VITE_API_BASE_URL', 'https://www.opentypeless.com')
    const fetchMock = vi.fn().mockResolvedValue(new Response('null'))
    vi.stubGlobal('fetch', fetchMock)
    await cloudFetch('https://www.opentypeless.com/api/subscription/status')
    expect(fetchMock).toHaveBeenCalledWith(
      'https://www.opentypeless.com/api/subscription/status',
      undefined,
    )
  })

  it('uses same-origin development transport only for the configured API', () => {
    expect(
      cloudRequestUrl(
        'https://www.opentypeless.com/api/auth/get-session?refresh=true',
        'https://www.opentypeless.com',
        true,
      ),
    ).toBe('/__opentypeless_cloud/api/auth/get-session?refresh=true')
    expect(
      cloudRequestUrl(
        'https://elsewhere.example/api/auth/get-session',
        'https://www.opentypeless.com',
        true,
      ),
    ).toBe('https://elsewhere.example/api/auth/get-session')
  })

  it('does not route production requests or external OAuth links through Vite', () => {
    const url = 'https://www.opentypeless.com/api/auth/get-session'
    expect(cloudRequestUrl(url, 'https://www.opentypeless.com', false)).toBe(url)
    const website = 'https://www.opentypeless.com/account'
    expect(cloudRequestUrl(website, 'https://www.opentypeless.com', true)).toBe(website)
  })

  it('keeps explicitly configured API path prefixes and query strings', () => {
    expect(
      cloudRequestUrl(
        'https://example.test/cloud/api/subscription/status',
        'https://example.test/cloud/',
        true,
      ),
    ).toBe('/__opentypeless_cloud/api/subscription/status')
    expect(
      cloudRequestUrl(
        'https://example.test/cloud-other/api/status',
        'https://example.test/cloud',
        true,
      ),
    ).toBe('https://example.test/cloud-other/api/status')
  })
})
