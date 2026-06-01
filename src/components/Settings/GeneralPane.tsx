import { useState, useCallback, useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../../stores/appStore'
import type { HotkeyMode, OutputMode } from '../../stores/appStore'
import {
  updateHotkey,
  pauseHotkey,
  resumeHotkey,
  checkAccessibilityPermission,
  requestAccessibilityPermission,
} from '../../lib/tauri'
import { SegmentedControl } from './shared/SegmentedControl'
import { Toggle } from './shared/Toggle'

// Keys that can be used as hotkeys without a modifier
const STANDALONE_KEYS = new Set([
  'Space',
  'Tab',
  'Enter',
  'Backspace',
  'Escape',
  'Delete',
  'Insert',
  'Home',
  'End',
  'PageUp',
  'PageDown',
  'Up',
  'Down',
  'Left',
  'Right',
  'F1',
  'F2',
  'F3',
  'F4',
  'F5',
  'F6',
  'F7',
  'F8',
  'F9',
  'F10',
  'F11',
  'F12',
])

function HotkeyRecorder() {
  const config = useAppStore((s) => s.config)
  const updateConfig = useAppStore((s) => s.updateConfig)
  const { t } = useTranslation()
  const [recording, setRecording] = useState(false)
  const [pending, setPending] = useState<string | null>(null)
  const [modifierHint, setModifierHint] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const autoConfirmTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const needsRestore = useRef(false)
  const platform = typeof navigator !== 'undefined' ? navigator.platform.toUpperCase() : ''
  const isWindows = platform.includes('WIN')
  const metaLabel = platform.includes('MAC') ? 'Cmd' : 'Win'

  const confirmHotkey = useCallback(
    (hotkey: string) => {
      needsRestore.current = false
      setRecording(false)
      setError(null)
      setModifierHint(null)
      updateHotkey(hotkey)
        .then(() => {
          updateConfig({ hotkey })
          setPending(null)
        })
        .catch((e) => {
          setError(String(e))
          setPending(null)
          resumeHotkey().catch(() => {})
        })
    },
    [updateConfig],
  )

  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      e.preventDefault()
      e.stopPropagation()

      // Build modifier prefix
      const parts: string[] = []
      if (e.ctrlKey) parts.push('Ctrl')
      if (e.altKey) parts.push('Alt')
      if (e.shiftKey) parts.push('Shift')
      if (e.metaKey) parts.push(metaLabel)

      const isModifierOnly = ['Control', 'Shift', 'Alt', 'Meta'].includes(e.key)
      const hasCtrlMeta = parts.includes('Ctrl') && parts.includes(metaLabel)

      // Windows Ctrl+Win is a valid pure-modifier hotkey handled by a native keyboard hook.
      if (isModifierOnly && isWindows && hasCtrlMeta && parts.length === 2) {
        setModifierHint(null)
        const combo = parts.join('+')
        setPending(combo)
        if (autoConfirmTimer.current) clearTimeout(autoConfirmTimer.current)
        autoConfirmTimer.current = setTimeout(() => {
          confirmHotkey(combo)
        }, 1500)
        return
      }

      // If only modifier keys are pressed, show hint like "Alt+..."
      if (isModifierOnly) {
        setModifierHint(parts.length > 0 ? parts.join('+') + '+...' : null)
        return
      }

      setModifierHint(null)

      const keyMap: Record<string, string> = {
        ' ': 'Space',
        Tab: 'Tab',
        Enter: 'Enter',
        Backspace: 'Backspace',
        Escape: 'Escape',
        Delete: 'Delete',
        Insert: 'Insert',
        Home: 'Home',
        End: 'End',
        PageUp: 'PageUp',
        PageDown: 'PageDown',
        ArrowUp: 'Up',
        ArrowDown: 'Down',
        ArrowLeft: 'Left',
        ArrowRight: 'Right',
      }

      let keyName = keyMap[e.key] || e.key
      if (keyName.length === 1) keyName = keyName.toUpperCase()

      // Letters and digits require at least one modifier to avoid interfering with typing
      if (parts.length === 0 && !STANDALONE_KEYS.has(keyName)) return

      parts.push(keyName)
      const combo = parts.join('+')
      setPending(combo)

      // Auto-confirm after 1.5 seconds
      if (autoConfirmTimer.current) clearTimeout(autoConfirmTimer.current)
      autoConfirmTimer.current = setTimeout(() => {
        confirmHotkey(combo)
      }, 1500)
    },
    [confirmHotkey, isWindows, metaLabel],
  )

  const handleKeyUp = useCallback(() => {
    setModifierHint(null)
  }, [])

  useEffect(() => {
    if (!recording) return
    needsRestore.current = true
    window.addEventListener('keydown', handleKeyDown, true)
    window.addEventListener('keyup', handleKeyUp, true)
    return () => {
      window.removeEventListener('keydown', handleKeyDown, true)
      window.removeEventListener('keyup', handleKeyUp, true)
      if (autoConfirmTimer.current) clearTimeout(autoConfirmTimer.current)
      if (needsRestore.current) {
        resumeHotkey().catch(() => {})
      }
    }
  }, [recording, handleKeyDown, handleKeyUp])

  const handleClick = () => {
    if (recording && pending) {
      // Confirm immediately on click
      if (autoConfirmTimer.current) clearTimeout(autoConfirmTimer.current)
      confirmHotkey(pending)
    } else if (recording) {
      // Cancel recording — re-register the old hotkey
      setRecording(false)
      setPending(null)
      setModifierHint(null)
      needsRestore.current = false
      if (autoConfirmTimer.current) clearTimeout(autoConfirmTimer.current)
      resumeHotkey().catch(() => {})
    } else {
      // Start recording — unregister global shortcut so webview can capture keys
      pauseHotkey().catch(() => {})
      setRecording(true)
      setPending(null)
      setError(null)
    }
  }

  return (
    <div>
      <button
        onClick={handleClick}
        className={`w-full px-3 py-2.5 rounded-[10px] text-[13px] font-mono text-left border transition-colors cursor-pointer ${
          recording
            ? 'bg-bg-tertiary border-text-secondary text-text-primary ring-2 ring-text-secondary/20'
            : 'bg-bg-secondary border-transparent text-text-primary hover:border-border'
        }`}
      >
        {recording ? pending || modifierHint || t('settings.pressKeyCombination') : config.hotkey}
      </button>
      {recording && pending && (
        <p className="text-[11px] text-text-tertiary mt-1.5">{t('settings.clickToConfirm')}</p>
      )}
      {error && <p className="text-[11px] text-error mt-1.5">{error}</p>}
    </div>
  )
}

