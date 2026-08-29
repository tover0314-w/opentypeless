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
} from 'lucide-react'
import { openUrl } from '@tauri-apps/plugin-opener'
import { readText } from '@tauri-apps/plugin-clipboard-manager'
import { hasManagedCloudAccess, useAuthStore } from '../../stores/authStore'
import { useAppStore } from '../../stores/appStore'
import { API_BASE_URL } from '../../lib/constants'
import { uploadBackup, downloadBackup, createCheckout, createPortalSession } from '../../lib/api'
import { createBackupSettings, mergeBackupSettings } from '../../lib/backup-settings'
import {
  getConfig,
  restoreBackupData,
  setAutoStart,
  updateConfig as persistConfig,
} from '../../lib/tauri'
import {
  EMAIL_VERIFICATION_STATE_TTL_MS,
  OAUTH_STATE_TTL_MS,
  clearOAuthState,
  handleDeepLinkUrl,
} from '../../lib/deep-link'
import {
  claimDesktopAuthCallbackURL,
  createDesktopAuthCallbackURL,
} from '../../lib/desktop-auth-callback'
import { readPendingDesktopCheckout } from '../../lib/desktop-checkout-intent'
import { shouldRefreshSubscriptionOnAccountOpen } from '../../lib/subscription-refresh-policy'
import { PasswordDialog } from './PasswordDialog'
import { PasswordField } from './PasswordField'

type Tab = 'signin' | 'signup'
type AuthMode = 'auth' | 'forgot' | 'forgot-sent'

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
  return message
}

export function AccountPage() {
  const { user, loading } = useAuthStore()

  if (loading && !user) {
    return (
      <div className="flex items-center justify-center py-20">
        <Loader2 size={20} className="animate-spin text-text-tertiary" />
      </div>
    )
  }

  if (!user) {
    return <AuthForm />
  }

  return <AccountDetails />
}

