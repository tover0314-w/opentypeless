import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAuthStore, type AuthUser } from '../../stores/authStore'
import type { HistorySyncProposal } from '../../lib/historySyncConsent'
import { useHistorySyncPreparation } from './useHistorySyncPreparation'
import { HistorySyncProposalConfirmation } from './HistorySyncProposalConfirmation'
import {
  beginHistorySyncAttempt,
  historySyncAttemptBlocked,
} from '../../lib/historySyncAttemptGate'

export function HistorySyncAuthorizationFlow({
  owner,
  onAuthorizationChanged,
}: {
  owner: AuthUser
  onAuthorizationChanged?: () => void
}) {
  const currentOwner = useAuthStore((state) => state.user)
  const [boundary, setBoundary] = useState({ owner, generation: 0 })
  if (boundary.owner !== owner) {
    setBoundary({ owner, generation: boundary.generation + 1 })
    return null
  }
  if (currentOwner !== owner) return null
  return (
    <OwnedAuthorizationFlow
      key={boundary.generation}
      owner={owner}
      onAuthorizationChanged={onAuthorizationChanged}
    />
  )
}

function OwnedAuthorizationFlow({
  owner,
  onAuthorizationChanged,
}: {
  owner: AuthUser
  onAuthorizationChanged?: () => void
}) {
  const { t } = useTranslation()
  const [proposal, setProposal] = useState<HistorySyncProposal | null>(null)
  const [blocked, setBlocked] = useState(historySyncAttemptBlocked)
  const [unknown, setUnknown] = useState(historySyncAttemptBlocked)
  const releaseAttempt = useRef<(() => void) | null>(null)
  const preparation = useHistorySyncPreparation(owner, setProposal)
  return (
    <section className="space-y-2 text-[12px] text-text-secondary">
      <button
        type="button"
        className="btn-secondary"
        disabled={blocked || preparation.busy || proposal !== null}
        aria-busy={preparation.busy}
        onClick={() => {
          if (blocked || historySyncAttemptBlocked() || preparation.busy || proposal) return
          void preparation.start()
        }}
      >
        {t(preparation.busy ? 'common.loading' : 'historySyncAuthorization.title')}
      </button>
      {preparation.busy && (
        <button type="button" className="btn-secondary" onClick={preparation.cancel}>
          {t('common.cancel')}
        </button>
      )}
      {preparation.error && <p role="alert">{t('historySyncAuthorization.error')}</p>}
      {unknown && <p role="alert">{t('historySyncAuthorization.unknownWarning')}</p>}
      {proposal && (
        <HistorySyncProposalConfirmation
          owner={owner}
          proposal={proposal}
          onAttempt={() => {
            if (releaseAttempt.current) return true
            releaseAttempt.current = beginHistorySyncAttempt()
            return releaseAttempt.current !== null
          }}
          onSettled={(settled, outcome) => {
            if (useAuthStore.getState().user !== owner || settled.requestId !== proposal.requestId)
              return
            setProposal(null)
            if (outcome === 'cancelled' || outcome === 'rejected') {
              releaseAttempt.current?.()
              releaseAttempt.current = null
            }
            setBlocked(outcome === 'confirmed' || outcome === 'unknown')
            setUnknown(outcome === 'unknown')
            if (outcome === 'confirmed') onAuthorizationChanged?.()
          }}
        />
      )}
    </section>
  )
}
