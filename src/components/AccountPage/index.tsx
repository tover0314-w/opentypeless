import { useState, useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import {
  LogOut,
  Upload,
  Download,
  Loader2,
  ExternalLink,
  ClipboardCheck,
  Mail,
  KeyRound,
  UserCircle,
} from 'lucide-react'
import { openUrl } from '@tauri-apps/plugin-opener'
import { readText } from '@tauri-apps/plugin-clipboard-manager'
import { hasManagedCloudAccess, useAuthStore } from '../../stores/authStore'
import { API_BASE_URL } from '../../lib/constants'
import { usageWords } from '../../lib/usage-words'
import { useRoute } from '../../lib/router'
import { createCheckout, createPortalSession } from '../../lib/api'
import {
  EMAIL_VERIFICATION_STATE_TTL_MS,
  OAUTH_STATE_TTL_MS,
  clearOAuthState,
  handleDeepLinkUrl,
} from '../../lib/deep-link'
import {
  claimDesktopAuthCallbackURL,
  createDesktopWebAuthURL,
  DesktopAuthError,
} from '../../lib/desktop-auth-callback'
import { readPendingDesktopCheckout } from '../../lib/desktop-checkout-intent'
import { shouldRefreshSubscriptionOnAccountOpen } from '../../lib/subscription-refresh-policy'
import { PasswordDialog } from './PasswordDialog'
import { PasswordField } from './PasswordField'
import { useAccountBackup } from './useAccountBackup'
import { QuotaBar } from './QuotaBar'
import { toast } from '../toast-service'

type Tab = 'signin' | 'signup'
type AuthMode = 'auth' | 'forgot'

function accountErrorMessage(message: string | null, t: ReturnType<typeof useTranslation>['t']) {
  if (!message) return null
  const normalized = message.toLowerCase()
  if (
    normalized.includes('load failed') ||
    normalized.includes('failed to fetch') ||
    normalized.includes('network')
  ) {
    return t(
      'account.networkError',
      'Could not reach OpenTypeless cloud. Check your connection and try again.',
    )
  }
  return t('account.signInUnavailable')
}

function desktopAuthErrorMessage(
  error: DesktopAuthError,
  t: ReturnType<typeof useTranslation>['t'],
) {
  if (error.status !== null) {
    return t(
      'account.signInUnavailableWithStatus',
      'The sign-in service is temporarily unavailable (HTTP {{status}}). Please try again later.',
      { status: error.status },
    )
  }
  return t(
    'account.signInUnavailable',
    'The sign-in service is temporarily unavailable. Please try again later.',
  )
}

export function AccountPage({ embedded = false }: { embedded?: boolean } = {}) {
  const { user, loading } = useAuthStore()

  let content: React.ReactNode
  if (loading && !user) {
    content = (
      <div className="flex items-center justify-center py-20">
        <Loader2 size={20} className="animate-spin text-text-tertiary" />
      </div>
    )
  } else if (!user) {
    content = <AuthForm />
  } else {
    content = <AccountDetails key={user.id} embedded={embedded} />
  }

  return embedded ? <div className="account-embedded">{content}</div> : content
}

function AuthForm() {
  const [tab, setTab] = useState<Tab>('signin')
  const [mode, setMode] = useState<AuthMode>('auth')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const { signIn, loading, error, emailVerificationPending } = useAuthStore()
  const [localError, setLocalError] = useState<string | null>(null)
  const [oauthPending, setOauthPending] = useState<'google' | 'github' | 'browser' | null>(null)
  const oauthDeadline = useRef<number | null>(null)
  const loginAttemptRef = useRef(0)
  const clipboardAttemptRef = useRef<number | null>(null)
  const { t, i18n } = useTranslation()
  const authLocale = i18n.resolvedLanguage ?? i18n.language ?? 'en'
  const pendingCheckout = readPendingDesktopCheckout(localStorage)
  const pendingPlan =
    pendingCheckout?.product === 'lifetime_starter' ? t('upgrade.lifetime') : t('upgrade.pro')

  useEffect(
    () => () => {
      loginAttemptRef.current += 1
    },
    [],
  )

  // Keep the UI timeout aligned with the in-memory OAuth proof TTL.
  useEffect(() => {
    if (!oauthPending) {
      oauthDeadline.current = null
      return
    }
    if (oauthDeadline.current === null) {
      oauthDeadline.current =
        performance.now() +
        (oauthPending === 'browser' ? EMAIL_VERIFICATION_STATE_TTL_MS : OAUTH_STATE_TTL_MS)
    }
    const attempt = loginAttemptRef.current
    const timer = setTimeout(
      () => {
        if (loginAttemptRef.current !== attempt) return
        loginAttemptRef.current += 1
        setOauthPending(null)
        clearOAuthState()
        setLocalError(t('account.oauthTimeout', 'Sign in timed out. Please try again.'))
      },
      Math.max(0, oauthDeadline.current - performance.now()),
    )
    return () => clearTimeout(timer)
  }, [oauthPending, t])

  const displayError = localError ?? accountErrorMessage(error, t)

  const handleBrowserAuth = async (browserMode: 'signup' | 'verify' | 'forgot') => {
    const attempt = ++loginAttemptRef.current
    try {
      setOauthPending('browser')
      setLocalError(null)
      useAuthStore.setState({ error: null })
      const url = await createDesktopWebAuthURL(
        browserMode,
        EMAIL_VERIFICATION_STATE_TTL_MS,
        authLocale,
      )
      if (loginAttemptRef.current !== attempt) return
      await openUrl(url)
    } catch (error) {
      if (loginAttemptRef.current !== attempt) return
      clearOAuthState()
      setOauthPending(null)
      setLocalError(
        error instanceof DesktopAuthError
          ? desktopAuthErrorMessage(error, t)
          : t('account.oauthFailed'),
      )
    }
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setLocalError(null)
    try {
      if (mode === 'forgot') {
        await handleBrowserAuth('forgot')
      } else {
        await signIn(email, password)
      }
    } catch (error) {
      if (error instanceof DesktopAuthError) {
        setLocalError(desktopAuthErrorMessage(error, t))
      }
      // Error already set in store
    }
  }

  if (emailVerificationPending) {
    return (
      <div className="account-auth space-y-5 text-[14px]">
        <Mail size={32} className="mx-auto text-text-secondary" aria-hidden="true" />
        <h2 className="text-[16px] font-semibold text-text-primary">
          {t('account.verifyEmailTitle', 'Check your email')}
        </h2>
        <p className="text-text-secondary">
          {t(
            'account.verifyEmailDesc',
            'We sent a verification link to your email. Please click the link to verify your account, then come back and sign in.',
          )}
        </p>
        <div className="flex flex-col items-center gap-2 mt-4">
          <button
            onClick={async () => {
              await handleBrowserAuth('verify')
            }}
            disabled={loading || oauthPending === 'browser'}
            className="px-4 py-2 rounded-[8px] bg-accent text-white text-[13px] font-medium cursor-pointer border-none hover:opacity-90 transition-opacity disabled:opacity-50"
          >
            {loading || oauthPending === 'browser'
              ? t('account.sending', 'Sending...')
              : t('account.resendVerification', 'Resend verification email')}
          </button>
          {displayError && (
            <p className="text-error text-[12px] [overflow-wrap:anywhere]">{displayError}</p>
          )}
          <button
            onClick={() => {
              loginAttemptRef.current += 1
              useAuthStore.setState({ emailVerificationPending: false, pendingEmail: null })
              clearOAuthState()
              setTab('signin')
              setOauthPending(null)
            }}
            className="px-4 py-2 rounded-[8px] bg-bg-secondary border border-border text-text-primary text-[13px] cursor-pointer hover:bg-bg-tertiary transition-colors"
          >
            {t('account.backToSignIn', 'Back to Sign In')}
          </button>
        </div>
      </div>
    )
  }

  const handleOAuth = async (provider: 'google' | 'github') => {
    const attempt = ++loginAttemptRef.current
    try {
      setOauthPending(provider)
      setLocalError(null)
      useAuthStore.setState({ error: null })
      const callbackURL = await claimDesktopAuthCallbackURL(OAUTH_STATE_TTL_MS, authLocale)
      if (!callbackURL || loginAttemptRef.current !== attempt) return
      // Open the desktop-oauth bridge route in the system browser. The server
      // internally POSTs to Better Auth, then 302-redirects the browser to the
      // OAuth provider while forwarding the state cookie — keeping cookie and
      // callback in the same browser context.
      const url = `${API_BASE_URL}/api/auth/desktop-oauth?provider=${provider}&callbackURL=${encodeURIComponent(callbackURL)}`
      await openUrl(url)
    } catch (error) {
      if (loginAttemptRef.current !== attempt) return
      clearOAuthState()
      setOauthPending(null)
      setLocalError(
        error instanceof DesktopAuthError
          ? desktopAuthErrorMessage(error, t)
          : t('account.oauthFailed'),
      )
    }
  }

  const handleClipboardOAuth = async () => {
    const attempt = loginAttemptRef.current
    if (clipboardAttemptRef.current === attempt) return
    clipboardAttemptRef.current = attempt
    const isCurrentAttempt = () => loginAttemptRef.current === attempt
    setLocalError(null)
    try {
      const text = (await readText()).trim()
      if (!isCurrentAttempt()) return
      const handled = await handleDeepLinkUrl(text, isCurrentAttempt)
      if (!isCurrentAttempt()) return
      if (!handled) {
        setLocalError(t('account.oauthClipboardInvalid', 'No valid sign-in link found.'))
      }
    } catch {
      if (!isCurrentAttempt()) return
      setLocalError(t('account.oauthClipboardInvalid', 'No valid sign-in link found.'))
    } finally {
      if (clipboardAttemptRef.current === attempt) clipboardAttemptRef.current = null
    }
  }

  if (oauthPending) {
    return (
      <div className="account-auth text-[14px] text-center">
        <div className="space-y-5">
          {/* Provider icon with breathing animation */}
          <div className="flex justify-center">
            <div className="w-12 h-12 rounded-[12px] flex items-center justify-center bg-bg-secondary">
              {oauthPending === 'browser' ? (
                <UserCircle size={24} className="text-accent" />
              ) : oauthPending === 'google' ? (
                <svg width="24" height="24" viewBox="0 0 24 24">
                  <path
                    d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z"
                    fill="#4285F4"
                  />
                  <path
                    d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
                    fill="#34A853"
                  />
                  <path
                    d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"
                    fill="#FBBC05"
                  />
                  <path
                    d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"
                    fill="#EA4335"
                  />
                </svg>
              ) : (
                <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0 0 24 12c0-6.63-5.37-12-12-12z" />
                </svg>
              )}
            </div>
          </div>

          <div className="space-y-1.5">
            <p className="text-[15px] font-medium text-text-primary">
              {t('account.oauthPendingTitle', 'Completing sign in...')}
            </p>
            <p className="text-text-secondary text-[12px]">
              {t(
                'account.oauthPendingDesc',
                "Finish signing in with your browser. You'll be redirected back automatically.",
              )}
            </p>
            <p className="text-text-tertiary text-[11px] leading-relaxed">
              {t(
                'account.oauthPendingHint',
                'If your browser shows “Load failed”, copy the browser address and use Complete from clipboard below.',
              )}
            </p>
          </div>

          <div className="flex justify-center">
            <Loader2
              size={18}
              className="animate-spin text-accent motion-reduce:animate-none"
              aria-hidden="true"
            />
          </div>

          {displayError && (
            <p className="text-error text-[12px] [overflow-wrap:anywhere]">{displayError}</p>
          )}

          <div className="flex flex-col gap-2">
            <button
              onClick={handleClipboardOAuth}
              className="btn-outline min-h-10 px-4 text-[14px] cursor-pointer inline-flex items-center justify-center gap-2"
            >
              <ClipboardCheck size={14} />
              {t('account.completeFromClipboard', 'Complete from clipboard')}
            </button>
            <button
              onClick={() => {
                loginAttemptRef.current += 1
                setOauthPending(null)
                clearOAuthState()
              }}
              className="btn-outline min-h-10 px-4 text-[14px] cursor-pointer"
            >
              {t('account.cancel', 'Cancel')}
            </button>
          </div>
        </div>
      </div>
    )
  }

  const forgotMode = mode === 'forgot'

  return (
    <div className="account-auth space-y-5 text-[14px]">
      <div className="mb-2">
        <h1 className="text-[23px] leading-8 font-semibold text-text-primary">
          {forgotMode
            ? t('account.forgotPasswordTitle', 'Forgot your password?')
            : tab === 'signup'
              ? t('account.signUp')
              : t('account.signIn')}
        </h1>
        <p className="mt-2 text-[13px] leading-5 text-text-tertiary">
          {forgotMode
            ? t(
                'account.forgotPasswordBrowserDescription',
                'Continue in your browser to complete human verification and request a secure reset link.',
              )
            : t('account.subtitle')}
        </p>
      </div>

      {!forgotMode && pendingCheckout && (
        <div
          role="status"
          className="flex items-center gap-2 rounded-[8px] border border-border bg-bg-secondary/50 px-3 py-2 text-[12px] text-text-secondary"
        >
          <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-accent" aria-hidden="true" />
          <span>{t('upgrade.continueAfterSignIn', { plan: pendingPlan })}</span>
        </div>
      )}

      {forgotMode ? (
        <div className="space-y-3">
          {displayError && (
            <p className="text-error text-[12px] [overflow-wrap:anywhere]">{displayError}</p>
          )}
          <button
            type="button"
            onClick={() => handleBrowserAuth('forgot')}
            className="w-full py-2 rounded-[8px] bg-accent text-white text-[13px] font-medium cursor-pointer border-none hover:opacity-90 transition-opacity"
          >
            {t('account.sendResetLink', 'Send reset link')}
          </button>
          <button
            type="button"
            onClick={() => {
              setMode('auth')
              setTab('signin')
              setLocalError(null)
            }}
            className="w-full py-2 text-[12px] text-accent bg-transparent border-none cursor-pointer hover:underline"
          >
            {t('account.backToSignIn', 'Back to Sign In')}
          </button>
        </div>
      ) : tab === 'signin' ? (
        <form onSubmit={handleSubmit} className="space-y-3">
          <label className="block text-[13px] text-text-secondary">
            {t('account.email')}
            <input
              type="email"
              aria-label={t('account.email')}
              autoComplete="email"
              placeholder={t('account.email')}
              value={email}
              onChange={(e) => {
                setEmail(e.target.value)
                useAuthStore.setState({ error: null })
              }}
              className="field-control mt-2"
              required
            />
          </label>
          <PasswordField
            label={t('account.password')}
            value={password}
            onChange={(value) => {
              setPassword(value)
              useAuthStore.setState({ error: null })
            }}
            autoComplete="current-password"
            showLabel
          />
          <div className="text-right">
            <button
              type="button"
              onClick={() => {
                setMode('forgot')
                setLocalError(null)
                useAuthStore.setState({ error: null })
              }}
              className="p-0 text-[12px] text-accent bg-transparent border-none cursor-pointer hover:underline"
            >
              {t('account.forgotPassword', 'Forgot password?')}
            </button>
          </div>
          {displayError && (
            <p className="text-error text-[12px] [overflow-wrap:anywhere]">{displayError}</p>
          )}
          <button
            type="submit"
            disabled={loading}
            className="w-full py-2 rounded-[8px] bg-accent text-white text-[13px] font-medium cursor-pointer border-none hover:opacity-90 transition-opacity disabled:opacity-50 flex items-center justify-center gap-2"
          >
            {loading && <Loader2 size={14} className="animate-spin" />}
            {t('account.signIn')}
          </button>
        </form>
      ) : (
        <button
          type="button"
          onClick={() => handleBrowserAuth('signup')}
          className="w-full py-2 rounded-[8px] bg-accent text-white text-[13px] font-medium cursor-pointer border-none hover:opacity-90 transition-opacity"
        >
          {t('account.signUp')}
        </button>
      )}

      {/* Divider */}
      {!forgotMode && (
        <div className="flex items-center gap-3">
          <div className="flex-1 h-px bg-border" />
          <span className="text-text-tertiary text-[12px]">{t('account.orContinueWith')}</span>
          <div className="flex-1 h-px bg-border" />
        </div>
      )}

      {/* OAuth buttons */}
      {!forgotMode && (
        <div className="account-provider-actions space-y-2">
          <button
            onClick={() => handleOAuth('google')}
            className="w-full py-2 rounded-[8px] border border-border bg-transparent text-text-primary text-[13px] font-medium cursor-pointer hover:bg-bg-secondary transition-colors flex items-center justify-center gap-2"
          >
            <svg width="16" height="16" viewBox="0 0 24 24">
              <path
                d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z"
                fill="#4285F4"
              />
              <path
                d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
                fill="#34A853"
              />
              <path
                d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"
                fill="#FBBC05"
              />
              <path
                d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"
                fill="#EA4335"
              />
            </svg>
            {t('account.continueWithGoogle')}
          </button>
          <button
            onClick={() => handleOAuth('github')}
            className="w-full py-2 rounded-[8px] border border-border bg-transparent text-text-primary text-[13px] font-medium cursor-pointer hover:bg-bg-secondary transition-colors flex items-center justify-center gap-2"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0 0 24 12c0-6.63-5.37-12-12-12z" />
            </svg>
            {t('account.continueWithGithub')}
          </button>
        </div>
      )}
      {!forgotMode && (
        <div className="text-center">
          <button
            type="button"
            className="text-[12px] text-accent hover:underline"
            onClick={() => {
              setTab(tab === 'signin' ? 'signup' : 'signin')
              setLocalError(null)
            }}
          >
            {tab === 'signin' ? t('account.signUp') : t('account.backToSignIn')}
          </button>
        </div>
      )}
    </div>
  )
}

function AccountDetails({ embedded }: { embedded: boolean }) {
  const { navigate } = useRoute()
  const {
    user,
    plan,
    source,
    displayName,
    subscriptionEnd,
    subscriptionStatus,
    billingProvider,
    canManageBilling,
    canMigrateToStripe,
    quotaDataAvailable,
    credentialCapability,
    subscriptionRefreshState,
    subscriptionRefreshLoading,
    refreshSubscription,
    refreshCredentialCapability,
    changePassword,
    loading,
    signOut,
    subscriptionRefreshedAt,
  } = useAuthStore()
  const { t, i18n } = useTranslation()
  const { backupLoading, backupMsg, handleBackup, handleRestore } = useAccountBackup()
  const [portalLoading, setPortalLoading] = useState(false)
  const [migrationLoading, setMigrationLoading] = useState(false)
  const [billingError, setBillingError] = useState<string | null>(null)
  const [securityOpen, setSecurityOpen] = useState(false)
  const passwordTriggerRef = useRef<HTMLButtonElement>(null)
  const subscriptionRefreshInFlightRef = useRef(false)
  const signOutPending = useRef(false)
  const accountMounted = useRef(false)
  const [signingOut, setSigningOut] = useState(false)
  useEffect(() => {
    accountMounted.current = true
    return () => {
      accountMounted.current = false
    }
  }, [])

  const handleSignOut = () => {
    if (!user || useAuthStore.getState().user !== user || signOutPending.current) return
    signOutPending.current = true
    setSigningOut(true)
    const owner = user
    void signOut()
      .catch(() => {
        if (!accountMounted.current) return
        const currentUser = useAuthStore.getState().user
        if (currentUser !== null && currentUser !== owner) return
        toast.error(t('account.networkError'))
      })
      .finally(() => {
        signOutPending.current = false
        if (accountMounted.current) setSigningOut(false)
      })
  }

  useEffect(() => {
    if (
      subscriptionRefreshInFlightRef.current ||
      !shouldRefreshSubscriptionOnAccountOpen(subscriptionRefreshedAt, Date.now())
    ) {
      return
    }
    subscriptionRefreshInFlightRef.current = true
    void refreshSubscription().finally(() => {
      subscriptionRefreshInFlightRef.current = false
    })
  }, [refreshSubscription, subscriptionRefreshedAt])

  useEffect(() => {
    if (credentialCapability !== 'unknown') return
    void refreshCredentialCapability().catch(() => {})
  }, [credentialCapability, refreshCredentialCapability])

  const hasCloudAccess = useAuthStore(hasManagedCloudAccess)
  const isAppSumo = source === 'appsumo'
  const isDirectLifetime = source === 'lifetime' || plan === 'lifetime_starter'
  const isCreemPaymentFailed =
    billingProvider === 'creem' &&
    canMigrateToStripe &&
    (subscriptionStatus === 'past_due' || subscriptionStatus === 'unpaid')
  const canManageSubscription =
    !isCreemPaymentFailed &&
    plan === 'pro' &&
    !isAppSumo &&
    !isDirectLifetime &&
    canManageBilling &&
    (billingProvider === 'creem' || billingProvider === 'stripe')
  const subscriptionUnavailable = subscriptionRefreshState === 'unavailable'
  const subscriptionStale = subscriptionRefreshState === 'stale'
  const {
    used: wordsUsed,
    limit: wordsLimit,
    resetAt: wordsResetAt,
  } = usageWords(useAuthStore.getState())

  const handleManageSubscription = async () => {
    setPortalLoading(true)
    setBillingError(null)
    try {
      const { url } = await createPortalSession()
      await openUrl(url)
    } catch {
      setBillingError(t('account.paymentPortalUnavailable'))
    } finally {
      setPortalLoading(false)
    }
  }

  const handleMigrateToStripe = async () => {
    setMigrationLoading(true)
    setBillingError(null)
    try {
      const { url } = await createCheckout('desktop', 'pro_monthly')
      await openUrl(url)
      useAuthStore.setState({ checkoutPending: true })
    } catch {
      setBillingError(t('upgrade.checkoutTemporarilyUnavailable'))
    } finally {
      setMigrationLoading(false)
    }
  }

  const passwordActionLabel =
    credentialCapability === 'present'
      ? t('account.changePassword', 'Change password')
      : t('account.setPassword', 'Set password')

  return (
    <div className="account-details account-review space-y-4 text-[13px]">
      {!embedded && (
        <div className="mb-2">
          <h1 className="text-[24px] leading-8 font-medium text-text-primary">
            {t('account.title')}
          </h1>
        </div>
      )}

      {(subscriptionStale || subscriptionUnavailable) && (
        <section
          role="status"
          aria-live="polite"
          className="rounded-[8px] border border-amber-400/25 bg-amber-500/[0.06] px-2.5 py-2"
          data-testid="subscription-refresh-notice"
        >
          <div className="flex items-center gap-2">
            <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-amber-500" aria-hidden="true" />
            <p className="min-w-0 flex-1 text-[12px] leading-4">
              <span className="font-medium text-text-primary">
                {subscriptionStale
                  ? t('account.subscriptionStaleTitle', 'Membership not refreshed')
                  : t('account.subscriptionUnavailableTitle', 'Membership unavailable')}
              </span>
              <span className="text-text-secondary">
                {' · '}
                {subscriptionStale
                  ? t('account.subscriptionStaleBody', 'using your last verified plan')
                  : t('account.subscriptionUnavailableBody', 'try again shortly')}
              </span>
            </p>
            <button
              type="button"
              onClick={() => void refreshSubscription()}
              disabled={subscriptionRefreshLoading}
              className="h-7 shrink-0 rounded-[6px] border border-amber-400/30 bg-transparent px-2 text-[11px] font-medium text-text-primary transition-colors hover:bg-bg-hover disabled:cursor-not-allowed disabled:opacity-50"
            >
              {subscriptionRefreshLoading
                ? t('account.subscriptionRetrying', 'Checking...')
                : t('account.subscriptionRetry', 'Try again')}
            </button>
          </div>
        </section>
      )}

      {/* User info */}
      <div className="account-profile-card">
        <div data-account-profile="" className="flex min-w-0 items-start justify-between gap-4">
          <div className="flex min-w-0 items-center gap-3">
            <span className="account-avatar" aria-hidden="true">
              <UserCircle size={25} />
            </span>
            <div className="min-w-0">
              <h2 className="m-0 break-words text-[18px] font-semibold leading-6 text-text-primary">
                {user!.name || t('account.title')}
              </h2>
              <p className="m-0 mt-2 min-w-0 break-words text-[13px] leading-5 text-text-tertiary">
                {user!.email}
              </p>
            </div>
          </div>
          <span className="shrink-0 rounded-[6px] bg-fill-thin px-2 py-1 text-[12px] font-medium text-text-secondary">
            {subscriptionUnavailable
              ? t('account.subscriptionStatusUnknown', 'Temporarily unavailable')
              : displayName}
          </span>
        </div>
        {(isAppSumo || isDirectLifetime) && (
          <InfoRow
            label={t('account.license', 'License')}
            value={t('account.lifetime', 'Lifetime')}
          />
        )}
        {canManageSubscription && subscriptionEnd && (
          <InfoRow
            label={t('account.renews')}
            value={new Date(subscriptionEnd).toLocaleDateString(i18n.language)}
          />
        )}
        {wordsResetAt && (
          <InfoRow
            label={t('account.resets', 'Resets')}
            value={new Date(wordsResetAt).toLocaleDateString(i18n.language)}
          />
        )}
      </div>

      {/* Security */}
      <section className="account-section">
        <div className="min-h-10 py-4 flex items-center justify-between gap-3">
          <div className="flex items-center gap-2 text-text-primary font-medium">
            <KeyRound size={14} aria-hidden="true" />
            <span>{t('account.security', 'Security')}</span>
          </div>
          {credentialCapability === 'unknown' ? (
            <Loader2
              size={14}
              className="animate-spin text-text-tertiary"
              aria-label={t('common.loading')}
            />
          ) : (
            <button
              ref={passwordTriggerRef}
              type="button"
              aria-haspopup="dialog"
              aria-expanded={securityOpen}
              onClick={() => setSecurityOpen(true)}
              className="h-7 rounded-[6px] border border-border bg-bg-primary px-2.5 text-[12px] font-medium text-text-secondary transition-colors hover:bg-bg-hover hover:text-text-primary"
            >
              {passwordActionLabel}
            </button>
          )}
        </div>
      </section>

      {securityOpen && credentialCapability !== 'unknown' && (
        <PasswordDialog
          credentialCapability={credentialCapability}
          loading={loading}
          returnFocusRef={passwordTriggerRef}
          onCancel={() => setSecurityOpen(false)}
          onSubmit={changePassword}
        />
      )}

      {/* Quota */}
      {!subscriptionUnavailable && quotaDataAvailable !== false && wordsLimit > 0 ? (
        <div className="account-quota-card">
          <div className="flex items-center justify-between gap-3">
            <h3 className="text-[13px] font-semibold text-text-primary">
              {t('upgrade.cloudWords')}
            </h3>
            {!isAppSumo && !isDirectLifetime && (
              <button
                type="button"
                onClick={() => navigate('upgrade')}
                className="rounded-[8px] bg-accent px-3 py-1.5 text-[12px] font-medium text-white hover:bg-accent-hover"
              >
                {t('upgrade.title')}
              </button>
            )}
          </div>
          <div className="py-4 space-y-3">
            <QuotaBar
              label={t('onboarding.account.freePlanQuota')}
              used={wordsUsed}
              limit={wordsLimit}
              unit={t('account.quotaKWords', 'k words')}
              divisor={1000}
              remaining
            />
            <p className="text-[11px] text-text-tertiary">
              {t(
                'account.byokUnlimited',
                'BYOK usage is unlimited and does not count toward cloud words.',
              )}
            </p>
          </div>
        </div>
      ) : null}

      {/* Cloud backup (Pro) */}
      {hasCloudAccess && (
        <div className="account-section">
          <div className="pb-2">
            <h3 className="text-[13px] font-medium text-text-primary">
              {t('account.cloudBackup')}
            </h3>
          </div>
          <div className="py-4 space-y-2">
            <div className="flex gap-2">
              <button
                onClick={handleBackup}
                disabled={backupLoading}
                className="flex-1 py-2 rounded-[8px] border border-border bg-transparent text-text-primary text-[13px] font-medium cursor-pointer hover:bg-bg-secondary transition-colors disabled:opacity-50 flex items-center justify-center gap-1.5"
              >
                <Upload size={14} /> {t('account.backup')}
              </button>
              <button
                onClick={handleRestore}
                disabled={backupLoading}
                className="flex-1 py-2 rounded-[8px] border border-border bg-transparent text-text-primary text-[13px] font-medium cursor-pointer hover:bg-bg-secondary transition-colors disabled:opacity-50 flex items-center justify-center gap-1.5"
              >
                <Download size={14} /> {t('account.restore')}
              </button>
            </div>
            {backupLoading && (
              <div className="flex items-center justify-center py-1">
                <Loader2 size={14} className="animate-spin text-text-tertiary" />
              </div>
            )}
            {backupMsg && (
              <p className="text-[12px] text-text-secondary text-center">{backupMsg}</p>
            )}
          </div>
        </div>
      )}

      {/* Manage subscription (Pro) */}
      {isCreemPaymentFailed && (
        <section className="rounded-[10px] border border-amber-400/30 px-3 py-3">
          <div className="flex items-start gap-2.5">
            <span
              className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-amber-500"
              aria-hidden="true"
            />
            <div className="min-w-0 flex-1">
              <p className="font-medium text-text-primary">{t('upgrade.paymentNeedsAttention')}</p>
              <p className="mt-0.5 text-[12px] leading-5 text-text-secondary">
                {t('upgrade.migrationDescription')}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={handleMigrateToStripe}
            disabled={migrationLoading}
            className="mt-3 flex w-full items-center justify-center gap-1.5 rounded-[8px] bg-accent py-2 text-[13px] font-medium text-white transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {migrationLoading && <Loader2 size={14} className="animate-spin" />}
            {migrationLoading ? t('upgrade.openingCheckout') : t('upgrade.switchToStripe')}
          </button>
        </section>
      )}

      {canManageSubscription && (
        <button
          onClick={handleManageSubscription}
          disabled={portalLoading}
          className="w-full py-2 rounded-[8px] border border-border bg-transparent text-text-primary text-[13px] font-medium cursor-pointer hover:bg-bg-secondary transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-1.5"
        >
          {portalLoading ? (
            <Loader2 size={14} className="animate-spin" />
          ) : (
            <ExternalLink size={14} />
          )}
          {portalLoading ? t('account.opening') : t('account.managePayment')}
        </button>
      )}

      {billingError && (
        <p role="alert" className="text-[12px] text-error [overflow-wrap:anywhere]">
          {billingError}
        </p>
      )}

      {/* Sign out */}
      <button
        onClick={handleSignOut}
        disabled={signingOut}
        className="btn-outline min-h-10 w-full text-error text-[14px] font-medium cursor-pointer hover:bg-error/5 transition-colors flex items-center justify-center gap-1.5 disabled:opacity-50 disabled:cursor-not-allowed"
      >
        <LogOut size={14} />
        {t('account.signOut')}
      </button>
    </div>
  )
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="account-info-row grid grid-cols-[minmax(0,1fr)_minmax(0,2fr)] gap-6 py-4 border-b border-border-thin last:border-b-0">
      <span className="min-w-0 break-words text-text-secondary">{label}</span>
      <span className="min-w-0 break-words text-right text-text-primary">{value}</span>
    </div>
  )
}
