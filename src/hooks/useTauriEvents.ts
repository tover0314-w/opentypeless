import { useEffect } from 'react'
import { listen } from '@tauri-apps/api/event'
import { useAppStore } from '../stores/appStore'
import type { PipelineState } from '../stores/appStore'
import { getHistory } from '../lib/tauri'

export function useTauriEvents() {
  const {
    setAudioVolume,
    setPartialTranscript,
    setFinalTranscript,
    appendPolishedChunk,
    setPipelineState,
    setTargetApp,
    setPipelineError,
    setAccessibilityTrusted,
    setHistory,
    setSegmentCount,
  } = useAppStore()

  useEffect(() => {
    let cancelled = false
    const unlisteners: Array<() => void> = []

    function addListener<T>(event: string, handler: (payload: T) => void) {
      listen<T>(event, (e) => handler(e.payload))
        .then((unlisten) => {
          if (cancelled) {
            unlisten()
          } else {
            unlisteners.push(unlisten)
          }
        })
        .catch((err) => {
          console.error(`Failed to register listener for "${event}":`, err)
        })
    }

    addListener<number>('audio:volume', setAudioVolume)
    addListener<string>('stt:partial', setPartialTranscript)
    addListener<string>('stt:final', setFinalTranscript)
    addListener<string>('llm:chunk', appendPolishedChunk)
    addListener<PipelineState>('pipeline:state', (state) => {
      setPipelineState(state)
      if (state === 'recording') {
        // Clear any previous error when starting a new pipeline run
        setPipelineError(null)
      }
      if (state === 'idle') {
        // Don't clear pipelineError here — CapsuleError auto-resets after 2.5s.
        // Clearing here would swallow errors from failed start() calls that
        // transition Recording → Idle in rapid succession.
        getHistory(200, 0)
          .then(setHistory)
          .catch((err) => {
            console.error('Failed to refresh history:', err)
          })
      }
    })
    addListener<string>('pipeline:target_app', setTargetApp)
    addListener<string>('pipeline:error', (error) => {
      setPipelineError(error)
      if (error === 'ACCESSIBILITY_REQUIRED') {
        setAccessibilityTrusted(false)
      }
    })

    addListener<void>('tray:settings', () => {
      window.location.hash = '#/settings'
    })
    addListener<void>('tray:history', () => {
      window.location.hash = '#/history'
    })
    addListener<string>('navigate', (hash) => {
      window.location.hash = hash
    })
    addListener<void>('tray:about', () => {
      window.location.hash = '#/settings'
    })
    addListener<number>('pipeline:segment_count', (count) => {
      setSegmentCount(count)
    })

    return () => {
      cancelled = true
      unlisteners.forEach((unlisten) => unlisten())
    }
  }, [
    setAudioVolume,
    setPartialTranscript,
    setFinalTranscript,
    appendPolishedChunk,
    setPipelineState,
    setTargetApp,
    setPipelineError,
    setAccessibilityTrusted,
    setHistory,
    setSegmentCount,
  ])
}
