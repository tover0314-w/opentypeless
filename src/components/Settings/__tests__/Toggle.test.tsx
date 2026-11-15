import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Toggle } from '../shared/Toggle'

afterEach(cleanup)

describe('settings toggle', () => {
  it('disables both track and thumb transitions for reduced motion', () => {
    render(<Toggle checked onChange={vi.fn()} ariaLabel="Save history" />)
    const toggle = screen.getByRole('switch', { name: 'Save history' })
    expect(toggle).toHaveClass('motion-reduce:transition-none')
    expect(toggle.firstElementChild).toHaveClass('motion-reduce:transition-none')
  })

  it('uses its visible label as the accessible name', () => {
    const change = vi.fn()
    render(<Toggle checked={false} onChange={change} label="Interaction sounds" />)
    const toggle = screen.getByRole('switch', { name: 'Interaction sounds' })
    fireEvent.click(screen.getByText('Interaction sounds'))
    expect(change).toHaveBeenCalledExactlyOnceWith(true)
    expect(toggle).toHaveAttribute('aria-checked', 'false')
  })

  it('centers its compact track within the shared control height', () => {
    const change = vi.fn()
    const { container } = render(
      <Toggle checked={false} onChange={change} ariaLabel="Save history" />,
    )
    expect(container.firstElementChild).toHaveClass('min-h-10', 'items-center')
    const toggle = screen.getByRole('switch', { name: 'Save history' })
    expect(toggle).toHaveClass('h-[18px]', 'w-[30px]')
    fireEvent.click(toggle)
    expect(change).toHaveBeenCalledExactlyOnceWith(true)
  })

  it('keeps disabled state and its accessible name', () => {
    const change = vi.fn()
    render(<Toggle checked onChange={change} ariaLabel="Save history" disabled />)
    const toggle = screen.getByRole('switch', { name: 'Save history' })
    expect(toggle).toHaveAttribute('aria-checked', 'true')
    expect(toggle).toBeDisabled()
    fireEvent.click(toggle)
    expect(change).not.toHaveBeenCalled()
  })
})
