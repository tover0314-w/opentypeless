import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { openUrl } from '@tauri-apps/plugin-opener'
import i18n from '../../../i18n'
import {
  clearOAuthState,
  generateOAuthState,
  getPendingOAuthVerifier,
} from '../../../lib/deep-link'
import { useAuthStore } from '../../../stores/authStore'
import { AccountPage } from '../index'

const mocks = vi.hoisted(() => ({ claimCallback: vi.fn() }))
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn() }))
vi.mock('@tauri-apps/plugin-clipboard-manager', () => ({ readText: vi.fn() }))
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
}))

describe('AccountPage cancelled OAuth requests', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    mocks.claimCallback.mockReset()
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
