import { useTranslation } from 'react-i18next'

import type { ContextFamily, HistoryEntry } from '../../stores/appStore'
import { AppLogo } from '../AppLogo'

interface Props {
  iconKey: string
  family: ContextFamily
  label: string
  time: string
  providerKind: HistoryEntry['provider_kind']
  browserAccessStatus?: HistoryEntry['browser_access_status']
}

export function AppContextMeta({
  iconKey,
  family,
  label,
  time,
  providerKind,
  browserAccessStatus,
}: Props) {
  const { t } = useTranslation()
  const needsBrowserAccess = label === 'Browser' && browserAccessStatus === 'needs_permission'

  return (
    <div className="mt-1 flex min-w-0 items-center gap-1.5 text-[11px] text-text-tertiary">
      <AppLogo iconKey={iconKey} family={family} />
      <span className="min-w-0 truncate">{label}</span>
      {needsBrowserAccess && (
        <>
          <span aria-hidden="true" className="shrink-0">
            ·
          </span>
          <span className="shrink-0">{t('history.needsBrowserAccess')}</span>
        </>
      )}
      {time && (
        <>
          <span aria-hidden="true" className="shrink-0">
            ·
          </span>
          <span className="shrink-0">{time}</span>
        </>
      )}
      <span aria-hidden="true" className="shrink-0 max-[419px]:hidden">
        ·
      </span>
      <span className="shrink-0 max-[419px]:hidden">{t(`history.providers.${providerKind}`)}</span>
    </div>
  )
}
