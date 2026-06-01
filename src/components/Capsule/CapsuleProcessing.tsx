import { useTranslation } from 'react-i18next'
import { motion, useReducedMotion } from 'framer-motion'
import { Loader2, X } from 'lucide-react'
import { abortRecording } from '../../lib/tauri'
import { useAppStore } from '../../stores/appStore'

export function CapsuleProcessing() {
  const { t } = useTranslation()
  const partialTranscript = useAppStore((s) => s.partialTranscript)
  const reduced = useReducedMotion()

  const displayText = partialTranscript || t('capsule.transcribing')

  const handleCancel = async (e: React.MouseEvent) => {
    e.stopPropagation()
    try {
      await abortRecording()
    } catch (err) {
      console.error('Failed to abort processing:', err)
    }
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
        {displayText}
        <motion.span
          className="inline-block w-[2px] h-[11px] bg-white/60 ml-0.5 align-middle"
          animate={reduced ? undefined : { opacity: [1, 0, 1] }}
          transition={{ repeat: Infinity, duration: 0.8 }}
        />
      </p>
      <button
        onClick={handleCancel}
        aria-label={t('capsule.cancelProcessing')}
        className="flex-shrink-0 p-1 rounded-full text-white/70 hover:text-white hover:bg-white/15 transition-colors bg-transparent border-none cursor-pointer"
      >
        <X size={12} />
      </button>
    </motion.div>
  )
}