export function GeneralPane() {
  const config = useAppStore((s) => s.config)
  const updateConfig = useAppStore((s) => s.updateConfig)
  const { t } = useTranslation()
  const isMac =
    typeof navigator !== 'undefined' && navigator.platform.toUpperCase().indexOf('MAC') >= 0
  const [a11yTrusted, setA11yTrusted] = useState<boolean | null>(null)

  useEffect(() => {
    if (isMac && config.output_mode === 'keyboard') {
      checkAccessibilityPermission().then(setA11yTrusted)
      const onFocus = () => checkAccessibilityPermission().then(setA11yTrusted)
      window.addEventListener('focus', onFocus)
      return () => window.removeEventListener('focus', onFocus)
    }
  }, [isMac, config.output_mode])

  const handleGrantPermission = useCallback(async () => {
    await requestAccessibilityPermission()
    const trusted = await checkAccessibilityPermission()
    setA11yTrusted(trusted)
  }, [])

  return (
    <div className="space-y-6">
      <Section title={t('settings.hotkey')}>
        <HotkeyRecorder />
        <div className="mt-3">
          <SegmentedControl
            options={[
              { value: 'hold', label: t('settings.holdToTalk') },
              { value: 'toggle', label: t('settings.toggleOnOff') },
            ]}
            value={config.hotkey_mode}
            onChange={(v) => updateConfig({ hotkey_mode: v as HotkeyMode })}
          />
        </div>
      </Section>

      <Section title={t('settings.outputMode')}>
        <SegmentedControl
          options={[
            { value: 'keyboard', label: t('settings.keyboardSimulation') },
            { value: 'clipboard', label: t('settings.clipboardPaste') },
          ]}
          value={config.output_mode}
          onChange={(v) => updateConfig({ output_mode: v as OutputMode })}
        />
      </Section>

      {isMac && config.output_mode === 'keyboard' && a11yTrusted !== null && (
        <Section title={t('settings.accessibilityPermission')}>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <span
                className={`w-2 h-2 rounded-full ${a11yTrusted ? 'bg-green-500' : 'bg-amber-500'}`}
              />
              <span className="text-[13px] text-text-primary">
                {a11yTrusted
                  ? t('settings.accessibilityGranted')
                  : t('settings.accessibilityRequired')}
              </span>
            </div>
            {!a11yTrusted && (
              <button
                onClick={handleGrantPermission}
                className="px-3 py-1.5 text-[12px] font-medium text-white bg-accent rounded-full border-none cursor-pointer hover:bg-accent-hover transition-colors"
              >
                {t('settings.grantPermission')}
              </button>
            )}
          </div>
        </Section>
      )}

      <Section title={t('settings.maxRecordingDuration', 'Max Recording Duration')}>
        <div className="flex items-center gap-3">
          <input
            type="range"
            min={10}
            max={300}
            step={10}
            value={config.max_recording_seconds}
            onChange={(e) => updateConfig({ max_recording_seconds: Number(e.target.value) })}
            className="flex-1 accent-accent"
          />
          <span className="text-[13px] text-text-secondary font-mono w-12 text-right">
            {config.max_recording_seconds}s
          </span>
        </div>
      </Section>

      <Section title={t('settings.other')}>
        <div className="space-y-3">
          <Toggle
            checked={config.auto_start}
            onChange={(checked) => updateConfig({ auto_start: checked })}
            label={t('settings.launchAtStartup')}
          />
          {config.auto_start && (
            <Toggle
              checked={config.start_minimized}
              onChange={(checked) => updateConfig({ start_minimized: checked })}
              label={t('settings.startMinimized')}
            />
          )}
          <Toggle
            checked={config.capsule_auto_hide}
            onChange={(checked) => updateConfig({ capsule_auto_hide: checked })}
            label={t('settings.hideCapsuleWhenIdle')}
          />
        </div>
      </Section>
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
