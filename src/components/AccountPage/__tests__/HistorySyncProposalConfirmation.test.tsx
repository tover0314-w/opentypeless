import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { StrictMode } from 'react'
import { useAuthStore, type AuthUser } from '../../../stores/authStore'
import {
  cancelHistorySyncProposal,
  confirmAndReadHistorySyncConsent,
  HistorySyncConfirmationUnknownError,
  getHistorySyncConsentView,
} from '../../../lib/historySyncConsent'
import { HistorySyncProposalConfirmation } from '../HistorySyncProposalConfirmation'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))
vi.mock('../../../lib/historySyncConsent', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../lib/historySyncConsent')>()),
  confirmAndReadHistorySyncConsent: vi.fn(),
  cancelHistorySyncProposal: vi.fn(),
  getHistorySyncConsentView: vi.fn(),
}))
vi.mock('../../../stores/authStore', () => ({
  useAuthStore: Object.assign(vi.fn(), { getState: vi.fn() }),
}))

const owner = { id: 'synthetic-user', email: 'user@example.invalid' } as AuthUser
const proposal = {
  requestId: '11111111-1111-4111-8111-111111111111',
  description: {
    accountId: 'synthetic-account',
    serviceUrl: 'https://example.invalid/api/history-sync/status',
    sessionEpoch: '22222222-2222-4222-8222-222222222222',
  },
}
const view = {
  sessionEpoch: proposal.description.sessionEpoch,
  active: true,
  generation: '33333333-3333-4333-8333-333333333333',
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(useAuthStore).mockReturnValue(owner)
  vi.mocked(useAuthStore.getState).mockReturnValue({ user: owner } as ReturnType<
    typeof useAuthStore.getState
  >)
  vi.mocked(confirmAndReadHistorySyncConsent).mockResolvedValue(view)
  vi.mocked(cancelHistorySyncProposal).mockResolvedValue(true)
  vi.mocked(getHistorySyncConsentView).mockResolvedValue(view)
})
afterEach(cleanup)

function confirmDisplayed() {
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' }))
}

it.each(['confirm', 'cancel'] as const)(
  'keeps a rejected attempt gate closable without sending %s',
  async (action) => {
    const settled = vi.fn()
    render(
      <HistorySyncProposalConfirmation
        owner={owner}
        proposal={proposal}
        onSettled={settled}
        onAttempt={() => false}
      />,
    )
    if (action === 'confirm') confirmDisplayed()
    else fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'common.close' })).toBeEnabled())
    expect(confirmAndReadHistorySyncConsent).not.toHaveBeenCalled()
    expect(cancelHistorySyncProposal).not.toHaveBeenCalled()
    expect(screen.getByText('historySyncAuthorization.unknownWarning')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'common.close' }))
    expect(settled).toHaveBeenCalledExactlyOnceWith(proposal, 'unknown')
  },
)

it('cleans an untouched proposal after real unmount without reporting cancellation success', async () => {
  const settled = vi.fn()
  const { unmount } = render(
    <HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={settled} />,
  )
  unmount()
  await act(async () => {})
  expect(cancelHistorySyncProposal).toHaveBeenCalledExactlyOnceWith(proposal.requestId)
  expect(settled).not.toHaveBeenCalled()
})

it('cleans only the untouched replaced proposal', async () => {
  const settled = vi.fn()
  const { rerender } = render(
    <HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={settled} />,
  )
  const replacement = { ...proposal, requestId: '44444444-4444-4444-8444-444444444444' }
  rerender(
    <HistorySyncProposalConfirmation owner={owner} proposal={replacement} onSettled={settled} />,
  )
  await act(async () => {})
  expect(cancelHistorySyncProposal).toHaveBeenCalledExactlyOnceWith(proposal.requestId)
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  expect(settled).not.toHaveBeenCalled()
})

