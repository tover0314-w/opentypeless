import { describe, expect, it } from 'vitest'
import {
  DESKTOP_CHECKOUT_INTENT_TTL_MS,
  clearPendingDesktopCheckout,
  readPendingDesktopCheckout,
  savePendingDesktopCheckout,
} from '../desktop-checkout-intent'

function memoryStorage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  }
}

describe('desktop checkout intent', () => {
  it('keeps the selected product long enough to finish desktop sign-in', () => {
    const storage = memoryStorage()
    const now = Date.parse('2026-08-26T12:00:00.000Z')

    savePendingDesktopCheckout(storage, 'lifetime_starter', now)

    expect(readPendingDesktopCheckout(storage, now + 60_000)).toEqual({
      product: 'lifetime_starter',
      createdAt: now,
      expiresAt: now + DESKTOP_CHECKOUT_INTENT_TTL_MS,
    })
  })

  it('rejects expired, malformed, and unsupported pending purchases', () => {
    const storage = memoryStorage()
    const now = Date.parse('2026-08-26T12:00:00.000Z')
    savePendingDesktopCheckout(storage, 'pro_monthly', now)
    expect(readPendingDesktopCheckout(storage, now + DESKTOP_CHECKOUT_INTENT_TTL_MS)).toBeNull()

    storage.setItem(
      'opentypeless.desktop-checkout-intent.v1',
      JSON.stringify({ product: 'custom_price', createdAt: now, expiresAt: now + 1_000 }),
    )
    expect(readPendingDesktopCheckout(storage, now)).toBeNull()

    storage.setItem('opentypeless.desktop-checkout-intent.v1', '{broken')
    expect(readPendingDesktopCheckout(storage, now)).toBeNull()
  })

  it('clears a pending purchase explicitly', () => {
    const storage = memoryStorage()
    savePendingDesktopCheckout(storage, 'pro_monthly', 100)
    clearPendingDesktopCheckout(storage)
    expect(readPendingDesktopCheckout(storage, 100)).toBeNull()
  })
})
