import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { HistorySyncConsentDialog } from '../HistorySyncConsentDialog'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))
afterEach(cleanup)

const proposal = {
  requestId: '11111111-1111-4111-8111-111111111111',
  description: {
    accountId: 'synthetic-account',
    serviceUrl: 'https://example.invalid/api/history-sync/status',
    sessionEpoch: '22222222-2222-4222-8222-222222222222',
  },
}

it('preserves consent and pending submission when equivalent proposal fields are reordered', () => {
  const confirm = vi.fn()
  const cancel = vi.fn()
  const { rerender } = render(
    <HistorySyncConsentDialog
      proposal={proposal}
      busy={false}
      onConfirm={confirm}
      onCancel={cancel}
    />,
  )
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' }))
  const reordered = {
    description: {
      sessionEpoch: proposal.description.sessionEpoch,
      serviceUrl: proposal.description.serviceUrl,
      accountId: proposal.description.accountId,
    },
    requestId: proposal.requestId,
  }
  rerender(
    <HistorySyncConsentDialog
      proposal={reordered}
      busy={false}
      onConfirm={confirm}
      onCancel={cancel}
    />,
  )
  expect(screen.getByRole('checkbox')).toBeChecked()
  expect(screen.getByRole('button', { name: 'common.loading' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'common.cancel' })).toBeDisabled()
  fireEvent.keyDown(document, { key: 'Escape' })
  expect(cancel).not.toHaveBeenCalled()
  expect(confirm).toHaveBeenCalledTimes(1)
})

it('preserves a latched unknown result across equivalent reordered proposal objects', () => {
  const props = { onConfirm: vi.fn(), onCancel: vi.fn(), onCloseUnknown: vi.fn(), busy: false }
  const { rerender } = render(
    <HistorySyncConsentDialog {...props} proposal={proposal} resultUnknown />,
  )
  rerender(
    <HistorySyncConsentDialog
      {...props}
      proposal={{
        description: {
          sessionEpoch: proposal.description.sessionEpoch,
          accountId: proposal.description.accountId,
          serviceUrl: proposal.description.serviceUrl,
        },
        requestId: proposal.requestId,
      }}
    />,
  )
  expect(screen.getByRole('button', { name: 'common.close' })).toBeEnabled()
  expect(screen.getByRole('checkbox')).toBeDisabled()
})

it('requires explicit consent and passes the displayed proposal to confirmation', () => {
  const confirm = vi.fn()
  render(
    <HistorySyncConsentDialog
      proposal={proposal}
      busy={false}
      onConfirm={confirm}
      onCancel={vi.fn()}
    />,
  )
  expect(screen.getByText(proposal.description.accountId)).toBeInTheDocument()
  expect(screen.getByText(proposal.description.serviceUrl)).toBeInTheDocument()
  const button = screen.getByRole('button', { name: 'historySyncAuthorization.confirm' })
  expect(button).toBeDisabled()
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(button)
  fireEvent.click(button)
  expect(confirm).toHaveBeenCalledExactlyOnceWith(proposal)
})

it('does not carry checked consent into a replacement proposal', () => {
  const props = { busy: false, onConfirm: vi.fn(), onCancel: vi.fn() }
  const { rerender } = render(<HistorySyncConsentDialog {...props} proposal={proposal} />)
  fireEvent.click(screen.getByRole('checkbox'))
  rerender(
    <HistorySyncConsentDialog
      {...props}
      proposal={{ ...proposal, requestId: '33333333-3333-4333-8333-333333333333' }}
    />,
  )
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' })).toBeDisabled()
  rerender(<HistorySyncConsentDialog {...props} proposal={proposal} />)
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' })).toBeDisabled()
})

it('passes cancellation without granting consent and blocks Escape while busy', () => {
  const cancel = vi.fn()
  const props = { proposal, onCancel: cancel, onConfirm: vi.fn() }
  const { rerender } = render(<HistorySyncConsentDialog {...props} busy={false} />)
  fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
  expect(cancel).toHaveBeenCalledExactlyOnceWith(proposal)
  rerender(<HistorySyncConsentDialog {...props} busy />)
  fireEvent.keyDown(document, { key: 'Escape' })
  expect(cancel).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('button', { name: 'common.cancel' })).toBeDisabled()
})

