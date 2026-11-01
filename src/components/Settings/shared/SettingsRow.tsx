import type { LucideIcon } from 'lucide-react'

/** Typeless settings group: grey icon + title over a thin divider, then rows. */
export function SettingsSection({
  icon: Icon,
  title,
  action,
  children,
  label,
}: {
  icon?: LucideIcon
  title: string
  action?: React.ReactNode
  children: React.ReactNode
  /** Accessible name for the group; defaults to the title. */
  label?: string
}) {
  return (
    <section aria-label={label ?? title} className="mb-10 last:mb-0">
      <div className="mb-1 flex items-center gap-2 border-b border-border-thin pb-4">
        <h3 className="m-0 flex min-w-0 flex-1 items-center gap-2 text-[16px] font-medium leading-6 text-text-tertiary">
          {Icon && <Icon size={18} strokeWidth={1.8} />}
          {title}
        </h3>
        {action}
      </div>
      <div>{children}</div>
    </section>
  )
}

/** Title + description on the left, a 280px control column on the right. */
export function SettingsRow({
  title,
  description,
  children,
  stacked = false,
}: {
  title: string
  description?: React.ReactNode
  children?: React.ReactNode
  stacked?: boolean
}) {
  return (
    <div className={`py-4 ${stacked ? 'space-y-3' : 'flex items-start justify-between gap-8'}`}>
      <div className="min-w-0 flex-1">
        <div className="text-[14px] font-semibold leading-5 text-text-primary">{title}</div>
        {description && (
          <div className="mt-1 text-[14px] leading-5 text-text-tertiary">{description}</div>
        )}
      </div>
      {children && (
        <div className={stacked ? '' : 'flex w-[280px] shrink-0 flex-col items-stretch gap-1.5'}>
          {children}
        </div>
      )}
    </div>
  )
}

export function SettingsNotice({
  tone = 'warning',
  children,
}: {
  tone?: 'warning' | 'error'
  children: React.ReactNode
}) {
  const toneClass =
    tone === 'error'
      ? 'border-error/30 bg-error/10 text-error'
      : 'border-warning/30 bg-warning/10 text-text-secondary'
  return (
    <p className={`mt-2 rounded-[8px] border px-3 py-2 text-[12px] leading-relaxed ${toneClass}`}>
      {children}
    </p>
  )
}
