import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  createCheckout: vi.fn(),
  getCheckoutOrderStatus: vi.fn(),
  openUrl: vi.fn(),
  refreshSubscription: vi.fn(),
  account: {
    user: { id: 'account-a' },
    plan: 'free',
    billingProvider: null as string | null,
    subscriptionRefreshState: 'fresh',
    checkoutPending: false,
  },
}))

vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: mocks.openUrl }))
vi.mock('../api', () => ({
  createCheckout: mocks.createCheckout,
  getCheckoutOrderStatus: mocks.getCheckoutOrderStatus,
}))
vi.mock('../cloud-session', () => ({
  captureCloudSessionOwner: () => () => true,
}))
vi.mock('../../stores/authStore', () => ({
  useAuthStore: {
    getState: () => ({ ...mocks.account, refreshSubscription: mocks.refreshSubscription }),
    setState: (patch: Partial<typeof mocks.account>) => Object.assign(mocks.account, patch),
  },
}))

import { readDesktopCheckoutOrder, rememberDesktopCheckoutOrder } from '../desktop-checkout-intent'
import { openDesktopCheckout, refreshDesktopCheckout } from '../desktop-checkout-order'

const FIRST_ORDER = '00000000-0000-4000-8000-000000000001'
const NEXT_ORDER = '00000000-0000-4000-8000-000000000002'

describe('desktop checkout order recovery', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    localStorage.clear()
    Object.assign(mocks.account, {
      user: { id: 'account-a' },
      plan: 'free',
      billingProvider: null,
      subscriptionRefreshState: 'fresh',
      checkoutPending: false,
    })
  })

  it('saves the order before opening the browser', async () => {
    mocks.createCheckout.mockResolvedValue({
      url: 'https://checkout.example.test',
      checkoutIntentId: FIRST_ORDER,
    })
    mocks.openUrl.mockImplementation(() => {
      expect(readDesktopCheckoutOrder(localStorage, 'account-a')).toBe(FIRST_ORDER)
    })

    await openDesktopCheckout('account-a', 'pro_monthly')
    expect(mocks.openUrl).toHaveBeenCalledOnce()
    expect(mocks.account.checkoutPending).toBe(true)
  })

  it('restores the saved order and waits for both payment and visible entitlement', async () => {
    rememberDesktopCheckoutOrder(localStorage, 'account-a', FIRST_ORDER)
    mocks.getCheckoutOrderStatus
      .mockResolvedValueOnce({ status: 'pending' })
      .mockResolvedValue({ status: 'paid', product: 'pro_monthly', provider: 'stripe' })

    await refreshDesktopCheckout('account-a')
    expect(mocks.getCheckoutOrderStatus).toHaveBeenCalledWith(FIRST_ORDER)
    expect(mocks.account.checkoutPending).toBe(true)
    expect(mocks.refreshSubscription).not.toHaveBeenCalled()

    await refreshDesktopCheckout('account-a')
    expect(readDesktopCheckoutOrder(localStorage, 'account-a')).toBe(FIRST_ORDER)
    expect(mocks.account.checkoutPending).toBe(true)

    mocks.refreshSubscription.mockImplementationOnce(() => {
      mocks.account.plan = 'pro'
      mocks.account.billingProvider = 'stripe'
    })
    await refreshDesktopCheckout('account-a')
    expect(readDesktopCheckoutOrder(localStorage, 'account-a')).toBeNull()
    expect(mocks.account.checkoutPending).toBe(false)
  })

  it('does not apply an old response to a new order or to another account', async () => {
    rememberDesktopCheckoutOrder(localStorage, 'account-a', FIRST_ORDER)
    let end: ((value: { status: string }) => void) | undefined
    mocks.getCheckoutOrderStatus.mockImplementationOnce(
      () =>
        new Promise<{ status: string }>((resolve) => {
          end = resolve
        }),
    )
    const pending = refreshDesktopCheckout('account-a')
    rememberDesktopCheckoutOrder(localStorage, 'account-a', NEXT_ORDER)
    end?.({ status: 'expired' })
    await pending
    expect(readDesktopCheckoutOrder(localStorage, 'account-a')).toBe(NEXT_ORDER)

    mocks.account.user = { id: 'account-b' }
    expect(refreshDesktopCheckout('account-b')).toBeNull()
    expect(mocks.getCheckoutOrderStatus).toHaveBeenCalledOnce()
  })
})
