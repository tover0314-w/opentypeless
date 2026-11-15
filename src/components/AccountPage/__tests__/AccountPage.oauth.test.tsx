import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { openUrl } from '@tauri-apps/plugin-opener'
import { readText } from '@tauri-apps/plugin-clipboard-manager'
import i18n from '../../../i18n'
import { UI_LANGUAGES } from '../../../lib/constants'
import {
  clearOAuthState,
  generateOAuthState,
  getPendingOAuthVerifier,
  handleDeepLinkUrl,
} from '../../../lib/deep-link'
import { useAuthStore } from '../../../stores/authStore'
import { AccountPage } from '../index'

const mocks = vi.hoisted(() => ({ claimCallback: vi.fn(), createWebAuth: vi.fn() }))
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn() }))
vi.mock('@tauri-apps/plugin-clipboard-manager', () => ({ readText: vi.fn() }))
vi.mock('../../../lib/deep-link', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../lib/deep-link')>()),
  handleDeepLinkUrl: vi.fn().mockResolvedValue(false),
}))
vi.mock('../../../lib/api', () => ({
  uploadBackup: vi.fn(),
  downloadBackup: vi.fn(),
  createCheckout: vi.fn(),
  createPortalSession: vi.fn(),
}))
vi.mock('../../../lib/tauri')
vi.mock('../../../lib/desktop-auth-callback', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../lib/desktop-auth-callback')>()),
  claimDesktopAuthCallbackURL: mocks.claimCallback,
  createDesktopWebAuthURL: mocks.createWebAuth,
}))

