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
  it('does not revive an old highlight when the selected value changes away and back', () => {
    const change = vi.fn()
    const options = [...OPTIONS, { value: 'd', label: 'Delta' }]
    const view = render(<Select value="a" options={options} onChange={change} />)
    const trigger = screen.getByRole('combobox')
    fireEvent.click(trigger)
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    view.rerender(<Select value="d" options={options} onChange={change} />)
    view.rerender(<Select value="a" options={options} onChange={change} />)
    expect(trigger).toHaveAttribute(
      'aria-activedescendant',
      screen.getByRole('option', { name: 'Alpha' }).id,
    )
    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(change).not.toHaveBeenCalled()
  })

  it('does not overwrite an externally changed value with an older highlight', () => {
    const change = vi.fn()
    const options = [...OPTIONS, { value: 'd', label: 'Delta' }]
    const view = render(<Select value="a" options={options} onChange={change} />)
    const trigger = screen.getByRole('combobox')
    fireEvent.click(trigger)
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    view.rerender(<Select value="d" options={options} onChange={change} />)
    expect(trigger).toHaveAttribute(
      'aria-activedescendant',
      screen.getByRole('option', { name: 'Delta' }).id,
    )
    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(change).not.toHaveBeenCalled()
  })

  it('preserves the highlighted option identity when options reorder', () => {
    const change = vi.fn()
    const view = render(<Select value="a" options={OPTIONS} onChange={change} />)
    const trigger = screen.getByRole('combobox')
    fireEvent.click(trigger)
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    view.rerender(
      <Select value="a" options={[OPTIONS[1], OPTIONS[0], OPTIONS[2]]} onChange={change} />,
    )
    expect(trigger).toHaveAttribute(
      'aria-activedescendant',
      screen.getByRole('option', { name: 'Beta' }).id,
    )
    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(change).toHaveBeenCalledExactlyOnceWith('b')
  })

  it('closes when its trigger leaves a local scrolling container', () => {
    const change = vi.fn()
    const view = render(
      <div style={{ overflowY: 'auto' }}>
        <Select value="a" options={OPTIONS} onChange={change} />
      </div>,
    )
    const parent = view.container.firstElementChild as HTMLElement
    const trigger = screen.getByRole('combobox')
    const bounds = {
      left: 100,
      right: 380,
      top: 210,
      bottom: 250,
      width: 280,
      height: 40,
      x: 100,
      y: 210,
      toJSON: () => ({}),
    }
    vi.spyOn(trigger, 'getBoundingClientRect').mockImplementation(() => bounds)
    vi.spyOn(parent, 'getBoundingClientRect').mockReturnValue({
      ...bounds,
      top: 200,
      bottom: 400,
      height: 200,
    })
    fireEvent.click(trigger)
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    bounds.top = 150
    bounds.bottom = 190
    fireEvent.scroll(parent)
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(change).not.toHaveBeenCalled()
  })

  it('closes without selection when scrolling moves its trigger outside the viewport', () => {
    const change = vi.fn()
    render(<Select value="a" options={OPTIONS} onChange={change} />)
    const trigger = screen.getByRole('combobox')
    const bounds = {
      left: 100,
      right: 380,
      top: 100,
      bottom: 140,
      width: 280,
      height: 40,
      x: 100,
      y: 100,
      toJSON: () => ({}),
    }
    vi.spyOn(trigger, 'getBoundingClientRect').mockImplementation(() => bounds)
    fireEvent.click(trigger)
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    bounds.top = -50
    bounds.bottom = -10
    fireEvent.scroll(window)
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(change).not.toHaveBeenCalled()
  })

  it('shows decorative leading marks without changing option names or keyboard selection', () => {
    const onChange = vi.fn()
    render(
      <Select
        aria-label="Provider"
        value="a"
        options={[
          { value: 'a', label: 'Alpha', leading: <span data-testid="alpha-mark">Mark</span> },
          { value: 'b', label: 'Beta', leading: <span data-testid="beta-mark">Mark</span> },
        ]}
        onChange={onChange}
      />,
    )
    const trigger = screen.getByRole('combobox', { name: 'Provider' })
    expect(screen.getByTestId('alpha-mark').parentElement).toHaveAttribute('aria-hidden', 'true')
    trigger.focus()
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    expect(screen.getByRole('option', { name: 'Alpha' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Beta' })).toBeInTheDocument()
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledExactlyOnceWith('b')
    expect(trigger).toHaveFocus()
  })

  it.each([
    ['empty', []],
    ['disabled', OPTIONS.map((option) => ({ ...option, disabled: true }))],
  ] as const)('does not select unavailable options in an %s menu', (_kind, options) => {
    const onChange = vi.fn()
    render(<Select value="a" options={[...options]} onChange={onChange} />)
    const trigger = screen.getByRole('combobox')
    trigger.focus()
    fireEvent.click(trigger)
    for (const key of ['ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter', ' ']) {
      fireEvent.keyDown(trigger, { key })
      expect(trigger).not.toHaveAttribute('aria-activedescendant')
      expect(screen.getByRole('listbox')).toBeInTheDocument()
      expect(onChange).not.toHaveBeenCalled()
    }
    fireEvent.keyDown(trigger, { key: 'Escape' })
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(trigger).toHaveFocus()
  })

  it('cannot select an active option that becomes disabled while its menu is open', () => {
    const onChange = vi.fn()
    const { rerender } = render(<Select value="a" options={OPTIONS} onChange={onChange} />)
    const trigger = screen.getByRole('combobox')
    fireEvent.click(trigger)
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    expect(trigger).toHaveAttribute(
      'aria-activedescendant',
      screen.getByRole('option', { name: 'Beta' }).id,
    )
    rerender(
      <Select
        value="a"
        options={OPTIONS.map((option) =>
          option.value === 'b' ? { ...option, disabled: true } : option,
        )}
        onChange={onChange}
      />,
    )
    expect(trigger).toHaveAttribute(
      'aria-activedescendant',
      screen.getByRole('option', { name: 'Alpha' }).id,
    )
    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('keeps the active enabled option when hovering a disabled option', () => {
    const onChange = vi.fn()
    render(<Select value="a" options={OPTIONS} onChange={onChange} />)
    const trigger = screen.getByRole('combobox')
    fireEvent.click(trigger)
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    const activeId = screen.getByRole('option', { name: 'Beta' }).id
    expect(trigger).toHaveAttribute('aria-activedescendant', activeId)
    fireEvent.mouseEnter(screen.getByRole('option', { name: 'Gamma' }))
    expect(trigger).toHaveAttribute('aria-activedescendant', activeId)
    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledExactlyOnceWith('b')
  })

  it('prevents option mousedown from moving focus before mouse selection completes', () => {
    const onChange = vi.fn()
    render(<Select aria-label="Pick" value="a" options={OPTIONS} onChange={onChange} />)
    const trigger = screen.getByRole('combobox')
    trigger.focus()
    fireEvent.click(trigger)
    const option = screen.getByRole('option', { name: 'Beta' })
    expect(fireEvent.mouseDown(option)).toBe(false)
    expect(trigger).toHaveFocus()
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    fireEvent.mouseUp(option)
    fireEvent.click(option)
    expect(onChange).toHaveBeenCalledExactlyOnceWith('b')
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(trigger).toHaveFocus()
  })

  it('closes when its trigger loses focus without changing the value or reclaiming focus', () => {
    const onChange = vi.fn()
    render(
      <>
        <Select aria-label="Pick" value="a" options={OPTIONS} onChange={onChange} />
        <button type="button">Other control</button>
      </>,
    )
    const trigger = screen.getByRole('combobox')
    trigger.focus()
    fireEvent.click(trigger)
    const other = screen.getByRole('button', { name: 'Other control' })
    other.focus()
    fireEvent.blur(trigger, { relatedTarget: other })
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(other).toHaveFocus()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('leaves input composition keys to the input method without selecting or closing', () => {
    const onChange = vi.fn()
    render(<Select value="a" options={OPTIONS} onChange={onChange} />)
    const trigger = screen.getByRole('combobox')
    fireEvent.keyDown(trigger, { key: 'Enter', isComposing: true })
    expect(screen.queryByRole('listbox')).toBeNull()
    fireEvent.click(trigger)
    const active = trigger.getAttribute('aria-activedescendant')
    for (const key of ['ArrowDown', 'Enter', 'Escape']) {
      fireEvent.keyDown(trigger, { key, isComposing: true })
      expect(screen.getByRole('listbox')).toBeInTheDocument()
      expect(trigger).toHaveAttribute('aria-activedescendant', active)
    }
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith('b')
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('fits an upward menu within the space above its trigger', () => {
    render(
      <Select
        value="0"
        options={Array.from({ length: 20 }, (_, index) => ({
          value: String(index),
          label: `Option ${index}`,
        }))}
        onChange={vi.fn()}
      />,
    )
    const trigger = screen.getByRole('combobox')
    vi.spyOn(trigger, 'getBoundingClientRect').mockReturnValue({
      left: 100,
      right: 380,
      top: 300,
      bottom: 340,
      width: 280,
      height: 40,
      x: 100,
      y: 300,
      toJSON: () => ({}),
    })
    const originalHeight = window.innerHeight
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 500 })
    try {
      fireEvent.click(trigger)
      const menu = screen.getByRole('listbox')
      expect(parseFloat(menu.style.maxHeight)).toBeLessThanOrEqual(288)
    } finally {
      Object.defineProperty(window, 'innerHeight', {
        configurable: true,
        value: originalHeight,
      })
    }
  })

  it('uses enabled boundaries for Home and End and exposes keyboard focus', () => {
    const onChange = vi.fn()
    render(
      <Select
        aria-label="Pick"
        value="b"
        options={[
          { value: 'disabled-first', label: 'First', disabled: true },
          { value: 'a', label: 'Alpha' },
          { value: 'b', label: 'Beta' },
          { value: 'disabled-last', label: 'Last', disabled: true },
        ]}
        onChange={onChange}
      />,
    )
    const trigger = screen.getByRole('combobox')
    fireEvent.click(trigger)
    fireEvent.keyDown(trigger, { key: 'Home' })
    expect(trigger).toHaveAttribute(
      'aria-activedescendant',
      screen.getByRole('option', { name: 'Alpha' }).id,
    )
    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith('a')
    fireEvent.click(trigger)
    fireEvent.keyDown(trigger, { key: 'End' })
    expect(trigger).toHaveAttribute(
      'aria-activedescendant',
      screen.getByRole('option', { name: 'Beta' }).id,
    )
  })

  it('closes an open menu when disabled and cannot change its value', () => {
    const onChange = vi.fn()
    const { rerender } = render(<Select value="a" options={OPTIONS} onChange={onChange} />)
    fireEvent.click(screen.getByRole('combobox'))
    rerender(<Select value="a" options={OPTIONS} onChange={onChange} disabled />)
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(onChange).not.toHaveBeenCalled()
  })

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
