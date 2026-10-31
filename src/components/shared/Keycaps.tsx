const KEY_LABELS: Record<string, string> = {
  LeftShift: 'Left Shift',
  RightShift: 'Right Shift',
  LeftOption: 'Left Option',
  RightAlt: 'Right Alt',
  LeftAlt: 'Left Alt',
  Command: '⌘',
  Cmd: '⌘',
  Ctrl: 'Ctrl',
  Control: 'Ctrl',
  Option: '⌥',
  Alt: 'Alt',
  Shift: 'Shift',
  Space: 'Space',
}

function hotkeyParts(hotkey: string): string[] {
  return hotkey
    .split('+')
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part) => KEY_LABELS[part] ?? part)
}

export function Keycaps({ hotkey, className = '' }: { hotkey: string; className?: string }) {
  return (
    <span className={`inline-flex items-center gap-1 ${className}`}>
      {hotkeyParts(hotkey).map((part, index) => (
        <kbd key={`${part}-${index}`} className="keycap font-sans">
          {part}
        </kbd>
      ))}
    </span>
  )
}
