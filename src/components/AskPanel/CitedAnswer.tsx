import { MarkdownView } from '../../lib/markdown'
import type { AskCitation } from './AskSources'
import { copyAnswerSelection } from '../../lib/copyAnswerSelection'

export interface CitationSupport {
  start: number
  end: number
  sourceIndex: number
}

export function CitedAnswer({
  answer,
  citations,
  supports,
  onOpenLink,
  className = 'answer-markdown',
}: {
  answer: string
  citations?: AskCitation[]
  supports?: CitationSupport[]
  onOpenLink?: (url: string) => void
  className?: string
}) {
  const links = new Map<string, { source: AskCitation; index: number }>()
  let asciiEnd = 0
  while (asciiEnd < answer.length && answer.charCodeAt(asciiEnd) <= 127) asciiEnd += 1
  for (const support of Array.isArray(supports) ? supports.slice(0, 100) : []) {
    if (
      !support ||
      !Number.isSafeInteger(support.start) ||
      !Number.isSafeInteger(support.end) ||
      !Number.isSafeInteger(support.sourceIndex) ||
      support.start < 0 ||
      support.end <= support.start ||
      support.end > asciiEnd ||
      support.end - support.start > 4096 ||
      support.sourceIndex < 0 ||
      support.sourceIndex >= 20
    )
      continue
    const source = citations?.[support.sourceIndex]
    if (
      !source ||
      typeof source.url !== 'string' ||
      typeof source.title !== 'string' ||
      !source.title.trim() ||
      source.title.length > 500 ||
      !/^https?:\/\//iu.test(source.url) ||
      Array.from(source.url).some(
        (character) => character <= ' ' || character === '\u007f' || character === '\\',
      )
    )
      continue
    const markdown = answer.slice(support.start, support.end)
    const match = /^\[[^\]\r\n]+\]\((https?:\/\/[^()\s]+)\)$/.exec(markdown)
    if (
      !match ||
      answer.indexOf(markdown) !== support.start ||
      answer.indexOf(markdown, support.start + 1) !== -1
    )
      continue
    try {
      const url = new URL(source.url)
      if (
        !['http:', 'https:'].includes(url.protocol) ||
        url.username ||
        url.password ||
        new TextEncoder().encode(source.url).length > 2048 ||
        new TextEncoder().encode(url.href).length > 2048 ||
        new URL(match[1]).href !== url.href
      )
        continue
      links.set(markdown, { source, index: support.sourceIndex })
    } catch {
      continue
    }
  }
  return (
    <MarkdownView
      source={answer}
      onOpenLink={onOpenLink}
      className={className}
      onCopy={(event) => {
        if (copyAnswerSelection(event.currentTarget, event.clipboardData)) event.preventDefault()
      }}
      renderCitation={(markdown) => {
        const citation = links.get(markdown)
        if (!citation) return null
        return (
          <span data-citation-group="true" className="ml-1 inline-flex align-middle">
            <button
              type="button"
              aria-label={citation.source.title}
              title={citation.source.title}
              onClick={() => onOpenLink?.(citation.source.url)}
              className="rounded-full border border-border px-1.5 text-[11px] text-accent cursor-pointer hover:bg-fill-hover"
            >
              {citation.index + 1}
            </button>
          </span>
        )
      }}
    />
  )
}
