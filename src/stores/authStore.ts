import { create } from 'zustand'
import {
  authClient,
  requestOpenTypelessPasswordReset,
  setOpenTypelessPassword,
} from '../lib/auth-client'
import {
  clearSessionTokenFromMemory,
  loadSessionToken,
  markCloudSessionAuthenticated,
  persistSessionToken,
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
import { clearPendingDesktopCheckout } from '../lib/desktop-checkout-intent'

let sttWarningShown = false
let llmWarningShown = false
let cloudWordsWarningShown = false
let subscriptionRefreshInFlight: Promise<void> | null = null

export interface AuthUser {
  id: string
  email: string
  name: string | null
  emailVerified: boolean
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
  handleDeepLinkToken: (token: string) => Promise<void>
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

function credentialCapabilityFromAccounts(
  accounts: Array<{ providerId: string }>,
): CredentialCapability {
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
    try {
      set({ loading: true, error: null })
      const savedToken = await loadSessionToken()
      const { data: session } = await authClient.getSession()
      if (session?.user) {
        set({
          user: {
            id: session.user.id,
            email: session.user.email,
            name: session.user.name ?? null,
            emailVerified: session.user.emailVerified === true,
          },
        })
        if (savedToken) {
          markCloudSessionAuthenticated()
        }
        await get().refreshCredentialCapability()
        await get().refreshSubscription()
      }
    } catch {
      // Not logged in — that's fine
    } finally {
      set({ loading: false })
    }
  },

  signIn: async (email, password, options) => {
    set({ loading: true, error: null })
    try {
      const { data, error } = await authClient.signIn.email(
        { email, password },
        {
          onSuccess: async (ctx) => {
            const token = ctx.response.headers.get('set-auth-token')
            if (token) {
              await persistSessionToken(token)
              markCloudSessionAuthenticated()
            }
          },
        },
      )
      if (error) {
        if (error.code === 'EMAIL_NOT_VERIFIED') {
          set({ emailVerificationPending: true, pendingEmail: email })
          if (options?.verificationCallbackURL) {
            const verification = await authClient.sendVerificationEmail({
              email,
              callbackURL: options.verificationCallbackURL,
            })
            if (verification.error) {
              throw new Error(verification.error.message ?? 'Failed to send verification email')
            }
          }
          return
        }
        throw new Error(error.message ?? 'Sign in failed')
      }
      if (data?.user) {
        set({
          user: {
            id: data.user.id,
            email: data.user.email,
            name: data.user.name ?? null,
            emailVerified: data.user.emailVerified === true,
          },
        })
        await get().refreshCredentialCapability()
        await get().refreshSubscription()
      }
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Sign in failed'
      set({ error: msg })
      throw e
    } finally {
      set({ loading: false })
    }
  },

  signUp: async (email, password, name, options) => {
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
            const token = ctx.response.headers.get('set-auth-token')
            if (token) {
              await persistSessionToken(token)
              markCloudSessionAuthenticated()
            }
          },
        },
      )
      if (error) throw new Error(error.message ?? 'Sign up failed')
      // Email verification is required — don't set user yet
      set({ emailVerificationPending: true, pendingEmail: email })
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Sign up failed'
      set({ error: msg })
      throw e
    } finally {
      set({ loading: false })
    }
  },

  resendVerification: async (options) => {
    const email = get().pendingEmail
    if (!email) return
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
      set({ error: msg })
    } finally {
      set({ loading: false })
    }
  },

  requestPasswordReset: async (email, locale) => {
    set({ loading: true, error: null })
    try {
      await requestOpenTypelessPasswordReset(email, locale)
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to request password reset'
      set({ error: message })
      throw e
    } finally {
      set({ loading: false })
    }
  },

  refreshCredentialCapability: async () => {
    const result = await authClient.listAccounts()
    if (result.error) {
      set({ credentialCapability: 'unknown' })
      throw new Error(result.error.message ?? 'Failed to load account security')
    }
    set({ credentialCapability: credentialCapabilityFromAccounts(result.data ?? []) })
  },

  changePassword: async (currentPassword, newPassword) => {
    const state = get()
    if (!state.user) throw new Error('Authentication required')

    let capability = state.credentialCapability
    if (capability === 'unknown') {
      await state.refreshCredentialCapability()
      capability = get().credentialCapability
    }
    if (capability === 'unknown') throw new Error('Failed to load account security')

    set({ loading: true, error: null })
    try {
      if (capability === 'none') {
        if (!state.user.emailVerified) {
          const verification = await authClient.sendVerificationEmail({ email: state.user.email })
          if (verification.error) {
            throw new Error(verification.error.message ?? 'Failed to send verification email')
          }
          set({ emailVerificationPending: true, pendingEmail: state.user.email })
          throw new Error('Verify your email before setting a password')
        }

        await setOpenTypelessPassword(newPassword)
        await get().refreshCredentialCapability()
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
        if (result.error) throw new Error(result.error.message ?? 'Failed to change password')

        const rotatedToken = result.data?.token ?? responseToken
        if (!rotatedToken) throw new Error('Password changed but the new session token was missing')
        await persistSessionToken(rotatedToken)
        markCloudSessionAuthenticated()
        await get().refreshSubscription()
      }
      toast(i18n.t('account.passwordChanged', 'Password updated'), 'success')
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to change password'
      set({ error: message })
      throw e
    } finally {
      set({ loading: false })
    }
  },

  invalidateCloudSession: async () => {
    const userId = get().user?.id
    try {
      await persistSessionToken(null)
    } catch (e) {
      clearSessionTokenFromMemory()
      console.error('Failed to clear session token in backend:', e)
    }
    if (userId) clearLastKnownSubscription(localStorage, userId)
    clearPendingDesktopCheckout(localStorage)
    set(signedOutCloudState())
    sttWarningShown = false
    llmWarningShown = false
    cloudWordsWarningShown = false
    toast(i18n.t('account.sessionExpired', 'Your cloud session expired. Sign in again.'), 'error')
  },

  signOut: async () => {
    const userId = get().user?.id
    try {
      await authClient.signOut()
    } finally {
      await persistSessionToken(null).catch((e: unknown) => {
        clearSessionTokenFromMemory()
        console.error('Failed to clear session token in backend:', e)
      })
      if (userId) clearLastKnownSubscription(localStorage, userId)
      clearPendingDesktopCheckout(localStorage)
      set(signedOutCloudState())
      sttWarningShown = false
      llmWarningShown = false
      cloudWordsWarningShown = false
    }
  },

  refreshSubscription: () => {
    const userId = get().user?.id
    if (!userId) return Promise.resolve()
    if (subscriptionRefreshInFlight) return subscriptionRefreshInFlight

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
        if (get().user?.id !== userId) return
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
        } catch (error) {
          console.warn('Failed to sync managed STT capability; using the safe fallback.', error)
        }
        // Clear checkout pending flag after first post-checkout refresh
        if (get().checkoutPending) {
          set({ checkoutPending: false })
        }
        const wordsUsed =
          status.quotaModel === 'legacy_dual_meter' && status.displayWordsLimit > 0
            ? status.displayWordsUsedEstimate
            : status.cloudWordsUsed
        const wordsLimit =
          status.quotaModel === 'legacy_dual_meter' && status.displayWordsLimit > 0
            ? status.displayWordsLimit
            : status.cloudWordsLimit
        if (wordsLimit > 0 && wordsUsed / wordsLimit >= 0.9) {
          if (!cloudWordsWarningShown) {
            toast(i18n.t('account.cloudQuotaWarning', 'Cloud words are almost used up.'), 'error')
            cloudWordsWarningShown = true
          }
          sttWarningShown = true
          llmWarningShown = true
        } else {
          cloudWordsWarningShown = false
        }
        if (
          status.sttSecondsLimit > 0 &&
          status.sttSecondsUsed / status.sttSecondsLimit >= 0.9 &&
          !sttWarningShown
        ) {
          toast(i18n.t('account.sttQuotaWarning'), 'error')
          sttWarningShown = true
        }
        if (
          status.llmTokensLimit > 0 &&
          status.llmTokensUsed / status.llmTokensLimit >= 0.9 &&
          !llmWarningShown
        ) {
          toast(i18n.t('account.llmQuotaWarning'), 'error')
          llmWarningShown = true
        }
      } catch (e) {
        if (get().user?.id !== userId) return
        const message = e instanceof Error ? e.message : String(e)
        set({
          subscriptionRefreshState: get().subscriptionLastVerifiedAt ? 'stale' : 'unavailable',
          subscriptionRefreshError: message,
        })
        console.warn('Failed to refresh subscription status:', message)
      } finally {
        if (get().user?.id === userId) set({ subscriptionRefreshLoading: false })
      }
    })()
    subscriptionRefreshInFlight = refresh
    void refresh.finally(() => {
      if (subscriptionRefreshInFlight === refresh) subscriptionRefreshInFlight = null
    })
    return refresh
  },

  handleDeepLinkToken: async (token: string) => {
    try {
      set({ loading: true, error: null })
      await persistSessionToken(token)
      markCloudSessionAuthenticated()
      const { data: session } = await authClient.getSession({
        fetchOptions: {
          headers: { Authorization: `Bearer ${token}` },
        },
      })
      if (session?.user) {
        set({
          user: {
            id: session.user.id,
            email: session.user.email,
            name: session.user.name ?? null,
            emailVerified: session.user.emailVerified === true,
          },
        })
        await get().refreshCredentialCapability()
        await get().refreshSubscription()
      }
    } catch {
      set({ error: 'Failed to authenticate with token' })
    } finally {
      set({ loading: false })
    }
  },
}))

registerCloudSessionInvalidation(() => useAuthStore.getState().invalidateCloudSession())
