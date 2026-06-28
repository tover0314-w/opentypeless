import { useEffect, useRef } from 'react'
import { listen } from '@tauri-apps/api/event'
import { useTranslation } from 'react-i18next'
import i18n from '../i18n'
import { useAppStore } from '../stores/appStore'
import type { AppConfig, PipelineState } from '../stores/appStore'
import { getHistory, getHistoryCount } from '../lib/tauri'
import { toast } from '../components/Toast'
import { capsuleErrorKeyFromPayload, type PipelineErrorPayload } from '../lib/capsuleError'

export function useTauriEvents() {
  const { t } = useTranslation()
  const {
    setAudioVolume,
    setPartialTranscript,
    setFinalTranscript,
    appendPolishedChunk,
    setPipelineState,
    setTargetApp,
    setPipelineError,
    setPipelineErrorKey,
    setAccessibilityTrusted,
    setHistory,
    setHistoryCount,
    setJustCompleted,
    applyPersistedConfigPatch,
  } = useAppStore()

  // Holds the "transcription completed" confirmation visible for a couple of
  // seconds after success, independent of the backend's quick return to idle.
  const completeTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const COMPLETE_HINT_MS = 2500

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
        setPipelineErrorKey(null)
        // Cancel a lingering completion hint from the previous run.
        setJustCompleted(false)
        if (completeTimer.current) {
          clearTimeout(completeTimer.current)
          completeTimer.current = null
        }
      }
      if (state === 'outputting') {
        // Output means a transcript was produced and is being delivered — show a
        // brief "completed" confirmation that outlives the backend's near-instant
        // transition back to idle.
        setJustCompleted(true)
        if (completeTimer.current) clearTimeout(completeTimer.current)
        completeTimer.current = setTimeout(() => {
          setJustCompleted(false)
          completeTimer.current = null
        }, COMPLETE_HINT_MS)
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
        getHistoryCount()
          .then(setHistoryCount)
          .catch((err) => {
            console.error('Failed to refresh history count:', err)
          })
      }
    })
    addListener<string>('pipeline:target_app', setTargetApp)
    addListener<PipelineErrorPayload>('pipeline:error', (payload) => {
      const capsuleErrorKey = capsuleErrorKeyFromPayload(payload)
      setPipelineError(t(`capsule.errors.${capsuleErrorKey}`))
      setPipelineErrorKey(capsuleErrorKey)
      if (capsuleErrorKey === 'accessibility_required') {
        setAccessibilityTrusted(false)
      }
    })
    addListener<{ code: string; details?: string }>('pipeline:warning', (payload) => {
      const message = t(`errors.${payload.code}`, { details: payload.details ?? '' })
      toast(message, 'info')
    })
    addListener<Partial<AppConfig>>('config:patch', (patch) => {
      applyPersistedConfigPatch(patch)
      if (patch.ui_language) {
        i18n.changeLanguage(patch.ui_language)
        localStorage.setItem('ui_language', patch.ui_language)
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

    return () => {
      cancelled = true
      unlisteners.forEach((unlisten) => unlisten())
      if (completeTimer.current) {
        clearTimeout(completeTimer.current)
        completeTimer.current = null
      }
    }
  }, [
    setAudioVolume,
    setPartialTranscript,
    setFinalTranscript,
    appendPolishedChunk,
    setPipelineState,
    setTargetApp,
    setPipelineError,
    setPipelineErrorKey,
    setAccessibilityTrusted,
    setHistory,
    setHistoryCount,
    setJustCompleted,
    applyPersistedConfigPatch,
    t,
  ])
}
