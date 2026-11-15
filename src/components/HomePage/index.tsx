import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ArrowUpRight, Clock3, Gauge, Github, Hourglass, Mic, Server, Share } from 'lucide-react'
import { openUrl } from '@tauri-apps/plugin-opener'
import { useAppStore } from '../../stores/appStore'
import { getUsageStats, type UsageStats } from '../../lib/tauri'
import { buildHeatmap } from '../../lib/heatmap'
import { APP_REPO_URL, LLM_PROVIDERS, STT_PROVIDERS } from '../../lib/constants'
import { useRoute } from '../../lib/router'
import { useAppVersion } from '../../hooks/useAppVersion'
import { CheckUpdateDialog } from '../Settings/CheckUpdateDialog'
import { APP_ICON_BY_KEY } from '../../assets/app-icons/manifest'
import { PageContainer } from '../shared/Page'
import { ShortcutsCard } from './ShortcutsCard'
import { ShareInsightsDialog } from './ShareInsightsDialog'
import { useUseCaseVoiceHint } from '../../hooks/useUseCaseVoiceHint'
import { QuickSettings } from './QuickSettings'
import { ProviderLogo } from '../shared/ProviderLogo'

const USE_CASES = [
  { key: 'gmail', icon: 'gmail', url: 'https://mail.google.com/' },
  { key: 'slack', icon: 'slack', url: 'https://app.slack.com/' },
  { key: 'chatgpt', icon: 'chatgpt', url: 'https://chatgpt.com/' },
  { key: 'googleDocs', icon: 'google-docs', url: 'https://docs.google.com/' },
  { key: 'notion', icon: 'notion', url: 'https://www.notion.so/' },
  { key: 'claude', icon: 'claude', url: 'https://claude.ai/' },
  { key: 'cursor', icon: 'cursor', url: 'https://cursor.com/' },
  { key: 'linear', icon: 'linear', url: 'https://linear.app/' },
  { key: 'lark', icon: 'lark', url: 'https://www.larksuite.com/' },
] as const

const LEVEL_CLASS = [
  'bg-fill-tough',
  'bg-accent/25',
  'bg-accent/50',
  'bg-accent/75',
  'bg-accent',
] as const

export function HomePage() {
  const { navigate } = useRoute()
  const version = useAppVersion()
  const [checkingUpdate, setCheckingUpdate] = useState(false)
  const config = useAppStore((s) => s.config)
  const history = useAppStore((s) => s.history)
  const { t } = useTranslation()
  const [stats, setStats] = useState<UsageStats | null>(null)
  const [sharing, setSharing] = useState<UsageStats | null>(null)

  useEffect(() => {
    let cancelled = false
    getUsageStats()
      .then((value) => {
        if (!cancelled) setStats(value)
      })
      .catch((error) => console.error('Failed to load usage stats:', error))
    return () => {
      cancelled = true
    }
  }, [history])

  const sttLabel = STT_PROVIDERS.find((item) => item.value === config.stt_provider)?.labelKey
  const llmLabel = LLM_PROVIDERS.find((item) => item.value === config.llm_provider)?.labelKey

  return (
    <div className="relative flex h-full min-h-0 flex-col">
      <div data-home-scroll="" className="min-h-0 flex-1 overflow-y-auto">
        <PageContainer className="pb-[140px]">
          <h1 className="mb-6 text-[48px] font-bold leading-[56px] tracking-tight text-text-primary">
            {t('home.title')}
          </h1>

          <div className="flex gap-6">
            <div className="min-w-0 flex-1 space-y-6">
              <ShortcutsCard />
              <InsightsCard stats={stats} onShare={() => stats && setSharing(stats)} />
              <ActivityCard stats={stats} />
              <UseCases />
            </div>

            <aside className="w-[220px] shrink-0 space-y-4">
              <section className="surface-muted space-y-3 p-4">
                <h2 className="text-[13px] font-medium text-text-tertiary">
                  {t('home.currentConfig')}
                </h2>
                <QuickSettings />
                <ConfigRow
                  icon={Mic}
                  provider={config.stt_provider}
                  label={t('home.sttProvider')}
                  onClick={() => navigate('models')}
                >
                  {sttLabel ? t(sttLabel) : config.stt_provider}
                </ConfigRow>
                <ConfigRow
                  icon={Server}
                  provider={config.llm_provider}
                  label={t('home.llmProvider')}
                  onClick={() => navigate('models')}
                  status={
                    !config.polish_enabled
                      ? `${t('settings.enableAiPolish')} · ${t('home.disabled')}`
                      : undefined
                  }
                >
                  {llmLabel ? t(llmLabel) : config.llm_provider}
                </ConfigRow>
              </section>
            </aside>
          </div>
        </PageContainer>
      </div>
      <footer
        data-home-footer=""
        className="absolute bottom-4 right-6 w-max max-w-[calc(100%-48px)]"
      >
        <div className="flex flex-col items-end gap-2">
          <button
            type="button"
            onClick={() => void openUrl(APP_REPO_URL).catch(() => {})}
            className="group flex min-h-9 cursor-pointer items-center gap-2 rounded-[8px] border border-border bg-fill-thin px-3 py-2 text-[13px] font-medium text-text-secondary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
          >
            <Github
              size={15}
              aria-hidden="true"
              className="shrink-0 transition-colors group-hover:text-accent"
            />
            <span>{t('home.starOnGitHub')}</span>
            <ArrowUpRight size={14} aria-hidden="true" className="text-text-tertiary" />
          </button>
          <div className="flex items-center gap-2 rounded-[6px] bg-bg-primary px-1 py-0.5 text-[12px] text-text-tertiary">
            <span>v{version}</span>
            <span aria-hidden="true">·</span>
            <button
              type="button"
              onClick={() => setCheckingUpdate(true)}
              className="cursor-pointer border-none bg-transparent p-0 text-text-secondary hover:text-accent"
            >
              {t('settings.aboutPage.checkUpdates')}
            </button>
          </div>
        </div>
      </footer>
      {sharing ? <ShareInsightsDialog stats={sharing} onClose={() => setSharing(null)} /> : null}
      {checkingUpdate ? <CheckUpdateDialog onClose={() => setCheckingUpdate(false)} /> : null}
    </div>
  )
}

