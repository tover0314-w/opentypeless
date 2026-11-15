import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { openUrl } from '@tauri-apps/plugin-opener'
import { APP_NAME } from '../../lib/constants'
import { useAppVersion } from '../../hooks/useAppVersion'
import {
  checkForUpdates,
  installAndRestart,
  useUpdaterStore,
  type UpdateError,
} from '../../lib/appUpdater'
import { siteUrl } from '../../lib/siteLinks'

const AUTO_RESTART_MS = 3000

const BUTTON = 'h-10 cursor-pointer rounded-full px-4 text-[16px] font-normal leading-none'

export function CheckUpdateDialog({ onClose }: { onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const { phase, latestVersion, progress, error } = useUpdaterStore()
  const current = useAppVersion()
  const [restartFailed, setRestartFailed] = useState(false)
  const panel = useRef<HTMLDivElement>(null)
  const active = useRef(false)
  const closeRef = useRef(onClose)
  closeRef.current = onClose

  // Before paint, so a stale background result never flashes.
  useLayoutEffect(() => {
    void checkForUpdates()
  }, [])

  useEffect(() => {
    active.current = true
    panel.current?.focus()
    const keydown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      event.stopImmediatePropagation()
      closeRef.current()
    }
    window.addEventListener('keydown', keydown, true)
    return () => {
      active.current = false
      window.removeEventListener('keydown', keydown, true)
    }
  }, [])

  const restart = () => {
    installAndRestart().catch(() => {
      if (!active.current) return
      console.error('Failed to install update')
      setRestartFailed(true)
    })
  }

  // Typeless restarts on its own three seconds after the download lands.
  useEffect(() => {
    if (phase !== 'downloaded' || restartFailed) return
    const timer = window.setTimeout(restart, AUTO_RESTART_MS)
    return () => window.clearTimeout(timer)
  }, [phase, restartFailed])

  const showError = phase === 'error' || restartFailed
  const title = showError
    ? t('updates.check.titleError')
    : phase === 'downloading'
      ? t('updates.check.titleAvailable')
      : phase === 'downloaded'
        ? t('updates.check.titleDownloaded')
        : phase === 'up-to-date'
          ? t('updates.check.titleUpToDate')
          : t('updates.check.titleChecking', { app: APP_NAME })

  return createPortal(
    <div className="fixed inset-0 z-[110] flex items-center justify-center bg-black/25 p-4 backdrop-blur-[3px]">
      <div className="fixed inset-0" onClick={onClose} />
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        data-update-phase={showError ? 'error' : phase}
        className="dialog-surface relative max-h-[calc(100dvh-32px)] w-full max-w-[460px] overflow-y-auto rounded-[20px] bg-bg-elevated text-text-primary outline-none"
      >
        <button
          type="button"
          aria-label={t('common.close')}
          onClick={onClose}
          className="absolute right-3 top-3 grid h-6 w-6 cursor-pointer place-items-center rounded-full border-none bg-transparent text-text-secondary hover:bg-fill-hover hover:text-text-primary"
        >
          <X size={16} />
        </button>
        <h2 className="m-0 px-6 pb-4 pt-6 text-[18px] font-medium leading-[1.4]">{title}</h2>
        <div className="px-6 pb-4">
          {showError ? (
            <ErrorBody
              error={restartFailed ? { kind: 'other', message: t('updates.error') } : error}
              language={i18n.language}
            />
          ) : phase === 'downloading' ? (
            <div className="flex flex-col gap-4">
              <p className="m-0 text-[14px] leading-[1.5] text-text-tertiary">
                {t('updates.check.downloadingText', {
                  app: APP_NAME,
                  latest: latestVersion ?? '',
                  current,
                })}
              </p>
              <ProgressBar value={progress} />
            </div>
          ) : phase === 'downloaded' ? (
            <p className="m-0 text-[14px] leading-[22px] text-text-tertiary">
              {t('updates.check.downloadedText')}
            </p>
          ) : phase === 'up-to-date' ? (
            <p className="m-0 text-[14px] leading-[1.5] text-text-tertiary">
              {t('updates.check.upToDateText', { app: APP_NAME, current })}
            </p>
          ) : (
            <div className="flex flex-col gap-4">
              <p className="m-0 text-[14px] leading-[1.5] text-text-tertiary">
                {t('updates.check.checkingText')}
              </p>
              <ProgressBar value={null} />
            </div>
          )}
        </div>
        <div className="flex justify-end gap-3 px-6 pb-6">
          {showError ? (
            <>
              <button type="button" onClick={onClose} className={`btn-outline ${BUTTON}`}>
                {t('common.cancel')}
              </button>
              <button
                type="button"
                onClick={() => {
                  setRestartFailed(false)
                  void checkForUpdates()
                }}
                className={`btn-primary ${BUTTON}`}
              >
                {t('common.retry')}
              </button>
            </>
          ) : phase === 'downloaded' ? (
            <button type="button" onClick={restart} className={`btn-primary ${BUTTON}`}>
              {t('updates.check.restart')}
            </button>
          ) : phase === 'up-to-date' ? (
            <button type="button" onClick={onClose} className={`btn-primary ${BUTTON}`}>
              {t('updates.check.gotIt')}
            </button>
          ) : (
            <button type="button" onClick={onClose} className={`btn-outline ${BUTTON}`}>
              {t('common.cancel')}
            </button>
          )}
        </div>
      </div>
    </div>,
    document.body,
  )
}

function ProgressBar({ value }: { value: number | null }) {
  return (
    <div
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={value ?? undefined}
      className="relative h-2 w-full overflow-hidden rounded-full bg-fill-tough"
    >
      {value === null ? (
        <div className="update-progress-indeterminate absolute inset-y-0 rounded-full bg-accent" />
      ) : (
        <div
          className="h-full rounded-full bg-accent transition-[width] duration-300"
          style={{ width: `${value}%` }}
        />
      )}
    </div>
  )
}

function ErrorBody({ error, language }: { error: UpdateError | null; language: string }) {
  const { t } = useTranslation()
  if (!error) return null
  if (error.kind === 'read_only') {
    return (
      <div className="text-[14px] leading-[1.5] text-error">
        <p className="m-0">{t('updates.check.errors.readOnlyTitle')}</p>
        <p className="m-0 mt-4">{t('updates.check.errors.readOnlyFix')}</p>
        <p className="m-0">{t('updates.check.errors.readOnlyStep1')}</p>
        <p className="m-0">{t('updates.check.errors.readOnlyStep2')}</p>
        <p className="m-0">{t('updates.check.errors.readOnlyStep3')}</p>
      </div>
    )
  }
  if (error.kind === 'code_signing') {
    const download = siteUrl('download', language)
    return (
      <div className="flex flex-col gap-2 text-[14px] leading-[1.5] text-error">
        <p className="m-0">{t('updates.check.errors.codeSigning')}</p>
        <p className="m-0">
          {t('updates.check.errors.downloadLabel')}
          <button
            type="button"
            onClick={() => openUrl(download)}
            className="cursor-pointer border-none bg-transparent p-0 text-[14px] text-error underline"
          >
            {download}
          </button>
        </p>
      </div>
    )
  }
  const message =
    error.kind === 'development'
      ? t('updates.check.errors.development')
      : error.kind === 'network'
        ? t('updates.check.errors.network')
        : error.message
  return <p className="m-0 break-words text-[14px] leading-[1.5] text-error">{message}</p>
}
