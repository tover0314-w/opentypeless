export function PageContainer({
  children,
  className = '',
}: {
  children: React.ReactNode
  className?: string
}) {
  return (
    <div className={`mx-auto w-full max-w-[1080px] px-6 pb-10 pt-6 ${className}`}>{children}</div>
  )
}

export function PageHeader({
  title,
  actions,
}: {
  title: React.ReactNode
  actions?: React.ReactNode
}) {
  return (
    <header className="mb-6 flex items-center justify-between gap-4">
      <h1 className="text-[40px] font-bold leading-[48px] tracking-tight text-text-primary">
        {title}
      </h1>
      {actions && <div className="flex shrink-0 items-center gap-3">{actions}</div>}
    </header>
  )
}
