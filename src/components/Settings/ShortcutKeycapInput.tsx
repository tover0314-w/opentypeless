import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import {
  bindingFromHotkey,
  hotkeyFromBinding,
  isMacPlatform,
  isWindowsPlatform,
} from '../../stores/appStore'
import type { ShortcutBinding } from '../../stores/appStore'
import { useHotkeyCapture } from '../../hooks/useHotkeyCapture'
import { hotkeyParts } from '../../lib/hotkeyLabels'
import {
  nativeShortcutErrorKey,
  SHORTCUT_ERROR_KEYS,
  validateShortcut,
} from '../../lib/shortcutRules'
import { ShortcutCaptureState } from '../../lib/shortcutCaptureState'
import { useFnShortcutCapture } from '../../hooks/useFnShortcutCapture'

// Typeless allows up to four bindings per feature (`Ht = 4`).
const MAX_BINDINGS = 4
const SHORTCUT_EDITOR_STARTED = 'opentypeless:shortcut-editor-started'
const MODIFIER_KEYS = ['Control', 'Shift', 'Alt', 'Meta']
const NAMED_KEYS: Record<string, string> = {
  ' ': 'Space',
  ArrowUp: 'Up',
  ArrowDown: 'Down',
  ArrowLeft: 'Left',
  ArrowRight: 'Right',
}

function physicalShortcutKey(code: string): string | null {
  if (/^Key[A-Z]$/.test(code)) return code.slice(3)
  if (/^Digit[0-9]$/.test(code)) return code.slice(5)
  const punctuation: Record<string, string> = {
    Slash: '/',
    Period: '.',
    Comma: ',',
    Semicolon: ';',
    Minus: '-',
    Equal: '=',
    BracketLeft: '[',
    BracketRight: ']',
    Backslash: '\\',
    Quote: "'",
    Backquote: '`',
  }
  return punctuation[code] ?? null
}

function shortcutPlatform() {
  return isMacPlatform() ? 'macos' : isWindowsPlatform() ? 'windows' : 'other'
}

interface Props {
  bindings: ShortcutBinding[]
  /** Bindings used by every other feature, for the "already in use" rule. */
  otherBindings: ShortcutBinding[]
  /** The primary dictation shortcut cannot be removed (Typeless `disabledClearShortcut`). */
  required?: boolean
  /** macOS Fn presets offered while recording, since the browser cannot see the Fn key. */
  presets?: Array<{ value: string; label: string }>
  onChange: (bindings: ShortcutBinding[], recorded?: boolean) => void
  label: string
  maxBindings?: number
}

