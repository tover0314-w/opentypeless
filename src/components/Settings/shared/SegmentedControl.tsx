import { useId } from 'react'
import { motion, useReducedMotion } from 'framer-motion'

interface Props {
  options: { value: string; label: string }[]
  value: string
  onChange: (value: string) => void
}

export function SegmentedControl({ options, value, onChange }: Props) {
  const id = useId()
  const reducedMotion = useReducedMotion()

  return (
    <div
      className="grid w-full bg-bg-tertiary/50 rounded-[10px] p-0.5 gap-0.5"
      style={{ gridTemplateColumns: `repeat(${Math.max(1, options.length)}, minmax(0, 1fr))` }}
    >
      {options.map((opt) => (
        <button
          key={opt.value}
          type="button"
          aria-pressed={value === opt.value}
          onClick={() => onChange(opt.value)}
          className={`relative min-w-0 min-h-10 whitespace-normal break-words px-2 py-1.5 text-[13px] leading-5 rounded-[8px] border-none cursor-pointer transition-colors ${
            value === opt.value
              ? 'text-text-primary font-medium'
              : 'text-text-secondary hover:text-text-primary bg-transparent'
          }`}
        >
          {value === opt.value &&
            (reducedMotion ? (
              <div className="absolute inset-0 rounded-[8px] bg-bg-elevated shadow-sm" />
            ) : (
              <motion.div
                layoutId={`segment-bg-${id}`}
                className="absolute inset-0 rounded-[8px] bg-bg-elevated shadow-sm"
                transition={{ type: 'spring', stiffness: 400, damping: 18 }}
              />
            ))}
          <span className="relative z-10">{opt.label}</span>
        </button>
      ))}
    </div>
  )
}
