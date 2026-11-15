import { useTranslation } from 'react-i18next'

export function QuotaBar({
  label,
  used,
  limit,
  unit,
  divisor,
  remaining = false,
}: {
  label: string
  used: number
  limit: number
  unit: string
  divisor: number
  remaining?: boolean
}) {
  const { t, i18n } = useTranslation()
  const displayedUsed = remaining ? Math.max(0, Math.min(limit, limit - used)) : used
  const pct = limit > 0 ? Math.max(0, Math.min((displayedUsed / limit) * 100, 100)) : 0
  const numberFormat = new Intl.NumberFormat(i18n.language, {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
    useGrouping: false,
  })
  const usedDisplay = numberFormat.format(Number((displayedUsed / divisor).toFixed(1)))
  const limitDisplay = numberFormat.format(Number((limit / divisor).toFixed(1)))

  return (
    <div className="space-y-1">
      <div className="flex justify-between text-[12px]">
        <span className="text-text-secondary">{label}</span>
        <span className="text-text-tertiary">
          {usedDisplay} / {limitDisplay} {unit}
        </span>
      </div>
      <div
        className="h-1.5 bg-bg-secondary rounded-full overflow-hidden"
        role="progressbar"
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={t('account.quotaUsage', {
          label,
          used: usedDisplay,
          limit: limitDisplay,
          unit,
        })}
      >
        <div
          className={`h-full rounded-full transition-all ${(remaining ? pct === 0 : pct > 90) ? 'bg-error' : 'bg-accent'}`}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  )
}
