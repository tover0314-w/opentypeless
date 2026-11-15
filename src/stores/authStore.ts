import { create } from 'zustand'
import {
  authClient,
  requestOpenTypelessPasswordReset,
  setOpenTypelessPassword,
} from '../lib/auth-client'
import {
  clearSessionTokenFromMemory,
  CloudSessionDisabledError,
  loadSessionToken,
  markCloudSessionAuthenticated,
  persistSessionToken,
  persistOwnedSessionToken,
  registerCloudSessionInvalidation,
} from '../lib/cloud-session'
import {
  getSubscriptionStatus,
  type BillingProvider,
  type LicenseStatus,
  type QuotaModel,
  type SubscriptionPlan,
  type SubscriptionSource,
  type SubscriptionStatus,
} from '../lib/api'
import { isActiveCloudPlan } from '../lib/constants'
import { syncManagedSttCapability } from '../lib/managed-stt-capability'
import { toast } from '../components/toast-service'
import i18n from '../i18n'
import {
  clearLastKnownSubscription,
  isLastKnownSubscriptionUsable,
  loadLastKnownSubscription,
  saveLastKnownSubscription,
} from '../lib/last-known-subscription'
import { accountSnapshotMatches, isSubscriptionStatus } from '../lib/subscription-validation'
import { clearPendingDesktopCheckout } from '../lib/desktop-checkout-intent'

let subscriptionRefreshInFlight: {
  userId: string
  generation: number
  promise: Promise<void>
} | null = null
let credentialRequestId = 0
let authenticationGeneration = 0

function requireCurrentAuthentication(generation: number): void {
  if (generation !== authenticationGeneration) {
    throw new Error('Authentication request superseded')
  }
}

export interface AuthUser {
  id: string
  email: string
  name: string | null
  emailVerified: boolean
}

function authenticatedUser(value: unknown): AuthUser {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Invalid account response')
  }
  const user = value as Record<string, unknown>
  if (
    typeof user.id !== 'string' ||
    !user.id.trim() ||
    typeof user.email !== 'string' ||
    !user.email.trim() ||
    (user.name != null && typeof user.name !== 'string') ||
    (user.emailVerified !== undefined && typeof user.emailVerified !== 'boolean')
  ) {
    throw new Error('Invalid account response')
  }
  return {
    id: user.id,
    email: user.email,
    name: typeof user.name === 'string' ? user.name : null,
    emailVerified: user.emailVerified === true,
  }
}

export type CredentialCapability = 'unknown' | 'present' | 'none'
export type SubscriptionRefreshState = 'unknown' | 'fresh' | 'stale' | 'unavailable'

interface AuthState {
  // User
  user: AuthUser | null
  plan: SubscriptionPlan
  source: SubscriptionSource
  displayName: string
  subscriptionEnd: string | null
  subscriptionStatus: string | null
  licenseStatus: LicenseStatus | null
  billingProvider: BillingProvider
  cancelAtPeriodEnd: boolean
  canManageBilling: boolean
  canMigrateToStripe: boolean
  quotaModel: QuotaModel
  quotaDataAvailable?: boolean
  credentialCapability: CredentialCapability
  subscriptionRefreshState: SubscriptionRefreshState
  subscriptionRefreshLoading: boolean
  subscriptionLastVerifiedAt: string | null
  subscriptionRefreshError: string | null

  // Quotas
  displayWordsUsedEstimate: number
  displayWordsLimit: number
  displayWordsResetAt: string | null
  sttSecondsUsed: number
  sttSecondsLimit: number
  llmTokensUsed: number
  llmTokensLimit: number
  cloudWordsUsed: number
  cloudWordsLimit: number
  cloudWordsResetAt: string | null
  byokUnlimited: boolean

  // Loading
  loading: boolean
  error: string | null
  emailVerificationPending: boolean
  pendingEmail: string | null

  // Checkout flow
  checkoutPending: boolean
  subscriptionRefreshedAt: number | null

