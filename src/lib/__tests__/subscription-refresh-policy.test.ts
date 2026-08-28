import { describe, expect, it } from 'vitest'
import appSource from '../../App.tsx?raw'
import accountPageSource from '../../components/AccountPage/index.tsx?raw'
import {
  shouldRefreshSubscriptionOnAccountOpen,
  shouldRefreshSubscriptionOnFocus,
} from '../subscription-refresh-policy'

describe('subscription refresh lifecycle', () => {
  it('refreshes on focus only while a checkout result is pending', () => {
    expect(shouldRefreshSubscriptionOnFocus(false)).toBe(false)
    expect(shouldRefreshSubscriptionOnFocus(true)).toBe(true)
  })

  it('does not keep Neon awake with a fixed subscription polling interval', () => {
    expect(appSource).not.toMatch(/5\s*\*\s*60\s*\*\s*1000/)
    expect(appSource).not.toMatch(/setInterval\s*\([^)]*refreshSubscription/s)
  })

  it('refreshes stale quota data when the user opens the account page', () => {
    expect(shouldRefreshSubscriptionOnAccountOpen(null, 120_000)).toBe(true)
    expect(shouldRefreshSubscriptionOnAccountOpen(90_001, 120_000)).toBe(false)
    expect(shouldRefreshSubscriptionOnAccountOpen(60_000, 120_000)).toBe(true)
    expect(accountPageSource).toContain('shouldRefreshSubscriptionOnAccountOpen')
  })
})
