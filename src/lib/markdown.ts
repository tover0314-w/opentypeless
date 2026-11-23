import { createElement, Fragment, type ClipboardEvent, type ReactNode } from 'react'

// A deliberately small Markdown subset for LLM answers: headings, paragraphs, bullet and
// numbered lists, fenced code, blockquotes, and inline bold/italic/code/links. Text is
// rendered through React elements only, so model output can never inject HTML.

type Block =
  | { type: 'heading'; level: number; text: string }
  | { type: 'paragraph'; text: string }
  | {
      type: 'list'
      ordered: boolean
      items: string[]
      start?: number
      children?: Record<number, Block[]>
      tasks?: Record<number, boolean>
    }
  | { type: 'table'; headers: string[]; alignments: Alignment[]; rows: string[][] }
  | { type: 'code'; text: string }
  | { type: 'quote'; text: string }

type Alignment = 'left' | 'center' | 'right' | undefined

function tableCells(line: string): string[] {
  const cells: string[] = []
  let cell = ''
  let codeDelimiter = 0
  const trimmed = line.trim()
  for (let cursor = 0; cursor < trimmed.length; cursor += 1) {
    const character = trimmed[cursor]
    if (character === '\\' && trimmed[cursor + 1] === '|') {
      cell += '|'
      cursor += 1
    } else if (character === '`') {
      let delimiter = '`'
      while (trimmed[cursor + 1] === '`') {
        delimiter += '`'
        cursor += 1
      }
      if (!codeDelimiter) codeDelimiter = delimiter.length
      else if (codeDelimiter === delimiter.length) codeDelimiter = 0
      cell += delimiter
    } else if (character === '|' && !codeDelimiter) {
      cells.push(cell.trim())
      cell = ''
    } else {
      cell += character
    }
  }
  cells.push(cell.trim())
  if (trimmed.startsWith('|')) cells.shift()
  if (trimmed.endsWith('|') && cells[cells.length - 1] === '') cells.pop()
  return cells
}

function listMarker(line: string) {
  const match = /^(\s*)(?:([-*+])|(\d+)[.)])\s+(.*)$/.exec(line)
  if (!match) return null
  return {
    indent: match[1].replace(/\t/g, '    ').length,
    ordered: Boolean(match[3]),
    start: match[3] ? Number(match[3]) : undefined,
    text: match[4],
  }
}

const MAX_LIST_DEPTH = 32
const MAX_FORMATTED_CHARACTERS = 100_000
const MAX_FORMATTED_LINES = 1000
const MAX_FORMATTING_MARKERS = 2000
const MAX_TABLE_CELLS = 2000
const MAX_INLINE_CHARACTERS = 8000

function parseList(lines: string[], from: number, depth = 1): { block: Block; next: number } {
  const first = listMarker(lines[from])!
  const block: Extract<Block, { type: 'list' }> = {
    type: 'list',
    ordered: first.ordered,
    items: [],
  }
  if (first.ordered) block.start = first.start
  let cursor = from
  while (cursor < lines.length) {
    const marker = listMarker(lines[cursor])
    if (!marker || marker.indent < first.indent) break
    if (marker.indent > first.indent) {
      const parentIndex = block.items.length - 1
      block.children ??= {}
      block.children[parentIndex] ??= []
      if (depth >= MAX_LIST_DEPTH) {
        const unformatted: string[] = []
        while (cursor < lines.length) {
          const descendant = listMarker(lines[cursor])
          if (!descendant || descendant.indent <= first.indent) break
          unformatted.push(lines[cursor])
          cursor += 1
        }
        block.children[parentIndex].push({ type: 'code', text: unformatted.join('\n') })
        continue
      }
      const nested = parseList(lines, cursor, depth + 1)
      block.children[parentIndex].push(nested.block)
      cursor = nested.next
      continue
    }
    if (marker.ordered !== first.ordered) break
    const task = /^\[([ xX])\]\s+(.*)$/.exec(marker.text)
    if (task) {
      block.tasks ??= {}
      block.tasks[block.items.length] = task[1].toLowerCase() === 'x'
    }
    block.items.push(task ? task[2] : marker.text)
    cursor += 1
  }
  return { block, next: cursor }
}

