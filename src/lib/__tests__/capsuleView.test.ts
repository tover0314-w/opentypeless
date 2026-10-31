import { describe, expect, it } from 'vitest'
import {
  CAPSULE_SIZES,
  getCapsuleSize,
  getCapsuleTone,
  getCapsuleView,
  thinkingProgress,
} from '../capsuleView'

describe('capsule view model', () => {
  it('maps pipeline states onto Typeless-style bar views', () => {
    expect(getCapsuleView('idle', false, false)).toBe('idle')
    expect(getCapsuleView('idle', false, true)).toBe('hover')
    expect(getCapsuleView('preparing', false, false)).toBe('starting')
    expect(getCapsuleView('recording', false, false)).toBe('recording')
    expect(getCapsuleView('ask_recording', false, false)).toBe('recording')
    expect(getCapsuleView('transcribing', false, false)).toBe('processing')
    expect(getCapsuleView('polishing', false, false)).toBe('processing')
    expect(getCapsuleView('ask_thinking', false, false)).toBe('processing')
    expect(getCapsuleView('outputting', false, false)).toBe('done')
    expect(getCapsuleView('recording', true, false)).toBe('error')
  })

  it('distinguishes dictate, translate and ask tones', () => {
    expect(getCapsuleTone('recording', 'dictate')).toBe('dictate')
    expect(getCapsuleTone('recording', 'translate')).toBe('translate')
    expect(getCapsuleTone('ask_recording', null)).toBe('ask')
    expect(getCapsuleTone('ask_thinking', 'dictate')).toBe('ask')
  })

  it('uses the Typeless bar geometry', () => {
    expect(getCapsuleSize('idle')).toEqual({ width: 40, height: 6 })
    expect(getCapsuleSize('hover')).toEqual({ width: 70, height: 34 })
    expect(getCapsuleSize('recording')).toEqual({ width: 116, height: 34 })
    expect(getCapsuleSize('processing')).toEqual({ width: 92, height: 34 })
    expect(getCapsuleSize('processing', true)).toEqual(CAPSULE_SIZES.processingWide)
  })

  it('advances thinking progress quickly then creeps without finishing', () => {
    expect(thinkingProgress(0)).toBe(0)
    expect(thinkingProgress(0.5)).toBeCloseTo(40)
    expect(thinkingProgress(1)).toBe(80)
    expect(thinkingProgress(2.5)).toBeCloseTo(92.5)
    expect(thinkingProgress(7.5)).toBeCloseTo(98.5)
    expect(thinkingProgress(60)).toBe(99)
  })
})
