import { describe, expect, it } from 'vitest'
import { activityLevel, buildHeatmap } from '../heatmap'

describe('activity heatmap', () => {
  it('buckets daily words into usage levels', () => {
    expect(activityLevel(0)).toBe(0)
    expect(activityLevel(40)).toBe(1)
    expect(activityLevel(300)).toBe(2)
    expect(activityLevel(1200)).toBe(3)
    expect(activityLevel(5000)).toBe(4)
  })

  it('lays out Sunday-first week columns ending with the current week', () => {
    // Saturday 31 October 2026.
    const today = new Date(2026, 9, 31)
    const columns = buildHeatmap(
      [
        { date: '2026-10-31', words: 120, sessions: 2 },
        { date: '2026-10-25', words: 20, sessions: 1 },
      ],
      today,
      4,
    )

    expect(columns).toHaveLength(4)
    expect(columns.every((column) => column.length === 7)).toBe(true)
    expect(columns[3][0].date).toBe('2026-10-25')
    expect(columns[3][0].level).toBe(1)
    expect(columns[3][6]).toMatchObject({ date: '2026-10-31', words: 120, level: 2 })
    expect(columns[0][0].date).toBe('2026-10-04')
    expect(columns.flat().some((cell) => cell.inFuture)).toBe(false)
  })

  it('marks days after today in the current week as future', () => {
    // Wednesday 28 October 2026.
    const columns = buildHeatmap([], new Date(2026, 9, 28), 1)
    expect(columns[0].map((cell) => cell.inFuture)).toEqual([
      false,
      false,
      false,
      false,
      true,
      true,
      true,
    ])
  })
})
