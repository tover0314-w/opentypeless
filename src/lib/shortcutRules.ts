import type { ShortcutBinding } from '../stores/appStore'

export type ShortcutRuleError =
  | 'alphanumericOnly'
  | 'tooManyKeys'
  | 'systemReserved'
  | 'consecutiveLetters'
  | 'consecutiveNumbers'
  | 'alreadyInUse'

export const SHORTCUT_ERROR_KEYS: Record<ShortcutRuleError, string> = {
  alphanumericOnly: 'shortcuts.errorAlphanumeric',
  tooManyKeys: 'shortcuts.errorTooMany',
  systemReserved: 'shortcuts.errorReserved',
  consecutiveLetters: 'shortcuts.errorConsecutiveLetters',
  consecutiveNumbers: 'shortcuts.errorConsecutiveNumbers',
  alreadyInUse: 'shortcuts.errorInUse',
}

const MAX_KEYS = 3

/** Canonical key names so Option/Alt and Command/Super compare as equal. */
function canonicalKey(key: string): string {
  const lower = key.toLowerCase()
  if (lower === 'option' || lower === 'alt' || lower === 'leftoption' || lower === 'leftalt')
    return 'alt'
  if (lower === 'command' || lower === 'cmd' || lower === 'super' || lower === 'leftcmd')
    return 'cmd'
  if (lower === 'control' || lower === 'ctrl' || lower === 'leftctrl') return 'ctrl'
  if (lower === 'shift') return 'shift'
  return lower
}

// Typeless `tf()`: shortcuts the system or every app already owns.
const RESERVED = [
  'cmd+c',
  'cmd+v',
  'cmd+x',
  'cmd+a',
  'cmd+s',
  'cmd+z',
  'cmd+y',
  'cmd+w',
  'cmd+q',
  'cmd+r',
  'cmd+n',
  'cmd+o',
  'cmd+f',
  'cmd+g',
  'cmd+h',
  'cmd+tab',
  'cmd+leftshift+tab',
  'cmd+space',
  'cmd+alt+escape',
  'cmd+,',
  'ctrl+c',
  'ctrl+v',
  'ctrl+x',
  'ctrl+a',
  'ctrl+s',
  'ctrl+z',
  'ctrl+y',
  'ctrl+w',
  'ctrl+q',
  'ctrl+r',
  'ctrl+n',
  'ctrl+o',
  'ctrl+f',
  'ctrl+g',
  'ctrl+tab',
  'ctrl+leftshift+tab',
  'alt+tab',
  'alt+f4',
  'f1',
  'f2',
  'f3',
  'f4',
  'f5',
  'f6',
  'f7',
  'f8',
  'f9',
  'f10',
  'f11',
  'f12',
  'escape',
  'enter',
  'return',
  'delete',
  'backspace',
  'tab',
  'space',
  'capslock',
  'pause',
].map((combo) => combo.split('+').map(canonicalKey).sort().join('+'))
const RESERVED_SET = new Set(RESERVED)

function keysOf(binding: ShortcutBinding): string[] {
  return [...binding.modifiers, binding.primary]
}

function identity(keys: string[]): string {
  return keys.map(canonicalKey).sort().join('+')
}

function hasConsecutive(values: number[]): boolean {
  const sorted = [...values].sort((a, b) => a - b)
  return sorted.some((value, index) => index > 0 && value - sorted[index - 1] === 1)
}

/** Typeless shortcut validation (REA `Dx8DUWFx` `_0x2f7660`), first failing rule wins. */
export function validateShortcut(
  binding: ShortcutBinding,
  inUse: ShortcutBinding[],
): ShortcutRuleError | null {
  const keys = keysOf(binding)
  if (keys.every((key) => /^[a-zA-Z0-9`]$/.test(key))) return 'alphanumericOnly'
  if (keys.length > MAX_KEYS) return 'tooManyKeys'
  const id = identity(keys)
  if (RESERVED_SET.has(id)) return 'systemReserved'

  const singles = keys.filter((key) => /^[a-zA-Z0-9]$/.test(key))
  if (singles.length >= 2) {
    const letters = singles.filter((key) => /^[a-zA-Z]$/.test(key))
    if (
      letters.length >= 2 &&
      hasConsecutive(letters.map((key) => key.toLowerCase().charCodeAt(0)))
    )
      return 'consecutiveLetters'
    const digits = singles.filter((key) => /^[0-9]$/.test(key))
    if (digits.length >= 2 && hasConsecutive(digits.map(Number))) return 'consecutiveNumbers'
  }

  if (inUse.some((other) => identity(keysOf(other)) === id)) return 'alreadyInUse'
  return null
}
