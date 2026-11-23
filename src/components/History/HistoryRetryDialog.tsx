import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { useAppStore, type HistoryEntry } from '../../stores/appStore'
import { STT_PROVIDERS } from '../../lib/constants'
import { cancelTranscriptionRetry } from '../../lib/tauri'
import { historyRetryErrorKey } from '../../lib/historyRetryError'

const SUPPORTED_PROVIDERS = new Set([
  'apple-speech',
  'custom-whisper',
  'openai-whisper',
  'groq-whisper',
  'glm-asr',
  'siliconflow',
])

export function HistoryRetryDialog({
  entry,
  onClose,
  onRetry,
}: {
  entry: HistoryEntry
  onClose: () => void
  onRetry: () => Promise<string>
}) {
  const { t } = useTranslation()
  const provider = useAppStore((state) => state.config.stt_provider)
  const pipelineState = useAppStore((state) => state.pipelineState)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<string | null>(null)
  const [cancelled, setCancelled] = useState(false)
  const [cancelPending, setCancelPending] = useState(false)
  const cancelPendingRef = useRef(false)
  const active = useRef(true)
  const cancellationRequested = useRef(false)
  const heading = useRef<HTMLHeadingElement>(null)
  const dialog = useRef<HTMLDivElement>(null)
  const pendingRef = useRef(false)
  const retryRequestId = useRef(0)
  const closeRef = useRef(onClose)
  closeRef.current = onClose

  const cancel = () => {
    if (cancellationRequested.current) return
    cancellationRequested.current = true
    cancelPendingRef.current = true
    setCancelPending(true)
    const requestId = retryRequestId.current
    setCancelled(true)
    void cancelTranscriptionRetry()
      .catch((failure) => {
        if (!active.current || requestId !== retryRequestId.current) return
        cancellationRequested.current = false
        setCancelled(false)
        setError(t(historyRetryErrorKey(failure)))
      })
      .finally(() => {
        cancelPendingRef.current = false
        if (active.current && requestId === retryRequestId.current) setCancelPending(false)
      })
  }
  const cancelRef = useRef(cancel)
  cancelRef.current = cancel
  const supported = SUPPORTED_PROVIDERS.has(provider)
  const providerKey = STT_PROVIDERS.find((item) => item.value === provider)?.labelKey

  useEffect(() => {
    const previousFocus = document.activeElement as HTMLElement | null
    active.current = true
    heading.current?.focus()
    const keydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopImmediatePropagation()
        if (pendingRef.current) cancelRef.current()
        else closeRef.current()
      }
      if (event.key === 'Tab') {
        const buttons = dialog.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')
        if (!buttons?.length) return
        const first = buttons[0]
        const last = buttons[buttons.length - 1]
        if (
          event.shiftKey &&
          (document.activeElement === first || document.activeElement === heading.current)
        ) {
          event.preventDefault()
          last.focus()
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault()
          first.focus()
        }
      }
    }
    window.addEventListener('keydown', keydown, true)
    return () => {
      active.current = false
      retryRequestId.current += 1
      window.removeEventListener('keydown', keydown, true)
      if (pendingRef.current && !cancellationRequested.current) {
        void cancelTranscriptionRetry().catch(() => {})
      }
      if (previousFocus?.isConnected) previousFocus.focus()
    }
  }, [])

  const retry = async () => {
    if (pendingRef.current || cancelPendingRef.current || !supported || pipelineState !== 'idle')
      return
    const requestId = ++retryRequestId.current
    setPending(true)
    pendingRef.current = true
    setError(null)
    setResult(null)
    setCancelled(false)
    cancellationRequested.current = false
    try {
      const text = await onRetry()
      if (
        active.current &&
        requestId === retryRequestId.current &&
        !cancellationRequested.current
      ) {
        setResult(text)
      }
    } catch (failure) {
      if (
        active.current &&
        requestId === retryRequestId.current &&
        !cancellationRequested.current
      ) {
        setError(t(historyRetryErrorKey(failure)))
      }
    } finally {
      if (requestId === retryRequestId.current) {
        pendingRef.current = false
        if (active.current) setPending(false)
      }
    }
  }

  return createPortal(
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/40 p-6">
      <div
        ref={dialog}
        role="dialog"
        aria-modal="true"
        aria-label={t('history.retryRecording')}
        className="w-full max-w-[460px] rounded-[12px] bg-bg-elevated p-6 text-text-primary shadow-float"
      >
        <h2 ref={heading} tabIndex={-1} className="m-0 text-[20px] font-medium outline-none">
          {t('history.retryRecording')}
        </h2>
        <p className="text-[14px] leading-6 text-text-secondary">{t('history.retryDescription')}</p>
        <p className="text-[13px] text-text-tertiary">
          {t('settings.provider')}: {providerKey ? t(providerKey) : provider}
        </p>
        {!supported && (
          <p role="alert" className="text-[13px] text-warning">
            {t('history.retryUnsupported')}
          </p>
        )}
        {!pending && pipelineState !== 'idle' && (
          <p className="text-[13px] text-warning">{t('history.retryBusy')}</p>
        )}
        {error && !cancelled && (
          <p role="alert" className="break-words text-[13px] text-error">
            {error}
          </p>
        )}
        {result && !cancelled && (
          <div role="status" className="rounded-[8px] bg-fill-thin p-3">
            <p className="text-[13px] text-success">{t('capsule.alerts.retryCopied')}</p>
            <p className="max-h-48 overflow-y-auto whitespace-pre-wrap text-[14px]">{result}</p>
          </div>
        )}
        {cancelled && (
          <p role="status" className="text-[13px] text-text-secondary">
            {t('history.retryCancelled')}
          </p>
        )}
        <div className="mt-5 flex justify-end gap-2">
          {pending ? (
            <button
              type="button"
              disabled={cancelled}
              onClick={cancel}
              className="btn-outline h-9 px-4 text-[13px] disabled:opacity-40"
            >
              {t('common.cancel')}
            </button>
          ) : (
            <button type="button" onClick={onClose} className="btn-outline h-9 px-4 text-[13px]">
              {t('common.close')}
            </button>
          )}
          <button
            type="button"
            disabled={
              pending || cancelPending || !supported || pipelineState !== 'idle' || !entry.has_audio
            }
            onClick={() => void retry()}
            className="btn-primary h-9 px-4 text-[13px] disabled:opacity-40"
          >
            {t(pending ? 'capsule.thinking' : 'capsule.alerts.retryAndCopy')}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}
