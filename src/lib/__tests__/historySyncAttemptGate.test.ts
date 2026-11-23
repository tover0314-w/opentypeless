import { beforeEach, expect, it } from 'vitest'
import { clearSessionTokenFromMemory } from '../cloud-session'
import { beginHistorySyncAttempt, historySyncAttemptBlocked } from '../historySyncAttemptGate'

beforeEach(() => clearSessionTokenFromMemory())

it('keeps the same session blocked until its own attempt is explicitly released', () => {
  expect(historySyncAttemptBlocked()).toBe(false)
  const release = beginHistorySyncAttempt()
  expect(release).not.toBeNull()
  expect(historySyncAttemptBlocked()).toBe(true)
  expect(beginHistorySyncAttempt()).toBeNull()
  release!()
  expect(historySyncAttemptBlocked()).toBe(false)
})

it('does not let an old release remove a replacement session attempt', () => {
  const oldRelease = beginHistorySyncAttempt()!
  clearSessionTokenFromMemory()
  expect(historySyncAttemptBlocked()).toBe(false)
  const newRelease = beginHistorySyncAttempt()!
  oldRelease()
  expect(historySyncAttemptBlocked()).toBe(true)
  newRelease()
  expect(historySyncAttemptBlocked()).toBe(false)
})
