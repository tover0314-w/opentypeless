import { useTranslation } from 'react-i18next'
import { openUrl } from '@tauri-apps/plugin-opener'
import { BookOpen, ExternalLink, MessageCircleQuestion, Sparkles, ScrollText } from 'lucide-react'
import { APP_REPO_URL } from '../../lib/constants'
import type { PracticeOptions } from '../../lib/tauri'

interface Props {
  onExploreFeatures?: (mode: PracticeOptions['mode']) => void
}

export function HelpPane({ onExploreFeatures }: Props) {
  const { t } = useTranslation()

  const links = [
    { key: 'docs', icon: BookOpen, url: `${APP_REPO_URL}#readme` },
    { key: 'releaseNotes', icon: ScrollText, url: `${APP_REPO_URL}/releases` },
    { key: 'feedback', icon: MessageCircleQuestion, url: `${APP_REPO_URL}/issues` },
  ] as const

  return (
    <div className="space-y-6 text-[14px]">
      <div className="flex items-center justify-between gap-6 rounded-[16px] bg-fill-thin p-5">
        <div className="min-w-0">
          <div className="flex items-center gap-2 font-medium text-text-primary">
            <Sparkles size={16} className="text-accent" />
            {t('settingsModal.help.exploreTitle')}
          </div>
          <p className="mt-1 text-text-tertiary">{t('settingsModal.help.exploreDescription')}</p>
        </div>
        <div className="flex shrink-0 flex-wrap gap-2">
          {(['dictate', 'translate', 'ask'] as const).map((mode) => (
            <button
              key={mode}
              type="button"
              onClick={() => onExploreFeatures?.(mode)}
              disabled={!onExploreFeatures}
              className="cursor-pointer rounded-full border border-border bg-bg-elevated px-4 py-2 text-[14px] font-medium disabled:cursor-not-allowed disabled:opacity-40"
            >
              {t(`home.modes.${mode}.title`)}
            </button>
          ))}
        </div>
      </div>

      <div className="divide-y divide-border-thin">
        {links.map(({ key, icon: Icon, url }) => (
          <button
            key={key}
            type="button"
            onClick={() => openUrl(url)}
            className="flex w-full items-center justify-between border-none bg-transparent px-1 py-3.5 text-left cursor-pointer text-text-primary hover:text-accent"
          >
            <span className="flex items-center gap-2.5">
              <Icon size={16} strokeWidth={1.8} className="text-text-tertiary" />
              {t(`settingsModal.help.${key}`)}
            </span>
            <ExternalLink size={14} className="text-text-tertiary" />
          </button>
        ))}
      </div>
    </div>
  )
}