  // Actions
  initialize: () => Promise<void>
  signIn: (
    email: string,
    password: string,
    options?: { verificationCallbackURL?: string },
  ) => Promise<void>
  signUp: (
    email: string,
    password: string,
    name: string,
    options?: { verificationCallbackURL?: string },
  ) => Promise<void>
  resendVerification: (options?: { verificationCallbackURL?: string }) => Promise<void>
  requestPasswordReset: (email: string, locale: string) => Promise<void>
  refreshCredentialCapability: () => Promise<void>
  changePassword: (currentPassword: string | null, newPassword: string) => Promise<void>
  invalidateCloudSession: () => Promise<void>
  signOut: () => Promise<void>
  refreshSubscription: () => Promise<void>
  handleDeepLinkToken: (
    token: string,
    isCurrentOwner?: () => boolean,
    signal?: AbortSignal,
  ) => Promise<void>
}

export function hasManagedCloudAccess(
  state: Pick<AuthState, 'plan' | 'source' | 'cloudWordsLimit' | 'licenseStatus'> &
    Partial<Pick<AuthState, 'subscriptionRefreshState' | 'subscriptionLastVerifiedAt'>>,
): boolean {
  if (state.subscriptionRefreshState === 'unavailable') return false
  if (
    state.subscriptionRefreshState === 'stale' &&
    !isLastKnownSubscriptionUsable(state.subscriptionLastVerifiedAt ?? null)
  ) {
    return false
  }
  if (state.licenseStatus === 'refunded' || state.licenseStatus === 'deactivated') return false
  if (state.source === 'appsumo') {
    return state.cloudWordsLimit > 0 && state.licenseStatus === 'active'
  }
  if (state.source === 'lifetime') {
    return state.cloudWordsLimit > 0 || state.plan === 'lifetime_starter'
  }
  if ((state.source === 'creem' || state.source === 'stripe') && state.cloudWordsLimit > 0) {
    return true
  }
  return isActiveCloudPlan(state.plan)
}

function credentialCapabilityFromAccounts(accounts: unknown): CredentialCapability {
  if (
    !Array.isArray(accounts) ||
    accounts.some(
      (account) =>
        account === null ||
        typeof account !== 'object' ||
        Array.isArray(account) ||
        typeof account.providerId !== 'string' ||
        !account.providerId.trim() ||
        account.providerId !== account.providerId.trim(),
    )
  ) {
    throw new Error('Invalid account security response')
  }
  return accounts.some((account) => account.providerId === 'credential') ? 'present' : 'none'
}

function signedOutCloudState() {
  return {
    user: null,
    plan: 'free' as const,
    source: 'free' as const,
    displayName: 'Free',
    subscriptionEnd: null,
    subscriptionStatus: null,
    licenseStatus: null,
    billingProvider: null,
    cancelAtPeriodEnd: false,
    canManageBilling: false,
    canMigrateToStripe: false,
    quotaModel: 'legacy_dual_meter' as const,
    quotaDataAvailable: false,
    credentialCapability: 'unknown' as const,
    subscriptionRefreshState: 'unknown' as const,
    subscriptionRefreshLoading: false,
    subscriptionLastVerifiedAt: null,
    subscriptionRefreshError: null,
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
    error: null,
    emailVerificationPending: false,
    pendingEmail: null,
    checkoutPending: false,
    subscriptionRefreshedAt: null,
  }
}

