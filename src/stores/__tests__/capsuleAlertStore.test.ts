import { beforeEach, describe, expect, it } from 'vitest'
import {
  claimPressToFinishHint,
  claimSelectedTextTip,
  PRESS_TO_FINISH_COOLDOWN_MS,
} from '../capsuleAlertStore'

describe('capsule hint throttling', () => {
  beforeEach(() => localStorage.clear())

  it('shows the press-to-finish nudge at most once per 48 hours', () => {
    expect(claimPressToFinishHint(1_000)).toBe(true)
    expect(claimPressToFinishHint(1_000 + PRESS_TO_FINISH_COOLDOWN_MS - 1)).toBe(false)
    expect(claimPressToFinishHint(1_000 + PRESS_TO_FINISH_COOLDOWN_MS)).toBe(true)
  })

  it('shows the selected-text tip only once', () => {
    expect(claimSelectedTextTip(1_000)).toBe(true)
    expect(claimSelectedTextTip(1_000 + 365 * 24 * 60 * 60 * 1000)).toBe(false)
  })
})
