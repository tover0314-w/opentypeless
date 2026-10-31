import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { RefreshCw } from 'lucide-react'
import { useAppStore } from '../../stores/appStore'
import { listMicrophones, type MicrophoneInfo } from '../../lib/tauri'

const CONNECTION_KEYS: Record<MicrophoneInfo['connection'], string> = {
  builtIn: 'settings.micBuiltIn',
  usb: 'settings.micUsb',
  bluetooth: 'settings.micBluetooth',
  virtual: 'settings.micVirtual',
  other: 'settings.micOther',
}

/** Typeless-style microphone picker: "Auto-detect" follows the system default device. */
export function MicrophoneSelect() {
  const { t } = useTranslation()
  const selected = useAppStore((s) => s.config.microphone_device)
  const updateConfig = useAppStore((s) => s.updateConfig)
  const [devices, setDevices] = useState<MicrophoneInfo[]>([])
  const [loading, setLoading] = useState(false)

  const refresh = useCallback(() => {
    setLoading(true)
    listMicrophones()
      .then(setDevices)
      .catch((error) => console.error('Failed to list microphones:', error))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  const missing = Boolean(selected) && !loading && !devices.some((d) => d.name === selected)

  return (
    <div className="space-y-1.5">
      <div className="flex items-center gap-2">
        <select
          aria-label={t('settings.microphone')}
          value={selected}
          onChange={(event) => updateConfig({ microphone_device: event.target.value })}
          className="h-9 min-w-0 flex-1 rounded-[8px] border border-border bg-bg-primary px-2.5 text-[13px] text-text-primary outline-none focus:border-border-focus"
        >
          <option value="">{t('settings.micAutoDetect')}</option>
          {devices.map((device) => (
            <option key={device.name} value={device.name}>
              {device.name} · {t(CONNECTION_KEYS[device.connection])}
              {device.isDefault ? ` · ${t('settings.micSystemDefault')}` : ''}
            </option>
          ))}
          {missing && <option value={selected}>{selected}</option>}
        </select>
        <button
          type="button"
          aria-label={t('settings.micRefresh')}
          title={t('settings.micRefresh')}
          onClick={refresh}
          className="grid h-9 w-9 shrink-0 place-items-center rounded-[8px] border border-border bg-bg-primary text-text-tertiary cursor-pointer hover:text-text-primary"
        >
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
        </button>
      </div>
      {missing && (
        <p className="m-0 text-[12px] leading-5 text-warning">{t('settings.micUnavailable')}</p>
      )}
    </div>
  )
}
