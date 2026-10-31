import { useTranslation } from 'react-i18next'
import { KeyRound } from 'lucide-react'
import { useAppStore } from '../../stores/appStore'
import { hasManagedCloudAccess, useAuthStore } from '../../stores/authStore'
import { usageWords } from '../../lib/usage-words'

interface Props {
  onUpgrade: () => void
  onOpenModels: () => void
}

export function UsageCard({ onUpgrade, onOpenModels }: Props) {
  const { t } = useTranslation()
  const auth = useAuthStore()
  const hasCloudAccess = useAuthStore(hasManagedCloudAccess)
  const usesCloud = useAppStore(
    (s) => s.config.stt_provider === 'cloud' || s.config.llm_provider === 'cloud',
  )
  const { used, limit } = usageWords(auth)
  const formatter = new Intl.NumberFormat()

  if (!usesCloud && !hasCloudAccess) {
    return (
      <div className="rounded-[16px] bg-bg-elevated p-4 shadow-sm">
        <div className="flex items-center gap-2 text-[14px] font-medium text-text-primary">
          <KeyRound size={15} className="text-accent" />
          {t('shell.byokTitle')}
        </div>
        <p className="mt-1 text-[12px] leading-[18px] text-text-tertiary">
          {t('shell.byokDescription')}
        </p>
        <button
          type="button"
          onClick={onOpenModels}
          className="btn-outline mt-3 w-full cursor-pointer py-1.5 text-[13px] font-medium"
        >
          {t('shell.manageModels')}
        </button>
      </div>
    )
  }

  const pct = limit > 0 ? Math.min((used / limit) * 100, 100) : 0
  const atLimit = limit > 0 && used >= limit

  return (
    <div className="rounded-[16px] bg-bg-elevated p-4 shadow-sm">
      <div className="text-[14px] text-text-secondary">
        {hasCloudAccess ? t('shell.cloudWordsTitle') : t('shell.getMoreWords')}
      </div>
      {limit > 0 && (
        <>
          <div
            className={`mt-2 text-[14px] font-semibold tabular-nums ${atLimit ? 'text-error' : 'text-text-primary'}`}
          >
            {t('shell.wordsUsage', {
              used: formatter.format(used),
              limit: formatter.format(limit),
            })}
          </div>
          <div className="mt-1.5 h-1 overflow-hidden rounded-full bg-fill-tough">
            <div
              className={`h-full rounded-full ${atLimit ? 'bg-error' : 'bg-action'}`}
              style={{ width: `${pct}%` }}
            />
          </div>
        </>
      )}
      {!hasCloudAccess && (
        <button
          type="button"
          onClick={onUpgrade}
          className="btn-accent mt-3 min-h-[40px] w-full cursor-pointer text-[15px] font-semibold"
        >
          {t('nav.upgrade')}
        </button>
      )}
    </div>
  )
}
