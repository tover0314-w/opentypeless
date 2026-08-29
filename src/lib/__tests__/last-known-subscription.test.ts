import { describe, expect, it } from 'vitest'
import type { SubscriptionStatus } from '../api'
import {
  clearLastKnownSubscription,
  loadLastKnownSubscription,
  saveLastKnownSubscription,
} from '../last-known-subscription'

function memoryStorage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
  }
}

const stripePro: SubscriptionStatus = {
  plan: 'pro',
  source: 'stripe',
  displayName: 'Pro',
  subscriptionEnd: '2026-09-26T00:00:00.000Z',
  subscriptionStatus: 'active',
  licenseStatus: null,
  quotaModel: 'legacy_dual_meter',
  displayWordsUsedEstimate: 1200,
  displayWordsLimit: 100000,
  displayWordsResetAt: '2026-09-01T00:00:00.000Z',
  sttSecondsUsed: 120,
  sttSecondsLimit: 36000,
  llmTokensUsed: 300,
  llmTokensLimit: 5000000,
  cloudWordsUsed: 0,
  cloudWordsLimit: 0,
  cloudWordsResetAt: null,
  byokUnlimited: true,
}

describe('desktop last-known subscription cache', () => {
  it('restores a valid snapshot only for the same user', () => {
    const storage = memoryStorage()
    saveLastKnownSubscription(storage, 'user-1', stripePro, '2026-08-26T10:00:00.000Z')

    const now = Date.parse('2026-08-26T11:00:00.000Z')
    expect(loadLastKnownSubscription(storage, 'user-1', now)).toEqual({
      verifiedAt: '2026-08-26T10:00:00.000Z',
      snapshot: stripePro,
    })
    expect(loadLastKnownSubscription(storage, 'user-2', now)).toBeNull()
  })

  it('rejects malformed cache entries and clears valid ones', () => {
    const storage = memoryStorage()
    storage.setItem(
      'opentypeless.desktop-subscription.v1:user-1',
      JSON.stringify({ version: 1, userId: 'user-1', verifiedAt: 'invalid', snapshot: stripePro }),
    )
    expect(
      loadLastKnownSubscription(storage, 'user-1', Date.parse('2026-08-26T11:00:00.000Z')),
    ).toBeNull()

    saveLastKnownSubscription(storage, 'user-1', stripePro, '2026-08-26T10:00:00.000Z')
    clearLastKnownSubscription(storage, 'user-1')
    expect(
      loadLastKnownSubscription(storage, 'user-1', Date.parse('2026-08-26T11:00:00.000Z')),
    ).toBeNull()
  })

  it('expires cached entitlement evidence after 24 hours', () => {
    const storage = memoryStorage()
    saveLastKnownSubscription(storage, 'user-1', stripePro, '2026-08-25T09:59:59.000Z')
    expect(
      loadLastKnownSubscription(storage, 'user-1', Date.parse('2026-08-26T10:00:00.000Z')),
    ).toBeNull()
  })
})
