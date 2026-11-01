import { useCountdownRemainingMs, type RecordingKind } from './useCountdown'

export function DurationTimer({ recordingKind = 'dictation' }: { recordingKind?: RecordingKind }) {
  const remainingMs = useCountdownRemainingMs(recordingKind)
  if (remainingMs === null) return null

  const seconds = Math.floor(remainingMs / 1000)
  const mm = String(Math.floor(seconds / 60)).padStart(2, '0')
  const ss = String(seconds % 60).padStart(2, '0')

  return (
    <span className="mr-2 w-8 shrink-0 text-[12px] font-normal tabular-nums text-white">
      {mm}:{ss}
    </span>
  )
}
