import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  readPendingDesktopCheckout,
  savePendingDesktopCheckout,
} from '../../../lib/desktop-checkout-intent'
import { UpgradePage } from '../index'

type MockPlan =
  | 'free'
  | 'pro'
  | 'lifetime_starter'
  | 'appsumo_tier1'
  | 'appsumo_tier2'
  | 'appsumo_tier3'
type MockSource = 'free' | 'creem' | 'stripe' | 'lifetime' | 'appsumo'
type MockBillingProvider = 'stripe' | 'creem' | 'appsumo' | null
type MockLicenseStatus = 'pending' | 'active' | 'refunded' | 'deactivated' | null

const mocks = vi.hoisted(() => ({
  createCheckout: vi.fn().mockResolvedValue({ url: 'https://checkout.example.test' }),
  openUrl: vi.fn().mockResolvedValue(undefined),
  setState: vi.fn(),
}))

const mockAuthState = {
  user: null as null | { id: string; email: string; name: null },
  plan: 'free' as MockPlan,
  source: 'free' as MockSource,
  displayName: 'Free',
  subscriptionStatus: null as string | null,
  billingProvider: null as MockBillingProvider,
  canMigrateToStripe: false,
  licenseStatus: null as MockLicenseStatus,
  quotaModel: 'legacy_dual_meter' as const,
  displayWordsUsedEstimate: 0,
  displayWordsLimit: 0,
  cloudWordsUsed: 0,
  cloudWordsLimit: 0,
  sttSecondsUsed: 0,
  sttSecondsLimit: 0,
  llmTokensUsed: 0,
  llmTokensLimit: 0,
}

vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: mocks.openUrl }))

vi.mock('../../../lib/api', () => ({
  CloudApiError: class CloudApiError extends Error {
    constructor(
      public status: number,
      public code: string | null,
      message: string,
    ) {
      super(message)
    }
  },
  createCheckout: mocks.createCheckout,
}))

vi.mock('../../../stores/authStore', () => ({
  hasManagedCloudAccess: (state: typeof mockAuthState) =>
    state.licenseStatus !== 'refunded' &&
    state.licenseStatus !== 'deactivated' &&
    ((state.source === 'creem' && state.cloudWordsLimit > 0) ||
      (state.source === 'stripe' && state.cloudWordsLimit > 0) ||
      (state.source === 'lifetime' && state.cloudWordsLimit > 0) ||
      (state.source === 'appsumo' &&
        state.cloudWordsLimit > 0 &&
        state.licenseStatus === 'active') ||
      state.plan === 'pro' ||
      state.plan === 'lifetime_starter'),
  useAuthStore: Object.assign(
    (selector: ((state: typeof mockAuthState) => unknown) | undefined) =>
      typeof selector === 'function' ? selector(mockAuthState) : mockAuthState,
    { setState: mocks.setState },
  ),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, values?: Record<string, string>) =>
      (
        ({
          'upgrade.title': 'Upgrade',
          'upgrade.subtitle': 'Fast voice recognition and AI rewriting.',
          'upgrade.currentPlan': `Current plan: ${values?.plan ?? ''}`,
          'upgrade.pro': 'Pro Monthly',
          'upgrade.lifetime': 'Lifetime Starter',
          'upgrade.lifetimeBadge': 'Best value',
          'upgrade.lifetimeSave': 'Save after 18 months',
          'upgrade.lifetimeUpgradeSave': 'Includes your current monthly credit',
          'upgrade.month': 'month',
          'upgrade.oneTime': 'one-time',
          'upgrade.subscribeToPro': 'Subscribe to Pro',
          'upgrade.buyLifetime': 'Buy lifetime',
          'upgrade.signInFirst': 'Choose a plan to sign in and continue automatically.',
          'upgrade.openingCheckout': 'Opening secure checkout...',
          'upgrade.checkoutTemporarilyUnavailable':
            'Secure payment is temporarily unavailable. Your card was not charged.',
          'upgrade.checkoutRateLimited': 'Too many payment attempts.',
          'upgrade.checkoutInProgress': 'Another payment is already being prepared.',
          'upgrade.paymentNeedsAttention': 'Payment needs attention',
          'upgrade.restorePro': 'Restore Pro',
          'upgrade.migrationDescription':
            'Your Creem renewal failed. Continue Pro securely with Stripe.',
          'upgrade.switchToStripe': 'Continue with Stripe',
          'upgrade.benefits.title': 'What you get',
          'upgrade.benefits.cloudWords': '100,000 cloud words/month for voice and AI',
          'upgrade.benefits.noApiKey': 'No API keys required in cloud mode',
          'upgrade.benefits.backupScenes': 'Cloud backup and Pro scene packs',
          'upgrade.monthlyActive': 'Pro is active.',
          'upgrade.monthlyActiveLifetimeHint':
            'Pro is active. Lifetime is available as a one-time upgrade.',
          'upgrade.thankYou': 'Your plan is active — thank you!',
        }) as Record<string, string>
      )[key] ?? key,
  }),
}))

