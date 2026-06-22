import { useRef, useState, useEffect, useCallback, type CSSProperties } from 'react'
import { Play, Pause } from 'lucide-react'

/** Format seconds as m:ss. */
function fmtTime(s: number): string {
  if (!isFinite(s) || s < 0) return '0:00'
  const m = Math.floor(s / 60)
  const sec = Math.floor(s % 60)
  return `${m}:${sec.toString().padStart(2, '0')}`
}

interface RecordingPlayerProps {
  src: string
  /** Known duration from the backend (ms). Authoritative when the raw MP3
   *  stream carries no duration header and the media element reports Infinity. */
  durationMs: number | null | undefined
}

/**
 * Compact, on-brand audio player for a saved recording.
 *
 * Raw MP3 streams (no Xing/LAME header) make WebKitGTK report `Infinity`
 * duration and break the native seekbar. We rely on the backend-stored
 * `durationMs` for the timeline, and force a one-time end-seek so the element
 * establishes a real, seekable duration before the user scrubs.
 */
export function RecordingPlayer({ src, durationMs }: RecordingPlayerProps) {
  const audioRef = useRef<HTMLAudioElement>(null)
  const recovered = useRef(false)
  const [playing, setPlaying] = useState(false)
  const [current, setCurrent] = useState(0)
  const [duration, setDuration] = useState(durationMs ? durationMs / 1000 : 0)

  useEffect(() => {
    if (durationMs) setDuration((d) => (d > 0 ? d : durationMs / 1000))
  }, [durationMs])

  const handleLoadedMetadata = useCallback(() => {
    const a = audioRef.current
    if (!a) return
    if (isFinite(a.duration) && a.duration > 0) {
      setDuration(a.duration)
      return
    }
    // No duration header: scan to the end once so WebKit computes a real,
    // seekable duration, then rewind to the start.
    if (recovered.current) return
    recovered.current = true
    const onDurationChange = () => {
      if (isFinite(a.duration) && a.duration > 0) {
        setDuration(a.duration)
        a.currentTime = 0
        a.removeEventListener('durationchange', onDurationChange)
      }
    }
    a.addEventListener('durationchange', onDurationChange)
    try {
      a.currentTime = 1e7
    } catch {
      // ignore — element not ready to seek yet
    }
  }, [])

  const togglePlay = useCallback(() => {
    const a = audioRef.current
    if (!a) return
    if (a.paused) a.play().catch(() => {})
    else a.pause()
  }, [])

  const handleSeek = useCallback((value: number) => {
    const a = audioRef.current
    if (!a) return
    a.currentTime = value
    setCurrent(value)
  }, [])

  const max = duration || 0
  const pct = max > 0 ? Math.min(100, (current / max) * 100) : 0

  return (
    <div className="flex items-center gap-2.5 mt-2 px-2.5 py-2 rounded-[10px] bg-bg-secondary border border-border">
      <audio
        ref={audioRef}
        src={src}
        preload="metadata"
        onLoadedMetadata={handleLoadedMetadata}
        onTimeUpdate={() => setCurrent(audioRef.current?.currentTime ?? 0)}
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => {
          setPlaying(false)
          setCurrent(0)
          if (audioRef.current) audioRef.current.currentTime = 0
        }}
      />
      <button
        type="button"
        onClick={togglePlay}
        aria-label={playing ? 'Pause' : 'Play'}
        className="grid place-items-center w-7 h-7 rounded-full bg-accent hover:bg-accent-hover text-white shrink-0 border-none cursor-pointer transition-colors"
      >
        {playing ? <Pause size={13} /> : <Play size={13} className="translate-x-[1px]" />}
      </button>
      <span className="tabular-nums text-[11px] text-text-tertiary w-8 text-right shrink-0">
        {fmtTime(current)}
      </span>
      <input
        type="range"
        min={0}
        max={max}
        step={0.05}
        value={Math.min(current, max)}
        onChange={(e) => handleSeek(Number(e.target.value))}
        disabled={max <= 0}
        aria-label="Seek"
        className="ot-seek flex-1"
        style={{ '--ot-seek-pct': `${pct}%` } as CSSProperties}
      />
      <span className="tabular-nums text-[11px] text-text-tertiary w-8 shrink-0">
        {fmtTime(max)}
      </span>
    </div>
  )
}
