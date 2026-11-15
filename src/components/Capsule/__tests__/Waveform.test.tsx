import { act, cleanup, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Waveform } from '../Waveform'
import { useAppStore } from '../../../stores/appStore'

const motion = vi.hoisted(() => ({ reduced: false }))
vi.mock('framer-motion', () => ({
  useReducedMotion: () => motion.reduced,
}))

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  motion.reduced = false
  useAppStore.setState({ audioVolume: 0 })
})

describe('live waveform feedback', () => {
  it.each([false, true])('reflects speech and silence with reduced motion %s', (reduced) => {
    motion.reduced = reduced
    vi.useFakeTimers()
    useAppStore.setState({ audioVolume: 0 })
    const view = render(<Waveform />)
    const heights = () =>
      Array.from(view.container.querySelectorAll<HTMLDivElement>('.w-\\[2px\\]')).map((bar) =>
        Number.parseFloat(bar.style.height),
      )
    act(() => vi.advanceTimersByTime(100))
    expect(heights()).toHaveLength(10)
    expect(Math.max(...heights())).toBe(2)
    act(() => {
      useAppStore.setState({ audioVolume: 0.02 })
      vi.advanceTimersByTime(150)
    })
    expect(Math.max(...heights())).toBeGreaterThan(8)
    expect(Math.max(...heights())).toBeLessThanOrEqual(18)
    act(() => {
      useAppStore.setState({ audioVolume: 0 })
      vi.advanceTimersByTime(1000)
    })
    expect(Math.max(...heights())).toBeLessThan(2.1)
  })
})
