import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  deepLinkHandler: null as null | ((urls: string[]) => Promise<void> | void),
  handleDeepLinkToken: vi.fn(),
  refreshSubscription: vi.fn(),
  fetch: vi.fn(),
  user: null as { id: string } | null,
}))

vi.mock('@tauri-apps/plugin-deep-link', () => ({
  onOpenUrl: vi.fn(async (handler: (urls: string[]) => Promise<void> | void) => {
    mocks.deepLinkHandler = handler
    return () => {}
  }),
}))

vi.mock('../../stores/authStore', () => ({
  useAuthStore: {
    getState: () => ({
      handleDeepLinkToken: mocks.handleDeepLinkToken,
      refreshSubscription: mocks.refreshSubscription,
      user: mocks.user,
    }),
  },
}))

describe('deep-link OAuth callback', () => {
  it('aborts account validation when its last native listener is released', async () => {
    const module = await import('../deep-link')
    let finishValidation!: () => void
    mocks.handleDeepLinkToken.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishValidation = resolve
        }),
    )
    const stop = module.initDeepLinkListener()
    await vi.waitFor(() => expect(mocks.deepLinkHandler).toBeTypeOf('function'))
    const state = module.generateOAuthState()
    const callback = mocks.deepLinkHandler!([
      `opentypeless://auth/callback?code=synthetic-code&state=${state}`,
    ])
    await vi.waitFor(() => expect(finishValidation).toBeTypeOf('function'))
    const signal = mocks.handleDeepLinkToken.mock.calls[0][2] as AbortSignal
    stop()
    const aborted = signal.aborted
    finishValidation()
    await callback
    expect(aborted).toBe(true)
  })

  it('does not authenticate a shared callback after all owners are released', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    const callback = `opentypeless://auth/callback?code=synthetic-code&state=${state}`
    let finish!: (response: Response) => void
    mocks.fetch.mockReturnValueOnce(
      new Promise<Response>((resolve) => {
        finish = resolve
      }),
    )
    let firstActive = true
    let secondActive = true
    const first = module.handleDeepLinkUrl(callback, () => firstActive)
    const second = module.handleDeepLinkUrl(callback, () => secondActive)
    firstActive = false
    secondActive = false
    finish(Response.json({ token: 'valid-token-12345' }))
    await expect(Promise.all([first, second])).resolves.toEqual([false, false])
    expect(mocks.fetch).toHaveBeenCalledOnce()
    expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
    expect(window.location.hash).toBe('')
  })

  it('lets a current listener complete a shared callback after the original owner is released', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    const callback = `opentypeless://auth/callback?code=synthetic-code&state=${state}`
    let finish!: (response: Response) => void
    mocks.fetch.mockReturnValueOnce(
      new Promise<Response>((resolve) => {
        finish = resolve
      }),
    )
    let originalActive = true
    const original = module.handleDeepLinkUrl(callback, () => originalActive)
    originalActive = false
    const current = module.handleDeepLinkUrl(callback, () => true)
    finish(Response.json({ token: 'valid-token-12345' }))
    await expect(Promise.all([original, current])).resolves.toEqual([false, true])
    expect(mocks.fetch).toHaveBeenCalledOnce()
    expect(mocks.handleDeepLinkToken).toHaveBeenCalledExactlyOnceWith(
      'valid-token-12345',
      expect.any(Function),
      expect.any(AbortSignal),
    )
    expect(window.location.hash).toBe('#/account')
  })

  it('does not authenticate an exchange after its listener owner is released', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    let finish!: (response: Response) => void
    mocks.fetch.mockReturnValueOnce(
      new Promise<Response>((resolve) => {
        finish = resolve
      }),
    )
    const stop = module.initDeepLinkListener()
    await Promise.resolve()
    const callback = mocks.deepLinkHandler!
    const pending = callback([`opentypeless://auth/callback?code=synthetic-code&state=${state}`])
    stop()
    finish(Response.json({ token: 'valid-token-12345' }))
    await pending
    expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
    expect(window.location.hash).toBe('')
  })

  it('does not navigate after its owner is released during account validation', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    let active = true
    let finish!: () => void
    mocks.handleDeepLinkToken.mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finish = resolve
      }),
    )
    const pending = module.handleDeepLinkUrl(
      `opentypeless://auth/callback?code=synthetic-code&state=${state}`,
      () => active,
    )
    await vi.waitFor(() => expect(mocks.handleDeepLinkToken).toHaveBeenCalledOnce())
    active = false
    finish()
    expect(await pending).toBe(false)
    expect(window.location.hash).toBe('')
  })

  it('rejects a declared oversized exchange before waiting for its body', async () => {
    vi.useFakeTimers()
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    const cancel = vi.fn()
    const stream = new ReadableStream<Uint8Array>({ cancel })
    mocks.fetch.mockResolvedValueOnce(
      new Response(stream, { headers: { 'Content-Length': '65537' } }),
    )
    let completed = false
    const pending = module
      .handleDeepLinkUrl(`opentypeless://auth/callback?code=synthetic-code&state=${state}`)
      .then((result) => {
        completed = true
        return result
      })
    try {
      await vi.advanceTimersByTimeAsync(0)
      expect(completed).toBe(true)
      expect(await pending).toBe(false)
      expect(cancel).toHaveBeenCalledOnce()
      expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
    } finally {
      module.clearOAuthState()
      await vi.advanceTimersByTimeAsync(30_000)
      await pending
    }
  })

  it('does not extend the exchange deadline when response chunks arrive slowly', async () => {
    vi.useFakeTimers()
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    let streamController!: ReadableStreamDefaultController<Uint8Array>
    const cancel = vi.fn()
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        streamController = controller
      },
      cancel,
    })
    mocks.fetch.mockResolvedValueOnce(new Response(stream))
    let completed = false
    const pending = module
      .handleDeepLinkUrl(`opentypeless://auth/callback?code=synthetic-code&state=${state}`)
      .then((result) => {
        completed = true
        return result
      })
    try {
      await vi.advanceTimersByTimeAsync(10000)
      streamController.enqueue(new TextEncoder().encode('{"token":"'))
      await vi.advanceTimersByTimeAsync(10000)
      streamController.enqueue(new TextEncoder().encode('valid-token'))
      await vi.advanceTimersByTimeAsync(9999)
      expect(completed).toBe(false)
      await vi.advanceTimersByTimeAsync(1)
      expect(await pending).toBe(false)
      expect(cancel).toHaveBeenCalledOnce()
      expect(stream.locked).toBe(false)
      expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
      module.clearOAuthState()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      module.clearOAuthState()
      await pending
    }
  })

  it('rejects a multibyte streamed exchange without waiting for cancellation cleanup', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    let finishCleanup!: () => void
    const cancel = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishCleanup = resolve
        }),
    )
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode('{"token":"valid-token-12345","padding":"'))
        controller.enqueue(new TextEncoder().encode('字'.repeat(11000)))
        controller.enqueue(new TextEncoder().encode('字'.repeat(11000)))
      },
      cancel,
    })
    mocks.fetch.mockResolvedValueOnce(new Response(stream))
    try {
      expect(
        await module.handleDeepLinkUrl(
          `opentypeless://auth/callback?code=synthetic-code&state=${state}`,
        ),
      ).toBe(false)
      expect(cancel).toHaveBeenCalledOnce()
      expect(stream.locked).toBe(false)
      expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
    } finally {
      finishCleanup?.()
    }
  })

  it.each(['', ' ', '\t\n'])('does not exchange a blank OAuth code: %j', async (code) => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    expect(
      await module.handleDeepLinkUrl(
        `opentypeless://auth/callback?code=${encodeURIComponent(code)}&state=${state}`,
      ),
    ).toBe(false)
    expect(mocks.fetch).not.toHaveBeenCalled()
    expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
    expect(module.getPendingOAuthVerifier(state)).not.toBeNull()
  })

  it.each([null, [], { token: 'valid-token-12345', error: 'Synthetic exchange rejected' }])(
    'rejects a malformed or contradictory exchange response: %j',
    async (body) => {
      const module = await import('../deep-link')
      const state = module.generateOAuthState()
      mocks.fetch.mockResolvedValueOnce(Response.json(body))
      expect(
        await module.handleDeepLinkUrl(
          `opentypeless://auth/callback?code=synthetic-code&state=${state}`,
        ),
      ).toBe(false)
      expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
      expect(window.location.hash).toBe('')
    },
  )

  it('accepts a valid exchange body exactly at the byte limit with split UTF-8', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    const source = JSON.stringify({ token: 'valid-token-12345', note: '字' })
    const encoded = new TextEncoder().encode(source)
    const body = new Uint8Array(65536).fill(32)
    body.set(encoded)
    const split = encoded.indexOf(0xe5) + 1
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(body.slice(0, split))
        controller.enqueue(body.slice(split))
        controller.close()
      },
    })
    mocks.fetch.mockResolvedValueOnce(new Response(stream))
    expect(
      await module.handleDeepLinkUrl(
        `opentypeless://auth/callback?code=synthetic-code&state=${state}`,
      ),
    ).toBe(true)
    expect(mocks.handleDeepLinkToken).toHaveBeenCalledExactlyOnceWith(
      'valid-token-12345',
      expect.any(Function),
      expect.any(AbortSignal),
    )
    expect(stream.locked).toBe(false)
  })

  it('cancels an invalid UTF-8 exchange stream without authenticating', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    const cancel = vi.fn()
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new Uint8Array([0xff, 0xfe]))
      },
      cancel,
    })
    mocks.fetch.mockResolvedValueOnce(new Response(stream))
    expect(
      await module.handleDeepLinkUrl(
        `opentypeless://auth/callback?code=synthetic-code&state=${state}`,
      ),
    ).toBe(false)
    expect(cancel).toHaveBeenCalledOnce()
    expect(stream.locked).toBe(false)
    expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
  })

  it('rejects an oversized exchange body before authenticating its token', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    mocks.fetch.mockResolvedValueOnce(
      Response.json({ token: 'valid-token-12345', padding: 's'.repeat(65536) }),
    )
    expect(
      await module.handleDeepLinkUrl(
        `opentypeless://auth/callback?code=synthetic-code&state=${state}`,
      ),
    ).toBe(false)
    expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
  })

  it('does not abort a newer exchange when an obsolete flow is cleared', async () => {
    const module = await import('../deep-link')
    const oldState = module.generateOAuthState()
    let finishOld!: (response: Response) => void
    let finishNew!: (response: Response) => void
    mocks.fetch
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            finishOld = resolve
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            finishNew = resolve
          }),
      )
    const old = module.handleDeepLinkUrl(
      `opentypeless://auth/callback?code=old-synthetic-code&state=${oldState}`,
    )
    vi.mocked(crypto.randomUUID).mockReturnValueOnce('22222222-2222-4222-8222-222222222222')
    const newState = module.generateOAuthState()
    const current = module.handleDeepLinkUrl(
      `opentypeless://auth/callback?code=new-synthetic-code&state=${newState}`,
    )
    try {
      expect(mocks.fetch.mock.calls[0][1].signal.aborted).toBe(true)
      module.clearOAuthState(oldState)
      expect(mocks.fetch.mock.calls[1][1].signal.aborted).toBe(false)
      finishOld(Response.json({ token: 'valid-old-token-12345' }))
      finishNew(Response.json({ token: 'valid-new-token-12345' }))
      await expect(Promise.all([old, current])).resolves.toEqual([false, true])
      expect(mocks.handleDeepLinkToken).toHaveBeenCalledExactlyOnceWith(
        'valid-new-token-12345',
        expect.any(Function),
        expect.any(AbortSignal),
      )
    } finally {
      finishOld(Response.json({}))
      finishNew(Response.json({}))
      await Promise.all([old, current])
    }
  })

  it.each(['headers', 'body'] as const)(
    'cancels a stalled exchange immediately while waiting for %s',
    async (stage) => {
      vi.useFakeTimers()
      const module = await import('../deep-link')
      const state = module.generateOAuthState()
      let release!: (value: unknown) => void
      const stalled = new Promise<unknown>((resolve) => {
        release = resolve
      })
      if (stage === 'headers') mocks.fetch.mockReturnValueOnce(stalled)
      else mocks.fetch.mockResolvedValueOnce(new Response(new ReadableStream<Uint8Array>()))
      let completed = false
      const pending = module
        .handleDeepLinkUrl(`opentypeless://auth/callback?code=synthetic-code&state=${state}`)
        .then((result) => {
          completed = true
          return result
        })
      try {
        await vi.advanceTimersByTimeAsync(0)
        module.clearOAuthState(state)
        await vi.advanceTimersByTimeAsync(0)
        expect(completed).toBe(true)
        expect(await pending).toBe(false)
        expect(mocks.fetch.mock.calls[0][1].signal.aborted).toBe(true)
        expect(vi.getTimerCount()).toBe(0)
        expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
      } finally {
        release(
          stage === 'headers'
            ? Response.json({ token: 'valid-token-12345' })
            : { token: 'valid-token-12345' },
        )
        await pending
      }
    },
  )

  it('releases a response arriving after the exchange deadline without logging in', async () => {
    vi.useFakeTimers()
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    let releaseResponse!: (response: Response) => void
    let finishCleanup!: () => void
    const cancel = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishCleanup = resolve
        }),
    )
    mocks.fetch.mockReturnValueOnce(
      new Promise<Response>((resolve) => {
        releaseResponse = resolve
      }),
    )
    const pending = module.handleDeepLinkUrl(
      `opentypeless://auth/callback?code=synthetic-code&state=${state}`,
    )
    try {
      await vi.advanceTimersByTimeAsync(30_000)
      expect(await pending).toBe(false)
      releaseResponse(new Response(new ReadableStream<Uint8Array>({ cancel })))
      await vi.advanceTimersByTimeAsync(0)
      expect(cancel).toHaveBeenCalledOnce()
      expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
      expect(window.location.hash).toBe('')
      module.clearOAuthState()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      finishCleanup?.()
      releaseResponse(Response.json({ token: 'valid-token-12345' }))
      await pending
    }
  })

  it('releases a rejected exchange body without waiting for cleanup', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    let finishCleanup!: () => void
    const cancel = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishCleanup = resolve
        }),
    )
    mocks.fetch.mockResolvedValueOnce(
      new Response(new ReadableStream<Uint8Array>({ cancel }), { status: 403 }),
    )
    try {
      expect(
        await module.handleDeepLinkUrl(
          `opentypeless://auth/callback?code=synthetic-code&state=${state}`,
        ),
      ).toBe(false)
      expect(cancel).toHaveBeenCalledOnce()
      expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
    } finally {
      finishCleanup?.()
    }
  })

  it.each(['headers', 'body'] as const)(
    'ends an OAuth exchange when its %s response stalls',
    async (stage) => {
      vi.useFakeTimers()
      const module = await import('../deep-link')
      const state = module.generateOAuthState()
      let release!: (value: unknown) => void
      const stalled = new Promise<unknown>((resolve) => {
        release = resolve
      })
      if (stage === 'headers') mocks.fetch.mockReturnValueOnce(stalled)
      else mocks.fetch.mockResolvedValueOnce(new Response(new ReadableStream<Uint8Array>()))
      let completed = false
      const pending = module
        .handleDeepLinkUrl(`opentypeless://auth/callback?code=synthetic-code&state=${state}`)
        .then((result) => {
          completed = true
          return result
        })
      try {
        await vi.advanceTimersByTimeAsync(30_000)
        expect(completed).toBe(true)
        expect(await pending).toBe(false)
        expect(mocks.fetch.mock.calls[0][1].signal.aborted).toBe(true)
        expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
        release(
          stage === 'headers'
            ? Response.json({ token: 'valid-token-12345' })
            : { token: 'valid-token-12345' },
        )
        await vi.advanceTimersByTimeAsync(0)
        expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
        expect(window.location.hash).toBe('')
      } finally {
        release(
          stage === 'headers'
            ? Response.json({ token: 'valid-token-12345' })
            : { token: 'valid-token-12345' },
        )
        await pending
      }
    },
  )

  it('does not log unrecognized callback paths containing sensitive text', async () => {
    const module = await import('../deep-link')
    const log = vi.spyOn(console, 'log').mockImplementation(() => {})
    try {
      expect(
        await module.handleDeepLinkUrl(
          'opentypeless://unknown/synthetic-private-token?email=private@example.test',
        ),
      ).toBe(false)
      expect(log).not.toHaveBeenCalled()
      expect(mocks.fetch).not.toHaveBeenCalled()
    } finally {
      log.mockRestore()
    }
  })

  afterEach(async () => {
    const module = await import('../deep-link')
    module.clearOAuthState()
    vi.useRealTimers()
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
  })

  it('exchanges a development callback through the cloud proxy with PKCE intact', async () => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('MODE', 'development')
    vi.stubEnv('VITE_API_BASE_URL', 'https://www.opentypeless.com')
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    const verifier = module.getPendingOAuthVerifier(state)
    const code = 'c'.repeat(43)
    expect(
      await module.handleDeepLinkUrl(`opentypeless://auth/callback?code=${code}&state=${state}`),
    ).toBe(true)
    const [url, init] = mocks.fetch.mock.calls[0]
    expect(url).toBe('/__opentypeless_cloud/api/auth/desktop-handoff/exchange')
    expect(JSON.parse(init.body)).toEqual({ code, verifier })
    expect(mocks.handleDeepLinkToken).toHaveBeenCalledWith(
      'valid-token-12345',
      expect.any(Function),
      expect.any(AbortSignal),
    )
  })

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
    mocks.user = { id: 'synthetic-user' }
  })

  it('ignores checkout callbacks when there is no signed-in user', async () => {
    mocks.user = null
    const module = await import('../deep-link')
    expect(await module.handleDeepLinkUrl('opentypeless://checkout/success')).toBe(false)
    expect(mocks.refreshSubscription).not.toHaveBeenCalled()
    expect(window.location.hash).toBe('')
  })

  it('does not navigate after the checkout callback account changes', async () => {
    let finishRefresh: (() => void) | undefined
    mocks.refreshSubscription.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishRefresh = resolve
        }),
    )
    const module = await import('../deep-link')
    const pending = module.handleDeepLinkUrl('opentypeless://checkout/success')
    mocks.user = { id: 'new-synthetic-user' }
    finishRefresh?.()
    expect(await pending).toBe(false)
    expect(window.location.hash).toBe('')
  })

  it('navigates after checkout refresh for the unchanged signed-in user', async () => {
    const module = await import('../deep-link')
    expect(await module.handleDeepLinkUrl('opentypeless://checkout/success')).toBe(true)
    expect(mocks.refreshSubscription).toHaveBeenCalledOnce()
    expect(window.location.hash).toBe('#/upgrade')
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
    expect(mocks.handleDeepLinkToken).toHaveBeenCalledWith(
      'valid-token-12345',
      expect.any(Function),
      expect.any(AbortSignal),
    )
    expect(window.location.hash).toBe('#/account')
  })

  it('does not report a failed session validation as a successful callback', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    mocks.handleDeepLinkToken.mockRejectedValueOnce(new Error('Invalid session'))
    const handled = await module.handleDeepLinkUrl(
      `opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${state}`,
    )
    expect(handled).toBe(false)
    expect(window.location.hash).toBe('')
  })

  it.each([false, true])(
    'does not navigate after cancellation during session validation (scoped: %s)',
    async (scoped) => {
      const module = await import('../deep-link')
      let finishValidation!: () => void
      mocks.handleDeepLinkToken.mockImplementationOnce(
        () =>
          new Promise<void>((resolve) => {
            finishValidation = resolve
          }),
      )
      const state = module.generateOAuthState()
      const pending = module.handleDeepLinkUrl(
        `opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${state}`,
      )
      await vi.waitFor(() => expect(mocks.handleDeepLinkToken).toHaveBeenCalledOnce())
      module.clearOAuthState(scoped ? state : undefined)
      const owner = mocks.handleDeepLinkToken.mock.calls[0][1] as () => boolean
      expect(owner()).toBe(false)
      window.location.hash = '#/history'
      finishValidation()

      await expect(pending).resolves.toBe(false)
      expect(window.location.hash).toBe('#/history')
      expect(mocks.handleDeepLinkToken).toHaveBeenCalledOnce()
    },
  )

  it.each([false, true])(
    'keeps a new flow when old session validation finishes (rejected: %s)',
    async (rejected) => {
      const module = await import('../deep-link')
      let finishValidation!: () => void
      mocks.handleDeepLinkToken.mockImplementationOnce(
        () =>
          new Promise<void>((resolve, reject) => {
            finishValidation = () => {
              if (rejected) reject(new Error('Synthetic validation failure'))
              else resolve()
            }
          }),
      )
      const oldState = module.generateOAuthState()
      const pending = module.handleDeepLinkUrl(
        `opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${oldState}`,
      )
      await vi.waitFor(() => expect(mocks.handleDeepLinkToken).toHaveBeenCalledOnce())
      const newState = module.generateOAuthState()
      const verifier = module.getPendingOAuthVerifier(newState)
      expect(newState).toBe(oldState)
      window.location.hash = '#/history'
      finishValidation()

      await expect(pending).resolves.toBe(false)
      expect(window.location.hash).toBe('#/history')
      expect(module.getPendingOAuthVerifier(newState)).toBe(verifier)
      mocks.fetch.mockResolvedValueOnce(Response.json({ token: 'valid-new-token-12345' }))
      await expect(
        module.handleDeepLinkUrl(
          `opentypeless://auth/callback?code=${'d'.repeat(43)}&state=${newState}`,
        ),
      ).resolves.toBe(true)
      expect(mocks.handleDeepLinkToken).toHaveBeenCalledTimes(2)
      expect(window.location.hash).toBe('#/account')
    },
  )

  it('rejects an old exchange even when a new flow has identical proof strings', async () => {
    const module = await import('../deep-link')
    let finishExchange!: (response: Response) => void
    mocks.fetch.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finishExchange = resolve
        }),
    )
    const oldState = module.generateOAuthState()
    const oldVerifier = module.getPendingOAuthVerifier(oldState)
    const pending = module.handleDeepLinkUrl(
      `opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${oldState}`,
    )
    const newState = module.generateOAuthState()
    expect(newState).toBe(oldState)
    expect(module.getPendingOAuthVerifier(newState)).toBe(oldVerifier)
    finishExchange(Response.json({ token: 'valid-old-token-12345' }))

    await expect(pending).resolves.toBe(false)
    expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
    expect(module.getPendingOAuthVerifier(newState)).toBe(oldVerifier)
    expect(window.location.hash).toBe('')
  })

  it('does not navigate when the flow expires during session validation', async () => {
    const module = await import('../deep-link')
    let finishValidation!: () => void
    let validationStarted!: () => void
    const started = new Promise<void>((resolve) => {
      validationStarted = resolve
    })
    mocks.handleDeepLinkToken.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishValidation = resolve
          validationStarted()
        }),
    )
    vi.useFakeTimers()
    const state = module.generateOAuthState(1_000)
    const pending = module.handleDeepLinkUrl(
      `opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${state}`,
    )
    await started
    window.location.hash = '#/history'
    await vi.advanceTimersByTimeAsync(1_000)
    finishValidation()

    await expect(pending).resolves.toBe(false)
    expect(window.location.hash).toBe('#/history')
    expect(mocks.handleDeepLinkToken).toHaveBeenCalledOnce()
  })

  it('does not release a newer in-flight exchange when old validation finishes', async () => {
    const module = await import('../deep-link')
    let finishValidation!: () => void
    let finishExchange!: (response: Response) => void
    mocks.handleDeepLinkToken.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finishValidation = resolve
        }),
    )
    const oldState = module.generateOAuthState()
    const oldPending = module.handleDeepLinkUrl(
      `opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${oldState}`,
    )
    await vi.waitFor(() => expect(mocks.handleDeepLinkToken).toHaveBeenCalledOnce())
    const newState = module.generateOAuthState()
    mocks.fetch.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finishExchange = resolve
        }),
    )
    const newCallback = `opentypeless://auth/callback?code=${'d'.repeat(43)}&state=${newState}`
    const newPending = module.handleDeepLinkUrl(newCallback)
    finishValidation()
    await expect(oldPending).resolves.toBe(false)
    const duplicate = module.handleDeepLinkUrl(newCallback)
    expect(mocks.fetch).toHaveBeenCalledTimes(2)
    finishExchange(Response.json({ token: 'valid-new-token-12345' }))

    await expect(Promise.all([newPending, duplicate])).resolves.toEqual([true, true])
    expect(mocks.handleDeepLinkToken).toHaveBeenCalledTimes(2)
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
    expect(mocks.handleDeepLinkToken).toHaveBeenCalledWith(
      'valid-token-12345',
      expect.any(Function),
      expect.any(AbortSignal),
    )
  })

  it.each([
    { authority: 'synthetic-user:synthetic-pass@auth', extra: '' },
    { authority: 'auth:1234', extra: '' },
    { authority: 'auth', extra: '&code=second-code&state=second-state' },
  ])(
    'rejects ambiguous callback authority $authority and extra $extra',
    async ({ authority, extra }) => {
      const module = await import('../deep-link')
      const state = module.generateOAuthState()
      const code = 'c'.repeat(43)
      const callback = `opentypeless://${authority}/callback?code=${code}&state=${state}${extra}`

      await expect(module.handleDeepLinkUrl(callback)).resolves.toBe(false)
      expect(mocks.fetch).not.toHaveBeenCalled()
      expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
      expect(module.getPendingOAuthVerifier(state)).not.toBeNull()
    },
  )

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

  it('rejects a successful callback replay without another exchange or navigation', async () => {
    const module = await import('../deep-link')
    const state = module.generateOAuthState()
    const callback = `opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${state}`
    await expect(module.handleDeepLinkUrl(callback)).resolves.toBe(true)
    expect(module.getPendingOAuthVerifier(state)).toBeNull()
    window.location.hash = '#/history'

    await expect(module.handleDeepLinkUrl(callback)).resolves.toBe(false)
    expect(mocks.fetch).toHaveBeenCalledOnce()
    expect(mocks.handleDeepLinkToken).toHaveBeenCalledOnce()
    expect(window.location.hash).toBe('#/history')
  })

  it('rejects a callback after the pending proof expires without starting an exchange', async () => {
    const module = await import('../deep-link')
    vi.useFakeTimers()
    try {
      const state = module.generateOAuthState(1_000)
      await vi.advanceTimersByTimeAsync(1_000)
      expect(module.getPendingOAuthVerifier(state)).toBeNull()
      await expect(
        module.handleDeepLinkUrl(
          `opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${state}`,
        ),
      ).resolves.toBe(false)
      expect(mocks.fetch).not.toHaveBeenCalled()
      expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
      expect(window.location.hash).toBe('')
    } finally {
      module.clearOAuthState()
      vi.useRealTimers()
    }
  })

  it('ignores an exchange response after its proof expires in flight', async () => {
    const module = await import('../deep-link')
    let finish!: (response: Response) => void
    mocks.fetch.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve
        }),
    )
    vi.useFakeTimers()
    try {
      const state = module.generateOAuthState(1_000)
      const pending = module.handleDeepLinkUrl(
        `opentypeless://auth/callback?code=${'c'.repeat(43)}&state=${state}`,
      )
      expect(mocks.fetch).toHaveBeenCalledOnce()
      await vi.advanceTimersByTimeAsync(1_000)
      finish(Response.json({ token: 'synthetic-expired-token' }))
      await expect(pending).resolves.toBe(false)
      expect(module.getPendingOAuthVerifier(state)).toBeNull()
      expect(mocks.handleDeepLinkToken).not.toHaveBeenCalled()
      expect(window.location.hash).toBe('')
    } finally {
      module.clearOAuthState()
      vi.useRealTimers()
    }
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
