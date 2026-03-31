import { useState, useEffect } from 'react'
import { motion } from 'framer-motion'
import { Loader2, UserCircle, CheckCircle2, Mail } from 'lucide-react'
import { openUrl } from '@tauri-apps/plugin-opener'
import { useAuthStore } from '../../stores/authStore'
import { API_BASE_URL } from '../../lib/constants'
import { generateOAuthState, clearOAuthState } from '../../lib/deep-link'

type Tab = 'signin' | 'signup'

export function AccountStep() {
  const { user, loading, error, emailVerificationPending, resendVerification, signIn, signUp } =
    useAuthStore()
  const [tab, setTab] = useState<Tab>('signin')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [name, setName] = useState('')
  const [localError, setLocalError] = useState<string | null>(null)
  const [resent, setResent] = useState(false)
  const [oauthPending, setOauthPending] = useState<'google' | 'github' | null>(null)

  // Auto-timeout OAuth pending state after 2 minutes
  useEffect(() => {
    if (!oauthPending) return
    const timer = setTimeout(
      () => {
        setOauthPending(null)
        clearOAuthState()
        setLocalError('Sign in timed out. Please try again.')
      },
      2 * 60 * 1000,
    )
    return () => clearTimeout(timer)
  }, [oauthPending])

  // When user becomes available (e.g. from OAuth deep-link callback), clear pending
  useEffect(() => {
    if (user && oauthPending) {
      setOauthPending(null)
    }
  }, [user, oauthPending])

  const displayError = localError ?? error

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setLocalError(null)
    try {
      if (tab === 'signin') {
        await signIn(email, password)
      } else {
        if (!name.trim()) {
          setLocalError('Name is required')
          return
        }
        if (password.length < 8) {
          setLocalError('Password must be at least 8 characters')
          return
        }
        await signUp(email, password, name)
      }
    } catch {
      // Error already set in store
    }
  }

  const handleOAuth = async (provider: 'google' | 'github') => {
    try {
      setOauthPending(provider)
      setLocalError(null)
      const state = generateOAuthState()
      const callbackURL = `${API_BASE_URL}/auth/callback?from=desktop&state=${state}`
      const url = `${API_BASE_URL}/api/auth/desktop-oauth?provider=${provider}&callbackURL=${encodeURIComponent(callbackURL)}`
      await openUrl(url)
    } catch {
      setOauthPending(null)
      setLocalError(`Failed to start ${provider} sign in`)
    }
  }

  // ── Post-login confirmation ──
  if (user) {
    return (
      <div className="max-w-[280px] mx-auto flex flex-col items-center gap-6 py-4">
        <motion.div
          className="w-20 h-20 rounded-full bg-success/10 flex items-center justify-center"
          initial={{ scale: 0 }}
          animate={{ scale: 1 }}
          transition={{ type: 'spring', stiffness: 500, damping: 20 }}
        >
          <motion.div
            initial={{ opacity: 0, scale: 0 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ delay: 0.2, type: 'spring', stiffness: 500, damping: 20 }}
          >
            <CheckCircle2 size={36} className="text-success" />
          </motion.div>
        </motion.div>
        <div className="text-center">
          <p className="text-[13px] text-text-secondary">Signed in as</p>
          <p className="text-[15px] font-medium text-text-primary mt-1">{user.email}</p>
        </div>
        <div className="bg-bg-secondary rounded-[14px] p-4 w-full">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-[10px] bg-accent/10 flex items-center justify-center">
              <UserCircle size={18} className="text-accent" />
            </div>
            <div>
              <p className="text-[13px] font-medium text-text-primary">Free Plan</p>
              <p className="text-[12px] text-text-secondary">15 min voice + 100K tokens</p>
            </div>
          </div>
        </div>
      </div>
    )
  }

  // ── Email verification pending ──
  if (emailVerificationPending) {
    return (
      <div className="max-w-[280px] mx-auto flex flex-col items-center gap-5 py-4 text-center">
        <div className="w-20 h-20 rounded-full bg-accent/10 flex items-center justify-center">
          <Mail size={36} className="text-accent" />
        </div>
        <div>
          <h2 className="text-[17px] font-semibold text-text-primary">Check your email</h2>
          <p className="text-[13px] text-text-secondary mt-1.5">
            We sent a verification link to your email. Click it to verify, then come back and sign
            in.
          </p>
        </div>
        <div className="flex flex-col items-center gap-2 w-full">
          <button
            onClick={async () => {
              setResent(false)
              await resendVerification()
              if (!useAuthStore.getState().error) {
                setResent(true)
              }
            }}
            disabled={loading}
            className="w-full py-2.5 rounded-[10px] bg-accent text-white text-[13px] font-medium cursor-pointer border-none hover:bg-accent-hover transition-colors disabled:opacity-50"
          >
            {loading ? 'Sending...' : 'Resend verification email'}
          </button>
          {resent && <p className="text-success text-[12px]">Verification email sent!</p>}
          {error && <p className="text-error text-[12px]">{error}</p>}
          <button
            onClick={() => {
              useAuthStore.setState({ emailVerificationPending: false, pendingEmail: null })
              setTab('signin')
              setResent(false)
            }}
            className="w-full py-2.5 rounded-[10px] bg-bg-secondary border border-border text-text-primary text-[13px] cursor-pointer hover:bg-bg-tertiary transition-colors"
          >
            Back to Sign In
          </button>
        </div>
      </div>
    )
  }

  // ── OAuth pending ──
  if (oauthPending) {
    return (
      <div className="max-w-[280px] mx-auto py-4">
        <div
          className="rounded-[20px] p-8 space-y-5 text-center"
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
            <p className="text-[15px] font-medium text-text-primary">Completing sign in...</p>
            <p className="text-text-secondary text-[12px]">
              Finish signing in with your browser. You'll be redirected back automatically.
            </p>
          </div>
          <div className="h-1 rounded-full overflow-hidden bg-bg-secondary mx-4">
            <div
              className="h-full rounded-full animate-[shimmer-sweep_1.5s_ease-in-out_infinite]"
              style={{
                background: 'linear-gradient(90deg, transparent, var(--accent), transparent)',
                width: '40%',
              }}
            />
          </div>
          <button
            onClick={() => {
              setOauthPending(null)
              clearOAuthState()
            }}
            className="px-4 py-2 rounded-[10px] border border-border bg-transparent text-text-secondary text-[12px] cursor-pointer hover:bg-bg-secondary transition-colors"
          >
            Cancel
          </button>
        </div>
      </div>
    )
  }

  // ── Sign in / Sign up form ──
  return (
    <div className="max-w-[280px] mx-auto space-y-4">
      {/* Hero icon — matching DoneStep circle style */}
      <div className="flex justify-center py-2">
        <div className="w-16 h-16 rounded-full bg-accent/10 flex items-center justify-center">
          <UserCircle size={28} className="text-accent" />
        </div>
      </div>

      {/* Tab switcher */}
      <div className="flex border border-border rounded-[10px] overflow-hidden">
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
          Sign In
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
          Sign Up
        </button>
      </div>

      <form onSubmit={handleSubmit} className="space-y-3">
        {tab === 'signup' && (
          <div>
            <label className="block text-[13px] font-medium text-text-secondary mb-2">Name</label>
            <input
              type="text"
              placeholder="Your name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
            />
          </div>
        )}
        <div>
          <label className="block text-[13px] font-medium text-text-secondary mb-2">Email</label>
          <input
            type="email"
            placeholder="user@example.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
            required
          />
        </div>
        <div>
          <label className="block text-[13px] font-medium text-text-secondary mb-2">Password</label>
          <input
            type="password"
            placeholder="••••••••"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            minLength={8}
            className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
            required
          />
        </div>
        {displayError && <p className="text-error text-[12px]">{displayError}</p>}
        <button
          type="submit"
          disabled={loading}
          className="w-full py-2.5 rounded-[10px] bg-accent text-white text-[13px] font-medium cursor-pointer border-none hover:bg-accent-hover disabled:opacity-40 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-1.5"
        >
          {loading && <Loader2 size={14} className="animate-spin" />}
          {tab === 'signin' ? 'Sign In' : 'Sign Up'}
        </button>
      </form>

      {/* Divider */}
      <div className="flex items-center gap-3">
        <div className="flex-1 h-px bg-border" />
        <span className="text-text-tertiary text-[12px]">or</span>
        <div className="flex-1 h-px bg-border" />
      </div>

      {/* OAuth buttons */}
      <div className="flex gap-2">
        <button
          onClick={() => handleOAuth('google')}
          className="flex-1 py-2.5 rounded-[10px] border border-border bg-transparent text-text-primary text-[13px] font-medium cursor-pointer hover:bg-bg-secondary transition-colors flex items-center justify-center gap-2"
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
          Google
        </button>
        <button
          onClick={() => handleOAuth('github')}
          className="flex-1 py-2.5 rounded-[10px] border border-border bg-transparent text-text-primary text-[13px] font-medium cursor-pointer hover:bg-bg-secondary transition-colors flex items-center justify-center gap-2"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0 0 24 12c0-6.63-5.37-12-12-12z" />
          </svg>
          GitHub
        </button>
      </div>
    </div>
  )
}
