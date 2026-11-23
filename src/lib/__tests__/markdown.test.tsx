import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MarkdownView, parseMarkdownBlocks } from '../markdown'

afterEach(cleanup)

describe('markdown answers', () => {
  it.each([
    Array.from({ length: 1001 }, (_, index) => `- Item ${index}`).join('\n'),
    `| ${'Cell | '.repeat(2001)}\n| ${'--- | '.repeat(2001)}`,
    'a'.repeat(100001),
  ])('keeps oversized formatting as complete inert text', (source) => {
    expect(parseMarkdownBlocks(source)).toEqual([{ type: 'code', text: source }])
    const onOpenLink = vi.fn()
    const { container } = render(<MarkdownView source={source} onOpenLink={onOpenLink} />)
    expect(container.querySelector('pre code')?.textContent).toBe(source)
    expect(container.querySelectorAll('li,td,th,a')).toHaveLength(0)
    expect(onOpenLink).not.toHaveBeenCalled()
  })

  it('avoids inline formatting for very long individual text blocks without losing content', () => {
    const text = `**${'x'.repeat(8001)}**`
    const { container } = render(<MarkdownView source={text} />)
    expect(container.querySelector('strong')).toBeNull()
    expect(container.textContent).toBe(text)
  })

  it('bounds recursive list formatting and preserves over-depth content verbatim', () => {
    const source = Array.from(
      { length: 80 },
      (_, depth) => `${' '.repeat(depth * 2)}- Synthetic level ${depth}`,
    ).join('\n')
    const boundedSource = `${source}\n- Root sibling\n\nAfter the list`
    const blocks = parseMarkdownBlocks(boundedSource)
    const pending = blocks.map((block) => ({ block, depth: 1 }))
    let deepestList = 0
    while (pending.length) {
      const { block, depth } = pending.pop()!
      if (block.type !== 'list') continue
      deepestList = Math.max(deepestList, depth)
      for (const children of Object.values(block.children ?? {})) {
        pending.push(...children.map((child) => ({ block: child, depth: depth + 1 })))
      }
    }
    expect(deepestList).toBeLessThanOrEqual(32)
    const { container } = render(<MarkdownView source={boundedSource} />)
    expect(container.querySelector('pre code')?.textContent).toContain('- Synthetic level 79')
    for (let depth = 0; depth < 80; depth += 1) {
      expect(container.textContent).toContain(`Synthetic level ${depth}`)
    }
    expect(screen.getByText('After the list')).toBeInTheDocument()
    expect(screen.getByText('Root sibling').closest('li')?.parentElement).toBe(
      container.querySelector('ul'),
    )
  })

  it('renders tables with safe inline content and aligned cells', () => {
    const { container } = render(
      <MarkdownView
        source={'| Product | Price |\n| :--- | ---: |\n| **Local** | `10` |\n| <img src=x> | 20 |'}
      />,
    )
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getAllByRole('columnheader')).toHaveLength(2)
    expect(screen.getAllByRole('row')).toHaveLength(3)
    expect(container.querySelector('td strong')?.textContent).toBe('Local')
    expect(container.querySelector('td:nth-child(2)')).toHaveStyle({ textAlign: 'right' })
    expect(container.querySelector('img')).toBeNull()
  })

  it('keeps nested mixed lists inside their parent item and preserves numbering', () => {
    const { container } = render(
      <MarkdownView source={'3. Parent\n   - Child\n     7. Grandchild\n4. Next'} />,
    )
    expect(container.querySelector('ol')?.getAttribute('start')).toBe('3')
    expect(container.querySelector('ol > li > ul > li > ol')?.getAttribute('start')).toBe('7')
    expect(container.querySelector('ol')?.children).toHaveLength(2)
    expect(container.querySelector('ol > li > ul')?.textContent).toContain('Child')
  })

  it('shows task states without allowing editing', () => {
    render(<MarkdownView source={'- [x] Done\n- [ ] Pending\n- Ordinary'} />)
    const checkboxes = screen.getAllByRole('checkbox')
    expect(checkboxes).toHaveLength(2)
    expect(checkboxes[0]).toBeChecked()
    expect(checkboxes[1]).not.toBeChecked()
    checkboxes.forEach((checkbox) => expect(checkbox).toBeDisabled())
    expect(screen.getByText('Ordinary')).toBeInTheDocument()
  })

  it('preserves escaped pipes and inline code pipes in table cells', () => {
    render(<MarkdownView source={'| Text | Code |\n| --- | --- |\n| left\\|right | `a|b` |'} />)
    expect(screen.getAllByRole('cell').map((cell) => cell.textContent)).toEqual([
      'left|right',
      'a|b',
    ])
  })

  it('does not turn ordinary pipe text or fenced tables into tables', () => {
    const { container } = render(
      <MarkdownView
        source={'left | right\nnot a separator\n\n```\n| a | b |\n| --- | --- |\n```'}
      />,
    )
    expect(screen.queryByRole('table')).toBeNull()
    expect(container.querySelector('pre code')?.textContent).toContain('| --- | --- |')
  })

  it('keeps active HTML and embedded resources inert across answer blocks', () => {
    const onOpenLink = vi.fn()
    const source = [
      '# <script>alert("synthetic")</script>',
      '<iframe src="https://example.test/private"></iframe>',
      '- <svg onload="alert(1)"><use href="https://example.test/icon"></use></svg>',
      '> <img src="https://example.test/tracker" onerror="alert(1)">',
      '![Image](https://example.test/image)',
      '```html',
      '<a href="javascript:alert(1)">Unsafe</a>',
      '```',
    ].join('\n')
    const { container } = render(<MarkdownView source={source} onOpenLink={onOpenLink} />)
    expect(container.querySelector('script, iframe, svg, use, img, object, embed')).toBeNull()
    expect(container.querySelector('[onload], [onerror]')).toBeNull()
    expect(container.querySelector('pre code')?.textContent).toBe(
      '<a href="javascript:alert(1)">Unsafe</a>',
    )
    expect(onOpenLink).not.toHaveBeenCalled()
    for (const link of container.querySelectorAll('a')) {
      expect(link.getAttribute('href')).toMatch(/^https:\/\/example\.test\//)
    }
  })

  it.each([
    'https:',
    'https://',
    'https://user:password@example.com/docs',
    'https://example.com\\@elsewhere.example',
    'mailto:',
    'https://example.com/\u0000path',
  ])('does not make an unsafe or malformed address clickable: %s', (address) => {
    render(<MarkdownView source={`[Link](${address})`} onOpenLink={vi.fn()} />)
    expect(screen.queryByRole('link')).toBeNull()
    expect(screen.getByText('Link')).toBeInTheDocument()
  })

  it('preserves legitimate mail links without invoking them during rendering', () => {
    const onOpenLink = vi.fn()
    render(<MarkdownView source="[Email](mailto:person@example.com)" onOpenLink={onOpenLink} />)
    expect(onOpenLink).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('link', { name: 'Email' }))
    expect(onOpenLink).toHaveBeenCalledWith('mailto:person@example.com')
  })

  it('parses headings, paragraphs, lists and code fences', () => {
    const blocks = parseMarkdownBlocks(
      '### 1. Core flow\nFirst line\ncontinues here.\n\n- **Entry**: visible\n- Second\n\n1. one\n2. two\n\n```\nconst a = 1\n```',
    )
    expect(blocks.map((block) => block.type)).toEqual([
      'heading',
      'paragraph',
      'list',
      'list',
      'code',
    ])
    expect(blocks[1]).toEqual({ type: 'paragraph', text: 'First line continues here.' })
    expect(blocks[2]).toMatchObject({ ordered: false, items: ['**Entry**: visible', 'Second'] })
    expect(blocks[3]).toMatchObject({ ordered: true, items: ['one', 'two'] })
    expect(blocks[4]).toEqual({ type: 'code', text: 'const a = 1' })
  })

  it('renders inline emphasis and code without raw HTML', () => {
    const { container } = render(
      <MarkdownView source={'Use **bold**, *italic* and `code`. <img src=x onerror=alert(1)>'} />,
    )
    expect(container.querySelector('strong')?.textContent).toBe('bold')
    expect(container.querySelector('em')?.textContent).toBe('italic')
    expect(container.querySelector('code')?.textContent).toBe('code')
    expect(container.querySelector('img')).toBeNull()
    expect(container.textContent).toContain('<img src=x onerror=alert(1)>')
  })

  it('opens only http and mailto links through the callback', () => {
    const onOpenLink = vi.fn()
    render(
      <MarkdownView
        source={'[Docs](https://example.com/docs) and [bad](javascript:alert(1))'}
        onOpenLink={onOpenLink}
      />,
    )
    fireEvent.click(screen.getByRole('link', { name: 'Docs' }))
    expect(onOpenLink).toHaveBeenCalledWith('https://example.com/docs')
    expect(screen.queryByRole('link', { name: 'bad' })).toBeNull()
    expect(screen.getByText(/bad/)).toBeInTheDocument()
  })
})
