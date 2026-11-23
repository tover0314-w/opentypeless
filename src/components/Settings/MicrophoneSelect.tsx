import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, ChevronDown, Mic, RefreshCw, X } from 'lucide-react'
import { createPortal } from 'react-dom'
import { useAppStore } from '../../stores/appStore'
import {
  getMicrophonePreviewLevel,
  listMicrophones,
  startMicrophonePreview,
  stopMicrophonePreview,
  type MicrophoneInfo,
} from '../../lib/tauri'

const CONNECTION_KEYS: Record<MicrophoneInfo['connection'], string> = {
  builtIn: 'settings.micBuiltIn',
  usb: 'settings.micUsb',
  bluetooth: 'settings.micBluetooth',
  virtual: 'settings.micVirtual',
  other: 'settings.micOther',
}

export function MicrophoneSelect({
  followSettingsRoute = true,
}: { followSettingsRoute?: boolean } = {}) {
  const { t } = useTranslation()
  const selected = useAppStore((state) => state.config.microphone_device)
  const [open, setOpen] = useState(false)
  const trigger = useRef<HTMLButtonElement>(null)
  const lastRequest = useRef<string | null>(null)

  useEffect(() => {
    if (!followSettingsRoute) return
    const openRequestedDialog = () => {
      const [path, query] = window.location.hash.split('?')
      if (path !== '#/settings') return
      const params = new URLSearchParams(query)
      if (params.get('dialog') !== 'microphone') return
      const request = params.get('open') ?? window.location.hash
      if (lastRequest.current === request) return
      lastRequest.current = request
      setOpen(true)
    }
    openRequestedDialog()
    window.addEventListener('hashchange', openRequestedDialog)
    return () => window.removeEventListener('hashchange', openRequestedDialog)
  }, [followSettingsRoute])

  const close = () => {
    setOpen(false)
    const [path, query] = window.location.hash.split('?')
    const params = new URLSearchParams(query)
    if (followSettingsRoute && path === '#/settings' && params.get('dialog') === 'microphone') {
      params.delete('dialog')
      params.delete('open')
      const search = params.toString()
      window.history.replaceState(null, '', `${path}${search ? `?${search}` : ''}`)
    }
    trigger.current?.focus()
  }

  return (
    <>
      <button
        ref={trigger}
        type="button"
        aria-label={t('settings.microphone')}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen(true)}
        className="field-control flex items-center justify-between gap-2 text-left hover:bg-fill-hover"
      >
        <span className="min-w-0 truncate">{selected || t('settings.micAutoDetect')}</span>
        <ChevronDown size={16} className="shrink-0 text-text-tertiary" />
      </button>
      {open && createPortal(<MicrophoneDialog onClose={close} />, document.body)}
    </>
  )
}