export function parseMarkdownBlocks(source: string): Block[] {
  const plainText = (): Block[] => [{ type: 'code', text: source }]
  if (source.length > MAX_FORMATTED_CHARACTERS) return plainText()
  let markers = 0
  for (const character of source) {
    if ('|*`_[]'.includes(character)) markers += 1
    if (markers > MAX_FORMATTING_MARKERS) return plainText()
  }
  const lines = source.replace(/\r\n?/g, '\n').split('\n')
  if (lines.length > MAX_FORMATTED_LINES) return plainText()
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

    if (line.includes('|') && i + 1 < lines.length) {
      const headers = tableCells(line)
      const separators = tableCells(lines[i + 1])
      if (
        headers.length > 1 &&
        headers.length === separators.length &&
        separators.every((cell) => /^:?-{3,}:?$/.test(cell))
      ) {
        flushParagraph()
        const alignments: Alignment[] = separators.map((cell) =>
          cell.startsWith(':') && cell.endsWith(':')
            ? 'center'
            : cell.endsWith(':')
              ? 'right'
              : cell.startsWith(':')
                ? 'left'
                : undefined,
        )
        const rows: string[][] = []
        i += 2
        while (i < lines.length && lines[i].trim() && lines[i].includes('|')) {
          if (headers.length * (rows.length + 2) > MAX_TABLE_CELLS) return plainText()
          const cells = tableCells(lines[i])
          rows.push(headers.map((_, column) => cells[column] ?? ''))
          i += 1
        }
        i -= 1
        blocks.push({ type: 'table', headers, alignments, rows })
        continue
      }
    }

    if (listMarker(line)) {
      flushParagraph()
      const list = parseList(lines, i)
      blocks.push(list.block)
      i = list.next - 1
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
  if (
    Array.from(url).some(
      (character) => character.charCodeAt(0) <= 32 || character.charCodeAt(0) === 127,
    )
  ) {
    return null
  }
  if (url.includes('\\')) return null
  try {
    const parsed = new URL(url)
    if (parsed.protocol === 'mailto:') return parsed.pathname ? url : null
    if (
      !['http:', 'https:'].includes(parsed.protocol) ||
      !/^https?:\/\//i.test(url) ||
      !parsed.hostname ||
      parsed.username ||
      parsed.password
    ) {
      return null
    }
    return url
  } catch {
    return null
  }
}

export function renderInline(
  text: string,
  onOpenLink?: (url: string) => void,
  keyPrefix = 'i',
  renderCitation?: (markdown: string) => ReactNode,
): ReactNode[] {
  if (text.length > MAX_INLINE_CHARACTERS) return [text]
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
      const anchor = createElement(
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
      return createElement(Fragment, { key }, anchor, renderCitation?.(part))
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
  renderCitation,
  onCopy,
}: {
  source: string
  onOpenLink?: (url: string) => void
  className?: string
  renderCitation?: (markdown: string) => ReactNode
  onCopy?: (event: ClipboardEvent<HTMLDivElement>) => void
}) {
  const blocks = parseMarkdownBlocks(source)
  return createElement(
    'div',
    { className: `markdown-body ${className}`, onCopy },
    blocks.map((block, index) => renderBlock(block, `b-${index}`, onOpenLink, renderCitation)),
  )
}

function renderBlock(
  block: Block,
  key: string,
  onOpenLink?: (url: string) => void,
  renderCitation?: (markdown: string) => ReactNode,
): ReactNode {
  switch (block.type) {
    case 'heading':
      return createElement(
        `h${Math.min(block.level + 1, 6)}`,
        { key },
        renderInline(block.text, onOpenLink, key, renderCitation),
      )
    case 'list':
      return createElement(
        block.ordered ? 'ol' : 'ul',
        { key, start: block.ordered ? block.start : undefined },
        block.items.map((item, itemIndex) =>
          createElement(
            'li',
            {
              key: `${key}-${itemIndex}`,
              className: block.tasks?.[itemIndex] !== undefined ? 'markdown-task' : undefined,
            },
            block.tasks?.[itemIndex] !== undefined
              ? createElement('input', {
                  type: 'checkbox',
                  checked: block.tasks[itemIndex],
                  disabled: true,
                  'aria-label': item,
                })
              : null,
            renderInline(item, onOpenLink, `${key}-${itemIndex}`, renderCitation),
            block.children?.[itemIndex]?.map((child, childIndex) =>
              renderBlock(child, `${key}-${itemIndex}-${childIndex}`, onOpenLink, renderCitation),
            ),
          ),
        ),
      )
    case 'table':
      return createElement(
        'div',
        { key, className: 'markdown-table-scroll' },
        createElement(
          'table',
          null,
          createElement(
            'thead',
            null,
            createElement(
              'tr',
              null,
              block.headers.map((header, column) =>
                createElement(
                  'th',
                  { key: column, scope: 'col', style: { textAlign: block.alignments[column] } },
                  renderInline(header, onOpenLink, `${key}-head-${column}`, renderCitation),
                ),
              ),
            ),
          ),
          createElement(
            'tbody',
            null,
            block.rows.map((row, rowIndex) =>
              createElement(
                'tr',
                { key: rowIndex },
                row.map((cell, column) =>
                  createElement(
                    'td',
                    { key: column, style: { textAlign: block.alignments[column] } },
                    renderInline(cell, onOpenLink, `${key}-${rowIndex}-${column}`, renderCitation),
                  ),
                ),
              ),
            ),
          ),
        ),
      )
    case 'code':
      return createElement('pre', { key }, createElement('code', null, block.text))
    case 'quote':
      return createElement(
        'blockquote',
        { key },
        renderInline(block.text, onOpenLink, key, renderCitation),
      )
    default:
      return createElement('p', { key }, renderInline(block.text, onOpenLink, key, renderCitation))
  }
}
