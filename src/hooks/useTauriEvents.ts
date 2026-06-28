import { useEffect } from 'react'
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
    applyPersistedConfigPatch,
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
        setPipelineErrorKey(null)
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
    applyPersistedConfigPatch,
    t,
  ])
}
