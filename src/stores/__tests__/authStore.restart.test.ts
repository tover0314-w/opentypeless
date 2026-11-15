import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
vi.mock('../../components/toast-service', () => ({ toast: vi.fn() }))

import { invoke } from '@tauri-apps/api/core'
import { API_BASE_URL, APP_VERSION_HEADER_VALUE, CLIENT_VERSION_HEADER } from '../../lib/constants'
import { loadSessionToken, resetCloudSessionCoordinatorForTests } from '../../lib/cloud-session'
import { hasManagedCloudAccess, useAuthStore } from '../authStore'
import { toast } from '../../components/toast-service'
import { clearOAuthState, generateOAuthState, handleDeepLinkUrl } from '../../lib/deep-link'

describe('authStore restart recovery', () => {
  it('cancels persistence from the real desktop callback entry without changing accounts', async () => {
    const epoch = crypto.randomUUID()
    const attempt = crypto.randomUUID()
    let rejectCommit!: (error: Error) => void
    const transport = vi.mocked(fetch).getMockImplementation()!
    vi.mocked(fetch).mockImplementation(async (input, init) => {
      if (String(input) === `${API_BASE_URL}/api/auth/desktop-handoff/exchange`)
        return Response.json({ token: 'synthetic-candidate-token' })
      return transport(input, init)
    })
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_cloud_session_snapshot') return { token: null, sessionEpoch: epoch }
      if (command === 'begin_cloud_session_commit') return attempt
      if (command === 'set_cloud_session_snapshot') {
        return new Promise((_resolve, reject) => {
          rejectCommit = reject
        })
      }
      if (command === 'cancel_cloud_session_commit') {
        rejectCommit(new Error('Cloud session commit cancelled'))
        return true
      }
    })
    const state = generateOAuthState()
    const pending = handleDeepLinkUrl(
      `opentypeless://auth/callback?code=synthetic-code&state=${state}`,
    )
    await vi.waitFor(() => expect(rejectCommit).toBeTypeOf('function'))
    clearOAuthState(state)
    await expect(pending).resolves.toBe(false)
    expect(invoke).toHaveBeenCalledWith('cancel_cloud_session_commit', { attemptId: attempt })
    expect(useAuthStore.getState().user).toBeNull()
    expect(useAuthStore.getState().loading).toBe(false)
    expect(useAuthStore.getState().error).toBeNull()
    expect(await loadSessionToken()).toBeNull()
    expect(invoke).not.toHaveBeenCalledWith('set_session_token', expect.anything())
  })

  it('cancels an OAuth commit through the real auth client and session coordinator', async () => {
    const controller = new AbortController()
    const epoch = crypto.randomUUID()
    const attempt = crypto.randomUUID()
    let rejectCommit!: (error: Error) => void
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_cloud_session_snapshot') return { token: null, sessionEpoch: epoch }
      if (command === 'begin_cloud_session_commit') return attempt
      if (command === 'set_cloud_session_snapshot') {
        return new Promise((_resolve, reject) => {
          rejectCommit = reject
        })
      }
      if (command === 'cancel_cloud_session_commit') {
        rejectCommit(new Error('Cloud session commit cancelled'))
        return true
      }
    })
    const pending = useAuthStore
      .getState()
      .handleDeepLinkToken('synthetic-candidate-token', () => true, controller.signal)
    const rejected = expect(pending).rejects.toThrow('Cloud session commit cancelled')
    await vi.waitFor(() => expect(rejectCommit).toBeTypeOf('function'))
    const validation = vi
      .mocked(fetch)
      .mock.calls.find(([input]) => String(input) === `${API_BASE_URL}/api/auth/get-session`)
    expect(validation).toBeDefined()
    expect(new Headers(validation![1]?.headers).get('Authorization')).toBe(
      'Bearer synthetic-candidate-token',
    )
    expect(invoke).toHaveBeenCalledWith('set_cloud_session_snapshot', {
      token: 'synthetic-candidate-token',
      expectedSessionEpoch: epoch,
      attemptId: attempt,
    })
    controller.abort()
    await rejected
    expect(invoke).toHaveBeenCalledWith('cancel_cloud_session_commit', { attemptId: attempt })
    expect(useAuthStore.getState().user).toBeNull()
    expect(useAuthStore.getState().loading).toBe(false)
    expect(useAuthStore.getState().error).toBeNull()
    expect(await loadSessionToken()).toBeNull()
    expect(invoke).not.toHaveBeenCalledWith('set_session_token', expect.anything())
  })

  it('authenticates a deep-link token without depending on the previous vault read', async () => {
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_session_token') {
        throw new Error('Synthetic previous vault unavailable')
      }
      return undefined
    })

    await useAuthStore.getState().handleDeepLinkToken('synthetic-candidate-token')

    expect(useAuthStore.getState().user?.id).toBe('user-1')
    expect(await loadSessionToken()).toBe('synthetic-candidate-token')
    expect(invoke).not.toHaveBeenCalledWith('get_session_token')
    expect(invoke).toHaveBeenCalledWith('set_session_token', {
      token: 'synthetic-candidate-token',
    })
    const sessionRequest = vi
      .mocked(fetch)
      .mock.calls.find(([input]) => String(input) === `${API_BASE_URL}/api/auth/get-session`)
    expect(sessionRequest).toBeDefined()
    expect(new Headers(sessionRequest![1]?.headers).get('Authorization')).toBe(
      'Bearer synthetic-candidate-token',
    )
    expect(useAuthStore.getState().subscriptionRefreshState).toBe('fresh')
  })

  it.each([false, true])(
    'validates the actual client sign-in response with a header token: %s',
    async (hasToken) => {
      const previousUser = {
        id: 'previous',
        email: 'previous@example.test',
        name: null,
        emailVerified: true,
      }
      useAuthStore.setState({ user: previousUser })
      const transport = vi.mocked(fetch).getMockImplementation()!
      vi.mocked(fetch).mockImplementation(async (input, init) => {
        if (String(input) === `${API_BASE_URL}/api/auth/sign-in/email`) {
          return Response.json(
            {
              user: {
                id: 'user-1',
                email: 'person@example.com',
                name: 'Person',
                emailVerified: true,
              },
            },
            hasToken ? { headers: { 'set-auth-token': 'synthetic-header-token' } } : undefined,
          )
        }
        return transport(input, init)
      })
      if (hasToken) {
        await useAuthStore.getState().signIn('person@example.com', 'synthetic-password')
        expect(useAuthStore.getState().user?.id).toBe('user-1')
        expect(await loadSessionToken()).toBe('synthetic-header-token')
        expect(invoke).toHaveBeenCalledWith('set_session_token', {
          token: 'synthetic-header-token',
        })
      } else {
        await expect(
          useAuthStore.getState().signIn('person@example.com', 'synthetic-password'),
        ).rejects.toThrow('Sign in failed')
        expect(useAuthStore.getState().user).toBe(previousUser)
        expect(await loadSessionToken()).toBe('vault-token')
        expect(invoke).not.toHaveBeenCalledWith('set_session_token', expect.anything())
        expect(vi.mocked(fetch).mock.calls.map(([input]) => String(input))).not.toContain(
          `${API_BASE_URL}/api/subscription/status`,
        )
      }
    },
  )

  it('keeps a signed-out startup quiet when both the vault and server session are empty', async () => {
    vi.mocked(invoke).mockResolvedValue(null)
    vi.mocked(fetch).mockResolvedValue(Response.json(null))
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})

    await useAuthStore.getState().initialize()

    expect(useAuthStore.getState().user).toBeNull()
    expect(useAuthStore.getState().loading).toBe(false)
    expect(useAuthStore.getState().error).toBeNull()
    expect(await loadSessionToken()).toBeNull()
    expect(toast).not.toHaveBeenCalled()
    expect(warning).not.toHaveBeenCalled()
    expect(invoke).not.toHaveBeenCalledWith('set_session_token', expect.anything())
    expect(invoke).not.toHaveBeenCalledWith('clear_managed_stt_capability')
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('invalidates the running session even when clearing the expired vault token fails', async () => {
    const transport = vi.mocked(fetch).getMockImplementation()!
    vi.mocked(fetch).mockImplementation(async (input, init) =>
      String(input) === `${API_BASE_URL}/api/auth/get-session`
        ? Response.json(null)
        : transport(input, init),
    )
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_session_token') return 'vault-token'
      if (command === 'set_session_token') {
        throw new Error('Synthetic vault failure token=private-detail')
      }
    })
    const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {})
    useAuthStore.setState({
      user: { id: 'user-1', email: 'person@example.test', name: null, emailVerified: true },
      plan: 'lifetime_starter',
      source: 'lifetime',
    })

    await useAuthStore.getState().initialize()

    expect(useAuthStore.getState().user).toBeNull()
    expect(useAuthStore.getState().loading).toBe(false)
    expect(hasManagedCloudAccess(useAuthStore.getState())).toBe(false)
    expect(await loadSessionToken()).toBeNull()
    expect(errorLog).toHaveBeenCalledExactlyOnceWith('Failed to clear session token in backend')
    expect(invoke).toHaveBeenCalledWith('clear_managed_stt_capability')
  })

  it.each(['expired', 'offline'] as const)(
    'distinguishes an %s session response from temporary network failure',
    async (outcome) => {
      useAuthStore.setState({
        user: {
          id: 'user-1',
          email: 'person@example.test',
          name: null,
          emailVerified: true,
        },
        plan: 'lifetime_starter',
        source: 'lifetime',
      })
      const transport = vi.mocked(fetch).getMockImplementation()!
      vi.mocked(fetch).mockImplementation(async (input, init) => {
        if (String(input) === `${API_BASE_URL}/api/auth/get-session`) {
          if (outcome === 'offline') throw new TypeError('Synthetic network unavailable')
          return Response.json(null)
        }
        return transport(input, init)
      })
      vi.spyOn(console, 'warn').mockImplementation(() => {})

      await useAuthStore.getState().initialize()

      if (outcome === 'expired') {
        expect(useAuthStore.getState().user).toBeNull()
        expect(await loadSessionToken()).toBeNull()
        expect(invoke).toHaveBeenCalledWith('set_session_token', { token: '' })
        expect(hasManagedCloudAccess(useAuthStore.getState())).toBe(false)
      } else {
        expect(useAuthStore.getState().user?.id).toBe('user-1')
        expect(await loadSessionToken()).toBe('vault-token')
        expect(invoke).not.toHaveBeenCalledWith('set_session_token', expect.anything())
        expect(useAuthStore.getState().error).not.toBeNull()
      }
      expect(useAuthStore.getState().loading).toBe(false)
      expect(
        vi
          .mocked(fetch)
          .mock.calls.some(([input]) => String(input).endsWith('/api/subscription/status')),
      ).toBe(false)
    },
  )

  it('rejects mismatched subscription JSON and restores access after a matching retry', async () => {
    const transport = vi.mocked(fetch).getMockImplementation()!
    let mismatch = true
    vi.mocked(fetch).mockImplementation(async (input, init) => {
      const response = await transport(input, init)
      if (String(input) !== `${API_BASE_URL}/api/subscription/status` || !mismatch) {
        return response
      }
      const status = await response.json()
      return Response.json({
        ...status,
        accountSnapshot: { ...status.accountSnapshot, userId: 'different-user' },
      })
    })
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})

    await useAuthStore.getState().initialize()

    expect(useAuthStore.getState().user?.id).toBe('user-1')
    expect(useAuthStore.getState().subscriptionRefreshState).toBe('unavailable')
    expect(useAuthStore.getState().plan).toBe('free')
    expect(hasManagedCloudAccess(useAuthStore.getState())).toBe(false)
    expect(localStorage.getItem('opentypeless.desktop-subscription.v1:user-1')).toBeNull()
    expect(invoke).not.toHaveBeenCalledWith('cache_managed_stt_capability', expect.anything())
    expect(warning).toHaveBeenCalledExactlyOnceWith('Failed to refresh subscription status')

    mismatch = false
    await useAuthStore.getState().refreshSubscription()

    expect(useAuthStore.getState().subscriptionRefreshState).toBe('fresh')
    expect(hasManagedCloudAccess(useAuthStore.getState())).toBe(true)
    expect(invoke).toHaveBeenCalledWith('cache_managed_stt_capability', {
      accountSnapshot: expect.objectContaining({ userId: 'user-1' }),
      expectedUserId: 'user-1',
    })
  })

  it.each([
    { accounts: [], capability: 'none' },
    { accounts: [{ providerId: 'google' }], capability: 'none' },
    {
      accounts: [{ providerId: 'google' }, { providerId: 'credential' }],
      capability: 'present',
    },
  ] as const)('restores valid account capability JSON as $capability', async (scenario) => {
    const transport = vi.mocked(fetch).getMockImplementation()!
    vi.mocked(fetch).mockImplementation(async (input, init) => {
      if (String(input) === `${API_BASE_URL}/api/auth/list-accounts`) {
        return Response.json(scenario.accounts)
      }
      return transport(input, init)
    })
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})

    await useAuthStore.getState().initialize()

    expect(useAuthStore.getState().credentialCapability).toBe(scenario.capability)
    expect(useAuthStore.getState().user?.id).toBe('user-1')
    expect(useAuthStore.getState().subscriptionRefreshState).toBe('fresh')
    expect(useAuthStore.getState().loading).toBe(false)
    expect(warning).not.toHaveBeenCalled()
  })

  it.each([null, {}, [{ providerId: ' credential ' }], [{ providerId: 'credential' }, null]])(
    'restores the account but rejects malformed capability JSON: %j',
    async (accounts) => {
      const transport = vi.mocked(fetch).getMockImplementation()!
      vi.mocked(fetch).mockImplementation(async (input, init) => {
        if (String(input) === `${API_BASE_URL}/api/auth/list-accounts`) {
          return Response.json(accounts)
        }
        return transport(input, init)
      })
      const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})

      await useAuthStore.getState().initialize()

      expect(useAuthStore.getState().user?.id).toBe('user-1')
      expect(useAuthStore.getState().credentialCapability).toBe('unknown')
      expect(useAuthStore.getState().subscriptionRefreshState).toBe('fresh')
      expect(useAuthStore.getState().loading).toBe(false)
      expect(warning).toHaveBeenCalledExactlyOnceWith(
        'Failed to restore account security capability',
      )
      await expect(
        useAuthStore.getState().changePassword(null, 'synthetic-password'),
      ).rejects.toThrow('Invalid account security response')
      expect(vi.mocked(fetch).mock.calls.map(([input]) => String(input))).not.toContain(
        `${API_BASE_URL}/api/opentypeless/auth/set-password`,
      )
    },
  )

  afterEach(() => {
    clearOAuthState()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    resetCloudSessionCoordinatorForTests()
    useAuthStore.setState({
      user: null,
      plan: 'free',
      source: 'free',
      displayName: 'Free',
      licenseStatus: null,
      subscriptionRefreshState: 'unknown',
      subscriptionLastVerifiedAt: null,
      loading: false,
      error: null,
    })

    vi.mocked(invoke).mockImplementation((command) =>
      Promise.resolve(command === 'get_session_token' ? 'vault-token' : undefined),
    )
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: string | URL | Request) => {
        const url = String(input)
        if (url === `${API_BASE_URL}/api/auth/get-session`) {
          return new Response(
            JSON.stringify({
              user: {
                id: 'user-1',
                email: 'person@example.com',
                name: 'Person',
                emailVerified: true,
              },
              session: { id: 'session-1' },
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        if (url === `${API_BASE_URL}/api/auth/list-accounts`) {
          return new Response(JSON.stringify([{ providerId: 'credential' }]), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        if (url === `${API_BASE_URL}/api/subscription/status`) {
          return new Response(
            JSON.stringify({
              plan: 'lifetime_starter',
              source: 'lifetime',
              displayName: 'Lifetime Starter',
              subscriptionEnd: null,
              subscriptionStatus: 'active',
              licenseStatus: 'active',
              quotaModel: 'legacy_dual_meter',
              displayWordsUsedEstimate: 0,
              displayWordsLimit: 0,
              displayWordsResetAt: null,
              sttSecondsUsed: 0,
              sttSecondsLimit: 0,
              llmTokensUsed: 0,
              llmTokensLimit: 0,
              cloudWordsUsed: 0,
              cloudWordsLimit: 0,
              cloudWordsResetAt: null,
              byokUnlimited: true,
              accountSnapshot: {
                schemaVersion: 1,
                userId: 'user-1',
                managedSttCapabilities: {
                  version: 1,
                  maxRecordingSeconds: 600,
                  maxMultipartBytes: 25_000_000,
                  formats: [{ mimeType: 'audio/wav', maxAudioBytes: 25_000_000 }],
                },
                generatedAt: '2026-09-08T00:00:00.000Z',
              },
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        throw new Error(`Unexpected request: ${url}`)
      }),
    )
  })

  it('restores a paid account and managed recording limit from the vault token', async () => {
    await useAuthStore.getState().initialize()

    const state = useAuthStore.getState()
    expect(state.user?.id).toBe('user-1')
    expect(state.plan).toBe('lifetime_starter')
    expect(state.licenseStatus).toBe('active')
    expect(state.subscriptionRefreshState).toBe('fresh')
    expect(invoke).toHaveBeenCalledWith('get_session_token')
    expect(invoke).toHaveBeenCalledWith('cache_managed_stt_capability', {
      accountSnapshot: expect.objectContaining({
        userId: 'user-1',
        managedSttCapabilities: expect.objectContaining({ maxRecordingSeconds: 600 }),
      }),
      expectedUserId: 'user-1',
    })
    expect(invoke).not.toHaveBeenCalledWith('clear_managed_stt_capability')

    for (const [, init] of vi.mocked(fetch).mock.calls) {
      const headers = new Headers(init?.headers)
      expect(headers.get('Authorization')).toBe('Bearer vault-token')
      expect(headers.get(CLIENT_VERSION_HEADER)).toBe(APP_VERSION_HEADER_VALUE)
    }
    expect(localStorage.getItem('session_token')).toBeNull()
  })

  it('does not request account data after vault restoration fails and permits retry', async () => {
    vi.mocked(invoke).mockRejectedValueOnce(new Error('Synthetic vault unavailable'))
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})

    await useAuthStore.getState().initialize()

    expect(useAuthStore.getState().user).toBeNull()
    expect(useAuthStore.getState().loading).toBe(false)
    expect(useAuthStore.getState().error).not.toBeNull()
    expect(fetch).not.toHaveBeenCalled()
    expect(invoke).not.toHaveBeenCalledWith('set_session_token', expect.anything())
    expect(invoke).not.toHaveBeenCalledWith('cache_managed_stt_capability', expect.anything())
    expect(warning).toHaveBeenCalledExactlyOnceWith('Failed to restore cloud session')
    expect(localStorage.getItem('session_token')).toBeNull()

    await useAuthStore.getState().initialize()

    expect(useAuthStore.getState().user?.id).toBe('user-1')
    expect(useAuthStore.getState().subscriptionRefreshState).toBe('fresh')
    expect(useAuthStore.getState().error).toBeNull()
    expect(useAuthStore.getState().loading).toBe(false)
    expect(invoke).toHaveBeenCalledWith('cache_managed_stt_capability', expect.anything())
  })
})
