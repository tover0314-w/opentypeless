import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastContainer } from '../Toast'
import { toast } from '../toast-service'

vi.mock('framer-motion', () => ({
  AnimatePresence: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  motion: {
    div: ({
      children,
      className,
      role,
      onMouseEnter,
      onMouseLeave,
      onFocus,
      onBlur,
    }: React.HTMLAttributes<HTMLDivElement>) => (
      <div
        className={className}
        role={role}
        onMouseEnter={onMouseEnter}
        onMouseLeave={onMouseLeave}
        onFocus={onFocus}
        onBlur={onBlur}
      >
        {children}
      </div>
    ),
  },
}))

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('ToastContainer lifecycle', () => {
  it.each(['pointer', 'keyboard'] as const)(
    'pauses expiry during %s reading and resumes only the remaining duration',
    (interaction) => {
      render(<ToastContainer />)
      act(() => toast('Reading'))
      act(() => vi.advanceTimersByTime(1000))
      const alert = screen.getByRole('alert')
      if (interaction === 'pointer') fireEvent.mouseEnter(alert)
      else fireEvent.focus(screen.getByText('Reading'))
      act(() => toast('Unrelated'))
      act(() => vi.advanceTimersByTime(5000))
      expect(screen.getByText('Reading')).toBeInTheDocument()
      expect(screen.queryByText('Unrelated')).toBeNull()
      if (interaction === 'pointer') fireEvent.mouseLeave(alert)
      else fireEvent.blur(screen.getByText('Reading'), { relatedTarget: document.body })
      act(() => vi.advanceTimersByTime(1999))
      expect(screen.getByText('Reading')).toBeInTheDocument()
      act(() => vi.advanceTimersByTime(1))
      expect(screen.queryByText('Reading')).toBeNull()
    },
  )

  it('keeps expiry paused while either pointer or keyboard focus remains inside', () => {
    render(<ToastContainer />)
    act(() => toast('Reading'))
    const alert = screen.getByRole('alert')
    const text = screen.getByText('Reading')
    fireEvent.mouseEnter(alert)
    fireEvent.focus(text)
    fireEvent.mouseLeave(alert)
    act(() => vi.advanceTimersByTime(5000))
    expect(text).toBeInTheDocument()
    fireEvent.blur(text, { relatedTarget: alert })
    act(() => vi.advanceTimersByTime(5000))
    expect(text).toBeInTheDocument()
    fireEvent.blur(alert, { relatedTarget: document.body })
    act(() => vi.advanceTimersByTime(3000))
    expect(screen.queryByText('Reading')).toBeNull()
  })

  it('bounds long notifications and their stack without discarding text', () => {
    const message = 'Long diagnostic '.repeat(200).trim()
    render(<ToastContainer />)
    act(() => {
      toast.error(message)
      toast('Another notification')
    })
    const alerts = screen.getAllByRole('alert')
    expect(alerts[0]).toHaveClass('shrink-0', 'max-w-[min(300px,calc(100vw-32px))]')
    expect(alerts[0].parentElement).toHaveClass('max-h-[calc(100dvh-32px)]', 'overflow-y-auto')
    expect(screen.getByText(message)).toHaveClass(
      'max-h-[min(240px,calc(100dvh-56px))]',
      'overflow-y-auto',
      'overscroll-contain',
    )
    expect(screen.getByText(message).textContent).toBe(message)
    expect(screen.getByText(message)).toHaveAttribute('tabindex', '0')
    expect(screen.getByText(message)).toHaveClass('focus-visible:outline-border-focus')
    screen.getByText(message).focus()
    expect(screen.getByText(message)).toHaveFocus()
  })

  it('wraps an unbroken diagnostic without truncating its content', () => {
    const message = `synthetic-diagnostic-${'x'.repeat(600)}`
    render(<ToastContainer />)
    act(() => toast.error(message))
    expect(screen.getByText(message)).toHaveClass('min-w-0', '[overflow-wrap:anywhere]')
    expect(screen.getByRole('alert')).toHaveTextContent(message)
  })

  it('expires each notification independently even when timestamps match', () => {
    vi.spyOn(Date, 'now').mockReturnValue(1000)
    render(<ToastContainer />)
    act(() => toast('First'))
    act(() => vi.advanceTimersByTime(1000))
    act(() => toast.error('Second'))
    expect(screen.getAllByRole('alert')).toHaveLength(2)
    act(() => vi.advanceTimersByTime(2000))
    expect(screen.queryByText('First')).toBeNull()
    expect(screen.getByText('Second')).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(1000))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('releases expiry timers and the message handler when unmounted', () => {
    const { unmount } = render(<ToastContainer />)
    act(() => toast('Pending'))
    expect(vi.getTimerCount()).toBe(1)
    unmount()
    expect(vi.getTimerCount()).toBe(0)
    act(() => toast('After unmount'))
    expect(vi.getTimerCount()).toBe(0)
  })

  it('does not restart a paused expiry after the container unmounts', () => {
    const { unmount } = render(<ToastContainer />)
    act(() => toast('Reading'))
    const alert = screen.getByRole('alert')
    fireEvent.mouseEnter(alert)
    fireEvent.focus(screen.getByText('Reading'))
    expect(vi.getTimerCount()).toBe(0)
    unmount()
    fireEvent.mouseLeave(alert)
    fireEvent.blur(alert, { relatedTarget: document.body })
    act(() => toast('After unmount'))
    expect(vi.getTimerCount()).toBe(0)
  })

  it('keeps the newer handler active when an older container unmounts', () => {
    const older = render(<ToastContainer />)
    act(() => toast('Older notification'))
    const newer = render(<ToastContainer />)
    act(() => toast('Newer notification'))
    expect(vi.getTimerCount()).toBe(2)
    older.unmount()
    expect(vi.getTimerCount()).toBe(1)
    expect(screen.queryByText('Older notification')).toBeNull()
    act(() => toast('Current notification'))
    expect(screen.getByText('Current notification')).toBeInTheDocument()
    expect(screen.getByText('Newer notification')).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(3000))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(vi.getTimerCount()).toBe(0)
    newer.unmount()
  })
})
