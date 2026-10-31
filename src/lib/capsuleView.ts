import type { PipelineState, VoiceMode } from '../stores/appStore'

export type CapsuleView =
  | 'idle'
  | 'hover'
  | 'starting'
  | 'recording'
  | 'processing'
  | 'done'
  | 'error'

export type CapsuleTone = 'dictate' | 'translate' | 'ask'

export interface CapsuleSize {
  width: number
  height: number
}

// Typeless floating bar geometry (REA: floating-bar chunk `Sa`): idle 40x6, recording 70x34,
// hands-free 116x34, thinking 92x34 (170 for web search). Hands-free is our only recording
// style because every recording can be finished or cancelled from the bar.
export const CAPSULE_SIZES = {
  idle: { width: 40, height: 6 },
  hover: { width: 70, height: 34 },
  starting: { width: 54, height: 12 },
  recording: { width: 116, height: 34 },
  processing: { width: 92, height: 34 },
  processingWide: { width: 170, height: 34 },
  done: { width: 92, height: 34 },
  error: { width: 220, height: 34 },
} satisfies Record<string, CapsuleSize>

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
      return 'processing'
    case 'outputting':
      return 'done'
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

export function getCapsuleSize(view: CapsuleView, wideProcessing = false): CapsuleSize {
  if (view === 'processing' && wideProcessing) return CAPSULE_SIZES.processingWide
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
