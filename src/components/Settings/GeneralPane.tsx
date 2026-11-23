import { useState, useCallback, useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { invoke } from '@tauri-apps/api/core'
import { Globe, Keyboard, MessageCircle, Mic, Monitor, SlidersHorizontal } from 'lucide-react'
import i18n from '../../i18n'
import { isMacPlatform, useAppStore } from '../../stores/appStore'
import type { HotkeyMode, OutputMode, Theme } from '../../stores/appStore'
import { useVoiceShortcuts, type VoiceShortcutRole } from '../../hooks/useVoiceShortcuts'
import {
  getPlatformCapabilities,
  getHotkeyStatus,
  resumeHotkey,
  startAskFlow,
} from '../../lib/tauri'
import type { HotkeyStatus } from '../../lib/tauri'
import { UI_LANGUAGES } from '../../lib/constants'
import { Select } from '../shared/Select'
import { SegmentedControl } from './shared/SegmentedControl'
import { SettingsNotice, SettingsRow, SettingsSection } from './shared/SettingsRow'
import { Toggle } from './shared/Toggle'
import { ShortcutKeycapInput } from './ShortcutKeycapInput'
import { MicrophoneSelect } from './MicrophoneSelect'
import { queueShortcutCaptureSave } from '../../hooks/useHotkeyCapture'
import { enqueueConfigSave } from './shared/useAutoSaveConfig'

const MAC_ACCESSIBILITY_HOTKEY_ERROR = 'Accessibility permission may be denied'

export function GeneralPane() {
  const config = useAppStore((s) => s.config)
  const updateConfig = useAppStore((s) => s.updateConfig)
  const platformCapabilities = useAppStore((s) => s.platformCapabilities)
  const setPlatformCapabilities = useAppStore((s) => s.setPlatformCapabilities)
  const hotkeyRegistrationError = useAppStore((s) => s.hotkeyRegistrationError)
  const setHotkeyRegistrationError = useAppStore((s) => s.setHotkeyRegistrationError)
  const accessibilityTrusted = useAppStore((s) => s.accessibilityTrusted)
  const { t } = useTranslation()
  const isMac = isMacPlatform()
  const [hotkeyStatus, setHotkeyStatus] = useState<HotkeyStatus | null>(null)
  const accessibilityRecoveryAttemptedRef = useRef(false)

  useEffect(() => {
    if (platformCapabilities) return
    getPlatformCapabilities()
      .then(setPlatformCapabilities)
      .catch((err) => {
        console.error('Failed to load platform capabilities:', err)
      })
  }, [platformCapabilities, setPlatformCapabilities])

  useEffect(() => {
    let cancelled = false
    getHotkeyStatus()
      .then((status) => {
        if (!cancelled) {
          setHotkeyStatus(status)
          setHotkeyRegistrationError(status.registration_error)
        }
      })
      .catch((err) => {
        console.error('Failed to load hotkey status:', err)
      })
    return () => {
      cancelled = true
    }
  }, [config.hotkeys, hotkeyRegistrationError, setHotkeyRegistrationError])

  useEffect(() => {
    if (
      !isMac ||
      !accessibilityTrusted ||
      !hotkeyRegistrationError?.includes(MAC_ACCESSIBILITY_HOTKEY_ERROR)
    ) {
      if (!hotkeyRegistrationError) {
        accessibilityRecoveryAttemptedRef.current = false
      }
      return
    }
    if (accessibilityRecoveryAttemptedRef.current) return
    accessibilityRecoveryAttemptedRef.current = true

    resumeHotkey()
      .then(() => {
        setHotkeyRegistrationError(null)
      })
      .catch((err) => {
        const message = err instanceof Error ? err.message : String(err)
        setHotkeyRegistrationError(message)
      })
  }, [accessibilityTrusted, hotkeyRegistrationError, isMac, setHotkeyRegistrationError])

  const handleOpenAsk = useCallback(() => {
    startAskFlow().catch((err) => {
      console.error('Failed to start Ask flow:', err)
    })
  }, [])

  const hotkeyStatusMessage = hotkeyStatus?.conflict
    ? t('settings.hotkeyConflict')
    : hotkeyStatus && (!hotkeyStatus.dictation.valid || !hotkeyStatus.ask.valid)
      ? t('settings.hotkeyInvalid')
      : null
  const registrationErrorCoveredByAccessibilityBanner = Boolean(
    isMac &&
    !accessibilityTrusted &&
    hotkeyRegistrationError?.includes('Accessibility permission may be denied'),
  )
  const { bindings, otherBindings, specialOptions, setBindings } = useVoiceShortcuts()
  const currentLanguage = config.ui_language || i18n.language || 'en'

  const shortcutRows: Array<{
    role: VoiceShortcutRole
    title: string
    description: string
    required?: boolean
  }> = [
    {
      role: 'dictation',
      title: t('shortcuts.dictateTitle'),
      description: t('shortcuts.dictateDesc'),
      required: true,
    },
    {
      role: 'translate',
      title: t('shortcuts.translateTitle'),
      description: t('shortcuts.translateDesc'),
    },
    { role: 'ask', title: t('shortcuts.askTitle'), description: t('shortcuts.askDesc') },
    {
      role: 'pasteLast',
      title: t('shortcuts.pasteLastTitle'),
      description: t('shortcuts.pasteLastDesc'),
    },
  ]

  return (
    <div>
      <SettingsSection icon={Keyboard} title={t('settings.hotkey')}>
        {shortcutRows.map((row) => (
          <SettingsRow key={row.role} title={row.title} description={row.description}>
            <div className="flex justify-end">
              <ShortcutKeycapInput
                label={row.title}
                bindings={bindings[row.role]}
                otherBindings={otherBindings(row.role)}
                required={row.required}
                maxBindings={row.role === 'pasteLast' ? 1 : 4}
                presets={specialOptions[row.role]}
                onChange={(next, recorded) => {
                  setBindings(row.role, next)
                  const save = recorded
                    ? queueShortcutCaptureSave(enqueueConfigSave)
                    : enqueueConfigSave()
                  void save.catch(() => {
                    console.error('Failed to persist recorded shortcut')
                  })
                }}
              />
            </div>
          </SettingsRow>
        ))}
        {platformCapabilities && !platformCapabilities.globalHotkeyReliable && (
          <SettingsNotice>{t('settings.waylandHotkeyLimited')}</SettingsNotice>
        )}
        {hotkeyRegistrationError && !registrationErrorCoveredByAccessibilityBanner && (
          <SettingsNotice tone="error">{t('settings.hotkeyRegistrationFailed')}</SettingsNotice>
        )}
        {hotkeyStatusMessage && <SettingsNotice>{hotkeyStatusMessage}</SettingsNotice>}
      </SettingsSection>

      <SettingsSection icon={Globe} title={t('generalPane.language')}>
        <SettingsRow
          title={t('generalPane.interfaceLanguage')}
          description={t('generalPane.interfaceLanguageDesc')}
        >
          <Select
            aria-label={t('generalPane.interfaceLanguage')}
            value={currentLanguage}
            options={UI_LANGUAGES.map((lang) => ({ value: lang.value, label: lang.label }))}
            onChange={(value) => {
              i18n.changeLanguage(value)
              localStorage.setItem('ui_language', value)
              updateConfig({ ui_language: value })
              invoke('refresh_tray_labels').catch(() => {})
            }}
          />
        </SettingsRow>
      </SettingsSection>

      <SettingsSection icon={Mic} title={t('generalPane.audio')}>
        <SettingsRow
          title={t('generalPane.microphone')}
          description={t('generalPane.microphoneDesc')}
        >
          <MicrophoneSelect />
        </SettingsRow>
        <SettingsRow
          title={t('generalPane.interactionSounds')}
          description={t('generalPane.interactionSoundsDesc')}
        >
          <div className="flex justify-end">
            <Toggle
              ariaLabel={t('generalPane.interactionSounds')}
              checked={config.interaction_sounds}
              onChange={(checked) => updateConfig({ interaction_sounds: checked })}
            />
          </div>
        </SettingsRow>
        {platformCapabilities?.os !== 'linux' && (
          <SettingsRow
            title={t('generalPane.muteWhenDictating')}
            description={t('generalPane.muteWhenDictatingDesc')}
          >
            <div className="flex justify-end">
              <Toggle
                ariaLabel={t('generalPane.muteWhenDictating')}
                checked={config.mute_background_audio}
                onChange={(checked) => updateConfig({ mute_background_audio: checked })}
              />
            </div>
          </SettingsRow>
        )}
      </SettingsSection>

      <SettingsSection icon={Monitor} title={t('generalPane.general')}>
        <SettingsRow
          title={t('generalPane.appearance')}
          description={t('generalPane.appearanceDesc')}
        >
          <SegmentedControl
            options={[
              { value: 'system', label: t('generalPane.appearanceSystem') },
              { value: 'light', label: t('generalPane.appearanceLight') },
              { value: 'dark', label: t('generalPane.appearanceDark') },
            ]}
            value={config.theme}
            onChange={(value) => updateConfig({ theme: value as Theme })}
          />
        </SettingsRow>
        <SettingsRow
          title={t('generalPane.launchAtLogin')}
          description={t('generalPane.launchAtLoginDesc')}
        >
          <div className="flex justify-end">
            <Toggle
              ariaLabel={t('generalPane.launchAtLogin')}
              checked={config.auto_start}
              onChange={(checked) => updateConfig({ auto_start: checked })}
            />
          </div>
        </SettingsRow>
        {isMac && (
          <SettingsRow
            title={t('generalPane.showInDock')}
            description={t('generalPane.showInDockDesc')}
          >
            <div className="flex justify-end">
              <Toggle
                ariaLabel={t('generalPane.showInDock')}
                checked={config.show_in_dock}
                onChange={(checked) => updateConfig({ show_in_dock: checked })}
              />
            </div>
          </SettingsRow>
        )}
      </SettingsSection>

      {/* OpenTypeless-only preferences that Typeless has no equivalent for. */}
      <SettingsSection icon={SlidersHorizontal} title={t('generalPane.input')}>
        <SettingsRow
          title={t('generalPane.triggerMode')}
          description={t('generalPane.triggerModeDesc')}
        >
          <SegmentedControl
            options={[
              { value: 'auto', label: t('settings.autoPressMode') },
              { value: 'toggle', label: t('settings.toggleOnOff') },
              { value: 'hold', label: t('settings.holdToTalk') },
            ]}
            value={config.hotkey_mode}
            onChange={(v) => updateConfig({ hotkey_mode: v as HotkeyMode })}
          />
        </SettingsRow>
        <SettingsRow
          title={t('generalPane.outputMode')}
          description={t('generalPane.outputModeDesc')}
        >
          <SegmentedControl
            options={[
              { value: 'keyboard', label: t('settings.keyboardSimulation') },
              { value: 'clipboard', label: t('settings.clipboardPaste') },
            ]}
            value={config.output_mode}
            onChange={(v) => {
              const outputMode = v as OutputMode
              updateConfig({
                output_mode: outputMode,
                insertion_strategy: outputMode === 'clipboard' ? 'clipboardPaste' : 'auto',
              })
            }}
          />
          {config.output_mode === 'clipboard' &&
            platformCapabilities &&
            !platformCapabilities.clipboardAutoPasteReliable && (
              <SettingsNotice>{t('settings.waylandClipboardCopyOnly')}</SettingsNotice>
            )}
        </SettingsRow>
        <SettingsRow
          title={t('generalPane.hideCapsule')}
          description={t('generalPane.hideCapsuleDesc')}
        >
          <div className="flex justify-end">
            <Toggle
              ariaLabel={t('generalPane.hideCapsule')}
              checked={config.capsule_auto_hide}
              onChange={(checked) => updateConfig({ capsule_auto_hide: checked })}
            />
          </div>
        </SettingsRow>
        <SettingsRow
          title={t('generalPane.closeToTray')}
          description={t('generalPane.closeToTrayDesc')}
        >
          <div className="flex justify-end">
            <Toggle
              ariaLabel={t('generalPane.closeToTray')}
              checked={config.close_to_tray}
              onChange={(checked) => updateConfig({ close_to_tray: checked })}
            />
          </div>
        </SettingsRow>
        <SettingsRow
          title={t('generalPane.startMinimized')}
          description={t('generalPane.startMinimizedDesc')}
        >
          <div className="flex justify-end">
            <Toggle
              ariaLabel={t('generalPane.startMinimized')}
              checked={config.start_minimized}
              onChange={(checked) => updateConfig({ start_minimized: checked })}
            />
          </div>
        </SettingsRow>
        <SettingsRow title={t('settings.saveHistory')} description={t('settings.saveHistoryDesc')}>
          <div className="flex justify-end">
            <Toggle
              ariaLabel={t('settings.saveHistory')}
              checked={config.history_enabled}
              onChange={(checked) => updateConfig({ history_enabled: checked })}
            />
          </div>
        </SettingsRow>
        <SettingsRow title={t('history.saveAudio')} description={t('history.saveAudioDescription')}>
          <div className="flex justify-end">
            <Toggle
              ariaLabel={t('history.saveAudio')}
              checked={config.save_recording_audio}
              onChange={(checked) => updateConfig({ save_recording_audio: checked })}
            />
          </div>
        </SettingsRow>
        {isMac && (
          <SettingsRow
            title={t('generalPane.autoLearnDictionary')}
            description={t('generalPane.autoLearnDictionaryDesc')}
          >
            <div className="flex justify-end">
              <Toggle
                ariaLabel={t('generalPane.autoLearnDictionary')}
                checked={config.auto_learn_dictionary}
                onChange={(checked) => updateConfig({ auto_learn_dictionary: checked })}
              />
            </div>
          </SettingsRow>
        )}
        <SettingsRow title={t('settings.tryAsk')} description={t('generalPane.tryAskDesc')}>
          <div className="flex justify-end">
            <button
              type="button"
              aria-label={t('settings.tryAsk')}
              onClick={handleOpenAsk}
              className="btn-outline flex h-9 cursor-pointer items-center gap-1.5 px-3 text-[13px]"
            >
              <MessageCircle size={14} />
              {t('settings.tryAsk')}
            </button>
          </div>
        </SettingsRow>
      </SettingsSection>
    </div>
  )
}
