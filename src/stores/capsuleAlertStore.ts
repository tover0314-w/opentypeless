import { create } from 'zustand'

export type CapsuleAlertKind =
  | 'shortcut_guide'
  | 'press_to_finish'
  | 'previous_pending'
  | 'weak_network'
  | 'copy_last_transcript'
  | 'transcript_cancelled'
  | 'ready'
  | 'last_minute'
  | 'selected_text_tip'

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
  ready: null,
  last_minute: 5000,
  selected_text_tip: 3000,
}

/** Typeless shows the "press X to finish" nudge at most once per 48 h. */
export const PRESS_TO_FINISH_COOLDOWN_MS = 48 * 60 * 60 * 1000
const PRESS_TO_FINISH_SHOWN_AT = 'capsule.pressToFinishShownAt'
const SELECTED_TEXT_TIP_SHOWN = 'capsule.selectedTextTipShown'

function claimOnce(key: string, cooldownMs: number | null, now: number): boolean {
  try {
    const last = Number(localStorage.getItem(key))
    if (Number.isFinite(last) && last > 0 && (cooldownMs === null || now - last < cooldownMs)) {
      return false
    }
    localStorage.setItem(key, String(now))
    return true
  } catch {
    return false
  }
}

export function claimPressToFinishHint(now = Date.now()): boolean {
  return claimOnce(PRESS_TO_FINISH_SHOWN_AT, PRESS_TO_FINISH_COOLDOWN_MS, now)
}

/** Typeless teaches "press the Ask key to ask about a selection" once, the first time it applies. */
export function claimSelectedTextTip(now = Date.now()): boolean {
  return claimOnce(SELECTED_TEXT_TIP_SHOWN, null, now)
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
