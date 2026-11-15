import { useTranslation } from 'react-i18next'
import { Info } from 'lucide-react'
import type { HistoryEntry } from '../../stores/appStore'
import { historyItemState, historyTranscript } from './historyItemState'
import { Tooltip } from '../shared/Tooltip'

export function HistoryItemText({ entry }: { entry: HistoryEntry }) {
  const { t } = useTranslation()
  const state = historyItemState(entry)

  if (state === 'transcript') {
    return (
      <p className="m-0 whitespace-pre-wrap break-words text-[14px] leading-[22px] text-text-primary">
        {historyTranscript(entry)}
      </p>
    )
  }

  if (state === 'interrupted') {
    return (
      <p className="m-0 break-words text-[14px] leading-[22px] text-error">
        {t('history.item.interrupted')}
      </p>
    )
  }

  const text = t(state === 'dismissed' ? 'history.item.dismissed' : 'history.item.silent')
  const tooltip = t(
    state === 'dismissed' ? 'history.item.dismissedTooltip' : 'history.item.silentTooltip',
  )
  return (
    <p className="m-0 flex min-w-0 items-center text-[14px] leading-[22px] text-text-tertiary">
      <span className="min-w-0 break-words">{text}</span>
      <Tooltip label={tooltip} wide>
        <span
          tabIndex={0}
          aria-label={tooltip}
          className="ml-1 inline-flex cursor-default rounded-full outline-none focus-visible:ring-2 focus-visible:ring-accent"
        >
          <Info size={14} aria-hidden />
        </span>
      </Tooltip>
    </p>
  )
}
