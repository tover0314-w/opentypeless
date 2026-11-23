import { expect, it } from 'vitest'
import { nextHistorySyncDelay } from '../historySyncSchedule'

it('drains bounded pending work promptly and polls idle authorization conservatively', () => {
  expect(nextHistorySyncDelay({ pushed: 1, pulled: 2, pending: 3, hasMore: false }, 0)).toBe(7000)
  expect(nextHistorySyncDelay({ pushed: 0, pulled: 0, pending: 0, hasMore: true }, 0)).toBe(7000)
  expect(nextHistorySyncDelay({ pushed: 0, pulled: 0, pending: 0, hasMore: false }, 0)).toBe(30000)
})

it('backs off transport failures without tight retry loops', () => {
  expect(nextHistorySyncDelay(null, 1)).toBe(30000)
  expect(nextHistorySyncDelay(null, 2)).toBe(60000)
  expect(nextHistorySyncDelay(null, 100)).toBe(300000)
})

it('does not rapidly retry pending metadata when the previous attempt made no progress', () => {
  expect(nextHistorySyncDelay({ pushed: 0, pulled: 0, pending: 3, hasMore: false }, 0)).toBe(30000)
})
