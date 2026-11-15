import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearMocks, mockIPC } from '@tauri-apps/api/mocks'
import { getSubscriptionStatus } from '../api'
import {
  clearSessionTokenFromMemory,
  loadSessionToken,
  markCloudSessionAuthenticated,
  persistSessionToken,
  registerCloudSessionInvalidation,
  resetCloudSessionCoordinatorForTests,
} from '../cloud-session'

beforeEach(() => {
  resetCloudSessionCoordinatorForTests()
  mockIPC(() => undefined)
})

afterEach(() => {
  clearMocks()
  vi.unstubAllGlobals()
  resetCloudSessionCoordinatorForTests()
})

describe('current cloud request account ownership gap', () => {
  it('does not invalidate a new session for a request started during its pending write', async () => {
    await persistSessionToken('synthetic-old-session')
    let finishWrite!: () => void
    mockIPC((command) => {
      if (command === 'set_session_token') {
        return new Promise<void>((resolve) => {
          finishWrite = resolve
        })
      }
    })
    const login = persistSessionToken('synthetic-new-session')
    await vi.waitFor(() => expect(finishWrite).toBeTypeOf('function'))
    let respond!: (response: Response) => void
    const transport = vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          respond = resolve
        }),
    )
    vi.stubGlobal('fetch', transport)
    const invalidation = vi.fn(async () => clearSessionTokenFromMemory())
    registerCloudSessionInvalidation(invalidation)
    const request = getSubscriptionStatus().catch((error: unknown) => error)
    await vi.waitFor(() => expect(transport).toHaveBeenCalledOnce())
    finishWrite()
    await login
    markCloudSessionAuthenticated()
    respond(
      Response.json(
        { error: { code: 'AUTH_SESSION_INVALID', message: 'Session expired' } },
        { status: 401 },
      ),
    )
    expect(await request).toMatchObject({ status: 401 })
    expect(invalidation).not.toHaveBeenCalled()
    expect(await loadSessionToken()).toBe('synthetic-new-session')
  })

  it('retains a newer session when the old request returns a service failure', async () => {
    let respond!: (response: Response) => void
    const transport = vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          respond = resolve
        }),
    )
    vi.stubGlobal('fetch', transport)
    await persistSessionToken('synthetic-old-session')
    const invalidation = vi.fn(async () => clearSessionTokenFromMemory())
    registerCloudSessionInvalidation(invalidation)
    const request = getSubscriptionStatus().catch((error: unknown) => error)
    await vi.waitFor(() => expect(transport).toHaveBeenCalledOnce())
    await persistSessionToken('synthetic-new-session')
    markCloudSessionAuthenticated()

    respond(
      new Response(
        JSON.stringify({ error: { code: 'AUTH_SESSION_INVALID', message: 'Service failure' } }),
        { status: 503 },
      ),
    )
    expect(await request).toMatchObject({ status: 503 })
    expect(invalidation).not.toHaveBeenCalled()
    expect(await loadSessionToken()).toBe('synthetic-new-session')
  })

  it('clears the current session for its own explicit authentication failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            JSON.stringify({ error: { code: 'AUTH_SESSION_INVALID', message: 'Session expired' } }),
            { status: 401 },
          ),
        ),
    )
    await persistSessionToken('synthetic-current-session')
    const invalidation = vi.fn(async () => clearSessionTokenFromMemory())
    registerCloudSessionInvalidation(invalidation)

    await expect(getSubscriptionStatus()).rejects.toMatchObject({
      status: 401,
      code: 'AUTH_SESSION_INVALID',
    })
    expect(invalidation).toHaveBeenCalledOnce()
    expect(await loadSessionToken()).toBeNull()
  })

  it('retains the newer renderer session when an old request returns 401', async () => {
    let respond!: (response: Response) => void
    const transport = vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          respond = resolve
        }),
    )
    vi.stubGlobal('fetch', transport)
    await persistSessionToken('synthetic-old-session')
    registerCloudSessionInvalidation(async () => clearSessionTokenFromMemory())

    const request = getSubscriptionStatus().catch((error: unknown) => error)
    await vi.waitFor(() => expect(transport).toHaveBeenCalledOnce())
    expect(transport.mock.calls[0]).toEqual([
      expect.any(String),
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer synthetic-old-session' }),
      }),
    ])
    await persistSessionToken('synthetic-new-session')
    markCloudSessionAuthenticated()
    expect(await loadSessionToken()).toBe('synthetic-new-session')

    respond(
      new Response(
        JSON.stringify({ error: { code: 'AUTH_SESSION_INVALID', message: 'Session expired' } }),
        { status: 401 },
      ),
    )
    expect(await request).toMatchObject({ status: 401, code: 'AUTH_SESSION_INVALID' })
    expect(await loadSessionToken()).toBe('synthetic-new-session')
  })
})
