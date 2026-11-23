import { openUrl } from '@tauri-apps/plugin-opener'
import { useAuthStore } from '../stores/authStore'
import { createCheckout, getCheckoutOrderStatus } from './api'
import { captureCloudSessionOwner } from './cloud-session'
import type { CheckoutProduct } from './constants'
import {
  clearDesktopCheckoutOrder,
  isCheckoutIntentId,
  readDesktopCheckoutOrder,
  rememberDesktopCheckoutOrder,
} from './desktop-checkout-intent'

let inFlight: {
  intentId: string
  isCurrent: () => boolean
  completion: Promise<void>
} | null = null

export async function openDesktopCheckout(userId: string, product: CheckoutProduct) {
  const owner = useAuthStore.getState().user
  if (owner?.id !== userId) return
  const isSessionCurrent = captureCloudSessionOwner()
  const checkout = await createCheckout('desktop', product)
  if (!isSessionCurrent() || useAuthStore.getState().user !== owner) return
  if (checkout.checkoutIntentId !== undefined) {
    if (!isCheckoutIntentId(checkout.checkoutIntentId)) throw new Error('Invalid checkout order')
    rememberDesktopCheckoutOrder(localStorage, userId, checkout.checkoutIntentId)
  }
  useAuthStore.setState({ checkoutPending: true })
  await openUrl(checkout.url)
}

/** Return null when this account has no locally tracked order. */
export function refreshDesktopCheckout(userId: string): Promise<void> | null {
  const intentId = readDesktopCheckoutOrder(localStorage, userId)
  const owner = useAuthStore.getState().user
  if (!intentId || owner?.id !== userId) return null
  if (inFlight?.intentId === intentId && inFlight.isCurrent()) return inFlight.completion

  const isSessionCurrent = captureCloudSessionOwner()
  const isCurrent = () =>
    isSessionCurrent() &&
    useAuthStore.getState().user === owner &&
    readDesktopCheckoutOrder(localStorage, userId) === intentId

  if (!isCurrent()) return null
  useAuthStore.setState({ checkoutPending: true })
  const completion = (async () => {
    const order = await getCheckoutOrderStatus(intentId)
    if (!isCurrent() || order.status === 'pending') return
    if (order.status === 'paid') {
      await useAuthStore.getState().refreshSubscription()
      if (!isCurrent()) return
      const account = useAuthStore.getState()
      const entitlementVisible =
        order.product === 'lifetime_starter'
          ? account.plan === 'lifetime_starter'
          : account.plan === 'pro' && account.billingProvider === order.provider
      if (account.subscriptionRefreshState !== 'fresh' || !entitlementVisible) return
    }
    clearDesktopCheckoutOrder(localStorage, userId, intentId)
    useAuthStore.setState({ checkoutPending: false })
  })()
    .catch(() => {
      // Keep the original order available for the next authenticated check.
    })
    .finally(() => {
      if (inFlight?.completion === completion) inFlight = null
    })
  inFlight = { intentId, isCurrent, completion }
  return completion
}
