import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ApiError, CloudApiError } from '../api'
import { API_BASE_URL, APP_VERSION_HEADER_VALUE, CLIENT_VERSION_HEADER } from '../constants'

const invalidateCloudSessionOnce = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))
const loadSessionToken = vi.hoisted(() => vi.fn().mockResolvedValue(null))

vi.mock('../cloud-session', () => ({
  invalidateCloudSessionOnce,
  loadSessionToken,
}))

const API_BASE = API_BASE_URL

describe('ApiError', () => {
  it('stores status and message', () => {
    const err = new ApiError(404, 'Not found')
    expect(err.status).toBe(404)
    expect(err.message).toBe('Not found')
    expect(err.name).toBe('ApiError')
    expect(err).toBeInstanceOf(Error)
  })
})

describe('request() via getSubscriptionStatus', () => {
  beforeEach(() => {
    loadSessionToken.mockResolvedValue(null)
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () =>
          Promise.resolve({
            plan: 'pro',
            subscriptionEnd: '2025-12-31',
            quotaModel: 'legacy_dual_meter',
            displayWordsUsedEstimate: 2500,
            displayWordsLimit: 100000,
            displayWordsResetAt: '2026-07-01T00:00:00.000Z',
            sttSecondsUsed: 100,
            sttSecondsLimit: 36000,
            llmTokensUsed: 5000,
            llmTokensLimit: 5000000,
          }),
      }),
    )
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('calls fetch with correct URL and options', async () => {
    const { getSubscriptionStatus } = await import('../api')
    await getSubscriptionStatus()

    expect(fetch).toHaveBeenCalledWith(
      `${API_BASE}/api/subscription/status`,
      expect.objectContaining({
        credentials: 'include',
        headers: expect.objectContaining({
          'Content-Type': 'application/json',
          [CLIENT_VERSION_HEADER]: APP_VERSION_HEADER_VALUE,
        }),
      }),
    )
  })

  it('uses the system-vault-backed in-memory bearer without localStorage', async () => {
    loadSessionToken.mockResolvedValueOnce('vault-token')

    const { getSubscriptionStatus } = await import('../api')
    await getSubscriptionStatus()

    expect(fetch).toHaveBeenCalledWith(
      `${API_BASE}/api/subscription/status`,
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer vault-token' }),
      }),
    )
    expect(localStorage.getItem('session_token')).toBeNull()
  })

  it('returns parsed JSON on success', async () => {
    const { getSubscriptionStatus } = await import('../api')
    const result = await getSubscriptionStatus()
    expect(result.plan).toBe('pro')
    expect(result.quotaModel).toBe('legacy_dual_meter')
    expect(result.displayWordsLimit).toBe(100000)
    expect(result.sttSecondsLimit).toBe(36000)
  })

  it('uses the explicit billing provider without breaking the legacy source field', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      json: () =>
        Promise.resolve({
          plan: 'pro',
          source: 'creem',
          billingProvider: 'stripe',
          subscriptionStatus: 'active',
          canManageBilling: true,
          canMigrateToStripe: false,
        }),
    } as Response)

    const { getSubscriptionStatus } = await import('../api')
    const result = await getSubscriptionStatus()

    expect(result.source).toBe('creem')
    expect(result.billingProvider).toBe('stripe')
    expect(result.canManageBilling).toBe(true)
    expect(result.canMigrateToStripe).toBe(false)
  })

  it('only exposes Stripe migration for an explicit failed Creem account', async () => {
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      json: () =>
        Promise.resolve({
          plan: 'free',
          source: 'free',
          billingProvider: 'creem',
          subscriptionStatus: 'past_due',
          canManageBilling: true,
          canMigrateToStripe: true,
        }),
    } as Response)

    const { getSubscriptionStatus } = await import('../api')
    const result = await getSubscriptionStatus()

    expect(result.billingProvider).toBe('creem')
    expect(result.subscriptionStatus).toBe('past_due')
    expect(result.canMigrateToStripe).toBe(true)
  })

  it('preserves the authenticated account snapshot for Rust capability negotiation', async () => {
    const accountSnapshot = {
      schemaVersion: 1,
      userId: 'user-1',
      managedSttCapabilities: {
        version: 2,
        maxRecordingSeconds: 600,
        maxMultipartBytes: 4_200_000,
        formats: [],
      },
      generatedAt: '2026-07-22T08:00:00.000Z',
    }
    vi.mocked(fetch).mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({ plan: 'pro', accountSnapshot }),
    } as Response)

    const { getSubscriptionStatus } = await import('../api')
    const result = await getSubscriptionStatus()

    expect(result.accountSnapshot).toEqual(accountSnapshot)
  })
})

