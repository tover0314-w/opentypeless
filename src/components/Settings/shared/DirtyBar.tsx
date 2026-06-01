import { useTranslation } from 'react-i18next'
import { useState } from 'react'
import { motion } from 'framer-motion'
import { Loader2 } from 'lucide-react'
import { useAppStore } from '../../../stores/appStore'
import { updateConfig, setAutoStart } from '../../../lib/tauri'

type SaveResult = 'idle' | 'success' | 'error'

export function DirtyBar() {
  const { t } = useTranslation()
  const config = useAppStore((s) => s.config)
  const savedConfig = useAppStore((s) => s.savedConfig)
  const resetConfig = useAppStore((s) => s.resetConfig)
  const setSavedConfig = useAppStore((s) => s.setSavedConfig)
  const [saving, setSaving] = useState(false)
  const [saveResult, setSaveResult] = useState<SaveResult>('idle')
  const [errorMsg, setErrorMsg] = useState('')

  const handleSave = async () => {
    if (saving) return
    setSaving(true)
    setSaveResult('idle')
    setErrorMsg('')
    try {
      await updateConfig(config)
      // Sync system auto-start only when the value actually changed
      if (savedConfig?.auto_start !== config.auto_start) {
        await setAutoStart(config.auto_start)
      }
      setSavedConfig(config)
      setSaveResult('success')
      setTimeout(() => {
        setSaveResult('idle')
      }, 1500)
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Failed to save settings'
      setErrorMsg(msg)
      setSaveResult('error')
    } finally {
      setSaving(false)
    }
  }

  const handleReset = () => {
    setSaveResult('idle')
    setErrorMsg('')
    resetConfig()
  }

  const bgClass =
    saveResult === 'success'
      ? 'bg-success/10 border-t border-success/20'
      : saveResult === 'error'
        ? 'bg-error/10 border-t border-error/20'
        : 'bg-warning/10 border-t border-warning/20'

  const labelText =
    saveResult === 'success'
      ? t('common.save')
      : saveResult === 'error'
        ? errorMsg || t('common.connectionFail')
        : t('settings.unsavedChanges')

  const labelColor =
    saveResult === 'success'
      ? 'text-success'
      : saveResult === 'error'
        ? 'text-error'
        : 'text-warning'

  return (
    <motion.div
      className={`flex items-center justify-between px-5 py-3 ${bgClass}`}
      initial={{ y: 20, opacity: 0 }}
      animate={{ y: 0, opacity: 1 }}
      exit={{ y: 20, opacity: 0 }}
      transition={{ type: 'spring', stiffness: 400, damping: 30 }}
    >
      <span className={`${labelColor} text-[13px] truncate mr-3`}>{labelText}</span>
      {saveResult !== 'success' && (
        <div className="flex items-center gap-2 flex-shrink-0">
          <button
            onClick={handleReset}
            disabled={saving}
            className="px-3 py-1.5 text-[12px] text-text-secondary hover:text-text-primary bg-transparent border-none cursor-pointer rounded-[10px] hover:bg-bg-tertiary transition-colors disabled:opacity-50"
          >
            Reset
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className="flex items-center gap-1.5 px-3 py-1.5 text-[12px] text-white bg-accent rounded-[10px] border-none cursor-pointer hover:opacity-90 transition-opacity disabled:opacity-70"
          >
            {saving && (
              <motion.div
                animate={{ rotate: 360 }}
                transition={{ repeat: Infinity, duration: 0.8, ease: 'linear' }}
              >
                <Loader2 size={12} />
              </motion.div>
            )}
            {saving ? t('common.saving') : t('common.save')}
          </button>
        </div>
      )}
    </motion.div>
  )
}
