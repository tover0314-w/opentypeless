import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  claimPressToFinishHint,
  claimSelectedTextTip,
  PRESS_TO_FINISH_COOLDOWN_MS,
  useCapsuleAlertStore,
} from '../capsuleAlertStore'

describe('capsule hint throttling', () => {
  beforeEach(() => localStorage.clear())

  it('shows the press-to-finish nudge at most once per 48 hours', () => {
    expect(claimPressToFinishHint(1_000)).toBe(true)
    expect(claimPressToFinishHint(1_000 + PRESS_TO_FINISH_COOLDOWN_MS - 1)).toBe(false)
    expect(claimPressToFinishHint(1_000 + PRESS_TO_FINISH_COOLDOWN_MS)).toBe(true)
  })

  it('shows the selected-text tip at most once every six hours', () => {
    expect(claimSelectedTextTip(1_000)).toBe(true)
    expect(claimSelectedTextTip(1_000 + 6 * 60 * 60 * 1000 - 1)).toBe(false)
    expect(claimSelectedTextTip(1_000 + 6 * 60 * 60 * 1000)).toBe(true)
  })
})

describe('capsule alert queue', () => {
  it('removes a hidden alert by id without dismissing the visible alert', () => {
    const store = useCapsuleAlertStore.getState()
    store.show('weak_network')
    const hiddenId = useCapsuleAlertStore.getState().alert!.id
    store.show('ready')
    store.dismissById(hiddenId)
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('ready')
    store.dismiss('ready')
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('does not remove a replacement alert when given the previous id', () => {
    const store = useCapsuleAlertStore.getState()
    store.show('transcript_cancelled')
    const previousId = useCapsuleAlertStore.getState().alert!.id
    store.show('transcript_cancelled', undefined, 42)
    store.dismissById(previousId)
    expect(useCapsuleAlertStore.getState().alert).toMatchObject({
      kind: 'transcript_cancelled',
      entryId: 42,
    })
  })

  beforeEach(() => {
    vi.useFakeTimers()
    useCapsuleAlertStore.getState().dismiss()
    useCapsuleAlertStore.setState({ cancelledTakes: {} })
  })

  afterEach(() => {
    useCapsuleAlertStore.getState().dismiss()
    vi.useRealTimers()
  })

  it('restores a persistent hint after a higher-priority alert expires', () => {
    const store = useCapsuleAlertStore.getState()
    store.show('weak_network')
    store.show('last_minute')
    store.show('previous_pending')
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('previous_pending')
    store.dismiss('previous_pending')
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('last_minute')
    vi.advanceTimersByTime(5000)
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('weak_network')
  })

  it('keeps Ask visible until dismissed and restores it after a temporary alert', () => {
    const store = useCapsuleAlertStore.getState()
    store.show('ask_anything')
    store.show('last_minute')
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('last_minute')
    vi.advanceTimersByTime(5000)
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('ask_anything')
    vi.advanceTimersByTime(60000)
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('ask_anything')
    store.dismiss('ask_anything')
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('keeps the translation target notice for the whole recording', () => {
    useCapsuleAlertStore.getState().show('translating_to')
    vi.advanceTimersByTime(60000)
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('translating_to')
  })

  it('shows the microphone above the mode hint for two seconds', () => {
    useCapsuleAlertStore.getState().show('ask_anything')
    useCapsuleAlertStore.getState().show('microphone_in_use', 'USB Mic')
    expect(useCapsuleAlertStore.getState().alert?.text).toBe('USB Mic')
    vi.advanceTimersByTime(2000)
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('ask_anything')
  })

  it('keeps the selected-text hint for five seconds', () => {
    useCapsuleAlertStore.getState().show('selected_text_tip')
    vi.advanceTimersByTime(4999)
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('selected_text_tip')
    vi.advanceTimersByTime(1)
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('keeps the fallback transcript available for 999 seconds', () => {
    useCapsuleAlertStore.getState().show('copy_last_transcript', 'Draft')
    vi.advanceTimersByTime(998999)
    expect(useCapsuleAlertStore.getState().alert?.text).toBe('Draft')
    vi.advanceTimersByTime(1)
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('expires hidden alerts from their arrival rather than when they become visible', () => {
    const store = useCapsuleAlertStore.getState()
    store.show('ready')
    store.show('last_minute')
    vi.advanceTimersByTime(5000)
    store.dismiss('ready')
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('replaces the same kind and cancels its previous timer', () => {
    const store = useCapsuleAlertStore.getState()
    store.show('previous_pending', 'first')
    vi.advanceTimersByTime(3000)
    store.show('previous_pending', 'second')
    vi.advanceTimersByTime(2000)
    expect(useCapsuleAlertStore.getState().alert?.text).toBe('second')
    vi.advanceTimersByTime(3000)
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('removes volatile finish hints when another alert arrives', () => {
    const store = useCapsuleAlertStore.getState()
    store.show('press_to_finish')
    store.show('weak_network')
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('weak_network')
    store.dismiss('weak_network')
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('preserves FIFO order for equal priorities', () => {
    const store = useCapsuleAlertStore.getState()
    store.show('ready')
    store.show('previous_pending')
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('ready')
    store.dismiss('ready')
    expect(useCapsuleAlertStore.getState().alert?.kind).toBe('previous_pending')
  })

  it('clears all queued alerts and their timers without affecting a later alert', () => {
    const store = useCapsuleAlertStore.getState()
    store.show('weak_network')
    store.show('previous_pending')
    store.dismiss()
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
    vi.advanceTimersByTime(3000)
    store.show('previous_pending', 'new take')
    vi.advanceTimersByTime(2000)
    expect(useCapsuleAlertStore.getState().alert?.text).toBe('new take')
  })

  it('attaches a cancelled take without restarting its timer', () => {
    const store = useCapsuleAlertStore.getState()
    store.show('transcript_cancelled')
    vi.advanceTimersByTime(3000)
    store.attachCancelledTake(42)
    expect(useCapsuleAlertStore.getState().alert?.entryId).toBe(42)
    vi.advanceTimersByTime(2000)
    expect(useCapsuleAlertStore.getState().alert).toBeNull()
  })

  it('does not attach an older cancelled recording to the current notice', () => {
    const store = useCapsuleAlertStore.getState()
    store.show('transcript_cancelled', undefined, undefined, undefined, 'dictation:2')
    store.attachCancelledTake(41, 'dictation:1')
    expect(useCapsuleAlertStore.getState().alert?.entryId).toBeUndefined()
    store.attachCancelledTake(42, 'dictation:2')
    expect(useCapsuleAlertStore.getState().alert?.entryId).toBe(42)
  })

  it('retains an owned history result arriving before cancellation acknowledgement', () => {
    const store = useCapsuleAlertStore.getState()
    store.attachCancelledTake(42, 'dictation:2')
    store.show('transcript_cancelled', undefined, undefined, undefined, 'dictation:2')
    expect(useCapsuleAlertStore.getState().alert?.entryId).toBe(42)
  })

  it.each([0, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY])(
    'rejects an invalid cancelled history id %s',
    (historyId) => {
      const store = useCapsuleAlertStore.getState()
      store.show('transcript_cancelled', undefined, undefined, undefined, 'dictation:99')
      const previous = useCapsuleAlertStore.getState().cancelledTakes
      store.attachCancelledTake(historyId, 'dictation:99')
      expect(useCapsuleAlertStore.getState().alert?.entryId).toBeUndefined()
      expect(useCapsuleAlertStore.getState().cancelledTakes).toBe(previous)
    },
  )

  it('bounds early cancelled history results without removing local history', () => {
    const store = useCapsuleAlertStore.getState()
    for (let sessionId = 100; sessionId < 120; sessionId += 1) {
      store.attachCancelledTake(sessionId, `dictation:${sessionId}`)
    }
    const pending = useCapsuleAlertStore.getState().cancelledTakes
    expect(Object.keys(pending)).toHaveLength(16)
    expect(pending['dictation:100']).toBeUndefined()
    expect(pending['dictation:119']).toBe(119)
  })
})
