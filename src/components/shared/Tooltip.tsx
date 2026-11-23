export function Tooltip({
  label,
  align = 'center',
  wide = false,
  disabled = false,
  children,
}: {
  label: string
  align?: 'center' | 'start'
  wide?: boolean
  disabled?: boolean
  children: React.ReactNode
}) {
  if (disabled) return <>{children}</>
  const position = align === 'start' ? 'left-0' : 'left-1/2 -translate-x-1/2'
  return (
    <span className="group/tooltip relative inline-flex">
      {children}
      <span
        role="tooltip"
        className={`pointer-events-none absolute bottom-full z-50 mb-2 rounded-[6px] bg-action px-2 py-1 text-[12px] font-normal leading-4 text-on-action opacity-0 transition-opacity delay-100 group-hover/tooltip:opacity-100 group-focus-within/tooltip:opacity-100 ${position} ${
          wide ? 'w-[260px] whitespace-normal' : 'whitespace-nowrap'
        }`}
      >
        {label}
      </span>
    </span>
  )
}
