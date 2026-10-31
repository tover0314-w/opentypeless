import { useId } from 'react'
import { motion } from 'framer-motion'

interface Props {
  options: { value: string; label: string }[]
  value: string
  onChange: (value: string) => void
}

export function SegmentedControl({ options, value, onChange }: Props) {
  const id = useId()

  return (
    <div className="flex bg-bg-tertiary/50 rounded-[10px] p-0.5 gap-0.5">
      {options.map((opt) => (
        <button
          key={opt.value}
          onClick={() => onChange(opt.value)}
          className={`relative flex-1 px-3 py-1.5 text-[13px] rounded-[8px] border-none cursor-pointer transition-colors ${
            value === opt.value
              ? 'text-text-primary font-medium'
              : 'text-text-secondary hover:text-text-primary bg-transparent'
          }`}
        >
          {value === opt.value && (
            <motion.div
              layoutId={`segment-bg-${id}`}
              className="absolute inset-0 rounded-[8px] bg-bg-elevated shadow-sm"
              transition={{ type: 'spring', stiffness: 400, damping: 18 }}
            />
          )}
          <span className="relative z-10">{opt.label}</span>
        </button>
      ))}
    </div>
  )
}
