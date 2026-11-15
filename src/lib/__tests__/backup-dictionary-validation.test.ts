import { describe, expect, it } from 'vitest'
import { validBackupDictionary } from '../backup-dictionary-validation'

describe('backup dictionary validation', () => {
  it('accepts Unicode character limits and optional fields without mutating input', () => {
    const payload = {
      entries: [
        { word: '😀'.repeat(100), pronunciation: null, auto: true },
        { word: '\0  Term  \0', pronunciation: '𠮷'.repeat(100) },
      ],
      correctionRules: [
        { pattern: '😀'.repeat(120), replacement: '𠮷'.repeat(120), enabled: false },
      ],
    }
    const original = JSON.stringify(payload)
    expect(validBackupDictionary(payload)).toBe(true)
    expect(JSON.stringify(payload)).toBe(original)
  })

  it.each([10_000, 10_001])('bounds dictionary and correction counts at %s', (count) => {
    const entries = Array.from({ length: count }, () => ({ word: 'Term' }))
    const rules = Array.from({ length: count }, () => ({
      pattern: 'wrong',
      replacement: 'right',
    }))
    expect(validBackupDictionary(entries)).toBe(count <= 10_000)
    expect(validBackupDictionary({ entries: [], correction_rules: rules })).toBe(count <= 10_000)
  })

  it.each([
    [{ word: '😀'.repeat(101) }],
    [{ word: 'Term', pronunciation: ' ' }],
    [{ word: 'Term', auto: null }],
    { entries: [], correction_rules: [{ pattern: 'wrong', replacement: ' ' }] },
    { entries: [], correction_rules: [{ pattern: 'wrong', replacement: 'right', enabled: null }] },
  ])('rejects invalid text and optional boolean fields', (payload) => {
    expect(validBackupDictionary(payload)).toBe(false)
  })
})