function AuthForm() {
  const [tab, setTab] = useState<Tab>('signin')
  const [mode, setMode] = useState<AuthMode>('auth')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [name, setName] = useState('')
  const {
    signIn,
    signUp,
    requestPasswordReset,
    loading,
    error,
    emailVerificationPending,
    resendVerification,
  } = useAuthStore()
  const [localError, setLocalError] = useState<string | null>(null)
  const [resent, setResent] = useState(false)
  const [oauthPending, setOauthPending] = useState<'google' | 'github' | null>(null)
  const { t, i18n } = useTranslation()
  const pendingCheckout = readPendingDesktopCheckout(localStorage)
  const pendingPlan =
    pendingCheckout?.product === 'lifetime_starter' ? t('upgrade.lifetime') : t('upgrade.pro')

  // Keep the UI timeout aligned with the in-memory OAuth proof TTL.
  useEffect(() => {
    if (!oauthPending) return
    const timer = setTimeout(() => {
      setOauthPending(null)
      clearOAuthState()
      setLocalError(t('account.oauthTimeout', 'Sign in timed out. Please try again.'))
    }, OAUTH_STATE_TTL_MS)
    return () => clearTimeout(timer)
  }, [oauthPending, t])

  const displayError = accountErrorMessage(localError ?? error, t)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setLocalError(null)
    try {
      if (mode === 'forgot') {
        await requestPasswordReset(email, i18n.resolvedLanguage ?? i18n.language ?? 'en')
        setMode('forgot-sent')
      } else if (tab === 'signin') {
        const verificationCallbackURL = await createDesktopAuthCallbackURL(
          EMAIL_VERIFICATION_STATE_TTL_MS,
        )
        await signIn(email, password, { verificationCallbackURL })
        if (!useAuthStore.getState().emailVerificationPending) {
          clearOAuthState()
        }
      } else {
        if (!name.trim()) {
          setLocalError(t('account.nameRequired'))
          return
        }
        if (password.length < 8) {
          setLocalError(t('account.passwordMinLength'))
          return
        }
        const verificationCallbackURL = await createDesktopAuthCallbackURL(
          EMAIL_VERIFICATION_STATE_TTL_MS,
        )
        await signUp(email, password, name, { verificationCallbackURL })
      }
    } catch {
      if (!useAuthStore.getState().emailVerificationPending) {
        clearOAuthState()
      }
      // Error already set in store
    }
  }

  if (emailVerificationPending) {
    return (
      <div className="max-w-[340px] mx-auto py-8 px-6 space-y-5 text-[13px] text-center">
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
              setResent(false)
              const verificationCallbackURL = await createDesktopAuthCallbackURL(
                EMAIL_VERIFICATION_STATE_TTL_MS,
              )
              await resendVerification({ verificationCallbackURL })
              // Only show success if store didn't set an error
              if (!useAuthStore.getState().error) {
                setResent(true)
              }
            }}
            disabled={loading}
            className="px-4 py-2 rounded-[8px] bg-accent text-white text-[13px] font-medium cursor-pointer border-none hover:opacity-90 transition-opacity disabled:opacity-50"
          >
            {loading
              ? t('account.sending', 'Sending...')
              : t('account.resendVerification', 'Resend verification email')}
          </button>
          {resent && (
            <p className="text-green-500 text-[12px]">
              {t('account.verificationResent', 'Verification email sent!')}
            </p>
          )}
          {error && <p className="text-red-500 text-[12px]">{error}</p>}
          <button
            onClick={() => {
              useAuthStore.setState({ emailVerificationPending: false, pendingEmail: null })
              clearOAuthState()
              setTab('signin')
              setResent(false)
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
    try {
      setOauthPending(provider)
      setLocalError(null)
      useAuthStore.setState({ error: null })
      const callbackURL = await claimDesktopAuthCallbackURL()
      if (!callbackURL) return
      // Open the desktop-oauth bridge route in the system browser. The server
      // internally POSTs to Better Auth, then 302-redirects the browser to the
      // OAuth provider while forwarding the state cookie — keeping cookie and
      // callback in the same browser context.
      const url = `${API_BASE_URL}/api/auth/desktop-oauth?provider=${provider}&callbackURL=${encodeURIComponent(callbackURL)}`
      await openUrl(url)
    } catch {
      clearOAuthState()
      setOauthPending(null)
      setLocalError(t('account.oauthFailed'))
    }
  }

  const handleClipboardOAuth = async () => {
    setLocalError(null)
    try {
      const text = (await readText()).trim()
      const handled = await handleDeepLinkUrl(text)
      if (!handled) {
        setLocalError(t('account.oauthClipboardInvalid', 'No valid sign-in link found.'))
      }
    } catch {
      setLocalError(t('account.oauthClipboardInvalid', 'No valid sign-in link found.'))
    }
  }

  if (mode === 'forgot-sent') {
    return (
      <div className="max-w-[340px] mx-auto py-8 px-6 space-y-4 text-[13px] text-center">
        <Mail size={28} className="mx-auto text-text-secondary" aria-hidden="true" />
        <h2 className="text-[16px] font-semibold text-text-primary">
          {t('account.resetLinkSent', 'Check your email')}
        </h2>
        <p className="text-text-secondary">
          {t(
            'account.resetLinkSentDesc',
            'If an account exists for that email, a password reset link is on its way.',
          )}
        </p>
        <button
          type="button"
          onClick={() => {
            setMode('auth')
            setTab('signin')
            setLocalError(null)
          }}
          className="px-4 py-2 rounded-[8px] border border-border bg-transparent text-text-primary text-[13px] cursor-pointer hover:bg-bg-secondary transition-colors"
        >
          {t('account.backToSignIn', 'Back to Sign In')}
        </button>
      </div>
    )
  }

  if (oauthPending) {
    return (
      <div className="max-w-[340px] mx-auto py-8 px-6 text-[13px] text-center">
        <div
          className="rounded-[20px] p-8 space-y-5"
          style={{
            background: 'var(--bg-elevated)',
            boxShadow: `
              0 4px 20px rgba(0,0,0,0.07),
              0 10px 40px rgba(0,0,0,0.03),
              inset 0 2px 6px rgba(255,255,255,0.8),
              inset 0 -2px 6px rgba(0,0,0,0.05)
            `,
          }}
        >
          {/* Provider icon with breathing animation */}
          <div className="flex justify-center">
            <div
              className="w-12 h-12 rounded-[14px] flex items-center justify-center animate-[jelly-breathe_2s_ease-in-out_infinite]"
              style={{
                background: 'var(--bg-secondary)',
                boxShadow:
                  'inset 0 1px 3px rgba(255,255,255,0.6), inset 0 -1px 3px rgba(0,0,0,0.04)',
              }}
            >
              {oauthPending === 'google' ? (
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

          {/* Shimmer bar */}
          <div className="h-1 rounded-full overflow-hidden bg-bg-secondary mx-8">
            <div
              className="h-full rounded-full animate-[shimmer-sweep_1.5s_ease-in-out_infinite]"
              style={{
                background: 'linear-gradient(90deg, transparent, var(--accent), transparent)',
                width: '40%',
              }}
            />
          </div>

          {displayError && <p className="text-error text-[12px]">{displayError}</p>}

          <div className="flex flex-col gap-2">
            <button
              onClick={handleClipboardOAuth}
              className="px-4 py-2 rounded-[10px] border border-border bg-bg-secondary text-text-primary text-[12px] cursor-pointer hover:bg-bg-tertiary transition-colors inline-flex items-center justify-center gap-2"
            >
              <ClipboardCheck size={14} />
              {t('account.completeFromClipboard', 'Complete from clipboard')}
            </button>
            <button
              onClick={() => {
                setOauthPending(null)
                clearOAuthState()
              }}
              className="px-4 py-2 rounded-[10px] border border-border bg-transparent text-text-secondary text-[12px] cursor-pointer hover:bg-bg-secondary transition-colors"
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
    <div className="max-w-[340px] mx-auto py-8 px-6 space-y-5 text-[13px]">
      <div className="text-center mb-2">
        <h1 className="text-[18px] font-semibold text-text-primary">
          {forgotMode
            ? t('account.forgotPasswordTitle', 'Forgot your password?')
            : t('account.title')}
        </h1>
        <p className="text-text-secondary mt-1">
          {forgotMode
            ? t(
                'account.forgotPasswordDescription',
                'Enter your email and we will send a secure reset link.',
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

      {/* Tab switcher */}
      {!forgotMode && (
        <div className="flex border border-border rounded-[8px] overflow-hidden">
          <button
            onClick={() => {
              setTab('signin')
              setLocalError(null)
            }}
            className={`flex-1 py-2 text-[13px] font-medium border-none cursor-pointer transition-colors ${
              tab === 'signin'
                ? 'bg-bg-secondary text-text-primary'
                : 'bg-transparent text-text-secondary hover:text-text-primary'
            }`}
          >
            {t('account.signIn')}
          </button>
          <button
            onClick={() => {
              setTab('signup')
              setLocalError(null)
            }}
            className={`flex-1 py-2 text-[13px] font-medium border-none cursor-pointer transition-colors ${
              tab === 'signup'
                ? 'bg-bg-secondary text-text-primary'
                : 'bg-transparent text-text-secondary hover:text-text-primary'
            }`}
          >
            {t('account.signUp')}
          </button>
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-3">
        {!forgotMode && tab === 'signup' && (
          <input
            type="text"
            aria-label={t('account.name')}
            autoComplete="name"
            placeholder={t('account.name')}
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="w-full px-3 py-2 rounded-[8px] border border-border bg-bg-secondary text-text-primary text-[13px] outline-none focus:border-accent transition-colors"
          />
        )}
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
          className="w-full px-3 py-2 rounded-[8px] border border-border bg-bg-secondary text-text-primary text-[13px] outline-none focus:border-accent transition-colors"
          required
        />
        {!forgotMode && (
          <>
            <PasswordField
              label={t('account.password')}
              value={password}
              onChange={(value) => {
                setPassword(value)
                useAuthStore.setState({ error: null })
              }}
              autoComplete={tab === 'signin' ? 'current-password' : 'new-password'}
            />
            {tab === 'signin' && (
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
            )}
          </>
        )}
        {displayError && <p className="text-red-500 text-[12px]">{displayError}</p>}
        <button
          type="submit"
          disabled={loading}
          className="w-full py-2 rounded-[8px] bg-accent text-white text-[13px] font-medium cursor-pointer border-none hover:opacity-90 transition-opacity disabled:opacity-50 flex items-center justify-center gap-2"
        >
          {loading && <Loader2 size={14} className="animate-spin" />}
          {forgotMode
            ? t('account.sendResetLink', 'Send reset link')
            : tab === 'signin'
              ? t('account.signIn')
              : t('account.signUp')}
        </button>
        {forgotMode && (
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
        )}
      </form>

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
        <div className="space-y-2">
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
    </div>
  )
}

function AccountDetails() {
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
    quotaModel,
    displayWordsUsedEstimate,
    displayWordsLimit,
    displayWordsResetAt,
    cloudWordsUsed,
    cloudWordsLimit,
    cloudWordsResetAt,
    sttSecondsUsed,
    sttSecondsLimit,
    llmTokensUsed,
    llmTokensLimit,
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
  const config = useAppStore((s) => s.config)
  const history = useAppStore((s) => s.history)
  const dictionary = useAppStore((s) => s.dictionary)
  const correctionRules = useAppStore((s) => s.correctionRules)
  const setConfig = useAppStore((s) => s.setConfig)
  const setSavedConfig = useAppStore((s) => s.setSavedConfig)
  const setHistory = useAppStore((s) => s.setHistory)
  const setDictionary = useAppStore((s) => s.setDictionary)
  const setCorrectionRules = useAppStore((s) => s.setCorrectionRules)
  const { t } = useTranslation()
  const [backupLoading, setBackupLoading] = useState(false)
  const [backupMsg, setBackupMsg] = useState<string | null>(null)
  const [portalLoading, setPortalLoading] = useState(false)
  const [migrationLoading, setMigrationLoading] = useState(false)
  const [billingError, setBillingError] = useState<string | null>(null)
  const [securityOpen, setSecurityOpen] = useState(false)
  const passwordTriggerRef = useRef<HTMLButtonElement>(null)
  const subscriptionRefreshInFlightRef = useRef(false)

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
  const wordsUsed =
    quotaModel === 'legacy_dual_meter' && displayWordsLimit > 0
      ? displayWordsUsedEstimate
      : cloudWordsUsed
  const wordsLimit =
    quotaModel === 'legacy_dual_meter' && displayWordsLimit > 0
      ? displayWordsLimit
      : cloudWordsLimit
  const wordsResetAt =
    quotaModel === 'legacy_dual_meter' && displayWordsLimit > 0
      ? displayWordsResetAt
      : cloudWordsResetAt

  const handleBackup = async () => {
    setBackupLoading(true)
    setBackupMsg(null)
    try {
      const safeConfig = createBackupSettings(config)
      await uploadBackup({
        history,
        dictionary: { entries: dictionary, correction_rules: correctionRules },
        settings: safeConfig,
      })
      setBackupMsg(t('account.toast.backupOk'))
    } catch (e) {
      setBackupMsg(e instanceof Error ? e.message : t('account.toast.backupFail'))
    } finally {
      setBackupLoading(false)
    }
  }

  const handleRestore = async () => {
    setBackupLoading(true)
    setBackupMsg(null)
    let autoStartApplied = false
    try {
      const data = await downloadBackup()
      if (data.settings) {
        const restoredConfig = mergeBackupSettings(config, data.settings)
        if (restoredConfig.auto_start !== config.auto_start) {
          await setAutoStart(restoredConfig.auto_start)
          autoStartApplied = true
        }
        try {
          await persistConfig(restoredConfig)
        } catch (error) {
          if (autoStartApplied) await setAutoStart(config.auto_start).catch(() => {})
          throw error
        }
        const persistedConfig = await getConfig().catch(() => restoredConfig)
        setConfig(persistedConfig)
        setSavedConfig(persistedConfig)
      }
      if (data.history != null || data.dictionary != null) {
        const restoredData = await restoreBackupData(data.history ?? null, data.dictionary ?? null)
        setHistory(restoredData.history)
        setDictionary(restoredData.dictionary)
        setCorrectionRules(restoredData.correctionRules)
      }
      setBackupMsg(t('account.toast.restoreOk'))
    } catch (e) {
      setBackupMsg(e instanceof Error ? e.message : t('account.toast.restoreFail'))
    } finally {
      setBackupLoading(false)
    }
  }

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
    <div className="max-w-[400px] mx-auto py-8 px-6 space-y-5 text-[13px]">
      <div className="text-center mb-2">
        <h1 className="text-[18px] font-semibold text-text-primary">{t('account.title')}</h1>
      </div>

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
      <div className="border border-border rounded-[10px] overflow-hidden">
        <InfoRow label={t('account.email')} value={user!.email} />
        {user!.name && <InfoRow label={t('account.name')} value={user!.name} />}
        <InfoRow
          label={t('account.plan')}
          value={
            subscriptionUnavailable
              ? t('account.subscriptionStatusUnknown', 'Temporarily unavailable')
              : displayName
          }
        />
        {(isAppSumo || isDirectLifetime) && (
          <InfoRow
            label={t('account.license', 'License')}
            value={t('account.lifetime', 'Lifetime')}
          />
        )}
        {canManageSubscription && subscriptionEnd && (
          <InfoRow
            label={t('account.renews')}
            value={new Date(subscriptionEnd).toLocaleDateString()}
          />
        )}
        {wordsResetAt && (
          <InfoRow
            label={t('account.resets', 'Resets')}
            value={new Date(wordsResetAt).toLocaleDateString()}
          />
        )}
      </div>

      {/* Security */}
      <section className="border border-border rounded-[10px] overflow-hidden">
        <div className="min-h-10 px-3 py-2.5 flex items-center justify-between gap-3 bg-bg-secondary/50">
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
      {wordsLimit > 0 ? (
        <div className="border border-border rounded-[10px] overflow-hidden">
          <div className="px-3 py-2.5 bg-bg-secondary/50 border-b border-border">
            <h3 className="text-[13px] font-medium text-text-primary">
              {t('account.cloudWordsThisMonth', 'Cloud words this month')}
            </h3>
          </div>
          <div className="px-3 py-3 space-y-3">
            <QuotaBar
              label={t('account.cloudWords', 'Cloud words')}
              used={wordsUsed}
              limit={wordsLimit}
              unit={t('account.quotaKWords', 'k words')}
              divisor={1000}
            />
            <p className="text-[11px] text-text-tertiary">
              {t(
                'account.byokUnlimited',
                'BYOK usage is unlimited and does not count toward cloud words.',
              )}
            </p>
          </div>
        </div>
      ) : sttSecondsLimit > 0 ? (
        <div className="border border-border rounded-[10px] overflow-hidden">
          <div className="px-3 py-2.5 bg-bg-secondary/50 border-b border-border">
            <h3 className="text-[13px] font-medium text-text-primary">
              {hasCloudAccess
                ? t('account.usageThisMonth')
                : t('account.freeCredit', 'Free Credit')}
            </h3>
          </div>
          <div className="px-3 py-3 space-y-3">
            <QuotaBar
              label={t('upgrade.stt')}
              used={sttSecondsUsed}
              limit={sttSecondsLimit}
              unit={sttSecondsLimit >= 3600 ? t('account.quotaHours') : t('account.quotaMin')}
              divisor={sttSecondsLimit >= 3600 ? 3600 : 60}
            />
            <QuotaBar
              label={t('upgrade.llm')}
              used={llmTokensUsed}
              limit={llmTokensLimit}
              unit={t('account.quotaTokens')}
              divisor={1000}
            />
          </div>
        </div>
      ) : null}

      {/* Cloud backup (Pro) */}
      {hasCloudAccess && (
        <div className="border border-border rounded-[10px] overflow-hidden">
          <div className="px-3 py-2.5 bg-bg-secondary/50 border-b border-border">
            <h3 className="text-[13px] font-medium text-text-primary">
              {t('account.cloudBackup')}
            </h3>
          </div>
          <div className="px-3 py-3 space-y-2">
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
        <p role="alert" className="text-center text-[12px] text-red-500">
          {billingError}
        </p>
      )}

      {/* Sign out */}
      <button
        onClick={signOut}
        className="w-full py-2 rounded-[8px] border border-border bg-transparent text-red-500 text-[13px] font-medium cursor-pointer hover:bg-red-500/5 transition-colors flex items-center justify-center gap-1.5"
      >
        <LogOut size={14} />
        {t('account.signOut')}
      </button>
    </div>
  )
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between px-3 py-2.5 border-b border-border last:border-b-0">
      <span className="text-text-secondary">{label}</span>
      <span className="text-text-primary">{value}</span>
    </div>
  )
}

function QuotaBar({
  label,
  used,
  limit,
  unit,
  divisor,
}: {
  label: string
  used: number
  limit: number
  unit: string
  divisor: number
}) {
  const { t } = useTranslation()
  const pct = limit > 0 ? Math.min((used / limit) * 100, 100) : 0
  const usedDisplay = (used / divisor).toFixed(1)
  const limitDisplay = (limit / divisor).toFixed(1)

  return (
    <div className="space-y-1">
      <div className="flex justify-between text-[12px]">
        <span className="text-text-secondary">{label}</span>
        <span className="text-text-tertiary">
          {usedDisplay} / {limitDisplay} {unit}
        </span>
      </div>
      <div
        className="h-1.5 bg-bg-secondary rounded-full overflow-hidden"
        role="progressbar"
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={t('account.quotaUsage', {
          label,
          used: usedDisplay,
          limit: limitDisplay,
          unit,
        })}
      >
        <div
          className={`h-full rounded-full transition-all ${pct > 90 ? 'bg-red-500' : 'bg-accent'}`}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  )
}
