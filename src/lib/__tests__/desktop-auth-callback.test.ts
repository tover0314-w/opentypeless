import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  claimDesktopAuthCallbackURL,
  createDesktopAuthCallbackURL,
  createDesktopWebAuthURL,
  DesktopAuthError,
} from '../desktop-auth-callback'
import { clearOAuthState, getPendingOAuthVerifier } from '../deep-link'

const actualSubtleCrypto = crypto.subtle

describe('createDesktopAuthCallbackURL', () => {
  afterEach(() => {
    clearOAuthState()
    vi.useRealTimers()
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
  })
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

  it('ends a stalled desktop proof registration without waiting for its response', async () => {
    vi.useFakeTimers()
    let release!: (response: Response) => void
    fetchMock.mockReturnValueOnce(
      new Promise<Response>((resolve) => {
        release = resolve
      }),
    )
    let completed = false
    const pending = createDesktopAuthCallbackURL()
      .catch((error: unknown) => error)
      .finally(() => {
        completed = true
      })
    try {
      await vi.advanceTimersByTimeAsync(30000)
      expect(completed).toBe(true)
      expect(await pending).toBeInstanceOf(DesktopAuthError)
      expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true)
      await expect(claimDesktopAuthCallbackURL()).resolves.not.toBeNull()
    } finally {
      release(new Response(null, { status: 201 }))
      await pending
    }
  })

  it('does not register proof after cancellation while the challenge is computing', async () => {
    let finishDigest!: (digest: ArrayBuffer) => void
    vi.mocked(crypto.subtle.digest).mockReturnValueOnce(
      new Promise<ArrayBuffer>((resolve) => {
        finishDigest = resolve
      }),
    )
    const pending = createDesktopAuthCallbackURL().catch((error: unknown) => error)
    clearOAuthState()
    finishDigest(new Uint8Array(32).fill(7).buffer)
    expect(await pending).toBeInstanceOf(Error)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it.each([201, 403])(
    'releases an unused registration response body for HTTP %s',
    async (status) => {
      let finishCleanup!: () => void
      const cancel = vi.fn(
        () =>
          new Promise<void>((resolve) => {
            finishCleanup = resolve
          }),
      )
      const stream = new ReadableStream<Uint8Array>({ cancel })
      fetchMock.mockResolvedValueOnce(new Response(stream, { status }))
      try {
        const result = await createDesktopAuthCallbackURL().catch((error: unknown) => error)
        if (status === 201) expect(typeof result).toBe('string')
        else expect(result).toBeInstanceOf(DesktopAuthError)
        expect(cancel).toHaveBeenCalledOnce()
        expect(stream.locked).toBe(false)
      } finally {
        finishCleanup?.()
      }
    },
  )

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

  it('registers the exact SHA-256 challenge using real Web Crypto without sending the verifier', async () => {
    clearOAuthState()
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn(() => '11111111-1111-4111-8111-111111111111'),
      subtle: actualSubtleCrypto,
    })
    try {
      const callback = await createDesktopAuthCallbackURL()
      expect(fetchMock).toHaveBeenCalledOnce()
      const [, request] = fetchMock.mock.calls[0]
      const body = JSON.parse(request.body)
      expect(body.challenge).toBe('4aJQf5oRFqaVLfpMcysc1RTwn54bCHF8i6T0VxgW5kU')
      const state = new URL(callback).searchParams.get('desktop')!
      const verifier = getPendingOAuthVerifier(state)
      expect(verifier).toBe('11111111111141118111111111111111'.repeat(2))
      expect(request.body).not.toContain(verifier)
      expect(callback).not.toContain(verifier)
      expect(callback).not.toContain(body.challenge)
    } finally {
      clearOAuthState()
    }
  })

  it('adds the selected UI language without exposing proof material', async () => {
    await expect(createDesktopAuthCallbackURL(undefined, 'zh-CN')).resolves.toBe(
      'https://www.opentypeless.com/auth/callback?desktop=11111111-1111-4111-8111-111111111111&locale=zh',
    )
  })

  it('initiates development handoff through the same-origin API while keeping the callback external', async () => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('MODE', 'development')
    vi.stubEnv('VITE_API_BASE_URL', 'https://www.opentypeless.com')
    clearOAuthState()
    const callback = await createDesktopAuthCallbackURL()
    expect(fetchMock.mock.calls[0][0]).toBe(
      '/__opentypeless_cloud/api/auth/desktop-handoff/initiate',
    )
    expect(callback).toMatch(/^https:\/\/www\.opentypeless\.com\/auth\/callback/)
    clearOAuthState()
  })

  it('opens signup and verification on the Turnstile-protected web page', async () => {
    await expect(createDesktopWebAuthURL('signup', undefined, 'zh-CN')).resolves.toBe(
      'https://www.opentypeless.com/zh/login?tab=signup&desktop=11111111-1111-4111-8111-111111111111',
    )
    clearOAuthState()
    await expect(createDesktopWebAuthURL('verify', undefined, 'en')).resolves.toBe(
      'https://www.opentypeless.com/en/login?mode=verify&desktop=11111111-1111-4111-8111-111111111111',
    )
  })

  it('opens password reset without consuming a desktop handoff row', async () => {
    await expect(createDesktopWebAuthURL('forgot', undefined, 'invalid-locale')).resolves.toBe(
      'https://www.opentypeless.com/en/login?mode=forgot',
    )
    expect(fetchMock).not.toHaveBeenCalled()
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

  it('does not cancel a new login when an older registration fails late', async () => {
    clearOAuthState()
    let fail!: (error: Error) => void
    fetchMock.mockImplementationOnce(
      () =>
        new Promise((_resolve, reject) => {
          fail = reject
        }),
    )
    const oldRegistration = createDesktopAuthCallbackURL().catch((error: unknown) => error)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    clearOAuthState()
    vi.mocked(crypto.randomUUID).mockReturnValueOnce('22222222-2222-4222-8222-222222222222')
    const newCallback = await claimDesktopAuthCallbackURL()
    const newState = new URL(newCallback!).searchParams.get('desktop')!
    const verifier = getPendingOAuthVerifier(newState)
    fail(new TypeError('fetch failed'))
    await expect(oldRegistration).resolves.toBeInstanceOf(DesktopAuthError)
    expect(getPendingOAuthVerifier(newState)).toBe(verifier)
    clearOAuthState()
  })

  it('does not clear a replacement flow with identical proof strings after an old failure', async () => {
    let fail!: (error: Error) => void
    fetchMock.mockReturnValueOnce(
      new Promise<Response>((_resolve, reject) => {
        fail = reject
      }),
    )
    const old = createDesktopAuthCallbackURL().catch((error: unknown) => error)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    const callback = await createDesktopAuthCallbackURL()
    const state = new URL(callback).searchParams.get('desktop')!
    const verifier = getPendingOAuthVerifier(state)
    fail(new Error('Synthetic old registration failure'))
    await old
    expect(getPendingOAuthVerifier(state)).toBe(verifier)
    expect(verifier).not.toBeNull()
  })

  it('rejects an old successful registration after an identical-proof flow replaces it', async () => {
    let finish!: (response: Response) => void
    fetchMock.mockReturnValueOnce(
      new Promise<Response>((resolve) => {
        finish = resolve
      }),
    )
    const old = createDesktopAuthCallbackURL().catch((error: unknown) => error)
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    const callback = await createDesktopAuthCallbackURL()
    const state = new URL(callback).searchParams.get('desktop')!
    const verifier = getPendingOAuthVerifier(state)
    finish(new Response(null, { status: 201 }))
    expect(await old).toBeInstanceOf(Error)
    expect(getPendingOAuthVerifier(state)).toBe(verifier)
    expect(verifier).not.toBeNull()
  })
})
