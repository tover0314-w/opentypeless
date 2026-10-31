import { useEffect, useRef } from 'react'
import { useAppStore } from '../stores/appStore'
import { cueForTransition, playInteractionCue } from '../lib/interactionSounds'

/** Plays the start / end cues in the floating-bar window as recording begins and ends. */
export function useInteractionSounds() {
  const pipelineState = useAppStore((s) => s.pipelineState)
  const enabled = useAppStore((s) => s.config.interaction_sounds)
  const previous = useRef(pipelineState)

  useEffect(() => {
    const cue = cueForTransition(previous.current, pipelineState)
    previous.current = pipelineState
    if (cue && enabled) playInteractionCue(cue)
  }, [enabled, pipelineState])
}
