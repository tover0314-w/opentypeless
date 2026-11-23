import {
  Home,
  History,
  BookOpen,
  Cpu,
  CircleUser,
  Settings,
  CircleHelp,
  Github,
} from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { openUrl } from '@tauri-apps/plugin-opener'
import { useRoute, type Page } from '../../lib/router'
import { hasManagedCloudAccess, useAuthStore } from '../../stores/authStore'
import { APP_REPO_URL } from '../../lib/constants'
import { AccessibilityBanner } from './AccessibilityBanner'
import { CloudServiceBanner } from './CloudServiceBanner'
import { BrandMark } from './BrandMark'
import { UsageCard } from './UsageCard'

const NAV_ITEMS: { id: Page; labelKey: string; icon: typeof Home }[] = [
  { id: 'home', labelKey: 'nav.home', icon: Home },
  { id: 'models', labelKey: 'nav.models', icon: Cpu },
  { id: 'history', labelKey: 'nav.history', icon: History },
  { id: 'dictionary', labelKey: 'nav.dictionary', icon: BookOpen },
]

const isMac = typeof navigator !== 'undefined' && /mac/i.test(navigator.platform)

interface Props {
  children: React.ReactNode
}

export function MainLayout({ children }: Props) {
  const { page, navigate } = useRoute()
  const hasCloudAccess = useAuthStore(hasManagedCloudAccess)
  const plan = useAuthStore((s) => s.plan)
  const user = useAuthStore((s) => s.user)
  const { t } = useTranslation()

  const planLabel = !user
    ? null
    : hasCloudAccess
      ? plan === 'lifetime_starter'
        ? t('shell.planLifetime')
        : t('nav.pro')
      : t('shell.planFree')

  return (
    <div className="flex h-full w-full bg-bg-primary text-text-primary">
      <aside className="flex w-[210px] shrink-0 flex-col py-2 pl-2">
        <div className="flex h-full flex-col rounded-[20px] bg-fill-thin px-3">
          <div
            data-tauri-drag-region
            className={`flex items-center gap-2 px-2 pb-4 ${isMac ? 'pt-[52px]' : 'pt-5'}`}
          >
            <BrandMark size={20} className="text-text-primary" />
            <span className="text-[16px] font-semibold tracking-tight">{t('app.name')}</span>
            {planLabel && (
              <span
                className={`ml-auto rounded-[6px] px-1.5 py-0.5 text-[11px] font-medium ${
                  hasCloudAccess ? 'bg-accent/10 text-accent' : 'bg-fill-tough text-text-tertiary'
                }`}
              >
                {planLabel}
              </span>
            )}
          </div>

          <nav className="space-y-0.5" aria-label={t('nav.mainNavigation')}>
            {NAV_ITEMS.map(({ id, labelKey, icon: Icon }) => {
              const active = page === id
              const label = t(labelKey)
              return (
                <button
                  key={id}
                  type="button"
                  onClick={() => navigate(id)}
                  aria-label={label}
                  aria-current={active ? 'page' : undefined}
                  className={`flex w-full items-center gap-2.5 rounded-[10px] border-none px-2.5 py-2.5 text-left text-[14px] transition-colors cursor-pointer ${
                    active
                      ? 'bg-fill-selected text-text-primary'
                      : 'bg-transparent text-text-secondary hover:bg-fill-hover hover:text-text-primary'
                  }`}
                >
                  <Icon size={17} strokeWidth={1.8} />
                  {label}
                </button>
              )
            })}
          </nav>

          <div className="mt-auto space-y-3 pb-3">
            <UsageCard onUpgrade={() => navigate('upgrade')} />
            <div className="flex items-center border-t border-border-thin pt-3">
              <IconButton
                label={t('nav.account')}
                onClick={() => navigate('settings', { pane: 'account' })}
              >
                <CircleUser size={17} strokeWidth={1.8} />
              </IconButton>
              <div className="ml-auto flex items-center gap-1">
                <IconButton label={t('shell.github')} onClick={() => openUrl(APP_REPO_URL)}>
                  <Github size={17} strokeWidth={1.8} />
                </IconButton>
                <IconButton label={t('nav.settings')} onClick={() => navigate('settings')}>
                  <Settings size={17} strokeWidth={1.8} />
                </IconButton>
                <IconButton
                  label={t('settingsModal.tabs.help')}
                  onClick={() => navigate('settings', { pane: 'help' })}
                >
                  <CircleHelp size={17} strokeWidth={1.8} />
                </IconButton>
              </div>
            </div>
          </div>
        </div>
      </aside>

      <main className="flex min-w-[540px] flex-1 flex-col">
        <div data-tauri-drag-region className="h-[12px] shrink-0" />
        <AccessibilityBanner />
        <CloudServiceBanner />
        <div
          className={`min-h-0 flex-1 ${page === 'home' ? 'overflow-hidden' : 'overflow-y-auto'}`}
        >
          {children}
        </div>
      </main>
    </div>
  )
}

function IconButton({
  label,
  onClick,
  children,
}: {
  label: string
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={onClick}
      className="grid h-8 w-8 place-items-center rounded-[6px] border-none bg-transparent text-text-secondary cursor-pointer hover:bg-fill-hover hover:text-text-primary"
    >
      {children}
    </button>
  )
}