it('does not clean a visible proposal during StrictMode effect replay', async () => {
  const { unmount } = render(
    <StrictMode>
      <HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={vi.fn()} />
    </StrictMode>,
  )
  await act(async () => {})
  expect(cancelHistorySyncProposal).not.toHaveBeenCalled()
  unmount()
  await act(async () => {})
  expect(cancelHistorySyncProposal).toHaveBeenCalledExactlyOnceWith(proposal.requestId)
})

it('does not cancel a pending confirmation on unmount', async () => {
  let finish!: (value: typeof view) => void
  vi.mocked(confirmAndReadHistorySyncConsent).mockImplementation(
    () => new Promise((resolve) => (finish = resolve)),
  )
  const settled = vi.fn()
  const { unmount } = render(
    <HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={settled} />,
  )
  confirmDisplayed()
  unmount()
  await act(async () => finish(view))
  expect(cancelHistorySyncProposal).not.toHaveBeenCalled()
  expect(settled).not.toHaveBeenCalled()
})

it('does not clean the same proposal when its owner object is replaced', async () => {
  const settled = vi.fn()
  const { rerender, unmount } = render(
    <HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={settled} />,
  )
  const replacement = { ...owner }
  vi.mocked(useAuthStore).mockReturnValue(replacement)
  vi.mocked(useAuthStore.getState).mockReturnValue({ user: replacement } as ReturnType<
    typeof useAuthStore.getState
  >)
  rerender(
    <HistorySyncProposalConfirmation owner={replacement} proposal={proposal} onSettled={settled} />,
  )
  await act(async () => {})
  expect(cancelHistorySyncProposal).not.toHaveBeenCalled()
  unmount()
  await act(async () => {})
  expect(cancelHistorySyncProposal).toHaveBeenCalledExactlyOnceWith(proposal.requestId)
})

it('observes an unmount cleanup rejection without reporting a successful cancellation', async () => {
  vi.mocked(cancelHistorySyncProposal).mockRejectedValue(new Error('Synthetic cleanup failure'))
  const settled = vi.fn()
  const { unmount } = render(
    <HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={settled} />,
  )
  unmount()
  await act(async () => {})
  expect(cancelHistorySyncProposal).toHaveBeenCalledExactlyOnceWith(proposal.requestId)
  expect(settled).not.toHaveBeenCalled()
})

it.each([true, false])(
  'reads active=%s only on request without resolving the unknown operation',
  async (active) => {
    vi.mocked(confirmAndReadHistorySyncConsent).mockRejectedValue(
      new HistorySyncConfirmationUnknownError(),
    )
    vi.mocked(getHistorySyncConsentView).mockResolvedValue({
      ...view,
      active,
      generation: active ? view.generation : null,
    })
    const settled = vi.fn()
    render(
      <HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={settled} />,
    )
    confirmDisplayed()
    await waitFor(() => expect(screen.getByRole('button', { name: 'common.close' })).toBeEnabled())
    expect(getHistorySyncConsentView).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.checkCurrent' }))
    await waitFor(() =>
      expect(
        screen.getByText(
          active
            ? 'historySyncAuthorization.currentActive'
            : 'historySyncAuthorization.currentInactive',
        ),
      ).toBeInTheDocument(),
    )
    expect(screen.getByText('historySyncAuthorization.snapshotWarning')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' })).toBeDisabled()
    expect(settled).not.toHaveBeenCalled()
    expect(confirmAndReadHistorySyncConsent).toHaveBeenCalledTimes(1)
    expect(cancelHistorySyncProposal).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'common.close' }))
    expect(settled).toHaveBeenCalledExactlyOnceWith(proposal, 'unknown')
  },
)

