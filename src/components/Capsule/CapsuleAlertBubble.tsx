import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, Info, X } from 'lucide-react'
import { writeText } from '@tauri-apps/plugin-clipboard-manager'
import { ALERT_DURATION_MS, useCapsuleAlertStore } from '../../stores/capsuleAlertStore'
import { abortRecording } from '../../lib/tauri'
import { useVoiceHotkeys } from '../../lib/hotkeyLabels'
import { Keycaps } from '../shared/Keycaps'
import { BrandMark } from '../MainLayout/BrandMark'

export function CapsuleAlertBubble() {
  const alert = useCapsuleAlertStore((s) => s.alert)
  const dismiss = useCapsuleAlertStore((s) => s.dismiss)

  useEffect(() => {
    if (!alert) return
    const duration = ALERT_DURATION_MS[alert.kind]
    if (duration === null) return
    const timer = window.setTimeout(() => dismiss(alert.kind), duration)
    return () => window.clearTimeout(timer)
  }, [alert, dismiss])

  if (!alert) return null

  return (
    <div
      role="status"
      aria-live="polite"
      data-capsule-alert={alert.kind}
      onPointerDown={(event) => event.stopPropagation()}
      onPointerUp={(event) => event.stopPropagation()}
      className="pointer-events-auto mb-2 max-w-[440px]"
    >
      {alert.kind === 'shortcut_guide' && <ShortcutGuide />}
      {alert.kind === 'press_to_finish' && <PressToFinish />}
      {alert.kind === 'previous_pending' && (
        <PreviousPending onClose={() => dismiss('previous_pending')} />
      )}
      {alert.kind === 'weak_network' && <WeakNetwork />}
      {alert.kind === 'copy_last_transcript' && (
        <CopyLastTranscript text={alert.text ?? ''} onClose={() => dismiss(alert.kind)} />
      )}
      {alert.kind === 'transcript_cancelled' && <SimpleAlert textKey="capsule.alerts.cancelled" />}
      {alert.kind === 'ready' && <ReadyAlert onClose={() => dismiss('ready')} />}
      {alert.kind === 'last_minute' && <LastMinute />}
      {alert.kind === 'selected_text_tip' && <SelectedTextTip />}
    </div>
  )
}

