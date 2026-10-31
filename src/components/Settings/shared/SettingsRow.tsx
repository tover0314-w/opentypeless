import type { LucideIcon } from 'lucide-react'

export function SettingsSection({
  icon: Icon,
  title,
  children,
}: {
  icon?: LucideIcon
  title: string
  children: React.ReactNode
}) {
  return (
    <section className="mb-8 last:mb-0">
      <h3 className="mb-1 flex items-center gap-2 border-b border-border-thin pb-4 text-[16px] font-medium text-text-tertiary">
        {Icon && <Icon size={18} strokeWidth={1.8} />}
        {title}
      </h3>
      <div className="divide-y divide-transparent">{children}</div>
    </section>
  )
}

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
    <div className={`py-3.5 ${stacked ? 'space-y-3' : 'flex items-start justify-between gap-6'}`}>
      <div className="min-w-0 flex-1">
        <div className="text-[14px] font-medium leading-5 text-text-primary">{title}</div>
        {description && (
          <div className="mt-0.5 text-[14px] leading-5 text-text-tertiary">{description}</div>
        )}
      </div>
      {children && (
        <div className={stacked ? '' : 'flex min-w-[200px] justify-end'}>{children}</div>
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
