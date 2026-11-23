import { AppLogo } from '../shared/AppLogo'

export function BrandMark({ size = 20, className = '' }: { size?: number; className?: string }) {
  return <AppLogo size={size} className={className} />
}
