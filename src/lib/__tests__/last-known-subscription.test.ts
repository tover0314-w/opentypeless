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
  it('rejects a malformed quota availability flag in cached account data', () => {
    const storage = memoryStorage()
    const verifiedAt = '2026-08-26T10:00:00.000Z'
    saveLastKnownSubscription(
      storage,
      'user-1',
      { ...stripePro, quotaDataAvailable: 'false' } as unknown as SubscriptionStatus,
      verifiedAt,
    )
    expect(
      loadLastKnownSubscription(storage, 'user-1', Date.parse('2026-08-26T11:00:00.000Z')),
    ).toBeNull()
  })

  it.each([false, true])('preserves quota availability set to %s', (available) => {
    const storage = memoryStorage()
    const verifiedAt = '2026-08-26T10:00:00.000Z'
    saveLastKnownSubscription(
      storage,
      'user-1',
      { ...stripePro, quotaDataAvailable: available },
      verifiedAt,
    )
    expect(
      loadLastKnownSubscription(storage, 'user-1', Date.parse('2026-08-26T11:00:00.000Z'))?.snapshot
        .quotaDataAvailable,
    ).toBe(available)
  })

  it.each([null, 4294967295])('preserves native optional format values: %s', (optionalValue) => {
    const storage = memoryStorage()
    const verifiedAt = '2026-08-26T10:00:00.000Z'
    const accountSnapshot = {
      schemaVersion: 1 as const,
      userId: 'user-1',
      generatedAt: verifiedAt,
      managedSttCapabilities: {
        version: 4294967295,
        maxRecordingSeconds: 4294967295,
        maxMultipartBytes: 4294967296,
        formats: [
          {
            mimeType: 'audio/wav',
            maxAudioBytes: 4294967296,
            preferredClientSwitchBytes: optionalValue,
            bitrateBitsPerSecond: optionalValue,
          },
        ],
      },
    }
    saveLastKnownSubscription(storage, 'user-1', { ...stripePro, accountSnapshot }, verifiedAt)
    expect(
      loadLastKnownSubscription(storage, 'user-1', Date.parse('2026-08-26T11:00:00.000Z'))?.snapshot
        .accountSnapshot,
    ).toEqual(accountSnapshot)
  })

  it.each([false, true])(
    'preserves valid billing flags set to %s and the entitlement version',
    (flag) => {
      const storage = memoryStorage()
      const snapshot: SubscriptionStatus = {
        ...stripePro,
        billingProvider: 'stripe',
        canManageBilling: flag,
        canMigrateToStripe: flag,
        cancelAtPeriodEnd: flag,
        entitlementVersion: 2,
      }
      const verifiedAt = '2026-08-26T10:00:00.000Z'
      saveLastKnownSubscription(storage, 'user-1', snapshot, verifiedAt)
      expect(
        loadLastKnownSubscription(storage, 'user-1', Date.parse('2026-08-26T11:00:00.000Z')),
      ).toEqual({ verifiedAt, snapshot })
    },
  )

  it.each([
    { plan: 'free', source: 'free' },
    { plan: 'pro', source: 'creem' },
    { plan: 'pro', source: 'stripe' },
    { plan: 'lifetime_starter', source: 'lifetime' },
    { plan: 'appsumo_tier1', source: 'appsumo' },
    { plan: 'appsumo_tier2', source: 'appsumo' },
    { plan: 'appsumo_tier3', source: 'appsumo' },
  ] as const)('preserves the supported $plan plan from $source', (scenario) => {
    const storage = memoryStorage()
    const snapshot: SubscriptionStatus = {
      ...stripePro,
      ...scenario,
      quotaModel: scenario.source === 'appsumo' ? 'cloud_words' : 'legacy_dual_meter',
      licenseStatus: scenario.source === 'appsumo' ? 'active' : null,
    }
    const verifiedAt = '2026-08-26T10:00:00.000Z'
    saveLastKnownSubscription(storage, 'user-1', snapshot, verifiedAt)
    expect(
      loadLastKnownSubscription(storage, 'user-1', Date.parse('2026-08-26T11:00:00.000Z')),
    ).toEqual({ verifiedAt, snapshot })
  })

  it.each([
    { cloudWordsLimit: -1 },
    { sttSecondsUsed: Number.NaN },
    { displayWordsLimit: Number.POSITIVE_INFINITY },
    { byokUnlimited: 'yes' },
    { displayName: undefined },
    { plan: 'unknown-plan' },
    { source: 'unknown-source' },
    { quotaModel: 'unknown-meter' },
    { licenseStatus: 'unknown-license' },
    { canManageBilling: 'true' },
    { canMigrateToStripe: 1 },
    { cancelAtPeriodEnd: null },
    { billingProvider: 'unknown-provider' },
    { entitlementVersion: -1 },
    { displayWordsResetAt: {} },
    { cloudWordsResetAt: 42 },
  ])('preserves valid cached entitlement when a malformed response arrives: %j', (patch) => {
    const storage = memoryStorage()
    const verifiedAt = '2026-08-26T10:00:00.000Z'
    saveLastKnownSubscription(storage, 'user-1', stripePro, verifiedAt)
    const malformed = { ...stripePro, ...patch } as unknown as SubscriptionStatus
    saveLastKnownSubscription(storage, 'user-1', malformed, verifiedAt)
    expect(
      loadLastKnownSubscription(storage, 'user-1', Date.parse('2026-08-26T11:00:00.000Z')),
    ).toEqual({ verifiedAt, snapshot: stripePro })
  })

  it('filters unknown fields from legacy cache reads without rewriting stored data', () => {
    const storage = memoryStorage()
    const raw = JSON.stringify({
      version: 1,
      userId: 'user-1',
      verifiedAt: '2026-08-26T10:00:00.000Z',
      snapshot: {
        ...stripePro,
        accessToken: 'synthetic-private-token',
        accountSnapshot: {
          schemaVersion: 1,
          userId: 'user-1',
          generatedAt: '2026-08-26T10:00:00.000Z',
          managedSttCapabilities: null,
          debugToken: 'synthetic-private-token',
        },
      },
    })
    storage.setItem('opentypeless.desktop-subscription.v1:user-1', raw)
    const restored = loadLastKnownSubscription(
      storage,
      'user-1',
      Date.parse('2026-08-26T11:00:00.000Z'),
    )
    expect(restored?.snapshot.plan).toBe('pro')
    expect(JSON.stringify(restored)).not.toContain('synthetic-private-token')
    expect(storage.getItem('opentypeless.desktop-subscription.v1:user-1')).toBe(raw)
  })

  it('preserves managed recording limits while excluding nested unknown fields', () => {
    const storage = memoryStorage()
    const format = {
      mimeType: 'audio/wav',
      maxAudioBytes: 25000000,
      preferredClientSwitchBytes: 1000000,
      bitrateBitsPerSecond: 128000,
      debugToken: 'synthetic-private-token',
    }
    const capability = {
      version: 1,
      maxRecordingSeconds: 600,
      maxMultipartBytes: 25000000,
      formats: [format],
      accessToken: 'synthetic-private-token',
    }
    const account = {
      schemaVersion: 1 as const,
      userId: 'user-1',
      generatedAt: '2026-08-26T10:00:00.000Z',
      managedSttCapabilities: capability,
    }
    saveLastKnownSubscription(
      storage,
      'user-1',
      { ...stripePro, accountSnapshot: account },
      account.generatedAt,
    )
    const raw = storage.getItem('opentypeless.desktop-subscription.v1:user-1')
    expect(raw).not.toContain('synthetic-private-token')
    const restored = loadLastKnownSubscription(
      storage,
      'user-1',
      Date.parse('2026-08-26T11:00:00.000Z'),
    )
    expect(restored?.snapshot.accountSnapshot?.managedSttCapabilities).toEqual({
      version: 1,
      maxRecordingSeconds: 600,
      maxMultipartBytes: 25000000,
      formats: [
        {
          mimeType: 'audio/wav',
          maxAudioBytes: 25000000,
          preferredClientSwitchBytes: 1000000,
          bitrateBitsPerSecond: 128000,
        },
      ],
    })
    expect(format.debugToken).toBe('synthetic-private-token')
    expect(capability.accessToken).toBe('synthetic-private-token')
  })

  it('does not persist unknown fields attached to a subscription response', () => {
    const storage = memoryStorage()
    const snapshot = {
      ...stripePro,
      accessToken: 'synthetic-private-token',
      accountSnapshot: {
        schemaVersion: 1 as const,
        userId: 'user-1',
        managedSttCapabilities: null,
        generatedAt: '2026-08-26T10:00:00.000Z',
        debugToken: 'synthetic-private-token',
      },
    }
    saveLastKnownSubscription(storage, 'user-1', snapshot, '2026-08-26T10:00:00.000Z')
    expect(storage.getItem('opentypeless.desktop-subscription.v1:user-1')).not.toContain(
      'synthetic-private-token',
    )
    expect(snapshot.accessToken).toBe('synthetic-private-token')
    expect(
      loadLastKnownSubscription(storage, 'user-1', Date.parse('2026-08-26T11:00:00.000Z')),
    )?.toMatchObject({ snapshot: { plan: 'pro' } })
  })

  it('does not overwrite valid cached entitlement with another account snapshot', () => {
    const storage = memoryStorage()
    const verifiedAt = '2026-08-26T10:00:00.000Z'
    saveLastKnownSubscription(storage, 'user-1', stripePro, verifiedAt)
    saveLastKnownSubscription(
      storage,
      'user-1',
      {
        ...stripePro,
        accountSnapshot: {
          schemaVersion: 1,
          userId: 'user-2',
          managedSttCapabilities: null,
          generatedAt: verifiedAt,
        },
      },
      verifiedAt,
    )
    expect(
      loadLastKnownSubscription(storage, 'user-1', Date.parse('2026-08-26T11:00:00.000Z')),
    ).toEqual({ verifiedAt, snapshot: stripePro })
  })

  it('rejects a cache whose embedded account snapshot belongs to a different user', () => {
    const storage = memoryStorage()
    const snapshot: SubscriptionStatus = {
      ...stripePro,
      accountSnapshot: {
        schemaVersion: 1,
        userId: 'user-2',
        managedSttCapabilities: null,
        generatedAt: '2026-08-26T10:00:00.000Z',
      },
    }
    storage.setItem(
      'opentypeless.desktop-subscription.v1:user-1',
      JSON.stringify({
        version: 1,
        userId: 'user-1',
        verifiedAt: '2026-08-26T10:00:00.000Z',
        snapshot,
      }),
    )
    expect(
      loadLastKnownSubscription(storage, 'user-1', Date.parse('2026-08-26T11:00:00.000Z')),
    ).toBeNull()
  })

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
