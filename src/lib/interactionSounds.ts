// Interaction cues synthesized with Web Audio (no bundled sound files). Typeless plays a start
// cue once the microphone is live and an end cue on finish or cancel, at a modest volume.

export type InteractionCue = 'start' | 'end'

const CUE_GAIN = 0.18

const CUES: Record<InteractionCue, { notes: number[]; step: number; length: number }> = {
  // Rising two-note chime: "listening".
  start: { notes: [660, 990], step: 0.07, length: 0.16 },
  // Falling two-note chime: "done".
  end: { notes: [880, 587], step: 0.07, length: 0.16 },
}

let context: AudioContext | null = null

function audioContext(): AudioContext | null {
  if (typeof window === 'undefined' || typeof window.AudioContext === 'undefined') return null
  context ??= new window.AudioContext()
  return context
}

export function playInteractionCue(cue: InteractionCue) {
  const ctx = audioContext()
  if (!ctx) return
  if (ctx.state === 'suspended') void ctx.resume().catch(() => {})

  const { notes, step, length } = CUES[cue]
  const start = ctx.currentTime + 0.01
  notes.forEach((frequency, index) => {
    const at = start + index * step
    const oscillator = ctx.createOscillator()
    const gain = ctx.createGain()
    oscillator.type = 'sine'
    oscillator.frequency.setValueAtTime(frequency, at)
    gain.gain.setValueAtTime(0, at)
    gain.gain.linearRampToValueAtTime(CUE_GAIN, at + 0.012)
    gain.gain.exponentialRampToValueAtTime(0.0001, at + length)
    oscillator.connect(gain).connect(ctx.destination)
    oscillator.start(at)
    oscillator.stop(at + length + 0.02)
  })
}

const RECORDING_STATES = new Set(['recording', 'ask_recording'])

/** Which cue a pipeline state change should play, if any. */
export function cueForTransition(previous: string, next: string): InteractionCue | null {
  const wasRecording = RECORDING_STATES.has(previous)
  const isRecording = RECORDING_STATES.has(next)
  if (!wasRecording && isRecording) return 'start'
  if (wasRecording && !isRecording) return 'end'
  return null
}
