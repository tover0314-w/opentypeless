import { hotkeyParts } from '../../lib/hotkeyLabels'

export function Keycaps({ hotkey, className = '' }: { hotkey: string; className?: string }) {
  return (
    <span className={`inline-flex items-center gap-1 whitespace-nowrap ${className}`}>
      {hotkeyParts(hotkey).map((part, index) => (
        <kbd key={`${part}-${index}`} className="keycap font-sans">
          {part}
        </kbd>
      ))}
    </span>
  )
}
