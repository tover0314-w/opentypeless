import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { openUrl } from '@tauri-apps/plugin-opener'
import { Clock3, Gauge, Hourglass, Mic, Server } from 'lucide-react'
import { useAppStore, type HotkeyConfig, type ShortcutBinding } from '../../stores/appStore'
import { getUsageStats, type UsageStats } from '../../lib/tauri'
import { buildHeatmap } from '../../lib/heatmap'
import { LLM_PROVIDERS, STT_PROVIDERS } from '../../lib/constants'
import { APP_ICON_BY_KEY } from '../../assets/app-icons/manifest'
import { PageContainer } from '../shared/Page'
import { Keycaps } from '../shared/Keycaps'

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

function bindingText(binding: ShortcutBinding | null | undefined) {
  return binding ? [...binding.modifiers, binding.primary].join('+') : null
}

function shortcutRows(hotkeys: HotkeyConfig) {
  return [
    { key: 'dictate', hotkey: bindingText(hotkeys.dictation), accent: '' },
    { key: 'translate', hotkey: bindingText(hotkeys.translate), accent: 'bg-accent/10' },
    { key: 'ask', hotkey: bindingText(hotkeys.ask), accent: 'bg-[rgba(120,214,255,0.14)]' },
  ]
}

export function HomePage() {
  const config = useAppStore((s) => s.config)
  const historyCount = useAppStore((s) => s.history.length)
  const { t } = useTranslation()
  const [stats, setStats] = useState<UsageStats | null>(null)

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
  }, [historyCount])

  const sttLabel = STT_PROVIDERS.find((item) => item.value === config.stt_provider)?.labelKey
  const llmLabel = LLM_PROVIDERS.find((item) => item.value === config.llm_provider)?.labelKey

  return (
    <PageContainer>
      <h1 className="mb-6 text-[48px] font-bold leading-[56px] tracking-tight text-text-primary">
        {t('home.title')}
      </h1>

      <div className="flex gap-6">
        <div className="min-w-0 flex-1 space-y-6">
          <ShortcutsCard hotkeys={config.hotkeys} />
          <InsightsCard stats={stats} />
          <ActivityCard stats={stats} />
          <UseCases />
        </div>

        <aside className="w-[220px] shrink-0 space-y-4">
          <section className="surface-muted space-y-3 p-4">
            <h2 className="text-[13px] font-medium text-text-tertiary">
              {t('home.currentConfig')}
            </h2>
            <ConfigRow icon={Mic} label={t('home.sttProvider')}>
              {sttLabel ? t(sttLabel) : config.stt_provider}
            </ConfigRow>
            <ConfigRow icon={Server} label={t('home.llmProvider')}>
              {config.polish_enabled
                ? llmLabel
                  ? t(llmLabel)
                  : config.llm_provider
                : t('home.disabled')}
            </ConfigRow>
          </section>
        </aside>
      </div>
    </PageContainer>
  )
}

function ConfigRow({
  icon: Icon,
  label,
  children,
}: {
  icon: typeof Mic
  label: string
  children: React.ReactNode
}) {
  return (
    <div>
      <div className="flex items-center gap-1.5 text-[12px] text-text-tertiary">
        <Icon size={13} />
        {label}
      </div>
      <div className="mt-0.5 truncate text-[14px] font-medium text-text-primary">{children}</div>
    </div>
  )
}

function ShortcutsCard({ hotkeys }: { hotkeys: HotkeyConfig }) {
  const { t } = useTranslation()
  return (
    <section className="surface-card divide-y divide-border-thin px-5">
      {shortcutRows(hotkeys).map((row) => (
        <div key={row.key} className="flex items-center justify-between gap-6 py-4">
          <div>
            <div className="text-[16px] font-semibold text-text-primary">
              {t(`home.modes.${row.key}.title`)}
            </div>
            <div className="mt-0.5 text-[14px] text-text-tertiary">
              {t(`home.modes.${row.key}.description`)}
            </div>
          </div>
          {row.hotkey ? (
            <Keycaps hotkey={row.hotkey} className={`rounded-[8px] p-1 ${row.accent}`} />
          ) : (
            <span className="text-[13px] text-text-tertiary">{t('home.shortcutOff')}</span>
          )}
        </div>
      ))}
    </section>
  )
}

function InsightsCard({ stats }: { stats: UsageStats | null }) {
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
    <section>
      <h2 className="mb-3 text-[24px] font-medium leading-7 text-text-tertiary">
        {t('home.insights')}
      </h2>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
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
            onClick={() => openUrl(useCase.url)}
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
