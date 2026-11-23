import { describe, expect, it } from 'vitest'
import { decideHistorySyncAcknowledgements } from '../historySyncAcknowledgements'

const sent = {
  recordId: 'record-one',
  mutationId: 'mutation-one',
  clientRevision: 3,
  baseServerRevision: 2,
}
const session = { accountId: 'synthetic-account', sessionEpoch: 'epoch-one' }
const acknowledgement = {
  recordId: sent.recordId,
  mutationId: sent.mutationId,
  clientRevision: 3,
  result: 'accepted',
  serverRevision: 4,
}
const response = {
  version: 1,
  accountId: session.accountId,
  acknowledgements: [acknowledgement],
}

function decide(value: unknown = response, current = [{ ...sent }], active = session) {
  return decideHistorySyncAcknowledgements({
    requestSession: session,
    currentSession: active,
    sent: [sent],
    current,
    response: value,
  })
}

describe('offline history sync acknowledgement decisions', () => {
  it('maps reordered partial acknowledgements to only their matching current mutations', () => {
    const mutations = Array.from({ length: 5 }, (_, index) => ({
      ...sent,
      recordId: `record-${index}`,
      mutationId: `mutation-${index}`,
    }))
    const current = mutations.map((mutation, index) =>
      index === 3 ? { ...mutation, clientRevision: 4 } : mutation,
    )
    const acknowledgements = [3, 2, 0].map((index) => ({
      ...acknowledgement,
      recordId: mutations[index].recordId,
      mutationId: mutations[index].mutationId,
      result: index === 2 ? 'conflict' : 'accepted',
    }))
    const decisions = decideHistorySyncAcknowledgements({
      requestSession: session,
      currentSession: session,
      sent: mutations,
      current,
      response: { ...response, acknowledgements },
    })
    expect(decisions.map((decision) => decision.recordId)).toEqual(
      mutations.map((mutation) => mutation.recordId),
    )
    expect(decisions.map((decision) => decision.status)).toEqual([
      'accepted',
      'pending',
      'conflict',
      'stale',
      'pending',
    ])
    expect(decisions[3]).not.toHaveProperty('serverRevision')
    expect(current[3].clientRevision).toBe(4)
  })

  it('rejects an invalid trailing acknowledgement instead of returning partial success', () => {
    const mutations = [sent, { ...sent, recordId: 'record-two', mutationId: 'mutation-two' }]
    expect(() =>
      decideHistorySyncAcknowledgements({
        requestSession: session,
        currentSession: session,
        sent: mutations,
        current: mutations,
        response: {
          ...response,
          acknowledgements: [
            acknowledgement,
            {
              ...acknowledgement,
              recordId: 'record-two',
              mutationId: 'mutation-two',
              serverRevision: -1,
            },
          ],
        },
      }),
    ).toThrow('Invalid history sync acknowledgement')
    expect(mutations[0].baseServerRevision).toBe(2)
  })

  it.each([
    { ...sent, recordId: '' },
    { ...sent, mutationId: ' private ' },
    { ...sent, mutationId: 'private\nvalue' },
    { ...sent, mutationId: 'a'.repeat(201) },
    { ...sent, clientRevision: 0 },
    { ...sent, clientRevision: Number.MAX_SAFE_INTEGER + 1 },
    { ...sent, baseServerRevision: -1 },
  ])('rejects invalid local mutation metadata: %j', (mutation) => {
    expect(() =>
      decideHistorySyncAcknowledgements({
        requestSession: session,
        currentSession: session,
        sent: [mutation],
        current: [mutation],
        response,
      }),
    ).toThrow('Invalid history sync acknowledgement')
  })

  it('rejects duplicate local mutations and oversized batches', () => {
    for (const mutations of [
      [sent, sent],
      [sent, { ...sent, mutationId: 'second-mutation' }],
      Array.from({ length: 201 }, (_, index) => ({
        ...sent,
        recordId: `record-${index}`,
        mutationId: `mutation-${index}`,
      })),
    ]) {
      expect(() =>
        decideHistorySyncAcknowledgements({
          requestSession: session,
          currentSession: session,
          sent: mutations,
          current: mutations,
          response,
        }),
      ).toThrow('Invalid history sync acknowledgement')
    }
  })

  it('does not return untrusted extra acknowledgement data', () => {
    const result = decide({
      ...response,
      acknowledgements: [{ ...acknowledgement, text: 'private response body' }],
    })
    expect(JSON.stringify(result)).not.toContain('private response body')
    expect(result[0].status).toBe('accepted')
  })

  it('accepts only a matching current local revision and returns bounded metadata', () => {
    expect(decide()).toEqual([
      {
        recordId: sent.recordId,
        mutationId: sent.mutationId,
        status: 'accepted',
        serverRevision: 4,
      },
    ])
  })

  it('keeps missing acknowledgements pending', () => {
    expect(decide({ ...response, acknowledgements: [] })[0].status).toBe('pending')
  })

  it.each([
    { ...sent, clientRevision: 4 },
    { ...sent, mutationId: 'new-mutation' },
    { ...sent, baseServerRevision: 4 },
  ])('does not accept a late acknowledgement after local state changes: %j', (current) => {
    expect(decide(response, [current])[0].status).toBe('stale')
  })

  it('does not revive a locally removed mutation', () => {
    expect(decide(response, [])[0].status).toBe('stale')
  })

  it.each([
    { ...session, accountId: 'another-account' },
    { ...session, sessionEpoch: 'new-epoch' },
  ])('ignores the whole response after session ownership changes: %j', (active) => {
    expect(decide(response, [{ ...sent }], active)).toEqual([])
  })

  it.each(['conflict', 'rejected'])('does not treat %s as accepted', (result) => {
    expect(
      decide({ ...response, acknowledgements: [{ ...acknowledgement, result }] })[0].status,
    ).toBe(result)
  })

  it.each([
    null,
    { ...response, version: 2 },
    { ...response, accountId: 'another-account' },
    { ...response, acknowledgements: [acknowledgement, acknowledgement] },
    { ...response, acknowledgements: [{ ...acknowledgement, mutationId: 'unknown' }] },
    { ...response, acknowledgements: [{ ...acknowledgement, recordId: 'another-record' }] },
    { ...response, acknowledgements: [{ ...acknowledgement, clientRevision: 4 }] },
    { ...response, acknowledgements: [{ ...acknowledgement, serverRevision: 2 }] },
    { ...response, acknowledgements: [{ ...acknowledgement, serverRevision: NaN }] },
    { ...response, acknowledgements: [{ ...acknowledgement, serverRevision: 1.5 }] },
    { ...response, acknowledgements: [{ ...acknowledgement, result: 'synced' }] },
  ])(
    'rejects malformed or ambiguous acknowledgements without private error details: %j',
    (value) => {
      expect(() => decide(value)).toThrow('Invalid history sync acknowledgement')
    },
  )
})