function ConfigRow({
  icon: Icon,
  label,
  children,
  onClick,
  status,
  provider,
}: {
  icon: typeof Mic
  label: string
  children: React.ReactNode
  onClick: () => void
  status?: string
  provider?: string
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="group block w-full cursor-pointer border-none bg-transparent p-0 text-left"
    >
      <div className="flex items-center gap-1.5 text-[12px] text-text-tertiary">
        <Icon size={13} />
        {label}
      </div>
      <div className="mt-0.5 flex min-w-0 items-center gap-2 text-[14px] font-medium text-text-primary group-hover:text-accent">
        {provider && <ProviderLogo provider={provider} />}
        <span className="min-w-0 truncate">{children}</span>
      </div>
      {status && <div className="mt-0.5 text-[12px] text-text-tertiary">{status}</div>}
    </button>
  )
}

function InsightsCard({ stats, onShare }: { stats: UsageStats | null; onShare: () => void }) {
  const { t, i18n } = useTranslation()
  const number = new Intl.NumberFormat(i18n.language)
  const placeholder = '--'

  const items = [
    {
      key: 'words',
      icon: Mic,
      value: stats ? number.format(stats.totalWords) : placeholder,
      unit: t('home.stats.wordsUnit'),
    },
    {
      key: 'saved',
      icon: Hourglass,
      value: stats ? number.format(stats.minutesSaved) : placeholder,
      unit: t('home.stats.minutesUnit'),
    },
    {
      key: 'wpm',
      icon: Gauge,
      value: stats?.averageWpm != null ? number.format(stats.averageWpm) : placeholder,
      unit: t('home.stats.wpmUnit'),
    },
    {
      key: 'time',
      icon: Clock3,
      value: stats ? number.format(Math.round(stats.totalAudioSeconds / 60)) : placeholder,
      unit: t('home.stats.minutesUnit'),
    },
  ]

  return (
    <section className="@container">
      <div className="mb-3 flex items-center justify-between gap-3">
        <h2 className="text-[24px] font-medium leading-7 text-text-tertiary">
          {t('home.insights')}
        </h2>
        <button
          type="button"
          disabled={stats === null}
          onClick={onShare}
          className="flex h-8 cursor-pointer items-center gap-1.5 rounded-full border border-border-thin bg-bg-primary px-3 py-1.5 text-[14px] leading-4 text-text-primary transition-colors hover:bg-fill-hover disabled:cursor-default disabled:text-text-disabled disabled:hover:bg-bg-primary"
        >
          <Share size={14} />
          {t('home.share.button')}
        </button>
      </div>
      <div className="grid grid-cols-2 gap-3 @min-[880px]:grid-cols-4">
        {items.map(({ key, icon: Icon, value, unit }) => (
          <div key={key} className="surface-muted p-4">
            <div className="flex items-baseline gap-1">
              <span className="text-[24px] font-semibold tabular-nums text-text-primary">
                {value}
              </span>
              <span className="text-[12px] text-text-tertiary">{unit}</span>
            </div>
            <div className="mt-1 flex items-center gap-1.5 text-[14px] text-text-tertiary">
              <Icon size={14} />
              {t(`home.stats.${key}`)}
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}

function ActivityCard({ stats }: { stats: UsageStats | null }) {
  const { t, i18n } = useTranslation()
  const columns = useMemo(() => buildHeatmap(stats?.daily ?? [], new Date()), [stats])
  const dateFormat = new Intl.DateTimeFormat(i18n.language, { month: 'short', day: 'numeric' })

  const summary = [
    { key: 'activeDays', value: stats?.activeDays ?? 0 },
    { key: 'currentStreak', value: stats?.currentStreak ?? 0 },
    { key: 'longestStreak', value: stats?.longestStreak ?? 0 },
  ]

  return (
    <section className="surface-card p-4">
      <div className="mb-4 flex flex-wrap gap-6">
        {summary.map((item) => (
          <div key={item.key}>
            <div className="text-[20px] font-semibold tabular-nums text-text-primary">
              {t('home.activity.days', { count: item.value })}
            </div>
            <div className="text-[13px] text-text-tertiary">{t(`home.activity.${item.key}`)}</div>
          </div>
        ))}
      </div>
      <div
        className="flex gap-[3px] overflow-hidden"
        role="img"
        aria-label={t('home.activity.title')}
      >
        {columns.map((column) => (
          <div key={column[0].date} className="flex flex-col gap-[3px]">
            {column.map((cell) => (
              <div
                key={cell.date}
                title={
                  cell.inFuture
                    ? undefined
                    : `${dateFormat.format(new Date(`${cell.date}T00:00:00`))} · ${t('home.activity.words', { count: cell.words })}`
                }
                className={`h-[12px] w-[12px] rounded-[3px] ${
                  cell.inFuture ? 'bg-transparent' : LEVEL_CLASS[cell.level]
                }`}
              />
            ))}
          </div>
        ))}
      </div>
      <div className="mt-3 flex items-center justify-end gap-1 text-[11px] text-text-tertiary">
        {t('home.activity.less')}
        {LEVEL_CLASS.map((className) => (
          <span key={className} className={`h-[10px] w-[10px] rounded-[2px] ${className}`} />
        ))}
        {t('home.activity.more')}
      </div>
    </section>
  )
}

function UseCases() {
  const { t } = useTranslation()
  const openUseCase = useUseCaseVoiceHint()
  return (
    <section>
      <h2 className="mb-3 text-[20px] font-semibold text-text-primary">
        {t('home.useCases.title')}
      </h2>
      <div className="grid grid-cols-2 gap-3 xl:grid-cols-3">
        {USE_CASES.map((useCase) => (
          <button
            key={useCase.key}
            type="button"
            onClick={() => void openUseCase(useCase.key, useCase.url)}
            className="surface-card flex cursor-pointer flex-col items-start gap-3 p-5 text-left transition-colors hover:bg-fill-thin"
          >
            <img
              src={APP_ICON_BY_KEY[useCase.icon]}
              alt=""
              className="h-9 w-9 rounded-[8px]"
              draggable={false}
            />
            <div>
              <div className="text-[16px] font-semibold text-text-primary">
                {t(`home.useCases.${useCase.key}.name`)}
              </div>
              <div className="mt-1 text-[14px] leading-5 text-text-tertiary">
                {t(`home.useCases.${useCase.key}.description`)}
              </div>
            </div>
          </button>
        ))}
      </div>
    </section>
  )
}
