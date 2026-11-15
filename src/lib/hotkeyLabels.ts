import { useAppStore, type ShortcutBinding } from '../stores/appStore'

const KEY_LABELS: Record<string, string> = {
  Command: '⌘',
  Cmd: '⌘',
  Control: 'Ctrl',
  Option: '⌥',
}

/** "LeftShift" → "Left Shift", "MouseButton4" → "Mouse 4" (Typeless key label rules). */
export function keyLabel(part: string): string {
  if (Object.prototype.hasOwnProperty.call(KEY_LABELS, part)) return KEY_LABELS[part]
  const mouse = /^MouseButton(\d+)$/.exec(part)
  if (mouse) return `Mouse ${mouse[1]}`
  return part.replace(/^(Left|Right)([A-Z])/, '$1 $2')
}

export function hotkeyParts(hotkey: string): string[] {
  return hotkey
    .split('+')
    .map((part) => part.trim())
    .filter(Boolean)
    .map(keyLabel)
}

function formatBinding(binding: ShortcutBinding | null | undefined): string | null {
  if (!binding?.primary.trim()) return null
  return [...binding.modifiers, binding.primary].join('+')
}

/** First configured binding per voice feature, which is what Typeless teaches in its hints. */
export function useVoiceHotkeys() {
  const hotkeys = useAppStore((s) => s.config.hotkeys)
  return {
    dictate: formatBinding(hotkeys.dictationBindings?.[0] ?? hotkeys.dictation),
    translate: formatBinding(hotkeys.translateBindings?.[0] ?? hotkeys.translate),
    ask: formatBinding(hotkeys.askBindings?.[0] ?? hotkeys.ask),
  }
}
