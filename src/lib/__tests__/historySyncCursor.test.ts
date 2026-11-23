import { describe, expect, it } from 'vitest'
import { validateHistorySyncPageCursor } from '../historySyncCursor'

const page = {
  version: 1,
  accountId: 'synthetic-account',
  fromCursor: 10,
  nextCursor: 14,
  hasMore: true,
  changeSequences: [12, 14],
}
const session = { accountId: 'synthetic-account', sessionEpoch: 'synthetic-epoch' }

function validate(response: unknown = page, currentCursor = 10) {
  return validateHistorySyncPageCursor({
    requestSession: session,
    currentSession: session,
    requestedCursor: 10,
    currentCursor,
    response,
  })
}

describe('offline history sync page cursor validation', () => {
  it.each([
    { ...session, accountId: 'another-account' },
    { ...session, sessionEpoch: 'replacement-epoch' },
  ])('ignores old pages after session ownership changes: %j', (currentSession) => {
    expect(
      validateHistorySyncPageCursor({
        requestSession: session,
        currentSession,
        requestedCursor: 10,
        currentCursor: 10,
        response: page,
      }),
    ).toBeNull()
  })

  it.each(['', ' private ', 'private\nvalue', 'a'.repeat(201)])(
    'rejects invalid session epochs: %j',
    (sessionEpoch) => {
      expect(() =>
        validateHistorySyncPageCursor({
          requestSession: { ...session, sessionEpoch },
          currentSession: session,
          requestedCursor: 10,
          currentCursor: 10,
          response: page,
        }),
      ).toThrow('Invalid history sync cursor')
    },
  )

  it('accepts ordered server sequences without assuming they are consecutive', () => {
    expect(validate()).toEqual({ nextCursor: 14, hasMore: true, changeCount: 2 })
  })

  it('allows an exhausted empty page without advancing the cursor', () => {
    expect(validate({ ...page, nextCursor: 10, hasMore: false, changeSequences: [] })).toEqual({
      nextCursor: 10,
      hasMore: false,
      changeCount: 0,
    })
  })

  it('does not apply a page if the local cursor changed while pulling', () => {
    expect(validate(page, 12)).toBeNull()
  })

  it.each([
    null,
    { ...page, version: 2 },
    { ...page, accountId: 'another-account' },
    { ...page, fromCursor: 9 },
    { ...page, nextCursor: 9 },
    { ...page, nextCursor: NaN },
    { ...page, nextCursor: Infinity },
    { ...page, nextCursor: Number.MAX_SAFE_INTEGER + 1 },
    { ...page, nextCursor: 14.5 },
    { ...page, nextCursor: '14' },
    { ...page, nextCursor: 15 },
    { ...page, hasMore: 'true' },
    { ...page, changeSequences: [14, 12] },
    { ...page, changeSequences: [12, 12, 14] },
    { ...page, changeSequences: [10, 14] },
    { ...page, changeSequences: [12, 15] },
    { ...page, changeSequences: ['12', 14] },
    { ...page, changeSequences: [12, NaN] },
    { ...page, changeSequences: [] },
    { ...page, changeSequences: [], nextCursor: 10 },
    { ...page, changeSequences: [], nextCursor: 14, hasMore: false },
    { ...page, changeSequences: Array.from({ length: 501 }, (_, index) => index + 11) },
  ])('rejects malformed, stalled or skipping page metadata: %j', (response) => {
    expect(() => validate(response)).toThrow('Invalid history sync cursor')
  })
})
