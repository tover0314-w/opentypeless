import { useCallback, useRef } from 'react'
import { invoke } from '@tauri-apps/api/core'
import { useAppStore } from '../stores/appStore'

export function useRecording() {
  const { pipelineState, resetRecording } = useAppStore()
  const pendingStops = useRef(new Set<number | null>())

  const startRecording = useCallback(async () => {
    resetRecording()
    await invoke('start_recording')
  }, [resetRecording])

  const stopRecording = useCallback(async () => {
    const deadline = useAppStore.getState().recordingDeadline
    const sessionId = deadline?.recordingKind === 'dictation' ? deadline.sessionId : null
    if (pendingStops.current.has(sessionId)) return
    pendingStops.current.add(sessionId)
    try {
      await invoke('stop_recording')
    } finally {
      pendingStops.current.delete(sessionId)
    }
  }, [])

  const isPreparing = pipelineState === 'preparing'
  const isRecording = pipelineState === 'recording'
  const isProcessing =
    pipelineState === 'preparing' ||
    pipelineState === 'transcribing' ||
    pipelineState === 'polishing'
  const isIdle = pipelineState === 'idle'

  return {
    startRecording,
    stopRecording,
    isPreparing,
    isRecording,
    isProcessing,
    isIdle,
    pipelineState,
  }
}
