import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { bindingFromHotkey, hotkeyFromBinding, isMacPlatform } from '../../stores/appStore'
import type { ShortcutBinding } from '../../stores/appStore'
import { pauseHotkey, resumeHotkey } from '../../lib/tauri'
import { hotkeyParts } from '../../lib/hotkeyLabels'
import { SHORTCUT_ERROR_KEYS, validateShortcut } from '../../lib/shortcutRules'

// Typeless allows up to four bindings per feature (`Ht = 4`).
const MAX_BINDINGS = 4
const MODIFIER_KEYS = ['Control', 'Shift', 'Alt', 'Meta']
const NAMED_KEYS: Record<string, string> = {
  ' ': 'Space',
  ArrowUp: 'Up',
  ArrowDown: 'Down',
  ArrowLeft: 'Left',
  ArrowRight: 'Right',
}

interface Props {
  bindings: ShortcutBinding[]
  /** Bindings used by every other feature, for the "already in use" rule. */
  otherBindings: ShortcutBinding[]
  /** The primary dictation shortcut cannot be removed (Typeless `disabledClearShortcut`). */
  required?: boolean
  /** macOS Fn presets offered while recording, since the browser cannot see the Fn key. */
  presets?: Array<{ value: string; label: string }>
  onChange: (bindings: ShortcutBinding[]) => void
  label: string
}

/** Typeless shortcut input (REA `Dx8DUWFx` `So`/`Dt`): keycap boxes with × and "Add another". */
export function ShortcutKeycapInput({
  bindings,
  otherBindings,
  required = false,
  presets = [],
  onChange,
  label,
}: Props) {
  const { t } = useTranslation()
  const [editing, setEditing] = useState<number | null>(null)
  const [error, setError] = useState<{ row: number; message: string } | null>(null)
  const rows = editing === bindings.length ? [...bindings, null] : bindings

  const stopEditing = useCallback(() => {
    setEditing(null)
    resumeHotkey().catch(() => {})
  }, [])

  const commit = (row: number, binding: ShortcutBinding) => {
    const others = [...otherBindings, ...bindings.filter((_, index) => index !== row)]
    const rule = validateShortcut(binding, others)
    if (rule) {
      setError({ row, message: t(SHORTCUT_ERROR_KEYS[rule]) })
      return
    }
    setError(null)
    const next = [...bindings]
    next[row] = binding
    onChange(next)
    stopEditing()
  }

  const startEditing = (row: number) => {
    setError(null)
    setEditing(row)
    pauseHotkey().catch(() => {})
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
          error={error?.row === row ? error.message : null}
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
          onRemove={() => remove(row)}
        />
      ))}
      {editing === null && bindings.length > 0 && bindings.length < MAX_BINDINGS && (
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
}: {
  binding: ShortcutBinding | null
  otherBindings: ShortcutBinding[]
  presets?: Array<{ value: string; label: string }>
  onSave: (binding: ShortcutBinding) => void
  onCancel: () => void
}) {
  const { t } = useTranslation()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    pauseHotkey().catch(() => {})
    return () => {
      resumeHotkey().catch(() => {})
    }
  }, [])

  return (
    <ShortcutBox
      binding={binding}
      editing
      error={error}
      removable={false}
      removeLabel=""
      presets={presets}
      onStart={() => {}}
      onCancel={onCancel}
      onRecord={(recorded) => {
        const rule = validateShortcut(recorded, otherBindings)
        if (rule) {
          setError(t(SHORTCUT_ERROR_KEYS[rule]))
          return
        }
        onSave(recorded)
      }}
      onRemove={() => {}}
    />
  )
}

function ShortcutBox({
  binding,
  editing,
  error,
  removable,
  removeLabel,
  presets,
  onStart,
  onCancel,
  onRecord,
  onRemove,
}: {
  binding: ShortcutBinding | null
  editing: boolean
  error: string | null
  removable: boolean
  removeLabel: string
  presets: Array<{ value: string; label: string }>
  onStart: () => void
  onCancel: () => void
  onRecord: (binding: ShortcutBinding) => void
  onRemove: () => void
}) {
  const { t } = useTranslation()
  const isMac = isMacPlatform()
  const [pressed, setPressed] = useState<string[]>([])
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!editing) {
      setPressed([])
      return
    }
    boxRef.current?.focus()
    const onKeyDown = (event: KeyboardEvent) => {
      event.preventDefault()
      event.stopPropagation()
      if (event.key === 'Escape' && !event.metaKey && !event.ctrlKey && !event.altKey) {
        onCancel()
        return
      }
      const modifiers: string[] = []
      if (event.ctrlKey) modifiers.push('Ctrl')
      if (isMac && event.metaKey) modifiers.push('Command')
      if (event.altKey) modifiers.push(isMac ? 'Option' : 'Alt')
      if (event.shiftKey) modifiers.push('Shift')
      if (!isMac && event.metaKey) modifiers.push('Super')
      if (MODIFIER_KEYS.includes(event.key)) {
        setPressed(modifiers)
        return
      }
      const named = NAMED_KEYS[event.key] ?? event.key
      const primary = named.length === 1 ? named.toUpperCase() : named
      const binding = bindingFromHotkey([...modifiers, primary].join('+'))
      if (binding) onRecord(binding)
    }
    const onKeyUp = () => setPressed([])
    window.addEventListener('keydown', onKeyDown, true)
    window.addEventListener('keyup', onKeyUp, true)
    return () => {
      window.removeEventListener('keydown', onKeyDown, true)
      window.removeEventListener('keyup', onKeyUp, true)
    }
  }, [editing, isMac, onCancel, onRecord])

  useEffect(() => {
    if (!editing) return
    const onPointerDown = (event: PointerEvent) => {
      if (!boxRef.current?.parentElement?.contains(event.target as Node)) onCancel()
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
        aria-label={
          shown
            ? `${t('shortcuts.change')}: ${hotkeyParts(shown).join(' ')}`
            : t('shortcuts.recordPlaceholder')
        }
        title={!editing && binding ? t('shortcuts.change') : undefined}
        data-editing={editing || undefined}
        onClick={editing ? undefined : onStart}
        onKeyDown={(event) => {
          if (!editing && (event.key === 'Enter' || event.key === ' ')) {
            event.preventDefault()
            onStart()
          }
        }}
        className={`relative flex min-h-10 w-full flex-wrap items-center gap-1 rounded-[10px] border bg-transparent p-[3px] outline-none transition-[border-color,box-shadow] ${
          removable && !editing ? 'pr-[34px]' : ''
        } ${
          error
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
            {t('shortcuts.recordPlaceholder')}
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
      {error && <p className="m-0 text-[12px] leading-4 text-error">{error}</p>}
    </div>
  )
}
