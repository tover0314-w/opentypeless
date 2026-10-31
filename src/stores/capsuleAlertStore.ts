import { create } from 'zustand'

export type CapsuleAlertKind =
  | 'shortcut_guide'
  | 'press_to_finish'
  | 'weak_network'
  | 'copy_last_transcript'
  | 'transcript_cancelled'

export interface CapsuleAlert {
  kind: CapsuleAlertKind
  /** Transcript text for `copy_last_transcript`. */
  text?: string
  id: number
}

// Typeless alert durations (REA: `kt`): SHORT 2s, MEDIUM 5s, LONG 10s, PERSISTENT until cleared.
export const ALERT_DURATION_MS: Record<CapsuleAlertKind, number | null> = {
  shortcut_guide: null,
  press_to_finish: 5000,
  weak_network: null,
  copy_last_transcript: 10000,
  transcript_cancelled: 5000,
}

interface CapsuleAlertState {
  alert: CapsuleAlert | null
  show: (kind: CapsuleAlertKind, text?: string) => void
  dismiss: (kind?: CapsuleAlertKind) => void
}

let nextId = 1

export const useCapsuleAlertStore = create<CapsuleAlertState>((set, get) => ({
  alert: null,
  show: (kind, text) => set({ alert: { kind, text, id: nextId++ } }),
  dismiss: (kind) => {
    const current = get().alert
    if (!current) return
    if (kind && current.kind !== kind) return
    set({ alert: null })
  },
}))
