import { AppLogo } from '../shared/AppLogo'

export function CapsuleLogo({ size = 22, className = '' }: { size?: number; className?: string }) {
  return <AppLogo size={size} className={className} />
}
