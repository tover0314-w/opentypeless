import { expect, it, vi } from 'vitest'
import { parseHistorySyncPage, validateHistorySyncPage } from '../historySyncPage'

const session = { accountId: 'synthetic-account', sessionEpoch: 'epoch' }
const change = {
  changeSequence: 1,
  recordId: '11111111-1111-4111-8111-111111111111',
  serverRevision: 1,
  deleted: true,
  payload: null,
}
const page = {
  version: 1,
  accountId: session.accountId,
  fromCursor: 0,
  nextCursor: 1,
  hasMore: false,
  changeSequences: [1],
  records: [change],
}
function validate(response: unknown = page) {
  return validateHistorySyncPage({
    requestSession: session,
    currentSession: session,
    requestedCursor: 0,
    currentCursor: 0,
    response,
  })
}

it('accepts a matching tombstone page', () => {
  expect(validate()).toEqual({ nextCursor: 1, hasMore: false, changeCount: 1 })
})

const payload = {
  rawText: 'Synthetic 😀 text',
  polishedText: '',
  mode: 'dictate',
  answerText: null,
  createdAt: '2026-11-19T10:00:00',
  durationMs: null,
  language: null,
}

it('accepts a valid text page without modifying its content', () => {
  const response = { ...page, records: [{ ...change, deleted: false, payload }] }
  expect(validate(response)?.changeCount).toBe(1)
  expect(response.records[0].payload.rawText).toBe('Synthetic 😀 text')
})

it('returns detached canonical records and session ownership for later local application', () => {
  const response = {
    ...page,
    records: [
      {
        ...change,
        recordId: 'ABCDEFAB-1111-4111-8111-111111111111',
        deleted: false,
        payload: { ...payload },
      },
    ],
  }
  const requestSession = { ...session }
  const parsed = parseHistorySyncPage({
    requestSession,
    currentSession: session,
    requestedCursor: 0,
    currentCursor: 0,
    response,
  })
  expect(parsed).toMatchObject({
    accountId: session.accountId,
    sessionEpoch: session.sessionEpoch,
    fromCursor: 0,
    nextCursor: 1,
    hasMore: false,
    changeSequences: [1],
    records: [
      {
        recordId: 'abcdefab-1111-4111-8111-111111111111',
        deleted: false,
        payload,
      },
    ],
  })
  expect(parsed?.records).not.toBe(response.records)
  expect(parsed?.records[0]).not.toBe(response.records[0])
  expect(parsed?.records[0].payload).not.toBe(response.records[0].payload)
  response.records[0].payload.rawText = 'Changed after validation'
  response.changeSequences[0] = 99
  requestSession.sessionEpoch = 'Changed epoch'
  expect(parsed?.records[0].payload?.rawText).toBe('Synthetic 😀 text')
  expect(parsed?.changeSequences).toEqual([1])
  expect(parsed?.sessionEpoch).toBe('epoch')
})

it('does not return records when the request session has been replaced', () => {
  expect(
    parseHistorySyncPage({
      requestSession: session,
      currentSession: { ...session, sessionEpoch: 'replacement' },
      requestedCursor: 0,
      currentCursor: 0,
      response: page,
    }),
  ).toBeNull()
})

it('counts JSON escaping and page metadata in the aggregate response budget', () => {
  const records = Array.from({ length: 3 }, (_, index) => ({
    ...change,
    changeSequence: index + 1,
    recordId: `11111111-1111-4111-8111-${String(index).padStart(12, '0')}`,
    deleted: false,
    payload: { ...payload, rawText: '\n'.repeat(900_000) },
  }))
  const response = {
    ...page,
    nextCursor: 3,
    changeSequences: [1, 2, 3],
    records,
  }
  expect(new TextEncoder().encode(JSON.stringify(response)).byteLength).toBeGreaterThan(
    4 * 1024 * 1024,
  )
  expect(() => validate(response)).toThrow('Invalid history sync page')
  expect(
    validate({
      ...response,
      nextCursor: 2,
      changeSequences: [1, 2],
      records: records.slice(0, 2),
    })?.changeCount,
  ).toBe(2)
})

it.each([['dictate'], new String('dictate')])(
  'rejects a non-string mode instead of coercing it',
  (mode) => {
    expect(() =>
      validate({
        ...page,
        records: [{ ...change, deleted: false, payload: { ...payload, mode } }],
      }),
    ).toThrow('Invalid history sync page')
  },
)

it('rejects mode objects without executing their string conversion', () => {
  const toString = vi.fn(() => 'dictate')
  expect(() =>
    validate({
      ...page,
      records: [{ ...change, deleted: false, payload: { ...payload, mode: { toString } } }],
    }),
  ).toThrow('Invalid history sync page')
  expect(toString).not.toHaveBeenCalled()
})

it('accepts successive revisions followed by deletion for the same record', () => {
  expect(
    validate({
      ...page,
      nextCursor: 3,
      changeSequences: [1, 2, 3],
      records: [
        { ...change, deleted: false, payload },
        { ...change, changeSequence: 2, serverRevision: 2, deleted: false, payload },
        { ...change, changeSequence: 3, serverRevision: 3 },
      ],
    })?.changeCount,
  ).toBe(3)
})

it.each([
  { ...change, changeSequence: 2, serverRevision: 1 },
  { ...change, changeSequence: 2, serverRevision: 2, deleted: false, payload },
])('rejects non-increasing versions or resurrection after deletion', (second) => {
  expect(() =>
    validate({
      ...page,
      nextCursor: 2,
      changeSequences: [1, 2],
      records: [change, second],
    }),
  ).toThrow('Invalid history sync page')
})

it.each([
  { ...payload, token: 'private' },
  { ...payload, createdAt: '2026-02-30T10:00:00' },
  { ...payload, rawText: '\ud800' },
  { ...payload, rawText: 'x'.repeat(1024 * 1024 + 1) },
  { ...payload, answerText: 'Wrong mode answer' },
])('rejects unsafe text payloads', (invalidPayload) => {
  expect(() =>
    validate({ ...page, records: [{ ...change, deleted: false, payload: invalidPayload }] }),
  ).toThrow('Invalid history sync page')
})

it.each([
  { ...page, records: [] },
  { ...page, records: [change, change] },
  { ...page, records: [{ ...change, changeSequence: 2 }] },
  { ...page, records: [{ ...change, recordId: 'invalid' }] },
  { ...page, records: [{ ...change, serverRevision: 0 }] },
  { ...page, records: [{ ...change, serverRevision: 1.5 }] },
  { ...page, records: [{ ...change, deleted: 'true' }] },
  { ...page, records: [{ ...change, payload: { rawText: 'Private body' } }] },
  { ...page, records: [{ ...change, deleted: false }] },
])('rejects missing or mismatched records: %j', (response) => {
  expect(() => validate(response)).toThrow('Invalid history sync page')
})
