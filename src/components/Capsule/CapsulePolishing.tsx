import { useTranslation } from 'react-i18next'
import { motion, useReducedMotion } from 'framer-motion'
import { X } from 'lucide-react'
import { abortRecording } from '../../lib/tauri'

export function CapsulePolishing() {
  const { t } = useTranslation()
  const reduced = useReducedMotion()

  const handleCancel = async (e: React.MouseEvent) => {
    e.stopPropagation()
    try {
      await abortRecording()
    } catch (err) {
      console.error('Failed to abort polishing:', err)
    }
  }

  return (
    <motion.div className="relative z-10 flex items-center gap-2 h-9 px-3">
      {/* Dot pulse animation */}
      <div className="flex items-center gap-[3px] flex-shrink-0">
        {[0, 1, 2].map((i) => (
          <motion.span
            key={i}
            className="block w-[4px] h-[4px] rounded-full bg-white/80"
            animate={reduced ? undefined : { opacity: [0.3, 1, 0.3], scale: [0.8, 1.1, 0.8] }}
            transition={{
              repeat: Infinity,
              duration: 1,
              delay: i * 0.2,
              ease: 'easeInOut',
            }}
          />
        ))}
      </div>
      <p className="text-[11px] text-white leading-snug truncate flex-1 min-w-0">
        {t('capsule.thinking')}
      </p>
      <button
        onClick={handleCancel}
        aria-label={t('capsule.cancelPolishing')}
        className="flex-shrink-0 p-1 rounded-full text-white/70 hover:text-white hover:bg-white/15 transition-colors bg-transparent border-none cursor-pointer"
      >
        <X size={12} />
      </button>
      {/* Bottom progress bar */}
      <div className="capsule-progress-bar" />
    </motion.div>
  )
}
