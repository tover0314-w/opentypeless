import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Info, X } from 'lucide-react'
import { writeText } from '@tauri-apps/plugin-clipboard-manager'
import { useAppStore } from '../../stores/appStore'
import { ALERT_DURATION_MS, useCapsuleAlertStore } from '../../stores/capsuleAlertStore'
import { Keycaps } from '../shared/Keycaps'
import { BrandMark } from '../MainLayout/BrandMark'

function useHotkeys() {
  const hotkeys = useAppStore((s) => s.config.hotkeys)
  const format = (binding: { primary: string; modifiers: string[] } | null | undefined) =>
    binding ? [...binding.modifiers, binding.primary].join('+') : null
  return {
    dictate: format(hotkeys.dictation),
    translate: format(hotkeys.translate),
    ask: format(hotkeys.ask),
  }
}

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
      onPointerDown={(event) => event.stopPropagation()}
      onPointerUp={(event) => event.stopPropagation()}
      className="pointer-events-auto mb-2 max-w-[440px]"
    >
      {alert.kind === 'shortcut_guide' && <ShortcutGuide />}
      {alert.kind === 'press_to_finish' && <PressToFinish />}
      {alert.kind === 'weak_network' && <WeakNetwork />}
      {alert.kind === 'copy_last_transcript' && (
        <CopyLastTranscript text={alert.text ?? ''} onClose={() => dismiss(alert.kind)} />
      )}
      {alert.kind === 'transcript_cancelled' && <SimpleAlert textKey="capsule.alerts.cancelled" />}
    </div>
  )
}

function ShortcutGuide() {
  const { t } = useTranslation()
  const keys = useHotkeys()
  const rows = [
    { label: t('capsule.alerts.guideDictate'), hotkey: keys.dictate },
    { label: t('capsule.alerts.guideTranslate'), hotkey: keys.translate },
    { label: t('capsule.alerts.guideAsk'), hotkey: keys.ask },
  ].filter((row): row is { label: string; hotkey: string } => Boolean(row.hotkey))

  return (
    <div className="capsule-alert min-w-[360px] overflow-hidden p-2">
      <div className="flex items-center justify-center gap-2 px-2 pt-2 text-[14px] font-medium leading-4">
        <BrandMark size={16} className="text-white" />
        {t('capsule.alerts.guideTitle')}
      </div>
      <p className="flex flex-wrap items-center justify-center gap-1 p-2 text-center text-[14px] leading-[22px] text-white/70">
        {t('capsule.alerts.guidePrefix')}
        {keys.dictate && <Keycaps hotkey={keys.dictate} />}
        {t('capsule.alerts.guideSuffix')}
      </p>
      <div className="overflow-hidden rounded-[8px] border border-white/12 bg-white/[0.06]">
        {rows.map((row, index) => (
          <div key={row.label} className={`flex ${index > 0 ? 'border-t border-white/12' : ''}`}>
            <div className="flex w-[138px] items-center px-3 py-2 text-[14px] font-medium text-white/80">
              {row.label}
            </div>
            <div className="flex flex-1 flex-wrap items-center gap-1 border-l border-white/12 px-3 py-2">
              <Keycaps hotkey={row.hotkey} />
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function PressToFinish() {
  const { t } = useTranslation()
  const keys = useHotkeys()
  return (
    <div className="capsule-alert flex items-center gap-2 px-3 py-2 text-[14px]">
      <BrandMark size={16} className="text-white" />
      <span>{t('capsule.alerts.pressToFinishPrefix')}</span>
      {keys.dictate && <Keycaps hotkey={keys.dictate} />}
      <span>{t('capsule.alerts.pressToFinishSuffix')}</span>
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
