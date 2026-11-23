import { describe, expect, it } from 'vitest'
import type { UsageStats } from '../../../lib/tauri'
import {
  buildShareViewModel,
  durationParts,
  formatCompactCount,
  shareFileName,
  shareHeatmapCells,
  shareMonthLabels,
  xShareUrl,
} from '../shareInsightsModel'

const t = (key: string, options?: Record<string, unknown>) =>
  options?.count === undefined ? key : `${key}:${String(options.count)}`

function stats(overrides: Partial<UsageStats> = {}): UsageStats {
  return {
    totalWords: 12_345,
    totalSessions: 30,
    totalAudioSeconds: 5_460,
    averageWpm: 142,
    minutesSaved: 75,
    activeDays: 1,
    currentStreak: 3,
    longestStreak: 9,
    daily: [
      { date: '2026-10-20', words: 40, sessions: 1 },
      { date: '2026-11-02', words: 2_000, sessions: 4 },
    ],
    ...overrides,
  }
}

describe('share insights model', () => {
  it('formats counts the way the Typeless share card does', () => {
    expect(formatCompactCount(999)).toBe('999')
    expect(formatCompactCount(12_345)).toBe('12.3K')
    expect(formatCompactCount(120_400)).toBe('120K')
    expect(formatCompactCount(1_500_000)).toBe('1.5M')
  })

  it('splits durations into minutes, hours plus minutes, or whole hours', () => {
    expect(durationParts(59 * 60)).toEqual([{ value: '59', unit: 'min' }])
    expect(durationParts(91 * 60)).toEqual([
      { value: '1', unit: 'hr' },
      { value: '31', unit: 'min' },
    ])
    expect(durationParts(26 * 3_600)).toEqual([{ value: '26', unit: 'hr' }])
  })

  it('builds 13 Sunday-first weeks with blank future days and no-data days before the first use', () => {
    const today = new Date(2026, 10, 3)
    const cells = shareHeatmapCells(stats().daily, today)
    expect(cells).toHaveLength(91)
    expect(cells[0].date).toBe('2026-08-09')
    expect(new Date(`${cells[0].date}T00:00:00`).getDay()).toBe(0)
    expect(cells[cells.length - 1].date).toBe('2026-11-07')
    expect(cells.find((cell) => cell.date === '2026-10-19')?.variant).toBe('noData')
    expect(cells.find((cell) => cell.date === '2026-10-20')?.variant).toBe(1)
    expect(cells.find((cell) => cell.date === '2026-10-21')?.variant).toBe(0)
    expect(cells.find((cell) => cell.date === '2026-11-02')?.variant).toBe(4)
    expect(cells.find((cell) => cell.date === '2026-11-03')?.variant).toBe(0)
    expect(cells.find((cell) => cell.date === '2026-11-04')?.variant).toBe('blank')
  })

  it('places a month label on the week column containing the 1st', () => {
    const cells = shareHeatmapCells([], new Date(2026, 10, 3))
    expect(shareMonthLabels(cells, 'en')).toEqual([
      { columnIndex: 3, text: 'Sep' },
      { columnIndex: 7, text: 'Oct' },
      { columnIndex: 12, text: 'Nov' },
    ])
  })

  it('maps usage stats onto highlight tiles and activity stats', () => {
    const model = buildShareViewModel(stats(), t, 'en', new Date(2026, 10, 3))
    expect(model.highlights.map((metric) => metric.parts)).toEqual([
      [{ value: '12.3K', unit: 'home.stats.wordsUnit' }],
      [
        { value: '1', unit: 'home.stats.hoursUnit' },
        { value: '15', unit: 'home.stats.minutesUnit' },
      ],
      [{ value: '142', unit: 'home.stats.wpmUnit' }],
      [
        { value: '1', unit: 'home.stats.hoursUnit' },
        { value: '31', unit: 'home.stats.minutesUnit' },
      ],
    ])
    expect(model.activity.stats[0].parts).toEqual([{ value: '1', unit: 'home.share.dayUnit:1' }])
    expect(model.titles.insights).toBe('home.share.previewTitle.insights')
  })

  it('names exports after the card kind and date and encodes the X post text', () => {
    expect(shareFileName('highlights', new Date(2026, 0, 5))).toBe(
      'OpenTypeless-highlights-2026-01-05.png',
    )
    expect(xShareUrl('Here are my OpenTypeless insights.')).toBe(
      'https://x.com/intent/post?text=Here%20are%20my%20OpenTypeless%20insights.',
    )
  })
})
