import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Modal } from '../Modal'
import { StrictMode, useState } from 'react'
import { Select } from '../Select'
import { ModelInput } from '../ModelInput'

afterEach(cleanup)

describe('Modal keyboard dismissal', () => {
  it.each([false, true])(
    'bounds long modal content with an internal scroll area (bare=%s)',
    (bare) => {
      render(
        <Modal title="Long content" onClose={vi.fn()} bare={bare}>
          <div style={{ height: 1300 }}>Synthetic long content</div>
          <button>Bottom action</button>
        </Modal>,
      )
      const panel = screen.getByRole('dialog', { name: 'Long content' })
      expect(panel.style.maxHeight).toBe('calc(100dvh - 48px)')
      expect(panel.style.overflowY).toBe('auto')
    },
  )

  it('places a child modal above a parent menu and its own menu above the child', () => {
    function Harness() {
      const [childOpen, setChildOpen] = useState(false)
      return (
        <Modal title="Parent" onClose={vi.fn()}>
          <Select
            aria-label="Parent choice"
            value="a"
            options={[{ value: 'a', label: 'Alpha' }]}
            onChange={vi.fn()}
          />
          <button onClick={() => setChildOpen(true)}>Open child layer</button>
          {childOpen && (
            <Modal title="Child" onClose={() => setChildOpen(false)}>
              <Select
                aria-label="Child choice"
                value="a"
                options={[{ value: 'a', label: 'Alpha' }]}
                onChange={vi.fn()}
              />
            </Modal>
          )}
        </Modal>
      )
    }
    render(<Harness />)
    fireEvent.click(screen.getByRole('combobox', { name: 'Parent choice' }))
    const parentMenuLayer = Number(screen.getByRole('listbox').style.zIndex)
    fireEvent.click(screen.getByRole('button', { name: 'Open child layer' }))
    const childLayer = Number(
      screen.getByRole('dialog', { name: 'Child' }).parentElement!.style.zIndex,
    )
    expect(childLayer).toBeGreaterThan(parentMenuLayer)
    fireEvent.click(screen.getByRole('combobox', { name: 'Child choice' }))
    const childMenu = screen.getByRole('listbox', { name: 'Child choice' })
    expect(Number(childMenu.style.zIndex)).toBeGreaterThan(childLayer)
  })

  it.each(['select', 'model'])(
    'keeps the %s menu above its modal and dismisses it before the modal',
    (kind) => {
      const close = vi.fn()
      render(
        <Modal title="Editor" onClose={close}>
          {kind === 'select' ? (
            <Select
              aria-label="Choice"
              value="a"
              options={[{ value: 'a', label: 'Alpha' }]}
              onChange={vi.fn()}
            />
          ) : (
            <ModelInput label="Choice" value="a" models={['a', 'b']} onChange={vi.fn()} />
          )}
        </Modal>,
      )
      const trigger = screen.getByRole('combobox', { name: 'Choice' })
      trigger.focus()
      fireEvent.keyDown(trigger, { key: 'ArrowDown' })
      const menu = screen.getByRole('listbox')
      const wrapper = screen.getByRole('dialog').parentElement!
      expect(Number(menu.style.zIndex)).toBeGreaterThan(Number(wrapper.style.zIndex))
      fireEvent.keyDown(trigger, { key: 'Escape' })
      expect(screen.queryByRole('listbox')).toBeNull()
      expect(close).not.toHaveBeenCalled()
      expect(trigger).toHaveFocus()
      fireEvent.keyDown(trigger, { key: 'Escape' })
      expect(close).toHaveBeenCalledOnce()
    },
  )

  it('returns escaped focus to the top modal without moving a parent modal', () => {
    const background = document.createElement('button')
    document.body.append(background)
    const { unmount } = render(
      <Modal title="Parent" onClose={vi.fn()}>
        <input data-autofocus aria-label="Parent field" />
        <Modal title="Child" onClose={vi.fn()}>
          <input data-autofocus aria-label="Child field" />
        </Modal>
      </Modal>,
    )
    try {
      const child = screen.getByRole('textbox', { name: 'Child field' })
      expect(child).toHaveFocus()
      background.focus()
      expect(screen.getByRole('dialog', { name: 'Child' })).toHaveFocus()
      screen.getByRole('textbox', { name: 'Parent field' }).focus()
      expect(screen.getByRole('dialog', { name: 'Child' })).toHaveFocus()
    } finally {
      unmount()
      background.remove()
    }
  })

  it('does not steal an active parent input when a hidden child unmounts', () => {
    const onClose = vi.fn()
    const { rerender } = render(
      <Modal title="Parent" onClose={onClose}>
        <input data-autofocus aria-label="First parent input" />
        <input aria-label="Active parent input" />
        <Modal title="Child" onClose={vi.fn()}>
          <input data-autofocus aria-label="Child input" />
        </Modal>
      </Modal>,
    )
    screen.getByRole('dialog', { name: 'Child' }).parentElement!.hidden = true
    const active = screen.getByRole('textbox', { name: 'Active parent input' })
    active.focus()
    rerender(
      <Modal title="Parent" onClose={onClose}>
        <input data-autofocus aria-label="First parent input" />
        <input aria-label="Active parent input" />
      </Modal>,
    )
    expect(active).toHaveFocus()
  })

  it.each(['hidden', 'detached'])(
    'lets the visible parent handle keys when its child is %s',
    (childState) => {
      const parentClose = vi.fn()
      const childClose = vi.fn()
      render(
        <Modal title="Parent" onClose={parentClose}>
          <input data-autofocus aria-label="Parent text" />
          <Modal title="Child" onClose={childClose}>
            <input data-autofocus aria-label="Child text" />
          </Modal>
        </Modal>,
      )
      const childWrapper = screen.getByRole('dialog', { name: 'Child' }).parentElement!
      if (childState === 'hidden') childWrapper.hidden = true
      else childWrapper.remove()
      const parentInput = screen.getByRole('textbox', { name: 'Parent text' })
      try {
        fireEvent.keyDown(parentInput, { key: 'Tab' })
        expect(parentInput).toHaveFocus()
        fireEvent.keyDown(parentInput, { key: 'Escape' })
        expect(parentClose).toHaveBeenCalledOnce()
        expect(childClose).not.toHaveBeenCalled()
      } finally {
        if (childState === 'detached') document.body.append(childWrapper)
      }
    },
  )

  it('places a simultaneously mounted portal child above its parent', () => {
    render(
      <Modal title="Parent" onClose={vi.fn()}>
        <Modal title="Child" onClose={vi.fn()}>
          <button>Child control</button>
        </Modal>
      </Modal>,
    )
    const parent = screen.getByRole('dialog', { name: 'Parent' }).parentElement!
    const child = screen.getByRole('dialog', { name: 'Child' }).parentElement!
    expect(Number(child.style.zIndex)).toBeGreaterThan(Number(parent.style.zIndex))
  })

  it('returns to the parent after child dismissal under StrictMode', () => {
    const parentClose = vi.fn()
    function Harness() {
      const [childOpen, setChildOpen] = useState(false)
      return (
        <Modal title="Parent" onClose={parentClose}>
          <button onClick={() => setChildOpen(true)}>Open child</button>
          {childOpen && (
            <Modal title="Child" onClose={() => setChildOpen(false)}>
              <input data-autofocus aria-label="Child text" />
            </Modal>
          )}
        </Modal>
      )
    }
    render(
      <StrictMode>
        <Harness />
      </StrictMode>,
    )
    const trigger = screen.getByRole('button', { name: 'Open child' })
    trigger.focus()
    fireEvent.click(trigger)
    const input = screen.getByRole('textbox', { name: 'Child text' })
    expect(input).toHaveFocus()
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(screen.queryByRole('dialog', { name: 'Child' })).toBeNull()
    expect(parentClose).not.toHaveBeenCalled()
    expect(trigger).toHaveFocus()
    fireEvent.keyDown(trigger, { key: 'Escape' })
    expect(parentClose).toHaveBeenCalledOnce()
  })

  it('does not steal focus when an earlier independent modal unmounts', () => {
    const older = render(
      <Modal title="Older" onClose={vi.fn()}>
        <input data-autofocus aria-label="Older input" />
      </Modal>,
    )
    const newerClose = vi.fn()
    render(
      <Modal title="Newer" onClose={newerClose}>
        <input data-autofocus aria-label="Newer input" />
      </Modal>,
    )
    const input = screen.getByRole('textbox', { name: 'Newer input' })
    expect(input).toHaveFocus()
    older.unmount()
    expect(input).toHaveFocus()
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(newerClose).toHaveBeenCalledOnce()
  })

  it('keeps focus and Escape in a simultaneously mounted portal child', () => {
    const parentClose = vi.fn()
    const childClose = vi.fn()
    render(
      <Modal title="Parent" onClose={parentClose}>
        <input data-autofocus aria-label="Parent input" />
        <Modal title="Child" onClose={childClose}>
          <input data-autofocus aria-label="Child input" />
        </Modal>
      </Modal>,
    )
    const child = screen.getByRole('textbox', { name: 'Child input' })
    expect(child).toHaveFocus()
    fireEvent.keyDown(child, { key: 'Escape' })
    expect(childClose).toHaveBeenCalledOnce()
    expect(parentClose).not.toHaveBeenCalled()
  })

  it('autofocuses an explicit programmatic target without adding it to the Tab loop', () => {
    render(
      <Modal title="Edit" onClose={vi.fn()}>
        <input data-autofocus tabIndex={-1} aria-label="Programmatic target" />
        <button>First</button>
        <button>Last</button>
      </Modal>,
    )
    const target = screen.getByRole('textbox', { name: 'Programmatic target' })
    expect(target).toHaveFocus()
    fireEvent.keyDown(target, { key: 'Tab' })
    expect(screen.getByRole('button', { name: 'First' })).toHaveFocus()
    fireEvent.keyDown(screen.getByRole('button', { name: 'Last' }), { key: 'Tab' })
    expect(screen.getByRole('button', { name: 'First' })).toHaveFocus()
  })

  it('restores focus to a visible programmatic entry outside the Tab order', () => {
    const trigger = document.createElement('div')
    trigger.tabIndex = -1
    document.body.append(trigger)
    trigger.focus()
    const { unmount } = render(
      <Modal title="Edit" onClose={vi.fn()}>
        <input data-autofocus aria-label="Text" />
      </Modal>,
    )
    try {
      unmount()
      expect(trigger).toHaveFocus()
    } finally {
      trigger.remove()
    }
  })

  it.each(['hidden', 'inert', 'disabled'])(
    'does not restore focus to an entry that became %s',
    (attribute) => {
      const trigger = document.createElement('button')
      document.body.append(trigger)
      trigger.focus()
      const { unmount } = render(
        <Modal title="Edit" onClose={vi.fn()}>
          <input data-autofocus aria-label="Text" />
        </Modal>,
      )
      trigger.setAttribute(attribute, '')
      const focus = vi.spyOn(trigger, 'focus')
      try {
        unmount()
        expect(focus).not.toHaveBeenCalled()
      } finally {
        focus.mockRestore()
        trigger.remove()
      }
    },
  )

  it('restores focus to an available entry', () => {
    const trigger = document.createElement('button')
    document.body.append(trigger)
    trigger.focus()
    const { unmount } = render(
      <Modal title="Edit" onClose={vi.fn()}>
        <input data-autofocus aria-label="Text" />
      </Modal>,
    )
    try {
      unmount()
      expect(trigger).toHaveFocus()
    } finally {
      trigger.remove()
    }
  })

  it.each([{ display: 'none' as const }, { visibility: 'hidden' as const }])(
    'skips autofocus controls inside invisible ancestors: %j',
    (style) => {
      render(
        <Modal title="Edit" onClose={vi.fn()}>
          <div style={style}>
            <input data-autofocus aria-label="Invisible" />
          </div>
          <input data-autofocus aria-label="Visible" />
        </Modal>,
      )
      expect(screen.getByRole('textbox', { name: 'Visible' })).toHaveFocus()
    },
  )

  it.each(['hidden', 'inert'])('skips autofocus inside a %s region', (attribute) => {
    render(
      <Modal title="Edit" onClose={vi.fn()}>
        <div {...{ [attribute]: true }}>
          <input data-autofocus aria-label="Unavailable" />
        </div>
        <input data-autofocus aria-label="Available" />
      </Modal>,
    )
    expect(screen.getByRole('textbox', { name: 'Available' })).toHaveFocus()
  })

  it('focuses the panel when the autofocus control is disabled', () => {
    render(
      <Modal title="Loading" onClose={vi.fn()}>
        <input data-autofocus disabled aria-label="Unavailable" />
      </Modal>,
    )
    expect(screen.getByRole('dialog', { name: 'Loading' })).toHaveFocus()
  })

  it.each([false, true])('redirects focus from a newly disabled control: shift=%s', (shiftKey) => {
    const { rerender } = render(
      <Modal title="Saving" onClose={vi.fn()}>
        <button>First</button>
        <button>Save</button>
        <button>Last</button>
      </Modal>,
    )
    const save = screen.getByRole('button', { name: 'Save' })
    save.focus()
    rerender(
      <Modal title="Saving" onClose={vi.fn()}>
        <button>First</button>
        <button disabled>Save</button>
        <button>Last</button>
      </Modal>,
    )
    fireEvent.keyDown(save, { key: 'Tab', shiftKey })
    expect(screen.getByRole('button', { name: shiftKey ? 'Last' : 'First' })).toHaveFocus()
  })

  it('includes explicit tab stops and multiline inputs in the focus boundary', () => {
    render(
      <Modal title="Feedback" onClose={vi.fn()}>
        <span tabIndex={0}>Explanation</span>
        <textarea aria-label="Feedback text" />
        <button>Close</button>
      </Modal>,
    )
    const explanation = screen.getByText('Explanation')
    const close = screen.getByRole('button', { name: 'Close' })
    close.focus()
    fireEvent.keyDown(close, { key: 'Tab' })
    expect(explanation).toHaveFocus()
    fireEvent.keyDown(explanation, { key: 'Tab', shiftKey: true })
    expect(close).toHaveFocus()
    const input = screen.getByRole('textbox')
    input.focus()
    const event = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
    fireEvent(input, event)
    expect(event.defaultPrevented).toBe(false)
  })

  it.each([false, true])(
    'keeps Tab inside a dialog without enabled controls: shift=%s',
    (shiftKey) => {
      render(
        <Modal title="Saving" onClose={vi.fn()} dismissible={false}>
          <button disabled>Save</button>
        </Modal>,
      )
      const dialog = screen.getByRole('dialog')
      const event = new KeyboardEvent('keydown', {
        key: 'Tab',
        shiftKey,
        bubbles: true,
        cancelable: true,
      })
      fireEvent(dialog, event)
      expect(event.defaultPrevented).toBe(true)
      expect(dialog).toHaveFocus()
    },
  )

  it('ignores hidden inputs and negative tab stops when wrapping focus', () => {
    render(
      <Modal title="Edit" onClose={vi.fn()}>
        <input type="hidden" tabIndex={0} />
        <button tabIndex={-1}>Excluded</button>
        <button>First</button>
        <button>Last</button>
        <input aria-label="Hidden picker" style={{ display: 'none' }} />
      </Modal>,
    )
    const last = screen.getByRole('button', { name: 'Last' })
    const first = screen.getByRole('button', { name: 'First' })
    last.focus()
    fireEvent.keyDown(last, { key: 'Tab' })
    expect(first).toHaveFocus()
    fireEvent.keyDown(first, { key: 'Tab', shiftKey: true })
    expect(last).toHaveFocus()
  })

  it('does not dismiss while Escape belongs to an input method composition', () => {
    const onClose = vi.fn()
    render(
      <Modal title="Edit" onClose={onClose}>
        <input aria-label="Text" />
      </Modal>,
    )
    const input = screen.getByRole('textbox')
    fireEvent.keyDown(input, { key: 'Escape', isComposing: true })
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.keyDown(input, { key: 'Escape', keyCode: 229 })
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