describe('request() error handling', () => {
  beforeEach(() => {
    invalidateCloudSessionOnce.mockClear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('throws ApiError with body.error on non-ok response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 403,
        statusText: 'Forbidden',
        json: () => Promise.resolve({ error: 'Subscription required' }),
      }),
    )

    const { getSubscriptionStatus } = await import('../api')
    await expect(getSubscriptionStatus()).rejects.toThrow('Subscription required')
    await expect(getSubscriptionStatus()).rejects.toBeInstanceOf(ApiError)
  })

  it('falls back to statusText when body has no error field', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
        json: () => Promise.resolve({}),
      }),
    )

    const { getSubscriptionStatus } = await import('../api')
    await expect(getSubscriptionStatus()).rejects.toThrow('Internal Server Error')
  })

  it('parses AUTH_SESSION_INVALID and invalidates the cloud session once', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        statusText: 'Unauthorized',
        json: () =>
          Promise.resolve({
            error: { code: 'AUTH_SESSION_INVALID', message: 'Session expired' },
          }),
      }),
    )

    const { getSubscriptionStatus } = await import('../api')
    const error = await getSubscriptionStatus().catch((caught) => caught)

    expect(error).toBeInstanceOf(CloudApiError)
    expect(error).toMatchObject({
      status: 401,
      code: 'AUTH_SESSION_INVALID',
      message: 'Session expired',
    })
    expect(invalidateCloudSessionOnce).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['AUTH_REQUIRED', 401, 'Authentication required'],
    ['QUOTA_EXCEEDED', 403, 'Cloud quota exceeded'],
  ])('parses %s without invalidating the current identity', async (code, status, message) => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status,
        statusText: 'Request failed',
        json: () => Promise.resolve({ error: { code, message } }),
      }),
    )

    const { getSubscriptionStatus } = await import('../api')
    const error = await getSubscriptionStatus().catch((caught) => caught)

    expect(error).toMatchObject({ status, code, message })
    expect(invalidateCloudSessionOnce).not.toHaveBeenCalled()
  })

  it('retains rollout compatibility with legacy top-level error strings', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 403,
        statusText: 'Forbidden',
        json: () => Promise.resolve({ error: 'Subscription required' }),
      }),
    )

    const { getSubscriptionStatus } = await import('../api')
    const error = await getSubscriptionStatus().catch((caught) => caught)

    expect(error).toMatchObject({ status: 403, code: null, message: 'Subscription required' })
    expect(invalidateCloudSessionOnce).not.toHaveBeenCalled()
  })
})

describe('createCheckout', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('sends POST with origin and checkout product in body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ url: 'https://checkout.stripe.com/xxx' }),
      }),
    )

    const { createCheckout } = await import('../api')
    const result = await createCheckout('web', 'lifetime_starter')

    expect(fetch).toHaveBeenCalledWith(
      `${API_BASE}/api/checkout/create`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ origin: 'web', product: 'lifetime_starter' }),
      }),
    )
    expect(result.url).toBe('https://checkout.stripe.com/xxx')
  })
})

describe('proxyStt', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('sends the desktop client version without forcing a JSON content type', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ text: 'transcribed text' }),
      }),
    )

    const { proxyStt } = await import('../api')
    const result = await proxyStt(new Blob(['audio'], { type: 'audio/wav' }), 'en', {
      operationId: '11111111-1111-1111-1111-111111111111',
      stageKey: '11111111-1111-1111-1111-111111111111:stt',
      requestType: 'voice_pipeline',
      clientVersion: APP_VERSION_HEADER_VALUE,
    })

    expect(fetch).toHaveBeenCalledWith(
      `${API_BASE}/api/proxy/stt`,
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          [CLIENT_VERSION_HEADER]: APP_VERSION_HEADER_VALUE,
        }),
      }),
    )
    const headers = vi.mocked(fetch).mock.calls[0][1]?.headers as Record<string, string>
    expect(headers['Content-Type']).toBeUndefined()
    const body = vi.mocked(fetch).mock.calls[0][1]?.body as FormData
    expect(body.get('operationId')).toBe('11111111-1111-1111-1111-111111111111')
    expect(body.get('stageKey')).toBe('11111111-1111-1111-1111-111111111111:stt')
    expect(result.text).toBe('transcribed text')
  })
})

describe('proxyLlm', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('sends messages array as POST body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ text: 'polished text' }),
      }),
    )

    const { proxyLlm } = await import('../api')
    const messages = [{ role: 'user', content: 'hello' }]
    const context = {
      operationId: '22222222-2222-2222-2222-222222222222',
      stageKey: '22222222-2222-2222-2222-222222222222:llm',
      requestType: 'voice_pipeline',
      clientVersion: APP_VERSION_HEADER_VALUE,
    }
    const result = await proxyLlm(messages, context)

    expect(fetch).toHaveBeenCalledWith(
      `${API_BASE}/api/proxy/llm`,
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          [CLIENT_VERSION_HEADER]: APP_VERSION_HEADER_VALUE,
        }),
        body: JSON.stringify({ messages, context }),
      }),
    )
    expect(result.text).toBe('polished text')
  })
})