function MicrophoneDialog({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation()
  const selected = useAppStore((state) => state.config.microphone_device)
  const updateConfig = useAppStore((state) => state.updateConfig)
  const busy = useAppStore((state) => state.pipelineState !== 'idle')
  const [draft, setDraft] = useState(selected)
  const [devices, setDevices] = useState<MicrophoneInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [previewError, setPreviewError] = useState(false)
  const [level, setLevel] = useState(0)
  const [refresh, setRefresh] = useState(0)
  const dialogRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let active = true
    setLoading(true)
    setLoadError(false)
    listMicrophones()
      .then((listed) => {
        if (active) setDevices(listed)
      })
      .catch(() => {
        if (active) setLoadError(true)
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [refresh])

  const available = !loading && !loadError && devices.length > 0
  const missing = Boolean(draft) && available && !devices.some((device) => device.name === draft)

  useEffect(() => {
    setLevel(0)
    setPreviewError(false)
    if (!available || busy || missing) return
    let active = true
    let session: number | null = null
    let timer: ReturnType<typeof setTimeout> | undefined
    const stop = () => {
      if (session !== null) {
        void stopMicrophonePreview(session).catch(() => {})
        session = null
      }
    }
    const poll = async () => {
      if (!active || session === null) return
      try {
        const volume = await getMicrophonePreviewLevel(session)
        if (!active) return
        setLevel(Number.isFinite(volume) ? Math.min(1, Math.max(0, volume)) : 0)
        timer = setTimeout(poll, 100)
      } catch {
        if (active) {
          setPreviewError(true)
          setLevel(0)
        }
        stop()
      }
    }
    startMicrophonePreview(draft)
      .then((startedSession) => {
        if (!active) {
          void stopMicrophonePreview(startedSession).catch(() => {})
          return
        }
        session = startedSession
        void poll()
      })
      .catch(() => {
        if (active) setPreviewError(true)
      })
    return () => {
      active = false
      if (timer) clearTimeout(timer)
      stop()
    }
  }, [available, busy, draft, missing, refresh])

  useEffect(() => {
    dialogRef.current?.focus()
    const keydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopImmediatePropagation()
        onClose()
      }
      if (event.key === 'Tab') {
        const controls = dialogRef.current?.querySelectorAll<HTMLElement>(
          'button:not(:disabled):not([tabindex="-1"]), [tabindex="0"]',
        )
        if (!controls?.length) return
        const first = controls[0]
        const last = controls[controls.length - 1]
        if (
          event.shiftKey &&
          (document.activeElement === first || document.activeElement === dialogRef.current)
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
    return () => window.removeEventListener('keydown', keydown, true)
  }, [onClose])

  const choices: MicrophoneInfo[] = [{ name: '', isDefault: true, connection: 'other' }, ...devices]

  return (
    <div
      className="fixed inset-0 z-[100] flex items-center justify-center bg-black/40 p-6"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label={t('microphoneDialog.title')}
        tabIndex={-1}
        className="w-full max-w-[460px] rounded-[12px] bg-bg-elevated p-6 text-text-primary shadow-float outline-none"
      >
        <div className="mb-5 flex items-center justify-between gap-4">
          <h2 className="m-0 text-[20px] font-medium">{t('microphoneDialog.title')}</h2>
          <button
            type="button"
            aria-label={t('common.close')}
            onClick={onClose}
            className="btn-ghost grid h-7 w-7 place-items-center"
          >
            <X size={18} />
          </button>
        </div>
        <div
          className="max-h-[260px] space-y-1 overflow-y-auto"
          role="radiogroup"
          aria-label={t('settings.microphone')}
        >
          {choices.map((device, index) => (
            <button
              key={device.name}
              type="button"
              role="radio"
              aria-checked={draft === device.name}
              tabIndex={draft === device.name || (missing && index === 0) ? 0 : -1}
              aria-label={device.name || t('settings.micAutoDetect')}
              onClick={() => setDraft(device.name)}
              onKeyDown={(event) => {
                if (event.nativeEvent.isComposing) return
                const delta =
                  event.key === 'ArrowDown' || event.key === 'ArrowRight'
                    ? 1
                    : event.key === 'ArrowUp' || event.key === 'ArrowLeft'
                      ? -1
                      : 0
                if (!delta) return
                event.preventDefault()
                const nextIndex = (index + delta + choices.length) % choices.length
                setDraft(choices[nextIndex].name)
                const radios =
                  event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>(
                    '[role="radio"]',
                  )
                radios?.[nextIndex]?.focus()
              }}
              className={`flex min-h-12 w-full items-center gap-3 rounded-[8px] border-none px-3 py-2 text-left ${draft === device.name ? 'bg-fill-selected' : 'bg-transparent hover:bg-fill-hover'}`}
            >
              <Mic size={18} className="shrink-0 text-text-secondary" />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[14px]">
                  {device.name || t('settings.micAutoDetect')}
                </span>
                <span className="block text-[12px] text-text-tertiary">
                  {device.name
                    ? t(CONNECTION_KEYS[device.connection])
                    : t('microphoneDialog.systemDefault')}
                </span>
              </span>
              {draft === device.name && <Check size={16} className="shrink-0" />}
            </button>
          ))}
        </div>
        {loading && (
          <p className="text-[12px] text-text-tertiary">{t('microphoneDialog.loading')}</p>
        )}
        {!loading && !loadError && devices.length === 0 && (
          <p className="text-[12px] text-text-tertiary">{t('microphoneDialog.empty')}</p>
        )}
        {loadError && (
          <p role="alert" className="text-[12px] text-error">
            {t('microphoneDialog.loadError')}
          </p>
        )}
        {missing && (
          <p role="alert" className="text-[12px] text-warning">
            {t('settings.micUnavailable')}
          </p>
        )}
        <div className="mt-5 rounded-[8px] bg-fill-thin p-3">
          <div
            role="meter"
            aria-label={t('microphoneDialog.level')}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(level * 100)}
            className="h-2 overflow-hidden rounded-full bg-fill-tough"
          >
            <div
              className="h-full rounded-full bg-accent transition-[width] duration-100"
              style={{ width: `${level * 100}%` }}
            />
          </div>
          <p className="mb-0 mt-2 text-[12px] leading-4 text-text-tertiary">
            {t(
              busy
                ? 'microphoneDialog.busy'
                : previewError
                  ? 'microphoneDialog.previewError'
                  : 'microphoneDialog.hint',
            )}
          </p>
        </div>
        <div className="mt-5 flex items-center justify-between gap-3">
          <button
            type="button"
            aria-label={t('settings.micRefresh')}
            disabled={loading}
            onClick={() => setRefresh((value) => value + 1)}
            className="btn-ghost grid h-9 w-9 place-items-center disabled:opacity-40"
          >
            <RefreshCw size={16} />
          </button>
          <div className="flex gap-2">
            <button
              type="button"
              aria-label={t('microphoneDialog.cancel')}
              onClick={onClose}
              className="btn-outline h-9 px-4 text-[13px]"
            >
              {t('microphoneDialog.cancel')}
            </button>
            <button
              type="button"
              disabled={!available || missing}
              onClick={() => {
                updateConfig({ microphone_device: draft })
                onClose()
              }}
              className="btn-primary h-9 px-4 text-[13px] disabled:opacity-40"
            >
              {t('microphoneDialog.save')}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
