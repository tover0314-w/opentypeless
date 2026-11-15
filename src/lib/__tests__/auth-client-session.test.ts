import { beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import {
  authClient,
  requestOpenTypelessPasswordReset,
  setOpenTypelessPassword,
} from '../auth-client'
import { resetCloudSessionCoordinatorForTests } from '../cloud-session'
import { API_BASE_URL, APP_VERSION_HEADER_VALUE, CLIENT_VERSION_HEADER } from '../constants'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

describe('auth client cloud session transport', () => {
  beforeEach(() => {
    localStorage.clear()
    resetCloudSessionCoordinatorForTests()
    vi.clearAllMocks()
    vi.mocked(invoke).mockReset()
    vi.mocked(invoke).mockImplementation((command) =>
      Promise.resolve(command === 'get_session_token' ? 'vault-token' : undefined),
    )
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
      }),
    )
  })

  it('adds the system-vault-backed bearer without persisting it in localStorage', async () => {
    await requestOpenTypelessPasswordReset('person@example.com', 'en')

    const [, init] = vi.mocked(fetch).mock.calls[0]!
    const headers = new Headers(init?.headers)
    expect(headers.get('Authorization')).toBe('Bearer vault-token')
    expect(headers.get(CLIENT_VERSION_HEADER)).toBe(APP_VERSION_HEADER_VALUE)
    expect(localStorage.getItem('session_token')).toBeNull()
  })

  it('uses an explicit authentication bearer without reading the previous session vault', async () => {
    vi.mocked(invoke).mockRejectedValueOnce(new Error('Synthetic previous vault unavailable'))
    vi.mocked(fetch).mockImplementation(async () =>
      Response.json({
        user: { id: 'synthetic-user', email: 'person@example.test', emailVerified: true },
        session: { id: 'synthetic-session' },
      }),
    )
    const result = await authClient.getSession({
      fetchOptions: { headers: { Authorization: 'Bearer synthetic-candidate-token' } },
    })
    expect(result.data?.user.id).toBe('synthetic-user')
    expect(invoke).not.toHaveBeenCalled()
    expect(new Headers(vi.mocked(fetch).mock.calls[0][1]?.headers).get('Authorization')).toBe(
      'Bearer synthetic-candidate-token',
    )
  })

  it('rejects an already cancelled explicit-bearer lookup without reading credentials', async () => {
    const controller = new AbortController()
    const cancellation = new DOMException('Synthetic cancelled lookup', 'AbortError')
    controller.abort(cancellation)
    const outcome = await authClient
      .getSession({
        fetchOptions: {
          signal: controller.signal,
          headers: { Authorization: 'Bearer synthetic-candidate-token' },
        },
      })
      .catch((error: unknown) => error)
    expect(outcome).toBe(cancellation)
    expect(invoke).not.toHaveBeenCalled()
    expect(fetch).not.toHaveBeenCalled()
  })

  it('does not dispatch a cancelled client session lookup after credentials arrive', async () => {
    let finishCredentials!: (token: string) => void
    vi.mocked(invoke).mockImplementationOnce(
      () =>
        new Promise<string>((resolve) => {
          finishCredentials = resolve
        }),
    )
    const controller = new AbortController()
    let completed = false
    const pending = authClient
      .getSession({ fetchOptions: { signal: controller.signal } })
      .catch((error: unknown) => error)
      .finally(() => {
        completed = true
      })
    await vi.waitFor(() => expect(invoke).toHaveBeenCalledWith('get_session_token'))
    controller.abort()
    try {
      await vi.waitFor(() => expect(completed).toBe(true))
    } finally {
      finishCredentials('synthetic-late-token')
    }
    await pending
    expect(fetch).not.toHaveBeenCalled()
  })

  it('does not cancel shared credential recovery when one of two session lookups is cancelled', async () => {
    let finishCredentials!: (token: string) => void
    vi.mocked(invoke).mockImplementationOnce(
      () =>
        new Promise<string>((resolve) => {
          finishCredentials = resolve
        }),
    )
    vi.mocked(fetch).mockImplementation(async () =>
      Response.json({
        user: { id: 'synthetic-user', email: 'person@example.test', emailVerified: true },
        session: { id: 'synthetic-session' },
      }),
    )
    const controller = new AbortController()
    const cancelled = authClient
      .getSession({ fetchOptions: { signal: controller.signal } })
      .catch((error: unknown) => error)
    const current = authClient.getSession()
    try {
      await vi.waitFor(() => expect(invoke).toHaveBeenCalledWith('get_session_token'))
      controller.abort()
      await cancelled
      expect(fetch).not.toHaveBeenCalled()
      finishCredentials('synthetic-shared-token')
      const result = await current
      expect(result.data?.user.id).toBe('synthetic-user')
      expect(fetch).toHaveBeenCalledOnce()
      expect(
        vi.mocked(invoke).mock.calls.filter(([command]) => command === 'get_session_token'),
      ).toHaveLength(1)
      expect(new Headers(vi.mocked(fetch).mock.calls[0][1]?.headers).get('Authorization')).toBe(
        'Bearer synthetic-shared-token',
      )
    } finally {
      finishCredentials?.('synthetic-cleanup-token')
      await Promise.all([cancelled, current])
    }
  })

  it('releases an unused successful password response without delaying completion', async () => {
    let finishCancellation!: () => void
    const cancel = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishCancellation = resolve
        }),
    )
    vi.mocked(fetch).mockResolvedValueOnce(new Response(new ReadableStream<Uint8Array>({ cancel })))
    try {
      await expect(setOpenTypelessPassword('synthetic-password')).resolves.toBeUndefined()
      expect(cancel).toHaveBeenCalledOnce()
    } finally {
      finishCancellation?.()
    }
  })

  it.each(['reset', 'password'])(
    'does not send a timed-out %s request when credentials arrive late',
    async (action) => {
      vi.useFakeTimers()
      let finishCredentials!: (token: string) => void
      vi.mocked(invoke).mockImplementationOnce(
        () =>
          new Promise<string>((resolve) => {
            finishCredentials = resolve
          }),
      )
      const pending = (
        action === 'reset'
          ? requestOpenTypelessPasswordReset('person@example.test', 'en')
          : setOpenTypelessPassword('synthetic-password')
      ).catch((error: unknown) => error)
      try {
        await vi.advanceTimersByTimeAsync(30_000)
        expect(await pending).toMatchObject({ message: 'Authentication request timed out' })
        finishCredentials('synthetic-late-token')
        await vi.advanceTimersByTimeAsync(0)
        expect(fetch).not.toHaveBeenCalled()
        expect(vi.getTimerCount()).toBe(0)
      } finally {
        finishCredentials?.('synthetic-cleanup-token')
        await pending
        vi.useRealTimers()
      }
    },
  )

  it.each(['reset', 'password'])(
    'ends a stalled %s request before response headers arrive',
    async (action) => {
      vi.useFakeTimers()
      let finish!: (response: Response) => void
      vi.mocked(fetch).mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            finish = resolve
          }),
      )
      let completed = false
      const pending = (
        action === 'reset'
          ? requestOpenTypelessPasswordReset('person@example.test', 'en')
          : setOpenTypelessPassword('synthetic-password')
      )
        .catch((error: unknown) => error)
        .finally(() => {
          completed = true
        })
      try {
        await vi.advanceTimersByTimeAsync(30_000)
        expect(completed).toBe(true)
        expect(await pending).toMatchObject({ message: 'Authentication request timed out' })
        expect(vi.mocked(fetch).mock.calls[0][1]?.signal?.aborted).toBe(true)
      } finally {
        finish?.(Response.json({}))
        await pending
        vi.useRealTimers()
      }
    },
  )

  it('cancels a late response body without waiting for stream cleanup', async () => {
    vi.useFakeTimers()
    let finish!: (response: Response) => void
    let finishCancellation!: () => void
    const cancel = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishCancellation = resolve
        }),
    )
    vi.mocked(fetch).mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve
        }),
    )
    const pending = setOpenTypelessPassword('synthetic-password').catch((error: unknown) => error)
    try {
      await vi.advanceTimersByTimeAsync(30_000)
      expect(await pending).toMatchObject({ message: 'Authentication request timed out' })
      finish(new Response(new ReadableStream<Uint8Array>({ cancel })))
      await vi.advanceTimersByTimeAsync(0)
      expect(cancel).toHaveBeenCalledOnce()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      finishCancellation?.()
      finish?.(Response.json({}))
      await pending
      vi.useRealTimers()
    }
  })

  it.each([42, {}, ['private-detail'], true])(
    'rejects a malformed error message without coercing it: %j',
    async (message) => {
      vi.mocked(fetch).mockResolvedValueOnce(
        Response.json({ error: { message } }, { status: 400, statusText: 'Bad Request' }),
      )
      await expect(requestOpenTypelessPasswordReset('person@example.com', 'en')).rejects.toThrow(
        'Bad Request',
      )
    },
  )

  it('preserves a valid server error message', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      Response.json({ error: { message: 'Synthetic request rejected' } }, { status: 400 }),
    )
    await expect(requestOpenTypelessPasswordReset('person@example.com', 'en')).rejects.toThrow(
      'Synthetic request rejected',
    )
  })

  it('does not parse an oversized authentication error body', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      Response.json({ error: 's'.repeat(64 * 1024) }, { status: 400, statusText: 'Bad Request' }),
    )
    await expect(setOpenTypelessPassword('synthetic-password')).rejects.toThrow('Bad Request')
  })

  it('accepts an error body exactly at the byte limit', async () => {
    const error = 's'.repeat(64 * 1024 - new TextEncoder().encode('{"error":""}').byteLength)
    vi.mocked(fetch).mockResolvedValueOnce(Response.json({ error }, { status: 400 }))
    const result = await setOpenTypelessPassword('synthetic-password').catch(
      (failure: unknown) => failure,
    )
    expect(result).toBeInstanceOf(Error)
    expect((result as Error).message.length).toBe(error.length)
  })

  it('counts multibyte text by bytes rather than characters', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      Response.json({ error: '字'.repeat(22000) }, { status: 400, statusText: 'Bad Request' }),
    )
    await expect(setOpenTypelessPassword('synthetic-password')).rejects.toThrow('Bad Request')
  })

  it('cancels a streamed error body once it exceeds the byte limit', async () => {
    const cancel = vi.fn()
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(`{"error":"${'s'.repeat(64 * 1024)}`))
      },
      cancel,
    })
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(stream, { status: 400, statusText: 'Bad Request' }),
    )
    await expect(setOpenTypelessPassword('synthetic-password')).rejects.toThrow('Bad Request')
    expect(cancel).toHaveBeenCalledOnce()
  })

  it('returns the error fallback without waiting for a stalled stream cancellation', async () => {
    let finishCancellation: (() => void) | undefined
    const cancel = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishCancellation = resolve
        }),
    )
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new Uint8Array(64 * 1024 + 1))
      },
      cancel,
    })
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(stream, { status: 400, statusText: 'Bad Request' }),
    )
    let completed = false
    const pending = setOpenTypelessPassword('synthetic-password').catch(() => {
      completed = true
    })
    try {
      await new Promise((resolve) => setTimeout(resolve, 0))
      expect(cancel).toHaveBeenCalledOnce()
      expect(completed).toBe(true)
    } finally {
      finishCancellation?.()
      await pending
    }
  })

  it('ends a stalled error body after thirty seconds without waiting for cancellation', async () => {
    vi.useFakeTimers()
    let finishCancellation: (() => void) | undefined
    const cancel = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishCancellation = resolve
        }),
    )
    const stream = new ReadableStream<Uint8Array>({ cancel })
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(stream, { status: 503, statusText: 'Service Unavailable' }),
    )
    let message: string | null = null
    const pending = setOpenTypelessPassword('synthetic-password').catch((error: Error) => {
      message = error.message
    })
    try {
      await vi.advanceTimersByTimeAsync(29999)
      expect(message).toBeNull()
      await vi.advanceTimersByTimeAsync(1)
      expect(message).toBe('Service Unavailable')
      expect(cancel).toHaveBeenCalledOnce()
    } finally {
      finishCancellation?.()
      await vi.advanceTimersByTimeAsync(0)
      vi.useRealTimers()
      void pending
    }
  })

  it('does not reset the total deadline when error body chunks arrive slowly', async () => {
    vi.useFakeTimers()
    let streamController: ReadableStreamDefaultController<Uint8Array> | undefined
    const cancel = vi.fn()
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        streamController = controller
      },
      cancel,
    })
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(stream, { status: 503, statusText: 'Service Unavailable' }),
    )
    let message: string | null = null
    const pending = setOpenTypelessPassword('synthetic-password').catch((error: Error) => {
      message = error.message
    })
    try {
      await vi.advanceTimersByTimeAsync(10000)
      streamController?.enqueue(new TextEncoder().encode('{"error":"'))
      await vi.advanceTimersByTimeAsync(10000)
      streamController?.enqueue(new TextEncoder().encode('still waiting'))
      await vi.advanceTimersByTimeAsync(9999)
      expect(message).toBeNull()
      await vi.advanceTimersByTimeAsync(1)
      expect(message).toBe('Service Unavailable')
      expect(cancel).toHaveBeenCalledOnce()
      expect(vi.getTimerCount()).toBe(0)
      await pending
    } finally {
      vi.useRealTimers()
    }
  })

  it('clears the deadline after a complete error body is parsed', async () => {
    vi.useFakeTimers()
    try {
      vi.mocked(fetch).mockResolvedValueOnce(
        Response.json({ error: 'Synthetic request rejected' }, { status: 400 }),
      )
      await expect(setOpenTypelessPassword('synthetic-password')).rejects.toThrow(
        'Synthetic request rejected',
      )
      expect(vi.getTimerCount()).toBe(0)
      await vi.advanceTimersByTimeAsync(30000)
    } finally {
      vi.useRealTimers()
    }
  })

  it('sends password setup through the authenticated custom endpoint', async () => {
    await setOpenTypelessPassword('synthetic-password')
    const [url, init] = vi.mocked(fetch).mock.calls[0]!
    expect(String(url)).toBe(`${API_BASE_URL}/api/opentypeless/auth/set-password`)
    expect(init?.method).toBe('POST')
    expect(init?.credentials).toBe('include')
    expect(JSON.parse(String(init?.body))).toEqual({ newPassword: 'synthetic-password' })
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer vault-token')
  })

  it.each(['string', 'object', 'statusText'])(
    'removes submitted passwords from the %s error response',
    async (source) => {
      const password =
        source === 'statusText' ? 'synthetic+$private-password' : 'synthetic+$私密-password'
      const message = `Rejected ${password}; repeated ${password}`
      vi.mocked(fetch).mockResolvedValueOnce(
        Response.json(
          { error: source === 'string' ? message : source === 'object' ? { message } : null },
          { status: 400, statusText: source === 'statusText' ? message : 'Bad Request' },
        ),
      )
      await expect(setOpenTypelessPassword(password)).rejects.toThrow(
        'Rejected [redacted]; repeated [redacted]',
      )
    },
  )

  it('removes the submitted password from a transport rejection', async () => {
    const password = 'synthetic+$私密-password'
    vi.mocked(fetch).mockRejectedValueOnce(new Error(`Network failure for ${password}`))
    await expect(setOpenTypelessPassword(password)).rejects.toThrow(
      'Network failure for [redacted]',
    )
  })

  it('preserves transport rejection identity for password reset', async () => {
    const failure = new Error('Synthetic network unavailable')
    vi.mocked(fetch).mockRejectedValueOnce(failure)
    await expect(requestOpenTypelessPasswordReset('person@example.com', 'en')).rejects.toBe(failure)
  })

  it.each([
    { error: { message: {} }, expected: 'Bad Request' },
    { error: 'Synthetic password rejected', expected: 'Synthetic password rejected' },
    { error: { message: 'Synthetic password invalid' }, expected: 'Synthetic password invalid' },
  ])('handles password setup errors: $expected', async ({ error, expected }) => {
    vi.mocked(fetch).mockResolvedValueOnce(
      Response.json({ error }, { status: 400, statusText: 'Bad Request' }),
    )
    await expect(setOpenTypelessPassword('synthetic-password')).rejects.toThrow(expected)
  })

  it('uses the HTTP fallback when a password setup response is not JSON', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response('not-json', { status: 503, statusText: 'Service Unavailable' }),
    )
    await expect(setOpenTypelessPassword('synthetic-password')).rejects.toThrow(
      'Service Unavailable',
    )
  })

  it('uses the generic fallback when the error and HTTP status text are empty', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(Response.json({ error: '' }, { status: 400 }))
    await expect(setOpenTypelessPassword('synthetic-password')).rejects.toThrow(
      'Authentication request failed',
    )
  })

  it.each([
    { error: '   ', statusText: 'Bad Request', expected: 'Bad Request' },
    { error: { message: '\t\n' }, statusText: 'Bad Request', expected: 'Bad Request' },
    { error: '   ', statusText: '   ', expected: 'Authentication request failed' },
  ])('uses a visible fallback for blank error text: $expected', async (scenario) => {
    for (const request of [
      () => requestOpenTypelessPasswordReset('person@example.com', 'en'),
      () => setOpenTypelessPassword('synthetic-password'),
    ]) {
      vi.mocked(fetch).mockResolvedValueOnce(
        Response.json({ error: scenario.error }, { status: 400, statusText: scenario.statusText }),
      )
      await expect(request()).rejects.toThrow(scenario.expected)
    }
  })

  it('reuses one restored token without reading the vault on every request', async () => {
    await requestOpenTypelessPasswordReset('person@example.com', 'en')
    await requestOpenTypelessPasswordReset('person@example.com', 'en')

    expect(invoke).toHaveBeenCalledTimes(1)
    expect(invoke).toHaveBeenCalledWith('get_session_token')
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('adds the restored bearer to Better Auth session restoration', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          user: { id: 'user-1', email: 'person@example.com', name: 'Person' },
          session: { id: 'session-1' },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )

    const result = await authClient.getSession()

    expect(result.data?.user.id).toBe('user-1')
    expect(invoke).toHaveBeenCalledTimes(1)
    expect(invoke).toHaveBeenCalledWith('get_session_token')
    const [url, init] = vi.mocked(fetch).mock.calls[0]!
    const headers = new Headers(init?.headers)
    expect(String(url)).toBe(`${API_BASE_URL}/api/auth/get-session`)
    expect(init?.method).toBe('GET')
    expect(headers.get('Authorization')).toBe('Bearer vault-token')
    expect(headers.get(CLIENT_VERSION_HEADER)).toBe(APP_VERSION_HEADER_VALUE)
    expect(localStorage.getItem('session_token')).toBeNull()
  })
})
