import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { ModalControlsPreview } from '../ModalControlsPreview'

afterEach(cleanup)

it('changes only local preview state and restores focus after dismissal', () => {
  render(<ModalControlsPreview />)
  const trigger = screen.getByRole('button', { name: 'Preview modal controls' })
  trigger.focus()
  fireEvent.click(trigger)
  fireEvent.click(screen.getByRole('combobox', { name: 'Synthetic choice' }))
  fireEvent.click(screen.getByRole('option', { name: 'Beta' }))
  expect(screen.getByText('Local selection: beta')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Close preview controls' }))
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(trigger).toHaveFocus()
})
