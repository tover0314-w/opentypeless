import { useEffect } from 'react'
import { listen } from '@tauri-apps/api/event'
import { useTranslation } from 'react-i18next'
import i18n from '../i18n'
import { useAppStore } from '../stores/appStore'
import { useAuthStore } from '../stores/authStore'
import type {
  AppConfig,
  ContextProfileSummary,
  InsertResult,
  PipelineState,
  RecordingDeadlineSnapshot,
  VoiceMode,
} from '../stores/appStore'
import { getHistory } from '../lib/tauri'
import { toast } from '../components/toast-service'
import { capsuleErrorKeyFromPayload, type PipelineErrorPayload } from '../lib/capsuleError'
import { invalidateCloudSessionOnce } from '../lib/cloud-session'
import {
  managedCloudIncidentFromPipelineError,
  useCloudServiceStore,
} from '../stores/cloudServiceStore'

type Unlisten = () => void | Promise<void>

interface RecordingDeadlineNotice {
  sessionId: number
  recordingKind: 'dictation' | 'ask'
  secondsRemaining?: number
  effectiveMaxSeconds: number
  providerId: string
  explanationKey: string
}

function formatDeadlineDuration(seconds: number, t: ReturnType<typeof useTranslation>['t']) {
  if (seconds === 60) return t('recordingLimits.durationMinute', { count: 1 })
  if (seconds > 0 && seconds % 60 === 0) {
    return t('recordingLimits.durationMinutes', { count: seconds / 60 })
  }
  return t('recordingLimits.durationSeconds', { count: seconds })
}

function safeUnlisten(unlisten: Unlisten) {
  try {
    Promise.resolve(unlisten()).catch(() => {})
  } catch {
    // Dev HMR can leave Tauri listener handles stale.
  }
}

export function useTauriEvents() {
  const { t } = useTranslation()
  const {
    setAudioVolume,
    setPartialTranscript,
    setFinalTranscript,
    appendPolishedChunk,
    setPipelineState,
    setRecordingDeadline,
    setActiveVoiceMode,
    setTargetApp,
    setLastInsertResult,
    setLastContext,
    setPipelineError,
    setAccessibilityTrusted,
    setHistory,
    applyPersistedConfigPatch,
    setHotkeyRegistrationError,
  } = useAppStore()

  useEffect(() => {
    let cancelled = false
    let managedRunActive = false
    const unlisteners: Unlisten[] = []

    function addListener<T>(event: string, handler: (payload: T) => void) {
      listen<T>(event, (e) => handler(e.payload))
        .then((unlisten) => {
          if (cancelled) {
            safeUnlisten(unlisten)
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
      if (state === 'preparing' || state === 'recording' || state === 'ask_recording') {
        const config = useAppStore.getState().config
        managedRunActive =
          config.stt_provider === 'cloud' ||
          (config.polish_enabled && config.llm_provider === 'cloud')
      }
      if (state === 'preparing' || state === 'idle') {
        setRecordingDeadline(null)
      }
      if (state === 'preparing' || state === 'recording' || state === 'ask_recording') {
        // Clear any previous error when starting a new pipeline run
        setPipelineError(null)
        useCloudServiceStore.getState().clearIncident()
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
        if (managedRunActive && useAuthStore.getState().user) {
          // The managed request has already completed, so this read cannot add
          // stop-to-output latency and Neon is already awake from real usage.
          void useAuthStore.getState().refreshSubscription()
        }
        managedRunActive = false
      }
    })
    addListener<RecordingDeadlineSnapshot>('recording:deadline', setRecordingDeadline)
    addListener<RecordingDeadlineNotice>('recording:deadline-warning', (payload) => {
      toast(
        t('recordingLimits.deadlineWarning', {
          seconds: payload.secondsRemaining ?? 10,
        }),
        'info',
      )
    })
    addListener<RecordingDeadlineNotice>('recording:deadline-reached', (payload) => {
      toast(
        t('recordingLimits.deadlineReached', {
          duration: formatDeadlineDuration(payload.effectiveMaxSeconds, t),
          reason: t(payload.explanationKey),
        }),
        'info',
      )
    })
    addListener<VoiceMode | null>('pipeline:voice_mode', setActiveVoiceMode)
    addListener<string>('pipeline:target_app', setTargetApp)
    addListener<InsertResult>('pipeline:insert_result', setLastInsertResult)
    addListener<ContextProfileSummary>('pipeline:context', setLastContext)
    addListener<PipelineErrorPayload>('pipeline:error', (payload) => {
      const capsuleErrorKey = capsuleErrorKeyFromPayload(payload)
      setPipelineError(t(`capsule.errors.${capsuleErrorKey}`))
      if (capsuleErrorKey === 'accessibility_required') {
        setAccessibilityTrusted(false)
      }
      const incident = managedCloudIncidentFromPipelineError(payload, useAppStore.getState().config)
      if (incident) useCloudServiceStore.getState().setIncident(incident)
    })
    addListener<{ code: string; details?: string }>('pipeline:warning', (payload) => {
      const message = t(`errors.${payload.code}`, { details: payload.details ?? '' })
      toast(message, 'info')
    })
    addListener<string>('hotkey:registration-failed', (payload) => {
      setHotkeyRegistrationError(payload)
    })
    addListener<void>('hotkey:registration-recovered', () => {
      setHotkeyRegistrationError(null)
    })
    addListener<void>('auth:session-invalid', () => {
      void invalidateCloudSessionOnce().catch((error) => {
        console.error('Failed to invalidate cloud session:', error)
      })
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
      unlisteners.forEach(safeUnlisten)
    }
  }, [
    setAudioVolume,
    setPartialTranscript,
    setFinalTranscript,
    appendPolishedChunk,
    setPipelineState,
    setRecordingDeadline,
    setActiveVoiceMode,
    setTargetApp,
    setLastInsertResult,
    setLastContext,
    setPipelineError,
    setAccessibilityTrusted,
    setHistory,
    applyPersistedConfigPatch,
    setHotkeyRegistrationError,
    t,
  ])
}
