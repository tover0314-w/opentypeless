import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { AskSources } from '../AskSources'

afterEach(cleanup)

it.each([300, 700])('rejects a source exceeding raw or normalized UTF8 bounds: %s', (length) => {
  const open = vi.fn()
  const url = `https://example.invalid/${'字'.repeat(length)}`
  expect(url.length).toBeLessThan(2048)
  const { container } = render(
    <AskSources citations={[{ url, title: 'Oversized source' }]} onOpenLink={open} />,
  )
  expect(container).toBeEmptyDOMElement()
  expect(open).not.toHaveBeenCalled()
})

it('preserves a source exactly at the normalized UTF8 boundary', () => {
  const prefix = 'https://example.invalid/'
  const url = `${prefix}${'x'.repeat(2048 - prefix.length)}`
  const open = vi.fn()
  render(<AskSources citations={[{ url, title: 'Boundary source' }]} onOpenLink={open} />)
  fireEvent.click(screen.getByRole('link'))
  expect(open).toHaveBeenCalledExactlyOnceWith(url)
})

it('renders inert deduplicated source titles and opens only on user action', () => {
  const open = vi.fn()
  const source = { url: 'https://example.invalid/source', title: '<img src=x onerror=alert(1)>' }
  const { container } = render(
    <AskSources
      citations={[source, source, { url: 'javascript:alert(1)', title: 'Unsafe' }]}
      onOpenLink={open}
    />,
  )
  expect(container.querySelector('img')).toBeNull()
  expect(screen.getAllByRole('link')).toHaveLength(1)
  expect(open).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('link'))
  expect(open).toHaveBeenCalledExactlyOnceWith(source.url)
})

it('keeps old answers without sources unchanged', () => {
  const { container } = render(<AskSources />)
  expect(container).toBeEmptyDOMElement()
})

it('shows three valid sources initially and expands without opening a link', () => {
  const open = vi.fn()
  const citations = Array.from({ length: 5 }, (_, index) => ({
    url: `https://example.invalid/${index}`,
    title: `Source ${index}`,
  }))
  const { rerender } = render(<AskSources citations={citations} onOpenLink={open} />)
  expect(screen.getAllByRole('link')).toHaveLength(3)
  const expand = screen.getByRole('button')
  expect(expand).toHaveAttribute('type', 'button')
  expect(expand).toHaveAttribute('aria-expanded', 'false')
  expect(expand.textContent).toContain('+2')
  fireEvent.click(expand)
  expect(screen.getAllByRole('link')).toHaveLength(5)
  expect(screen.getByRole('button')).toHaveAttribute('aria-expanded', 'true')
  expect(open).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button'))
  expect(screen.getAllByRole('link')).toHaveLength(3)
  fireEvent.click(screen.getByRole('button'))
  rerender(
    <AskSources
      citations={citations.map((source) => ({ ...source, title: `New ${source.title}` }))}
      onOpenLink={open}
    />,
  )
  expect(screen.getAllByRole('link')).toHaveLength(3)
})
