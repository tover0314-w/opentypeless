import { useReducedMotion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../../stores/appStore'

export function AskModeHint() {
  const { t } = useTranslation()
  const audioVolume = useAppStore((state) => state.audioVolume)
  const reducedMotion = useReducedMotion()
  const volume =
    reducedMotion || !Number.isFinite(audioVolume) ? 0 : Math.min(1, Math.max(0, audioVolume))

  return (
    <div className="capsule-alert relative w-fit overflow-hidden rounded-[8px] p-2 text-[14px] leading-4">
      <div
        aria-hidden="true"
        data-mode-hint-glow="ask"
        className="pointer-events-none absolute bottom-0 left-1/2 h-full w-full rounded-[50%]"
        style={{
          background:
            'radial-gradient(ellipse at bottom, #2abba7, rgba(42,187,167,0.4) 50%, transparent 75%)',
          filter: 'blur(7px)',
          transform: `translate(-50%, ${95 - volume * 75}%)`,
        }}
      />
      <span className="relative z-[1]">{t('capsule.alerts.askAnything')}</span>
    </div>
  )
}
