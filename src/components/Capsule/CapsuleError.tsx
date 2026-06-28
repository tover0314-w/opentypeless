import { useEffect } from 'react'
import { motion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../../stores/appStore'
import { shouldShowSavedRecordingHint } from '../../lib/capsuleError'

export function CapsuleError() {
  const { t } = useTranslation()
  const pipelineError = useAppStore((s) => s.pipelineError)
  const pipelineErrorKey = useAppStore((s) => s.pipelineErrorKey)
  const setPipelineError = useAppStore((s) => s.setPipelineError)
  const setPipelineErrorKey = useAppStore((s) => s.setPipelineErrorKey)
  const resetRecording = useAppStore((s) => s.resetRecording)
  const saveRecordings = useAppStore((s) => s.config.save_recordings)

  const showSavedHint = shouldShowSavedRecordingHint(pipelineErrorKey, saveRecordings)

  useEffect(() => {
    const timer = setTimeout(() => {
      setPipelineError(null)
      setPipelineErrorKey(null)
      // Only reset recording state if the pipeline is actually idle.
      // If the user started a new recording during the 2.5s error window,
      // don't overwrite the active pipeline state.
      const currentState = useAppStore.getState().pipelineState
      if (currentState === 'idle') {
        resetRecording()
      }
    }, 2500)
    return () => clearTimeout(timer)
  }, [setPipelineError, setPipelineErrorKey, resetRecording, pipelineError])

  return (
    <motion.div
      className={`relative z-10 flex items-center gap-2 px-3 ${showSavedHint ? 'py-2' : 'h-9'}`}
      initial={{ opacity: 0, x: -4 }}
      animate={{ opacity: 1, x: 0 }}
      transition={{ duration: 0.3, ease: 'easeOut' }}
    >
      {/* White dot */}
      <motion.div className="w-2 h-2 rounded-full bg-white/80 flex-shrink-0" />
      <div className="flex-1 min-w-0">
        <p className="text-[11px] text-white truncate">
          {pipelineError || t('capsule.errors.unknown')}
        </p>
        {showSavedHint && (
          <p className="text-[10px] text-white/70 truncate leading-tight">
            {t('capsule.errors.saved_hint')}
          </p>
        )}
      </div>
    </motion.div>
  )
}
