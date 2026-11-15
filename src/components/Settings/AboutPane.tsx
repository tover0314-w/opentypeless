import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ArrowUpRight } from 'lucide-react'
import { openUrl } from '@tauri-apps/plugin-opener'
import { APP_LICENSE_URL, APP_REPO_URL } from '../../lib/constants'
import { useAppVersion } from '../../hooks/useAppVersion'
import { siteUrl } from '../../lib/siteLinks'
import { CheckUpdateDialog } from './CheckUpdateDialog'

/** Typeless About: version + check for updates, then legal links; OpenTypeless adds its source rows. */
export function AboutPane({ autoCheck = null }: { autoCheck?: string | null }) {
  const { t, i18n } = useTranslation()
  const [checking, setChecking] = useState(false)
  const version = useAppVersion()

  // Typeless opens the dialog shortly after the pane when reached from "Check for updates...".
  useEffect(() => {
    if (!autoCheck) return
    const timer = window.setTimeout(() => setChecking(true), 1100)
    return () => window.clearTimeout(timer)
  }, [autoCheck])

  const links = [
    { title: t('settings.aboutPage.privacy'), url: siteUrl('privacy', i18n.language) },
    { title: t('settings.aboutPage.terms'), url: siteUrl('terms', i18n.language) },
    { title: t('settings.aboutPage.sourceCode'), url: APP_REPO_URL, value: 'GitHub' },
    { title: t('settings.aboutPage.license'), url: APP_LICENSE_URL, value: t('settings.mit') },
  ]

  return (
    <div className="flex flex-col">
      <AboutRow title={t('settings.aboutPage.version')} description={`v${version}`}>
        <button
          type="button"
          onClick={() => setChecking(true)}
          className="h-8 cursor-pointer rounded-[20px] border border-border bg-transparent px-3 text-[14px] leading-5 text-text-primary hover:bg-fill-hover"
        >
          {t('settings.aboutPage.checkUpdates')}
        </button>
      </AboutRow>
      {links.map((link) => (
        <button
          key={link.title}
          type="button"
          onClick={() => openUrl(link.url)}
          className="group w-full cursor-pointer border-none bg-transparent p-0 text-left"
        >
          <AboutRow title={link.title}>
            <span className="flex items-center gap-2 text-[14px] text-text-tertiary group-hover:text-text-secondary">
              {link.value}
              <ArrowUpRight size={16} />
            </span>
          </AboutRow>
        </button>
      ))}
      <AboutRow title={t('settings.framework')} description={t('settings.tauriReact')} />
      {checking ? <CheckUpdateDialog onClose={() => setChecking(false)} /> : null}
    </div>
  )
}

function AboutRow({
  title,
  description,
  children,
}: {
  title: string
  description?: string
  children?: React.ReactNode
}) {
  return (
    <div className="flex min-h-[52px] items-center gap-4 py-2">
      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <span className="text-[14px] font-medium leading-5 text-text-primary">{title}</span>
        {description ? (
          <span className="text-[14px] leading-5 text-text-tertiary">{description}</span>
        ) : null}
      </div>
      {children ? <div className="shrink-0">{children}</div> : null}
    </div>
  )
}
