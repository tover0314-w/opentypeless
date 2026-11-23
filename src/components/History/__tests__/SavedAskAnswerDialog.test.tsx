import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { SavedAskAnswerDialog } from '../SavedAskAnswerDialog'

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))
afterEach(cleanup)

it('reopens a saved verified citation as an inline control inside the real dialog', () => {
  const url = 'https://example.invalid/source'
  const answer = `[Source](${url})`
  const open = vi.fn()
  const { container } = render(
    <SavedAskAnswerDialog
      question="Question"
      answer={answer}
      citations={[{ url, title: 'Stored source' }]}
      citationSupports={[{ start: 0, end: answer.length, sourceIndex: 0 }]}
      onClose={vi.fn()}
      onOpenLink={open}
    />,
  )
  expect(document.querySelector('.answer-markdown [data-citation-group]')).not.toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Stored source' }))
  expect(open).toHaveBeenCalledExactlyOnceWith(url)
  expect(container.querySelector('[data-tauri-drag-region]')).toBeNull()
})

it.each(['open_question', 'ask_selection'] as const)(
  'shows the recorded %s task without inventing retained selection text',
  (askIntent) => {
    render(
      <SavedAskAnswerDialog
        question="Synthetic question"
        answer="Synthetic answer"
        askIntent={askIntent}
        onClose={vi.fn()}
        onOpenLink={vi.fn()}
      />,
    )
    expect(
      screen.getByText(
        askIntent === 'ask_selection'
          ? 'history.savedSelectionQuestion'
          : 'history.savedOpenQuestion',
      ),
    ).toBeInTheDocument()
    if (askIntent === 'ask_selection') {
      expect(screen.getByText('history.savedSelectionNotRetained')).toBeInTheDocument()
    } else {
      expect(screen.queryByText('history.savedSelectionNotRetained')).toBeNull()
    }
  },
)

it('renders saved content and sources in the main modal without a desktop drag region', () => {
  const close = vi.fn()
  const openLink = vi.fn()
  render(
    <SavedAskAnswerDialog
      question="Synthetic saved question"
      answer="## Synthetic saved answer"
      citations={[{ url: 'https://example.invalid/source', title: 'Saved source' }]}
      onClose={close}
      onOpenLink={openLink}
    />,
  )
  const dialog = screen.getByRole('dialog')
  expect(within(dialog).getByText('Synthetic saved question')).toBeInTheDocument()
  expect(
    within(dialog).getByRole('heading', { name: 'Synthetic saved answer' }),
  ).toBeInTheDocument()
  expect(within(dialog).getByText('app.name')).toBeInTheDocument()
  expect(dialog.querySelector('[data-tauri-drag-region]')).toBeNull()
  expect(screen.queryByText('history.savedSelectionQuestion')).toBeNull()
  expect(screen.queryByText('history.savedOpenQuestion')).toBeNull()
  expect(close).not.toHaveBeenCalled()
  expect(openLink).not.toHaveBeenCalled()
  fireEvent.click(within(dialog).getByRole('link', { name: 'Saved source' }))
  expect(openLink).toHaveBeenCalledExactlyOnceWith('https://example.invalid/source')
  fireEvent.click(within(dialog).getByRole('button', { name: 'ask.close' }))
  expect(close).toHaveBeenCalledTimes(1)
})

it('closes on Escape without requesting a new answer or opening sources', () => {
  const close = vi.fn()
  const openLink = vi.fn()
  render(
    <SavedAskAnswerDialog
      question="Synthetic question"
      answer="Synthetic answer"
      onClose={close}
      onOpenLink={openLink}
    />,
  )
  fireEvent.keyDown(document, { key: 'Escape' })
  expect(close).toHaveBeenCalledTimes(1)
  expect(openLink).not.toHaveBeenCalled()
})
