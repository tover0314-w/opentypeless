const MAX_ENTRIES = 10_000

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function validText(value: unknown, limit: number): boolean {
  if (typeof value !== 'string') return false
  const cleaned = value.replace(/\0/g, '').trim()
  return cleaned.length > 0 && Array.from(cleaned).length <= limit
}

function validEntries(value: unknown): boolean {
  return (
    Array.isArray(value) &&
    value.length <= MAX_ENTRIES &&
    value.every(
      (entry) =>
        record(entry) &&
        validText(entry.word, 100) &&
        (entry.pronunciation == null || validText(entry.pronunciation, 100)) &&
        (entry.auto === undefined || typeof entry.auto === 'boolean'),
    )
  )
}

function validCorrections(value: unknown): boolean {
  return (
    value === undefined ||
    (Array.isArray(value) &&
      value.length <= MAX_ENTRIES &&
      value.every(
        (rule) =>
          record(rule) &&
          validText(rule.pattern, 120) &&
          validText(rule.replacement, 120) &&
          (rule.enabled === undefined || typeof rule.enabled === 'boolean'),
      ))
  )
}

export function validBackupDictionary(value: unknown): boolean {
  if (value == null) return true
  if (Array.isArray(value)) return validEntries(value)
  return (
    record(value) &&
    !('correction_rules' in value && 'correctionRules' in value) &&
    validEntries(value.entries) &&
    validCorrections(value.correction_rules) &&
    validCorrections(value.correctionRules)
  )
}
