import { describe, expect, it } from 'vitest'
import { waveformLevel } from '../waveformLevel'

describe('waveform display level', () => {
  it('keeps silence and invalid audio levels at zero', () => {
    for (const rms of [0, -1, Number.NaN, Number.POSITIVE_INFINITY, 0.0001]) {
      expect(waveformLevel(rms)).toBe(0)
    }
  })

  it('makes ordinary speech visible without requiring full-scale audio', () => {
    expect(waveformLevel(0.02)).toBeGreaterThan(0.35)
    expect(waveformLevel(0.05)).toBeGreaterThan(waveformLevel(0.02))
    expect(waveformLevel(0.1)).toBeGreaterThan(0.7)
  })

  it('clamps loud audio to the existing maximum bar height', () => {
    expect(waveformLevel(1)).toBe(1)
    expect(waveformLevel(2)).toBe(1)
  })
})
