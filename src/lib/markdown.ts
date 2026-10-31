import { createElement, Fragment, type ReactNode } from 'react'

// A deliberately small Markdown subset for LLM answers: headings, paragraphs, bullet and
// numbered lists, fenced code, blockquotes, and inline bold/italic/code/links. Text is
// rendered through React elements only, so model output can never inject HTML.

type Block =
  | { type: 'heading'; level: number; text: string }
  | { type: 'paragraph'; text: string }
  | { type: 'list'; ordered: boolean; items: string[] }
  | { type: 'code'; text: string }
  | { type: 'quote'; text: string }

export function parseMarkdownBlocks(source: string): Block[] {
  const lines = source.replace(/\r\n?/g, '\n').split('\n')
  const blocks: Block[] = []
  let paragraph: string[] = []

  const flushParagraph = () => {
    if (paragraph.length) blocks.push({ type: 'paragraph', text: paragraph.join(' ') })
    paragraph = []
  }

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i]
    const trimmed = line.trim()

    if (trimmed.startsWith('```')) {
      flushParagraph()
      const code: string[] = []
      i += 1
      while (i < lines.length && !lines[i].trim().startsWith('```')) {
        code.push(lines[i])
        i += 1
      }
      blocks.push({ type: 'code', text: code.join('\n') })
      continue
    }

    if (!trimmed) {
      flushParagraph()
      continue
    }

    const heading = /^(#{1,6})\s+(.*)$/.exec(trimmed)
    if (heading) {
      flushParagraph()
      blocks.push({ type: 'heading', level: heading[1].length, text: heading[2] })
      continue
    }

    const bullet = /^[-*+]\s+(.*)$/.exec(trimmed)
    const numbered = /^\d+[.)]\s+(.*)$/.exec(trimmed)
    if (bullet || numbered) {
      flushParagraph()
      const ordered = Boolean(numbered)
      const previous = blocks[blocks.length - 1]
      const item = (bullet ?? numbered)![1]
      if (previous?.type === 'list' && previous.ordered === ordered) previous.items.push(item)
      else blocks.push({ type: 'list', ordered, items: [item] })
      continue
    }

    if (trimmed.startsWith('>')) {
      flushParagraph()
      blocks.push({ type: 'quote', text: trimmed.replace(/^>\s?/, '') })
      continue
    }

    paragraph.push(trimmed)
  }
  flushParagraph()
  return blocks
}

const INLINE =
  /(\*\*[^*]+\*\*|__[^_]+__|`[^`]+`|\[[^\]]+\]\([^)\s]+\)|\*[^*\s][^*]*\*|_[^_\s][^_]*_)/g

function safeHref(url: string) {
  return /^(https?:|mailto:)/i.test(url) ? url : null
}

export function renderInline(
  text: string,
  onOpenLink?: (url: string) => void,
  keyPrefix = 'i',
): ReactNode[] {
  const parts = text.split(INLINE).filter((part) => part !== '')
  return parts.map((part, index) => {
    const key = `${keyPrefix}-${index}`
    if (/^(\*\*|__)/.test(part) && part.length > 4) {
      return createElement('strong', { key }, part.slice(2, -2))
    }
    if (part.startsWith('`') && part.endsWith('`') && part.length > 2) {
      return createElement('code', { key }, part.slice(1, -1))
    }
    const link = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(part)
    if (link) {
      const href = safeHref(link[2])
      if (!href) return createElement(Fragment, { key }, link[1])
      return createElement(
        'a',
        {
          key,
          href,
          onClick: (event: MouseEvent) => {
            event.preventDefault()
            onOpenLink?.(href)
          },
        },
        link[1],
      )
    }
    if (/^[*_][^*_]/.test(part) && /[*_]$/.test(part) && part.length > 2) {
      return createElement('em', { key }, part.slice(1, -1))
    }
    return createElement(Fragment, { key }, part)
  })
}

export function MarkdownView({
  source,
  onOpenLink,
  className = '',
}: {
  source: string
  onOpenLink?: (url: string) => void
  className?: string
}) {
  const blocks = parseMarkdownBlocks(source)
  return createElement(
    'div',
    { className: `markdown-body ${className}` },
    blocks.map((block, index) => {
      const key = `b-${index}`
      switch (block.type) {
        case 'heading':
          return createElement(
            `h${Math.min(block.level + 1, 6)}`,
            { key },
            renderInline(block.text, onOpenLink, key),
          )
        case 'list':
          return createElement(
            block.ordered ? 'ol' : 'ul',
            { key },
            block.items.map((item, itemIndex) =>
              createElement(
                'li',
                { key: `${key}-${itemIndex}` },
                renderInline(item, onOpenLink, `${key}-${itemIndex}`),
              ),
            ),
          )
        case 'code':
          return createElement('pre', { key }, createElement('code', null, block.text))
        case 'quote':
          return createElement('blockquote', { key }, renderInline(block.text, onOpenLink, key))
        default:
          return createElement('p', { key }, renderInline(block.text, onOpenLink, key))
      }
    }),
  )
}
