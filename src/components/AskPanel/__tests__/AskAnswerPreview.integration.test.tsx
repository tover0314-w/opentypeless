import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { AskAnswerPreview } from '../AskAnswerPreview'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

it('renders the actual answer card with local source actions and no clipboard writes', () => {
  const writeText = vi.fn()
  const write = vi.fn()
  vi.stubGlobal('navigator', { clipboard: { writeText, write } })
  render(<AskAnswerPreview />)
  const card = screen.getByTestId('ask-floating-note')
  expect(within(card).getByText('Synthetic answer')).toBeInTheDocument()
  expect(within(card).queryByRole('button', { name: /copy/i })).not.toBeInTheDocument()
  expect(within(card).getAllByRole('link')).toHaveLength(3)
  fireEvent.click(within(card).getByRole('link', { name: 'Synthetic source 1' }))
  expect(screen.getByRole('status')).toHaveTextContent('external link was not opened')
  const expansion = within(card).getByRole('button', { expanded: false })
  fireEvent.click(expansion)
  expect(expansion).toHaveAttribute('aria-expanded', 'true')
  expect(within(card).getAllByRole('link')).toHaveLength(5)
  fireEvent.click(expansion)
  expect(within(card).getAllByRole('link')).toHaveLength(3)
  expect(write).not.toHaveBeenCalled()
  expect(writeText).not.toHaveBeenCalled()
})

it('opens and closes real long-content modal controls without replacing the answer', () => {
  render(<AskAnswerPreview />)
  fireEvent.click(screen.getByRole('button', { name: 'Preview long modal content' }))
  const dialog = screen.getByRole('dialog')
  expect(within(dialog).getByText('Synthetic long content paragraph 30')).toBeInTheDocument()
  fireEvent.click(within(dialog).getByRole('button', { name: 'Close preview controls' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.getByTestId('ask-floating-note')).toBeInTheDocument()
})
