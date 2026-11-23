import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ModalFrame } from '../index'
import { Modal } from '../../shared/Modal'

afterEach(cleanup)

describe('ModalFrame keyboard boundary', () => {
  it('uses the approved dialog surface and viewport-safe spacing', () => {
    render(
      <ModalFrame onClose={vi.fn()}>
        <button>Inside</button>
      </ModalFrame>,
    )
    expect(screen.getByRole('dialog')).toHaveClass('p-4')
    expect(screen.getByRole('button', { name: 'Inside' }).parentElement).toHaveClass(
      'dialog-surface',
    )
    expect(screen.getByRole('button', { name: 'Inside' }).parentElement).not.toHaveClass(
      'shadow-float',
    )
  })

  it.each([false, true])(
    'redirects Tab from a control disabled after focus: shift=%s',
    (shiftKey) => {
      const { rerender } = render(
        <ModalFrame onClose={vi.fn()}>
          <button>First</button>
          <button>Busy</button>
          <button>Last</button>
        </ModalFrame>,
      )
      const busy = screen.getByRole('button', { name: 'Busy' })
      busy.focus()
      rerender(
        <ModalFrame onClose={vi.fn()}>
          <button>First</button>
          <button disabled>Busy</button>
          <button>Last</button>
        </ModalFrame>,
      )
      fireEvent.keyDown(busy, { key: 'Tab', shiftKey })
      expect(screen.getByRole('button', { name: shiftKey ? 'Last' : 'First' })).toHaveFocus()
    },
  )

  it('exposes a nonempty accessible dialog name', () => {
    render(
      <ModalFrame onClose={vi.fn()}>
        <button>Inside</button>
      </ModalFrame>,
    )
    expect(screen.getByRole('dialog')).toHaveAccessibleName()
  })

  it('updates its accessible name when the supplied localized title changes', () => {
    const { rerender } = render(
      <ModalFrame title="账户" onClose={vi.fn()}>
        <button>Inside</button>
      </ModalFrame>,
    )
    expect(screen.getByRole('dialog', { name: '账户' })).toBeInTheDocument()
    rerender(
      <ModalFrame title="Konto" onClose={vi.fn()}>
        <button>Inside</button>
      </ModalFrame>,
    )
    expect(screen.getByRole('dialog', { name: 'Konto' })).toBeInTheDocument()
  })

  it('does not close the parent backdrop while a portal child is open', () => {
    const parentClose = vi.fn()
    const { rerender } = render(
      <ModalFrame onClose={parentClose}>
        <Modal title="Child" onClose={vi.fn()}>
          <button>Child control</button>
        </Modal>
      </ModalFrame>,
    )
    const backdrop = screen.getByRole('dialog', { name: 'Settings' })
    fireEvent.mouseDown(backdrop)
    expect(parentClose).not.toHaveBeenCalled()
    rerender(
      <ModalFrame onClose={parentClose}>
        <button>Parent control</button>
      </ModalFrame>,
    )
    fireEvent.mouseDown(screen.getByRole('dialog'))
    expect(parentClose).toHaveBeenCalledOnce()
  })

  it('focuses its first control and wraps Tab in both directions', () => {
    render(
      <ModalFrame onClose={vi.fn()}>
        <button>First</button>
        <button>Last</button>
      </ModalFrame>,
    )
    const first = screen.getByRole('button', { name: 'First' })
    const last = screen.getByRole('button', { name: 'Last' })
    expect(first).toHaveFocus()
    last.focus()
    fireEvent.keyDown(last, { key: 'Tab' })
    expect(first).toHaveFocus()
    fireEvent.keyDown(first, { key: 'Tab', shiftKey: true })
    expect(last).toHaveFocus()
  })

  it('closes on Escape and restores a connected trigger on unmount', () => {
    const trigger = document.createElement('button')
    document.body.append(trigger)
    trigger.focus()
    const onClose = vi.fn()
    const { unmount } = render(
      <ModalFrame onClose={onClose}>
        <button>Inside</button>
      </ModalFrame>,
    )
    fireEvent.keyDown(screen.getByRole('button', { name: 'Inside' }), { key: 'Escape' })
    expect(onClose).toHaveBeenCalledOnce()
    unmount()
    expect(trigger).toHaveFocus()
    trigger.remove()
  })

  it('leaves composition and child-consumed Escape untouched', () => {
    const onClose = vi.fn()
    render(
      <ModalFrame onClose={onClose}>
        <input onKeyDown={(event) => event.preventDefault()} aria-label="Editor" />
      </ModalFrame>,
    )
    const input = screen.getByRole('textbox')
    fireEvent.keyDown(input, { key: 'Escape', isComposing: true })
    fireEvent.keyDown(input, { key: 'Escape', keyCode: 229 })
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
  })

  it('does not take over while a separate visible modal is present', () => {
    const onClose = vi.fn()
    render(
      <ModalFrame onClose={onClose}>
        <button>Parent</button>
        <div role="dialog" aria-modal="true">
          <button>Child</button>
        </div>
      </ModalFrame>,
    )
    const child = screen.getByRole('button', { name: 'Child' })
    child.focus()
    fireEvent.keyDown(child, { key: 'Escape' })
    fireEvent.keyDown(child, { key: 'Tab' })
    expect(onClose).not.toHaveBeenCalled()
    expect(child).toHaveFocus()
  })

  it('keeps focus on the panel when all controls are unavailable', () => {
    render(
      <ModalFrame onClose={vi.fn()}>
        <button disabled>Disabled</button>
        <input type="hidden" />
        <button tabIndex={-1}>Excluded</button>
        <button hidden>Hidden</button>
      </ModalFrame>,
    )
    const panel = screen.getByRole('dialog').firstElementChild
    expect(panel).toHaveFocus()
    fireEvent.keyDown(panel!, { key: 'Tab' })
    expect(panel).toHaveFocus()
    fireEvent.keyDown(panel!, { key: 'Tab', shiftKey: true })
    expect(panel).toHaveFocus()
  })

  it('uses the latest close callback after rerendering', () => {
    const previousClose = vi.fn()
    const currentClose = vi.fn()
    const { rerender } = render(
      <ModalFrame onClose={previousClose}>
        <button>Inside</button>
      </ModalFrame>,
    )
    rerender(
      <ModalFrame onClose={currentClose}>
        <button>Inside</button>
      </ModalFrame>,
    )
    fireEvent.keyDown(screen.getByRole('button'), { key: 'Escape' })
    expect(previousClose).not.toHaveBeenCalled()
    expect(currentClose).toHaveBeenCalledOnce()
  })

  it('leaves portal child Escape to the child modal', () => {
    const parentClose = vi.fn()
    const childClose = vi.fn()
    render(
      <ModalFrame onClose={parentClose}>
        <button>Parent</button>
        <Modal title="Child dialog" onClose={childClose}>
          <input data-autofocus aria-label="Child input" />
        </Modal>
      </ModalFrame>,
    )
    const input = screen.getByRole('textbox')
    expect(input).toHaveFocus()
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(childClose).toHaveBeenCalledOnce()
    expect(parentClose).not.toHaveBeenCalled()
  })
})