/** Typeless shortcut input (REA `Dx8DUWFx` `So`/`Dt`): keycap boxes with × and "Add another". */
export function ShortcutKeycapInput({
  bindings,
  otherBindings,
  required = false,
  presets = [],
  onChange,
  label,
  maxBindings = MAX_BINDINGS,
}: Props) {
  const { t } = useTranslation()
  const [editing, setEditing] = useState<number | null>(null)
  const [error, setError] = useState<{ row: number; message: string } | null>(null)
  const rows = editing === bindings.length ? [...bindings, null] : bindings

  const captureStatus = useHotkeyCapture(editing !== null)

  const stopEditing = useCallback(() => {
    setEditing(null)
  }, [])

  const commit = (row: number, binding: ShortcutBinding) => {
    const availabilityError = nativeShortcutErrorKey(binding, shortcutPlatform())
    if (availabilityError) {
      setError({ row, message: t(availabilityError) })
      return
    }
    const others = [...otherBindings, ...bindings.filter((_, index) => index !== row)]
    const rule = validateShortcut(binding, others)
    if (rule) {
      setError({ row, message: t(SHORTCUT_ERROR_KEYS[rule]) })
      return
    }
    setError(null)
    const next = [...bindings]
    next[row] = binding
    onChange(next, true)
    stopEditing()
  }

  const startEditing = (row: number) => {
    setError(null)
    setEditing(row)
  }

  const remove = (row: number) => {
    setError(null)
    onChange(bindings.filter((_, index) => index !== row))
  }

  return (
    <div className="flex w-[204px] flex-col gap-2" role="group" aria-label={label}>
      {rows.map((binding, row) => (
        <ShortcutBox
          key={row}
          binding={binding}
          editing={editing === row}
          captureReady={captureStatus === 'ready'}
          error={
            editing === row && captureStatus === 'error'
              ? t('settings.hotkeyRegistrationFailed')
              : error?.row === row
                ? error.message
                : null
          }
          removable={!(required && bindings.length === 1) && binding !== null}
          removeLabel={
            required && bindings.length === 1 ? t('shortcuts.disable') : t('shortcuts.remove')
          }
          presets={presets}
          onStart={() => startEditing(row)}
          onCancel={() => {
            setError(null)
            stopEditing()
          }}
          onRecord={(recorded) => commit(row, recorded)}
          onInvalid={(reason) => setError({ row, message: t(reason ?? 'settings.hotkeyInvalid') })}
          onRemove={() => remove(row)}
        />
      ))}
      {editing === null && bindings.length > 0 && bindings.length < maxBindings && (
        <button
          type="button"
          onClick={() => startEditing(bindings.length)}
          className="h-8 w-fit cursor-pointer rounded-[8px] border border-border bg-transparent px-3 text-[14px] leading-4 text-text-secondary transition-colors hover:border-text-tertiary/50 hover:bg-fill-hover"
        >
          {t('shortcuts.addAnother')}
        </button>
      )}
      {editing === null && bindings.length === 0 && (
        <button
          type="button"
          onClick={() => startEditing(0)}
          className="h-8 w-full cursor-pointer rounded-[8px] border border-border bg-transparent px-3 text-[14px] leading-4 text-text-secondary transition-colors hover:border-text-tertiary/50 hover:bg-fill-hover"
        >
          {t('shortcuts.addShortcut')}
        </button>
      )}
    </div>
  )
}

/** One recording box with Typeless validation, for editing a single binding in place. */
export function ShortcutRecorderBox({
  binding,
  otherBindings,
  presets = [],
  onSave,
  onCancel,
  saveError = null,
}: {
  binding: ShortcutBinding | null
  otherBindings: ShortcutBinding[]
  presets?: Array<{ value: string; label: string }>
  onSave: (binding: ShortcutBinding) => void
  onCancel: () => void
  saveError?: string | null
}) {
  const { t } = useTranslation()
  const [error, setError] = useState<string | null>(null)

  const captureStatus = useHotkeyCapture(true)

  return (
    <ShortcutBox
      binding={binding}
      editing
      captureReady={captureStatus === 'ready'}
      error={
        captureStatus === 'error' ? t('settings.hotkeyRegistrationFailed') : (error ?? saveError)
      }
      removable={false}
      removeLabel=""
      presets={presets}
      onStart={() => {}}
      onCancel={onCancel}
      onRecord={(recorded) => {
        const availabilityError = nativeShortcutErrorKey(recorded, shortcutPlatform())
        if (availabilityError) {
          setError(t(availabilityError))
          return
        }
        const rule = validateShortcut(recorded, otherBindings)
        if (rule) {
          setError(t(SHORTCUT_ERROR_KEYS[rule]))
          return
        }
        setError(null)
        onSave(recorded)
      }}
      onInvalid={(reason) => setError(t(reason ?? 'settings.hotkeyInvalid'))}
      onRemove={() => {}}
    />
  )
}

