import { useEffect, useState } from 'react'
import { useAppStore } from '../../stores/appStore'

export type RecordingKind = 'dictation' | 'ask'

// Typeless only shows a countdown in the final minute before the session limit.
const COUNTDOWN_WINDOW_MS = 60_000

/** Milliseconds left in the take while inside its final minute, otherwise null. */
export function useCountdownRemainingMs(recordingKind: RecordingKind): number | null {
  const recordingDeadline = useAppStore((s) => s.recordingDeadline)
  const deadline = recordingDeadline?.recordingKind === recordingKind ? recordingDeadline : null
  const [now, setNow] = useState(Date.now())

  useEffect(() => {
    if (!deadline) return
    const interval = setInterval(() => setNow(Date.now()), 250)
    return () => clearInterval(interval)
  }, [deadline])

  if (!deadline) return null
  const remainingMs = Math.max(0, deadline.deadlineAtUnixMs - now)
  return remainingMs > COUNTDOWN_WINDOW_MS ? null : remainingMs
}
