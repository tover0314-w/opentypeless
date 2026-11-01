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
    expect(getCapsuleView('idle', false)).toBe('idle')
    expect(getCapsuleView('preparing', false)).toBe('starting')
    expect(getCapsuleView('recording', false)).toBe('recording')
    expect(getCapsuleView('ask_recording', false)).toBe('recording')
    expect(getCapsuleView('transcribing', false)).toBe('processing')
    expect(getCapsuleView('polishing', false)).toBe('processing')
    expect(getCapsuleView('ask_thinking', false)).toBe('processing')
    expect(getCapsuleView('outputting', false)).toBe('processing')
    expect(getCapsuleView('recording', true)).toBe('error')
  })

  it('distinguishes dictate, translate and ask tones', () => {
    expect(getCapsuleTone('recording', 'dictate')).toBe('dictate')
    expect(getCapsuleTone('recording', 'translate')).toBe('translate')
    expect(getCapsuleTone('ask_recording', null)).toBe('ask')
    expect(getCapsuleTone('ask_thinking', 'dictate')).toBe('ask')
  })

  it('uses the Typeless bar geometry', () => {
    expect(getCapsuleSize('idle')).toEqual({ width: 40, height: 6 })
    expect(getCapsuleSize('starting')).toEqual({ width: 54, height: 12 })
    expect(getCapsuleSize('recording')).toEqual({ width: 116, height: 34 })
    expect(getCapsuleSize('recording', { recordingStyle: 'pushToTalk' })).toEqual({
      width: 70,
      height: 34,
    })
    expect(getCapsuleSize('processing')).toEqual({ width: 92, height: 34 })
    expect(getCapsuleSize('processing', { wideProcessing: true })).toEqual(
      CAPSULE_SIZES.processingWide,
    )
    expect(getCapsuleSize('error')).toEqual({ width: 70, height: 34 })
  })

  it('widens the recording bar for the last-minute countdown', () => {
    expect(getCapsuleSize('recording', { countdown: true }).width).toBe(156)
    expect(
      getCapsuleSize('recording', { recordingStyle: 'pushToTalk', countdown: true }).width,
    ).toBe(110)
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
