import { useEffect, useRef, useState } from 'react'
import { useReducedMotion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { thinkingProgress, SLOW_PROCESSING_SECONDS } from '../../lib/capsuleView'
import { useCapsuleAlertStore } from '../../stores/capsuleAlertStore'

export function ThinkingLabel() {
  const { t } = useTranslation()
  const reduced = useReducedMotion()
  const [elapsed, setElapsed] = useState(0)
  const showAlert = useCapsuleAlertStore((s) => s.show)
  const dismissAlert = useCapsuleAlertStore((s) => s.dismissById)
  const slowNetworkAlertId = useRef<number | null>(null)
  const isSlow = elapsed >= SLOW_PROCESSING_SECONDS

  useEffect(() => {
    const started = performance.now()
    const timer = window.setInterval(() => setElapsed((performance.now() - started) / 1000), 100)
    return () => {
      window.clearInterval(timer)
      if (slowNetworkAlertId.current !== null) dismissAlert(slowNetworkAlertId.current)
      slowNetworkAlertId.current = null
    }
  }, [dismissAlert])

  useEffect(() => {
    if (isSlow) {
      showAlert('weak_network')
      slowNetworkAlertId.current =
        useCapsuleAlertStore.getState().queue.find((alert) => alert.kind === 'weak_network')?.id ??
        null
    }
  }, [isSlow, showAlert])

  const progress = reduced ? 0 : thinkingProgress(elapsed)

  return (
    <>
      <div className="pointer-events-none absolute inset-0 overflow-hidden rounded-full">
        <div
          className="absolute inset-y-0 left-0 bg-[rgba(242,241,240,0.25)] transition-[width] duration-100 ease-linear"
          style={{ width: `${progress}%` }}
          aria-hidden="true"
        />
      </div>
      <div className="relative z-[1] flex h-full items-center justify-center">
        <span className="capsule-thinking-text text-[14px] font-[450] leading-4">
          {t('capsule.thinking')}
        </span>
      </div>
    </>
  )
}
