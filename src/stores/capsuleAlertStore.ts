import { create } from 'zustand'

export type CapsuleAlertKind =
  | 'better_microphone'
  | 'microphone_in_use'
  | 'switch_translation_mode'
  | 'use_voice'
  | 'ask_anything'
  | 'translating_to'
  | 'retry_copied'
  | 'shortcut_guide'
  | 'press_to_finish'
  | 'previous_pending'
  | 'weak_network'
  | 'copy_last_transcript'
  | 'transcript_cancelled'
  | 'ready'
  | 'last_minute'
  | 'input_duration_exceeded'
  | 'selected_text_tip'
  | 'update_available'
  | 'dictionary_auto_added'
  | 'dictionary_word_removed'

export interface CapsuleAlert {
  kind: CapsuleAlertKind
  /** Transcript text for `copy_last_transcript`, the learned word for `dictionary_auto_added`,
   * or the formatted take limit for `input_duration_exceeded`. */
  text?: string
  durationSeconds?: number
  /** Dictionary row id for `dictionary_auto_added`, or the History row of a cancelled take. */
  entryId?: number
  sessionKey?: string
  id: number
}

// Typeless alert durations (REA: `kt`): SHORT 2s, MEDIUM 5s, LONG 10s, PERSISTENT until cleared.
export const ALERT_DURATION_MS: Record<CapsuleAlertKind, number | null> = {
  better_microphone: 10000,
  microphone_in_use: 2000,
  switch_translation_mode: 5000,
  use_voice: 3000,
  ask_anything: null,
  translating_to: null,
  retry_copied: 5000,
  shortcut_guide: null,
  press_to_finish: 3000,
  previous_pending: 5000,
  weak_network: null,
  copy_last_transcript: 999000,
  transcript_cancelled: 5000,
  ready: null,
  last_minute: 5000,
  input_duration_exceeded: 5000,
  selected_text_tip: 5000,
  update_available: null,
  dictionary_auto_added: 5000,
  dictionary_word_removed: 2000,
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

/** Typeless `SAFE_WORD`: long words keep their first and last seven characters. */
export function safeAlertWord(word: string): string {
  const chars = Array.from(word)
  if (chars.length <= 14) return word
  return `${chars.slice(0, 7).join('').trim()}...${chars.slice(-7).join('').trim()}`
}

export function claimPressToFinishHint(now = Date.now()): boolean {
  return claimOnce(PRESS_TO_FINISH_SHOWN_AT, PRESS_TO_FINISH_COOLDOWN_MS, now)
}

export function claimSelectedTextTip(now = Date.now()): boolean {
  return claimOnce(SELECTED_TEXT_TIP_SHOWN, 6 * 60 * 60 * 1000, now)
}

interface CapsuleAlertState {
  cancelledTakes: Record<string, number>
  alert: CapsuleAlert | null
  queue: CapsuleAlert[]
  show: (
    kind: CapsuleAlertKind,
    text?: string,
    entryId?: number,
    durationSeconds?: number,
    sessionKey?: string,
  ) => void
  /** The cancelled take was stored; let its "Transcript cancelled" alert offer Undo. */
  attachCancelledTake: (historyId: number, sessionKey?: string) => void
  dismiss: (kind?: CapsuleAlertKind) => void
  dismissById: (id: number) => void
}

let nextId = 1
const alertTimers = new Map<number, ReturnType<typeof setTimeout>>()

function alertPriority(kind: CapsuleAlertKind): number {
  if (kind === 'microphone_in_use') return 1
  if (kind === 'ask_anything') return 0
  if (kind === 'translating_to' || kind === 'weak_network') return 0
  if (kind === 'last_minute' || kind === 'press_to_finish') return 1
  return 2
}

function clearAlertTimer(alert: CapsuleAlert) {
  clearTimeout(alertTimers.get(alert.id))
  alertTimers.delete(alert.id)
}

export const useCapsuleAlertStore = create<CapsuleAlertState>((set, get) => ({
  cancelledTakes: {},
  alert: null,
  queue: [],
  show: (kind, text, entryId, durationSeconds, sessionKey) => {
    const queue = get().queue.filter((item) => {
      if (
        kind === 'transcript_cancelled' ||
        item.kind === kind ||
        item.kind === 'press_to_finish'
      ) {
        clearAlertTimer(item)
        return false
      }
      return true
    })
    const recoveredId =
      kind === 'transcript_cancelled' && sessionKey ? get().cancelledTakes[sessionKey] : undefined
    const alert = {
      kind,
      text,
      entryId: entryId ?? recoveredId,
      durationSeconds,
      sessionKey,
      id: nextId++,
    }
    queue.push(alert)
    queue.sort((left, right) => alertPriority(right.kind) - alertPriority(left.kind))
    const duration = ALERT_DURATION_MS[kind]
    if (duration !== null) {
      alertTimers.set(
        alert.id,
        setTimeout(() => {
          get().dismissById(alert.id)
        }, duration),
      )
    }
    set({ queue, alert: queue[0] ?? null })
  },
  attachCancelledTake: (historyId, sessionKey) => {
    if (!Number.isSafeInteger(historyId) || historyId <= 0) return
    if (sessionKey) {
      const entries = Object.entries(get().cancelledTakes).filter(([key]) => key !== sessionKey)
      set({ cancelledTakes: Object.fromEntries([...entries, [sessionKey, historyId]].slice(-16)) })
    }
    const current = get().alert
    if (current?.kind !== 'transcript_cancelled' || current.entryId !== undefined) return
    if (current.sessionKey !== sessionKey) return
    const alert = { ...current, entryId: historyId }
    set({
      alert,
      queue: get().queue.map((item) => (item.id === current.id ? alert : item)),
    })
  },
  dismiss: (kind) => {
    const queue = get().queue.filter((item) => {
      if (kind === undefined || item.kind === kind) {
        clearAlertTimer(item)
        return false
      }
      return true
    })
    set({ queue, alert: queue[0] ?? null })
  },
  dismissById: (id) => {
    const queue = get().queue.filter((item) => {
      if (item.id !== id) return true
      clearAlertTimer(item)
      return false
    })
    set({ queue, alert: queue[0] ?? null })
  },
}))
