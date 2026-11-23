import { useTranslation } from 'react-i18next'
import { useAppStore, type HotkeyMode, type OutputMode } from '../../stores/appStore'
import { Select } from '../shared/Select'
import { MicrophoneSelect } from '../Settings/MicrophoneSelect'
import { useAutoSaveConfig } from '../Settings/shared/useAutoSaveConfig'

export function QuickSettings() {
  const { t } = useTranslation()
  const config = useAppStore((state) => state.config)
  const updateConfig = useAppStore((state) => state.updateConfig)
  const capabilities = useAppStore((state) => state.platformCapabilities)
  useAutoSaveConfig()

  return (
    <div className="space-y-3 border-b border-border-thin pb-3">
      <div className="space-y-1.5">
        <div className="text-[12px] text-text-tertiary">{t('generalPane.microphone')}</div>
        <MicrophoneSelect followSettingsRoute={false} />
      </div>
      <div className="space-y-1.5">
        <div className="text-[12px] text-text-tertiary">{t('generalPane.triggerMode')}</div>
        <Select
          aria-label={t('generalPane.triggerMode')}
          value={config.hotkey_mode}
          options={[
            { value: 'auto', label: t('settings.autoPressMode') },
            { value: 'toggle', label: t('settings.toggleOnOff') },
            { value: 'hold', label: t('settings.holdToTalk') },
          ]}
          onChange={(value) => updateConfig({ hotkey_mode: value as HotkeyMode })}
        />
      </div>
      <div className="space-y-1.5">
        <div className="text-[12px] text-text-tertiary">{t('generalPane.outputMode')}</div>
        <Select
          aria-label={t('generalPane.outputMode')}
          value={config.output_mode}
          options={[
            { value: 'keyboard', label: t('settings.keyboardSimulation') },
            { value: 'clipboard', label: t('settings.clipboardPaste') },
          ]}
          onChange={(value) => {
            const mode = value as OutputMode
            updateConfig({
              output_mode: mode,
              insertion_strategy: mode === 'clipboard' ? 'clipboardPaste' : 'auto',
            })
          }}
        />
        {config.output_mode === 'clipboard' &&
          capabilities &&
          !capabilities.clipboardAutoPasteReliable && (
            <p className="m-0 text-[12px] leading-4 text-text-tertiary">
              {t('settings.waylandClipboardCopyOnly')}
            </p>
          )}
      </div>
    </div>
  )
}
