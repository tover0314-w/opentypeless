const BLOCK_TAGS = new Set([
  'P',
  'H1',
  'H2',
  'H3',
  'H4',
  'H5',
  'H6',
  'BLOCKQUOTE',
  'PRE',
  'UL',
  'OL',
])

function contentText(node: Node): string {
  if (node.nodeType === Node.TEXT_NODE) return node.textContent ?? ''
  if (!(node instanceof Element)) return ''
  if (node.tagName === 'BR') return '\n'
  if (node.tagName === 'PRE') return node.textContent ?? ''
  if (node.tagName === 'UL' || node.tagName === 'OL') {
    const start = Number.parseInt(node.getAttribute('start') ?? '1', 10)
    const first = Number.isFinite(start) ? start : 1
    return Array.from(node.children)
      .filter((child) => child.tagName === 'LI')
      .map((child, index) => {
        const marker = node.tagName === 'OL' ? `${first + index}. ` : '• '
        return `${marker}${childrenText(child)}`
      })
      .join('\n')
  }
  return childrenText(node)
}

function childrenText(node: Node): string {
  let output = ''
  let previousBlock = false
  for (const child of Array.from(node.childNodes)) {
    const block = child instanceof Element && BLOCK_TAGS.has(child.tagName)
    const text = contentText(child)
    if (!text) continue
    if (output && (block || previousBlock)) output += '\n\n'
    output += text
    previousBlock = block
  }
  return output
}

export function renderedAnswerText(rendered: HTMLElement): string {
  return childrenText(rendered)
}
