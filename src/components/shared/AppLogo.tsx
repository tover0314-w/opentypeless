import originalLogo from '../../../src-tauri/icons/app-icon.svg'

export function AppLogo({ size = 20, className = '' }: { size?: number; className?: string }) {
  return (
    <img
      src={originalLogo}
      alt=""
      aria-hidden="true"
      width={size}
      height={size}
      draggable={false}
      className={`shrink-0 ${className}`}
    />
  )
}
