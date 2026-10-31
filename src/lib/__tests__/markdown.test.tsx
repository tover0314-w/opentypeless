import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MarkdownView, parseMarkdownBlocks } from '../markdown'

afterEach(cleanup)

describe('markdown answers', () => {
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
