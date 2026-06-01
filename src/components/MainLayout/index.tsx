import { Home, Settings, History, Crown, CircleUser } from 'lucide-react'
import { motion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { spring } from '../../lib/animations'
import { useRoute, type Route } from '../../lib/router'
import { useAuthStore } from '../../stores/authStore'
import { AccessibilityBanner } from './AccessibilityBanner'

const baseNavItems: { id: Route; labelKey: string; icon: typeof Home }[] = [
  { id: 'home', labelKey: 'nav.home', icon: Home },
  { id: 'settings', labelKey: 'nav.settings', icon: Settings },
  { id: 'history', labelKey: 'nav.history', icon: History },
]

const bottomNavItem = { id: 'account' as Route, labelKey: 'nav.account', icon: CircleUser }

interface Props {
  children: React.ReactNode
}

export function MainLayout({ children }: Props) {
  const { route, navigate } = useRoute()
  const { plan } = useAuthStore()
  const { t } = useTranslation()
  const isPro = plan === 'pro'

  return (
    <div className="w-full h-full flex bg-bg-primary text-text-primary">
      {/* Sidebar — jelly surface */}
      <aside className="w-[208px] flex flex-col border-r border-border jelly-surface-flat shrink-0">
        {/* Logo */}
        <div className="px-5 pt-5 pb-4" data-tauri-drag-region>
          <h1 className="text-[15px] font-semibold tracking-tight">{t('app.name')}</h1>
          <p className="text-[11px] text-text-tertiary mt-0.5">{t('app.tagline')}</p>
        </div>

        {/* Main Nav */}
        <nav className="flex-1 px-3 space-y-0.5 relative" aria-label={t('nav.mainNavigation')}>
          {baseNavItems.map(({ id, labelKey, icon: Icon }) => {
            const active = route === id
            const label = t(labelKey)
            return (
              <motion.button
                key={id}
                onClick={() => navigate(id)}
                whileHover={{ scale: 1.03 }}
                whileTap={{ scaleX: 1.05, scaleY: 0.95 }}
                transition={spring.jellyGentle}
                aria-label={label}
                aria-current={active ? 'page' : undefined}
                className={`flex items-center gap-2.5 w-full px-3 py-2 text-[13px] rounded-[8px] transition-colors bg-transparent border-none cursor-pointer text-left relative ${
                  active
                    ? 'text-text-primary font-medium'
                    : 'text-text-secondary hover:text-text-primary'
                }`}
              >
                {active && (
                  <motion.div
                    layoutId="nav-indicator"
                    className="absolute inset-0 jelly-nav-active"
                    transition={spring.jellyGentle}
                  />
                )}
                <span className="relative z-10 flex items-center gap-2.5">
                  <Icon size={16} />
                  {label}
                </span>
              </motion.button>
            )
          })}

          {/* Upgrade / Pro nav item */}
          {(() => {
            const active = route === 'upgrade'
            return (
              <motion.button
                onClick={() => navigate('upgrade')}
                whileHover={{ scale: 1.03 }}
                whileTap={{ scaleX: 1.05, scaleY: 0.95 }}
                transition={spring.jellyGentle}
                className={`flex items-center gap-2.5 w-full px-3 py-2 text-[13px] rounded-[8px] transition-colors bg-transparent border-none cursor-pointer text-left relative ${
                  active
                    ? 'text-text-primary font-medium'
                    : isPro
                      ? 'text-amber-500 hover:text-amber-400'
                      : 'text-text-secondary hover:text-text-primary'
                }`}
              >
                {active && (
                  <motion.div
                    layoutId="nav-indicator"
                    className="absolute inset-0 jelly-nav-active"
                    transition={spring.jellyGentle}
                  />
                )}
                <span className="relative z-10 flex items-center gap-2.5">
                  <Crown size={16} className={isPro ? 'text-amber-500' : ''} />
                  {isPro ? t('nav.pro') : t('nav.upgrade')}
                </span>
              </motion.button>
            )
          })()}
        </nav>

        {/* Bottom: Account */}
        <div className="px-3 pb-3 mt-auto border-t border-border pt-3">
          {(() => {
            const { id, labelKey, icon: Icon } = bottomNavItem
            const active = route === id
            const label = t(labelKey)
            return (
              <motion.button
                onClick={() => navigate(id)}
                whileHover={{ scale: 1.03 }}
                whileTap={{ scaleX: 1.05, scaleY: 0.95 }}
                transition={spring.jellyGentle}
                className={`flex items-center gap-2.5 w-full px-3 py-2 text-[13px] rounded-[8px] transition-colors bg-transparent border-none cursor-pointer text-left relative ${
                  active
                    ? 'text-text-primary font-medium'
                    : 'text-text-secondary hover:text-text-primary'
                }`}
              >
                {active && (
                  <motion.div
                    layoutId="nav-indicator"
                    className="absolute inset-0 jelly-nav-active"
                    transition={spring.jellyGentle}
                  />
                )}
                <span className="relative z-10 flex items-center gap-2.5">
                  <Icon size={16} />
                  {label}
                </span>
              </motion.button>
            )
          })()}
        </div>
      </aside>

      {/* Content */}
      <main className="flex-1 min-w-0 flex flex-col">
        <AccessibilityBanner />
        <div className="flex-1 overflow-y-auto">{children}</div>
      </main>
    </div>
  )
}
