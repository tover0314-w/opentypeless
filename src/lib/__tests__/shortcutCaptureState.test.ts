import { describe, expect, it } from 'vitest'
import { ShortcutCaptureState } from '../shortcutCaptureState'

describe('shortcut capture state', () => {
  it('previews the complete chord and submits it on the first release only', () => {
    const capture = new ShortcutCaptureState()
    expect(capture.press('Ctrl')).toEqual(['Ctrl'])
    expect(capture.press('Shift')).toEqual(['Ctrl', 'Shift'])
    expect(capture.press('K')).toEqual(['Ctrl', 'Shift', 'K'])
    expect(capture.release('Shift')).toEqual(['Ctrl', 'Shift', 'K'])
    expect(capture.release('K')).toBeNull()
    expect(capture.release('Ctrl')).toBeNull()
  })

  it('does not add duplicate keys from repeated keydown events', () => {
    const capture = new ShortcutCaptureState()
    capture.press('Ctrl')
    capture.press('K')
    expect(capture.press('K')).toEqual(['Ctrl', 'K'])
    expect(capture.release('K')).toEqual(['Ctrl', 'K'])
  })

  it('does not commit on an unrelated release', () => {
    const capture = new ShortcutCaptureState()
    capture.press('Ctrl')
    capture.press('K')
    expect(capture.release('J')).toBeNull()
    expect(capture.release('K')).toEqual(['Ctrl', 'K'])
  })

  it('retains a modifier-only chord for validation instead of silently discarding it', () => {
    const capture = new ShortcutCaptureState()
    capture.press('Ctrl')
    expect(capture.release('Ctrl')).toEqual(['Ctrl'])
  })

  it('waits for all keys to release before starting another chord', () => {
    const capture = new ShortcutCaptureState()
    capture.press('Ctrl')
    capture.press('K')
    expect(capture.release('K')).toEqual(['Ctrl', 'K'])
    expect(capture.press('J')).toEqual([])
    expect(capture.release('J')).toBeNull()
    expect(capture.release('Ctrl')).toBeNull()
    expect(capture.press('Alt')).toEqual(['Alt'])
    expect(capture.press('L')).toEqual(['Alt', 'L'])
    expect(capture.release('L')).toEqual(['Alt', 'L'])
  })

  it('cancels the pending chord without submitting it', () => {
    const capture = new ShortcutCaptureState()
    capture.press('Ctrl')
    capture.press('K')
    capture.cancel()
    expect(capture.release('K')).toBeNull()
    expect(capture.release('Ctrl')).toBeNull()
  })
})
