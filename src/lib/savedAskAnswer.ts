export interface SavedAskAnswer {
  historyId: number
  question: string
  answer: string
  citations: Array<{ url: string; title: string }>
  askIntent?: 'open_question' | 'ask_selection' | null
  citationSupports?: Array<{ start: number; end: number; sourceIndex: number }>
}

export async function readSavedAskAnswer(
  historyId: number,
  question: string,
  answer: string,
): Promise<SavedAskAnswer | null> {
  if (
    !Number.isSafeInteger(historyId) ||
    historyId <= 0 ||
    typeof question !== 'string' ||
    typeof answer !== 'string' ||
    !answer.trim() ||
    question.length + answer.length > MAX_BYTES ||
    bytes(question) + bytes(answer) > MAX_BYTES
  ) {
    throw new Error('Invalid saved answer input')
  }
  let timer: ReturnType<typeof setTimeout> | undefined
  let response: unknown
  try {
    response = await Promise.race([
      invoke<unknown>('read_saved_ask_answer', { historyId, question, answer }),
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => reject(new Error('Saved answer read timed out')), 5000)
      }),
    ])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
  if (response === null) return null
  const invalid = () => new Error('Invalid saved answer response')
  if (!response || typeof response !== 'object' || Array.isArray(response)) throw invalid()
  const snapshot = response as Record<string, unknown>
  if (
    Object.keys(snapshot).some(
      (key) =>
        !['historyId', 'question', 'answer', 'citations', 'askIntent', 'citationSupports'].includes(
          key,
        ),
    ) ||
    snapshot.historyId !== historyId ||
    snapshot.question !== question ||
    snapshot.answer !== answer ||
    !Array.isArray(snapshot.citations) ||
    snapshot.citations.length > 20
  ) {
    throw invalid()
  }
  if (
    'askIntent' in snapshot &&
    snapshot.askIntent !== null &&
    snapshot.askIntent !== 'open_question' &&
    snapshot.askIntent !== 'ask_selection'
  ) {
    throw invalid()
  }
  const seen = new Set<string>()
  const citations = snapshot.citations.map((entry: unknown) => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) throw invalid()
    const source = entry as Record<string, unknown>
    if (
      Object.keys(source).length !== 2 ||
      typeof source.url !== 'string' ||
      source.url.length > 2048 ||
      bytes(source.url) > 2048 ||
      !/^https?:\/\//iu.test(source.url) ||
      Array.from(source.url).some(
        (character) => character <= ' ' || character === '\u007f' || character === '\\',
      ) ||
      typeof source.title !== 'string' ||
      !source.title.trim() ||
      source.title.trim() !== source.title ||
      source.title.length > 500
    ) {
      throw invalid()
    }
    let url: URL
    try {
      url = new URL(source.url)
    } catch {
      throw invalid()
    }
    if (
      !url.hostname ||
      url.username ||
      url.password ||
      bytes(url.href) > 2048 ||
      seen.has(url.href)
    )
      throw invalid()
    seen.add(url.href)
    return { url: source.url, title: source.title }
  })
  const result: SavedAskAnswer = { historyId, question, answer, citations }
  if ('citationSupports' in snapshot) {
    if (!Array.isArray(snapshot.citationSupports) || snapshot.citationSupports.length > 100)
      throw invalid()
    let asciiEnd = 0
    while (asciiEnd < answer.length && answer.charCodeAt(asciiEnd) <= 127) asciiEnd += 1
    const seenSupports = new Set<string>()
    result.citationSupports = snapshot.citationSupports.map((value: unknown) => {
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid()
      const support = value as Record<string, unknown>
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
        throw invalid()
      const segment = answer.slice(start, end)
      const link = /^\[[^\]\r\n]+\]\((https?:\/\/[^()\s]+)\)$/.exec(segment)
      const candidate = link?.[1] ?? segment
      try {
        if (
          !/^https?:\/\/[^\s]+$/.test(candidate) ||
          new URL(candidate).href !== new URL(citations[sourceIndex].url).href
        )
          throw invalid()
      } catch {
        throw invalid()
      }
      const identity = `${start}:${end}:${sourceIndex}`
      if (seenSupports.has(identity)) throw invalid()
      seenSupports.add(identity)
      return { start, end, sourceIndex }
    })
  }
  if ('askIntent' in snapshot) {
    result.askIntent = snapshot.askIntent as SavedAskAnswer['askIntent']
  }
  if (bytes(JSON.stringify(result)) > MAX_BYTES) throw invalid()
  return result
}
import { invoke } from '@tauri-apps/api/core'

const MAX_BYTES = 4 * 1024 * 1024
const bytes = (value: string) => new TextEncoder().encode(value).length
