import type { CheckoutProduct } from './constants'

const STORAGE_KEY = 'opentypeless.desktop-checkout-intent.v1'
const MAX_FUTURE_CLOCK_SKEW_MS = 5 * 60 * 1000
export const DESKTOP_CHECKOUT_INTENT_TTL_MS = 30 * 60 * 1000

export interface PendingDesktopCheckout {
  product: CheckoutProduct
  createdAt: number
  expiresAt: number
}

function isCheckoutProduct(value: unknown): value is CheckoutProduct {
  return value === 'pro_monthly' || value === 'lifetime_starter'
}

function discardPendingDesktopCheckout(storage: Pick<Storage, 'removeItem'>) {
  try {
    storage.removeItem(STORAGE_KEY)
  } catch {
    // Storage may be unavailable in hardened webviews. The server remains authoritative.
  }
}

export function savePendingDesktopCheckout(
  storage: Pick<Storage, 'setItem'>,
  product: CheckoutProduct,
  now = Date.now(),
): PendingDesktopCheckout {
  const pending = {
    product,
    createdAt: now,
    expiresAt: now + DESKTOP_CHECKOUT_INTENT_TTL_MS,
  }
  storage.setItem(STORAGE_KEY, JSON.stringify(pending))
  return pending
}

export function readPendingDesktopCheckout(
  storage: Pick<Storage, 'getItem' | 'removeItem'>,
  now = Date.now(),
): PendingDesktopCheckout | null {
  try {
    const raw = storage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<PendingDesktopCheckout>
    const invalid =
      !isCheckoutProduct(parsed.product) ||
      typeof parsed.createdAt !== 'number' ||
      !Number.isFinite(parsed.createdAt) ||
      typeof parsed.expiresAt !== 'number' ||
      !Number.isFinite(parsed.expiresAt) ||
      parsed.createdAt > now + MAX_FUTURE_CLOCK_SKEW_MS ||
      parsed.expiresAt <= now ||
      parsed.expiresAt - parsed.createdAt !== DESKTOP_CHECKOUT_INTENT_TTL_MS

    if (invalid) {
      discardPendingDesktopCheckout(storage)
      return null
    }
    return parsed as PendingDesktopCheckout
  } catch {
    discardPendingDesktopCheckout(storage)
    return null
  }
}

export function clearPendingDesktopCheckout(storage: Pick<Storage, 'removeItem'>) {
  discardPendingDesktopCheckout(storage)
}
