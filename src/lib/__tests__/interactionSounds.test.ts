import { describe, expect, it } from 'vitest'
import { cueForTransition } from '../interactionSounds'

describe('cueForTransition', () => {
  it('plays the start cue once the microphone is live', () => {
    expect(cueForTransition('preparing', 'recording')).toBe('start')
    expect(cueForTransition('idle', 'ask_recording')).toBe('start')
  })

  it('plays the end cue when recording finishes or is cancelled', () => {
    expect(cueForTransition('recording', 'transcribing')).toBe('end')
    expect(cueForTransition('ask_recording', 'ask_thinking')).toBe('end')
    expect(cueForTransition('recording', 'idle')).toBe('end')
  })

  it('stays silent for thinking, output and errors', () => {
    expect(cueForTransition('idle', 'preparing')).toBeNull()
    expect(cueForTransition('transcribing', 'polishing')).toBeNull()
    expect(cueForTransition('outputting', 'idle')).toBeNull()
    expect(cueForTransition('recording', 'recording')).toBeNull()
  })
})
