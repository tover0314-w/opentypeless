import { describe, it, expect, vi, beforeEach } from 'vitest'
import packageJson from '../../../package.json'
import { hasManagedCloudAccess, useAuthStore } from '../authStore'

// Mock external dependencies
vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn(),
}))

vi.mock('../../lib/auth-client', () => ({
  authClient: {
    getSession: vi.fn(),
    signIn: { email: vi.fn() },
    signUp: { email: vi.fn() },
    sendVerificationEmail: vi.fn(),
    listAccounts: vi.fn(),
    changePassword: vi.fn(),
    signOut: vi.fn(),
  },
  requestOpenTypelessPasswordReset: vi.fn(),
  setOpenTypelessPassword: vi.fn(),
}))

vi.mock('../../lib/api', () => ({
  getSubscriptionStatus: vi.fn(),
}))

vi.mock('../../components/toast-service', () => ({
  toast: vi.fn(),
}))

import { invoke } from '@tauri-apps/api/core'
import { authClient } from '../../lib/auth-client'
import { requestOpenTypelessPasswordReset, setOpenTypelessPassword } from '../../lib/auth-client'
import { getSubscriptionStatus } from '../../lib/api'
import {
  enqueueCloudSessionOperation,
  loadSessionToken,
  persistSessionToken,
  resetCloudSessionCoordinatorForTests,
} from '../../lib/cloud-session'
import { toast } from '../../components/toast-service'
import i18n from '../../i18n'

function getState() {
  return useAuthStore.getState()
}

