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
  loadSessionToken,
  persistSessionToken,
  resetCloudSessionCoordinatorForTests,
} from '../../lib/cloud-session'
import { toast } from '../../components/toast-service'

function getState() {
  return useAuthStore.getState()
}

describe('authStore', () => {
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
    vi.mocked(getSubscriptionStatus).mockResolvedValue({
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
        userId: '1',
        managedSttCapabilities: null,
        generatedAt: '2026-07-22T08:00:00.000Z',
      },
    })
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

    it('shows cloud words quota warning only once while usage stays high', async () => {
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

      expect(toast).toHaveBeenCalledTimes(1)
      expect(toast).toHaveBeenCalledWith('Cloud words are almost used up.', 'error')
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
    it('sets loading during initialization', async () => {
      const promise = getState().initialize()
      expect(getState().loading).toBe(true)
      await promise
      expect(getState().loading).toBe(false)
    })

    it('stays null user when no session exists', async () => {
      await getState().initialize()
      expect(getState().user).toBeNull()
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
  })

  describe('password actions', () => {
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

    it('resends verification instead of setting a password for an unverified account', async () => {
      useAuthStore.setState({
        user: {
          id: 'user-1',
          email: 'person@example.com',
          name: 'Person',
          emailVerified: false,
        },
        credentialCapability: 'none',
      })

      await expect(getState().changePassword(null, 'new-password')).rejects.toThrow()

      expect(authClient.sendVerificationEmail).toHaveBeenCalledWith({
        email: 'person@example.com',
      })
      expect(setOpenTypelessPassword).not.toHaveBeenCalled()
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
