import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { AskWebSearchStart } from '../AskWebSearchStart'
import { useAuthStore } from '../../../stores/authStore'
import { useAppStore } from '../../../stores/appStore'
import { ASK_WEB_QUOTE_TTL_MS } from '../../../lib/askWebSearchCapability'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  useAuthStore.setState(useAuthStore.getInitialState())
  useAppStore.setState(useAppStore.getInitialState())
})

function enable() {
  useAppStore.getState().updateConfig({ llm_provider: 'cloud' })
  useAuthStore.setState({
    user: { id: 'synthetic', email: 'synthetic@example.invalid', name: null, emailVerified: true },
    askWebSearchAccountId: 'synthetic',
    subscriptionRefreshState: 'fresh',
    subscriptionRefreshLoading: false,
    subscriptionLastVerifiedAt: new Date().toISOString(),
    quotaModel: 'cloud_words',
    askWebSearchCapability: {
      version: 1,
      outputTokenLimit: 1024,
      maxResults: 3,
      additionalCloudWords: 25,
      additionalLlmTokens: 100,
      selectedTextAllowed: false,
    },
  })
}

it('requires confirmation before starting and invalidates it when prices change', () => {
  enable()
  const start = vi.fn()
  render(<AskWebSearchStart busy={false} onStart={start} />)
  const button = screen.getByRole('button')
  expect(button).toBeDisabled()
  expect(screen.getByText(/25/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(button)
  expect(start).toHaveBeenCalledOnce()
  expect(start).toHaveBeenCalledWith(
    expect.objectContaining({
      quotaModel: 'cloud_words',
      additionalCloudWords: 25,
      accountId: 'synthetic',
    }),
  )
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  fireEvent.click(screen.getByRole('checkbox'))
  act(() =>
    useAuthStore.setState({
      askWebSearchCapability: {
        ...useAuthStore.getState().askWebSearchCapability!,
        additionalCloudWords: 30,
      },
    }),
  )
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  expect(button).toBeDisabled()
})

it('expires confirmed quotes without requiring another store update', () => {
  vi.useFakeTimers()
  enable()
  const start = vi.fn()
  const { container } = render(<AskWebSearchStart busy={false} onStart={start} />)
  fireEvent.click(screen.getByRole('checkbox'))
  act(() => vi.advanceTimersByTime(ASK_WEB_QUOTE_TTL_MS))
  expect(container).toBeEmptyDOMElement()
  expect(start).not.toHaveBeenCalled()
})

it('rejects an expired click even when the expiry timer has not run', () => {
  vi.useFakeTimers()
  enable()
  const start = vi.fn()
  const { container } = render(<AskWebSearchStart busy={false} onStart={start} />)
  fireEvent.click(screen.getByRole('checkbox'))
  const button = screen.getByRole('button')
  vi.setSystemTime(Date.now() + ASK_WEB_QUOTE_TTL_MS)
  fireEvent.click(button)
  expect(start).not.toHaveBeenCalled()
  expect(container).toBeEmptyDOMElement()
})

it('requires new consent after a fresh quote replaces an expired quote', () => {
  vi.useFakeTimers()
  enable()
  const start = vi.fn()
  const { container } = render(<AskWebSearchStart busy={false} onStart={start} />)
  fireEvent.click(screen.getByRole('checkbox'))
  act(() => vi.advanceTimersByTime(ASK_WEB_QUOTE_TTL_MS))
  expect(container).toBeEmptyDOMElement()
  act(() => useAuthStore.setState({ subscriptionLastVerifiedAt: new Date().toISOString() }))
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  expect(screen.getByRole('button')).toBeDisabled()
  act(() => vi.advanceTimersByTime(ASK_WEB_QUOTE_TTL_MS - 1))
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('button'))
  expect(start).toHaveBeenCalledOnce()
  act(() => vi.advanceTimersByTime(1))
  expect(container).toBeEmptyDOMElement()
})

it.each([null, 'invalid', 'expired', 'future'])(
  'does not offer a quote with a %s verification time',
  (verification) => {
    vi.useFakeTimers()
    enable()
    const verifiedAt =
      verification === 'expired'
        ? new Date(Date.now() - ASK_WEB_QUOTE_TTL_MS).toISOString()
        : verification === 'future'
          ? new Date(Date.now() + 1000).toISOString()
          : verification
    useAuthStore.setState({ subscriptionLastVerifiedAt: verifiedAt })
    const { container } = render(<AskWebSearchStart busy={false} onStart={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  },
)

it('does not offer web search with stale or mismatched account capability', () => {
  enable()
  useAuthStore.setState({ askWebSearchAccountId: 'another-account' })
  const { container } = render(<AskWebSearchStart busy={false} onStart={vi.fn()} />)
  expect(container).toBeEmptyDOMElement()
})

it.each(['refresh', 'provider', 'price'])(
  'does not revive confirmation after %s changes and returns to its previous value',
  (change) => {
    enable()
    const original = useAuthStore.getState().askWebSearchCapability
    const start = vi.fn()
    render(<AskWebSearchStart busy={false} onStart={start} />)
    fireEvent.click(screen.getByRole('checkbox'))
    if (change === 'refresh') {
      act(() => useAuthStore.setState({ subscriptionRefreshLoading: true }))
      act(() => useAuthStore.setState({ subscriptionRefreshLoading: false }))
    } else if (change === 'provider') {
      act(() => useAppStore.getState().updateConfig({ llm_provider: 'ollama' }))
      act(() => useAppStore.getState().updateConfig({ llm_provider: 'cloud' }))
    } else {
      act(() =>
        useAuthStore.setState({
          askWebSearchCapability: { ...original!, additionalCloudWords: 30 },
        }),
      )
      act(() => useAuthStore.setState({ askWebSearchCapability: original }))
    }
    expect(screen.getByRole('checkbox')).not.toBeChecked()
    expect(screen.getByRole('button')).toBeDisabled()
    expect(start).not.toHaveBeenCalled()
  },
)
