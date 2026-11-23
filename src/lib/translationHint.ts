const STORAGE_KEY = 'capsule.translationOnboarding'

export function recordTranslationHint(canTranslate: boolean): boolean {
  try {
    let stored: { count?: unknown; shown?: unknown } | null = null
    try {
      stored = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null')
    } catch {
      stored = null
    }
    const count =
      typeof stored?.count === 'number' && Number.isInteger(stored.count) && stored.count >= 0
        ? stored.count
        : 0
    if (stored?.shown === true) return false
    const show = count >= 10 && canTranslate
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ count: Math.min(count + 1, 10), shown: show }),
    )
    return show
  } catch {
    return false
  }
}
