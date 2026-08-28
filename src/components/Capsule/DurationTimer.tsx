import { useEffect, useRef, useState } from 'react'
import { useAppStore } from '../../stores/appStore'

type RecordingKind = 'dictation' | 'ask'

export function DurationTimer({ recordingKind = 'dictation' }: { recordingKind?: RecordingKind }) {
  const recordingDeadline = useAppStore((s) => s.recordingDeadline)
  const fallbackStartedAtRef = useRef(Date.now())
  const [now, setNow] = useState(Date.now())

  useEffect(() => {
    const interval = setInterval(() => setNow(Date.now()), 250)
    return () => clearInterval(interval)
  }, [])

  const activeDeadline =
    recordingDeadline?.recordingKind === recordingKind ? recordingDeadline : null
  const startedAt = activeDeadline?.startedAtUnixMs ?? fallbackStartedAtRef.current
  const displayNow = activeDeadline ? Math.min(now, activeDeadline.deadlineAtUnixMs) : now
  const seconds = Math.max(0, Math.floor((displayNow - startedAt) / 1000))

  const mm = String(Math.floor(seconds / 60)).padStart(2, '0')
  const ss = String(seconds % 60).padStart(2, '0')

  return (
    <span className="text-[11px] font-mono text-white/80 tabular-nums">
      {mm}:{ss}
    </span>
  )
}