describe('authStore', () => {
  it('does not persist a queued password rotation after the account is replaced', async () => {
    const originalUser = {
      id: 'original',
      email: 'original@example.test',
      name: null,
      emailVerified: true,
    }
    useAuthStore.setState({ user: originalUser, credentialCapability: 'present' })
    let release!: () => void
    const blocker = enqueueCloudSessionOperation(
      () =>
        new Promise<void>((resolve) => {
          release = resolve
        }),
    )
    await vi.waitFor(() => expect(release).toBeTypeOf('function'))
    vi.mocked(authClient.changePassword).mockResolvedValueOnce({
      data: { token: 'old-account-rotation' },
      error: null,
    } as never)
    const pending = getState().changePassword('synthetic-old-password', 'synthetic-new-password')
    const outcome = pending.catch(() => undefined)
    await vi.waitFor(() => expect(authClient.changePassword).toHaveBeenCalledOnce())
    await Promise.resolve()
    useAuthStore.setState({
      user: { ...originalUser, id: 'new-account', email: 'new@example.test' },
    })
    release()
    await blocker
    await outcome
    expect(invoke).not.toHaveBeenCalledWith('set_session_token', {
      token: 'old-account-rotation',
    })
  })

  it('revokes renderer access before a slow expired-session vault clear finishes', async () => {
    await persistSessionToken('old-token')
    useAuthStore.setState({
      user: { id: 'old', email: 'old@example.test', name: null, emailVerified: true },
      plan: 'pro',
      source: 'creem',
      cloudWordsLimit: 100000,
    })
    let finishClear!: () => void
    vi.mocked(invoke).mockImplementation(async (command, payload) => {
      if (
        command === 'set_session_token' &&
        payload &&
        'token' in payload &&
        payload.token === ''
      ) {
        return new Promise<void>((resolve) => {
          finishClear = resolve
        })
      }
    })
    const invalidation = getState().invalidateCloudSession()
    await vi.waitFor(() => expect(finishClear).toBeTypeOf('function'))
    const pendingUser = getState().user
    const pendingAccess = hasManagedCloudAccess(getState())
    const pendingToken = await loadSessionToken()
    finishClear()
    await invalidation
    expect(pendingUser).toBeNull()
    expect(pendingAccess).toBe(false)
    expect(pendingToken).toBeNull()
  })

  it('does not let delayed expired-session clearing overwrite a newer sign-in', async () => {
    await persistSessionToken('old-token')
    useAuthStore.setState({
      user: { id: 'old', email: 'old@example.test', name: null, emailVerified: true },
    })
    let finishClear!: () => void
    vi.mocked(invoke).mockImplementation(async (command, payload) => {
      if (
        command === 'set_session_token' &&
        payload &&
        'token' in payload &&
        payload.token === ''
      ) {
        return new Promise<void>((resolve) => {
          finishClear = resolve
        })
      }
    })
    vi.mocked(authClient.getSession).mockResolvedValueOnce({ data: null, error: null } as never)
    const expired = getState().initialize()
    await vi.waitFor(() => expect(finishClear).toBeTypeOf('function'))
    vi.mocked(authClient.signIn.email).mockResolvedValueOnce({
      data: {
        token: 'new-token',
        user: { id: 'new', email: 'new@example.test', name: null, emailVerified: true },
      },
      error: null,
    } as never)
    const signingIn = getState().signIn('new@example.test', 'synthetic-password')
    finishClear()
    await Promise.all([expired, signingIn])
    expect(getState().user?.id).toBe('new')
    expect(await loadSessionToken()).toBe('new-token')
    expect(getState().subscriptionRefreshState).toBe('fresh')
    expect(getState().loading).toBe(false)
    expect(toast).not.toHaveBeenCalledWith(i18n.t('account.sessionExpired'), 'error')
  })

  it('clears the previous account and token when session restoration explicitly returns null', async () => {
    await persistSessionToken('synthetic-expired-token')
    useAuthStore.setState({
      user: { id: '1', email: 'person@example.test', name: null, emailVerified: true },
      plan: 'pro',
      source: 'creem',
      cloudWordsLimit: 100000,
    })
    vi.mocked(authClient.getSession).mockResolvedValueOnce({ data: null, error: null } as never)
    await getState().initialize()
    expect(getState().user).toBeNull()
    expect(hasManagedCloudAccess(getState())).toBe(false)
    expect(await loadSessionToken()).toBeNull()
    expect(invoke).toHaveBeenCalledWith('set_session_token', { token: '' })
    expect(getSubscriptionStatus).not.toHaveBeenCalled()
    expect(getState().loading).toBe(false)
  })

  it.each([
    { cloudWordsLimit: -1 },
    { sttSecondsUsed: Number.NaN },
    { byokUnlimited: 'yes' },
    { canManageBilling: 'true' },
    { canMigrateToStripe: 1 },
    { cancelAtPeriodEnd: null },
    { billingProvider: 'unknown-provider' },
    { entitlementVersion: -1 },
    { displayWordsResetAt: {} },
    { cloudWordsResetAt: 42 },
  ])('rejects malformed live entitlement before publishing or caching: %j', async (patch) => {
    useAuthStore.setState({
      user: { id: '1', email: 'person@example.test', name: null, emailVerified: true },
    })
    const subscription = await getSubscriptionStatus()
    vi.mocked(getSubscriptionStatus).mockResolvedValueOnce({
      ...subscription,
      ...patch,
    } as never)
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      await getState().refreshSubscription()
      expect(getState().plan).toBe('free')
      expect(getState().subscriptionRefreshState).toBe('unavailable')
      expect(getState().subscriptionLastVerifiedAt).toBeNull()
      expect(hasManagedCloudAccess(getState())).toBe(false)
      expect(localStorage.getItem('opentypeless.desktop-subscription.v1:1')).toBeNull()
      expect(invoke).not.toHaveBeenCalledWith('cache_managed_stt_capability', expect.anything())
    } finally {
      warning.mockRestore()
    }
  })

  it('rejects another account subscription before publishing or caching its entitlement', async () => {
    useAuthStore.setState({
      user: { id: 'current', email: 'current@example.test', name: null, emailVerified: true },
    })
    const subscription = await getSubscriptionStatus()
    vi.mocked(getSubscriptionStatus).mockResolvedValueOnce({
      ...subscription,
      accountSnapshot: {
        schemaVersion: 1,
        userId: 'another',
        managedSttCapabilities: null,
        generatedAt: '2026-11-12T00:00:00Z',
      },
    })
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      await getState().refreshSubscription()
      expect(getState().plan).toBe('free')
      expect(getState().subscriptionRefreshState).toBe('unavailable')
      expect(getState().subscriptionLastVerifiedAt).toBeNull()
      expect(hasManagedCloudAccess(getState())).toBe(false)
      expect(invoke).not.toHaveBeenCalledWith('cache_managed_stt_capability', expect.anything())
      expect(localStorage.getItem('opentypeless.desktop-subscription.v1:current')).toBeNull()
    } finally {
      warning.mockRestore()
    }
  })

  it.each(['same', 'different'] as const)(
    'retains only the %s account entitlement when subscription refresh fails',
    async (identity) => {
      const verifiedAt = new Date().toISOString()
      const previousUser = {
        id: 'previous',
        email: 'previous@example.test',
        name: null,
        emailVerified: true,
      }
      useAuthStore.setState({
        user: previousUser,
        credentialCapability: 'present',
        plan: 'pro',
        source: 'creem',
        cloudWordsLimit: 100000,
        canManageBilling: true,
        subscriptionLastVerifiedAt: verifiedAt,
        subscriptionRefreshState: 'fresh',
      })
      vi.mocked(authClient.getSession).mockResolvedValueOnce({
        data: {
          user:
            identity === 'same'
              ? { ...previousUser, name: 'Updated profile' }
              : { ...previousUser, id: 'next', email: 'next@example.test' },
        },
        error: null,
      } as never)
      vi.mocked(getSubscriptionStatus).mockRejectedValueOnce(new Error('network unavailable'))
      const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
      try {
        await getState().initialize()
        if (identity === 'same') {
          expect(getState()).toMatchObject({
            plan: 'pro',
            cloudWordsLimit: 100000,
            canManageBilling: true,
            subscriptionLastVerifiedAt: verifiedAt,
            subscriptionRefreshState: 'stale',
          })
          expect(hasManagedCloudAccess(getState())).toBe(true)
        } else {
          expect(getState()).toMatchObject({
            plan: 'free',
            cloudWordsLimit: 0,
            canManageBilling: false,
            subscriptionLastVerifiedAt: null,
            subscriptionRefreshState: 'unavailable',
          })
          expect(hasManagedCloudAccess(getState())).toBe(false)
        }
        expect(getState().loading).toBe(false)
      } finally {
        warning.mockRestore()
      }
    },
  )

  it.each(['initialize', 'signIn', 'handleDeepLinkToken'] as const)(
    'clears the previous account capability before the new lookup during %s',
    async (action) => {
      useAuthStore.setState({
        user: { id: 'previous', email: 'previous@example.test', name: null, emailVerified: true },
        credentialCapability: 'none',
        plan: 'pro',
        source: 'creem',
        cloudWordsLimit: 100000,
        canManageBilling: true,
        subscriptionLastVerifiedAt: '2026-11-12T00:00:00Z',
        checkoutPending: true,
      })
      const user = { id: 'next', email: 'next@example.test', name: null, emailVerified: true }
      if (action === 'signIn') {
        vi.mocked(authClient.signIn.email).mockResolvedValueOnce({
          data: { user, token: 'synthetic-token' },
          error: null,
        } as never)
      } else {
        vi.mocked(authClient.getSession).mockResolvedValueOnce({
          data: { user },
          error: null,
        } as never)
      }
      let finish!: (value: unknown) => void
      const subscriptionResponse = await getSubscriptionStatus()
      vi.mocked(getSubscriptionStatus).mockClear()
      let finishSubscription!: () => void
      vi.mocked(getSubscriptionStatus).mockReturnValueOnce(
        new Promise((resolve) => {
          finishSubscription = () => resolve(subscriptionResponse)
        }),
      )
      vi.mocked(authClient.listAccounts).mockReturnValueOnce(
        new Promise((resolve) => {
          finish = resolve
        }) as never,
      )
      const request =
        action === 'initialize'
          ? getState().initialize()
          : action === 'signIn'
            ? getState().signIn(user.email, 'synthetic-password')
            : getState().handleDeepLinkToken('synthetic-token')
      await vi.waitFor(() => expect(authClient.listAccounts).toHaveBeenCalledOnce())
      const pendingCapability = getState().credentialCapability
      const pendingAccount = getState()
      finish({ data: [{ providerId: 'credential' }], error: null })
      await vi.waitFor(() => expect(finishSubscription).toBeTypeOf('function'))
      finishSubscription()
      await request
      expect(pendingCapability).toBe('unknown')
      expect(pendingAccount).toMatchObject({
        plan: 'free',
        source: 'free',
        cloudWordsLimit: 0,
        canManageBilling: false,
        subscriptionLastVerifiedAt: null,
        checkoutPending: false,
      })
      expect(getState().user?.id).toBe('next')
      expect(getState().credentialCapability).toBe('present')
    },
  )

  it.each([
    null,
    undefined,
    [{}],
    [{ providerId: '' }],
    [{ providerId: 42 }],
    [{ providerId: 'credential' }, null],
    [{ providerId: 'google' }, { providerId: '   ' }],
    [{ providerId: 'credential' }, []],
    [{ providerId: ' credential ' }],
    [{ providerId: '\tcredential\n' }],
  ])(
    'rejects malformed account capability data instead of assuming no password: %j',
    async (data) => {
      useAuthStore.setState({
        user: { id: '1', email: 'person@example.com', name: null, emailVerified: true },
        credentialCapability: 'unknown',
      })
      vi.mocked(authClient.listAccounts).mockResolvedValueOnce({ data, error: null } as never)
      await expect(getState().changePassword(null, 'new-password')).rejects.toThrow(
        'Invalid account security response',
      )
      expect(getState().credentialCapability).toBe('unknown')
      expect(setOpenTypelessPassword).not.toHaveBeenCalled()
      expect(authClient.changePassword).not.toHaveBeenCalled()
    },
  )

  it('ignores a late managed capability cache failure after sign-out', async () => {
    useAuthStore.setState({
      user: { id: '1', email: 'person@example.com', name: null, emailVerified: true },
    })
    let fail!: (error: Error) => void
    const cacheRequest = new Promise<void>((_resolve, reject) => {
      fail = reject
    })
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'cache_managed_stt_capability') return cacheRequest
    })
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      const refresh = getState().refreshSubscription()
      await vi.waitFor(() =>
        expect(invoke).toHaveBeenCalledWith('cache_managed_stt_capability', expect.anything()),
      )
      const signingOut = getState().signOut()
      fail(new Error('old account cache failure'))
      await Promise.all([refresh, signingOut])
      expect(warning).not.toHaveBeenCalled()
      expect(getState().user).toBeNull()
      expect(getState().plan).toBe('free')
      expect(getState().loading).toBe(false)
    } finally {
      warning.mockRestore()
    }
  })

  it('keeps managed capability cache failure details out of logs without losing subscription', async () => {
    useAuthStore.setState({
      user: { id: '1', email: 'person@example.com', name: null, emailVerified: true },
    })
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'cache_managed_stt_capability') {
        throw new Error('private-account-detail token=synthetic-secret')
      }
    })
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      await getState().refreshSubscription()
      expect(warning).toHaveBeenCalledExactlyOnceWith(
        'Failed to sync managed STT capability; using the safe fallback.',
      )
      expect(getState().subscriptionRefreshState).toBe('fresh')
      expect(getState().plan).toBe('pro')
      expect(getState().subscriptionRefreshLoading).toBe(false)
    } finally {
      warning.mockRestore()
    }
  })

  it.each([
    ['initialize', 'Failed to restore account security capability'],
    ['signIn', 'Failed to load account security after sign-in'],
    ['handleDeepLinkToken', 'Failed to load account security after OAuth sign-in'],
  ] as const)(
    'keeps private capability errors out of logs after %s while restoring subscription',
    async (action, summary) => {
      const user = { id: '1', email: 'person@example.com', name: null, emailVerified: true }
      if (action === 'signIn') {
        vi.mocked(authClient.signIn.email).mockResolvedValueOnce({
          data: { user, token: 'synthetic-token' },
          error: null,
        } as never)
      } else {
        vi.mocked(authClient.getSession).mockResolvedValueOnce({
          data: { user },
          error: null,
        } as never)
      }
      vi.mocked(authClient.listAccounts).mockRejectedValueOnce(
        new Error('Capability lookup failed: private-email@example.com token=private-detail'),
      )
      const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
      try {
        if (action === 'initialize') await getState().initialize()
        else if (action === 'signIn') await getState().signIn(user.email, 'synthetic-password')
        else await getState().handleDeepLinkToken('synthetic-token')
        expect(warning).toHaveBeenCalledExactlyOnceWith(summary)
        expect(getState().user?.id).toBe('1')
        expect(getState().credentialCapability).toBe('unknown')
        expect(getState().subscriptionRefreshState).toBe('fresh')
        expect(getState().loading).toBe(false)
      } finally {
        warning.mockRestore()
      }
    },
  )

  it('does not log private subscription failure details while retaining retry state', async () => {
    useAuthStore.setState({
      user: { id: '1', email: 'person@example.com', name: null, emailVerified: true },
    })
    const message = 'Subscription request failed: https://example.test/?token=private-detail'
    vi.mocked(getSubscriptionStatus).mockRejectedValueOnce(new Error(message))
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      await getState().refreshSubscription()
      expect(warning).toHaveBeenCalledExactlyOnceWith('Failed to refresh subscription status')
      expect(getState().subscriptionRefreshError).toBe(message)
      expect(getState().subscriptionRefreshLoading).toBe(false)
      await getState().refreshSubscription()
      expect(getState().subscriptionRefreshState).toBe('fresh')
      expect(getState().subscriptionRefreshError).toBeNull()
    } finally {
      warning.mockRestore()
    }
  })

  it.each(['signOut', 'invalidateCloudSession'] as const)(
    'keeps backend token-clearing error details out of logs during %s',
    async (action) => {
      await persistSessionToken('synthetic-session')
      vi.mocked(invoke).mockImplementation(async (command) => {
        if (command === 'set_session_token') {
          throw new Error('Synthetic vault error: private-token-detail')
        }
        return undefined
      })
      const errorLog = vi.spyOn(console, 'error').mockImplementation(() => {})
      try {
        await getState()[action]()
        expect(errorLog).toHaveBeenCalledExactlyOnceWith('Failed to clear session token in backend')
        expect(await loadSessionToken()).toBeNull()
        expect(getState().user).toBeNull()
      } finally {
        errorLog.mockRestore()
      }
    },
  )

  it('does not print raw session restore failure details to the console', async () => {
    const message = 'Synthetic restore failure: private-token-in-server-error'
    vi.mocked(authClient.getSession).mockRejectedValueOnce(new Error(message))
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      await getState().initialize()
      expect(warning).toHaveBeenCalledExactlyOnceWith('Failed to restore cloud session')
      expect(getState().error).toBe(message)
      expect(getState().loading).toBe(false)
    } finally {
      warning.mockRestore()
    }
  })

  it.each(['', '   '])('uses the header token when the data token is blank', async (token) => {
    await persistSessionToken('previous-token')
    vi.mocked(authClient.signIn.email).mockImplementationOnce(async (_input, options) => {
      await options?.onSuccess?.({
        response: new Response(null, { headers: { 'set-auth-token': 'header-token' } }),
      } as never)
      return {
        data: {
          token,
          user: { id: '1', email: 'person@example.com', emailVerified: true },
        },
        error: null,
      } as never
    })
    await getState().signIn('person@example.com', 'test-password')
    expect(await loadSessionToken()).toBe('header-token')
  })

  it.each([undefined, '', '   '])(
    'rejects a sign-in response without a usable token before switching accounts: %s',
    async (token) => {
      await persistSessionToken('synthetic-existing-token')
      const previousUser = {
        id: 'previous',
        email: 'previous@example.test',
        name: null,
        emailVerified: true,
      }
      useAuthStore.setState({ user: previousUser })
      vi.mocked(authClient.signIn.email).mockResolvedValueOnce({
        data: {
          token,
          user: { id: 'next', email: 'next@example.test', emailVerified: true },
        },
        error: null,
      } as never)
      await expect(getState().signIn('next@example.test', 'synthetic-password')).rejects.toThrow(
        'Sign in failed',
      )
      expect(getState().user).toBe(previousUser)
      expect(await loadSessionToken()).toBe('synthetic-existing-token')
      expect(getSubscriptionStatus).not.toHaveBeenCalled()
      expect(authClient.listAccounts).not.toHaveBeenCalled()
    },
  )

  it.each(['initialize', 'handleDeepLinkToken'] as const)(
    'rejects malformed session users through %s without persisting a candidate token',
    async (action) => {
      await persistSessionToken('existing-token')
      vi.mocked(authClient.getSession).mockResolvedValueOnce({
        data: { user: { id: '', email: 'person@example.com', emailVerified: true } },
        error: null,
      } as never)
      const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
      try {
        if (action === 'initialize') await getState().initialize()
        else {
          await expect(getState().handleDeepLinkToken('malformed-session-token')).rejects.toThrow(
            'Invalid account response',
          )
        }
        expect(getState().user).toBeNull()
        expect(getState().loading).toBe(false)
        expect(getState().error).not.toBeNull()
        expect(await loadSessionToken()).toBe('existing-token')
        expect(getSubscriptionStatus).not.toHaveBeenCalled()
      } finally {
        warning.mockRestore()
      }
    },
  )

  it('preserves a valid unverified user without inventing verification', async () => {
    vi.mocked(authClient.signIn.email).mockResolvedValueOnce({
      data: {
        token: 'unverified-session-token',
        user: { id: '1', email: 'person@example.com', emailVerified: false },
      },
      error: null,
    } as never)
    await getState().signIn('person@example.com', 'test-password')
    expect(getState().user).toEqual({
      id: '1',
      email: 'person@example.com',
      name: null,
      emailVerified: false,
    })
    expect(await loadSessionToken()).toBe('unverified-session-token')
  })

  it.each([
    { id: '', email: 'person@example.com' },
    { id: '   ', email: 'person@example.com' },
    { id: 12, email: 'person@example.com' },
    { id: '1', email: null },
    { id: '1', email: '' },
    { id: '1', email: 'person@example.com', name: {} },
    { id: '1', email: 'person@example.com', emailVerified: 'true' },
  ])('rejects malformed sign-in user fields before saving a token', async (user) => {
    vi.mocked(authClient.signIn.email).mockResolvedValueOnce({
      data: { user, token: 'malformed-user-token' },
      error: null,
    } as never)
    await expect(getState().signIn('person@example.com', 'test-password')).rejects.toThrow()
    expect(getState().user).toBeNull()
    expect(invoke).not.toHaveBeenCalledWith('set_session_token', { token: 'malformed-user-token' })
    expect(getSubscriptionStatus).not.toHaveBeenCalled()
  })

  it.each(['data', 'header'] as const)(
    'persists the %s sign-in token before loading account details',
    async (source) => {
      vi.mocked(authClient.signIn.email).mockImplementationOnce(async (_input, options) => {
        await options?.onSuccess?.({
          response: new Response(null, { headers: { 'set-auth-token': 'header-token' } }),
        } as never)
        return {
          data: {
            user: { id: '1', email: 'person@example.com', name: null, emailVerified: true },
            ...(source === 'data' ? { token: 'data-token' } : {}),
          },
          error: null,
        } as never
      })
      await getState().signIn('person@example.com', 'test-password')
      expect(await loadSessionToken()).toBe(`${source}-token`)
      const writeIndex = vi
        .mocked(invoke)
        .mock.calls.findIndex(([command]) => command === 'set_session_token')
      expect(writeIndex).toBeGreaterThanOrEqual(0)
      expect(vi.mocked(invoke).mock.invocationCallOrder[writeIndex]).toBeLessThan(
        vi.mocked(authClient.listAccounts).mock.invocationCallOrder[0],
      )
      expect(getState().user?.id).toBe('1')
    },
  )

  it('does not enter a signed-in state when token persistence fails', async () => {
    vi.mocked(authClient.signIn.email).mockResolvedValueOnce({
      data: {
        token: 'new-token',
        user: { id: '1', email: 'person@example.com', name: null, emailVerified: true },
      },
      error: null,
    } as never)
    vi.mocked(invoke).mockRejectedValueOnce(new Error('vault unavailable'))
    await expect(getState().signIn('person@example.com', 'test-password')).rejects.toThrow(
      'Cloud session disabled',
    )
    expect(getState().user).toBeNull()
    expect(getState().loading).toBe(false)
    expect(getSubscriptionStatus).not.toHaveBeenCalled()
  })

  it('does not persist a success-hook token when sign-in returns no user', async () => {
    await persistSessionToken('existing-token')
    vi.mocked(authClient.signIn.email).mockImplementationOnce(async (_input, options) => {
      await options?.onSuccess?.({
        response: new Response(null, { headers: { 'set-auth-token': 'invalid-response-token' } }),
      } as never)
      return { data: null, error: null } as never
    })
    await expect(getState().signIn('person@example.com', 'test-password')).rejects.toThrow(
      'Sign in failed',
    )
    expect(await loadSessionToken()).toBe('existing-token')
  })

  it.each([null, {}])('rejects a successful sign-in response without a user', async (data) => {
    vi.mocked(authClient.signIn.email).mockResolvedValueOnce({ data, error: null } as never)
    await expect(getState().signIn('person@example.com', 'test-password')).rejects.toThrow(
      'Sign in failed',
    )
    expect(getState().user).toBeNull()
    expect(getState().error).toBe('Sign in failed')
    expect(getState().loading).toBe(false)
    expect(getSubscriptionStatus).not.toHaveBeenCalled()
  })

  it.each(['initialize', 'signIn', 'handleDeepLinkToken'] as const)(
    'clears obsolete verification state after successful %s',
    async (action) => {
      useAuthStore.setState({
        emailVerificationPending: true,
        pendingEmail: 'previous@example.com',
      })
      const response = {
        data: {
          token: 'synthetic-verified-session',
          user: { id: '1', email: 'verified@example.com', name: null, emailVerified: true },
        },
        error: null,
      }
      if (action === 'signIn') {
        vi.mocked(authClient.signIn.email).mockResolvedValueOnce(response as never)
        await getState().signIn('verified@example.com', 'test-password')
      } else {
        vi.mocked(authClient.getSession).mockResolvedValueOnce(response as never)
        if (action === 'initialize') await getState().initialize()
        else await getState().handleDeepLinkToken('verified-session-token')
      }
      expect(getState().user?.email).toBe('verified@example.com')
      expect(getState().emailVerificationPending).toBe(false)
      expect(getState().pendingEmail).toBeNull()
    },
  )

  it.each(['resendVerification', 'requestPasswordReset'] as const)(
    'does not apply a delayed %s failure after sign-out',
    async (action) => {
      let fail: (error: Error) => void = () => {}
      const response = new Promise<never>((_resolve, reject) => {
        fail = reject
      })
      let pending: Promise<void>
      if (action === 'resendVerification') {
        useAuthStore.setState({ pendingEmail: 'person@example.com' })
        vi.mocked(authClient.sendVerificationEmail).mockReturnValueOnce(response as never)
        pending = getState().resendVerification()
      } else {
        vi.mocked(requestOpenTypelessPasswordReset).mockReturnValueOnce(response)
        pending = getState().requestPasswordReset('person@example.com', 'en')
      }
      const outcome = pending.catch(() => undefined)
      await getState().signOut()
      useAuthStore.setState({ error: 'new operation', loading: true })
      fail(new Error('old mail failure'))
      await outcome
      expect(getState().error).toBe('new operation')
      expect(getState().loading).toBe(true)
    },
  )

  it.each(['signIn', 'signUp'] as const)(
    'does not persist a late %s success-hook token after sign-out',
    async (action) => {
      const invokeSuccessHook = async () => {
        const callback =
          action === 'signIn'
            ? vi.mocked(authClient.signIn.email).mock.calls[0][1]?.onSuccess
            : vi.mocked(authClient.signUp.email).mock.calls[0][1]?.onSuccess
        if (!callback) throw new Error('Expected authentication success hook')
        await callback({
          response: new Response(null, { headers: { 'set-auth-token': 'late-token' } }),
        } as never)
      }
      const response = new Promise(() => {})
      if (action === 'signIn') {
        vi.mocked(authClient.signIn.email).mockReturnValueOnce(response as never)
        void getState().signIn('person@example.com', 'test-password')
      } else {
        vi.mocked(authClient.signUp.email).mockReturnValueOnce(response as never)
        void getState().signUp('person@example.com', 'test-password', 'Person')
      }
      await getState().signOut()
      await expect(invokeSuccessHook()).rejects.toThrow('superseded')
      expect(await loadSessionToken()).toBeNull()
      expect(invoke).not.toHaveBeenCalledWith('set_session_token', { token: 'late-token' })
    },
  )

  it.each(['initialize', 'signIn', 'signUp'] as const)(
    'ignores a delayed %s response after sign-out',
    async (action) => {
      let finish: (value: unknown) => void = () => {}
      const response = new Promise((resolve) => {
        finish = resolve
      })
      let pending: Promise<void>
      if (action === 'initialize') {
        await loadSessionToken()
        vi.mocked(authClient.getSession).mockReturnValueOnce(response as never)
        pending = getState().initialize()
        await Promise.resolve()
      } else if (action === 'signIn') {
        vi.mocked(authClient.signIn.email).mockReturnValueOnce(response as never)
        pending = getState().signIn('person@example.com', 'test-password')
      } else {
        vi.mocked(authClient.signUp.email).mockReturnValueOnce(response as never)
        pending = getState().signUp('person@example.com', 'test-password', 'Person')
      }
      const outcome = pending.catch(() => undefined)
      await getState().signOut()
      finish({
        data: {
          user: { id: '1', email: 'person@example.com', name: null, emailVerified: true },
        },
        error: null,
      })
      await outcome
      expect(getState().user).toBeNull()
      expect(getState().emailVerificationPending).toBe(false)
      expect(getState().pendingEmail).toBeNull()
      expect(getState().error).toBeNull()
      expect(getState().loading).toBe(false)
      expect(getSubscriptionStatus).not.toHaveBeenCalled()
    },
  )

  it.each(['response', 'transport'])(
    'keeps successful sign-in when account security lookup fails via %s',
    async (failure) => {
      vi.mocked(authClient.signIn.email).mockResolvedValue({
        data: {
          token: 'synthetic-security-session',
          user: { id: '1', email: 'person@example.com', name: 'Person', emailVerified: true },
        },
        error: null,
      } as never)
      if (failure === 'response') {
        vi.mocked(authClient.listAccounts).mockResolvedValue({
          data: null,
          error: { message: 'security unavailable' },
        } as never)
      } else {
        vi.mocked(authClient.listAccounts).mockRejectedValue(new Error('Failed to fetch'))
      }
      const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
      try {
        await expect(
          getState().signIn('person@example.com', 'test-password'),
        ).resolves.toBeUndefined()
        expect(getState().user?.id).toBe('1')
        expect(getState().error).toBeNull()
        expect(getState().loading).toBe(false)
        expect(getState().credentialCapability).toBe('unknown')
        expect(getSubscriptionStatus).toHaveBeenCalledTimes(1)
        expect(getState().plan).toBe('pro')
      } finally {
        warning.mockRestore()
      }
    },
  )

  it('pins the Better Auth desktop client version', () => {
    expect(packageJson.dependencies['better-auth']).toBe('1.6.25')
  })

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    resetCloudSessionCoordinatorForTests()

    // Reset store state
    useAuthStore.setState({
      user: null,
      plan: 'free',
      source: 'free',
      displayName: 'Free',
      subscriptionEnd: null,
      subscriptionStatus: null,
      licenseStatus: null,
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
      credentialCapability: 'unknown',
      subscriptionRefreshState: 'unknown',
      subscriptionRefreshLoading: false,
      subscriptionLastVerifiedAt: null,
      subscriptionRefreshError: null,
      loading: false,
      error: null,
    })

    // Set up mock implementations fresh each test
    vi.mocked(invoke).mockResolvedValue(undefined)
    vi.mocked(authClient.getSession).mockResolvedValue({ data: null } as never)
    vi.mocked(authClient.signIn.email).mockResolvedValue({ data: null, error: null } as never)
    vi.mocked(authClient.signUp.email).mockResolvedValue({ data: null, error: null } as never)
    vi.mocked(authClient.sendVerificationEmail).mockResolvedValue({
      data: null,
      error: null,
    } as never)
    vi.mocked(authClient.listAccounts).mockResolvedValue({ data: [], error: null } as never)
    vi.mocked(authClient.changePassword).mockResolvedValue({ data: null, error: null } as never)
    vi.mocked(authClient.signOut).mockResolvedValue(undefined as never)
    vi.mocked(requestOpenTypelessPasswordReset).mockResolvedValue(undefined)
    vi.mocked(setOpenTypelessPassword).mockResolvedValue(undefined)
    vi.mocked(getSubscriptionStatus).mockImplementation(async () => ({
      plan: 'pro',
      source: 'creem',
      displayName: 'Pro',
      subscriptionEnd: '2025-12-31',
      subscriptionStatus: 'active',
      licenseStatus: null,
      quotaModel: 'legacy_dual_meter',
      displayWordsUsedEstimate: 0,
      displayWordsLimit: 100000,
      displayWordsResetAt: '2026-07-01T00:00:00.000Z',
      sttSecondsUsed: 100,
      sttSecondsLimit: 36000,
      llmTokensUsed: 5000,
      llmTokensLimit: 5000000,
      cloudWordsUsed: 0,
      cloudWordsLimit: 0,
      cloudWordsResetAt: null,
      byokUnlimited: true,
      accountSnapshot: {
        schemaVersion: 1,
        userId: getState().user?.id ?? '1',
        managedSttCapabilities: null,
        generatedAt: '2026-07-22T08:00:00.000Z',
      },
    }))
  })

  describe('initial state', () => {
    it('starts with no user and free plan', () => {
      expect(getState().user).toBeNull()
      expect(getState().plan).toBe('free')
      expect(getState().loading).toBe(false)
      expect(getState().error).toBeNull()
    })
  })

  describe('email verification callback', () => {
    it('passes a desktop callback URL when signing up', async () => {
      await getState().signUp('test@example.com', 'password123', 'Test', {
        verificationCallbackURL: 'https://www.opentypeless.com/auth/callback?from=desktop',
      })

      expect(authClient.signUp.email).toHaveBeenCalledWith(
        {
          email: 'test@example.com',
          password: 'password123',
          name: 'Test',
          callbackURL: 'https://www.opentypeless.com/auth/callback?from=desktop',
        },
        expect.any(Object),
      )
      expect(getState().emailVerificationPending).toBe(true)
      expect(getState().pendingEmail).toBe('test@example.com')
    })

    it('resends verification with a desktop callback URL for unverified sign-ins', async () => {
      vi.mocked(authClient.signIn.email).mockResolvedValue({
        data: null,
        error: { code: 'EMAIL_NOT_VERIFIED', message: 'Email not verified' },
      } as never)

      await getState().signIn('test@example.com', 'password123', {
        verificationCallbackURL: 'https://www.opentypeless.com/auth/callback?from=desktop',
      })

      expect(authClient.sendVerificationEmail).toHaveBeenCalledWith({
        email: 'test@example.com',
        callbackURL: 'https://www.opentypeless.com/auth/callback?from=desktop',
      })
      expect(getState().emailVerificationPending).toBe(true)
    })

    it('resends pending verification with the supplied callback URL', async () => {
      useAuthStore.setState({ pendingEmail: 'test@example.com' })

      await getState().resendVerification({
        verificationCallbackURL: 'https://www.opentypeless.com/auth/callback?from=desktop',
      })

      expect(authClient.sendVerificationEmail).toHaveBeenCalledWith({
        email: 'test@example.com',
        callbackURL: 'https://www.opentypeless.com/auth/callback?from=desktop',
      })
    })
  })

  describe('signOut', () => {
    it('clears the local session when the remote sign-out request rejects', async () => {
      await persistSessionToken('old-token')
      useAuthStore.setState({
        user: { id: '1', email: 'person@example.com', name: null, emailVerified: true },
        plan: 'pro',
      })
      vi.mocked(authClient.signOut).mockRejectedValueOnce(new Error('network unavailable'))

      await expect(getState().signOut()).rejects.toThrow('network unavailable')

      expect(getState().user).toBeNull()
      expect(getState().plan).toBe('free')
      expect(await loadSessionToken()).toBeNull()
      expect(invoke).toHaveBeenCalledWith('set_session_token', { token: '' })
    })

    it('fails closed in renderer memory when native session clearing rejects', async () => {
      await persistSessionToken('old-token')
      useAuthStore.setState({
        user: { id: '1', email: 'person@example.com', name: null, emailVerified: true },
        plan: 'pro',
      })
      vi.mocked(invoke).mockImplementation(async (command) => {
        if (command === 'set_session_token') throw new Error('vault unavailable')
        return undefined
      })

      await getState().signOut()

      expect(getState().user).toBeNull()
      expect(getState().plan).toBe('free')
      expect(await loadSessionToken()).toBeNull()
      expect(invoke).toHaveBeenCalledWith('set_session_token', { token: '' })
    })

    it('does not clear a new login when an older sign-out request finishes', async () => {
      let finish: () => void = () => {}
      vi.mocked(authClient.signOut).mockReturnValueOnce(
        new Promise<void>((resolve) => {
          finish = resolve
        }) as never,
      )
      await persistSessionToken('old-token')
      const previous = getState().signOut()
      vi.mocked(authClient.getSession).mockResolvedValueOnce({
        data: {
          user: { id: 'new', email: 'new@example.com', name: null, emailVerified: true },
        },
        error: null,
      } as never)
      await getState().handleDeepLinkToken('new-token')
      finish()
      await previous
      expect(getState().user?.id).toBe('new')
      expect(await loadSessionToken()).toBe('new-token')
      expect(getState().plan).toBe('pro')
    })

    it('clears user and resets to free plan', async () => {
      useAuthStore.setState({
        user: { id: '1', email: 'test@example.com', name: 'Test', emailVerified: true },
        plan: 'pro',
        source: 'creem',
        displayName: 'Pro',
        subscriptionEnd: '2025-12-31',
        subscriptionStatus: 'active',
        licenseStatus: null,
        quotaModel: 'legacy_dual_meter',
        displayWordsUsedEstimate: 0,
        displayWordsLimit: 0,
        displayWordsResetAt: null,
        sttSecondsUsed: 100,
        sttSecondsLimit: 36000,
        llmTokensUsed: 5000,
        llmTokensLimit: 5000000,
        cloudWordsUsed: 0,
        cloudWordsLimit: 0,
        cloudWordsResetAt: null,
        byokUnlimited: true,
      })

      await getState().signOut()

      expect(getState().user).toBeNull()
      expect(getState().plan).toBe('free')
      expect(getState().subscriptionEnd).toBeNull()
      expect(getState().sttSecondsUsed).toBe(0)
      expect(getState().llmTokensUsed).toBe(0)
    })
  })

  describe('refreshSubscription', () => {
    it('does not reuse or apply an old session request after the same account signs in again', async () => {
      let release: (() => void) | undefined
      vi.mocked(getSubscriptionStatus).mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            release = () => resolve({ plan: 'free', accountSnapshot: null } as never)
          }),
      )
      const user = { id: '1', email: 'person@example.com', name: null, emailVerified: true }
      useAuthStore.setState({ user })
      const previous = getState().refreshSubscription()
      await getState().signOut()
      vi.mocked(authClient.signIn.email).mockResolvedValueOnce({
        data: { user, token: 'synthetic-reauthenticated-session' },
        error: null,
      } as never)
      const current = getState().signIn(user.email, 'test-password')
      await vi.waitFor(() => expect(getSubscriptionStatus).toHaveBeenCalledTimes(2))
      const requestCount = vi.mocked(getSubscriptionStatus).mock.calls.length
      release?.()
      await Promise.all([previous, current])
      expect(requestCount).toBe(2)
      expect(getState().plan).toBe('pro')
      expect(getState().subscriptionRefreshState).toBe('fresh')
    })

    it('does not reuse a previous account subscription request', async () => {
      let release: (() => void) | undefined
      vi.mocked(getSubscriptionStatus).mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            release = () => resolve({ plan: 'free', accountSnapshot: null } as never)
          }),
      )
      useAuthStore.setState({
        user: { id: 'old', email: 'old@example.com', name: null, emailVerified: true },
      })
      const previous = getState().refreshSubscription()
      useAuthStore.setState({
        user: { id: 'new', email: 'new@example.com', name: null, emailVerified: true },
      })
      const current = getState().refreshSubscription()
      const requestCount = vi.mocked(getSubscriptionStatus).mock.calls.length
      release?.()
      await Promise.all([previous, current])
      expect(requestCount).toBe(2)
      expect(getState().plan).toBe('pro')
      expect(getState().subscriptionRefreshState).toBe('fresh')
    })

    it('coalesces concurrent refresh triggers into one status request', async () => {
      useAuthStore.setState({
        user: { id: '1', email: 'test@example.com', name: 'Test', emailVerified: true },
      })
      let release: (() => void) | undefined
      vi.mocked(getSubscriptionStatus).mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            release = () => resolve({ accountSnapshot: null } as never)
          }),
      )

      const first = getState().refreshSubscription()
      const second = getState().refreshSubscription()

      expect(getSubscriptionStatus).toHaveBeenCalledTimes(1)
      release?.()
      await Promise.all([first, second])
    })

    it('does not request subscription state while signed out', async () => {
      await getState().refreshSubscription()

      expect(getSubscriptionStatus).not.toHaveBeenCalled()
    })

    it('updates quota fields from API response', async () => {
      useAuthStore.setState({
        user: { id: '1', email: 'test@example.com', name: 'Test', emailVerified: true },
      })

      await getState().refreshSubscription()

      expect(getState().plan).toBe('pro')
      expect(getState().subscriptionEnd).toBe('2025-12-31')
      expect(getState().sttSecondsUsed).toBe(100)
      expect(getState().sttSecondsLimit).toBe(36000)
      expect(getState().llmTokensUsed).toBe(5000)
      expect(getState().llmTokensLimit).toBe(5000000)
      expect(invoke).toHaveBeenCalledWith('cache_managed_stt_capability', {
        accountSnapshot: expect.objectContaining({ userId: '1' }),
        expectedUserId: '1',
      })
    })

    it('refreshes high cloud word usage without unsolicited quota error toasts', async () => {
      vi.mocked(getSubscriptionStatus).mockResolvedValue({
        plan: 'appsumo_tier1',
        source: 'appsumo',
        displayName: 'AppSumo Tier 1',
        subscriptionEnd: null,
        subscriptionStatus: null,
        licenseStatus: 'active',
        quotaModel: 'cloud_words',
        displayWordsUsedEstimate: 0,
        displayWordsLimit: 0,
        displayWordsResetAt: null,
        sttSecondsUsed: 0,
        sttSecondsLimit: 0,
        llmTokensUsed: 0,
        llmTokensLimit: 0,
        cloudWordsUsed: 180000,
        cloudWordsLimit: 200000,
        cloudWordsResetAt: null,
        byokUnlimited: true,
      })
      useAuthStore.setState({
        user: { id: '1', email: 'test@example.com', name: 'Test', emailVerified: true },
      })

      await getState().refreshSubscription()
      await getState().refreshSubscription()

      expect(toast).not.toHaveBeenCalled()
      expect(getState().cloudWordsUsed).toBe(180000)
      expect(getState().cloudWordsLimit).toBe(200000)
    })

    it('keeps legacy meters updated without exposing STT or LLM warning toasts', async () => {
      useAuthStore.setState({
        user: { id: '1', email: 'test@example.com', name: 'Test', emailVerified: true },
      })
      const status = await getSubscriptionStatus()
      vi.mocked(getSubscriptionStatus).mockResolvedValue({
        ...status,
        quotaModel: 'legacy_dual_meter',
        displayWordsLimit: 0,
        cloudWordsLimit: 0,
        sttSecondsUsed: 33000,
        sttSecondsLimit: 36000,
        llmTokensUsed: 4600000,
        llmTokensLimit: 5000000,
      })
      await getState().refreshSubscription()
      await getState().refreshSubscription()
      expect(toast).not.toHaveBeenCalled()
      expect(getState().sttSecondsUsed).toBe(33000)
      expect(getState().llmTokensUsed).toBe(4600000)
    })

    it('keeps the last verified paid entitlement when refresh later fails', async () => {
      useAuthStore.setState({
        user: { id: '1', email: 'test@example.com', name: 'Test', emailVerified: true },
      })
      await getState().refreshSubscription()
      const verifiedAt = getState().subscriptionLastVerifiedAt

      vi.mocked(getSubscriptionStatus).mockRejectedValueOnce(new Error('service unavailable'))
      await getState().refreshSubscription()

      expect(getState().plan).toBe('pro')
      expect(getState().subscriptionRefreshState).toBe('stale')
      expect(getState().subscriptionLastVerifiedAt).toBe(verifiedAt)
      expect(getState().subscriptionRefreshLoading).toBe(false)
      expect(getState().subscriptionRefreshError).toBe('service unavailable')
    })

    it('marks membership unavailable without pretending a failed first refresh is fresh', async () => {
      useAuthStore.setState({
        user: { id: 'new-user', email: 'new@example.com', name: null, emailVerified: true },
      })
      vi.mocked(getSubscriptionStatus).mockRejectedValueOnce(new Error('offline'))

      await getState().refreshSubscription()

      expect(getState().subscriptionRefreshState).toBe('unavailable')
      expect(getState().subscriptionLastVerifiedAt).toBeNull()
    })
  })

  describe('hasManagedCloudAccess', () => {
    it('recognizes Stripe Pro access returned by the new billing backend', () => {
      expect(
        hasManagedCloudAccess({
          plan: 'pro',
          source: 'stripe',
          cloudWordsLimit: 100000,
          licenseStatus: null,
        }),
      ).toBe(true)
    })
    it('allows AppSumo lifetime plans with cloud words', () => {
      expect(
        hasManagedCloudAccess({
          plan: 'appsumo_tier1',
          source: 'appsumo',
          cloudWordsLimit: 200000,
          licenseStatus: 'active',
        }),
      ).toBe(true)
    })

    it('allows Creem Pro plans with cloud words', () => {
      expect(
        hasManagedCloudAccess({
          plan: 'pro',
          source: 'creem',
          cloudWordsLimit: 200000,
          licenseStatus: null,
        }),
      ).toBe(true)
    })

    it('allows direct lifetime plans', () => {
      expect(
        hasManagedCloudAccess({
          plan: 'lifetime_starter',
          source: 'lifetime',
          cloudWordsLimit: 0,
          licenseStatus: 'active',
        }),
      ).toBe(true)

      expect(
        hasManagedCloudAccess({
          plan: 'lifetime_starter',
          source: 'lifetime',
          cloudWordsLimit: 100000,
          licenseStatus: null,
        }),
      ).toBe(true)
    })

    it('denies revoked lifetime licenses even when a quota remains', () => {
      expect(
        hasManagedCloudAccess({
          plan: 'appsumo_tier1',
          source: 'appsumo',
          cloudWordsLimit: 200000,
          licenseStatus: 'refunded',
        }),
      ).toBe(false)
    })

    it('denies AppSumo cloud access unless the license is active', () => {
      expect(
        hasManagedCloudAccess({
          plan: 'appsumo_tier1',
          source: 'appsumo',
          cloudWordsLimit: 200000,
          licenseStatus: 'pending',
        }),
      ).toBe(false)

      expect(
        hasManagedCloudAccess({
          plan: 'appsumo_tier1',
          source: 'appsumo',
          cloudWordsLimit: 200000,
          licenseStatus: null,
        }),
      ).toBe(false)
    })
  })

  describe('initialize', () => {
    it.each(['initialize', 'signIn', 'handleDeepLinkToken'] as const)(
      'loads quota while account security is pending during %s',
      async (action) => {
        vi.mocked(authClient.getSession).mockResolvedValue({
          data: {
            user: {
              id: 'quota-restoration',
              email: 'quota@example.test',
              name: 'Quota',
              emailVerified: true,
            },
          },
        } as never)
        vi.mocked(authClient.signIn.email).mockResolvedValueOnce({
          data: {
            user: {
              id: 'quota-restoration',
              email: 'quota@example.test',
              name: 'Quota',
              emailVerified: true,
            },
            token: 'synthetic-quota-token',
          },
          error: null,
        } as never)
        let releaseAccounts!: () => void
        vi.mocked(authClient.listAccounts).mockReturnValueOnce(
          new Promise((resolve) => {
            releaseAccounts = () => resolve({ data: [], error: null } as never)
          }) as never,
        )
        const pending =
          action === 'initialize'
            ? getState().initialize()
            : action === 'signIn'
              ? getState().signIn('quota@example.test', 'synthetic-password')
              : getState().handleDeepLinkToken('synthetic-quota-token')
        try {
          await vi.waitFor(() => expect(getSubscriptionStatus).toHaveBeenCalledOnce())
          expect(getState().displayWordsLimit).toBe(100000)
        } finally {
          releaseAccounts()
          await pending
        }
      },
    )

    it('revokes the previous account when registration token persistence disables native identity', async () => {
      await persistSessionToken('existing-token')
      useAuthStore.setState({
        user: { id: 'existing', email: 'existing@example.test', name: null, emailVerified: true },
        plan: 'pro',
        source: 'creem',
      })
      vi.mocked(authClient.signUp.email).mockImplementationOnce(async (_input, options) => {
        await options?.onSuccess?.({
          response: new Response(null, { headers: { 'set-auth-token': 'registration-token' } }),
        } as never)
        return { data: null, error: null } as never
      })
      vi.mocked(invoke).mockImplementation(async (command) => {
        if (command === 'set_session_token') throw new Error('Synthetic protected write failure')
        if (command === 'get_cloud_session_snapshot')
          return { token: null, sessionEpoch: crypto.randomUUID() }
      })
      await expect(
        getState().signUp('new@example.test', 'synthetic-password', 'New'),
      ).rejects.toThrow()
      expect(getState().user).toBeNull()
      expect(hasManagedCloudAccess(getState())).toBe(false)
      expect(getState().loading).toBe(false)
    })

    it('revokes account access when password token rotation disables native identity', async () => {
      await persistSessionToken('existing-token')
      useAuthStore.setState({
        user: { id: 'existing', email: 'existing@example.test', name: null, emailVerified: true },
        plan: 'pro',
        source: 'creem',
        credentialCapability: 'present',
      })
      vi.mocked(authClient.changePassword).mockResolvedValueOnce({
        data: { token: 'rotated-token' },
        error: null,
      } as never)
      vi.mocked(invoke).mockImplementation(async (command) => {
        if (command === 'set_session_token') throw new Error('Synthetic protected write failure')
        if (command === 'get_cloud_session_snapshot')
          return { token: null, sessionEpoch: crypto.randomUUID() }
      })
      await expect(getState().changePassword('old-password', 'new-password')).rejects.toThrow()
      expect(getState().user).toBeNull()
      expect(getState().loading).toBe(false)
      expect(hasManagedCloudAccess(getState())).toBe(false)
      expect(await loadSessionToken()).toBeNull()
    })

    it('revokes old account entitlements after an OAuth commit disables native identity', async () => {
      await persistSessionToken('existing-token')
      useAuthStore.setState({
        user: { id: 'existing', email: 'existing@example.test', name: null, emailVerified: true },
        plan: 'pro',
        source: 'creem',
        cloudWordsLimit: 100000,
      })
      vi.mocked(authClient.getSession).mockResolvedValueOnce({
        data: {
          user: { id: 'candidate', email: 'candidate@example.test', emailVerified: true },
        },
        error: null,
      } as never)
      let snapshots = 0
      vi.mocked(invoke).mockImplementation(async (command) => {
        if (command === 'get_cloud_session_snapshot') {
          snapshots += 1
          return {
            token: snapshots === 1 ? 'existing-token' : null,
            sessionEpoch: crypto.randomUUID(),
          }
        }
        if (command === 'set_cloud_session_snapshot') throw new Error('Synthetic vault failure')
      })
      await expect(getState().handleDeepLinkToken('candidate-token', () => true)).rejects.toThrow(
        'Cloud session disabled',
      )
      expect(await loadSessionToken()).toBeNull()
      expect(getState().user).toBeNull()
      expect(hasManagedCloudAccess(getState())).toBe(false)
      expect(getState().plan).toBe('free')
      expect(getState().loading).toBe(false)
    })

    it('passes OAuth cancellation to account validation and does not save its late result', async () => {
      const controller = new AbortController()
      let finish!: (value: unknown) => void
      vi.mocked(authClient.getSession).mockReturnValueOnce(
        new Promise((resolve) => {
          finish = resolve as (value: unknown) => void
        }) as never,
      )
      const pending = getState().handleDeepLinkToken(
        'cancelled-validation-token',
        () => true,
        controller.signal,
      )
      const outcome = pending.catch(() => undefined)
      controller.abort()
      finish({
        data: {
          user: { id: 'cancelled', email: 'cancelled@example.test', emailVerified: true },
        },
        error: null,
      })
      await outcome
      expect(authClient.getSession).toHaveBeenCalledWith({
        fetchOptions: {
          headers: { Authorization: 'Bearer cancelled-validation-token' },
          signal: controller.signal,
        },
      })
      expect(invoke).not.toHaveBeenCalledWith('get_cloud_session_snapshot')
      expect(getState().user).toBeNull()
      expect(getState().loading).toBe(false)
      expect(getState().error).toBeNull()
    })

    it('reconciles a committed OAuth session when cancellation arrives after native submission', async () => {
      let active = true
      let finishCommit!: (value: unknown) => void
      const initialEpoch = crypto.randomUUID()
      const committedEpoch = crypto.randomUUID()
      vi.mocked(authClient.getSession).mockResolvedValueOnce({
        data: {
          user: { id: 'committed', email: 'committed@example.test', emailVerified: true },
        },
        error: null,
      } as never)
      vi.mocked(invoke).mockImplementation(async (command) => {
        if (command === 'get_cloud_session_snapshot')
          return { token: null, sessionEpoch: initialEpoch }
        if (command === 'set_cloud_session_snapshot') {
          return new Promise((resolve) => {
            finishCommit = resolve
          })
        }
      })
      const pending = getState().handleDeepLinkToken('committed-token', () => active)
      await vi.waitFor(() => expect(finishCommit).toBeTypeOf('function'))
      active = false
      finishCommit({ token: 'committed-token', sessionEpoch: committedEpoch })
      await pending
      expect(getState().user?.id).toBe('committed')
      expect(await loadSessionToken()).toBe('committed-token')
      expect(getState().loading).toBe(false)
      expect(getState().error).toBeNull()
      expect(invoke).not.toHaveBeenCalledWith('set_session_token', { token: '' })
    })

    it('preserves the existing session when OAuth validation loses its owner', async () => {
      await persistSessionToken('existing-token')
      let finish!: (value: unknown) => void
      let active = true
      vi.mocked(authClient.getSession).mockReturnValueOnce(
        new Promise((resolve) => {
          finish = resolve as (value: unknown) => void
        }) as never,
      )
      const pending = getState().handleDeepLinkToken('cancelled-token', () => active)
      const outcome = pending.catch(() => undefined)
      active = false
      finish({
        data: {
          user: { id: 'cancelled', email: 'cancelled@example.test', emailVerified: true },
        },
        error: null,
      })
      await outcome
      expect(await loadSessionToken()).toBe('existing-token')
      expect(invoke).not.toHaveBeenCalledWith('set_session_token', { token: 'cancelled-token' })
      expect(getState().loading).toBe(false)
      expect(getState().error).toBeNull()
    })

    it.each(['signOut', 'invalidateCloudSession'] as const)(
      'does not restore a delayed OAuth session after %s',
      async (action) => {
        let finish: (value: unknown) => void = () => {}
        vi.mocked(authClient.getSession).mockReturnValueOnce(
          new Promise((resolve) => {
            finish = resolve as (value: unknown) => void
          }) as never,
        )
        const pending = getState().handleDeepLinkToken('delayed-callback-token')
        const outcome = pending.catch(() => undefined)
        await getState()[action]()
        finish({
          data: {
            user: { id: '1', email: 'person@example.com', name: null, emailVerified: true },
          },
          error: null,
        })
        await outcome
        expect(getState().user).toBeNull()
        expect(await loadSessionToken()).toBeNull()
        expect(getState().error).toBeNull()
        expect(getSubscriptionStatus).not.toHaveBeenCalled()
      },
    )

    it.each(['missing', 'error'])(
      'rejects a %s OAuth session before persisting its token',
      async (outcome) => {
        await persistSessionToken('existing-token')
        vi.mocked(authClient.getSession).mockResolvedValueOnce({
          data: null,
          error: outcome === 'error' ? { message: 'Invalid callback session' } : null,
        } as never)
        await expect(getState().handleDeepLinkToken('invalid-callback-token')).rejects.toThrow()
        expect(await loadSessionToken()).toBe('existing-token')
        expect(getState().user).toBeNull()
        expect(getState().error).toBe('Failed to authenticate with token')
        expect(getState().loading).toBe(false)
      },
    )

    it('keeps a verified OAuth session when the account security lookup fails', async () => {
      vi.mocked(authClient.getSession).mockResolvedValueOnce({
        data: {
          user: { id: '1', email: 'person@example.com', name: null, emailVerified: true },
        },
        error: null,
      } as never)
      vi.mocked(authClient.listAccounts).mockResolvedValueOnce({
        data: null,
        error: { message: 'security unavailable' },
      } as never)
      const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
      try {
        await getState().handleDeepLinkToken('valid-callback-token')
        expect(getState().error).toBeNull()
        expect(getState().user?.id).toBe('1')
        expect(getState().plan).toBe('pro')
        expect(await loadSessionToken()).toBe('valid-callback-token')
      } finally {
        warning.mockRestore()
      }
    })

    it('sets loading during initialization', async () => {
      const promise = getState().initialize()
      expect(getState().loading).toBe(true)
      await promise
      expect(getState().loading).toBe(false)
    })

    it('stays null user when no session exists', async () => {
      await getState().initialize()
      expect(getState().user).toBeNull()
      expect(getState().error).toBeNull()
    })

    it('surfaces session restoration failures instead of treating them as signed out', async () => {
      vi.mocked(authClient.getSession).mockResolvedValue({
        data: null,
        error: { message: 'service unavailable' },
      } as never)
      const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})

      await getState().initialize()

      expect(getState().user).toBeNull()
      expect(getState().error).toBe('service unavailable')
      expect(warning).toHaveBeenCalledWith('Failed to restore cloud session')
      warning.mockRestore()
    })

    it('propagates email verification and credential capability from Better Auth', async () => {
      vi.mocked(authClient.getSession).mockResolvedValue({
        data: {
          user: {
            id: 'user-1',
            email: 'person@example.com',
            name: 'Person',
            emailVerified: true,
          },
        },
      } as never)
      vi.mocked(authClient.listAccounts).mockResolvedValue({
        data: [{ providerId: 'credential' }],
        error: null,
      } as never)

      await getState().initialize()

      expect(getState().user?.emailVerified).toBe(true)
      expect(getState().credentialCapability).toBe('present')
    })

    it('restores subscription state even when account capability lookup fails', async () => {
      vi.mocked(authClient.getSession).mockResolvedValue({
        data: {
          user: {
            id: '1',
            email: 'person@example.com',
            name: 'Person',
            emailVerified: true,
          },
        },
      } as never)
      vi.mocked(authClient.listAccounts).mockResolvedValue({
        data: null,
        error: { message: 'account endpoint unavailable' },
      } as never)
      const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})

      await getState().initialize()

      expect(getState().user?.id).toBe('1')
      expect(getState().credentialCapability).toBe('unknown')
      expect(getSubscriptionStatus).toHaveBeenCalledTimes(1)
      expect(getState().plan).toBe('pro')
      expect(warning).toHaveBeenCalledWith('Failed to restore account security capability')
      warning.mockRestore()
    })
  })

  describe('password actions', () => {
    it('localizes the account-change error during password setup', async () => {
      const previousLanguage = i18n.language
      let finishSetup: () => void = () => {}
      vi.mocked(setOpenTypelessPassword).mockReturnValueOnce(
        new Promise<void>((resolve) => {
          finishSetup = resolve
        }),
      )
      useAuthStore.setState({
        user: { id: 'first', email: 'first@example.com', name: null, emailVerified: true },
        credentialCapability: 'none',
      })
      try {
        await i18n.changeLanguage('zh')
        const pending = getState().changePassword(null, 'new-password')
        const rejected = expect(pending).rejects.toThrow('更新密码期间账号已变化')
        useAuthStore.setState({ user: null })
        finishSetup()
        await rejected
      } finally {
        await i18n.changeLanguage(previousLanguage)
      }
    })

    it.each(['success', 'failure'])(
      'ignores an older capability %s after a newer lookup completes',
      async (outcome) => {
        let resolveOlder: (value: unknown) => void = () => {}
        let rejectOlder: (error: Error) => void = () => {}
        vi.mocked(authClient.listAccounts).mockReturnValueOnce(
          new Promise((resolve, reject) => {
            resolveOlder = resolve as (value: unknown) => void
            rejectOlder = reject
          }) as never,
        )
        useAuthStore.setState({
          user: { id: '1', email: 'person@example.com', name: null, emailVerified: true },
          credentialCapability: 'unknown',
        })
        const older = getState().refreshCredentialCapability()
        vi.mocked(authClient.listAccounts).mockResolvedValueOnce({
          data: [{ providerId: 'google' }],
          error: null,
        } as never)
        await getState().refreshCredentialCapability()
        expect(getState().credentialCapability).toBe('none')
        if (outcome === 'success') {
          resolveOlder({ data: [{ providerId: 'credential' }], error: null })
        } else {
          rejectOlder(new Error('old failure'))
        }
        await expect(older).resolves.toBeUndefined()
        expect(getState().credentialCapability).toBe('none')
      },
    )

    it('stops password submission if the account changes during capability lookup', async () => {
      let finishLookup: (value: unknown) => void = () => {}
      vi.mocked(authClient.listAccounts).mockReturnValueOnce(
        new Promise((resolve) => {
          finishLookup = resolve as (value: unknown) => void
        }) as never,
      )
      useAuthStore.setState({
        user: { id: 'first', email: 'first@example.com', name: null, emailVerified: true },
        credentialCapability: 'unknown',
      })
      const pending = getState().changePassword('old-password', 'new-password')
      const rejected = expect(pending).rejects.toThrow('Account changed')
      useAuthStore.setState({
        user: { id: 'second', email: 'second@example.com', name: null, emailVerified: true },
        credentialCapability: 'present',
      })
      finishLookup({ data: [{ providerId: 'credential' }], error: null })
      await rejected
      expect(authClient.changePassword).not.toHaveBeenCalled()
      expect(setOpenTypelessPassword).not.toHaveBeenCalled()
    })

    it('keeps a successful password setup when its follow-up lookup fails', async () => {
      useAuthStore.setState({
        user: { id: '1', email: 'person@example.com', name: null, emailVerified: true },
        credentialCapability: 'none',
      })
      vi.mocked(authClient.listAccounts).mockRejectedValueOnce(
        new Error('private-account-detail token=synthetic-secret'),
      )
      const warning = vi.spyOn(console, 'warn').mockImplementation(() => {})
      try {
        await expect(getState().changePassword(null, 'new-password')).resolves.toBeUndefined()
        expect(setOpenTypelessPassword).toHaveBeenCalledWith('new-password')
        expect(getState().credentialCapability).toBe('unknown')
        expect(getState().error).toBeNull()
        expect(getState().loading).toBe(false)
        expect(warning).toHaveBeenCalledExactlyOnceWith(
          'Failed to refresh account security after password setup',
        )
      } finally {
        warning.mockRestore()
      }
    })

    it('does not persist an old password response token after the account changes', async () => {
      let finishChange: (value: unknown) => void = () => {}
      vi.mocked(authClient.changePassword).mockReturnValueOnce(
        new Promise((resolve) => {
          finishChange = resolve as (value: unknown) => void
        }) as never,
      )
      useAuthStore.setState({
        user: { id: 'first', email: 'first@example.com', name: null, emailVerified: true },
        credentialCapability: 'present',
      })
      const pending = getState().changePassword('old-password', 'new-password')
      const rejected = expect(pending).rejects.toThrow('Account changed')
      useAuthStore.setState({
        user: { id: 'second', email: 'second@example.com', name: null, emailVerified: true },
        error: null,
        loading: true,
      })
      finishChange({ data: { token: 'old-account-token' }, error: null })
      await rejected
      expect(invoke).not.toHaveBeenCalledWith('set_session_token', { token: 'old-account-token' })
      expect(getState().error).toBeNull()
      expect(getState().loading).toBe(true)
    })

    it('does not apply account capability from a previous user', async () => {
      let finish: (value: unknown) => void = () => {}
      vi.mocked(authClient.listAccounts).mockReturnValueOnce(
        new Promise((resolve) => {
          finish = resolve as (value: unknown) => void
        }) as never,
      )
      useAuthStore.setState({
        user: { id: 'first', email: 'first@example.com', name: null, emailVerified: true },
        credentialCapability: 'unknown',
      })
      const pending = getState().refreshCredentialCapability()
      useAuthStore.setState({
        user: { id: 'second', email: 'second@example.com', name: null, emailVerified: true },
        credentialCapability: 'none',
      })
      finish({ data: [{ providerId: 'credential' }], error: null })
      await pending
      expect(getState().credentialCapability).toBe('none')
    })

    it('clears a stale capability when the current account lookup throws', async () => {
      useAuthStore.setState({
        user: { id: '1', email: 'person@example.com', name: null, emailVerified: true },
        credentialCapability: 'present',
      })
      vi.mocked(authClient.listAccounts).mockRejectedValueOnce(new Error('Failed to fetch'))
      await expect(getState().refreshCredentialCapability()).rejects.toThrow('Failed to fetch')
      expect(getState().credentialCapability).toBe('unknown')
    })

    it('ignores an old account lookup failure after signing out', async () => {
      let rejectLookup: (error: Error) => void = () => {}
      vi.mocked(authClient.listAccounts).mockReturnValueOnce(
        new Promise((_resolve, reject) => {
          rejectLookup = reject
        }) as never,
      )
      useAuthStore.setState({
        user: { id: '1', email: 'person@example.com', name: null, emailVerified: true },
      })
      const pending = getState().refreshCredentialCapability()
      useAuthStore.setState({ user: null, credentialCapability: 'none' })
      rejectLookup(new Error('late failure'))
      await expect(pending).resolves.toBeUndefined()
      expect(getState().credentialCapability).toBe('none')
    })

    it('requests a reset through the canonical wrapper', async () => {
      await getState().requestPasswordReset('person@example.com', 'zh')

      expect(requestOpenTypelessPasswordReset).toHaveBeenCalledWith('person@example.com', 'zh')
    })

    it('maps OAuth-only accounts to no credential capability', async () => {
      vi.mocked(authClient.listAccounts).mockResolvedValue({
        data: [{ providerId: 'google' }],
        error: null,
      } as never)

      await getState().refreshCredentialCapability()

      expect(getState().credentialCapability).toBe('none')
    })

    it('persists a rotated password token before refreshing subscription', async () => {
      useAuthStore.setState({
        user: {
          id: 'user-1',
          email: 'person@example.com',
          name: 'Person',
          emailVerified: true,
        },
        credentialCapability: 'present',
      })
      vi.mocked(authClient.changePassword).mockResolvedValue({
        data: { token: 'rotated-token' },
        error: null,
      } as never)

      await getState().changePassword('old-password', 'new-password')

      expect(authClient.changePassword).toHaveBeenCalledWith(
        {
          currentPassword: 'old-password',
          newPassword: 'new-password',
          revokeOtherSessions: true,
        },
        expect.objectContaining({ onSuccess: expect.any(Function) }),
      )
      expect(await loadSessionToken()).toBe('rotated-token')
      expect(localStorage.getItem('session_token')).toBeNull()
      expect(invoke).toHaveBeenCalledWith('set_session_token', { token: 'rotated-token' })
      expect(vi.mocked(invoke).mock.invocationCallOrder[0]).toBeLessThan(
        vi.mocked(getSubscriptionStatus).mock.invocationCallOrder[0]!,
      )
    })

    it('sets a password for a verified OAuth-only account without rotating its token', async () => {
      await persistSessionToken('existing-token')
      vi.mocked(invoke).mockClear()
      useAuthStore.setState({
        user: {
          id: 'user-1',
          email: 'person@example.com',
          name: 'Person',
          emailVerified: true,
        },
        credentialCapability: 'none',
      })
      vi.mocked(authClient.listAccounts).mockResolvedValue({
        data: [{ providerId: 'google' }, { providerId: 'credential' }],
        error: null,
      } as never)

      await getState().changePassword(null, 'new-password')

      expect(setOpenTypelessPassword).toHaveBeenCalledWith('new-password')
      expect(authClient.changePassword).not.toHaveBeenCalled()
      expect(await loadSessionToken()).toBe('existing-token')
      expect(localStorage.getItem('session_token')).toBeNull()
      expect(getState().credentialCapability).toBe('present')
    })

    it('requires the protected browser verification flow before setting a password', async () => {
      useAuthStore.setState({
        user: {
          id: 'user-1',
          email: 'person@example.com',
          name: 'Person',
          emailVerified: false,
        },
        credentialCapability: 'none',
      })

      await expect(getState().changePassword(null, 'new-password')).rejects.toThrow(
        'Verify your email in the secure browser',
      )

      expect(authClient.sendVerificationEmail).not.toHaveBeenCalled()
      expect(setOpenTypelessPassword).not.toHaveBeenCalled()
      expect(getState().emailVerificationPending).toBe(true)
    })
  })

  describe('cloud session invalidation', () => {
    it('clears only cloud identity and keeps local data and BYOK values', async () => {
      localStorage.setItem('session_token', 'expired-token')
      localStorage.setItem('talkmore_history', '[{"id":"local"}]')
      localStorage.setItem('talkmore_dictionary', '["OpenTypeless"]')
      localStorage.setItem('byok_api_key', 'local-provider-key')
      useAuthStore.setState({
        user: {
          id: 'user-1',
          email: 'person@example.com',
          name: 'Person',
          emailVerified: true,
        },
        plan: 'pro',
        credentialCapability: 'present',
      })

      await getState().invalidateCloudSession()

      expect(getState().user).toBeNull()
      expect(getState().plan).toBe('free')
      expect(localStorage.getItem('session_token')).toBeNull()
      expect(localStorage.getItem('talkmore_history')).toBe('[{"id":"local"}]')
      expect(localStorage.getItem('talkmore_dictionary')).toBe('["OpenTypeless"]')
      expect(localStorage.getItem('byok_api_key')).toBe('local-provider-key')
    })
  })
})
