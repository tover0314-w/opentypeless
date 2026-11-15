import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SegmentedControl } from '../shared/SegmentedControl'

const preferences = vi.hoisted(() => ({ reduced: false }))

vi.mock('framer-motion', () => ({
  useReducedMotion: () => preferences.reduced,
  motion: {
    div: ({
      layoutId,
      transition,
      ...props
    }: {
      layoutId?: string
      transition?: unknown
      className?: string
    }) => (
      <div
        {...props}
        data-motion-layout={layoutId}
        data-motion-transition={JSON.stringify(transition)}
      />
    ),
  },
}))

afterEach(() => {
  cleanup()
  preferences.reduced = false
})

describe('segmented setting control', () => {
  it.each([false, true])('respects reduced motion preference %s', (reduced) => {
    preferences.reduced = reduced
    const { container } = render(
      <SegmentedControl
        value="hold"
        options={[{ value: 'hold', label: 'Hold to speak' }]}
        onChange={vi.fn()}
      />,
    )
    const animatedBackground = container.querySelector('[data-motion-layout]')
    if (reduced) {
      expect(animatedBackground).toBeNull()
      expect(screen.getByRole('button', { name: 'Hold to speak' }).firstElementChild).toHaveClass(
        'bg-bg-elevated',
      )
    } else {
      expect(animatedBackground).toHaveAttribute(
        'data-motion-transition',
        JSON.stringify({ type: 'spring', stiffness: 400, damping: 18 }),
      )
    }
  })

  it('keeps long translated labels in equal shrinkable segments', () => {
    const { container } = render(
      <SegmentedControl
        value="auto"
        options={[
          { value: 'auto', label: 'Appuyer pour parler' },
          { value: 'toggle', label: 'Basculer marche/arrêt' },
          { value: 'hold', label: 'Удерживать для разговора' },
        ]}
        onChange={vi.fn()}
      />,
    )
    expect(container.firstElementChild).toHaveClass('grid', 'w-full')
    for (const button of screen.getAllByRole('button')) {
      expect(button).toHaveClass('min-w-0', 'min-h-10', 'whitespace-normal')
      expect(button).not.toHaveClass('whitespace-nowrap')
    }
  })

  it('exposes the selected mode and does not submit a parent form', () => {
    const change = vi.fn()
    const submit = vi.fn((event) => event.preventDefault())
    const options = [
      { value: 'toggle', label: 'Press to speak' },
      { value: 'hold', label: 'Hold to speak' },
    ]
    const view = render(
      <form onSubmit={submit}>
        <SegmentedControl options={options} value="toggle" onChange={change} />
      </form>,
    )
    expect(screen.getByRole('button', { name: 'Press to speak' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.getByRole('button', { name: 'Hold to speak' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Hold to speak' }))
    expect(change).toHaveBeenCalledExactlyOnceWith('hold')
    expect(submit).not.toHaveBeenCalled()
    view.rerender(
      <form onSubmit={submit}>
        <SegmentedControl options={options} value="hold" onChange={change} />
      </form>,
    )
    expect(screen.getByRole('button', { name: 'Hold to speak' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })
})
