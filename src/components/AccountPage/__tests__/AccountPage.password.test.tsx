import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../../../i18n'
import * as api from '../../../lib/api'
import * as tauri from '../../../lib/tauri'
import { savePendingDesktopCheckout } from '../../../lib/desktop-checkout-intent'
import { useAppStore } from '../../../stores/appStore'
import { useAuthStore } from '../../../stores/authStore'
import { AccountPage } from '../index'

vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn() }))
vi.mock('@tauri-apps/plugin-clipboard-manager', () => ({ readText: vi.fn() }))
vi.mock('../../../lib/api', () => ({
  uploadBackup: vi.fn(),
  downloadBackup: vi.fn(),
  createCheckout: vi.fn(),
  createPortalSession: vi.fn(),
}))
vi.mock('../../../lib/tauri')

const requestPasswordReset = vi.fn().mockResolvedValue(undefined)
const changePassword = vi.fn().mockResolvedValue(undefined)
const refreshCredentialCapability = vi.fn().mockResolvedValue(undefined)

function signedIn(capability: 'unknown' | 'present' | 'none') {
  useAuthStore.setState({
    user: {
      id: 'user-1',
      email: 'person@example.com',
      name: 'Person',
      emailVerified: true,
    },
    loading: false,
    error: null,
    credentialCapability: capability,
    plan: 'free',
    source: 'free',
    displayName: 'Free',
    subscriptionStatus: null,
    billingProvider: null,
    canManageBilling: false,
    canMigrateToStripe: false,
    subscriptionRefreshState: 'fresh',
    subscriptionRefreshedAt: Date.now(),
    requestPasswordReset,
    changePassword,
    refreshCredentialCapability,
  })
}

