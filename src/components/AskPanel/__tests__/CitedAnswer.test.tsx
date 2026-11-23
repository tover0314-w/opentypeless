import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { CitedAnswer } from '../CitedAnswer'
import { AskAnswerCard } from '../AskAnswerCard'

afterEach(() => {
  window.getSelection()?.removeAllRanges()
  cleanup()
})

const url = 'https://example.invalid/source'
const link = `[Read source](${url})`
const citations = [{ url, title: 'Verified source' }]

it('cleans the actual answer selection copy event without touching its rendered citation', () => {
  const { container } = render(
    <CitedAnswer
      answer={`Claim ${link}.`}
      citations={citations}
      supports={[{ start: 6, end: 6 + link.length, sourceIndex: 0 }]}
    />,
  )
  const body = container.querySelector<HTMLElement>('.answer-markdown')!
  const range = document.createRange()
  range.selectNodeContents(body)
  window.getSelection()?.addRange(range)
  const clipboardData = { setData: vi.fn() }
  expect(fireEvent.copy(body, { clipboardData })).toBe(false)
  expect(clipboardData.setData).toHaveBeenCalledWith('text/plain', 'Claim Read source.')
  expect(body.querySelector('[data-citation-group]')).not.toBeNull()
})

it('wires verified supports through the actual standalone answer card', () => {
  const { container } = render(
    <AskAnswerCard
      answer={link}
      citations={citations}
      citationSupports={[{ start: 0, end: link.length, sourceIndex: 0 }]}
      onClose={vi.fn()}
    />,
  )
  expect(container.querySelector('.answer-markdown [data-citation-group]')).not.toBeNull()
})

it('adds a user-operated inline citation only at a verified unique Markdown link', () => {
  const open = vi.fn()
  const answer = `Claim ${link}.`
  const { container } = render(
    <CitedAnswer
      answer={answer}
      citations={citations}
      supports={[{ start: 6, end: 6 + link.length, sourceIndex: 0 }]}
      onOpenLink={open}
    />,
  )
  expect(container.querySelector('p [data-citation-group="true"]')).not.toBeNull()
  expect(open).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Verified source' }))
  expect(open).toHaveBeenCalledExactlyOnceWith(url)
  expect(screen.getByRole('link', { name: 'Read source' })).toBeInTheDocument()
})

it.each([
  { answer: `Claim ${link}.`, start: 0, end: 5 },
  { answer: `😀 ${link}.`, start: 3, end: 3 + link.length },
  { answer: `${link} ${link}`, start: 0, end: link.length },
  { answer: `\`${link}\``, start: 1, end: 1 + link.length },
])('never guesses unsupported or ambiguous placement: %j', ({ answer, start, end }) => {
  const { container } = render(
    <CitedAnswer
      answer={answer}
      citations={citations}
      supports={[{ start, end, sourceIndex: 0 }]}
    />,
  )
  expect(container.querySelector('[data-citation-group]')).toBeNull()
})

it('does not mark a link pointing at a different source', () => {
  const { container } = render(
    <CitedAnswer
      answer={link}
      citations={[{ url: 'https://other.invalid/', title: 'Other' }]}
      supports={[{ start: 0, end: link.length, sourceIndex: 0 }]}
    />,
  )
  expect(container.querySelector('[data-citation-group]')).toBeNull()
})
