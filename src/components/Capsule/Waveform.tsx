import { useEffect, useRef } from 'react'
import { useReducedMotion } from 'framer-motion'
import { useAppStore } from '../../stores/appStore'

// Typeless waveform (REA: `Ki`): 2px bars with 2px gaps filling a 38px slot (10 bars), 2–18px
// tall, white, diffused from the centre.
const BAR_COUNT = 10
const MIN_HEIGHT = 2
const MAX_HEIGHT = 18
const UPDATE_MS = 50
const DECAY = 0.65

export function Waveform({ dim = false }: { dim?: boolean }) {
  const barsRef = useRef<(HTMLDivElement | null)[]>([])
  const levels = useRef<number[]>(Array(BAR_COUNT).fill(0))
  const reduced = useReducedMotion()

  useEffect(() => {
    const apply = () => {
      barsRef.current.forEach((bar, i) => {
        if (!bar) return
        bar.style.height = `${MIN_HEIGHT + (MAX_HEIGHT - MIN_HEIGHT) * levels.current[i]}px`
      })
    }

    if (reduced) {
      levels.current = levels.current.map(() => 0.35)
      apply()
      return
    }

    const center = (BAR_COUNT - 1) / 2
    const timer = window.setInterval(() => {
      const volume = Math.min(1, Math.max(0, useAppStore.getState().audioVolume))
      levels.current = levels.current.map((previous, i) => {
        const distance = Math.abs(i - center) / center
        const jitter = 0.75 + Math.random() * 0.5
        const target = volume * (1 - distance * 0.55) * jitter
        return target > previous ? target : previous * DECAY
      })
      apply()
    }, UPDATE_MS)
    return () => window.clearInterval(timer)
  }, [reduced])

  return (
    <div className="flex h-6 w-full items-center justify-center gap-[2px]" aria-hidden="true">
      {Array.from({ length: BAR_COUNT }).map((_, i) => (
        <div
          key={i}
          ref={(el) => {
            barsRef.current[i] = el
          }}
          className={`w-[2px] rounded-full transition-[height] duration-75 ${dim ? 'bg-white/50' : 'bg-white'}`}
          style={{ height: MIN_HEIGHT }}
        />
      ))}
    </div>
  )
}
