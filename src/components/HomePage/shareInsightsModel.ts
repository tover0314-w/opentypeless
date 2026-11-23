import type { DailyActivity, UsageStats } from '../../lib/tauri'
import { activityLevel } from '../../lib/heatmap'

export type ShareCardKind = 'insights' | 'highlights' | 'activity'

export const SHARE_CARD_KINDS: ShareCardKind[] = ['insights', 'highlights', 'activity']

export const SHARE_HEATMAP_WEEKS = 13

export interface MetricPart {
  value: string
  unit: string
}

export interface ShareMetric {
  key: string
  parts: MetricPart[]
  label: string
}

export type ShareHeatCell =
  | { date: string; variant: 'blank' | 'noData' }
  | { date: string; variant: 0 | 1 | 2 | 3 | 4 }

export interface ShareMonthLabel {
  columnIndex: number
  text: string
}

export interface ShareViewModel {
  titles: Record<ShareCardKind, string>
  highlights: ShareMetric[]
  activity: {
    stats: ShareMetric[]
    cells: ShareHeatCell[]
    monthLabels: ShareMonthLabel[]
  }
}

type Translate = (key: string, options?: Record<string, unknown>) => string

function roundTo(value: number, digits: number) {
  const factor = 10 ** digits
  return Math.round(value * factor) / factor
}

/** Typeless compact count: 999, 1.2K, 120K, 1.5M, 2B. */
export function formatCompactCount(value: number) {
  const rounded = Math.round(value)
  if (rounded < 1_000) return String(rounded)
  if (rounded < 100_000) return `${roundTo(rounded / 1_000, 1)}K`
  if (rounded < 1_000_000) return `${Math.round(rounded / 1_000)}K`
  if (rounded < 100_000_000) return `${roundTo(rounded / 1_000_000, 1)}M`
  if (rounded < 1_000_000_000) return `${Math.round(rounded / 1_000_000)}M`
  return `${roundTo(rounded / 1_000_000_000, 1)}B`
}

/** Minutes under an hour, hours plus minutes under a day, otherwise whole hours. */
export function durationParts(seconds: number): Array<{ value: string; unit: 'hr' | 'min' }> {
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return [{ value: String(minutes), unit: 'min' }]
  const hours = Math.floor(minutes / 60)
  if (minutes < 1_440) {
    return [
      { value: String(hours), unit: 'hr' },
      { value: String(minutes % 60), unit: 'min' },
    ]
  }
  const value = hours >= 999_999 ? '999,999+' : new Intl.NumberFormat('en-US').format(hours)
  return [{ value, unit: 'hr' }]
}

function toKey(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/**
 * The last 13 Sunday-first weeks ending on this week's Saturday. Days after today are blank
 * and days before the first recorded dictation have no data.
 */
export function shareHeatmapCells(daily: DailyActivity[], today: Date): ShareHeatCell[] {
  const words = new Map(daily.map((day) => [day.date, day.words]))
  const firstDay = daily.reduce<string | null>(
    (first, day) => (first === null || day.date < first ? day.date : first),
    null,
  )
  const todayKey = toKey(today)
  const cursor = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  cursor.setDate(cursor.getDate() + (6 - cursor.getDay()) - (SHARE_HEATMAP_WEEKS * 7 - 1))

  const cells: ShareHeatCell[] = []
  for (let index = 0; index < SHARE_HEATMAP_WEEKS * 7; index += 1) {
    const date = toKey(cursor)
    if (date > todayKey) cells.push({ date, variant: 'blank' })
    else if (firstDay === null || date < firstDay) cells.push({ date, variant: 'noData' })
    else cells.push({ date, variant: activityLevel(words.get(date) ?? 0) })
    cursor.setDate(cursor.getDate() + 1)
  }
  return cells
}

/** One label per month, placed on the week column that contains the 1st. */
export function shareMonthLabels(cells: ShareHeatCell[], language: string): ShareMonthLabel[] {
  const format = new Intl.DateTimeFormat(language, { month: 'short' })
  const labels: ShareMonthLabel[] = []
  for (let column = 0; column * 7 < cells.length; column += 1) {
    const first = cells.slice(column * 7, column * 7 + 7).find((cell) => cell.date.endsWith('-01'))
    if (first) {
      labels.push({ columnIndex: column, text: format.format(new Date(`${first.date}T00:00:00`)) })
    }
  }
  return labels
}

export function canShareInsights(stats: UsageStats | null): stats is UsageStats {
  return stats !== null && stats.totalSessions > 0
}

export function buildShareViewModel(
  stats: UsageStats,
  t: Translate,
  language: string,
  today: Date,
): ShareViewModel {
  const durationUnit = { hr: t('home.stats.hoursUnit'), min: t('home.stats.minutesUnit') }
  const duration = (seconds: number) =>
    durationParts(seconds).map((part) => ({ value: part.value, unit: durationUnit[part.unit] }))
  const days = (count: number) => [
    { value: formatCompactCount(count), unit: t('home.share.dayUnit', { count }) },
  ]
  const cells = shareHeatmapCells(stats.daily, today)

  return {
    titles: {
      insights: t('home.share.previewTitle.insights'),
      highlights: t('home.share.previewTitle.highlights'),
      activity: t('home.share.previewTitle.activity'),
    },
    highlights: [
      {
        key: 'words',
        parts: [{ value: formatCompactCount(stats.totalWords), unit: t('home.stats.wordsUnit') }],
        label: t('home.stats.words'),
      },
      { key: 'saved', parts: duration(stats.minutesSaved * 60), label: t('home.stats.saved') },
      {
        key: 'wpm',
        parts: [
          { value: formatCompactCount(stats.averageWpm ?? 0), unit: t('home.stats.wpmUnit') },
        ],
        label: t('home.stats.wpm'),
      },
      { key: 'time', parts: duration(stats.totalAudioSeconds), label: t('home.stats.time') },
    ],
    activity: {
      stats: [
        { key: 'activeDays', parts: days(stats.activeDays), label: t('home.activity.activeDays') },
        {
          key: 'currentStreak',
          parts: days(stats.currentStreak),
          label: t('home.activity.currentStreak'),
        },
        {
          key: 'longestStreak',
          parts: days(stats.longestStreak),
          label: t('home.activity.longestStreak'),
        },
      ],
      cells,
      monthLabels: shareMonthLabels(cells, language),
    },
  }
}

export function shareFileName(kind: ShareCardKind, date: Date) {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `OpenTypeless-${kind}-${date.getFullYear()}-${month}-${day}.png`
}

export function xShareUrl(text: string) {
  return `https://x.com/intent/post?text=${encodeURIComponent(text.replace(/[\uD800-\uDFFF](?![\uDC00-\uDFFF])/g, '\uFFFD'))}`
}
