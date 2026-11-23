import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAuthStore } from '../../stores/authStore'
import { useAppStore } from '../../stores/appStore'
import type { AskWebSearchQuote } from '../../lib/askWebSearchCapability'
import { ASK_WEB_QUOTE_TTL_MS, isAskWebQuoteFresh } from '../../lib/askWebSearchCapability'

export function AskWebSearchStart({
  busy,
  onStart,
}: {
  busy: boolean
  onStart: (quote: AskWebSearchQuote) => void
}) {
  const { t } = useTranslation()
  const auth = useAuthStore()
  const provider = useAppStore((state) => state.config.llm_provider)
  const [confirmed, setConfirmed] = useState<string | null>(null)
  const [previousIdentity, setPreviousIdentity] = useState<string | null>(null)
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    const verifiedTime = Date.parse(auth.subscriptionLastVerifiedAt ?? '')
    if (!Number.isFinite(verifiedTime)) return
    const delay = verifiedTime + ASK_WEB_QUOTE_TTL_MS - Date.now()
    if (delay <= 0) return
    const timer = setTimeout(() => setNow(Date.now()), delay)
    return () => clearTimeout(timer)
  }, [auth.subscriptionLastVerifiedAt])
  const capability = auth.askWebSearchCapability
  const available =
    provider === 'cloud' &&
    capability !== null &&
    auth.user !== null &&
    auth.askWebSearchAccountId === auth.user.id &&
    auth.subscriptionRefreshState === 'fresh' &&
    !auth.subscriptionRefreshLoading &&
    isAskWebQuoteFresh(auth.subscriptionLastVerifiedAt, Math.max(now, Date.now()))
  const identity = available
    ? JSON.stringify([auth.user, auth.subscriptionLastVerifiedAt, auth.quotaModel, capability])
    : null
  if (previousIdentity !== identity) {
    setPreviousIdentity(identity)
    setConfirmed(null)
  }
  if (!available || !capability) return null
  const charge =
    auth.quotaModel === 'cloud_words'
      ? capability.additionalCloudWords
      : capability.additionalLlmTokens
  return (
    <div className="rounded-[8px] border border-border p-3 text-[12px]">
      <p>
        {t('ask.webCharge', {
          charge,
          unit: t(auth.quotaModel === 'cloud_words' ? 'ask.webWords' : 'ask.webTokens'),
        })}
      </p>
      <label className="flex items-center gap-2">
        <input
          type="checkbox"
          checked={confirmed === identity}
          disabled={busy}
          onChange={(event) => setConfirmed(event.target.checked ? identity : null)}
        />
        {t('ask.webConsent')}
      </label>
      <button
        type="button"
        disabled={busy || confirmed !== identity}
        className="btn-secondary mt-2"
        onClick={() => {
          if (!isAskWebQuoteFresh(auth.subscriptionLastVerifiedAt)) {
            setConfirmed(null)
            setNow(Date.now())
            return
          }
          setConfirmed(null)
          onStart({ ...capability, quotaModel: auth.quotaModel, accountId: auth.user!.id })
        }}
      >
        {t('ask.webStart')}
      </button>
    </div>
  )
}
