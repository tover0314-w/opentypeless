interface Props {
  checked: boolean
  onChange: (checked: boolean) => void
  label?: string
  /** Accessible name when the visible label lives elsewhere (e.g. a settings row title). */
  ariaLabel?: string
  disabled?: boolean
}

/** Typeless switch (REA `Px1`): 30×18 track, 14px thumb, accent track when on. */
export function Toggle({ checked, onChange, label, ariaLabel, disabled = false }: Props) {
  return (
    <label
      className={`inline-flex min-h-10 items-center gap-2.5 ${disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer'}`}
    >
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label ? undefined : ariaLabel}
        disabled={disabled}
        onClick={() => onChange(!checked)}
        className={`relative h-[18px] w-[30px] shrink-0 rounded-full border-none p-0 transition-colors duration-300 motion-reduce:transition-none ${
          disabled ? 'cursor-not-allowed' : 'cursor-pointer'
        } ${checked ? 'bg-accent' : 'bg-[rgba(119,119,119,0.18)]'}`}
      >
        <span
          className={`absolute left-[2px] top-[2px] h-[14px] w-[14px] rounded-full bg-white shadow-sm transition-transform duration-300 motion-reduce:transition-none ${
            checked ? 'translate-x-[12px]' : 'translate-x-0'
          }`}
        />
      </button>
      {label && <span className="text-[13px] text-text-primary">{label}</span>}
    </label>
  )
}
