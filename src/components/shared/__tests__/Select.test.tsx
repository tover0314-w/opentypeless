import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { Select } from '../Select'
import { chooseOption, selectedValue } from './selectTestUtils'

const OPTIONS = [
  { value: 'a', label: 'Alpha' },
  { value: 'b', label: 'Beta' },
  { value: 'c', label: 'Gamma', disabled: true },
]

afterEach(cleanup)

describe('Select', () => {
  it('shows the selected label and opens a styled listbox', () => {
    render(<Select aria-label="Pick" value="a" options={OPTIONS} onChange={vi.fn()} />)
    const trigger = screen.getByRole('combobox', { name: 'Pick' })
    expect(trigger).toHaveTextContent('Alpha')
    expect(selectedValue(trigger)).toBe('a')

    fireEvent.click(trigger)
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('option', { name: 'Alpha' })).toHaveAttribute('aria-selected', 'true')
  })

  it('calls onChange with the chosen value and closes', () => {
    const onChange = vi.fn()
    render(<Select aria-label="Pick" value="a" options={OPTIONS} onChange={onChange} />)
    chooseOption(screen.getByRole('combobox'), 'b')
    expect(onChange).toHaveBeenCalledWith('b')
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('supports keyboard selection and skips disabled options', () => {
    const onChange = vi.fn()
    render(<Select aria-label="Pick" value="a" options={OPTIONS} onChange={onChange} />)
    const trigger = screen.getByRole('combobox')
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(onChange).not.toHaveBeenCalled()
  })

  it('closes on Escape without changing the value', () => {
    const onChange = vi.fn()
    render(<Select aria-label="Pick" value="a" options={OPTIONS} onChange={onChange} />)
    const trigger = screen.getByRole('combobox')
    fireEvent.click(trigger)
    fireEvent.keyDown(trigger, { key: 'Escape' })
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(onChange).not.toHaveBeenCalled()
  })
})
