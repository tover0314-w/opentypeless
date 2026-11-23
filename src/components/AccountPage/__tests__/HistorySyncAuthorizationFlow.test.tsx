import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { invoke } from '@tauri-apps/api/core'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAuthStore, type AuthUser } from '../../../stores/authStore'
import { HistorySyncAuthorizationFlow } from '../HistorySyncAuthorizationFlow'
import { clearSessionTokenFromMemory } from '../../../lib/cloud-session'

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))
vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))
vi.mock('../../../stores/authStore', () => ({
  useAuthStore: Object.assign(vi.fn(), { getState: vi.fn() }),
}))

const owner = { id: 'synthetic-user', email: 'user@example.invalid' } as AuthUser
const epoch = '22222222-2222-4222-8222-222222222222'
const proposal = {
  requestId: '11111111-1111-4111-8111-111111111111',
  description: {
    accountId: 'synthetic-account',
    serviceUrl: 'https://example.invalid/api/history-sync/status',
    sessionEpoch: epoch,
  },
}
const view = {
  sessionEpoch: epoch,
  active: true,
  generation: '33333333-3333-4333-8333-333333333333',
}

beforeEach(() => {
  vi.resetAllMocks()
  clearSessionTokenFromMemory()
  vi.mocked(useAuthStore).mockReturnValue(owner)
  vi.mocked(useAuthStore.getState).mockReturnValue({ user: owner } as ReturnType<
    typeof useAuthStore.getState
  >)
  vi.mocked(invoke).mockImplementation(async (command) => {
    if (command === 'get_history_sync_session_epoch') return epoch
    if (command === 'prepare_history_sync_proposal') return proposal
    if (command === 'get_history_sync_consent_view') return view
    if (command === 'cancel_history_sync_proposal') return true
    return null
  })
})
afterEach(async () => {
  cleanup()
  await act(async () => {})
})

async function prepareDisplayed() {
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.title' }))
  await waitFor(() => expect(screen.getByRole('checkbox')).toBeInTheDocument())
}

it('blocks a remounted flow while the prior native confirmation is still pending', async () => {
  let finish!: (value: null) => void
  vi.mocked(invoke).mockImplementation((command) => {
    if (command === 'get_history_sync_session_epoch') return Promise.resolve(epoch)
    if (command === 'prepare_history_sync_proposal') return Promise.resolve(proposal)
    if (command === 'get_history_sync_consent_view') return Promise.resolve(view)
    return new Promise((resolve) => (finish = resolve))
  })
  const { unmount } = render(<HistorySyncAuthorizationFlow owner={owner} />)
  await prepareDisplayed()
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' }))
  await waitFor(() =>
    expect(invoke).toHaveBeenCalledWith('confirm_history_sync_proposal', {
      requestId: proposal.requestId,
      expectedSessionEpoch: epoch,
    }),
  )
  unmount()
  render(<HistorySyncAuthorizationFlow owner={owner} />)
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.title' })).toBeDisabled()
  await act(async () => finish(null))
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.title' })).toBeDisabled()
})

it('requires explicit preparation and unchecked confirmation before native confirm/readback', async () => {
  const changed = vi.fn()
  render(<HistorySyncAuthorizationFlow owner={owner} onAuthorizationChanged={changed} />)
  expect(invoke).not.toHaveBeenCalled()
  await prepareDisplayed()
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  expect(vi.mocked(invoke).mock.calls.map(([command]) => command)).toEqual([
    'get_history_sync_session_epoch',
    'prepare_history_sync_proposal',
  ])
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(vi.mocked(invoke).mock.calls.map(([command]) => command)).toEqual([
    'get_history_sync_session_epoch',
    'prepare_history_sync_proposal',
    'confirm_history_sync_proposal',
    'get_history_sync_consent_view',
  ])
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.title' })).toBeDisabled()
  expect(changed).toHaveBeenCalledOnce()
  expect(vi.mocked(invoke).mock.calls.map(([command]) => command)).not.toContain(
    'push_history_sync_once',
  )
  expect(vi.mocked(invoke).mock.calls.map(([command]) => command)).not.toContain(
    'pull_history_sync_once',
  )
})

it('allows a new preparation after explicit successful cancellation', async () => {
  render(<HistorySyncAuthorizationFlow owner={owner} />)
  await prepareDisplayed()
  fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'historySyncAuthorization.title' })).toBeEnabled(),
  )
  expect(invoke).toHaveBeenCalledWith('cancel_history_sync_proposal', {
    requestId: proposal.requestId,
  })
})

it('keeps preparation blocked after an unknown cancellation is closed', async () => {
  vi.mocked(invoke).mockImplementation(async (command) => {
    if (command === 'get_history_sync_session_epoch') return epoch
    if (command === 'prepare_history_sync_proposal') return proposal
    return false
  })
  render(<HistorySyncAuthorizationFlow owner={owner} />)
  await prepareDisplayed()
  fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
  await waitFor(() => expect(screen.getByRole('button', { name: 'common.close' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'common.close' }))
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.title' })).toBeDisabled()
  expect(screen.getByText('historySyncAuthorization.unknownWarning')).toBeInTheDocument()
  expect(screen.queryByRole('dialog')).toBeNull()
})

it('cancels preparation and cleans the late native proposal without showing a dialog', async () => {
  let finish!: (value: typeof proposal) => void
  vi.mocked(invoke).mockImplementation((command) => {
    if (command === 'get_history_sync_session_epoch') return Promise.resolve(epoch)
    if (command === 'cancel_history_sync_proposal') return Promise.resolve(true)
    return new Promise((resolve) => (finish = resolve))
  })
  render(<HistorySyncAuthorizationFlow owner={owner} />)
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.title' }))
  await waitFor(() => expect(invoke).toHaveBeenCalledTimes(2))
  fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
  await act(async () => finish(proposal))
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(invoke).toHaveBeenCalledWith('cancel_history_sync_proposal', {
    requestId: proposal.requestId,
  })
})

it('does not discover after epoch reading fails and permits an explicit retry', async () => {
  vi.mocked(invoke).mockRejectedValueOnce(new Error('Synthetic epoch failure'))
  render(<HistorySyncAuthorizationFlow owner={owner} />)
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.title' }))
  await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
  expect(vi.mocked(invoke).mock.calls.map(([command]) => command)).toEqual([
    'get_history_sync_session_epoch',
  ])
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.title' })).toBeEnabled()
  await prepareDisplayed()
  expect(screen.getByRole('checkbox')).not.toBeChecked()
})

it('does not deliver an old prepared proposal after the account changes', async () => {
  let finish!: (value: typeof proposal) => void
  vi.mocked(invoke).mockImplementation((command) => {
    if (command === 'get_history_sync_session_epoch') return Promise.resolve(epoch)
    if (command === 'cancel_history_sync_proposal') return Promise.resolve(true)
    return new Promise((resolve) => (finish = resolve))
  })
  const { rerender } = render(<HistorySyncAuthorizationFlow owner={owner} />)
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.title' }))
  await waitFor(() => expect(invoke).toHaveBeenCalledTimes(2))
  const replacement = { ...owner }
  vi.mocked(useAuthStore).mockReturnValue(replacement)
  vi.mocked(useAuthStore.getState).mockReturnValue({ user: replacement } as ReturnType<
    typeof useAuthStore.getState
  >)
  rerender(<HistorySyncAuthorizationFlow owner={replacement} />)
  await act(async () => finish(proposal))
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.title' })).toBeEnabled()
  expect(invoke).toHaveBeenCalledWith('cancel_history_sync_proposal', {
    requestId: proposal.requestId,
  })
})
