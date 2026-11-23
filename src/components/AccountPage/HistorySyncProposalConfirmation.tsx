import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAuthStore, type AuthUser } from '../../stores/authStore'
import { cancelHistorySyncProposal, type HistorySyncProposal } from '../../lib/historySyncConsent'
import { HistorySyncConsentDialog } from './HistorySyncConsentDialog'
import { useHistorySyncConfirmation } from './useHistorySyncConfirmation'
import { useHistorySyncConsentRecovery } from './useHistorySyncConsentRecovery'

type Outcome = 'confirmed' | 'cancelled' | 'rejected' | 'unknown'

const proposalDisplays = new Map<string, { count: number; attempted: boolean }>()

interface Props {
  owner: AuthUser
  proposal: HistorySyncProposal
  onSettled: (proposal: HistorySyncProposal, outcome: Outcome) => void
  onAttempt?: () => boolean
}

export function HistorySyncProposalConfirmation(props: Props) {
  const currentOwner = useAuthStore((state) => state.user)
  const [boundary, setBoundary] = useState({ owner: props.owner, generation: 0 })
  if (boundary.owner !== props.owner) {
    setBoundary({ owner: props.owner, generation: boundary.generation + 1 })
    return null
  }
  if (currentOwner !== props.owner) return null
  const identity = JSON.stringify([
    boundary.generation,
    props.proposal.requestId,
    props.proposal.description.accountId,
    props.proposal.description.serviceUrl,
    props.proposal.description.sessionEpoch,
  ])
  return <OwnedProposalConfirmation key={identity} {...props} />
}

function OwnedProposalConfirmation({ owner, proposal, onSettled, onAttempt }: Props) {
  const { t } = useTranslation()
  const confirmation = useHistorySyncConfirmation(owner, proposal)
  const recovery = useHistorySyncConsentRecovery(owner, proposal.description.sessionEpoch)
  const notified = useRef(false)
  const [closed, setClosed] = useState(false)
  const [attemptUnavailable, setAttemptUnavailable] = useState(false)

  useEffect(() => {
    const requestId = proposal.requestId
    const display = proposalDisplays.get(requestId) ?? { count: 0, attempted: false }
    proposalDisplays.set(requestId, display)
    display.count += 1
    return () => {
      display.count -= 1
      queueMicrotask(() => {
        if (display.count !== 0 || proposalDisplays.get(requestId) !== display) return
        proposalDisplays.delete(requestId)
        if (!display.attempted) {
          void cancelHistorySyncProposal(requestId).catch(() => undefined)
        }
      })
    }
  }, [proposal.requestId])

  useEffect(() => {
    if (
      notified.current ||
      useAuthStore.getState().user !== owner ||
      (!confirmation.confirmed && !confirmation.cancelled)
    ) {
      return
    }
    notified.current = true
    onSettled(proposal, confirmation.confirmed ? 'confirmed' : 'cancelled')
  }, [owner, proposal, onSettled, confirmation.confirmed, confirmation.cancelled])

  const close = (outcome: Outcome) => {
    if (notified.current || confirmation.busy || useAuthStore.getState().user !== owner) return
    notified.current = true
    setClosed(true)
    onSettled(proposal, outcome)
  }

  if (closed || confirmation.confirmed || confirmation.cancelled) return null

  return (
    <HistorySyncConsentDialog
      proposal={proposal}
      busy={confirmation.busy}
      error={confirmation.error || attemptUnavailable ? t('historySyncAuthorization.error') : null}
      resultUnknown={confirmation.resultUnknown || attemptUnavailable}
      onConfirm={() => {
        if (useAuthStore.getState().user !== owner) return
        if (onAttempt && !onAttempt()) {
          setAttemptUnavailable(true)
          return
        }
        const display = proposalDisplays.get(proposal.requestId)
        if (display) display.attempted = true
        void confirmation.confirm()
      }}
      onCancel={() => {
        if (useAuthStore.getState().user !== owner) return
        if (!confirmation.error && onAttempt && !onAttempt()) {
          setAttemptUnavailable(true)
          return
        }
        const display = proposalDisplays.get(proposal.requestId)
        if (display) display.attempted = true
        if (confirmation.error && !confirmation.resultUnknown) close('rejected')
        else void confirmation.cancel()
      }}
      onCloseUnknown={() => close('unknown')}
      unknownContent={
        <div className="space-y-2 text-[12px] leading-5 text-text-secondary">
          <button
            type="button"
            className="btn-secondary"
            disabled={confirmation.busy || recovery.busy}
            aria-busy={recovery.busy}
            onClick={() => void recovery.read()}
          >
            {t(recovery.busy ? 'common.loading' : 'historySyncAuthorization.checkCurrent')}
          </button>
          {recovery.error && <p role="alert">{t('historySyncAuthorization.readError')}</p>}
          {recovery.view && (
            <p role="status">
              {t(
                recovery.view.active
                  ? 'historySyncAuthorization.currentActive'
                  : 'historySyncAuthorization.currentInactive',
              )}
            </p>
          )}
          <p>{t('historySyncAuthorization.snapshotWarning')}</p>
        </div>
      }
    />
  )
}
