import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  claimDesktopAuthCallbackURL,
  createDesktopAuthCallbackURL,
  DesktopAuthError,
} from '../desktop-auth-callback'
import { clearOAuthState } from '../deep-link'

describe('createDesktopAuthCallbackURL', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn(() => '11111111-1111-4111-8111-111111111111'),
      subtle: {
        digest: vi.fn(async () => new Uint8Array(32).fill(7).buffer),
      },
    })
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockReset().mockResolvedValue(new Response(null, { status: 201 }))
    localStorage.clear()
  })

  it('registers PKCE and keeps only state in the email verification callback', async () => {
    await expect(createDesktopAuthCallbackURL()).resolves.toBe(
      'https://www.opentypeless.com/auth/callback?desktop=11111111-1111-4111-8111-111111111111',
    )
    const [, request] = fetchMock.mock.calls[0]
    const body = JSON.parse(request.body)
    expect(body).toMatchObject({
      state: '11111111-1111-4111-8111-111111111111',
      ttlSeconds: 600,
    })
    expect(body.challenge).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(request.body).not.toContain('11111111111111111111111111111111'.repeat(2))
  })

  it('adds the selected UI language without exposing proof material', async () => {
    await expect(createDesktopAuthCallbackURL(undefined, 'zh-CN')).resolves.toBe(
      'https://www.opentypeless.com/auth/callback?desktop=11111111-1111-4111-8111-111111111111&locale=zh',
    )
  })

  it('only claims one callback URL until the pending state is cleared', async () => {
    clearOAuthState()

    await expect(claimDesktopAuthCallbackURL()).resolves.toBe(
      'https://www.opentypeless.com/auth/callback?desktop=11111111-1111-4111-8111-111111111111',
    )
    await expect(claimDesktopAuthCallbackURL()).resolves.toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    clearOAuthState()
  })

  it('clears local proof material when registration fails', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 503 }))

    await expect(createDesktopAuthCallbackURL()).rejects.toThrow(
      'Unable to initiate desktop authentication',
    )
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 201 }))
    await expect(claimDesktopAuthCallbackURL()).resolves.not.toBeNull()
  })

  it('throws a typed http error with the status when initiate returns 404', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 404 }))

    const error = await createDesktopAuthCallbackURL().catch((e: unknown) => e)
    expect(error).toBeInstanceOf(DesktopAuthError)
    expect((error as DesktopAuthError).reason).toBe('http')
    expect((error as DesktopAuthError).status).toBe(404)
  })

  it('throws a typed network error when fetch rejects', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('fetch failed'))

    const error = await createDesktopAuthCallbackURL().catch((e: unknown) => e)
    expect(error).toBeInstanceOf(DesktopAuthError)
    expect((error as DesktopAuthError).reason).toBe('network')
    expect((error as DesktopAuthError).status).toBeNull()
  })
})
