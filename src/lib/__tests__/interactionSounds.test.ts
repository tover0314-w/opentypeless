import { afterEach, describe, expect, it, vi } from 'vitest'
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

describe('Web Audio cue synthesis', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.resetModules()
  })

  function virtualContext(state = 'running') {
    const oscillators = Array.from({ length: 4 }, () => ({
      type: '',
      frequency: { setValueAtTime: vi.fn() },
      connect: vi.fn(),
      start: vi.fn(),
      stop: vi.fn(),
    }))
    const gains = Array.from({ length: 4 }, () => ({
      gain: {
        setValueAtTime: vi.fn(),
        linearRampToValueAtTime: vi.fn(),
        exponentialRampToValueAtTime: vi.fn(),
      },
      connect: vi.fn(),
    }))
    const context = {
      state,
      currentTime: 10,
      destination: {},
      resume: vi.fn().mockResolvedValue(undefined),
      createOscillator: vi.fn(),
      createGain: vi.fn(),
    }
    for (const oscillator of oscillators) {
      context.createOscillator.mockReturnValueOnce(oscillator)
    }
    for (const gain of gains) {
      context.createGain.mockReturnValueOnce(gain)
      gain.connect.mockReturnValue(context.destination)
    }
    oscillators.forEach((oscillator, index) => oscillator.connect.mockReturnValue(gains[index]))
    const constructor = vi.fn(function () {
      return context
    })
    vi.stubGlobal('AudioContext', constructor)
    return { context, constructor, oscillators, gains }
  }

  it('does nothing when the platform has no Web Audio context', async () => {
    vi.resetModules()
    vi.stubGlobal('AudioContext', undefined)
    const { playInteractionCue } = await import('../interactionSounds')
    expect(() => playInteractionCue('start')).not.toThrow()
  })

  it.each(['constructor', 'resume', 'oscillator'] as const)(
    'characterizes a synchronous %s failure escaping optional sound playback',
    async (stage) => {
      vi.resetModules()
      const { context, constructor } = virtualContext(stage === 'resume' ? 'suspended' : 'running')
      const failure = new Error(`Synthetic ${stage} failure`)
      const throwFailure = function () {
        throw failure
      }
      if (stage === 'constructor') constructor.mockImplementationOnce(throwFailure)
      if (stage === 'resume') context.resume.mockImplementationOnce(throwFailure)
      if (stage === 'oscillator') {
        context.createOscillator.mockReset().mockImplementation(throwFailure)
      }
      const { playInteractionCue } = await import('../interactionSounds')
      expect(() => playInteractionCue('start')).toThrow(failure)
    },
  )

  it('reuses one context and schedules bounded sine cues with a low gain', async () => {
    vi.resetModules()
    const { context, constructor, oscillators, gains } = virtualContext()
    const { playInteractionCue } = await import('../interactionSounds')
    playInteractionCue('start')
    playInteractionCue('end')
    expect(constructor).toHaveBeenCalledOnce()
    expect(context.resume).not.toHaveBeenCalled()
    expect(context.createOscillator).toHaveBeenCalledTimes(4)
    for (const [index, frequency] of [660, 990, 880, 587].entries()) {
      const oscillator = oscillators[index]
      const gain = gains[index]
      const start = 10.01 + (index % 2) * 0.07
      expect(oscillator.type).toBe('sine')
      expect(oscillator.frequency.setValueAtTime).toHaveBeenCalledWith(frequency, start)
      expect(oscillator.connect).toHaveBeenCalledWith(gain)
      expect(gain.connect).toHaveBeenCalledWith(context.destination)
      expect(gain.gain.setValueAtTime).toHaveBeenCalledWith(0, start)
      expect(gain.gain.linearRampToValueAtTime).toHaveBeenCalledWith(0.18, start + 0.012)
      expect(gain.gain.exponentialRampToValueAtTime).toHaveBeenCalledWith(0.0001, start + 0.16)
      expect(oscillator.start).toHaveBeenCalledWith(start)
      expect(oscillator.stop).toHaveBeenCalledWith(start + 0.16 + 0.02)
    }
  })

  it('contains asynchronous resume rejection without claiming audible playback', async () => {
    vi.resetModules()
    const { context } = virtualContext('suspended')
    context.resume.mockRejectedValueOnce(new Error('Synthetic audio resume rejection'))
    const { playInteractionCue } = await import('../interactionSounds')
    expect(() => playInteractionCue('start')).not.toThrow()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(context.resume).toHaveBeenCalledOnce()
    expect(context.createOscillator).toHaveBeenCalledTimes(2)
  })

  it('recreates a closed context without replacing a running context', async () => {
    vi.resetModules()
    const first = virtualContext()
    const { playInteractionCue } = await import('../interactionSounds')
    playInteractionCue('start')
    first.context.state = 'closed'
    first.context.createOscillator.mockReset().mockImplementation(() => {
      throw new Error('A closed audio context cannot create nodes')
    })
    const replacement = virtualContext()

    expect(() => playInteractionCue('end')).not.toThrow()
    expect(first.constructor).toHaveBeenCalledOnce()
    expect(replacement.constructor).toHaveBeenCalledOnce()
    expect(replacement.context.createOscillator).toHaveBeenCalledTimes(2)

    playInteractionCue('start')
    expect(replacement.constructor).toHaveBeenCalledOnce()
    expect(replacement.context.createOscillator).toHaveBeenCalledTimes(4)
  })
})
