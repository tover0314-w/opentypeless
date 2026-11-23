import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { openUrl } from '@tauri-apps/plugin-opener'
import { Info, X } from 'lucide-react'
import {
  isMacPlatform,
  isWindowsPlatform,
  useAppStore,
  type HistoryEntry,
} from '../../stores/appStore'
import { useAppVersion } from '../../hooks/useAppVersion'
import { toast } from '../toast-service'
import { Modal } from '../shared/Modal'
import { Tooltip } from '../shared/Tooltip'
import { buildFeedbackIssueUrl } from './feedbackIssue'
import { historyItemState, historyTranscript } from './historyItemState'

const TEXTAREA_MAX_HEIGHT = 264

const STATE_TEXT_KEY = {
  dismissed: 'history.item.dismissed',
  silent: 'history.item.silent',
  interrupted: 'history.item.interrupted',
} as const

// Typeless `jP`: transcript quote above a borderless textarea, sharing one focusable box.
export function HistoryFeedbackDialog({
  entry,
  onClose,
}: {
  entry: HistoryEntry
  onClose: () => void
}) {
  const { t } = useTranslation()
  const config = useAppStore((state) => state.config)
  const version = useAppVersion()
  const [feedback, setFeedback] = useState('')
  const [sending, setSending] = useState(false)
  const state = historyItemState(entry)
  const isAsk = entry.mode === 'ask'

  const send = async () => {
    if (!feedback.trim() || sending) return
    setSending(true)
    try {
      await openUrl(
        buildFeedbackIssueUrl({
          entry,
          feedback,
          version,
          platform: isMacPlatform() ? 'macOS' : isWindowsPlatform() ? 'Windows' : 'Linux',
          config,
        }),
      )
      toast.success(t('history.feedback.opened'))
      onClose()
    } catch {
      console.error('Failed to open feedback issue')
      toast.error(t('history.feedback.failed'))
      setSending(false)
    }
  }

  return (
    <Modal
      title={t('history.feedback.title')}
      onClose={onClose}
      dismissible={!sending}
      closeOnBackdrop={false}
      bare
    >
      <div data-testid="history-feedback-dialog">
        <div className="relative flex h-16 items-center px-6 pb-4 pt-6">
          <h2 className="m-0 text-[20px] font-bold leading-6">{t('history.feedback.title')}</h2>
          <button
            type="button"
            aria-label={t('common.close')}
            disabled={sending}
            onClick={onClose}
            className="absolute right-3 top-3 grid h-6 w-6 cursor-pointer place-items-center rounded-full border-none bg-transparent text-text-secondary transition-colors hover:bg-fill-hover disabled:cursor-default disabled:opacity-40"
          >
            <X size={14} />
          </button>
        </div>
        <div className="flex flex-col gap-6 px-6 pb-6 pt-4">
          <div className="flex flex-col gap-2 rounded-[10px] border border-border bg-bg-elevated px-3 pb-3 pt-2.5 transition-[border-color,box-shadow] focus-within:border-text-primary focus-within:shadow-[0_0_0_2px_rgba(17,17,17,0.1),0_0_0_2px_rgba(242,241,240,0.15)]">
            <div className="flex flex-col items-start gap-2 rounded-[6px] bg-fill-thin p-2">
              <div className="flex items-center gap-1">
                <span className="text-[14px] font-medium text-text-secondary">
                  {t(isAsk ? 'history.feedback.question' : 'history.feedback.transcript')}
                </span>
                <Tooltip label={t('history.feedback.includedTooltip')} wide>
                  <span
                    tabIndex={0}
                    aria-label={t('history.feedback.includedTooltip')}
                    className="inline-flex rounded-full text-text-secondary outline-none focus-visible:ring-2 focus-visible:ring-accent"
                  >
                    <Info size={12} aria-hidden />
                  </span>
                </Tooltip>
              </div>
              <div
                data-testid="history-feedback-transcript"
                className={`w-full overflow-hidden border-l-[1.5px] border-[rgba(119,119,119,0.3)] pl-1.5 text-[14px] leading-[22px] [-webkit-box-orient:vertical] [-webkit-line-clamp:5] [display:-webkit-box] ${
                  state === 'transcript'
                    ? 'whitespace-pre-wrap break-words text-text-secondary'
                    : 'text-text-tertiary'
                }`}
              >
                {state === 'transcript' ? historyTranscript(entry) : t(STATE_TEXT_KEY[state])}
              </div>
            </div>
            <textarea
              data-autofocus
              rows={5}
              value={feedback}
              disabled={sending}
              aria-label={t('history.feedback.title')}
              placeholder={t('history.feedback.placeholder')}
              onChange={(event) => {
                setFeedback(event.target.value)
                event.target.style.height = 'auto'
                event.target.style.height = `${event.target.scrollHeight}px`
              }}
              onKeyDown={(event) => {
                if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return
                if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
                  event.preventDefault()
                  void send()
                }
              }}
              style={{ maxHeight: TEXTAREA_MAX_HEIGHT }}
              className="w-full resize-none overflow-y-auto border-0 bg-transparent p-0 pr-[max(8px,1%)] text-[14px] leading-[22px] text-text-primary outline-none placeholder:text-text-tertiary"
            />
          </div>
          <div className="flex items-center justify-between gap-3">
            <span className="text-[12px] leading-4 text-text-tertiary">
              {t('history.feedback.githubHint')}
            </span>
            <button
              type="button"
              disabled={!feedback.trim() || sending}
              onClick={() => void send()}
              className="btn-primary h-8 shrink-0 cursor-pointer px-3 text-[14px] disabled:cursor-default disabled:opacity-40"
            >
              {t('history.feedback.send')}
            </button>
          </div>
        </div>
      </div>
    </Modal>
  )
}
