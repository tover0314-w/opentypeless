import { create } from 'zustand'

export type CapsuleAlertKind =
  | 'shortcut_guide'
  | 'press_to_finish'
  | 'previous_pending'
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
  press_to_finish: 3000,
  previous_pending: 5000,
  weak_network: null,
  copy_last_transcript: 10000,
  transcript_cancelled: 5000,
}

/** Typeless shows the "press X to finish" nudge at most once per 48 h. */
export const PRESS_TO_FINISH_COOLDOWN_MS = 48 * 60 * 60 * 1000
const PRESS_TO_FINISH_SHOWN_AT = 'capsule.pressToFinishShownAt'

export function claimPressToFinishHint(now = Date.now()): boolean {
  try {
    const last = Number(localStorage.getItem(PRESS_TO_FINISH_SHOWN_AT))
    if (Number.isFinite(last) && last > 0 && now - last < PRESS_TO_FINISH_COOLDOWN_MS) return false
    localStorage.setItem(PRESS_TO_FINISH_SHOWN_AT, String(now))
    return true
  } catch {
    return false
  }
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
