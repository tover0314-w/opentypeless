import { useEffect, useState } from 'react'
import { useAppStore } from '../../stores/appStore'

type RecordingKind = 'dictation' | 'ask'

// Typeless only shows a countdown in the final minute before the session limit.
const COUNTDOWN_WINDOW_MS = 60_000

export function DurationTimer({ recordingKind = 'dictation' }: { recordingKind?: RecordingKind }) {
  const recordingDeadline = useAppStore((s) => s.recordingDeadline)
  const [now, setNow] = useState(Date.now())

  useEffect(() => {
    const interval = setInterval(() => setNow(Date.now()), 250)
    return () => clearInterval(interval)
  }, [])

  const deadline = recordingDeadline?.recordingKind === recordingKind ? recordingDeadline : null
  if (!deadline) return null

  const remainingMs = Math.max(0, deadline.deadlineAtUnixMs - now)
  if (remainingMs > COUNTDOWN_WINDOW_MS) return null

  const seconds = Math.floor(remainingMs / 1000)
  const mm = String(Math.floor(seconds / 60)).padStart(2, '0')
  const ss = String(seconds % 60).padStart(2, '0')

  return (
    <span className="text-[12px] tabular-nums text-white">
      {mm}:{ss}
    </span>
  )
}
