import { describe, expect, it } from 'vitest'
import { useAppStore } from '../../stores/appStore'
import { mergeHotkeyDraft } from '../mergeHotkeyDraft'

describe('mergeHotkeyDraft', () => {
  it('keeps a locally changed binding list together with its primary binding', () => {
    const baseline = useAppStore.getInitialState().config.hotkeys
    const extra = { primary: 'K', modifiers: ['Ctrl', 'Shift'] }
    const local = { ...baseline, dictationBindings: [...baseline.dictationBindings, extra] }
    const remote = { primary: 'J', modifiers: ['Ctrl', 'Shift'] }
    const persisted = {
      ...baseline,
      dictation: remote,
      dictationBindings: [remote],
      ask: remote,
      askBindings: [remote],
    }
    const merged = mergeHotkeyDraft(local, baseline, persisted)
    expect(merged.dictation).toEqual(local.dictation)
    expect(merged.dictationBindings).toEqual(local.dictationBindings)
    expect(merged.ask).toEqual(remote)
    expect(merged.askBindings).toEqual([remote])
    expect(baseline.dictationBindings).not.toContain(extra)
  })

  it('preserves a local mode and accepts untouched secondary bindings', () => {
    const baseline = useAppStore.getInitialState().config.hotkeys
    const local = { ...baseline, dictationMode: 'toggle' as const, pasteLast: null }
    const remote = { primary: 'J', modifiers: ['Ctrl', 'Shift'] }
    const persisted = { ...baseline, openApp: remote, pasteLast: remote }
    const merged = mergeHotkeyDraft(local, baseline, persisted)
    expect(merged.dictationMode).toBe('toggle')
    expect(merged.pasteLast).toBeNull()
    expect(merged.openApp).toEqual(remote)
  })
})
