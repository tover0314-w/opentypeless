import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ModelInput } from '../ModelInput'

afterEach(cleanup)

describe('model candidate input', () => {
  it('does not revive an old highlight when the current value changes away and back', () => {
    const change = vi.fn()
    const models = ['alpha', 'beta', 'gamma']
    const view = render(
      <ModelInput label="Model" value="alpha" models={models} onChange={change} />,
    )
    const input = screen.getByRole('combobox')
    fireEvent.focus(input)
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    view.rerender(<ModelInput label="Model" value="gamma" models={models} onChange={change} />)
    view.rerender(<ModelInput label="Model" value="alpha" models={models} onChange={change} />)
    expect(input).toHaveAttribute(
      'aria-activedescendant',
      screen.getByRole('option', { name: 'alpha' }).id,
    )
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(change).not.toHaveBeenCalled()
  })

  it('does not overwrite a changed current model with a stale highlight', () => {
    const change = vi.fn()
    const models = ['alpha', 'beta', 'gamma']
    const view = render(
      <ModelInput label="Model" value="alpha" models={models} onChange={change} />,
    )
    const input = screen.getByRole('combobox')
    fireEvent.focus(input)
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    view.rerender(<ModelInput label="Model" value="gamma" models={models} onChange={change} />)
    expect(input).toHaveAttribute(
      'aria-activedescendant',
      screen.getByRole('option', { name: 'gamma' }).id,
    )
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(change).not.toHaveBeenCalled()
  })

  it('keeps the highlighted model identity when candidates are reordered', () => {
    const change = vi.fn()
    const view = render(
      <ModelInput label="Model" value="alpha" models={['alpha', 'beta']} onChange={change} />,
    )
    const input = screen.getByRole('combobox')
    fireEvent.focus(input)
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    view.rerender(
      <ModelInput label="Model" value="alpha" models={['beta', 'alpha']} onChange={change} />,
    )
    expect(input).toHaveAttribute(
      'aria-activedescendant',
      screen.getByRole('option', { name: 'beta' }).id,
    )
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(change).toHaveBeenCalledExactlyOnceWith('beta')
  })

  it('falls back to the current model when a highlighted candidate disappears', () => {
    const change = vi.fn()
    const view = render(
      <ModelInput
        label="Model"
        value="alpha"
        models={['alpha', 'beta', 'gamma']}
        onChange={change}
      />,
    )
    const input = screen.getByRole('combobox')
    fireEvent.focus(input)
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    view.rerender(
      <ModelInput label="Model" value="alpha" models={['gamma', 'alpha']} onChange={change} />,
    )
    expect(input).toHaveAttribute(
      'aria-activedescendant',
      screen.getByRole('option', { name: 'alpha' }).id,
    )
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(change).not.toHaveBeenCalled()
  })

  it('dismisses candidates when the input leaves an ancestor scroll area', () => {
    const view = render(
      <div style={{ overflowY: 'auto' }}>
        <ModelInput label="Model" value="alpha" models={['alpha', 'beta']} onChange={vi.fn()} />
      </div>,
    )
    const input = screen.getByRole('combobox')
    const parent = view.container.firstElementChild as HTMLElement
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
    vi.spyOn(input, 'getBoundingClientRect').mockImplementation(() => bounds)
    vi.spyOn(parent, 'getBoundingClientRect').mockReturnValue({
      ...bounds,
      top: 200,
      bottom: 400,
      height: 200,
    })
    fireEvent.focus(input)
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    bounds.top = 150
    bounds.bottom = 190
    fireEvent.scroll(parent)
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('dismisses candidates when scrolling moves the input outside the viewport', () => {
    const change = vi.fn()
    render(<ModelInput label="Model" value="alpha" models={['alpha', 'beta']} onChange={change} />)
    const input = screen.getByRole('combobox')
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
    vi.spyOn(input, 'getBoundingClientRect').mockImplementation(() => bounds)
    fireEvent.focus(input)
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    bounds.top = -50
    bounds.bottom = -10
    fireEvent.scroll(window)
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(change).not.toHaveBeenCalled()
  })

  it.each(['ArrowDown', 'ArrowUp'])('reopens on the current model with %s', (key) => {
    const change = vi.fn()
    render(<ModelInput label="Model" value="beta" models={['alpha', 'beta']} onChange={change} />)
    const input = screen.getByRole('combobox')
    fireEvent.focus(input)
    fireEvent.keyDown(input, { key: 'Escape' })
    fireEvent.keyDown(input, { key })
    expect(input).toHaveAttribute(
      'aria-activedescendant',
      screen.getByRole('option', { name: 'beta' }).id,
    )
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(change).not.toHaveBeenCalled()
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('restores the current model highlight when reopening after Escape', () => {
    const change = vi.fn()
    render(<ModelInput label="Model" value="alpha" models={['alpha', 'beta']} onChange={change} />)
    const input = screen.getByRole('combobox')
    fireEvent.focus(input)
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'Escape' })
    fireEvent.click(input)
    expect(input).toHaveAttribute(
      'aria-activedescendant',
      screen.getByRole('option', { name: 'alpha' }).id,
    )
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(change).not.toHaveBeenCalledWith('beta')
  })

  it('does not treat case-different identifiers as an exact current model', () => {
    const change = vi.fn()
    render(<ModelInput label="Model" value="BETA" models={['alpha', 'beta']} onChange={change} />)
    const input = screen.getByRole('combobox')
    fireEvent.focus(input)
    expect(screen.queryByRole('option', { name: 'alpha' })).toBeNull()
    expect(input).toHaveValue('BETA')
    expect(change).not.toHaveBeenCalled()
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(change).toHaveBeenCalledExactlyOnceWith('beta')
  })

  it.each(['ctrlKey', 'altKey', 'metaKey', 'shiftKey'])(
    'leaves modified arrow editing to the input for %s',
    (modifier) => {
      render(
        <ModelInput label="Model" value="alpha" models={['alpha', 'beta']} onChange={vi.fn()} />,
      )
      const input = screen.getByRole('combobox')
      fireEvent.focus(input)
      const originalActive = input.getAttribute('aria-activedescendant')
      const event = new KeyboardEvent('keydown', {
        key: 'ArrowDown',
        [modifier]: true,
        bubbles: true,
        cancelable: true,
      })
      fireEvent(input, event)
      expect(event.defaultPrevented).toBe(false)
      expect(input).toHaveAttribute('aria-activedescendant', originalActive!)
    },
  )

  it('keeps a long model identifier intact through deduplication and keyboard selection', () => {
    const longModel = `provider/${'long-model-name-'.repeat(30)}release`
    const onChange = vi.fn()
    render(
      <ModelInput
        label="Model"
        value="alpha"
        models={['alpha', longModel, longModel]}
        onChange={onChange}
      />,
    )
    const input = screen.getByRole('combobox')
    fireEvent.focus(input)
    const option = screen.getByRole('option', { name: longModel })
    const scrollIntoView = vi.fn()
    Object.defineProperty(option, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoView,
    })

    expect(screen.getAllByRole('option')).toHaveLength(2)
    expect(option.querySelector('span')).toHaveClass('truncate')
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    expect(input).toHaveAttribute('aria-activedescendant', option.id)
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(onChange).toHaveBeenCalledWith(longModel)
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(input).toHaveFocus()
  })

  it('keeps an upward candidate menu eight pixels inside the viewport', () => {
    render(
      <ModelInput
        label="Model"
        value=""
        models={Array.from({ length: 20 }, (_, index) => `model-${index}`)}
        onChange={vi.fn()}
      />,
    )
    const input = screen.getByRole('combobox')
    vi.spyOn(input, 'getBoundingClientRect').mockReturnValue({
      left: 100,
      right: 380,
      top: 200,
      bottom: 240,
      width: 280,
      height: 40,
      x: 100,
      y: 200,
      toJSON: () => ({}),
    })
    const originalHeight = window.innerHeight
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 300 })
    try {
      fireEvent.focus(input)
      expect(parseFloat(screen.getByRole('listbox').style.top)).toBeGreaterThanOrEqual(8)
    } finally {
      Object.defineProperty(window, 'innerHeight', {
        configurable: true,
        value: originalHeight,
      })
    }
  })

  it('shows other models when opening a known current model', () => {
    const onChange = vi.fn()
    render(
      <ModelInput label="Model" value="alpha" models={['alpha', 'beta']} onChange={onChange} />,
    )
    const input = screen.getByRole('combobox')
    fireEvent.focus(input)
    expect(screen.getByRole('option', { name: 'alpha' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('option', { name: 'beta' })).toBeInTheDocument()
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith('beta')
  })

  it('keeps free text entry when no candidates exist', () => {
    const onChange = vi.fn()
    render(<ModelInput label="Model" value="" models={[]} onChange={onChange} />)
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'custom-model' } })
    expect(onChange).toHaveBeenCalledWith('custom-model')
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('opens a styled candidate list and selects without losing input focus', () => {
    const onChange = vi.fn()
    render(<ModelInput label="Model" value="" models={['alpha', 'beta']} onChange={onChange} />)
    const input = screen.getByRole('combobox')
    fireEvent.focus(input)
    expect(screen.getByRole('listbox')).toHaveAttribute('data-select-menu')
    fireEvent.click(screen.getByRole('option', { name: 'beta' }))
    expect(onChange).toHaveBeenCalledWith('beta')
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(input).not.toHaveAttribute('list')
    expect(input).toHaveFocus()
  })

  it('filters candidates and supports keyboard selection and Escape', () => {
    const onChange = vi.fn()
    render(<ModelInput label="Model" value="bet" models={['alpha', 'beta']} onChange={onChange} />)
    const input = screen.getByRole('combobox')
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    expect(screen.queryByRole('option', { name: 'alpha' })).toBeNull()
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith('beta')
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('closes on outside pointer input and does not select while composing', () => {
    const onChange = vi.fn()
    render(<ModelInput label="Model" value="" models={['alpha']} onChange={onChange} />)
    const input = screen.getByRole('combobox')
    fireEvent.focus(input)
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.pointerDown(document.body)
    expect(screen.queryByRole('listbox')).toBeNull()
  })
})
