import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { HistorySyncProposal } from '../../lib/historySyncConsent'
import type { ReactNode } from 'react'
import { Modal } from '../shared/Modal'

interface Props {
  proposal: HistorySyncProposal
  busy: boolean
  error?: string | null
  resultUnknown?: boolean
  onConfirm: (proposal: HistorySyncProposal) => void
  onCancel: (proposal: HistorySyncProposal) => void
  onCloseUnknown?: (proposal: HistorySyncProposal) => void
  unknownContent?: ReactNode
}

export function HistorySyncConsentDialog({
  proposal,
  busy,
  error,
  resultUnknown = false,
  onConfirm,
  onCancel,
  onCloseUnknown,
  unknownContent,
}: Props) {
  const { t } = useTranslation()
  const [acceptedIdentity, setAcceptedIdentity] = useState<string | null>(null)
  const [submittingIdentity, setSubmittingIdentity] = useState<string | null>(null)
  const [unknownIdentity, setUnknownIdentity] = useState<string | null>(null)
  const [attemptedIdentity, setAttemptedIdentity] = useState<string | null>(null)
  const pendingIdentity = useRef<string | null>(null)
  const identity = JSON.stringify([
    proposal.requestId,
    proposal.description.accountId,
    proposal.description.serviceUrl,
    proposal.description.sessionEpoch,
  ])
  const accepted = acceptedIdentity === identity
  const unknown = resultUnknown || unknownIdentity === identity
  const pending = busy || submittingIdentity === identity
  useEffect(() => {
    setAcceptedIdentity(null)
    setSubmittingIdentity(null)
    setUnknownIdentity(null)
    setAttemptedIdentity(null)
    pendingIdentity.current = null
  }, [identity])
  useEffect(() => {
    if (resultUnknown) setUnknownIdentity(identity)
  }, [identity, resultUnknown])
  useEffect(() => {
    if (!busy && (error || resultUnknown)) {
      pendingIdentity.current = null
      setSubmittingIdentity(null)
    }
  }, [busy, error, resultUnknown])
  return (
    <Modal
      title={t('historySyncAuthorization.title')}
      dismissible={!pending}
      closeOnBackdrop={false}
      onClose={() => {
        if (busy || pendingIdentity.current === identity) return
        if (unknown) onCloseUnknown?.(proposal)
        else onCancel(proposal)
      }}
    >
      <div className="space-y-4">
        <dl className="space-y-2 text-[13px]">
          <div>
            <dt className="text-text-tertiary">{t('historySyncAuthorization.account')}</dt>
            <dd className="m-0 break-all text-text-primary">{proposal.description.accountId}</dd>
          </div>
          <div>
            <dt className="text-text-tertiary">{t('historySyncAuthorization.service')}</dt>
            <dd className="m-0 break-all text-text-primary">{proposal.description.serviceUrl}</dd>
          </div>
        </dl>
        <p className="text-[12px] leading-5 text-text-secondary">
          {t('historySyncAuthorization.scope')}
        </p>
        <p className="text-[12px] leading-5 text-text-secondary">
          {t('historySyncAuthorization.warning')}
        </p>
        <label className="flex items-start gap-2 text-[13px] text-text-primary">
          <input
            type="checkbox"
            checked={accepted}
            disabled={pending || unknown || attemptedIdentity === identity}
            onChange={(event) => setAcceptedIdentity(event.target.checked ? identity : null)}
          />
          {t('historySyncAuthorization.accept')}
        </label>
        {error && (
          <p role="alert" className="text-[12px] text-error">
            {error}
          </p>
        )}
        {unknown && (
          <p className="text-[12px] leading-5 text-text-secondary">
            {t('historySyncAuthorization.unknownWarning')}
          </p>
        )}
        {unknown && unknownContent}
        <div className="flex justify-end gap-2">
          <button
            type="button"
            className="btn-secondary"
            disabled={pending}
            data-autofocus
            onClick={() => {
              if (unknown) onCloseUnknown?.(proposal)
              else onCancel(proposal)
            }}
          >
            {t(unknown ? 'common.close' : 'common.cancel')}
          </button>
          <button
            type="button"
            className="btn-primary"
            aria-busy={pending}
            disabled={!accepted || pending || unknown || attemptedIdentity === identity}
            onClick={() => {
              if (
                !accepted ||
                busy ||
                unknown ||
                attemptedIdentity === identity ||
                pendingIdentity.current === identity
              ) {
                return
              }
              pendingIdentity.current = identity
              setAttemptedIdentity(identity)
              setSubmittingIdentity(identity)
              onConfirm(proposal)
            }}
          >
            {t(pending ? 'common.loading' : 'historySyncAuthorization.confirm')}
          </button>
        </div>
      </div>
    </Modal>
  )
}
