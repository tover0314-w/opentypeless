import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { MoreHorizontal, Plus, X } from 'lucide-react'
import { bindingFromHotkey, hotkeyFromBinding, isMacPlatform } from '../../stores/appStore'
import type { HotkeyRole } from '../../lib/tauri'
import type { ShortcutBinding } from '../../stores/appStore'
import { pauseHotkey, resumeHotkey } from '../../lib/tauri'

const MAX_BINDINGS = 3

interface HotkeyRecorderProps {
  value: string
  onSaved: (hotkey: string) => void
  validateHotkey?: (hotkey: string) => string | null
  specialOptions?: Array<{ value: string; label: string }>
  disabled?: boolean
  autoStart?: boolean
  onCancel?: () => void
}

export function HotkeyRecorder({
  value,
  onSaved,
  validateHotkey,
  specialOptions,
  disabled = false,
  autoStart = false,
  onCancel,
}: HotkeyRecorderProps) {
  const { t } = useTranslation()
  const isMac = isMacPlatform()
  const [recording, setRecording] = useState(false)
  const [pending, setPending] = useState<string | null>(null)
  const [modifierHint, setModifierHint] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const autoConfirmTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const autoStarted = useRef(false)
  const recordingRef = useRef(false)

  const clearTimer = useCallback(() => {
    if (!autoConfirmTimer.current) return
    clearTimeout(autoConfirmTimer.current)
    autoConfirmTimer.current = null
  }, [])

  const confirmHotkey = useCallback(
    (hotkey: string) => {
      clearTimer()
      recordingRef.current = false
      setRecording(false)
      setModifierHint(null)
      setPending(null)
      const validationError = validateHotkey?.(hotkey)
      if (validationError) {
        setError(validationError)
        resumeHotkey().catch((resumeError) => setError(String(resumeError)))
        return
      }
      setError(null)
      onSaved(hotkey)
      resumeHotkey().catch((resumeError) => setError(String(resumeError)))
    },
    [clearTimer, onSaved, validateHotkey],
  )

  const cancelRecording = useCallback(() => {
    clearTimer()
    recordingRef.current = false
    setRecording(false)
    setPending(null)
    setModifierHint(null)
    setError(null)
    resumeHotkey().catch(() => {})
    onCancel?.()
  }, [clearTimer, onCancel])

  const startRecording = useCallback(() => {
    if (disabled) return
    pauseHotkey().catch(() => {})
    recordingRef.current = true
    setRecording(true)
    setPending(null)
    setModifierHint(null)
    setError(null)
  }, [disabled])

  useEffect(() => {
    return () => {
      if (!recordingRef.current) return
      recordingRef.current = false
      resumeHotkey().catch(() => {})
    }
  }, [])

  useEffect(() => {
    if (!autoStart || autoStarted.current) return
    autoStarted.current = true
    startRecording()
  }, [autoStart, startRecording])

  const handleKeyDown = useCallback(
    (event: KeyboardEvent) => {
      event.preventDefault()
      event.stopPropagation()

      // `KeyboardEvent.key` identifies either Alt key as "Alt". The physical
      // code preserves the side for a standalone Alt binding.
      const standaloneAlt = !isMac
        ? event.code === 'AltLeft'
          ? 'LeftAlt'
          : event.code === 'AltRight'
            ? 'RightAlt'
            : null
        : null
      if (standaloneAlt) {
        confirmHotkey(standaloneAlt)
        return
      }

      const parts: string[] = []
      if (isMac && event.metaKey) parts.push('Command')
      if (event.ctrlKey) parts.push('Ctrl')
      if (event.altKey) parts.push(isMac ? 'Option' : 'Alt')
      if (event.shiftKey) parts.push('Shift')
      if (!isMac && event.metaKey) parts.push('Meta')

      if (['Control', 'Shift', 'Alt', 'Meta'].includes(event.key)) {
        setModifierHint(parts.length > 0 ? `${parts.join('+')}+...` : null)
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
        '。': '.',
        '?': '/',
      }
      let keyName = keyMap[event.key] || event.key
      if (keyName.length === 1) keyName = keyName.toUpperCase()
      parts.push(keyName)
      const combo = parts.join('+')
      setPending(combo)
      if (autoConfirmTimer.current) clearTimeout(autoConfirmTimer.current)
      autoConfirmTimer.current = setTimeout(() => confirmHotkey(combo), 1500)
    },
    [confirmHotkey, isMac],
  )

  useEffect(() => {
    if (!recording) return
    const clearModifierHint = () => setModifierHint(null)
    window.addEventListener('keydown', handleKeyDown, true)
    window.addEventListener('keyup', clearModifierHint, true)
    return () => {
      window.removeEventListener('keydown', handleKeyDown, true)
      window.removeEventListener('keyup', clearModifierHint, true)
      clearTimer()
    }
  }, [clearTimer, handleKeyDown, recording])

  const handleClick = () => {
    if (disabled) return
    if (recording && pending) {
      confirmHotkey(pending)
    } else if (recording) {
      cancelRecording()
    } else {
      startRecording()
    }
  }

  return (
    <div className="min-w-0">
      <div className="flex min-w-0 items-center gap-1">
        <button
          type="button"
          onClick={handleClick}
          disabled={disabled}
          className={`h-9 min-w-0 flex-1 rounded-[8px] border px-3 text-left font-mono text-[12px] transition-colors disabled:opacity-40 ${
            recording
              ? 'border-text-secondary bg-bg-tertiary text-text-primary ring-2 ring-text-secondary/20'
              : 'border-transparent bg-bg-secondary text-text-primary hover:border-border'
          }`}
        >
          {recording ? pending || modifierHint || t('settings.pressKeyCombination') : value}
        </button>
        {recording && onCancel && (
          <button
            type="button"
            onClick={cancelRecording}
            aria-label={t('common.cancel')}
            title={t('common.cancel')}
            className="grid h-9 w-8 flex-none place-items-center rounded-[6px] border-none bg-transparent text-text-tertiary hover:bg-bg-hover hover:text-text-primary"
          >
            <X size={14} />
          </button>
        )}
      </div>
      {recording && pending && (
        <p className="mt-1 text-[11px] text-text-tertiary">{t('settings.clickToConfirm')}</p>
      )}
      {recording && specialOptions && specialOptions.length > 0 && (
        <div className="mt-1.5 flex flex-wrap gap-1.5">
          {specialOptions.map((option) => (
            <button
              key={option.value}
              type="button"
              onClick={() => confirmHotkey(option.value)}
              className="rounded-[8px] border border-border bg-bg-secondary px-2 py-1 text-[11px] text-text-secondary hover:border-border-focus hover:text-text-primary"
            >
              {option.label}
            </button>
          ))}
        </div>
      )}
      {error && <p className="mt-1 text-[11px] text-error">{error}</p>}
    </div>
  )
}