/** First run after onboarding: where the bar lives and which key starts dictation. */
function ReadyAlert({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const keys = useVoiceHotkeys()
  return (
    <div className="capsule-alert flex min-w-[360px] max-w-[440px] flex-col items-center gap-2 p-4 text-center">
      <div className="flex items-center gap-2 text-[15px] font-semibold">
        <BrandMark size={18} className="text-white" />
        {t('capsule.alerts.readyTitle', { app: t('app.name') })}
      </div>
      <p className="m-0 flex flex-wrap items-center justify-center gap-1 text-[13px] leading-5 text-[#c9c9c9]">
        {t('capsule.alerts.readyPrefix')}
        {keys.dictate && <Keycaps hotkey={keys.dictate} />}
        {t('capsule.alerts.readySuffix')}
      </p>
      <button
        type="button"
        onClick={onClose}
        className="mt-1 h-7 rounded-full border-none bg-accent px-3 text-[12px] font-medium text-white cursor-pointer hover:bg-accent-hover"
      >
        {t('capsule.alerts.gotIt')}
      </button>
    </div>
  )
}

/** Recording is about to hit its provider limit; the bar also shows an mm:ss countdown. */
function LastMinute() {
  const { t } = useTranslation()
  return (
    <div className="capsule-alert flex max-w-[360px] flex-col items-center gap-1 p-4 text-center">
      <div className="flex items-center gap-2 text-[14px] font-medium leading-5">
        <AlertTriangle size={16} className="shrink-0 text-[#ff9e6c]" />
        {t('capsule.alerts.lastMinuteTitle')}
      </div>
      <p className="m-0 text-[12px] leading-4 text-[#c9c9c9]">
        {t('capsule.alerts.lastMinuteDesc')}
      </p>
    </div>
  )
}

/** Dictating over a selection: point at the Ask key, which answers about the selected text. */
function SelectedTextTip() {
  const { t } = useTranslation()
  const keys = useVoiceHotkeys()
  if (!keys.ask) return null
  return (
    <div className="capsule-alert flex items-center gap-1.5 px-3 py-2 text-[14px] leading-4">
      <BrandMark size={18} className="shrink-0 text-white" />
      <span>{t('capsule.alerts.selectedTipPrefix')}</span>
      <Keycaps hotkey={keys.ask} />
      <span>{t('capsule.alerts.selectedTipSuffix')}</span>
    </div>
  )
}

/** Shown while a voice key is held past the long-press threshold; hidden on release. */
function ShortcutGuide() {
  const { t } = useTranslation()
  const keys = useVoiceHotkeys()
  const rows = [
    { id: 'dictate', label: t('capsule.alerts.guideDictate'), hotkey: keys.dictate },
    { id: 'translate', label: t('capsule.alerts.guideTranslate'), hotkey: keys.translate },
    { id: 'ask', label: t('capsule.alerts.guideAsk'), hotkey: keys.ask },
  ].filter((row): row is { id: string; label: string; hotkey: string } => Boolean(row.hotkey))

  return (
    <div className="capsule-alert min-w-[360px] max-w-[440px] overflow-hidden p-2">
      <div className="flex items-center justify-center gap-2 px-2 pt-2">
        <BrandMark size={16} className="text-white" />
        <span className="text-[14px] font-medium leading-4 text-[#f2f1f0]">
          {t('capsule.alerts.guideTitle', { app: t('app.name') })}
        </span>
      </div>
      <div className="flex flex-col items-center gap-2 p-2">
        <p className="m-0 flex flex-wrap items-center justify-center text-center text-[14px] leading-[22px] text-[rgba(242,241,240,0.7)]">
          {t('capsule.alerts.guidePrefix')}
          {keys.dictate && (
            <span className="mx-1 inline-flex">
              <Keycaps hotkey={keys.dictate} />
            </span>
          )}
          {t('capsule.alerts.guideSuffix')}
        </p>
        <div className="capsule-shortcut-table w-full" role="table">
          {rows.map((row) => (
            <div key={row.id} role="row" className="flex" data-guide-row={row.id}>
              <div
                role="rowheader"
                className="flex w-[138px] shrink-0 items-center px-3 py-2 text-[14px] font-medium text-[#c9c9c9]"
              >
                {row.label}
              </div>
              <div
                role="cell"
                className="flex flex-1 flex-wrap items-center gap-1 border-l border-[rgba(242,241,240,0.12)] px-3 py-2"
              >
                <Keycaps hotkey={row.hotkey} />
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

/** Nudge after finishing with the ✓ button: the dictation key finishes faster. */
function PressToFinish() {
  const { t } = useTranslation()
  const keys = useVoiceHotkeys()
  return (
    <div className="capsule-alert flex items-center gap-1.5 px-3 py-2 text-[14px] leading-4">
      <BrandMark size={18} className="shrink-0 text-white" />
      <span>{t('capsule.alerts.pressToFinishPrefix')}</span>
      {keys.dictate && <Keycaps hotkey={keys.dictate} />}
      <span>{t('capsule.alerts.pressToFinishSuffix')}</span>
    </div>
  )
}

/** The voice key was pressed while the previous take is still being processed. */
function PreviousPending({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const discard = () => {
    abortRecording().catch((error) => console.error('Failed to discard pending take:', error))
    onClose()
  }
  return (
    <div className="capsule-alert flex max-w-[360px] flex-col items-center gap-2 p-4 text-center">
      <div className="flex items-center gap-2 text-[14px] font-medium leading-5">
        <AlertTriangle size={16} className="shrink-0 text-[#ff9e6c]" />
        {t('capsule.alerts.previousPendingTitle')}
      </div>
      <p className="m-0 text-[12px] leading-4 text-[#c9c9c9]">
        {t('capsule.alerts.previousPendingDesc')}
      </p>
      <button
        type="button"
        onClick={discard}
        className="mt-1 min-h-8 rounded-full border-none bg-[rgba(119,119,119,0.4)] px-3 text-[12px] text-white cursor-pointer hover:bg-[rgba(119,119,119,0.5)]"
      >
        {t('capsule.cancel')}
      </button>
    </div>
  )
}

function WeakNetwork() {
  const { t } = useTranslation()
  return (
    <div className="capsule-alert px-3 py-2 text-[12px] leading-[18px]">
      {t('capsule.alerts.weakNetwork')}
    </div>
  )
}

function SimpleAlert({ textKey }: { textKey: string }) {
  const { t } = useTranslation()
  return <div className="capsule-alert px-3 py-2 text-[13px]">{t(textKey)}</div>
}

function CopyLastTranscript({ text, onClose }: { text: string; onClose: () => void }) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)
  const preview = text.length > 32 ? `${text.slice(0, 32)}…` : text

  const copy = async () => {
    try {
      await writeText(text)
      setCopied(true)
    } catch (error) {
      console.error('Failed to copy last transcript:', error)
    }
  }

  return (
    <div className="capsule-alert relative min-w-[320px] px-4 pb-3 pt-3 text-center">
      <button
        type="button"
        aria-label={t('common.close')}
        onClick={onClose}
        className="absolute right-2 top-2 grid h-6 w-6 place-items-center rounded-full border-none bg-transparent text-white/60 cursor-pointer hover:bg-white/10 hover:text-white"
      >
        <X size={14} />
      </button>
      <div className="flex items-center justify-center gap-2 text-[14px] font-semibold">
        <Info size={16} className="text-accent" />
        {t('capsule.alerts.copyLastTitle')}
      </div>
      <p className="mt-1 text-[13px] text-white/80">“{preview}”</p>
      <button
        type="button"
        onClick={copy}
        className="mt-2 rounded-[8px] border-none bg-white/12 px-3 py-1.5 text-[13px] font-medium text-white cursor-pointer hover:bg-white/20"
      >
        {copied ? t('capsule.alerts.copied') : t('capsule.alerts.copy')}
      </button>
    </div>
  )
}
