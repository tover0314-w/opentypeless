import { useEffect, useRef, useState } from 'react'
import { X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import {
  checkAndDownloadSilently,
  dismissUpdateNotice,
  installAndRestart,
  useUpdaterStore,
} from '../lib/appUpdater'

/**
 * Typeless checks quietly on launch and whenever the main window regains focus, downloads in the
 * background, then offers a top-right "Update available — Restart" card.
 */
export function UpdatePrompt() {
  const { t } = useTranslation()
  const notifyVersion = useUpdaterStore((s) => s.notifyVersion)
  const [failedVersion, setFailedVersion] = useState<string | null>(null)
  const active = useRef(false)
  const failed = failedVersion !== null && failedVersion === notifyVersion

  useEffect(() => {
    active.current = true
    void checkAndDownloadSilently()
    const onFocus = () => void checkAndDownloadSilently()
    window.addEventListener('focus', onFocus)
    return () => {
      active.current = false
      window.removeEventListener('focus', onFocus)
    }
  }, [])

  if (!notifyVersion) return null

  const restart = () => {
    setFailedVersion(null)
    installAndRestart().catch(() => {
      if (!active.current) return
      console.warn('Failed to install update')
      setFailedVersion(notifyVersion)
    })
  }

  return (
    <div
      role="status"
      aria-live="polite"
      className="update-notice fixed right-4 top-4 z-[9998] flex w-[min(400px,calc(100vw-32px))] flex-col gap-1 rounded-[16px] bg-bg-elevated p-4 shadow-float"
    >
      <h2 className="m-0 pr-6 text-[18px] font-medium leading-[1.5] text-text-primary">
        {t('updates.availableTitle')}
      </h2>
      <div className="flex items-center gap-2">
        <p
          className={`m-0 flex-1 text-[14px] leading-[22px] ${failed ? 'text-error' : 'text-text-secondary'}`}
        >
          {failed ? t('updates.error') : t('updates.check.downloadedText')}
        </p>
        <button
          type="button"
          onClick={restart}
          className="btn-outline h-10 shrink-0 cursor-pointer px-4 text-[16px] text-text-primary"
        >
          {t('updates.check.restart')}
        </button>
      </div>
      <button
        type="button"
        onClick={dismissUpdateNotice}
        aria-label={t('updates.dismiss')}
        className="absolute right-3 top-3 grid h-6 w-6 cursor-pointer place-items-center rounded-full border-none bg-transparent text-text-secondary hover:bg-fill-hover hover:text-text-primary"
      >
        <X size={16} />
      </button>
    </div>
  )
}
