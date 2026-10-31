import type { DailyActivity } from './tauri'

export interface HeatmapCell {
  date: string
  words: number
  level: 0 | 1 | 2 | 3 | 4
  inFuture: boolean
}

function toKey(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/** Typeless activity levels: none, light, moderate, high, power usage. */
export function activityLevel(words: number): HeatmapCell['level'] {
  if (words <= 0) return 0
  if (words < 100) return 1
  if (words < 500) return 2
  if (words < 1500) return 3
  return 4
}

/**
 * Builds week columns (Sunday-first, 7 rows) ending in the week that contains `today`,
 * like a contribution graph.
 */
export function buildHeatmap(daily: DailyActivity[], today: Date, weeks = 20): HeatmapCell[][] {
  const words = new Map(daily.map((day) => [day.date, day.words]))
  const end = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  const start = new Date(end)
  start.setDate(end.getDate() - end.getDay() - (weeks - 1) * 7)

  const columns: HeatmapCell[][] = []
  const cursor = new Date(start)
  for (let week = 0; week < weeks; week += 1) {
    const column: HeatmapCell[] = []
    for (let day = 0; day < 7; day += 1) {
      const key = toKey(cursor)
      const count = words.get(key) ?? 0
      column.push({ date: key, words: count, level: activityLevel(count), inFuture: cursor > end })
      cursor.setDate(cursor.getDate() + 1)
    }
    columns.push(column)
  }
  return columns
}
