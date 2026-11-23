import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAppStore } from '../../../stores/appStore'
import { useAuthStore } from '../../../stores/authStore'
import { UsageCard } from '../UsageCard'

const locale = vi.hoisted(() => ({ language: 'en' }))

vi.mock('react-i18next', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-i18next')>()),
  useTranslation: () => ({
    i18n: { language: locale.language },
    t: (key: string, values?: Record<string, string>) =>
      key === 'shell.wordsUsage' ? `${values?.used} / ${values?.limit} words` : key,
  }),
}))

afterEach(() => {
  cleanup()
  locale.language = 'en'
})

describe('sidebar usage and upgrade', () => {
  it('shows one estimated word allowance without exposing raw legacy meters', () => {
    const previous = useAuthStore.getState()
    try {
      useAuthStore.setState({
        user: { id: 'free-meters', email: 'free@example.test', name: 'Free', emailVerified: true },
        plan: 'free',
        quotaModel: 'legacy_dual_meter',
        quotaDataAvailable: true,
        subscriptionRefreshState: 'fresh',
        displayWordsLimit: 0,
        cloudWordsLimit: 0,
        cloudWordsUsed: 10000,
        sttSecondsUsed: 300,
        sttSecondsLimit: 900,
        llmTokensUsed: 25000,
        llmTokensLimit: 100000,
      })
      render(<UsageCard onUpgrade={vi.fn()} />)
      expect(screen.getByText('onboarding.account.freePlanQuota')).toBeInTheDocument()
      expect(screen.queryByText('shell.estimatedWordsRemaining')).toBeNull()
      expect(screen.queryByText('0 / 0 words')).toBeNull()
      const meters = screen.getAllByRole('progressbar')
      expect(meters).toHaveLength(1)
      expect(Number(meters[0].getAttribute('aria-valuenow'))).toBe(75)
      expect(screen.getByText('1,500 / 2,000 words')).toBeInTheDocument()
      expect(screen.queryByText('upgrade.stt')).toBeNull()
      expect(screen.queryByText('upgrade.llm')).toBeNull()
      act(() =>
        useAuthStore.setState({
          sttSecondsUsed: 1200,
          llmTokensUsed: 100000,
        }),
      )
      for (const meter of screen.getAllByRole('progressbar')) {
        expect(meter).toHaveAttribute('aria-valuenow', '0')
        expect(meter.firstElementChild).toHaveStyle({ width: '0%' })
      }
      act(() => useAuthStore.setState({ quotaDataAvailable: false }))
      expect(screen.queryByRole('progressbar')).toBeNull()
      expect(screen.getByText('—')).toBeInTheDocument()
      act(() => useAuthStore.setState({ quotaDataAvailable: true }))
      expect(screen.getAllByRole('progressbar')).toHaveLength(1)
      act(() => useAuthStore.setState({ user: null }))
      expect(screen.queryByRole('progressbar')).toBeNull()
    } finally {
      useAuthStore.setState(previous)
    }
  })

  it('hides numeric quota when subscription retrieval is unavailable', () => {
    const auth = useAuthStore.getState()
    try {
      useAuthStore.setState({
        user: {
          id: 'unavailable-quota',
          email: 'unavailable@example.test',
          name: 'Unavailable',
          emailVerified: true,
        },
        plan: 'free',
        quotaModel: 'cloud_words',
        quotaDataAvailable: true,
        cloudWordsUsed: 250,
        cloudWordsLimit: 2000,
        subscriptionRefreshState: 'unavailable',
      })
      render(<UsageCard onUpgrade={vi.fn()} />)
      expect(screen.getByText('—')).toBeInTheDocument()
      expect(screen.queryByRole('progressbar')).toBeNull()
      expect(screen.queryByText('1,750 / 2,000 words')).toBeNull()
    } finally {
      useAuthStore.setState(auth)
    }
  })

  it('does not turn a successful response with missing quota into a zero allowance', () => {
    const auth = useAuthStore.getState()
    try {
      useAuthStore.setState({
        user: {
          id: 'missing-quota-test',
          email: 'missing@example.test',
          name: 'Missing',
          emailVerified: true,
        },
        plan: 'free',
        quotaModel: 'cloud_words',
        quotaDataAvailable: false,
        cloudWordsUsed: 0,
        cloudWordsLimit: 0,
        subscriptionRefreshState: 'fresh',
      })
      render(<UsageCard onUpgrade={vi.fn()} />)
      expect(screen.getByText('—')).toBeInTheDocument()
      expect(screen.queryByText('0 / 0 words')).toBeNull()
      expect(screen.queryByRole('progressbar')).toBeNull()
      act(() => useAuthStore.setState({ quotaDataAvailable: true }))
      expect(screen.getByText('0 / 0 words')).toBeInTheDocument()
      expect(screen.queryByText('—')).toBeNull()
    } finally {
      useAuthStore.setState(auth)
    }
  })

  it('shows a confirmed zero allowance instead of an unavailable quota placeholder', () => {
    const auth = useAuthStore.getState()
    try {
      useAuthStore.setState({
        user: {
          id: 'zero-quota-test',
          email: 'zero@example.test',
          name: 'Zero',
          emailVerified: true,
        },
        plan: 'free',
        quotaModel: 'cloud_words',
        cloudWordsUsed: 0,
        cloudWordsLimit: 0,
        subscriptionRefreshState: 'fresh',
      })
      render(<UsageCard onUpgrade={vi.fn()} />)
      expect(screen.getByText('0 / 0 words')).toBeInTheDocument()
      expect(screen.queryByText('—')).toBeNull()
      expect(screen.queryByRole('progressbar')).toBeNull()
    } finally {
      useAuthStore.setState(auth)
    }
  })

  it('keeps a quota placeholder visible before account usage is available', () => {
    const auth = useAuthStore.getState()
    try {
      useAuthStore.setState({
        user: null,
        plan: 'free',
        cloudWordsLimit: 0,
        displayWordsLimit: 0,
        subscriptionRefreshState: 'unknown',
      })
      render(<UsageCard onUpgrade={vi.fn()} />)
      expect(screen.getByText('onboarding.account.freePlanQuota')).toBeInTheDocument()
      expect(screen.getByText('—')).toBeInTheDocument()
      expect(screen.queryByRole('progressbar')).toBeNull()
    } finally {
      useAuthStore.setState(auth)
    }
  })

  it('does not present cached paid entitlement when there is no signed-in user', () => {
    const auth = useAuthStore.getState()
    try {
      useAuthStore.setState({
        user: null,
        plan: 'lifetime_starter',
        source: 'lifetime',
        cloudWordsUsed: 250,
        cloudWordsLimit: 2000,
        subscriptionRefreshState: 'fresh',
      })
      render(<UsageCard onUpgrade={vi.fn()} />)
      expect(screen.queryByRole('progressbar')).toBeNull()
      expect(screen.getByRole('button', { name: 'nav.upgrade' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'shell.getMoreWords' })).toBeNull()
    } finally {
      useAuthStore.setState(auth)
    }
  })

  it('formats usage in the selected interface language and updates when it changes', () => {
    const auth = useAuthStore.getState()
    try {
      useAuthStore.setState({
        user: {
          id: 'locale-test',
          email: 'locale@example.test',
          name: 'Locale',
          emailVerified: true,
        },
        quotaModel: 'cloud_words',
        cloudWordsUsed: 1250,
        cloudWordsLimit: 2000,
      })
      locale.language = 'de'
      const view = render(<UsageCard onUpgrade={vi.fn()} />)
      expect(screen.getByText('750 / 2.000 words')).toBeInTheDocument()
      expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuetext', '750 / 2.000 words')
      locale.language = 'en'
      view.rerender(<UsageCard onUpgrade={vi.fn()} />)
      expect(screen.getByText('750 / 2,000 words')).toBeInTheDocument()
    } finally {
      useAuthStore.setState(auth)
    }
  })

  it('keeps upgrade and real cloud quota visible when using own providers', () => {
    const config = useAppStore.getState().config
    const auth = useAuthStore.getState()
    const upgrade = vi.fn()
    const models = vi.fn()
    try {
      useAppStore.setState({
        config: { ...config, stt_provider: 'deepgram', llm_provider: 'openai' },
      })
      useAuthStore.setState({
        user: {
          id: 'sidebar-test',
          email: 'sidebar@example.test',
          name: 'Sidebar',
          emailVerified: true,
        },
        plan: 'free',
        subscriptionStatus: null,
        cloudWordsUsed: 250,
        cloudWordsLimit: 2000,
        quotaModel: 'cloud_words',
      })
      render(<UsageCard onUpgrade={upgrade} />)
      expect(screen.getByText('1,750 / 2,000 words')).toBeInTheDocument()
      expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '87.5')
      expect(screen.getByText('shell.planFree')).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'nav.upgrade' }))
      expect(upgrade).toHaveBeenCalledOnce()
      expect(screen.queryByRole('button', { name: 'shell.manageModels' })).toBeNull()
      expect(screen.queryByText('shell.byokTitle')).toBeNull()
      expect(models).not.toHaveBeenCalled()
    } finally {
      useAppStore.setState({ config })
      useAuthStore.setState(auth)
    }
  })

  it('shows zero remaining at the limit and hides cached quota after sign-out', () => {
    const auth = useAuthStore.getState()
    try {
      useAuthStore.setState({
        user: {
          id: 'quota-test',
          email: 'quota@example.test',
          name: 'Quota',
          emailVerified: true,
        },
        plan: 'free',
        subscriptionStatus: null,
        quotaModel: 'cloud_words',
        cloudWordsUsed: 2100,
        cloudWordsLimit: 2000,
      })
      render(<UsageCard onUpgrade={vi.fn()} />)
      expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '0')
      expect(screen.getByText('0 / 2,000 words')).toBeInTheDocument()
      expect(screen.getByText('shell.quotaReached')).toBeInTheDocument()
      act(() => useAuthStore.setState({ user: null }))
      expect(screen.queryByRole('progressbar')).toBeNull()
      expect(screen.queryByText('0 / 2,000 words')).toBeNull()
      expect(screen.getByRole('button', { name: 'nav.upgrade' })).toBeInTheDocument()
    } finally {
      useAuthStore.setState(auth)
    }
  })

  it('uses the legacy display quota without inventing quota before it loads', () => {
    const auth = useAuthStore.getState()
    try {
      useAuthStore.setState({
        user: {
          id: 'legacy-quota-test',
          email: 'legacy@example.test',
          name: 'Legacy',
          emailVerified: true,
        },
        plan: 'free',
        subscriptionStatus: null,
        quotaModel: 'legacy_dual_meter',
        displayWordsUsedEstimate: 300,
        displayWordsLimit: 1000,
        cloudWordsUsed: 900,
        cloudWordsLimit: 2000,
      })
      render(<UsageCard onUpgrade={vi.fn()} />)
      expect(screen.getByText('700 / 1,000 words')).toBeInTheDocument()
      expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '70')
      act(() =>
        useAuthStore.setState({
          displayWordsLimit: 0,
          cloudWordsLimit: 0,
        }),
      )
      expect(screen.queryByRole('progressbar')).toBeNull()
      expect(screen.queryByText('700 / 1,000 words')).toBeNull()
      expect(screen.queryByText('shell.manageModels')).toBeNull()
      expect(screen.getByRole('button', { name: 'nav.upgrade' })).toBeInTheDocument()
    } finally {
      useAuthStore.setState(auth)
    }
  })
})
