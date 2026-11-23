import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { AskAnswerPreview } from '../AskAnswerPreview'

vi.mock('../AskAnswerCard', () => ({
  AskAnswerCard: (props: {
    canCopyAnswer: boolean
    onClose: () => void
    onOpenLink: (url: string) => void
  }) => (
    <div>
      <span>{String(props.canCopyAnswer)}</span>
      <button onClick={() => props.onOpenLink('https://example.invalid')}>Source</button>
      <button onClick={props.onClose}>Close</button>
    </div>
  ),
}))

afterEach(cleanup)

it('disables answer copying and keeps external actions inside the preview', () => {
  render(<AskAnswerPreview />)
  expect(screen.getByText('false')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Source' }))
  expect(screen.getByRole('status')).toHaveTextContent('external link was not opened')
  fireEvent.click(screen.getByRole('button', { name: 'Close' }))
  expect(screen.getByRole('status')).toHaveTextContent('no desktop window was closed')
})
