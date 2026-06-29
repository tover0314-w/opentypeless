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
  /** Playback URL (localhost http stream), or undefined until the server port
   *  is known. */
  src: string | undefined
  /** Known duration from the backend (ms). Authoritative when the raw MP3
   *  stream carries no duration header and the media element reports Infinity. */
  durationMs: number | null | undefined
}

/**
 * Compact, on-brand audio player for a saved recording.
 *
 * Audio is streamed from a localhost http server (WebKitGTK on Linux can't play
 * asset://blob media). preload="none" means no media pipeline opens until the
 * user presses play. Raw MP3 streams carry no duration header, so we rely on the
 * backend-stored `durationMs` for the timeline.
 */
export function RecordingPlayer({ src, durationMs }: RecordingPlayerProps) {
  const audioRef = useRef<HTMLAudioElement>(null)
  const [playing, setPlaying] = useState(false)
  const [current, setCurrent] = useState(0)
  const [duration, setDuration] = useState(durationMs ? durationMs / 1000 : 0)
  const [errorCode, setErrorCode] = useState<number | null>(null)

  useEffect(() => {
    if (durationMs) setDuration((d) => (d > 0 ? d : durationMs / 1000))
  }, [durationMs])

  const handleLoadedMetadata = useCallback(() => {
    const a = audioRef.current
    if (!a) return
    // Adopt the element's duration only when it's a real, finite value. Raw MP3
    // streams report Infinity here; we keep the backend `durationMs` for the
    // timeline instead. We deliberately do NOT seek the element to coax a
    // duration out of it — that left the playhead stranded at the end of the
    // stream and stalled playback (element "playing" but no sound, stuck at 0).
    if (isFinite(a.duration) && a.duration > 0) {
      setDuration(a.duration)
    }
  }, [])

  const togglePlay = useCallback(() => {
    const a = audioRef.current
    if (!a || !src) return
    if (a.paused) a.play().catch((e) => console.error('Audio play failed:', e))
    else a.pause()
  }, [src])

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
        preload="none"
        onError={() => {
          const code = audioRef.current?.error?.code ?? -1
          setErrorCode(code)
          console.error('Audio element error', code, 'for', src)
        }}
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
      {errorCode !== null && (
        <span className="text-[10px] text-error shrink-0" title="Media error code">
          err {errorCode}
        </span>
      )}
    </div>
  )
}
