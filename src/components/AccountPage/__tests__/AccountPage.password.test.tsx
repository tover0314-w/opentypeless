import {
  act,
  cleanup,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createRef, useEffect } from 'react'
import i18n from '../../../i18n'
import * as api from '../../../lib/api'
import * as tauri from '../../../lib/tauri'
import { savePendingDesktopCheckout } from '../../../lib/desktop-checkout-intent'
import { UI_LANGUAGES } from '../../../lib/constants'
import { useAppStore } from '../../../stores/appStore'
import { useAuthStore } from '../../../stores/authStore'
import { AccountPage } from '../index'
import { PasswordDialog } from '../PasswordDialog'
import { useAccountBackup } from '../useAccountBackup'
import { pendingConfigSaves } from '../../../lib/configSaveRegistry'
import { openUrl } from '@tauri-apps/plugin-opener'
import { toast } from '../../toast-service'

vi.mock('../../toast-service', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

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

function ParentEscapeHarness({ onClose }: { onClose: () => void }) {
  useEffect(() => {
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !event.isComposing && !event.defaultPrevented) onClose()
    }
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [onClose])
  return <AccountPage />
}

describe('AccountPage password controls', () => {
  it('does not start restore from an old confirmation after cloud access becomes unavailable', () => {
    signedIn('present')
    useAuthStore.setState({
      plan: 'pro',
      source: 'creem',
      cloudWordsLimit: 1000,
      licenseStatus: 'active',
    })
    render(<AccountPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    const confirm = within(screen.getByRole('alertdialog')).getByRole('button', {
      name: 'Restore',
    })
    act(() => useAuthStore.setState({ subscriptionRefreshState: 'unavailable' }))
    fireEvent.click(confirm)
    expect(api.downloadBackup).not.toHaveBeenCalled()
    expect(tauri.restoreBackupData).not.toHaveBeenCalled()
    act(() => useAuthStore.setState({ subscriptionRefreshState: 'fresh' }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it.each([false, true])(
    'discards restore confirmation after owner roundtrip with same ID=%s',
    (sameId) => {
      signedIn('present')
      useAuthStore.setState({
        plan: 'pro',
        source: 'creem',
        cloudWordsLimit: 1000,
        licenseStatus: 'active',
      })
      render(<AccountPage />)
      const originalOwner = useAuthStore.getState().user
      fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
      expect(screen.getByRole('alertdialog')).toBeInTheDocument()
      act(() => {
        useAuthStore.setState({
          user: {
            id: sameId ? originalOwner!.id : 'new-user',
            email: 'new@example.invalid',
            name: null,
            emailVerified: true,
          },
        })
      })
      expect(screen.queryByRole('alertdialog')).toBeNull()
      act(() => useAuthStore.setState({ user: originalOwner }))
      expect(screen.queryByRole('alertdialog')).toBeNull()
      expect(api.downloadBackup).not.toHaveBeenCalled()
      expect(tauri.restoreBackupData).not.toHaveBeenCalled()
    },
  )

  it('requires explicit restore confirmation and cancellation starts no backup operation', () => {
    signedIn('present')
    useAuthStore.setState({
      plan: 'pro',
      source: 'creem',
      cloudWordsLimit: 1000,
      licenseStatus: 'active',
    })
    render(<AccountPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    const dialog = screen.getByRole('alertdialog')
    expect(dialog).toHaveTextContent('replace')
    expect(api.downloadBackup).not.toHaveBeenCalled()
    expect(tauri.restoreBackupData).not.toHaveBeenCalled()
    const cancel = within(dialog).getByRole('button', { name: 'Cancel' })
    expect(cancel).toHaveFocus()
    fireEvent.click(cancel)
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(api.downloadBackup).not.toHaveBeenCalled()
  })

  it('limits successful manual refreshes to one per ten seconds', async () => {
    signedIn('present')
    const previousRefresh = useAuthStore.getState().refreshSubscription
    const refresh = vi.fn().mockResolvedValue(undefined)
    const clock = vi.spyOn(Date, 'now').mockReturnValue(2_000_000_000_000)
    useAuthStore.setState({
      refreshSubscription: refresh,
      subscriptionRefreshedAt: Date.now(),
    })
    try {
      render(<AccountPage />)
      const button = screen.getByRole('button', { name: 'Refresh subscription status' })
      await act(async () => fireEvent.click(button))
      await act(async () => fireEvent.click(button))
      expect(refresh).toHaveBeenCalledOnce()
      expect(toast.error).toHaveBeenCalledWith(
        'Subscription status was recently refreshed. Try again in a moment.',
      )
      clock.mockReturnValue(2_000_000_010_000)
      await act(async () => fireEvent.click(button))
      expect(refresh).toHaveBeenCalledTimes(2)
    } finally {
      clock.mockRestore()
      useAuthStore.setState({ refreshSubscription: previousRefresh })
    }
  })

  it('does not apply the previous account cooldown after an account change', async () => {
    signedIn('present')
    const previousRefresh = useAuthStore.getState().refreshSubscription
    const refresh = vi.fn().mockResolvedValue(undefined)
    useAuthStore.setState({ refreshSubscription: refresh })
    try {
      render(<AccountPage />)
      await act(async () =>
        fireEvent.click(screen.getByRole('button', { name: 'Refresh subscription status' })),
      )
      act(() =>
        useAuthStore.setState({
          user: { id: 'new-user', email: 'new@example.test', name: null, emailVerified: true },
        }),
      )
      await act(async () =>
        fireEvent.click(screen.getByRole('button', { name: 'Refresh subscription status' })),
      )
      expect(refresh).toHaveBeenCalledTimes(2)
      expect(toast.error).not.toHaveBeenCalled()
    } finally {
      useAuthStore.setState({ refreshSubscription: previousRefresh })
    }
  })

  it.each(['fresh', 'stale'] as const)(
    'provides manual subscription refresh and reports the resulting %s state',
    async (state) => {
      signedIn('present')
      const previousRefresh = useAuthStore.getState().refreshSubscription
      const refresh = vi.fn(async () => {
        useAuthStore.setState({ subscriptionRefreshState: state })
      })
      useAuthStore.setState({ refreshSubscription: refresh })
      try {
        render(<AccountPage />)
        fireEvent.click(screen.getByRole('button', { name: 'Refresh subscription status' }))
        await waitFor(() => expect(refresh).toHaveBeenCalledOnce())
        if (state === 'fresh') {
          await waitFor(() =>
            expect(toast.success).toHaveBeenCalledWith('Subscription status is up to date'),
          )
          expect(toast.error).not.toHaveBeenCalled()
        } else {
          await waitFor(() =>
            expect(toast.error).toHaveBeenCalledWith(i18n.t('account.subscriptionUnavailableBody')),
          )
          expect(toast.success).not.toHaveBeenCalled()
        }
      } finally {
        useAuthStore.setState({ refreshSubscription: previousRefresh })
      }
    },
  )

  it('does not retry a subscription from a stale account render', async () => {
    signedIn('present')
    const previousRefresh = useAuthStore.getState().refreshSubscription
    const refresh = vi.fn().mockResolvedValue(undefined)
    useAuthStore.setState({
      subscriptionRefreshState: 'stale',
      subscriptionRefreshLoading: false,
      refreshSubscription: refresh,
    })
    try {
      render(<AccountPage />)
      const button = screen.getByRole('button', { name: i18n.t('account.subscriptionRetry') })
      await act(async () => {
        useAuthStore.setState({
          user: { id: 'new-user', email: 'new@example.test', name: null, emailVerified: true },
        })
        fireEvent.click(button)
      })
      expect(refresh).not.toHaveBeenCalled()
      expect(toast.error).not.toHaveBeenCalled()
    } finally {
      useAuthStore.setState({ refreshSubscription: previousRefresh })
    }
  })

  it('submits subscription retry once during batched clicks', async () => {
    signedIn('present')
    const previousRefresh = useAuthStore.getState().refreshSubscription
    let finish = () => {}
    const refresh = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    useAuthStore.setState({
      subscriptionRefreshState: 'stale',
      subscriptionRefreshLoading: false,
      refreshSubscription: refresh,
    })
    render(<AccountPage />)
    const retry = screen.getByRole('button', { name: i18n.t('account.subscriptionRetry') })
    act(() => {
      fireEvent.click(retry)
      fireEvent.click(retry)
    })
    try {
      expect(refresh).toHaveBeenCalledOnce()
    } finally {
      await act(async () => finish())
      useAuthStore.setState({ refreshSubscription: previousRefresh })
    }
  })

  it.each(['unmount', 'replacement'] as const)(
    'does not show an old subscription retry failure after %s',
    async (scenario) => {
      signedIn('present')
      const previousRefresh = useAuthStore.getState().refreshSubscription
      let rejectRefresh: (error: Error) => void = () => {}
      const refresh = vi.fn(
        () =>
          new Promise<void>((_, reject) => {
            rejectRefresh = reject
          }),
      )
      useAuthStore.setState({
        subscriptionRefreshState: 'stale',
        subscriptionRefreshLoading: false,
        refreshSubscription: refresh,
      })
      try {
        const view = render(<AccountPage />)
        fireEvent.click(screen.getByRole('button', { name: i18n.t('account.subscriptionRetry') }))
        if (scenario === 'unmount') view.unmount()
        else {
          act(() =>
            useAuthStore.setState({
              user: { id: 'new-user', email: 'new@example.test', name: null, emailVerified: true },
            }),
          )
        }
        await act(async () => rejectRefresh(new Error('synthetic private subscription detail')))
        expect(toast.error).not.toHaveBeenCalled()
      } finally {
        useAuthStore.setState({ refreshSubscription: previousRefresh })
      }
    },
  )

  it('handles a rejected subscription retry without losing the profile', async () => {
    signedIn('present')
    const previousRefresh = useAuthStore.getState().refreshSubscription
    const refresh = vi.fn().mockRejectedValue(new Error('synthetic private subscription detail'))
    useAuthStore.setState({
      subscriptionRefreshState: 'stale',
      subscriptionRefreshLoading: false,
      refreshSubscription: refresh,
    })
    try {
      render(<AccountPage />)
      fireEvent.click(screen.getByRole('button', { name: i18n.t('account.subscriptionRetry') }))
      await waitFor(() =>
        expect(toast.error).toHaveBeenCalledWith(i18n.t('account.subscriptionUnavailableBody')),
      )
      expect(screen.getByText('person@example.com')).toBeInTheDocument()
      expect(
        screen.getByRole('button', { name: i18n.t('account.subscriptionRetry') }),
      ).toBeEnabled()
    } finally {
      useAuthStore.setState({ refreshSubscription: previousRefresh })
    }
  })

  it('uses the approved compact profile and a single remaining-quota label', () => {
    signedIn('present')
    useAuthStore.setState({
      quotaModel: 'cloud_words',
      quotaDataAvailable: true,
      cloudWordsUsed: 500,
      cloudWordsLimit: 2000,
    })
    const { container } = render(<AccountPage embedded />)
    const profile = container.querySelector('[data-account-profile]')
    expect(profile).toContainElement(screen.getByText('Person'))
    expect(profile).toContainElement(screen.getByText('person@example.com'))
    expect(profile).toContainElement(screen.getByText('Free'))
    expect(screen.getAllByText(i18n.t('onboarding.account.freePlanQuota'))).toHaveLength(1)
  })

  it('does not repeat the parent account heading when embedded', () => {
    signedIn('present')
    render(<AccountPage embedded />)
    expect(screen.queryByRole('heading', { name: 'Account' })).toBeNull()
    expect(screen.getByText('person@example.com')).toBeInTheDocument()
  })

  it('uses shared form controls in the compact sign-in layout', () => {
    useAuthStore.setState({ user: null, loading: false, error: null })
    render(<AccountPage />)
    const email = screen.getByRole('textbox', { name: 'Email' })
    expect(email).toHaveClass('field-control')
    expect(screen.getByLabelText('Password', { selector: 'input' })).toHaveClass(
      'field-control',
      'field-control-with-action',
    )
    expect(email.closest('.account-auth')).not.toBeNull()
  })

  it('uses a readable account column with shrink-safe information rows', () => {
    signedIn('present')
    render(<AccountPage />)
    const email = screen.getByText('person@example.com')
    expect(email.closest('.account-details')).not.toBeNull()
    expect(email.closest('[data-account-profile]')).not.toBeNull()
    expect(email).toHaveClass('min-w-0', 'break-words')
  })

  it('drops the password draft when switching directly to another account', () => {
    signedIn('present')
    render(<AccountPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
    fireEvent.change(screen.getByLabelText('New password'), {
      target: { value: 'previous-account-password' },
    })
    act(() => {
      useAuthStore.setState({
        user: {
          id: 'replacement-user',
          email: 'replacement@example.test',
          name: null,
          emailVerified: true,
        },
      })
    })
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
    expect(screen.getByLabelText('New password')).toHaveValue('')
  })

  it('keeps the password draft when the same account profile refreshes', () => {
    signedIn('present')
    render(<AccountPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
    fireEvent.change(screen.getByLabelText('New password'), {
      target: { value: 'same-account-password' },
    })
    act(() => {
      useAuthStore.setState({
        user: { ...useAuthStore.getState().user!, name: 'Refreshed profile' },
      })
    })
    expect(screen.getByLabelText('New password')).toHaveValue('same-account-password')
  })

  it('does not show a late sign-out failure after the account page unmounts', async () => {
    signedIn('present')
    let fail!: (error: Error) => void
    const signOut = vi.fn(
      () =>
        new Promise<void>((_resolve, reject) => {
          fail = reject
        }),
    )
    useAuthStore.setState({ signOut })
    const { unmount } = render(<AccountPage />)
    fireEvent.click(screen.getByRole('button', { name: i18n.t('account.signOut') }))
    expect(signOut).toHaveBeenCalledOnce()
    unmount()
    await act(async () => fail(new Error('Late sign-out failure')))
    expect(toast.error).not.toHaveBeenCalled()
  })

  it.each(['escape', 'backdrop', 'button'] as const)(
    'blocks %s cancellation in the same batch as password submission',
    async (action) => {
      let fail!: (error: Error) => void
      const onCancel = vi.fn()
      const onSubmit = vi.fn(
        () =>
          new Promise<void>((_resolve, reject) => {
            fail = reject
          }),
      )
      render(
        <PasswordDialog
          credentialCapability="none"
          loading={false}
          returnFocusRef={createRef<HTMLButtonElement>()}
          onCancel={onCancel}
          onSubmit={onSubmit}
        />,
      )
      fireEvent.change(screen.getByLabelText('New password'), {
        target: { value: 'new-password' },
      })
      fireEvent.change(screen.getByLabelText('Confirm password'), {
        target: { value: 'new-password' },
      })
      const dialog = screen.getByRole('dialog')
      const cancelButton = within(dialog).getByRole('button', { name: 'Cancel' })
      const backdrop = dialog.previousElementSibling!
      act(() => {
        fireEvent.submit(dialog)
        if (action === 'escape') fireEvent.keyDown(window, { key: 'Escape' })
        else if (action === 'backdrop') fireEvent.click(backdrop)
        else fireEvent.click(cancelButton)
      })
      expect(onSubmit).toHaveBeenCalledOnce()
      expect(onCancel).not.toHaveBeenCalled()
      await act(async () => fail(new Error('Synthetic request failed')))
      fireEvent.click(cancelButton)
      expect(onCancel).toHaveBeenCalledOnce()
    },
  )

  it('does not close a replacement dialog when an unmounted password request completes', async () => {
    let finish!: () => void
    const onSubmit = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        }),
    )
    const onCancel = vi.fn()
    const older = render(
      <PasswordDialog
        credentialCapability="none"
        loading={false}
        returnFocusRef={createRef<HTMLButtonElement>()}
        onCancel={onCancel}
        onSubmit={onSubmit}
      />,
    )
    fireEvent.change(screen.getByLabelText('New password'), {
      target: { value: 'new-password' },
    })
    fireEvent.change(screen.getByLabelText('Confirm password'), {
      target: { value: 'new-password' },
    })
    fireEvent.submit(screen.getByRole('dialog'))
    expect(onSubmit).toHaveBeenCalledOnce()
    older.unmount()
    render(
      <PasswordDialog
        credentialCapability="none"
        loading={false}
        returnFocusRef={createRef<HTMLButtonElement>()}
        onCancel={onCancel}
        onSubmit={vi.fn()}
      />,
    )
    await act(async () => finish())
    expect(onCancel).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('submits a password change once during batched events and permits retry after failure', async () => {
    signedIn('present')
    let fail!: (error: Error) => void
    changePassword.mockImplementationOnce(
      () =>
        new Promise<void>((_resolve, reject) => {
          fail = reject
        }),
    )
    render(<AccountPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
    const dialog = screen.getByRole('dialog')
    for (const [label, value] of [
      ['Current password', 'old-password'],
      ['New password', 'new-password'],
      ['Confirm password', 'new-password'],
    ]) {
      fireEvent.change(within(dialog).getByLabelText(label), { target: { value } })
    }
    act(() => {
      fireEvent.submit(dialog)
      fireEvent.submit(dialog)
    })
    expect(changePassword).toHaveBeenCalledExactlyOnceWith('old-password', 'new-password')
    await act(async () => fail(new Error('Synthetic request failed')))
    expect(within(dialog).getByRole('alert')).toHaveTextContent(
      i18n.t('account.passwordChangeFailed'),
    )
    changePassword.mockResolvedValueOnce(undefined)
    fireEvent.submit(dialog)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(changePassword).toHaveBeenCalledTimes(2)
  })

  it.each(UI_LANGUAGES.map((language) => language.value))(
    'shows localized password failure without exposing backend details in %s',
    async (language) => {
      await i18n.changeLanguage(language)
      signedIn('present')
      changePassword.mockRejectedValueOnce(
        new Error('Invalid account security response: token=private-detail'),
      )
      render(<AccountPage />)
      fireEvent.click(screen.getByRole('button', { name: i18n.t('account.changePassword') }))
      const dialog = screen.getByRole('dialog')
      fireEvent.change(within(dialog).getByLabelText(i18n.t('account.currentPassword')), {
        target: { value: 'old-password' },
      })
      fireEvent.change(within(dialog).getByLabelText(i18n.t('account.newPassword')), {
        target: { value: 'new-password' },
      })
      fireEvent.change(within(dialog).getByLabelText(i18n.t('account.confirmPassword')), {
        target: { value: 'new-password' },
      })
      fireEvent.click(
        within(dialog).getByRole('button', { name: i18n.t('account.changePassword') }),
      )
      expect(await within(dialog).findByText(i18n.t('account.passwordChangeFailed'))).toBeVisible()
      expect(within(dialog).getByRole('alert')).toHaveClass('text-error')
      expect(within(dialog).queryByText(/private-detail/)).not.toBeInTheDocument()
      expect(
        within(dialog).getByRole('button', { name: i18n.t('account.changePassword') }),
      ).toBeEnabled()
      const nextLanguage = language === 'zh' ? 'en' : 'zh'
      await act(async () => {
        await i18n.changeLanguage(nextLanguage)
      })
      expect(within(dialog).getByRole('alert')).toHaveTextContent(
        i18n.t('account.passwordChangeFailed'),
      )
      expect(within(dialog).getByLabelText(i18n.t('account.newPassword'))).toHaveValue(
        'new-password',
      )
    },
  )

  it('does not start sign-out from a stale account render', async () => {
    signedIn('present')
    const signOut = vi.fn().mockResolvedValue(undefined)
    useAuthStore.setState({ signOut })
    render(<AccountPage />)
    const button = screen.getByRole('button', { name: i18n.t('account.signOut') })
    await act(async () => {
      useAuthStore.setState({
        user: { id: 'new-user', email: 'new@example.test', name: null, emailVerified: true },
      })
      fireEvent.click(button)
    })
    expect(signOut).not.toHaveBeenCalled()
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('disables repeated sign-out while a request is pending and permits retry after failure', async () => {
    signedIn('present')
    let rejectSignOut: ((error: Error) => void) | undefined
    const signOut = vi.fn(
      () =>
        new Promise<void>((_resolve, reject) => {
          rejectSignOut = reject
        }),
    )
    useAuthStore.setState({ signOut })
    render(<AccountPage />)
    const button = screen.getByRole('button', { name: i18n.t('account.signOut') })
    fireEvent.click(button)
    fireEvent.click(button)
    expect(signOut).toHaveBeenCalledOnce()
    expect(button).toBeDisabled()
    await act(async () => rejectSignOut?.(new Error('Synthetic network unavailable')))
    expect(button).not.toBeDisabled()
    signOut.mockResolvedValueOnce(undefined)
    fireEvent.click(button)
    await waitFor(() => expect(signOut).toHaveBeenCalledTimes(2))
  })

  it.each(UI_LANGUAGES.map((language) => language.value))(
    'handles private sign-out rejection with localized error feedback in %s',
    async (language) => {
      await i18n.changeLanguage(language)
      signedIn('present')
      const signOut = vi.fn().mockRejectedValue(new Error('Server failure token=private-detail'))
      useAuthStore.setState({ signOut })
      render(<AccountPage />)
      fireEvent.click(screen.getByRole('button', { name: i18n.t('account.signOut') }))
      await waitFor(() => expect(toast.error).toHaveBeenCalledWith(i18n.t('account.networkError')))
      expect(signOut).toHaveBeenCalledOnce()
      expect(JSON.stringify(vi.mocked(toast.error).mock.calls)).not.toContain('private-detail')
    },
  )

  it('does not show a late sign-out failure to a replacement account', async () => {
    signedIn('present')
    let rejectSignOut: ((error: Error) => void) | undefined
    useAuthStore.setState({
      signOut: vi.fn(
        () =>
          new Promise<void>((_resolve, reject) => {
            rejectSignOut = reject
          }),
      ),
    })
    render(<AccountPage />)
    fireEvent.click(screen.getByRole('button', { name: i18n.t('account.signOut') }))
    act(() =>
      useAuthStore.setState({
        user: { id: 'new-user', email: 'new@example.test', name: null, emailVerified: true },
      }),
    )
    await act(async () => rejectSignOut?.(new Error('Old network error')))
    expect(toast.error).not.toHaveBeenCalled()
  })

  it.each(['backup', 'restore'] as const)(
    'does not start %s from an obsolete account callback',
    async (operation) => {
      signedIn('present')
      const { result } = renderHook(() => useAccountBackup())
      const previousHandler =
        operation === 'backup' ? result.current.handleBackup : result.current.handleRestore
      act(() => {
        useAuthStore.setState({
          user: { id: 'another-user', email: 'other@example.com', name: null, emailVerified: true },
        })
      })
      await act(async () => previousHandler())
      expect(api.uploadBackup).not.toHaveBeenCalled()
      expect(api.downloadBackup).not.toHaveBeenCalled()
    },
  )

  it.each(['backup', 'restore'] as const)(
    'does not start %s after the backup hook unmounts',
    async (operation) => {
      signedIn('present')
      const { result, unmount } = renderHook(() => useAccountBackup())
      const handler =
        operation === 'backup' ? result.current.handleBackup : result.current.handleRestore
      unmount()
      await act(async () => handler())
      expect(api.uploadBackup).not.toHaveBeenCalled()
      expect(api.downloadBackup).not.toHaveBeenCalled()
    },
  )

  it.each(['backup', 'restore'] as const)(
    'starts only one %s operation before its loading state renders',
    async (operation) => {
      signedIn('present')
      let finish: (value: unknown) => void = () => {}
      const response = new Promise((resolve) => {
        finish = resolve
      })
      if (operation === 'backup') vi.mocked(api.uploadBackup).mockReturnValueOnce(response as never)
      else vi.mocked(api.downloadBackup).mockReturnValueOnce(response as never)
      const { result } = renderHook(() => useAccountBackup())
      const handler =
        operation === 'backup' ? result.current.handleBackup : result.current.handleRestore
      let first: Promise<void> = Promise.resolve()
      let second: Promise<void> = Promise.resolve()
      act(() => {
        first = handler()
        second = handler()
      })
      await waitFor(() =>
        expect(
          operation === 'backup' ? api.uploadBackup : api.downloadBackup,
        ).toHaveBeenCalledOnce(),
      )
      await act(async () => {
        finish(operation === 'backup' ? { success: true } : {})
        await Promise.all([first, second])
      })
      expect(operation === 'backup' ? api.uploadBackup : api.downloadBackup).toHaveBeenCalledOnce()
      expect(result.current.backupLoading).toBe(false)
    },
  )

  it.each(['backup', 'restore'] as const)(
    'ignores a late %s failure after switching accounts',
    async (operation) => {
      signedIn('present')
      let fail: ((error: Error) => void) | undefined
      const response = new Promise((_resolve, reject) => {
        fail = reject
      })
      if (operation === 'backup') vi.mocked(api.uploadBackup).mockReturnValueOnce(response as never)
      else vi.mocked(api.downloadBackup).mockReturnValueOnce(response as never)
      const { result } = renderHook(() => useAccountBackup())
      let pending: Promise<void> = Promise.resolve()
      act(() => {
        pending =
          operation === 'backup' ? result.current.handleBackup() : result.current.handleRestore()
      })
      await waitFor(() =>
        expect(
          operation === 'backup' ? api.uploadBackup : api.downloadBackup,
        ).toHaveBeenCalledOnce(),
      )
      act(() =>
        useAuthStore.setState({
          user: { id: 'new-account', email: 'new@example.com', name: null, emailVerified: true },
        }),
      )
      await act(async () => {
        fail?.(new Error('Synthetic previous account failure'))
        await pending
      })
      expect(result.current.backupMsg).toBeNull()
      expect(result.current.backupLoading).toBe(false)
      expect(tauri.restoreBackupData).not.toHaveBeenCalled()
    },
  )

  beforeEach(async () => {
    vi.clearAllMocks()
    vi.mocked(tauri.validateBackupData).mockResolvedValue(undefined)
    vi.mocked(tauri.getConfig).mockImplementation(async () => useAppStore.getState().config)
    vi.mocked(tauri.getHistory).mockResolvedValue([])
    vi.mocked(tauri.getCloudBackupHistory).mockResolvedValue([])
    vi.mocked(tauri.getDictionary).mockImplementation(async () => useAppStore.getState().dictionary)
    vi.mocked(tauri.getCorrectionRules).mockImplementation(
      async () => useAppStore.getState().correctionRules,
    )
    vi.mocked(tauri.getCloudBackupSnapshot).mockImplementation(async () => ({
      config: await tauri.getConfig(),
      history: await tauri.getCloudBackupHistory(),
      dictionary: await tauri.getDictionary(),
      correctionRules: await tauri.getCorrectionRules(),
    }))
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

  it('formats quota numbers using the UI language without changing usage', async () => {
    signedIn('present')
    useAuthStore.setState({
      plan: 'pro',
      source: 'stripe',
      licenseStatus: 'active',
      quotaModel: 'cloud_words',
      cloudWordsUsed: 1250,
      cloudWordsLimit: 10000,
    })
    await i18n.changeLanguage('de')
    render(<AccountPage />)
    expect(screen.getByText('8,8 / 10,0 Tsd. Wörter')).toBeInTheDocument()
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '87.5')
    await act(async () => i18n.changeLanguage('en'))
    expect(screen.getByText('8.8 / 10.0 k words')).toBeInTheDocument()
    expect(useAuthStore.getState().cloudWordsUsed).toBe(1250)
  })

  it('does not display cached word quota when the response marks it unavailable', () => {
    signedIn('present')
    useAuthStore.setState({
      quotaModel: 'cloud_words',
      quotaDataAvailable: false,
      cloudWordsUsed: 1250,
      cloudWordsLimit: 10000,
      sttSecondsLimit: 0,
      llmTokensLimit: 0,
    })
    render(<AccountPage />)
    expect(screen.queryByRole('progressbar')).toBeNull()
  })

  it('hides cached quota after subscription retrieval becomes unavailable', () => {
    signedIn('present')
    useAuthStore.setState({
      quotaModel: 'cloud_words',
      quotaDataAvailable: true,
      cloudWordsUsed: 1250,
      cloudWordsLimit: 10000,
      subscriptionRefreshState: 'unavailable',
    })
    render(<AccountPage />)
    expect(screen.queryByRole('progressbar')).toBeNull()
    expect(screen.queryByText('8.8 / 10.0 k words')).toBeNull()
  })

  it('formats quota reset and renewal dates using the selected UI language', async () => {
    signedIn('present')
    const resetAt = '2026-12-18T12:00:00Z'
    const renewsAt = '2027-01-19T12:00:00Z'
    useAuthStore.setState({
      quotaModel: 'cloud_words',
      cloudWordsResetAt: resetAt,
      subscriptionEnd: renewsAt,
      plan: 'pro',
      source: 'stripe',
      billingProvider: 'stripe',
      canManageBilling: true,
    })
    await i18n.changeLanguage('de')
    render(<AccountPage />)
    expect(screen.getByText(new Date(resetAt).toLocaleDateString('de'))).toBeInTheDocument()
    expect(screen.getByText(new Date(renewsAt).toLocaleDateString('de'))).toBeInTheDocument()
    await act(async () => i18n.changeLanguage('ja'))
    expect(screen.getByText(new Date(resetAt).toLocaleDateString('ja'))).toBeInTheDocument()
    expect(screen.getByText(new Date(renewsAt).toLocaleDateString('ja'))).toBeInTheDocument()
  })

  it('opens the localized Turnstile-protected reset page from beneath the password field', async () => {
    await i18n.changeLanguage('zh')
    render(<AccountPage />)

    const password = screen.getByLabelText('密码')
    const forgot = screen.getByRole('button', { name: '忘记密码？' })
    expect(password.compareDocumentPosition(forgot) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    fireEvent.click(forgot)
    fireEvent.click(screen.getByRole('button', { name: '发送重置链接' }))

    await waitFor(() => {
      expect(openUrl).toHaveBeenCalledWith('https://www.opentypeless.com/zh/login?mode=forgot')
    })
    expect(requestPasswordReset).not.toHaveBeenCalled()
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
    expect(within(dialog).getByRole('alert')).toHaveClass('text-error')

    fireEvent.change(within(dialog).getByLabelText('New password'), {
      target: { value: 'short' },
    })
    fireEvent.change(within(dialog).getByLabelText('Confirm password'), {
      target: { value: 'short' },
    })
    expect(within(dialog).getByRole('alert')).toHaveTextContent(i18n.t('account.passwordTooShort'))
    expect(within(dialog).getByRole('alert')).toHaveClass('text-error')
    expect(submit).toBeDisabled()
    expect(changePassword).not.toHaveBeenCalled()
    fireEvent.change(within(dialog).getByLabelText('New password'), {
      target: { value: 'new-password' },
    })
    fireEvent.change(within(dialog).getByLabelText('Confirm password'), {
      target: { value: 'new-password' },
    })
    expect(submit).toBeEnabled()

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('keeps the password dialog open when Escape belongs to input composition', () => {
    signedIn('present')
    render(<AccountPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
    const dialog = screen.getByRole('dialog', { name: 'Change password' })
    const input = within(dialog).getByLabelText('New password')
    fireEvent.keyDown(input, { key: 'Escape', isComposing: true })
    expect(dialog).toBeInTheDocument()
    expect(changePassword).not.toHaveBeenCalled()
    fireEvent.keyDown(input, { key: 'Escape', keyCode: 229 })
    expect(dialog).toBeInTheDocument()
    expect(changePassword).not.toHaveBeenCalled()
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('consumes password dialog Escape before an already registered parent listener', () => {
    signedIn('present')
    const closeParent = vi.fn()
    render(<ParentEscapeHarness onClose={closeParent} />)
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
    fireEvent.keyDown(within(screen.getByRole('dialog')).getByLabelText('New password'), {
      key: 'Escape',
    })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(closeParent).not.toHaveBeenCalled()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(closeParent).toHaveBeenCalledTimes(1)
  })

  it('keeps both dialog layers open when Escape arrives during password submission', async () => {
    signedIn('present')
    let finish = () => {}
    changePassword.mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finish = resolve
      }),
    )
    const closeParent = vi.fn()
    render(<ParentEscapeHarness onClose={closeParent} />)
    fireEvent.click(screen.getByRole('button', { name: 'Change password' }))
    const dialog = screen.getByRole('dialog', { name: 'Change password' })
    fireEvent.change(within(dialog).getByLabelText('Current password'), {
      target: { value: 'old-password' },
    })
    fireEvent.change(within(dialog).getByLabelText('New password'), {
      target: { value: 'new-password' },
    })
    fireEvent.change(within(dialog).getByLabelText('Confirm password'), {
      target: { value: 'new-password' },
    })
    const submit = within(dialog).getByRole('button', { name: 'Change password' })
    submit.focus()
    fireEvent.click(submit)
    expect(changePassword).toHaveBeenCalledTimes(1)
    fireEvent.keyDown(submit, { key: 'Tab' })
    expect(within(dialog).getByLabelText('Current password')).toHaveFocus()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(dialog).toBeInTheDocument()
    expect(closeParent).not.toHaveBeenCalled()
    await act(async () => finish())
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(closeParent).not.toHaveBeenCalled()
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

  it('restores through its versioned receipt without a later unversioned configuration read', async () => {
    signedIn('present')
    const config = {
      ...structuredClone(useAppStore.getInitialState().config),
      managed_stt_capability_state: null,
    }
    const configEpoch = '11111111-1111-4111-8111-111111111111'
    useAppStore
      .getState()
      .initializeConfigCommit({ configEpoch, revision: 1, saveId: null, config })
    vi.mocked(api.downloadBackup).mockResolvedValueOnce({ settings: { polish_enabled: false } })
    let identity = ''
    vi.mocked(tauri.updateConfig).mockImplementationOnce(async (submitted, saveId) => {
      identity = saveId!
      expect(pendingConfigSaves.snapshot(identity)).toEqual(submitted)
      useAppStore.getState().updateConfig({ microphone_device: 'newer-local-device' })
      return {
        config: submitted,
        savedRevision: 2,
        inputActivation: 'active',
        commit: { configEpoch, revision: 2, saveId: identity, config: submitted },
      }
    })
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleRestore())
    expect(result.current.backupMsg).toBe(i18n.t('account.toast.restoreOk'))
    expect(useAppStore.getState().configCommit?.revision).toBe(2)
    expect(useAppStore.getState().config.polish_enabled).toBe(false)
    expect(useAppStore.getState().config.microphone_device).toBe('newer-local-device')
    expect(tauri.getConfig).not.toHaveBeenCalled()
    expect(pendingConfigSaves.snapshot(identity)).toBeNull()
    useAppStore.setState(useAppStore.getInitialState())
  })

  it('rejects stale restore receipts without overwriting newer settings or restoring history', async () => {
    signedIn('present')
    const config = {
      ...structuredClone(useAppStore.getInitialState().config),
      managed_stt_capability_state: null,
    }
    const configEpoch = '11111111-1111-4111-8111-111111111111'
    useAppStore
      .getState()
      .initializeConfigCommit({ configEpoch, revision: 1, saveId: null, config })
    vi.mocked(api.downloadBackup).mockResolvedValueOnce({
      settings: { polish_enabled: false },
      history: [],
    })
    vi.mocked(tauri.validateBackupData).mockResolvedValueOnce(undefined)
    let identity = ''
    vi.mocked(tauri.updateConfig).mockImplementationOnce(async (submitted, saveId) => {
      identity = saveId!
      useAppStore.getState().applyConfigCommit({
        configEpoch,
        revision: 3,
        saveId: null,
        config: { ...submitted, microphone_device: 'newer-persisted-device' },
      })
      return {
        config: submitted,
        savedRevision: 2,
        inputActivation: 'active',
        commit: { configEpoch, revision: 2, saveId: identity, config: submitted },
      }
    })
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleRestore())
    expect(result.current.backupMsg).toContain('Settings may have changed')
    expect(result.current.backupMsg).toContain('Backup configuration restore superseded')
    expect(useAppStore.getState().configCommit?.revision).toBe(3)
    expect(useAppStore.getState().savedConfig?.microphone_device).toBe('newer-persisted-device')
    expect(tauri.restoreBackupData).not.toHaveBeenCalled()
    expect(pendingConfigSaves.snapshot(identity)).toBeNull()
    useAppStore.setState(useAppStore.getInitialState())
  })

  it('keeps translation fields together when restore failure follows a newer target-list edit', async () => {
    signedIn('present')
    const config = {
      ...structuredClone(useAppStore.getInitialState().config),
      managed_stt_capability_state: null,
    }
    const configEpoch = '11111111-1111-4111-8111-111111111111'
    useAppStore
      .getState()
      .initializeConfigCommit({ configEpoch, revision: 1, saveId: null, config })
    vi.mocked(api.downloadBackup).mockResolvedValueOnce({
      settings: { translation: { targets: ['fr'], active_target: 'fr' }, target_lang: 'fr' },
    })
    vi.mocked(tauri.updateConfig).mockImplementationOnce(async () => {
      useAppStore.getState().updateConfig({
        translation: { targets: ['fr', 'ja'], active_target: 'fr' },
        target_lang: 'fr',
      })
      throw new Error('Synthetic restore failure')
    })
    const { result } = renderHook(() => useAccountBackup())
    try {
      await act(async () => result.current.handleRestore())
      expect(useAppStore.getState().config.translation).toEqual({
        targets: ['fr', 'ja'],
        active_target: 'fr',
      })
      expect(useAppStore.getState().config.target_lang).toBe('fr')
      expect(useAppStore.getState().savedConfig?.target_lang).toBe('en')
    } finally {
      useAppStore.setState(useAppStore.getInitialState())
    }
  })

  it('preserves the entire newer scene draft when a pending backup restore fails', async () => {
    signedIn('present')
    const config = {
      ...structuredClone(useAppStore.getInitialState().config),
      managed_stt_capability_state: null,
    }
    useAppStore.getState().initializeConfigCommit({
      configEpoch: '11111111-1111-4111-8111-111111111111',
      revision: 1,
      saveId: null,
      config,
    })
    const scene = {
      id: 'custom_restore',
      name: 'Restore',
      description: '',
      prompt_template: 'Original instructions.',
      created_at: '',
      updated_at: '',
    }
    vi.mocked(api.downloadBackup).mockResolvedValueOnce({
      settings: {
        custom_scenes: [scene],
        family_scene_assignments: [{ family: 'email', scene_id: scene.id }],
      },
    })
    vi.mocked(tauri.updateConfig).mockImplementationOnce(async () => {
      useAppStore.getState().updateConfig({
        custom_scenes: [{ ...scene, prompt_template: 'Newer instructions.' }],
      })
      throw new Error('Synthetic restore failure')
    })
    const { result } = renderHook(() => useAccountBackup())
    try {
      await act(async () => result.current.handleRestore())
      expect(useAppStore.getState().config.custom_scenes[0].prompt_template).toBe(
        'Newer instructions.',
      )
      expect(useAppStore.getState().config.family_scene_assignments).toEqual([
        { family: 'email', scene_id: scene.id },
      ])
    } finally {
      useAppStore.setState(useAppStore.getInitialState())
    }
  })

  it('omits credential-bearing endpoints from the account backup request', async () => {
    signedIn('present')
    const current = {
      ...useAppStore.getState().config,
      llm_base_url: 'https://example.com/v1?api_key=private-llm',
      stt_custom_base_url: 'https://example.com/v1?access_token=private-stt',
    }
    useAppStore.getState().setConfig(current)
    vi.mocked(api.uploadBackup).mockResolvedValueOnce({ success: true })
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleBackup())
    expect(api.uploadBackup).toHaveBeenCalledTimes(1)
    const payload = vi.mocked(api.uploadBackup).mock.calls[0][0]
    expect(payload.settings).not.toHaveProperty('llm_base_url', expect.any(String))
    expect(payload.settings).not.toHaveProperty('stt_custom_base_url', expect.any(String))
    expect(JSON.stringify(payload)).not.toContain('private-llm')
    expect(JSON.stringify(payload)).not.toContain('private-stt')
    expect(useAppStore.getState().config).toEqual(current)
  })

  it('backs up retained native history rather than only the visible page', async () => {
    signedIn('present')
    const retained = Array.from({ length: 201 }, (_, index) => ({
      id: index + 1,
      created_at: '2026-11-01T10:00:00',
      raw_text: 'Synthetic question',
      polished_text: 'Synthetic question',
      mode: 'ask',
      answer_text: `Synthetic answer ${index}`,
    })) as Awaited<ReturnType<typeof tauri.getHistory>>
    vi.mocked(tauri.getCloudBackupHistory).mockResolvedValueOnce(retained)
    useAppStore.setState({ history: retained.slice(0, 200) })
    vi.mocked(api.uploadBackup).mockResolvedValueOnce({ success: true })
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleBackup())
    expect(tauri.getCloudBackupHistory).toHaveBeenCalledOnce()
    expect(vi.mocked(api.uploadBackup).mock.calls[0][0].history).toEqual(retained)
  })

  it('does not upload an incomplete backup if native history cannot be read', async () => {
    signedIn('present')
    vi.mocked(tauri.getCloudBackupHistory).mockRejectedValueOnce(
      new Error('Synthetic read failure'),
    )
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleBackup())
    expect(api.uploadBackup).not.toHaveBeenCalled()
    expect(result.current.backupMsg).toBe('Synthetic read failure')
  })

  it('refuses to silently upload only the latest 5000 history entries', async () => {
    signedIn('present')
    vi.mocked(tauri.getCloudBackupHistory).mockRejectedValueOnce('backup_history_too_large')
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleBackup())
    expect(api.uploadBackup).not.toHaveBeenCalled()
    expect(result.current.backupMsg).toContain('5000')
    expect(result.current.backupLoading).toBe(false)
  })

  it('excludes raw history errors from cloud backup while keeping answers and statuses', async () => {
    signedIn('present')
    const history = [
      {
        id: 71,
        created_at: '2026-11-01T10:00:00',
        raw_text: 'Synthetic question',
        polished_text: 'Synthetic question',
        mode: 'ask',
        answer_text: 'Synthetic answer',
        output_status: 'partial',
        output_error: 'Service failure with synthetic-private-error-token',
      },
    ] as Awaited<ReturnType<typeof tauri.getHistory>>
    vi.mocked(tauri.getCloudBackupHistory).mockResolvedValueOnce(history)
    vi.mocked(api.uploadBackup).mockResolvedValueOnce({ success: true })
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleBackup())
    const uploaded = vi.mocked(api.uploadBackup).mock.calls[0][0].history
    expect(JSON.stringify(uploaded)).not.toContain('synthetic-private-error-token')
    expect(uploaded).toEqual([{ ...history[0], output_error: null }])
    expect(history[0].output_error).toContain('synthetic-private-error-token')
  })

  it('backs up persisted dictionary and corrections instead of stale renderer data', async () => {
    signedIn('present')
    const dictionary = [{ id: 91, word: 'Persisted word', pronunciation: null, auto: false }]
    const corrections = [
      { id: 92, pattern: 'old spelling', replacement: 'New spelling', enabled: true },
    ]
    vi.mocked(tauri.getDictionary).mockResolvedValueOnce(dictionary)
    vi.mocked(tauri.getCorrectionRules).mockResolvedValueOnce(corrections)
    useAppStore.setState({ dictionary: [], correctionRules: [] })
    vi.mocked(api.uploadBackup).mockResolvedValueOnce({ success: true })
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleBackup())
    expect(vi.mocked(api.uploadBackup).mock.calls[0][0].dictionary).toEqual({
      entries: dictionary,
      correction_rules: corrections,
    })
  })

  it('backs up persisted settings instead of an unsaved local draft', async () => {
    signedIn('present')
    const saved = { ...useAppStore.getState().config, theme: 'light' as const }
    useAppStore.getState().setConfig({ ...saved, theme: 'dark' })
    vi.mocked(tauri.getConfig).mockResolvedValueOnce(saved)
    vi.mocked(api.uploadBackup).mockResolvedValueOnce({ success: true })
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleBackup())
    expect(vi.mocked(api.uploadBackup).mock.calls[0][0].settings).toHaveProperty('theme', 'light')
    expect(useAppStore.getState().config.theme).toBe('dark')
  })

  it('does not upload when persisted settings cannot be read', async () => {
    signedIn('present')
    vi.mocked(tauri.getConfig).mockRejectedValueOnce(new Error('Synthetic settings read failure'))
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleBackup())
    expect(api.uploadBackup).not.toHaveBeenCalled()
    expect(result.current.backupMsg).toBe('Synthetic settings read failure')
  })

  it.each(['dictionary', 'corrections'] as const)(
    'does not upload when persisted %s cannot be read',
    async (section) => {
      signedIn('present')
      const reader = section === 'dictionary' ? tauri.getDictionary : tauri.getCorrectionRules
      vi.mocked(reader).mockRejectedValueOnce(new Error('Synthetic section read failure'))
      const { result } = renderHook(() => useAccountBackup())
      await act(async () => result.current.handleBackup())
      expect(api.uploadBackup).not.toHaveBeenCalled()
      expect(result.current.backupMsg).toBe('Synthetic section read failure')
    },
  )

  it('keeps local endpoints while restoring other account settings from an old backup', async () => {
    signedIn('present')
    const current = {
      ...useAppStore.getState().config,
      llm_base_url: 'http://localhost:11434/v1',
      stt_custom_base_url: 'http://localhost:8000/v1',
    }
    useAppStore.getState().setConfig(current)
    vi.mocked(api.downloadBackup).mockResolvedValueOnce({
      settings: {
        llm_base_url: 'https://example.com/v1?api_key=private-llm',
        stt_custom_base_url: 'https://example.com/v1?access_token=private-stt',
        polish_enabled: !current.polish_enabled,
      },
    })
    vi.mocked(tauri.updateConfig).mockResolvedValueOnce(undefined)
    vi.mocked(tauri.getConfig).mockResolvedValueOnce({
      ...current,
      polish_enabled: !current.polish_enabled,
    })
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleRestore())
    expect(tauri.updateConfig).toHaveBeenCalledWith(
      expect.objectContaining({
        llm_base_url: current.llm_base_url,
        stt_custom_base_url: current.stt_custom_base_url,
        polish_enabled: !current.polish_enabled,
      }),
    )
    expect(useAppStore.getState().config.llm_base_url).toBe(current.llm_base_url)
    expect(useAppStore.getState().config.stt_custom_base_url).toBe(current.stt_custom_base_url)
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
    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Restore' }),
    )

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

  it('keeps settings edited during backup download when the backup does not contain them', async () => {
    signedIn('present')
    useAuthStore.setState({
      plan: 'pro',
      source: 'creem',
      cloudWordsLimit: 1000,
      licenseStatus: 'active',
    })
    let finish: (value: unknown) => void = () => {}
    vi.mocked(api.downloadBackup).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve as (value: unknown) => void
      }) as never,
    )
    vi.mocked(tauri.updateConfig).mockResolvedValue(undefined)
    vi.mocked(tauri.getConfig).mockRejectedValueOnce(new Error('read unavailable'))
    render(<AccountPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Restore' }),
    )
    act(() => useAppStore.getState().updateConfig({ microphone_device: 'New microphone' }))
    await act(async () => finish({ settings: { polish_enabled: false } }))
    expect(tauri.updateConfig).toHaveBeenCalledWith(
      expect.objectContaining({
        microphone_device: 'New microphone',
        polish_enabled: false,
      }),
    )
  })

  it('preserves edits made while restored settings are being saved', async () => {
    signedIn('present')
    useAuthStore.setState({
      plan: 'pro',
      source: 'creem',
      cloudWordsLimit: 1000,
      licenseStatus: 'active',
    })
    const current = useAppStore.getState().config
    const restored = { ...current, polish_enabled: false }
    let finishSave: () => void = () => {}
    vi.mocked(api.downloadBackup).mockResolvedValueOnce({
      settings: { polish_enabled: false },
    })
    vi.mocked(tauri.updateConfig).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finishSave = resolve
      }),
    )
    vi.mocked(tauri.getConfig).mockResolvedValueOnce(restored)
    render(<AccountPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Restore' }),
    )
    await waitFor(() => expect(tauri.updateConfig).toHaveBeenCalled())
    act(() => useAppStore.getState().updateConfig({ microphone_device: 'Edited while saving' }))
    await act(async () => finishSave())
    expect(useAppStore.getState().config.microphone_device).toBe('Edited while saving')
    expect(useAppStore.getState().config.polish_enabled).toBe(false)
    expect(useAppStore.getState().savedConfig).toEqual(restored)
  })

  it('does not restore data or invent a saved baseline when settings readback fails', async () => {
    signedIn('present')
    const previous = useAppStore.getState().config
    useAppStore.getState().setSavedConfig(previous)
    vi.mocked(api.downloadBackup).mockResolvedValueOnce({
      settings: { polish_enabled: !previous.polish_enabled },
      history: [],
    })
    vi.mocked(tauri.updateConfig).mockResolvedValueOnce(undefined)
    vi.mocked(tauri.getConfig).mockRejectedValueOnce(new Error('Synthetic readback failure'))
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleRestore())
    expect(tauri.restoreBackupData).not.toHaveBeenCalled()
    expect(useAppStore.getState().savedConfig).toEqual(previous)
    expect(result.current.backupMsg).toContain('Settings may have changed')
    expect(result.current.backupMsg).toContain('Synthetic readback failure')
  })

  it('reports partial restoration when settings are verified but data restoration fails', async () => {
    signedIn('present')
    const previous = useAppStore.getState().config
    const restored = { ...previous, polish_enabled: !previous.polish_enabled }
    useAppStore.getState().setSavedConfig(previous)
    vi.mocked(api.downloadBackup).mockResolvedValueOnce({
      settings: { polish_enabled: restored.polish_enabled },
      history: [],
    })
    vi.mocked(tauri.updateConfig).mockResolvedValueOnce(undefined)
    vi.mocked(tauri.getConfig).mockResolvedValueOnce(restored)
    vi.mocked(tauri.restoreBackupData).mockRejectedValueOnce(
      new Error('Synthetic database failure'),
    )
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleRestore())
    expect(useAppStore.getState().savedConfig).toEqual(restored)
    expect(result.current.backupMsg).toContain('Settings were restored')
    expect(result.current.backupMsg).toContain('Synthetic database failure')
    expect(result.current.backupLoading).toBe(false)
  })

  it('reports unconfirmed data changes after a data-only restore command rejects', async () => {
    signedIn('present')
    const previous = useAppStore.getState().history
    vi.mocked(api.downloadBackup).mockResolvedValueOnce({ history: [] })
    vi.mocked(tauri.restoreBackupData).mockRejectedValueOnce(
      new Error('Synthetic post-commit read failure'),
    )
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleRestore())
    expect(result.current.backupMsg).toContain('Local data may have changed')
    expect(result.current.backupMsg).toContain('Synthetic post-commit read failure')
    expect(useAppStore.getState().history).toBe(previous)
    expect(tauri.restoreBackupData).toHaveBeenCalledTimes(1)
    expect(tauri.updateConfig).not.toHaveBeenCalled()
    expect(result.current.backupLoading).toBe(false)
  })

  it.each([false, true])(
    'rolls back restore auto-start only while its saved baseline is unchanged: newer=%s',
    async (newer) => {
      signedIn('present')
      const previous = useAppStore.getState().config
      useAppStore.getState().setSavedConfig(previous)
      const restored = { ...previous, auto_start: !previous.auto_start }
      vi.mocked(tauri.setAutoStart).mockResolvedValue(undefined)
      vi.mocked(api.downloadBackup).mockResolvedValueOnce({
        settings: { auto_start: restored.auto_start },
      })
      let failSave: ((error: Error) => void) | undefined
      vi.mocked(tauri.updateConfig).mockImplementationOnce(
        () =>
          new Promise<void>((_resolve, reject) => {
            failSave = reject
          }),
      )
      const { result } = renderHook(() => useAccountBackup())
      let pending: Promise<void> = Promise.resolve()
      act(() => {
        pending = result.current.handleRestore()
      })
      await waitFor(() => expect(tauri.updateConfig).toHaveBeenCalledOnce())
      if (newer) act(() => useAppStore.getState().setSavedConfig(restored))
      await act(async () => {
        failSave?.(new Error('Synthetic restore save failure'))
        await pending
      })
      expect(tauri.setAutoStart).toHaveBeenCalledTimes(newer ? 1 : 2)
      if (!newer) expect(tauri.setAutoStart).toHaveBeenLastCalledWith(previous.auto_start)
      expect(result.current.backupMsg).toBe('Synthetic restore save failure')
    },
  )

  it('rejects native backup validation before changing settings or restoring data', async () => {
    signedIn('present')
    vi.mocked(api.downloadBackup).mockResolvedValueOnce({
      settings: { auto_start: !useAppStore.getState().config.auto_start },
      history: [{ raw_text: 'Synthetic invalid history' }],
    })
    vi.mocked(tauri.validateBackupData).mockRejectedValueOnce(
      new Error('Synthetic native validation failure'),
    )
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleRestore())
    expect(tauri.updateConfig).not.toHaveBeenCalled()
    expect(tauri.setAutoStart).not.toHaveBeenCalled()
    expect(tauri.restoreBackupData).not.toHaveBeenCalled()
    expect(result.current.backupMsg).toBe('Synthetic native validation failure')
  })

  it('rejects conflicting restored shortcuts before changing startup or restoring data', async () => {
    signedIn('present')
    const previous = useAppStore.getState().config
    useAppStore.getState().setSavedConfig(previous)
    vi.mocked(api.downloadBackup).mockResolvedValueOnce({
      settings: {
        auto_start: !previous.auto_start,
        hotkey: previous.hotkey,
        ask_hotkey: previous.hotkey,
        hotkeys: {
          ...previous.hotkeys,
          ask: previous.hotkeys.dictation,
          askBindings: [...previous.hotkeys.dictationBindings],
        },
      },
      history: [],
    })
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleRestore())
    expect(tauri.setAutoStart).not.toHaveBeenCalled()
    expect(tauri.updateConfig).not.toHaveBeenCalled()
    expect(tauri.restoreBackupData).not.toHaveBeenCalled()
    expect(useAppStore.getState().config).toBe(previous)
    expect(useAppStore.getState().savedConfig).toBe(previous)
    expect(result.current.backupMsg).toBe('Invalid shortcut configuration')
  })

  it('reports both restoration and auto-start rollback failures', async () => {
    signedIn('present')
    const previous = useAppStore.getState().config
    useAppStore.getState().setSavedConfig(previous)
    vi.mocked(api.downloadBackup).mockResolvedValueOnce({
      settings: { auto_start: !previous.auto_start },
    })
    vi.mocked(tauri.setAutoStart)
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new Error('Synthetic rollback failure'))
    vi.mocked(tauri.updateConfig).mockRejectedValueOnce(new Error('Synthetic save failure'))
    const { result } = renderHook(() => useAccountBackup())
    await act(async () => result.current.handleRestore())
    expect(result.current.backupMsg).toContain('Synthetic save failure')
    expect(result.current.backupMsg).toContain('Synthetic rollback failure')
    expect(result.current.backupMsg).toContain('Launch at Startup')
    expect(tauri.restoreBackupData).not.toHaveBeenCalled()
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
    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Restore' }),
    )

    await waitFor(() => {
      expect(tauri.restoreBackupData).toHaveBeenCalledWith(cloudHistory, cloudDictionary)
      expect(useAppStore.getState().history).toEqual(restoredHistory)
      expect(useAppStore.getState().dictionary).toEqual(restoredDictionary)
      expect(useAppStore.getState().correctionRules).toEqual(restoredCorrections)
    })
  })

  it('does not restore a previous account backup after the account changes', async () => {
    signedIn('present')
    useAuthStore.setState({
      plan: 'pro',
      source: 'creem',
      cloudWordsLimit: 1000,
      licenseStatus: 'active',
    })
    let finish: (value: unknown) => void = () => {}
    vi.mocked(api.downloadBackup).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve as (value: unknown) => void
      }) as never,
    )
    render(<AccountPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Restore' }),
    )
    act(() => {
      useAuthStore.setState({
        user: { id: 'new-user', email: 'new@example.com', name: null, emailVerified: true },
      })
    })
    await act(async () => finish({ history: [{ raw_text: 'previous account text' }] }))
    expect(tauri.restoreBackupData).not.toHaveBeenCalled()
    expect(tauri.updateConfig).not.toHaveBeenCalled()
  })

  it('does not replace newer desktop data with a late restoration response', async () => {
    signedIn('present')
    vi.mocked(api.downloadBackup).mockResolvedValueOnce({ history: [], dictionary: [] })
    let finish: ((value: Awaited<ReturnType<typeof tauri.restoreBackupData>>) => void) | undefined
    vi.mocked(tauri.restoreBackupData).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        }),
    )
    const { result } = renderHook(() => useAccountBackup())
    let pending: Promise<void> = Promise.resolve()
    act(() => {
      pending = result.current.handleRestore()
    })
    await waitFor(() => expect(tauri.restoreBackupData).toHaveBeenCalledOnce())
    const newerHistory = [{ id: 99, raw_text: 'New history' }] as never
    const newerDictionary = [{ id: 98, word: 'New dictionary', pronunciation: null }]
    const newerRules = [{ id: 97, pattern: 'new', replacement: 'New', enabled: true }]
    act(() =>
      useAppStore.setState({
        history: newerHistory,
        dictionary: newerDictionary,
        correctionRules: newerRules,
      }),
    )
    await act(async () => {
      finish?.({ history: [], dictionary: [], correctionRules: [] })
      await pending
    })
    expect(useAppStore.getState().history).toEqual(newerHistory)
    expect(useAppStore.getState().dictionary).toEqual(newerDictionary)
    expect(useAppStore.getState().correctionRules).toEqual(newerRules)
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
