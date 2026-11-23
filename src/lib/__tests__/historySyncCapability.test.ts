import { expect, it } from 'vitest'
import { parseHistorySyncCapability } from '../historySyncCapability'

const capability = {
  version: 1,
  maxPushRecords: 200,
  maxPullRecords: 500,
  maxPayloadBytes: 1048576,
  maxRequestBytes: 4194304,
  maxResponseBytes: 4194304,
  audioSupported: false,
  citationsSupported: false,
}
const snapshot = { version: 1, accountId: 'synthetic-account', capability }

it('copies only supported capability fields for the matching account', () => {
  const parsed = parseHistorySyncCapability(snapshot, 'synthetic-account')
  expect(parsed).toEqual(capability)
  expect(parsed).not.toBe(capability)
})

it('accepts an explicitly disabled capability', () => {
  expect(
    parseHistorySyncCapability({ ...snapshot, capability: null }, 'synthetic-account'),
  ).toBeNull()
})

it.each([
  null,
  { ...snapshot, version: 2 },
  { ...snapshot, accountId: 'another-account' },
  { ...snapshot, capability: undefined },
  { ...snapshot, capability: { ...capability, version: 2 } },
  { ...snapshot, capability: { ...capability, maxPushRecords: 201 } },
  { ...snapshot, capability: { ...capability, maxPullRecords: 501 } },
  { ...snapshot, capability: { ...capability, maxPayloadBytes: 1048577 } },
  { ...snapshot, capability: { ...capability, maxRequestBytes: 4194305 } },
  { ...snapshot, capability: { ...capability, maxResponseBytes: NaN } },
  { ...snapshot, capability: { ...capability, maxPushRecords: 0 } },
  { ...snapshot, capability: { ...capability, maxPullRecords: 1.5 } },
  { ...snapshot, capability: { ...capability, audioSupported: true } },
  { ...snapshot, capability: { ...capability, citationsSupported: true } },
])('rejects unknown or unsafe capability snapshots: %j', (value) => {
  expect(() => parseHistorySyncCapability(value, 'synthetic-account')).toThrow(
    'Invalid history sync capability',
  )
})
