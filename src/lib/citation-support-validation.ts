export function validCitationSupports(
  value: unknown,
  answer: unknown,
  citations: unknown,
): boolean {
  if (value === undefined) return true
  if (
    !Array.isArray(value) ||
    value.length > 100 ||
    typeof answer !== 'string' ||
    !Array.isArray(citations)
  )
    return false
  const sourceUrls = new Set<string>()
  for (const source of citations) {
    if (
      !source ||
      typeof source !== 'object' ||
      typeof source.url !== 'string' ||
      typeof source.title !== 'string' ||
      source.title.trim() !== source.title ||
      Object.keys(source).length !== 2
    )
      return false
    try {
      const normalized = new URL(source.url).href
      if (normalized !== source.url || sourceUrls.has(normalized)) return false
      sourceUrls.add(normalized)
    } catch {
      return false
    }
  }
  let asciiEnd = 0
  while (asciiEnd < answer.length && answer.charCodeAt(asciiEnd) <= 127) asciiEnd += 1
  const seen = new Set<string>()
  return value.every((entry: unknown) => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return false
    const support = entry as Record<string, unknown>
    const { start, end, sourceIndex } = support
    if (
      Object.keys(support).length !== 3 ||
      typeof start !== 'number' ||
      !Number.isSafeInteger(start) ||
      start < 0 ||
      typeof end !== 'number' ||
      !Number.isSafeInteger(end) ||
      end <= start ||
      end > asciiEnd ||
      end - start > 4096 ||
      typeof sourceIndex !== 'number' ||
      !Number.isSafeInteger(sourceIndex) ||
      sourceIndex < 0 ||
      sourceIndex >= citations.length
    )
      return false
    const source = citations[sourceIndex]
    if (!source || typeof source !== 'object' || typeof source.url !== 'string') return false
    const segment = answer.slice(start, end)
    const link = /^\[[^\]\r\n]+\]\((https?:\/\/[^()\s]+)\)$/.exec(segment)
    const candidate = link?.[1] ?? segment
    try {
      if (
        !/^https?:\/\/[^\s]+$/.test(candidate) ||
        new URL(candidate).href !== new URL(source.url).href
      )
        return false
    } catch {
      return false
    }
    const identity = `${start}:${end}:${sourceIndex}`
    if (seen.has(identity)) return false
    seen.add(identity)
    return true
  })
}