function ShortcutBox({
  binding,
  editing,
  captureReady,
  error,
  removable,
  removeLabel,
  presets,
  onStart,
  onCancel,
  onRecord,
  onInvalid,
  onRemove,
}: {
  binding: ShortcutBinding | null
  editing: boolean
  captureReady: boolean
  error: string | null
  removable: boolean
  removeLabel: string
  presets: Array<{ value: string; label: string }>
  onStart: () => void
  onCancel: () => void
  onRecord: (binding: ShortcutBinding) => void
  onInvalid: (reason?: string) => void
  onRemove: () => void
}) {
  const { t } = useTranslation()
  const errorId = useId()
  const cancelEditing = useRef(onCancel)
  cancelEditing.current = onCancel
  const isMac = isMacPlatform()
  const [pressed, setPressed] = useState<string[]>([])
  const boxRef = useRef<HTMLDivElement>(null)
  const capture = useRef(new ShortcutCaptureState())
  const captureRevoked = useRef(false)
  const shiftSide = useRef<string | null>(null)
  const altSide = useRef<string | null>(null)
  useLayoutEffect(() => {
    captureRevoked.current = !editing
    if (!editing) return
    const cancelOtherEditor = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== errorId) {
        captureRevoked.current = true
        capture.current.cancel()
        cancelEditing.current()
      }
    }
    window.addEventListener(SHORTCUT_EDITOR_STARTED, cancelOtherEditor)
    window.dispatchEvent(new CustomEvent(SHORTCUT_EDITOR_STARTED, { detail: errorId }))
    return () => window.removeEventListener(SHORTCUT_EDITOR_STARTED, cancelOtherEditor)
  }, [editing, errorId])
  const submitChord = useCallback(
    (chord: string[]) => {
      setPressed(chord)
      const modifierNames = new Set(['Ctrl', 'Command', 'Option', 'Alt', 'Shift', 'Super', 'Fn'])
      const rightAltCombo =
        isWindowsPlatform() &&
        altSide.current === 'AltRight' &&
        chord.includes('Alt') &&
        (chord.length === 1 ||
          (chord.length === 2 &&
            (chord.includes('Space') ||
              (chord.includes('Shift') && shiftSide.current === 'ShiftLeft'))))
      const normalized = rightAltCombo
        ? chord.map((part) => (part === 'Alt' ? 'RightAlt' : part === 'Shift' ? 'LeftShift' : part))
        : chord.length === 2 &&
            chord.includes('Fn') &&
            chord.includes('Shift') &&
            shiftSide.current === 'ShiftLeft'
          ? chord.map((part) => (part === 'Shift' ? 'LeftShift' : part))
          : chord
      modifierNames.add('RightAlt')
      const ordered =
        normalized.length === 1
          ? normalized
          : [
              ...normalized.filter((part) => modifierNames.has(part)),
              ...normalized.filter((part) => !modifierNames.has(part)),
            ]
      const binding = bindingFromHotkey(ordered.join('+'))
      if (binding) onRecord(binding)
      else {
        const captured = {
          primary: ordered[ordered.length - 1] ?? '',
          modifiers: ordered.slice(0, -1),
        }
        const availabilityError = nativeShortcutErrorKey(captured, shortcutPlatform())
        const rule = validateShortcut(captured, [])
        const ordinaryKeys = ordered.filter((key) => !modifierNames.has(key))
        onInvalid(
          availabilityError ??
            (rule
              ? SHORTCUT_ERROR_KEYS[rule]
              : ordinaryKeys.length > 1 || (ordered.length > 0 && ordinaryKeys.length === 0)
                ? 'shortcuts.errorNativeCombination'
                : undefined),
        )
      }
    },
    [onRecord, onInvalid],
  )
  const fnStatus = useFnShortcutCapture(editing && captureReady && isMac, (down) => {
    if (!editing || !captureReady || captureRevoked.current) return
    if (down) setPressed(capture.current.press('Fn'))
    else {
      const chord = capture.current.release('Fn')
      if (chord) submitChord(chord)
    }
  })
  const shownError =
    error ??
    (editing && captureReady && isMac && fnStatus === 'error'
      ? t('settings.hotkeyRegistrationFailed')
      : null)

  useLayoutEffect(() => {
    if (!editing) {
      captureRevoked.current = true
      capture.current.cancel()
      shiftSide.current = null
      altSide.current = null
      setPressed([])
      return
    }
    if (captureRevoked.current) return
    boxRef.current?.focus()
    const outsideCapture = (target: EventTarget | null) =>
      target instanceof HTMLElement &&
      target !== document.body &&
      target !== document.documentElement &&
      !boxRef.current?.parentElement?.contains(target)
    const onKeyDown = (event: KeyboardEvent) => {
      if (outsideCapture(event.target)) {
        captureRevoked.current = true
        capture.current.cancel()
        setPressed([])
        onCancel()
        return
      }
      if (captureRevoked.current) return
      if (event.key === 'Shift' && (event.code === 'ShiftLeft' || event.code === 'ShiftRight'))
        shiftSide.current = event.code
      if (event.key === 'Alt' && (event.code === 'AltLeft' || event.code === 'AltRight'))
        altSide.current = event.code
      const physicalKey = physicalShortcutKey(event.code)
      if (event.isComposing && !physicalKey) return
      event.preventDefault()
      event.stopPropagation()
      if (event.key === 'Escape' && !event.metaKey && !event.ctrlKey && !event.altKey) {
        captureRevoked.current = true
        capture.current.cancel()
        setPressed([])
        onCancel()
        return
      }
      if (!captureReady) return
      const modifiers: string[] = []
      if (event.ctrlKey) modifiers.push('Ctrl')
      if (isMac && event.metaKey) modifiers.push('Command')
      if (event.altKey) modifiers.push(isMac ? 'Option' : 'Alt')
      if (event.shiftKey) modifiers.push('Shift')
      if (!isMac && event.metaKey) modifiers.push('Super')
      for (const modifier of modifiers) capture.current.press(modifier)
      if (MODIFIER_KEYS.includes(event.key)) {
        const modifier =
          event.key === 'Control'
            ? 'Ctrl'
            : event.key === 'Alt'
              ? isMac
                ? 'Option'
                : 'Alt'
              : event.key === 'Meta'
                ? isMac
                  ? 'Command'
                  : 'Super'
                : 'Shift'
        setPressed(capture.current.press(modifier))
        return
      }
      const named = physicalKey ?? NAMED_KEYS[event.key] ?? event.key
      const primary = named.length === 1 ? named.toUpperCase() : named
      setPressed(capture.current.press(primary))
    }
    const onKeyUp = (event: KeyboardEvent) => {
      if (outsideCapture(event.target) || captureRevoked.current) return
      if (!captureReady) return
      const modifiers: Record<string, string> = {
        Control: 'Ctrl',
        Shift: 'Shift',
        Alt: isMac ? 'Option' : 'Alt',
        Meta: isMac ? 'Command' : 'Super',
      }
      const key =
        modifiers[event.key] ??
        physicalShortcutKey(event.code) ??
        NAMED_KEYS[event.key] ??
        (event.key.length === 1 ? event.key.toUpperCase() : event.key)
      const chord = capture.current.release(key)
      if (chord) submitChord(chord)
      if (event.key === 'Alt') altSide.current = null
      if (event.key === 'Shift') shiftSide.current = null
    }
    const onBlur = () => {
      captureRevoked.current = true
      capture.current.cancel()
      setPressed([])
      onCancel()
    }
    const onFocusIn = (event: FocusEvent) => {
      if (outsideCapture(event.target)) onBlur()
    }
    window.addEventListener('keydown', onKeyDown, true)
    window.addEventListener('keyup', onKeyUp, true)
    window.addEventListener('blur', onBlur)
    window.addEventListener('focusin', onFocusIn)
    return () => {
      window.removeEventListener('keydown', onKeyDown, true)
      window.removeEventListener('keyup', onKeyUp, true)
      window.removeEventListener('blur', onBlur)
      window.removeEventListener('focusin', onFocusIn)
    }
  }, [editing, captureReady, isMac, onCancel, submitChord])

  useEffect(() => {
    if (!editing) return
    const onPointerDown = (event: PointerEvent) => {
      if (!boxRef.current?.parentElement?.contains(event.target as Node)) {
        captureRevoked.current = true
        capture.current.cancel()
        setPressed([])
        onCancel()
      }
    }
    window.addEventListener('pointerdown', onPointerDown, true)
    return () => window.removeEventListener('pointerdown', onPointerDown, true)
  }, [editing, onCancel])

  const shown =
    editing && pressed.length ? pressed.join('+') : binding ? hotkeyFromBinding(binding) : ''

  return (
    <div className="flex flex-col gap-1.5">
      <div
        ref={boxRef}
        role="button"
        tabIndex={0}
        aria-describedby={shownError ? errorId : undefined}
        aria-label={
          shown
            ? `${t('shortcuts.change')}: ${hotkeyParts(shown).join(' ')}`
            : t('settings.pressKeyCombination')
        }
        title={!editing && binding ? t('shortcuts.change') : undefined}
        data-editing={editing || undefined}
        data-capture-ready={editing ? captureReady : undefined}
        onClick={editing ? undefined : onStart}
        onKeyDown={(event) => {
          if (event.target !== event.currentTarget) return
          if (!editing && (event.key === 'Enter' || event.key === ' ')) {
            event.preventDefault()
            onStart()
          }
        }}
        className={`relative flex min-h-10 w-full flex-wrap items-center gap-1 rounded-[10px] border bg-transparent p-[3px] outline-none transition-[border-color,box-shadow] ${
          removable && !editing ? 'pr-[34px]' : ''
        } ${
          shownError
            ? 'border-error shadow-[0_0_0_2px_rgba(234,77,0,0.15)]'
            : editing
              ? 'border-border-focus shadow-[0_0_0_2px_rgba(42,187,167,0.15)]'
              : 'cursor-pointer border-border hover:border-text-tertiary/50 focus-visible:border-border-focus'
        }`}
      >
        {shown ? (
          hotkeyParts(shown).map((part, index) => (
            <span
              key={`${part}-${index}`}
              className="flex min-h-8 min-w-8 items-center justify-center whitespace-nowrap rounded-[8px] border border-border bg-bg-elevated px-2 text-[14px] leading-4 text-text-primary"
            >
              {part}
            </span>
          ))
        ) : (
          <span className="px-2 py-1 text-[14px] leading-5 text-text-tertiary">
            {t('settings.pressKeyCombination')}
          </span>
        )}
        {removable && !editing && (
          <button
            type="button"
            aria-label={removeLabel}
            title={removeLabel}
            onClick={(event) => {
              event.stopPropagation()
              onRemove()
            }}
            className="absolute right-[6px] top-[6px] grid h-[26px] w-[26px] cursor-pointer place-items-center rounded-[4px] border-none bg-transparent text-text-secondary hover:bg-fill-hover"
          >
            <X size={14} />
          </button>
        )}
      </div>
      {editing && presets.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {presets.map((preset) => (
            <button
              key={preset.value}
              type="button"
              disabled={!captureReady}
              onClick={() => {
                const presetBinding = bindingFromHotkey(preset.value)
                if (presetBinding) onRecord(presetBinding)
              }}
              className="cursor-pointer rounded-[6px] border border-border bg-transparent px-2 py-0.5 text-[12px] text-text-secondary hover:bg-fill-hover hover:text-text-primary"
            >
              {preset.label}
            </button>
          ))}
        </div>
      )}
      {shownError && (
        <p id={errorId} role="alert" className="m-0 text-[12px] leading-4 text-error">
          {shownError}
        </p>
      )}
    </div>
  )
}
