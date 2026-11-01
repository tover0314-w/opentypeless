import { useState, useCallback, useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronDown, MessageCircle } from 'lucide-react'
import { isMacPlatform, useAppStore } from '../../stores/appStore'
import type { HotkeyMode, OutputMode } from '../../stores/appStore'
import { useVoiceShortcuts } from '../../hooks/useVoiceShortcuts'
import {
  getPlatformCapabilities,
  getHotkeyStatus,
  resumeHotkey,
  startAskFlow,
} from '../../lib/tauri'
import type { HotkeyStatus } from '../../lib/tauri'
import { SegmentedControl } from './shared/SegmentedControl'
import { Toggle } from './shared/Toggle'
import { ShortcutBindingList } from './ShortcutBindingList'
import { MicrophoneSelect } from './MicrophoneSelect'

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
  const [advancedOpen, setAdvancedOpen] = useState(false)
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

  return (
    <div className="space-y-6">
      <Section title={t('settings.hotkey')}>
        <div className="space-y-3">
          <ShortcutBindingList
            role="dictation"
            label={t('settings.dictationHotkey')}
            bindings={bindings.dictation}
            otherBindings={otherBindings('dictation')}
            required
            specialOptions={specialOptions.dictation}
            onChange={(next) => setBindings('dictation', next)}
          />
          <ShortcutBindingList
            role="ask"
            label={t('settings.askHotkey')}
            bindings={bindings.ask}
            otherBindings={otherBindings('ask')}
            required={false}
            specialOptions={specialOptions.ask}
            onChange={(next) => setBindings('ask', next)}
            trailingAction={
              <button
                type="button"
                aria-label={t('settings.tryAsk')}
                title={t('settings.tryAsk')}
                onClick={handleOpenAsk}
                className="grid h-7 w-7 place-items-center rounded-[6px] border border-transparent bg-bg-secondary text-text-tertiary hover:border-border hover:text-text-primary"
              >
                <MessageCircle size={13} />
              </button>
            }
          />
          <ShortcutBindingList
            role="translate"
            label={t('settings.translateHotkey')}
            bindings={bindings.translate}
            otherBindings={otherBindings('translate')}
            required={false}
            specialOptions={specialOptions.translate}
            onChange={(next) => setBindings('translate', next)}
          />
        </div>
        {platformCapabilities && !platformCapabilities.globalHotkeyReliable && (
          <p className="mt-2 rounded-[8px] border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-[12px] leading-relaxed text-text-secondary">
            {t('settings.waylandHotkeyLimited')}
          </p>
        )}
        {hotkeyRegistrationError && !registrationErrorCoveredByAccessibilityBanner && (
          <p className="mt-2 rounded-[8px] border border-error/30 bg-error/10 px-3 py-2 text-[12px] leading-relaxed text-error">
            {t('settings.hotkeyRegistrationFailed')}
          </p>
        )}
        {hotkeyStatusMessage && (
          <p className="mt-2 rounded-[8px] border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-[12px] leading-relaxed text-text-secondary">
            {hotkeyStatusMessage}
          </p>
        )}
      </Section>

      <Section title={t('settings.dictationMode')}>
        <SegmentedControl
          options={[
            { value: 'auto', label: t('settings.autoPressMode') },
            { value: 'toggle', label: t('settings.toggleOnOff') },
            { value: 'hold', label: t('settings.holdToTalk') },
          ]}
          value={config.hotkey_mode}
          onChange={(v) => updateConfig({ hotkey_mode: v as HotkeyMode })}
        />
      </Section>

      <Section title={t('settings.outputMode')}>
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
            <p className="mt-2 rounded-[8px] border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-[12px] leading-relaxed text-text-secondary">
              {t('settings.waylandClipboardCopyOnly')}
            </p>
          )}
      </Section>

      <Section title={t('settings.audio')}>
        <div className="space-y-3">
          <MicrophoneSelect />
          <Toggle
            checked={config.interaction_sounds}
            onChange={(checked) => updateConfig({ interaction_sounds: checked })}
            label={t('settings.interactionSounds')}
          />
          {platformCapabilities?.os !== 'linux' && (
            <Toggle
              checked={config.mute_background_audio}
              onChange={(checked) => updateConfig({ mute_background_audio: checked })}
              label={t('settings.muteBackgroundAudio')}
            />
          )}
        </div>
      </Section>

      <div>
        <button
          type="button"
          aria-expanded={advancedOpen}
          onClick={() => setAdvancedOpen((open) => !open)}
          className="flex w-full items-center justify-between rounded-[10px] border border-border bg-bg-secondary/40 px-3 py-2 text-[13px] font-medium text-text-primary transition-colors hover:border-border-focus"
        >
          <span>{t('settings.advancedGeneral')}</span>
          <ChevronDown
            size={14}
            className={`text-text-tertiary transition-transform ${advancedOpen ? 'rotate-180' : ''}`}
          />
        </button>

        {advancedOpen && (
          <div className="mt-4 space-y-3">
            <Toggle
              checked={config.auto_start}
              onChange={(checked) => updateConfig({ auto_start: checked })}
              label={t('settings.launchAtStartup')}
            />
            <Toggle
              checked={config.start_minimized}
              onChange={(checked) => updateConfig({ start_minimized: checked })}
              label={t('settings.startMinimized')}
            />
            <Toggle
              checked={config.history_enabled}
              onChange={(checked) => updateConfig({ history_enabled: checked })}
              label={t('settings.saveHistory')}
            />
            <Toggle
              checked={config.capsule_auto_hide}
              onChange={(checked) => updateConfig({ capsule_auto_hide: checked })}
              label={t('settings.hideCapsuleWhenIdle')}
            />
          </div>
        )}
      </div>
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h3 className="text-[11px] font-medium text-text-tertiary uppercase tracking-wider mb-2.5">
        {title}
      </h3>
      {children}
    </div>
  )
}