beforeEach(() => {
  Object.assign(mockAuthState, {
    user: null,
    plan: 'free' as MockPlan,
    source: 'free' as MockSource,
    displayName: 'Free',
    subscriptionStatus: null,
    billingProvider: null,
    canMigrateToStripe: false,
    licenseStatus: null,
    quotaModel: 'legacy_dual_meter' as const,
    displayWordsUsedEstimate: 0,
    displayWordsLimit: 0,
    cloudWordsUsed: 0,
    cloudWordsLimit: 0,
    sttSecondsUsed: 0,
    sttSecondsLimit: 0,
    llmTokensUsed: 0,
    llmTokensLimit: 0,
  })
  localStorage.clear()
  window.location.hash = '#/upgrade'
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('UpgradePage', () => {
  it('keeps both free-user purchase choices concise', () => {
    render(<UpgradePage />)

    expect(screen.getByText('Pro Monthly')).toBeInTheDocument()
    expect(screen.getByText('$4.99')).toBeInTheDocument()
    expect(screen.getByText('Lifetime Starter')).toBeInTheDocument()
    expect(screen.getByText('$89.99')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'What you get' })).toBeInTheDocument()
  })

  it('records an anonymous monthly purchase and opens the existing sign-in page', () => {
    render(<UpgradePage />)
    const button = screen.getByRole('button', { name: 'Subscribe to Pro' })
    expect(button).toBeEnabled()

    fireEvent.click(button)

    expect(readPendingDesktopCheckout(localStorage)?.product).toBe('pro_monthly')
    expect(window.location.hash).toBe('#/account')
    expect(mocks.createCheckout).not.toHaveBeenCalled()
  })

  it('automatically resumes the selected product once desktop sign-in completes', async () => {
    savePendingDesktopCheckout(localStorage, 'lifetime_starter')
    Object.assign(mockAuthState, {
      user: { id: 'user-1', email: 'user@example.com', name: null },
    })

    render(<UpgradePage />)

    await waitFor(() => {
      expect(mocks.createCheckout).toHaveBeenCalledTimes(1)
      expect(mocks.createCheckout).toHaveBeenCalledWith('desktop', 'lifetime_starter')
      expect(mocks.openUrl).toHaveBeenCalledWith('https://checkout.example.test')
    })
    expect(readPendingDesktopCheckout(localStorage)).toBeNull()
  })

  it('starts monthly checkout with one guarded request', async () => {
    Object.assign(mockAuthState, {
      user: { id: 'user-1', email: 'user@example.com', name: null },
    })

    render(<UpgradePage />)
    const button = screen.getByRole('button', { name: 'Subscribe to Pro' })
    fireEvent.click(button)
    fireEvent.click(button)

    await waitFor(() => {
      expect(mocks.createCheckout).toHaveBeenCalledTimes(1)
      expect(mocks.createCheckout).toHaveBeenCalledWith('desktop', 'pro_monthly')
    })
  })

  it.each([
    ['stripe', 'stripe'],
    ['creem', 'creem'],
  ] as const)(
    'shows only the lifetime upgrade for an active %s monthly user',
    (source, provider) => {
      Object.assign(mockAuthState, {
        user: { id: 'user-1', email: 'user@example.com', name: null },
        plan: 'pro' as MockPlan,
        source: source as MockSource,
        billingProvider: provider as MockBillingProvider,
        subscriptionStatus: 'active',
        displayName: 'Pro',
        cloudWordsLimit: 100000,
      })

      render(<UpgradePage />)
      expect(screen.queryByText('Pro Monthly')).not.toBeInTheDocument()
      expect(screen.getByText('Lifetime Starter')).toBeInTheDocument()
      expect(screen.getByText('$84.99')).toBeInTheDocument()
      expect(screen.queryByText('Payment needs attention')).not.toBeInTheDocument()
    },
  )

  it('offers Stripe recovery only for a failed Creem renewal', () => {
    Object.assign(mockAuthState, {
      user: { id: 'user-1', email: 'user@example.com', name: null },
      subscriptionStatus: 'past_due',
      billingProvider: 'creem' as MockBillingProvider,
      canMigrateToStripe: true,
    })

    render(<UpgradePage />)

    expect(screen.getByText('Restore Pro')).toBeInTheDocument()
    expect(screen.getByText(/Creem renewal failed/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue with Stripe' })).toBeInTheDocument()
    expect(screen.queryByText('Lifetime Starter')).not.toBeInTheDocument()
  })

  it.each([
    ['lifetime_starter', 'lifetime', 'Lifetime Starter'],
    ['appsumo_tier1', 'appsumo', 'AppSumo Lifetime'],
  ] as const)('does not offer another payment to a %s user', (plan, source, displayName) => {
    Object.assign(mockAuthState, {
      user: { id: 'user-1', email: 'user@example.com', name: null },
      plan: plan as MockPlan,
      source: source as MockSource,
      displayName,
      licenseStatus: 'active' as MockLicenseStatus,
      cloudWordsLimit: 100000,
    })

    render(<UpgradePage />)
    expect(screen.queryByRole('button', { name: 'Subscribe to Pro' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Buy lifetime' })).not.toBeInTheDocument()
    expect(screen.getByText('Your plan is active — thank you!')).toBeInTheDocument()
  })
})
