import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MarkdownView, parseMarkdownBlocks } from '../markdown'

afterEach(cleanup)

describe('markdown answers', () => {
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
