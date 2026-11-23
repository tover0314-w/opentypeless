import { useTranslation } from 'react-i18next'
import { hasManagedCloudAccess, useAuthStore } from '../../stores/authStore'
import { usageWords } from '../../lib/usage-words'

interface Props {
  onUpgrade: () => void
}

export function UsageCard({ onUpgrade }: Props) {
  const { t, i18n } = useTranslation()
  const auth = useAuthStore()
  const hasCloudAccess = useAuthStore(hasManagedCloudAccess)
  const { used, limit } = usageWords(auth)
  const quotaLabel = t('onboarding.account.freePlanQuota')
  const formatter = new Intl.NumberFormat(i18n.language)
  const remaining = Math.max(0, Math.min(limit, limit - used))

  const pct = limit > 0 ? (remaining / limit) * 100 : 0
  const atLimit = limit > 0 && used >= limit
  const quotaAvailable =
    Boolean(auth.user) &&
    auth.subscriptionRefreshState !== 'unavailable' &&
    auth.quotaDataAvailable !== false &&
    (limit > 0 || auth.subscriptionRefreshState === 'fresh')

  return (
    <div className="rounded-[16px] bg-bg-elevated p-4 shadow-sm">
      <div className="text-[14px] text-text-secondary">
        {auth.plan === 'free' ? t('shell.planFree') : t('shell.cloudWordsTitle')}
      </div>
      <div className="mt-2 text-[12px] text-text-tertiary">{quotaLabel}</div>
      {!quotaAvailable && <div className="mt-2 text-[14px] text-text-tertiary">—</div>}
      {quotaAvailable && (
        <>
          <div
            className={`mt-2 text-[14px] font-semibold tabular-nums ${atLimit ? 'text-error' : 'text-text-primary'}`}
          >
            {t('shell.wordsUsage', {
              used: formatter.format(remaining),
              limit: formatter.format(limit),
            })}
          </div>
          {limit > 0 && (
            <div
              role="progressbar"
              aria-label={quotaLabel}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={pct}
              aria-valuetext={t('shell.wordsUsage', {
                used: formatter.format(remaining),
                limit: formatter.format(limit),
              })}
              className="mt-1.5 h-1 overflow-hidden rounded-full bg-fill-tough"
            >
              <div
                className={`h-full rounded-full ${atLimit ? 'bg-error' : 'bg-action'}`}
                style={{ width: `${pct}%` }}
              />
            </div>
          )}
          {atLimit && (
            <p className="m-0 mt-2 text-[12px] leading-4 text-error">{t('shell.quotaReached')}</p>
          )}
        </>
      )}
      <button
        type="button"
        onClick={onUpgrade}
        className="upgrade-action btn-accent mt-3 min-h-[40px] w-full cursor-pointer text-[15px] font-semibold"
      >
        {auth.user && hasCloudAccess ? t('shell.getMoreWords') : t('nav.upgrade')}
      </button>
    </div>
  )
}