describe('AccountPage password controls', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    localStorage.clear()
    await i18n.changeLanguage('en')
    useAuthStore.setState({
      user: null,
      loading: false,
      error: null,
      emailVerificationPending: false,
      pendingEmail: null,
      credentialCapability: 'unknown',
      subscriptionStatus: null,
      billingProvider: null,
      canManageBilling: false,
      canMigrateToStripe: false,
      requestPasswordReset,
      changePassword,
      refreshCredentialCapability,
    })
  })

  afterEach(cleanup)

  it('places forgot password beneath the signed-out password field and uses the current locale', async () => {
    await i18n.changeLanguage('zh')
    render(<AccountPage />)

    const password = screen.getByLabelText('密码')
    const forgot = screen.getByRole('button', { name: '忘记密码？' })
    expect(password.compareDocumentPosition(forgot) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    fireEvent.click(forgot)
    fireEvent.change(screen.getByLabelText('邮箱'), { target: { value: 'person@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: '发送重置链接' }))

    await waitFor(() => {
      expect(requestPasswordReset).toHaveBeenCalledWith('person@example.com', 'zh')
    })
    expect(screen.getByText('请检查邮箱')).toBeInTheDocument()
  })

  it('explains that the selected desktop purchase will continue after sign-in', () => {
    savePendingDesktopCheckout(localStorage, 'lifetime_starter')

    render(<AccountPage />)

    expect(screen.getByRole('status')).toHaveTextContent(
      'Sign in to continue with Lifetime Starter.',
    )
  })

  it('shows payment management only for an active provider-backed subscription', () => {
    signedIn('present')
    useAuthStore.setState({
      plan: 'pro',
      source: 'creem',
      subscriptionStatus: 'active',
      billingProvider: 'stripe',
      canManageBilling: true,
      canMigrateToStripe: false,
    })

    render(<AccountPage />)

    expect(screen.getByRole('button', { name: 'Manage payment' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Continue with Stripe' })).not.toBeInTheDocument()
  })

  it('offers Stripe recovery instead of a portal after a failed Creem renewal', async () => {
    signedIn('present')
    useAuthStore.setState({
      plan: 'free',
      source: 'free',
      subscriptionStatus: 'past_due',
      billingProvider: 'creem',
      canManageBilling: true,
      canMigrateToStripe: true,
    })
    vi.mocked(api.createCheckout).mockResolvedValue({
      url: 'https://checkout.stripe.com/recover-pro',
    })

    render(<AccountPage />)
    expect(screen.queryByRole('button', { name: 'Manage payment' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Continue with Stripe' }))

    await waitFor(() => {
      expect(api.createCheckout).toHaveBeenCalledWith('desktop', 'pro_monthly')
    })
  })

  it.each([
    ['lifetime_starter', 'lifetime', 'stripe'],
    ['appsumo_tier1', 'appsumo', 'appsumo'],
  ] as const)(
    'never shows monthly payment controls for a %s entitlement',
    (plan, source, provider) => {
      signedIn('present')
      useAuthStore.setState({
        plan,
        source,
        licenseStatus: 'active',
        billingProvider: provider,
        canManageBilling: true,
        canMigrateToStripe: false,
      })

      render(<AccountPage />)

      expect(screen.queryByRole('button', { name: 'Manage payment' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Continue with Stripe' })).not.toBeInTheDocument()
    },
  )

  it('opens credential password controls in a focused modal and keeps invalid forms disabled', () => {
    signedIn('present')
    render(<AccountPage />)

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Current password')).not.toBeInTheDocument()
    const trigger = screen.getByRole('button', { name: 'Change password' })
    fireEvent.click(trigger)
    const dialog = screen.getByRole('dialog', { name: 'Change password' })
    expect(dialog.parentElement).toHaveClass('z-[9999]')
    expect(within(dialog).getByLabelText('Current password')).toHaveFocus()
    const submit = within(dialog).getByRole('button', { name: 'Change password' })
    expect(submit).toBeDisabled()

    fireEvent.change(within(dialog).getByLabelText('Current password'), {
      target: { value: 'old-password' },
    })
    fireEvent.change(within(dialog).getByLabelText('New password'), {
      target: { value: 'new-password' },
    })
    fireEvent.change(within(dialog).getByLabelText('Confirm password'), {
      target: { value: 'different' },
    })
    expect(submit).toBeDisabled()

    fireEvent.change(within(dialog).getByLabelText('Confirm password'), {
      target: { value: 'new-password' },
    })
    expect(submit).toBeEnabled()

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('renders Set password for OAuth-only accounts', () => {
    signedIn('none')
    render(<AccountPage />)

    fireEvent.click(screen.getByRole('button', { name: 'Set password' }))
    const dialog = screen.getByRole('dialog', { name: 'Set password' })
    expect(within(dialog).queryByLabelText('Current password')).not.toBeInTheDocument()
    expect(within(dialog).getByLabelText('New password')).toHaveFocus()
    expect(screen.queryByRole('button', { name: 'Change password' })).not.toBeInTheDocument()
  })

  it('persists allow-listed cloud settings and keeps device credentials local', async () => {
    signedIn('present')
    useAuthStore.setState({
      plan: 'pro',
      source: 'creem',
      cloudWordsLimit: 1000,
      licenseStatus: 'active',
    })
    const current = {
      ...useAppStore.getState().config,
      llm_api_key: 'local-secret',
      system_scene_overrides: [],
    }
    const restored = {
      ...current,
      polish_enabled: false,
      system_scene_overrides: [{ id: 'system_email', prompt_template: 'Use concise paragraphs.' }],
    }
    useAppStore.getState().setConfig(current)
    useAppStore.getState().setSavedConfig(current)
    vi.mocked(api.downloadBackup).mockResolvedValue({
      settings: {
        polish_enabled: false,
        system_scene_overrides: restored.system_scene_overrides,
        llm_api_key: 'cloud-secret',
      },
    })
    vi.mocked(tauri.updateConfig).mockResolvedValue(undefined)
    vi.mocked(tauri.getConfig).mockResolvedValue(restored)

    render(<AccountPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))

    await waitFor(() => {
      expect(tauri.updateConfig).toHaveBeenCalledWith(
        expect.objectContaining({
          polish_enabled: false,
          llm_api_key: 'local-secret',
          system_scene_overrides: restored.system_scene_overrides,
        }),
      )
      expect(useAppStore.getState().config).toEqual(restored)
      expect(useAppStore.getState().savedConfig).toEqual(restored)
    })
  })

  it('backs up dictionary entries and correction rules in one compatible payload', async () => {
    signedIn('present')
    useAuthStore.setState({
      plan: 'pro',
      source: 'creem',
      cloudWordsLimit: 1000,
      licenseStatus: 'active',
    })
    const dictionary = [{ id: 7, word: 'OpenTypeless', pronunciation: null }]
    const correctionRules = [
      {
        id: 9,
        pattern: 'open type less',
        replacement: 'OpenTypeless',
        enabled: true,
      },
    ]
    useAppStore.setState({ dictionary, correctionRules })
    vi.mocked(api.uploadBackup).mockResolvedValue({ success: true })

    render(<AccountPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Backup' }))

    await waitFor(() => {
      expect(api.uploadBackup).toHaveBeenCalledWith(
        expect.objectContaining({
          dictionary: {
            entries: dictionary,
            correction_rules: correctionRules,
          },
        }),
      )
    })
  })

  it('persists restored cloud data before replacing the desktop stores', async () => {
    signedIn('present')
    useAuthStore.setState({
      plan: 'pro',
      source: 'creem',
      cloudWordsLimit: 1000,
      licenseStatus: 'active',
    })
    const cloudHistory = [{ id: 1, raw_text: 'cloud raw' }]
    const cloudDictionary = {
      entries: [{ id: 2, word: 'TalkMore', pronunciation: null }],
      correction_rules: [{ id: 3, pattern: 'talk more', replacement: 'TalkMore', enabled: true }],
    }
    const restoredHistory = [{ id: 11, raw_text: 'persisted raw' }]
    const restoredDictionary = [{ id: 12, word: 'TalkMore', pronunciation: null }]
    const restoredCorrections = [
      { id: 13, pattern: 'talk more', replacement: 'TalkMore', enabled: true },
    ]
    vi.mocked(api.downloadBackup).mockResolvedValue({
      history: cloudHistory,
      dictionary: cloudDictionary,
    })
    vi.mocked(tauri.restoreBackupData).mockResolvedValue({
      history: restoredHistory as never,
      dictionary: restoredDictionary,
      correctionRules: restoredCorrections,
    })

    render(<AccountPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))

    await waitFor(() => {
      expect(tauri.restoreBackupData).toHaveBeenCalledWith(cloudHistory, cloudDictionary)
      expect(useAppStore.getState().history).toEqual(restoredHistory)
      expect(useAppStore.getState().dictionary).toEqual(restoredDictionary)
      expect(useAppStore.getState().correctionRules).toEqual(restoredCorrections)
    })
  })

  it('explains why passwords longer than 128 characters cannot be submitted', () => {
    signedIn('none')
    render(<AccountPage />)

    fireEvent.click(screen.getByRole('button', { name: 'Set password' }))
    const dialog = screen.getByRole('dialog', { name: 'Set password' })
    const tooLong = 'a'.repeat(129)
    fireEvent.change(within(dialog).getByLabelText('New password'), {
      target: { value: tooLong },
    })
    fireEvent.change(within(dialog).getByLabelText('Confirm password'), {
      target: { value: tooLong },
    })

    expect(within(dialog).getByText('Password must be 8 to 128 characters')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Set password' })).toBeDisabled()
  })

  it('keeps Tab and Shift+Tab focus inside the password dialog', () => {
    signedIn('present')
    render(<AccountPage />)

    const backgroundSignOut = screen.getByRole('button', { name: 'Sign Out' })
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
    const dialog = screen.getByRole('dialog', { name: 'Change password' })
    const firstField = within(dialog).getByLabelText('Current password')
    const cancel = within(dialog).getByRole('button', { name: 'Cancel' })

    cancel.focus()
    fireEvent.keyDown(cancel, { key: 'Tab' })
    expect(firstField).toHaveFocus()
    expect(backgroundSignOut).not.toHaveFocus()

    firstField.focus()
    fireEvent.keyDown(firstField, { key: 'Tab', shiftKey: true })
    expect(cancel).toHaveFocus()
    expect(backgroundSignOut).not.toHaveFocus()
  })

  it('renders neither password action while capability is unknown', () => {
    signedIn('unknown')
    render(<AccountPage />)

    expect(screen.queryByRole('button', { name: 'Set password' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Change password' })).not.toBeInTheDocument()
    expect(screen.queryByText(/dashboard/i)).not.toBeInTheDocument()
  })
})
