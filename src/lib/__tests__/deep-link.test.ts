import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  deepLinkHandler: null as null | ((urls: string[]) => Promise<void> | void),
  handleDeepLinkToken: vi.fn(),
  refreshSubscription: vi.fn(),
  fetch: vi.fn(),
}))

vi.mock('@tauri-apps/plugin-deep-link', () => ({
  onOpenUrl: vi.fn(async (handler: (urls: string[]) => Promise<void> | void) => {
    mocks.deepLinkHandler = handler
  }),
}))

vi.mock('../../stores/authStore', () => ({
  useAuthStore: {
    getState: () => ({
      handleDeepLinkToken: mocks.handleDeepLinkToken,
      refreshSubscription: mocks.refreshSubscription,
    }),
  },
}))

describe('deep-link OAuth callback', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn(() => '11111111-1111-4111-8111-111111111111'),
    })
    vi.stubGlobal('fetch', mocks.fetch)
    mocks.fetch.mockReset().mockResolvedValue(Response.json({ token: 'valid-token-12345' }))
    localStorage.clear()
    sessionStorage.clear()
    window.location.hash = ''
    mocks.deepLinkHandler = null
    mocks.handleDeepLinkToken.mockReset()
    mocks.refreshSubscription.mockReset()
  })

  it('rejects a callback after a reload loses the in-memory OAuth proof', async () => {
    const firstModule = await import('../deep-link')
    const state = firstModule.generateOAuthState()

    vi.resetModules()
    const secondModule = await import('../deep-link')
    await secondModule.initDeepLinkListener()
    await mocks.deepLinkHandler?.([
      `opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${state}`,
    ])

    expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
    expect(mocks.fetch).not.toHaveBeenCalled()
    expect(window.location.hash).toBe('')
  })

  it('keeps the OAuth state and verifier out of browser storage', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    const verifier = module.getPendingOAuthVerifier(state)

    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    expect(verifier).toMatch(/^[A-Za-z0-9._~-]{43,128}$/)
  })

  it('returns true when a pasted desktop callback signs the user in', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()

    const handled = await module.handleDeepLinkUrl(
      `opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${state}`,
    )

    expect(handled).toBe(true)
    expect(mocks.handleDeepLinkToken).toHaveBeenCalledWith('valid-token-12345')
    expect(window.location.hash).toBe('#/account')
  })

  it('does not replace the state of an active desktop callback flow', async () => {
    const module = await import('../deep-link')

    expect(module.claimOAuthState()).toBe('11111111-1111-4111-8111-111111111111')
    expect(module.claimOAuthState()).toBeNull()
    expect(crypto.randomUUID).toHaveBeenCalledTimes(3)
  })

  it('allows a new flow after the previous state is cleared', async () => {
    const module = await import('../deep-link')

    expect(module.claimOAuthState()).toBe('11111111-1111-4111-8111-111111111111')
    module.clearOAuthState()
    expect(module.claimOAuthState()).toBe('11111111-1111-4111-8111-111111111111')
    expect(crypto.randomUUID).toHaveBeenCalledTimes(6)
  })

  it('accepts single-slash desktop callback URLs forwarded by some systems', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()

    const handled = await module.handleDeepLinkUrl(
      `opentypeless:/auth/callback?code=${'c'.repeat(43)}&state=${state}`,
    )

    expect(handled).toBe(true)
    expect(mocks.handleDeepLinkToken).toHaveBeenCalledWith('valid-token-12345')
  })

  it('never accepts a bearer token directly from a custom-scheme URL', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()

    const handled = await module.handleDeepLinkUrl(
      `opentypeless://auth/callback?token=stolen-session-token&state=${state}`,
    )

    expect(handled).toBe(false)
    expect(mocks.fetch).not.toHaveBeenCalled()
    expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
  })

  it('keeps the local verifier private and sends it only to the HTTPS exchange', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    const url = `opentypeless://auth/callback?code=${'d'.repeat(43)}&state=${state}`

    await module.handleDeepLinkUrl(url)

    const request = mocks.fetch.mock.calls[0][1] as RequestInit
    const body = JSON.parse(request.body as string)
    expect(body.code).toBe('d'.repeat(43))
    expect(body.verifier).toMatch(/^[A-Za-z0-9._~-]{43,128}$/)
    expect(url).not.toContain(body.verifier)
  })

  it('ignores a cancelled flow callback without destroying a newer login', async () => {
    const module = await import('../deep-link')
    const oldState = module.generateOAuthState()
    module.clearOAuthState()
    vi.mocked(crypto.randomUUID).mockReturnValueOnce('22222222-2222-4222-8222-222222222222')
    const newState = module.generateOAuthState()
    const verifier = module.getPendingOAuthVerifier(newState)

    await expect(
      module.handleDeepLinkUrl(
        `opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${oldState}`,
      ),
    ).resolves.toBe(false)
    expect(mocks.fetch).not.toHaveBeenCalled()
    expect(module.getPendingOAuthVerifier(newState)).toBe(verifier)
    await expect(
      module.handleDeepLinkUrl(
        `opentypeless://auth/callback?code=${'d'.repeat(43)}&state=${newState}`,
      ),
    ).resolves.toBe(true)
    expect(mocks.handleDeepLinkToken).toHaveBeenCalledOnce()
  })

  it('ignores an exchange response arriving after a new flow starts', async () => {
    let finish!: (response: Response) => void
    mocks.fetch.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve
        }),
    )
    const module = await import('../deep-link')
    const oldState = module.generateOAuthState()
    const pending = module.handleDeepLinkUrl(
      `opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${oldState}`,
    )
    module.clearOAuthState()
    vi.mocked(crypto.randomUUID).mockReturnValueOnce('22222222-2222-4222-8222-222222222222')
    const newState = module.generateOAuthState()
    const verifier = module.getPendingOAuthVerifier(newState)
    finish(Response.json({ token: 'valid-old-token-12345' }))

    await expect(pending).resolves.toBe(false)
    expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
    expect(module.getPendingOAuthVerifier(newState)).toBe(verifier)
    expect(window.location.hash).toBe('')
  })

  it('shares one exchange when the same callback arrives twice concurrently', async () => {
    let finish!: (response: Response) => void
    mocks.fetch.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve
        }),
    )
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    const callback = `opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${state}`
    const first = module.handleDeepLinkUrl(callback)
    const second = module.handleDeepLinkUrl(callback)
    finish(Response.json({ token: 'valid-token-12345' }))

    await expect(Promise.all([first, second])).resolves.toEqual([true, true])
    expect(mocks.fetch).toHaveBeenCalledOnce()
    expect(mocks.handleDeepLinkToken).toHaveBeenCalledOnce()
  })

  it('rejects a different code while the current code is being exchanged', async () => {
    let finish!: (response: Response) => void
    mocks.fetch.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve
        }),
    )
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    const pending = module.handleDeepLinkUrl(
      `opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${state}`,
    )
    await expect(
      module.handleDeepLinkUrl(
        `opentypeless://auth/callback?code=${'d'.repeat(43)}&state=${state}`,
      ),
    ).resolves.toBe(false)
    expect(mocks.fetch).toHaveBeenCalledOnce()
    finish(Response.json({ token: 'valid-token-12345' }))
    await expect(pending).resolves.toBe(true)
  })

  it('does not clear another flow when cleanup belongs to an older state', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    const verifier = module.getPendingOAuthVerifier(state)
    module.clearOAuthState('22222222-2222-4222-8222-222222222222')
    expect(module.getPendingOAuthVerifier(state)).toBe(verifier)
  })
})