it('allows closing during a read and ignores the late snapshot', async () => {
  vi.mocked(confirmAndReadHistorySyncConsent).mockRejectedValue(
    new HistorySyncConfirmationUnknownError(),
  )
  let finish!: (value: typeof view) => void
  vi.mocked(getHistorySyncConsentView).mockImplementation(
    () => new Promise((resolve) => (finish = resolve)),
  )
  const settled = vi.fn()
  render(<HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={settled} />)
  confirmDisplayed()
  await waitFor(() => expect(screen.getByRole('button', { name: 'common.close' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.checkCurrent' }))
  fireEvent.click(screen.getByRole('button', { name: 'common.close' }))
  expect(settled).toHaveBeenCalledExactlyOnceWith(proposal, 'unknown')
  await act(async () => finish(view))
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(settled).toHaveBeenCalledTimes(1)
})

it('shows a read error and permits another read without retrying confirmation', async () => {
  vi.mocked(confirmAndReadHistorySyncConsent).mockRejectedValue(
    new HistorySyncConfirmationUnknownError(),
  )
  vi.mocked(getHistorySyncConsentView).mockRejectedValueOnce(new Error('Synthetic read failure'))
  const settled = vi.fn()
  render(<HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={settled} />)
  confirmDisplayed()
  await waitFor(() => expect(screen.getByRole('button', { name: 'common.close' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.checkCurrent' }))
  await waitFor(() =>
    expect(screen.getByText('historySyncAuthorization.readError')).toBeInTheDocument(),
  )
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.checkCurrent' }))
  await waitFor(() =>
    expect(screen.getByText('historySyncAuthorization.currentActive')).toBeInTheDocument(),
  )
  expect(getHistorySyncConsentView).toHaveBeenCalledTimes(2)
  expect(confirmAndReadHistorySyncConsent).toHaveBeenCalledTimes(1)
  expect(settled).not.toHaveBeenCalled()
})

it('starts unchecked without discovery and confirms once after readback completes', async () => {
  let finish!: (value: typeof view) => void
  vi.mocked(confirmAndReadHistorySyncConsent).mockImplementation(
    () => new Promise((resolve) => (finish = resolve)),
  )
  const settled = vi.fn()
  render(<HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={settled} />)
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  expect(confirmAndReadHistorySyncConsent).not.toHaveBeenCalled()
  expect(cancelHistorySyncProposal).not.toHaveBeenCalled()
  confirmDisplayed()
  fireEvent.keyDown(document, { key: 'Escape' })
  expect(settled).not.toHaveBeenCalled()
  expect(screen.getByRole('button', { name: 'common.cancel' })).toBeDisabled()
  expect(confirmAndReadHistorySyncConsent).toHaveBeenCalledExactlyOnceWith(proposal)
  await act(async () => finish(view))
  expect(settled).toHaveBeenCalledExactlyOnceWith(proposal, 'confirmed')
  expect(screen.queryByRole('dialog')).toBeNull()
})

it('notifies cancellation only after the cancellation interface returns true', async () => {
  const settled = vi.fn()
  render(<HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={settled} />)
  fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
  await waitFor(() => expect(settled).toHaveBeenCalledExactlyOnceWith(proposal, 'cancelled'))
  expect(cancelHistorySyncProposal).toHaveBeenCalledExactlyOnceWith(proposal.requestId)
  expect(confirmAndReadHistorySyncConsent).not.toHaveBeenCalled()
})

it.each(['false', 'error', 'confirmation'] as const)(
  'keeps %s unknown visible and closing does not report cancellation',
  async (failure) => {
    if (failure === 'false') vi.mocked(cancelHistorySyncProposal).mockResolvedValue(false)
    if (failure === 'error')
      vi.mocked(cancelHistorySyncProposal).mockRejectedValue(new Error('Synthetic failure'))
    if (failure === 'confirmation')
      vi.mocked(confirmAndReadHistorySyncConsent).mockRejectedValue(
        new HistorySyncConfirmationUnknownError(),
      )
    const settled = vi.fn()
    render(
      <HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={settled} />,
    )
    if (failure === 'confirmation') confirmDisplayed()
    else fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
    await waitFor(() =>
      expect(screen.getByText('historySyncAuthorization.unknownWarning')).toBeInTheDocument(),
    )
    expect(settled).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.getByRole('button', { name: 'common.close' })).toBeEnabled())
    expect(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'common.close' }))
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(settled).toHaveBeenCalledExactlyOnceWith(proposal, 'unknown')
    expect(cancelHistorySyncProposal).toHaveBeenCalledTimes(failure === 'confirmation' ? 0 : 1)
  },
)

it('lets a rejected confirmation close without retrying cancellation of a consumed proposal', async () => {
  vi.mocked(confirmAndReadHistorySyncConsent).mockRejectedValue(new Error('Synthetic rejection'))
  const settled = vi.fn()
  render(<HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={settled} />)
  confirmDisplayed()
  await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
  await waitFor(() => expect(screen.getByRole('button', { name: 'common.cancel' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
  expect(settled).toHaveBeenCalledExactlyOnceWith(proposal, 'rejected')
  expect(cancelHistorySyncProposal).not.toHaveBeenCalled()
  expect(screen.queryByRole('dialog')).toBeNull()
})

it('hides a stale owner and ignores its pending completion', async () => {
  let finish!: (value: typeof view) => void
  vi.mocked(confirmAndReadHistorySyncConsent).mockImplementation(
    () => new Promise((resolve) => (finish = resolve)),
  )
  const settled = vi.fn()
  const props = { owner, proposal, onSettled: settled }
  const { rerender } = render(<HistorySyncProposalConfirmation {...props} />)
  confirmDisplayed()
  const replacement = { ...owner }
  vi.mocked(useAuthStore).mockReturnValue(replacement)
  vi.mocked(useAuthStore.getState).mockReturnValue({ user: replacement } as ReturnType<
    typeof useAuthStore.getState
  >)
  rerender(<HistorySyncProposalConfirmation {...props} />)
  expect(screen.queryByRole('dialog')).toBeNull()
  await act(async () => finish(view))
  expect(settled).not.toHaveBeenCalled()
})

it('does not let a replaced proposal complete the new proposal', async () => {
  let finish!: (value: typeof view) => void
  vi.mocked(confirmAndReadHistorySyncConsent).mockImplementationOnce(
    () => new Promise((resolve) => (finish = resolve)),
  )
  const settled = vi.fn()
  const { rerender } = render(
    <HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={settled} />,
  )
  confirmDisplayed()
  const replacement = { ...proposal, requestId: '44444444-4444-4444-8444-444444444444' }
  rerender(
    <HistorySyncProposalConfirmation owner={owner} proposal={replacement} onSettled={settled} />,
  )
  await act(async () => finish(view))
  expect(settled).not.toHaveBeenCalled()
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  confirmDisplayed()
  await waitFor(() => expect(settled).toHaveBeenCalledExactlyOnceWith(replacement, 'confirmed'))
})

it('resets checked consent when a new owner object replaces the same user ID', () => {
  const settled = vi.fn()
  const { rerender } = render(
    <HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={settled} />,
  )
  fireEvent.click(screen.getByRole('checkbox'))
  const replacement = { ...owner }
  vi.mocked(useAuthStore).mockReturnValue(replacement)
  vi.mocked(useAuthStore.getState).mockReturnValue({ user: replacement } as ReturnType<
    typeof useAuthStore.getState
  >)
  rerender(
    <HistorySyncProposalConfirmation owner={replacement} proposal={proposal} onSettled={settled} />,
  )
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' })).toBeDisabled()
})

it('ignores a pending cancellation after unmount', async () => {
  let finish!: (value: boolean) => void
  vi.mocked(cancelHistorySyncProposal).mockImplementation(
    () => new Promise((resolve) => (finish = resolve)),
  )
  const settled = vi.fn()
  const { unmount } = render(
    <HistorySyncProposalConfirmation owner={owner} proposal={proposal} onSettled={settled} />,
  )
  fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
  unmount()
  await act(async () => finish(true))
  expect(settled).not.toHaveBeenCalled()
})