describe('AccountPage cancelled OAuth requests', () => {
  it('ignores an obsolete waiting timeout after a new login attempt starts', async () => {
    vi.useFakeTimers()
    const timers = vi.spyOn(globalThis, 'setTimeout')
    mocks.claimCallback.mockResolvedValue('https://example.test/oauth')
    try {
      render(<AccountPage />)
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /google/i }))
      })
      const oldTimeout = timers.mock.calls.find(([, delay]) => delay === 600000)?.[0]
      expect(typeof oldTimeout).toBe('function')
      fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /github/i }))
      })
      act(() => {
        if (typeof oldTimeout === 'function') oldTimeout()
      })
      expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
      expect(screen.queryByText(i18n.t('account.oauthTimeout'))).not.toBeInTheDocument()
    } finally {
      timers.mockRestore()
      vi.useRealTimers()
    }
  })

  it('does not let an obsolete clipboard read release the newer attempt lock', async () => {
    let finishOld!: (text: string) => void
    let finishNew!: (text: string) => void
    vi.mocked(readText)
      .mockReturnValueOnce(
        new Promise<string>((resolve) => {
          finishOld = resolve
        }),
      )
      .mockReturnValueOnce(
        new Promise<string>((resolve) => {
          finishNew = resolve
        }),
      )
    mocks.claimCallback.mockResolvedValue('https://example.test/oauth')
    render(<AccountPage />)
    try {
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /google/i }))
      })
      fireEvent.click(screen.getByRole('button', { name: 'Complete from clipboard' }))
      fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /github/i }))
      })
      const complete = screen.getByRole('button', { name: 'Complete from clipboard' })
      fireEvent.click(complete)
      expect(readText).toHaveBeenCalledTimes(2)
      await act(async () => finishOld('opentypeless://auth/callback?code=old-synthetic'))
      fireEvent.click(complete)
      expect(readText).toHaveBeenCalledTimes(2)
      await act(async () => finishNew('opentypeless://auth/callback?code=new-synthetic'))
      expect(handleDeepLinkUrl).toHaveBeenCalledExactlyOnceWith(
        'opentypeless://auth/callback?code=new-synthetic',
        expect.any(Function),
      )
    } finally {
      await act(async () => {
        finishOld('')
        finishNew('')
      })
    }
  })

  it('reads clipboard once while completion for the same login attempt is pending', async () => {
    let finishRead!: (text: string) => void
    vi.mocked(readText).mockReturnValueOnce(
      new Promise<string>((resolve) => {
        finishRead = resolve
      }),
    )
    mocks.claimCallback.mockResolvedValue('https://example.test/oauth')
    render(<AccountPage />)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /google/i }))
    })
    const complete = screen.getByRole('button', { name: 'Complete from clipboard' })
    await act(async () => {
      fireEvent.click(complete)
      fireEvent.click(complete)
    })
    expect(readText).toHaveBeenCalledTimes(1)
    await act(async () => finishRead('opentypeless://auth/callback?code=synthetic'))
  })

  it('passes the active login owner when completing from clipboard', async () => {
    vi.mocked(readText).mockResolvedValueOnce('  opentypeless://auth/callback?code=synthetic  ')
    vi.mocked(handleDeepLinkUrl).mockResolvedValueOnce(true)
    mocks.claimCallback.mockResolvedValue('https://example.test/oauth')
    render(<AccountPage />)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /google/i }))
    })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Complete from clipboard' }))
    })
    expect(handleDeepLinkUrl).toHaveBeenCalledWith(
      'opentypeless://auth/callback?code=synthetic',
      expect.any(Function),
    )
    const owner = vi.mocked(handleDeepLinkUrl).mock.calls[0][1]!
    expect(owner()).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(owner()).toBe(false)
  })

  it.each(['failure', 'rejected'] as const)(
    'does not show a late clipboard callback %s after cancellation',
    async (outcome) => {
      let finish!: (result: boolean) => void
      let fail!: (error: Error) => void
      vi.mocked(readText).mockResolvedValueOnce('opentypeless://auth/callback?code=synthetic')
      vi.mocked(handleDeepLinkUrl).mockReturnValueOnce(
        new Promise<boolean>((resolve, reject) => {
          finish = resolve
          fail = reject
        }),
      )
      mocks.claimCallback.mockResolvedValue('https://example.test/oauth')
      render(<AccountPage />)
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /google/i }))
      })
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Complete from clipboard' }))
      })
      fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
      await act(async () => {
        if (outcome === 'failure') finish(false)
        else fail(new Error('Synthetic callback failure'))
      })
      expect(screen.queryByText(i18n.t('account.oauthClipboardInvalid'))).not.toBeInTheDocument()
    },
  )

  it('ignores clipboard text arriving after the login attempt was cancelled', async () => {
    let finishRead!: (text: string) => void
    vi.mocked(readText).mockReturnValueOnce(
      new Promise<string>((resolve) => {
        finishRead = resolve
      }),
    )
    mocks.claimCallback.mockResolvedValue('https://example.test/oauth')
    render(<AccountPage />)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /google/i }))
    })
    fireEvent.click(screen.getByRole('button', { name: 'Complete from clipboard' }))
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    await act(async () => finishRead('opentypeless://auth/callback?code=synthetic'))
    expect(handleDeepLinkUrl).not.toHaveBeenCalled()
    expect(screen.queryByText(i18n.t('account.oauthClipboardInvalid'))).not.toBeInTheDocument()
  })

  it('gives a replacement OAuth attempt its own waiting deadline', async () => {
    vi.useFakeTimers()
    mocks.claimCallback.mockResolvedValue('https://example.test/oauth')
    try {
      render(<AccountPage />)
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /google/i }))
      })
      await act(async () => vi.advanceTimersByTime(540000))
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
      })
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /github/i }))
      })
      expect(mocks.claimCallback).toHaveBeenCalledTimes(2)
      await act(async () => vi.advanceTimersByTime(60000))
      expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
      expect(screen.queryByText(i18n.t('account.oauthTimeout'))).not.toBeInTheDocument()
      await act(async () => vi.advanceTimersByTime(540000))
      expect(screen.getByText(i18n.t('account.oauthTimeout'))).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not restart the OAuth waiting deadline when the UI language changes', async () => {
    vi.useFakeTimers()
    mocks.claimCallback.mockResolvedValueOnce('https://example.test/oauth')
    try {
      render(<AccountPage />)
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /google/i }))
      })
      await act(async () => vi.advanceTimersByTime(540000))
      await act(async () => {
        await i18n.changeLanguage('zh')
      })
      await act(async () => vi.advanceTimersByTime(60000))
      expect(screen.getByText(i18n.t('account.oauthTimeout'))).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: i18n.t('account.cancel') })).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  it('keeps browser verification pending for its one-hour proof lifetime', async () => {
    vi.useFakeTimers()
    useAuthStore.setState({ emailVerificationPending: true })
    mocks.createWebAuth.mockResolvedValueOnce('https://example.test/verify')
    try {
      render(<AccountPage />)
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Resend verification email' }))
      })
      expect(mocks.createWebAuth).toHaveBeenCalledWith('verify', 3600000, 'en')
      await act(async () => vi.advanceTimersByTime(600000))
      expect(screen.getByRole('button', { name: 'Sending...' })).toBeDisabled()
      expect(screen.queryByText(i18n.t('account.oauthTimeout'))).not.toBeInTheDocument()
      await act(async () => vi.advanceTimersByTime(3000000))
      expect(screen.getByRole('button', { name: 'Resend verification email' })).toBeEnabled()
      expect(screen.getByText(i18n.t('account.oauthTimeout'))).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it.each(UI_LANGUAGES.map((language) => language.value))(
    'replaces unknown authentication errors with a safe localized message in %s',
    async (language) => {
      await i18n.changeLanguage(language)
      const message = `Server error token=private-detail-${'x'.repeat(600)}`
      useAuthStore.setState({ error: message })
      render(<AccountPage />)
      expect(screen.getByText(i18n.t('account.signInUnavailable'))).toHaveClass(
        '[overflow-wrap:anywhere]',
      )
      expect(screen.queryByText(message)).not.toBeInTheDocument()
    },
  )

  beforeEach(async () => {
    vi.clearAllMocks()
    mocks.claimCallback.mockReset()
    mocks.createWebAuth.mockReset()
    clearOAuthState()
    localStorage.clear()
    await i18n.changeLanguage('en')
    useAuthStore.setState({
      user: null,
      loading: false,
      error: null,
      emailVerificationPending: false,
    })
  })
  afterEach(() => {
    cleanup()
    clearOAuthState()
  })

  it.each(['failure', 'success'])(
    'ignores a late %s after cancellation and a new sign-in',
    async (outcome) => {
      let finish!: (url: string) => void
      let fail!: (error: Error) => void
      let oldState = ''
      mocks.claimCallback.mockImplementationOnce(() => {
        oldState = generateOAuthState()
        return new Promise<string>((resolve, reject) => {
          finish = resolve
          fail = reject
        })
      })
      let newState = ''
      mocks.claimCallback.mockImplementationOnce(async () => {
        newState = generateOAuthState()
        return `https://www.opentypeless.com/auth/callback?desktop=${newState}`
      })

      render(<AccountPage />)
      fireEvent.click(screen.getByRole('button', { name: /google/i }))
      await waitFor(() => expect(mocks.claimCallback).toHaveBeenCalledOnce())
      fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
      fireEvent.click(screen.getByRole('button', { name: /github/i }))
      await waitFor(() => expect(openUrl).toHaveBeenCalledOnce())
      const verifier = getPendingOAuthVerifier(newState)
      expect(verifier).not.toBeNull()

      await act(async () => {
        if (outcome === 'failure') fail(new TypeError('old request failed'))
        else finish(`https://www.opentypeless.com/auth/callback?desktop=${oldState}`)
      })

      expect(getPendingOAuthVerifier(newState)).toBe(verifier)
      expect(openUrl).toHaveBeenCalledOnce()
      expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
      expect(useAuthStore.getState().error).toBeNull()
    },
  )
})
