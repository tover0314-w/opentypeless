import { useTranslation } from 'react-i18next'
import { useId, useState } from 'react'

export interface AskCitation {
  url: string
  title: string
}

export function AskSources({
  citations,
  onOpenLink,
}: {
  citations?: AskCitation[]
  onOpenLink?: (url: string) => void
}) {
  const { t } = useTranslation()
  const listId = useId()
  const [expandedIdentity, setExpandedIdentity] = useState<string | null>(null)
  if (!Array.isArray(citations)) return null
  const seen = new Set<string>()
  const sources = citations.slice(0, 20).filter((source) => {
    if (
      !source ||
      typeof source.url !== 'string' ||
      typeof source.title !== 'string' ||
      !source.title.trim() ||
      source.title.length > 500 ||
      source.url.length > 2048 ||
      new TextEncoder().encode(source.url).length > 2048 ||
      Array.from(source.url).some(
        (character) => character <= ' ' || character === '\u007f' || character === '\\',
      )
    ) {
      return false
    }
    try {
      const url = new URL(source.url)
      if (
        !/^https?:\/\//iu.test(source.url) ||
        !['http:', 'https:'].includes(url.protocol) ||
        url.username ||
        url.password ||
        new TextEncoder().encode(url.href).length > 2048 ||
        seen.has(url.href)
      ) {
        return false
      }
      seen.add(url.href)
      return true
    } catch {
      return false
    }
  })
  if (!sources.length) return null
  const identity = JSON.stringify(sources)
  const expanded = expandedIdentity === identity
  const visibleSources = expanded ? sources : sources.slice(0, 3)
  return (
    <nav aria-label={t('ask.sources')} className="mt-3 border-t border-border-thin pt-3">
      <p className="m-0 mb-2 text-[12px] text-text-tertiary">{t('ask.sources')}</p>
      <ul id={listId} className="m-0 list-none space-y-1 p-0">
        {visibleSources.map((source) => (
          <li key={source.url}>
            <a
              href={source.url}
              className="break-words text-[12px] text-accent underline"
              onClick={(event) => {
                event.preventDefault()
                onOpenLink?.(source.url)
              }}
            >
              {source.title}
            </a>
          </li>
        ))}
      </ul>
      {sources.length > 3 && (
        <button
          type="button"
          aria-expanded={expanded}
          aria-controls={listId}
          className="mt-2 rounded-full border border-border px-2 py-1 text-[12px] text-text-secondary"
          onClick={() => setExpandedIdentity(expanded ? null : identity)}
        >
          {expanded ? t('ask.showLess') : `+${sources.length - 3} ${t('ask.more')}`}
        </button>
      )}
    </nav>
  )
}