it('requires a fresh proposal after a controlled confirmation failure', () => {
  const confirm = vi.fn()
  const props = { proposal, onCancel: vi.fn(), onConfirm: confirm }
  const { rerender } = render(<HistorySyncConsentDialog {...props} busy={false} />)
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' }))
  rerender(<HistorySyncConsentDialog {...props} busy />)
  rerender(
    <HistorySyncConsentDialog {...props} busy={false} error="Synthetic confirmation failed" />,
  )
  expect(screen.getByRole('alert')).toHaveTextContent('Synthetic confirmation failed')
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'common.cancel' })).toBeEnabled()
  rerender(<HistorySyncConsentDialog {...props} busy={false} />)
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' }))
  expect(confirm).toHaveBeenCalledTimes(1)
  rerender(
    <HistorySyncConsentDialog
      {...props}
      proposal={{ ...proposal, requestId: '33333333-3333-4333-8333-333333333333' }}
      busy={false}
    />,
  )
  expect(screen.getByRole('checkbox')).not.toBeChecked()
  fireEvent.click(screen.getByRole('checkbox'))
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' })).toBeEnabled()
})

it('blocks cancellation immediately before the parent reports busy', () => {
  const confirm = vi.fn()
  const cancel = vi.fn()
  render(
    <HistorySyncConsentDialog
      proposal={proposal}
      busy={false}
      onConfirm={confirm}
      onCancel={cancel}
    />,
  )
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' }))
  expect(screen.getByRole('button', { name: 'common.loading' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'common.loading' })).toHaveAttribute(
    'aria-busy',
    'true',
  )
  expect(screen.getByRole('button', { name: 'common.cancel' })).toBeDisabled()
  expect(screen.getByRole('checkbox')).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
  fireEvent.keyDown(document, { key: 'Escape' })
  expect(cancel).not.toHaveBeenCalled()
  expect(confirm).toHaveBeenCalledExactlyOnceWith(proposal)
})

it('does not retry confirmation when its result is unknown', () => {
  const confirm = vi.fn()
  const closeUnknown = vi.fn()
  const props = { proposal, onCancel: vi.fn(), onConfirm: confirm, onCloseUnknown: closeUnknown }
  const { rerender } = render(<HistorySyncConsentDialog {...props} busy={false} />)
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' }))
  rerender(
    <HistorySyncConsentDialog
      {...props}
      busy={false}
      resultUnknown
      error="Synthetic result unknown; check native state."
    />,
  )
  const button = screen.getByRole('button', { name: 'historySyncAuthorization.confirm' })
  expect(button).toBeDisabled()
  expect(screen.getByRole('checkbox')).toBeDisabled()
  fireEvent.click(button)
  expect(confirm).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('alert')).toHaveTextContent('check native state')
  expect(screen.queryByRole('button', { name: 'common.cancel' })).toBeNull()
  expect(screen.getByText('historySyncAuthorization.unknownWarning')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'common.close' }))
  expect(closeUnknown).toHaveBeenCalledExactlyOnceWith(proposal)
  expect(props.onCancel).not.toHaveBeenCalled()
  fireEvent.keyDown(document, { key: 'Escape' })
  expect(closeUnknown).toHaveBeenCalledTimes(2)
  expect(props.onCancel).not.toHaveBeenCalled()
})

it('allows closing an unknown result without requiring an error message', () => {
  const closeUnknown = vi.fn()
  const cancel = vi.fn()
  const props = { proposal, onConfirm: vi.fn(), onCancel: cancel, onCloseUnknown: closeUnknown }
  const { rerender } = render(<HistorySyncConsentDialog {...props} busy={false} />)
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' }))
  rerender(<HistorySyncConsentDialog {...props} busy={false} resultUnknown />)
  expect(screen.getByRole('button', { name: 'common.close' })).toBeEnabled()
  expect(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' })).toBeDisabled()
  fireEvent.keyDown(document, { key: 'Escape' })
  expect(closeUnknown).toHaveBeenCalledExactlyOnceWith(proposal)
  expect(cancel).not.toHaveBeenCalled()
})

it('keeps an unknown proposal blocked when the parent clears its transient error state', () => {
  const confirm = vi.fn()
  const closeUnknown = vi.fn()
  const props = { proposal, onConfirm: confirm, onCancel: vi.fn(), onCloseUnknown: closeUnknown }
  const { rerender } = render(<HistorySyncConsentDialog {...props} busy={false} />)
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('button', { name: 'historySyncAuthorization.confirm' }))
  rerender(<HistorySyncConsentDialog {...props} busy={false} resultUnknown />)
  rerender(<HistorySyncConsentDialog {...props} busy={false} />)
  const button = screen.getByRole('button', { name: 'historySyncAuthorization.confirm' })
  expect(button).toBeDisabled()
  expect(screen.getByRole('checkbox')).toBeDisabled()
  expect(screen.getByRole('button', { name: 'common.close' })).toBeEnabled()
  fireEvent.click(button)
  expect(confirm).toHaveBeenCalledTimes(1)
  rerender(
    <HistorySyncConsentDialog
      {...props}
      proposal={{ ...proposal, requestId: '33333333-3333-4333-8333-333333333333' }}
      busy={false}
    />,
  )
  expect(screen.getByRole('checkbox')).toBeEnabled()
  expect(screen.getByRole('checkbox')).not.toBeChecked()
})