function subscriptionStateFromStatus(status: SubscriptionStatus) {
  return {
    plan: status.plan,
    source: status.source,
    displayName: status.displayName,
    subscriptionEnd: status.subscriptionEnd,
    subscriptionStatus: status.subscriptionStatus,
    licenseStatus: status.licenseStatus ?? null,
    billingProvider: status.billingProvider ?? null,
    cancelAtPeriodEnd: status.cancelAtPeriodEnd === true,
    canManageBilling: status.canManageBilling === true,
    canMigrateToStripe: status.canMigrateToStripe === true,
    quotaModel: status.quotaModel,
    quotaDataAvailable: status.quotaDataAvailable,
    displayWordsUsedEstimate: status.displayWordsUsedEstimate,
    displayWordsLimit: status.displayWordsLimit,
    displayWordsResetAt: status.displayWordsResetAt,
    sttSecondsUsed: status.sttSecondsUsed,
    sttSecondsLimit: status.sttSecondsLimit,
    llmTokensUsed: status.llmTokensUsed,
    llmTokensLimit: status.llmTokensLimit,
    cloudWordsUsed: status.cloudWordsUsed,
    cloudWordsLimit: status.cloudWordsLimit,
    cloudWordsResetAt: status.cloudWordsResetAt,
    byokUnlimited: status.byokUnlimited,
  }
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  plan: 'free',
  source: 'free',
  displayName: 'Free',
  subscriptionEnd: null,
  subscriptionStatus: null,
  licenseStatus: null,
  billingProvider: null,
  cancelAtPeriodEnd: false,
  canManageBilling: false,
  canMigrateToStripe: false,
  quotaModel: 'legacy_dual_meter',
  credentialCapability: 'unknown',
  subscriptionRefreshState: 'unknown',
  subscriptionRefreshLoading: false,
  subscriptionLastVerifiedAt: null,
  subscriptionRefreshError: null,
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
  loading: false,
  error: null,
  emailVerificationPending: false,
  pendingEmail: null,
  checkoutPending: false,
  subscriptionRefreshedAt: null,

  initialize: async () => {
    const generation = ++authenticationGeneration
    try {
      set({ loading: true, error: null })
      const savedToken = await loadSessionToken()
      requireCurrentAuthentication(generation)
      const { data: session, error: sessionError } = await authClient.getSession()
      requireCurrentAuthentication(generation)
      if (sessionError) {
        throw new Error(sessionError.message ?? 'Failed to restore cloud session')
      }
      if (session === null && (savedToken || get().user)) {
        await get().invalidateCloudSession()
        return
      }
      if (session?.user) {
        const user = authenticatedUser(session.user)
        set({
          ...(get().user?.id !== user.id ? signedOutCloudState() : {}),
          emailVerificationPending: false,
          pendingEmail: null,
          credentialCapability: get().user?.id === user.id ? get().credentialCapability : 'unknown',
          user,
        })
        if (savedToken) {
          markCloudSessionAuthenticated()
        }
        const accountSecurity = get()
          .refreshCredentialCapability()
          .catch(() => {
            console.warn('Failed to restore account security capability')
          })
        await Promise.all([accountSecurity, get().refreshSubscription()])
        requireCurrentAuthentication(generation)
      }
    } catch (error) {
      if (generation !== authenticationGeneration) return
      const message = error instanceof Error ? error.message : String(error)
      console.warn('Failed to restore cloud session')
      set({ error: message })
    } finally {
      if (generation === authenticationGeneration) set({ loading: false })
    }
  },

  signIn: async (email, password, options) => {
    const generation = ++authenticationGeneration
    set({ loading: true, error: null })
    let responseToken: string | null = null
    try {
      const { data, error } = await authClient.signIn.email(
        { email, password },
        {
          onSuccess: async (ctx) => {
            requireCurrentAuthentication(generation)
            responseToken = ctx.response.headers.get('set-auth-token')
          },
        },
      )
      requireCurrentAuthentication(generation)
      if (error) {
        if (error.code === 'EMAIL_NOT_VERIFIED') {
          set({ emailVerificationPending: true, pendingEmail: email })
          if (options?.verificationCallbackURL) {
            const verification = await authClient.sendVerificationEmail({
              email,
              callbackURL: options.verificationCallbackURL,
            })
            requireCurrentAuthentication(generation)
            if (verification.error) {
              throw new Error(verification.error.message ?? 'Failed to send verification email')
            }
          }
          return
        }
        throw new Error(error.message ?? 'Sign in failed')
      }
      if (!data?.user) throw new Error('Sign in failed')
      const user = authenticatedUser(data.user)
      const token = data.token?.trim() ? data.token : responseToken
      if (!token?.trim()) throw new Error('Sign in failed')
      await persistSessionToken(token, () => generation === authenticationGeneration)
      requireCurrentAuthentication(generation)
      markCloudSessionAuthenticated()
      if (data.user) {
        set({
          ...(get().user?.id !== user.id ? signedOutCloudState() : {}),
          emailVerificationPending: false,
          pendingEmail: null,
          credentialCapability: get().user?.id === user.id ? get().credentialCapability : 'unknown',
          user,
        })
        const accountSecurity = get()
          .refreshCredentialCapability()
          .catch(() => {
            requireCurrentAuthentication(generation)
            set({ credentialCapability: 'unknown' })
            console.warn('Failed to load account security after sign-in')
          })
        await Promise.all([accountSecurity, get().refreshSubscription()])
        requireCurrentAuthentication(generation)
      }
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Sign in failed'
      if (generation === authenticationGeneration && e instanceof CloudSessionDisabledError) {
        set({ ...signedOutCloudState(), loading: false })
      }
      if (generation === authenticationGeneration) set({ error: msg })
      throw e
    } finally {
      if (generation === authenticationGeneration) set({ loading: false })
    }
  },

  signUp: async (email, password, name, options) => {
    const generation = ++authenticationGeneration
    set({ loading: true, error: null, emailVerificationPending: false })
    try {
      const { error } = await authClient.signUp.email(
        {
          email,
          password,
          name,
          ...(options?.verificationCallbackURL
            ? { callbackURL: options.verificationCallbackURL }
            : {}),
        },
        {
          onSuccess: async (ctx) => {
            requireCurrentAuthentication(generation)
            const token = ctx.response.headers.get('set-auth-token')
            if (token) {
              await persistSessionToken(token, () => generation === authenticationGeneration)
              requireCurrentAuthentication(generation)
              markCloudSessionAuthenticated()
            }
          },
        },
      )
      requireCurrentAuthentication(generation)
      if (error) throw new Error(error.message ?? 'Sign up failed')
      // Email verification is required — don't set user yet
      set({ emailVerificationPending: true, pendingEmail: email })
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Sign up failed'
      if (generation === authenticationGeneration && e instanceof CloudSessionDisabledError) {
        set({ ...signedOutCloudState(), loading: false })
      }
      if (generation === authenticationGeneration) set({ error: msg })
      throw e
    } finally {
      if (generation === authenticationGeneration) set({ loading: false })
    }
  },

  resendVerification: async (options) => {
    const email = get().pendingEmail
    if (!email) return
    const generation = authenticationGeneration
    set({ loading: true, error: null })
    try {
      const { error } = await authClient.sendVerificationEmail({
        email,
        ...(options?.verificationCallbackURL
          ? { callbackURL: options.verificationCallbackURL }
          : {}),
      })
      if (error) throw new Error(error.message ?? 'Failed to resend')
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Failed to resend verification email'
      if (generation === authenticationGeneration) set({ error: msg })
    } finally {
      if (generation === authenticationGeneration) set({ loading: false })
    }
  },

  requestPasswordReset: async (email, locale) => {
    const generation = authenticationGeneration
    set({ loading: true, error: null })
    try {
      await requestOpenTypelessPasswordReset(email, locale)
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to request password reset'
      if (generation === authenticationGeneration) set({ error: message })
      throw e
    } finally {
      if (generation === authenticationGeneration) set({ loading: false })
    }
  },

  refreshCredentialCapability: async () => {
    const requestId = ++credentialRequestId
    const user = get().user
    try {
      const result = await authClient.listAccounts()
      if (get().user !== user || requestId !== credentialRequestId) return
      if (result.error) {
        throw new Error(result.error.message ?? 'Failed to load account security')
      }
      set({ credentialCapability: credentialCapabilityFromAccounts(result.data) })
    } catch (error) {
      if (get().user !== user || requestId !== credentialRequestId) return
      set({ credentialCapability: 'unknown' })
      throw error
    }
  },

  changePassword: async (currentPassword, newPassword) => {
    const state = get()
    if (!state.user) throw new Error('Authentication required')
    const requireSameAccount = () => {
      if (get().user !== state.user) {
        throw new Error(i18n.t('account.changedDuringPasswordUpdate'))
      }
    }

    let capability = state.credentialCapability
    if (capability === 'unknown') {
      await state.refreshCredentialCapability()
      requireSameAccount()
      capability = get().credentialCapability
    }
    if (capability === 'unknown') throw new Error('Failed to load account security')

    set({ loading: true, error: null })
    try {
      if (capability === 'none') {
        if (!state.user.emailVerified) {
          set({ emailVerificationPending: true, pendingEmail: state.user.email })
          throw new Error('Verify your email in the secure browser before setting a password')
        }

        await setOpenTypelessPassword(newPassword)
        requireSameAccount()
        try {
          await get().refreshCredentialCapability()
        } catch {
          console.warn('Failed to refresh account security after password setup')
        }
      } else {
        if (!currentPassword) throw new Error('Current password is required')

        let responseToken: string | null = null
        const result = await authClient.changePassword(
          {
            currentPassword,
            newPassword,
            revokeOtherSessions: true,
          },
          {
            onSuccess: (ctx) => {
              responseToken = ctx.response.headers.get('set-auth-token')
            },
          },
        )
        requireSameAccount()
        if (result.error) throw new Error(result.error.message ?? 'Failed to change password')

        const rotatedToken = result.data?.token ?? responseToken
        if (!rotatedToken) throw new Error('Password changed but the new session token was missing')
        await persistSessionToken(rotatedToken, () => get().user === state.user)
        requireSameAccount()
        markCloudSessionAuthenticated()
        await get().refreshSubscription()
      }
      requireSameAccount()
      toast(i18n.t('account.passwordChanged', 'Password updated'), 'success')
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to change password'
      if (get().user === state.user && e instanceof CloudSessionDisabledError) {
        set({ ...signedOutCloudState(), loading: false, error: message })
      }
      if (get().user === state.user) set({ error: message })
      throw e
    } finally {
      if (get().user === state.user) set({ loading: false })
    }
  },

  invalidateCloudSession: async () => {
    const generation = ++authenticationGeneration
    const userId = get().user?.id
    clearSessionTokenFromMemory()
    if (userId) clearLastKnownSubscription(localStorage, userId)
    clearPendingDesktopCheckout(localStorage)
    set({ ...signedOutCloudState(), loading: false })
    try {
      await persistSessionToken(null)
    } catch {
      if (generation === authenticationGeneration) clearSessionTokenFromMemory()
      console.error('Failed to clear session token in backend')
    }
    if (generation !== authenticationGeneration) return
    toast(i18n.t('account.sessionExpired', 'Your cloud session expired. Sign in again.'), 'error')
  },

  signOut: async () => {
    const generation = ++authenticationGeneration
    const userId = get().user?.id
    try {
      await authClient.signOut()
    } finally {
      if (generation === authenticationGeneration) {
        await persistSessionToken(null).catch(() => {
          if (generation === authenticationGeneration) clearSessionTokenFromMemory()
          console.error('Failed to clear session token in backend')
        })
        if (generation === authenticationGeneration) {
          if (userId) clearLastKnownSubscription(localStorage, userId)
          clearPendingDesktopCheckout(localStorage)
          set({ ...signedOutCloudState(), loading: false })
        }
      }
    }
  },

  refreshSubscription: () => {
    const userId = get().user?.id
    if (!userId) return Promise.resolve()
    const generation = authenticationGeneration
    const isCurrentSession = () =>
      get().user?.id === userId && generation === authenticationGeneration
    if (
      subscriptionRefreshInFlight?.userId === userId &&
      subscriptionRefreshInFlight.generation === generation
    ) {
      return subscriptionRefreshInFlight.promise
    }

    const refresh = (async () => {
      if (!get().subscriptionLastVerifiedAt) {
        const cached = loadLastKnownSubscription(localStorage, userId)
        if (cached) {
          set({
            ...subscriptionStateFromStatus(cached.snapshot),
            subscriptionRefreshState: 'stale',
            subscriptionLastVerifiedAt: cached.verifiedAt,
          })
        }
      }

      set({ subscriptionRefreshLoading: true, subscriptionRefreshError: null })
      try {
        const status = await getSubscriptionStatus()
        if (!isCurrentSession()) return
        if (!isSubscriptionStatus(status)) {
          throw new Error('Invalid subscription response')
        }
        if (!accountSnapshotMatches(status, userId)) {
          throw new Error('Subscription account does not match the authenticated user')
        }
        const verifiedAt = new Date().toISOString()
        set({
          ...subscriptionStateFromStatus(status),
          subscriptionRefreshState: 'fresh',
          subscriptionLastVerifiedAt: verifiedAt,
          subscriptionRefreshedAt: Date.now(),
        })
        saveLastKnownSubscription(localStorage, userId, status, verifiedAt)
        try {
          await syncManagedSttCapability(status.accountSnapshot ?? null, userId)
        } catch {
          if (!isCurrentSession()) return
          console.warn('Failed to sync managed STT capability; using the safe fallback.')
        }
        if (!isCurrentSession()) return
        // Clear checkout pending flag after first post-checkout refresh
        if (get().checkoutPending) {
          set({ checkoutPending: false })
        }
      } catch (e) {
        if (!isCurrentSession()) return
        const message = e instanceof Error ? e.message : String(e)
        set({
          subscriptionRefreshState: get().subscriptionLastVerifiedAt ? 'stale' : 'unavailable',
          subscriptionRefreshError: message,
        })
        console.warn('Failed to refresh subscription status')
      } finally {
        if (isCurrentSession()) set({ subscriptionRefreshLoading: false })
      }
    })()
    subscriptionRefreshInFlight = { userId, generation, promise: refresh }
    void refresh.finally(() => {
      if (subscriptionRefreshInFlight?.promise === refresh) subscriptionRefreshInFlight = null
    })
    return refresh
  },

  handleDeepLinkToken: async (token: string, flowOwner, signal) => {
    const isCurrentOwner = () => (flowOwner?.() ?? true) && !signal?.aborted
    const generation = ++authenticationGeneration
    const requireOwner = () => {
      requireCurrentAuthentication(generation)
      if (!isCurrentOwner()) throw new Error('Authentication request cancelled')
    }
    try {
      requireOwner()
      set({ loading: true, error: null })
      const { data: session, error: sessionError } = await authClient.getSession({
        fetchOptions: {
          headers: { Authorization: `Bearer ${token}` },
          ...(signal ? { signal } : {}),
        },
      })
      requireOwner()
      if (sessionError || !session?.user) {
        throw new Error('Failed to authenticate with token')
      }
      const user = authenticatedUser(session.user)
      const ownsCommit = () => generation === authenticationGeneration && isCurrentOwner()
      if (flowOwner) await persistOwnedSessionToken(token, ownsCommit, signal)
      else await persistSessionToken(token, ownsCommit)
      requireCurrentAuthentication(generation)
      markCloudSessionAuthenticated()
      if (session?.user) {
        set({
          ...(get().user?.id !== user.id ? signedOutCloudState() : {}),
          emailVerificationPending: false,
          pendingEmail: null,
          credentialCapability: get().user?.id === user.id ? get().credentialCapability : 'unknown',
          user,
        })
        const accountSecurity = get()
          .refreshCredentialCapability()
          .catch(() => {
            console.warn('Failed to load account security after OAuth sign-in')
          })
        await Promise.all([accountSecurity, get().refreshSubscription()])
        requireCurrentAuthentication(generation)
      }
    } catch (error) {
      if (generation === authenticationGeneration && error instanceof CloudSessionDisabledError) {
        set({ ...signedOutCloudState(), loading: false })
      }
      if (generation === authenticationGeneration && isCurrentOwner()) {
        set({ error: 'Failed to authenticate with token' })
      }
      throw error
    } finally {
      if (generation === authenticationGeneration) set({ loading: false })
    }
  },
}))

registerCloudSessionInvalidation(() => useAuthStore.getState().invalidateCloudSession())
