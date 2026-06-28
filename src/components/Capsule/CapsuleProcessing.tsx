import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { motion, useReducedMotion } from 'framer-motion'
import { Loader2, X } from 'lucide-react'
import { abortRecording } from '../../lib/tauri'
import { useAppStore } from '../../stores/appStore'

/** Seconds elapsed before we hint that the server is slow but still working. */
const BUSY_HINT_AFTER_SECONDS = 6

export function CapsuleProcessing() {
  const { t } = useTranslation()
  const partialTranscript = useAppStore((s) => s.partialTranscript)
  const pipelineState = useAppStore((s) => s.pipelineState)
  const reduced = useReducedMotion()

  // Elapsed-seconds counter so the user never sees a frozen "Transcribing".
  // Resets whenever the pipeline state changes (mirrors DurationTimer).
  const [seconds, setSeconds] = useState(0)
  useEffect(() => {
    setSeconds(0)
    const interval = setInterval(() => setSeconds((s) => s + 1), 1000)
    return () => clearInterval(interval)
  }, [pipelineState])

  // Once we have partial text the live transcript proves progress on its own.
  const showElapsed = !partialTranscript
  const isBusy = seconds >= BUSY_HINT_AFTER_SECONDS
  const baseText = partialTranscript || t('capsule.transcribing')

  const handleCancel = async (e: React.MouseEvent) => {
    e.stopPropagation()
    try {
      await abortRecording()
    } catch (err) {
      console.error('Failed to abort processing:', err)
    }
  }

  const stopPointerPropagation = (e: React.PointerEvent) => {
    e.stopPropagation()
  }

  return (
    <motion.div className="relative z-10 flex items-center gap-2 h-9 px-3">
      {/* Shimmer sweep overlay */}
      <div className="capsule-shimmer" />
      <motion.div
        className="flex-shrink-0"
        animate={reduced ? undefined : { rotate: 360 }}
        transition={{ repeat: Infinity, duration: 1, ease: 'linear' }}
      >
        <Loader2 size={12} className="text-white/80" />
      </motion.div>
      <p className="text-[11px] text-white leading-snug truncate flex-1 min-w-0">
        {baseText}
        {showElapsed && <span className="tabular-nums"> {seconds}s</span>}
        {showElapsed && isBusy && <span className="text-white/60"> · {t('capsule.busyHint')}</span>}
        <motion.span
          className="inline-block w-[2px] h-[11px] bg-white/60 ml-0.5 align-middle"
          animate={reduced ? undefined : { opacity: [1, 0, 1] }}
          transition={{ repeat: Infinity, duration: 0.8 }}
        />
      </p>
      <button
        onPointerDown={stopPointerPropagation}
        onPointerUp={stopPointerPropagation}
        onClick={handleCancel}
        aria-label={t('capsule.cancelProcessing')}
        className="flex-shrink-0 p-1 rounded-full text-white/70 hover:text-white hover:bg-white/15 transition-colors bg-transparent border-none cursor-pointer"
      >
        <X size={12} />
      </button>
    </motion.div>
  )
}
