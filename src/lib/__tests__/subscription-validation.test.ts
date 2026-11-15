import { describe, expect, it } from 'vitest'
import { isSubscriptionStatus } from '../subscription-validation'

describe('subscription response validation', () => {
  it.each([
    { version: 1, maxRecordingSeconds: -1, maxMultipartBytes: 100, formats: [] },
    { version: 1, maxRecordingSeconds: 60, maxMultipartBytes: 100, formats: 'wav' },
    { version: 4294967296, maxRecordingSeconds: 60, maxMultipartBytes: 100, formats: [] },
    { version: 1, maxRecordingSeconds: 4294967296, maxMultipartBytes: 100, formats: [] },
    {
      version: 1,
      maxRecordingSeconds: 60,
      maxMultipartBytes: 100,
      formats: [{ mimeType: 'audio/wav', maxAudioBytes: 100, bitrateBitsPerSecond: 4294967296 }],
    },
    {
      version: 1,
      maxRecordingSeconds: 60,
      maxMultipartBytes: 100,
      formats: [{ mimeType: 'audio/wav', maxAudioBytes: -1 }],
    },
  ])(
    'rejects malformed nested recording capabilities before accepting entitlement: %j',
    (capability) => {
      const snapshot = {
        plan: 'pro',
        source: 'stripe',
        displayName: 'Pro',
        subscriptionEnd: null,
        subscriptionStatus: 'active',
        quotaModel: 'legacy_dual_meter',
        displayWordsUsedEstimate: 0,
        displayWordsLimit: 100000,
        displayWordsResetAt: null,
        sttSecondsUsed: 0,
        sttSecondsLimit: 36000,
        llmTokensUsed: 0,
        llmTokensLimit: 5000000,
        cloudWordsUsed: 0,
        cloudWordsLimit: 0,
        cloudWordsResetAt: null,
        byokUnlimited: true,
        accountSnapshot: {
          schemaVersion: 1,
          userId: 'user-1',
          generatedAt: '2026-08-26T10:00:00.000Z',
          managedSttCapabilities: capability,
        },
      }
      expect(isSubscriptionStatus(snapshot)).toBe(false)
      expect(
        isSubscriptionStatus({
          ...snapshot,
          accountSnapshot: {
            ...snapshot.accountSnapshot,
            managedSttCapabilities: {
              version: 1,
              maxRecordingSeconds: 600,
              maxMultipartBytes: 25000000,
              formats: [
                {
                  mimeType: 'audio/wav',
                  maxAudioBytes: 25000000,
                  preferredClientSwitchBytes: null,
                  bitrateBitsPerSecond: null,
                },
              ],
            },
          },
        }),
      ).toBe(true)
      expect(
        isSubscriptionStatus({
          ...snapshot,
          accountSnapshot: { ...snapshot.accountSnapshot, managedSttCapabilities: null },
        }),
      ).toBe(true)
    },
  )
})