interface ShortcutBindingListProps {
  role: Extract<HotkeyRole, 'dictation' | 'ask' | 'translate'>
  label: string
  bindings: ShortcutBinding[]
  otherBindings: ShortcutBinding[]
  required: boolean
  specialOptions: Array<{ value: string; label: string }>
  onChange: (bindings: ShortcutBinding[]) => void
  disabled?: boolean
  trailingAction?: React.ReactNode
}

function bindingIdentity(binding: ShortcutBinding) {
  const semanticModifiers = binding.modifiers.map((modifier) => {
    if (modifier === 'Option') return 'Alt'
    if (modifier === 'Command') return 'Super'
    return modifier
  })
  return [...semanticModifiers, binding.primary].join('+')
}

export function ShortcutBindingList({
  role,
  label,
  bindings,
  otherBindings,
  required,
  specialOptions,
  onChange,
  disabled = false,
  trailingAction,
}: ShortcutBindingListProps) {
  const { t } = useTranslation()
  const [adding, setAdding] = useState(false)
  const [menuIndex, setMenuIndex] = useState<number | null>(null)
  const menuButtonRefs = useRef<Array<HTMLButtonElement | null>>([])
  const atLimit = bindings.length >= MAX_BINDINGS

  useEffect(() => {
    if (atLimit) setAdding(false)
  }, [atLimit])

  const closeMenu = useCallback((restoreFocus = false) => {
    setMenuIndex((current) => {
      if (restoreFocus && current !== null) {
        requestAnimationFrame(() => menuButtonRefs.current[current]?.focus())
      }
      return null
    })
  }, [])

  useEffect(() => {
    if (menuIndex === null) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      closeMenu(true)
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [closeMenu, menuIndex])

  const validate = (hotkey: string, editingIndex: number | null) => {
    const candidate = bindingFromHotkey(hotkey)
    if (!candidate) return t('settings.hotkeyInvalid')
    const identity = bindingIdentity(candidate)
    const ownConflicts = bindings.some(
      (binding, index) => index !== editingIndex && bindingIdentity(binding) === identity,
    )
    const externalConflict = otherBindings.some((binding) => bindingIdentity(binding) === identity)
    return ownConflicts || externalConflict ? t('settings.hotkeyConflict') : null
  }

  const saveAt = (index: number, hotkey: string) => {
    const binding = bindingFromHotkey(hotkey)
    if (!binding) return
    onChange(bindings.map((current, currentIndex) => (currentIndex === index ? binding : current)))
  }

  const makePrimary = (index: number) => {
    if (index <= 0 || index >= bindings.length) return
    onChange([bindings[index], ...bindings.filter((_, currentIndex) => currentIndex !== index)])
  }

  return (
    <div data-hotkey-role={role}>
      <div className="mb-1.5 flex min-h-7 items-center gap-2">
        <p className="min-w-0 flex-1 text-[12px] font-medium text-text-secondary">{label}</p>
        {trailingAction}
        <button
          type="button"
          aria-label={t('settings.shortcutAdd')}
          title={t('settings.shortcutAdd')}
          disabled={disabled || atLimit || adding}
          onClick={() => setAdding(true)}
          className="grid h-7 w-7 place-items-center rounded-[6px] border-none bg-transparent text-text-tertiary hover:bg-bg-hover hover:text-text-primary disabled:opacity-35"
        >
          <Plus size={14} />
        </button>
      </div>

      <div className="space-y-1.5">
        {bindings.map((binding, index) => (
          <div
            key={`${bindingIdentity(binding)}-${index}`}
            className="flex min-w-0 items-start gap-1"
          >
            <div className="min-w-0 flex-1">
              <HotkeyRecorder
                value={hotkeyFromBinding(binding)}
                disabled={disabled}
                specialOptions={specialOptions}
                validateHotkey={(hotkey) => validate(hotkey, index)}
                onSaved={(hotkey) => saveAt(index, hotkey)}
              />
              {bindings.length > 1 && index === 0 && (
                <p className="mt-0.5 text-[10px] text-text-tertiary">
                  {t('settings.shortcutPrimary')}
                </p>
              )}
            </div>
            {(bindings.length > 1 || !required) && (
              <div className="relative flex-none">
                <button
                  ref={(element) => {
                    menuButtonRefs.current[index] = element
                  }}
                  type="button"
                  aria-label={t('settings.shortcutManage')}
                  title={t('settings.shortcutManage')}
                  aria-expanded={menuIndex === index}
                  onClick={() => setMenuIndex((current) => (current === index ? null : index))}
                  disabled={disabled}
                  className="grid h-9 w-8 place-items-center rounded-[6px] border-none bg-transparent text-text-tertiary hover:bg-bg-hover hover:text-text-primary disabled:opacity-35"
                >
                  <MoreHorizontal size={14} />
                </button>
                {menuIndex === index && (
                  <>
                    <div className="fixed inset-0 z-30" onClick={() => closeMenu(true)} />
                    <div className="absolute right-0 top-9 z-40 min-w-[150px] rounded-[8px] border border-border bg-bg-primary py-1 shadow-float">
                      {index > 0 && (
                        <button
                          type="button"
                          onClick={() => {
                            closeMenu()
                            makePrimary(index)
                          }}
                          className="block w-full px-3 py-2 text-left text-[12px] text-text-secondary hover:bg-bg-hover hover:text-text-primary"
                        >
                          {t('settings.shortcutMakePrimary')}
                        </button>
                      )}
                      <button
                        type="button"
                        onClick={() => {
                          closeMenu()
                          onChange(bindings.filter((_, currentIndex) => currentIndex !== index))
                        }}
                        className="block w-full px-3 py-2 text-left text-[12px] text-text-secondary hover:bg-bg-hover hover:text-error"
                      >
                        {t('settings.shortcutRemove')}
                      </button>
                    </div>
                  </>
                )}
              </div>
            )}
          </div>
        ))}

        {adding && !atLimit && (
          <HotkeyRecorder
            value=""
            disabled={disabled}
            autoStart
            onCancel={() => setAdding(false)}
            specialOptions={specialOptions}
            validateHotkey={(hotkey) => validate(hotkey, null)}
            onSaved={(hotkey) => {
              const binding = bindingFromHotkey(hotkey)
              if (!binding) return
              setAdding(false)
              onChange([...bindings, binding])
            }}
          />
        )}
      </div>

      {atLimit && (
        <p className="mt-1 text-[10px] text-text-tertiary">{t('settings.shortcutMax')}</p>
      )}
    </div>
  )
}
