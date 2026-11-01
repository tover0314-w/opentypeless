import type { PipelineState, VoiceMode } from '../stores/appStore'

export type CapsuleView = 'idle' | 'hover' | 'starting' | 'recording' | 'processing' | 'error'

export type CapsuleTone = 'dictate' | 'translate' | 'ask'

/** Push-to-talk shows only the waveform; hands-free adds the ✕ and ✓ buttons (Typeless `Sa`). */
export type CapsuleRecordingStyle = 'pushToTalk' | 'handsFree'

export interface CapsuleSize {
  width: number
  height: number
}

// Typeless floating bar geometry (REA: floating-bar chunk `Sa`/`Ra`): idle 40x6, hover 70x34,
// push-to-talk 70x34, hands-free 116x34, starting 54x12, thinking 92x34 (170 for web search).
// A visible last-minute countdown widens the recording bar by 40px; errors use the 70px dotted bar.
export const CAPSULE_SIZES = {
  idle: { width: 40, height: 6 },
  hover: { width: 70, height: 34 },
  starting: { width: 54, height: 12 },
  recording: { width: 70, height: 34 },
  recordingHandsFree: { width: 116, height: 34 },
  processing: { width: 92, height: 34 },
  processingWide: { width: 170, height: 34 },
  error: { width: 70, height: 34 },
} satisfies Record<string, CapsuleSize>

const COUNTDOWN_EXTRA_WIDTH = 40
/** Room for the translation target chip, an OpenTypeless addition to the hands-free bar. */
const TARGET_CHIP_EXTRA_WIDTH = 34

export function getCapsuleView(
  pipelineState: PipelineState,
  hasError: boolean,
  hovered: boolean,
): CapsuleView {
  if (hasError) return 'error'
  switch (pipelineState) {
    case 'preparing':
      return 'starting'
    case 'recording':
    case 'ask_recording':
      return 'recording'
    case 'transcribing':
    case 'polishing':
    case 'ask_thinking':
    case 'outputting':
      return 'processing'
    default:
      return hovered ? 'hover' : 'idle'
  }
}

export function getCapsuleTone(
  pipelineState: PipelineState,
  voiceMode: VoiceMode | null,
): CapsuleTone {
  if (pipelineState === 'ask_recording' || pipelineState === 'ask_thinking') return 'ask'
  if (voiceMode === 'ask') return 'ask'
  if (voiceMode === 'translate') return 'translate'
  return 'dictate'
}

export function getCapsuleSize(
  view: CapsuleView,
  {
    recordingStyle = 'handsFree',
    countdown = false,
    targetChip = false,
    wideProcessing = false,
  }: {
    recordingStyle?: CapsuleRecordingStyle
    countdown?: boolean
    targetChip?: boolean
    wideProcessing?: boolean
  } = {},
): CapsuleSize {
  if (view === 'processing' && wideProcessing) return CAPSULE_SIZES.processingWide
  if (view === 'recording') {
    const base =
      recordingStyle === 'handsFree' ? CAPSULE_SIZES.recordingHandsFree : CAPSULE_SIZES.recording
    const extra =
      (countdown ? COUNTDOWN_EXTRA_WIDTH : 0) + (targetChip ? TARGET_CHIP_EXTRA_WIDTH : 0)
    return extra ? { ...base, width: base.width + extra } : base
  }
  return CAPSULE_SIZES[view]
}

// Typeless "Thinking" fake progress (REA: `Ke` keyframes): fast to 80% in 1s, then creeps
// toward 99% so long waits never look finished.
const PROGRESS_KEYFRAMES: ReadonlyArray<[seconds: number, percent: number]> = [
  [0, 0],
  [1, 80],
  [2, 90],
  [3, 95],
  [4, 97],
  [5, 98],
  [10, 99],
]

export function thinkingProgress(elapsedSeconds: number): number {
  if (elapsedSeconds <= 0) return 0
  for (let i = 0; i < PROGRESS_KEYFRAMES.length - 1; i += 1) {
    const [t0, p0] = PROGRESS_KEYFRAMES[i]
    const [t1, p1] = PROGRESS_KEYFRAMES[i + 1]
    if (elapsedSeconds >= t0 && elapsedSeconds < t1) {
      return p0 + ((elapsedSeconds - t0) / (t1 - t0)) * (p1 - p0)
    }
  }
  return PROGRESS_KEYFRAMES[PROGRESS_KEYFRAMES.length - 1][1]
}

/** Seconds after which a still-processing request shows the "taking longer than usual" hint. */
export const SLOW_PROCESSING_SECONDS = 5
