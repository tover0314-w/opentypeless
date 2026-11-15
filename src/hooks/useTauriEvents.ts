import { useEffect, useRef } from 'react'
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
  VoiceMode,
} from '../stores/appStore'
import { getDictionary, getHistory, type WordAutoAddedPayload } from '../lib/tauri'
import {
  claimSelectedTextTip,
  useCapsuleAlertStore,
  type CapsuleAlertKind,
} from '../stores/capsuleAlertStore'
import { toast } from '../components/toast-service'
import { capsuleErrorKeyFromPayload, type PipelineErrorPayload } from '../lib/capsuleError'
import type { CapsuleRecordingStyle } from '../lib/capsuleView'
import { recordTranslationHint } from '../lib/translationHint'
import { invalidateOwnedCloudSession } from '../lib/cloud-session'
import { isRecordingDeadline } from '../lib/recordingDeadline'
import {
  managedCloudIncidentFromPipelineError,
  useCloudServiceStore,
} from '../stores/cloudServiceStore'

type Unlisten = () => void | Promise<void>

function parseShortcutGuidePressId(value: unknown): number | undefined | null {
  if (value === undefined || value === null) return undefined
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : null
}

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
  const shortcutGuideIdentity = useRef<{
    active: number | undefined
    released: number
  }>({ active: undefined, released: 0 })
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
    let historyRequestId = 0
    let dictionaryRequestId = 0
    let managedRunActive = false
    let dictationCounted = false
    const unlisteners: Unlisten[] = []

    const refreshHistory = () => {
      if (window.location.hash === '#capsule' || window.location.hash === '#ask') return
      const requestId = ++historyRequestId
      const previousHistory = useAppStore.getState().history
      getHistory(200, 0)
        .then((history) => {
          if (
            !cancelled &&
            requestId === historyRequestId &&
            useAppStore.getState().history === previousHistory
          ) {
            setHistory(history)
          }
        })
        .catch(() => {
          if (!cancelled && requestId === historyRequestId) {
            console.error('Failed to refresh history')
          }
        })
    }

    function addListener<T>(event: string, handler: (payload: T) => void) {
      listen<T>(event, (e) => {
        if (!cancelled) handler(e.payload)
      })
        .then((unlisten) => {
          if (cancelled) {
            safeUnlisten(unlisten)
          } else {
            unlisteners.push(unlisten)
          }
        })
        .catch(() => {
          if (!cancelled) console.error(`Failed to register listener for "${event}"`)
        })
    }

    addListener<number>('audio:volume', (volume) => {
      const { pipelineState: state, recordingDeadline } = useAppStore.getState()
      if (recordingDeadline) return
      if (state === 'recording' || state === 'ask_recording') setAudioVolume(volume)
    })
    addListener<unknown>('audio:volume-owned', (payload) => {
      if (!payload || typeof payload !== 'object') return
      const { sessionId, recordingKind, level } = payload as Record<string, unknown>
      const { pipelineState, recordingDeadline } = useAppStore.getState()
      const expectedKind =
        pipelineState === 'recording'
          ? 'dictation'
          : pipelineState === 'ask_recording'
            ? 'ask'
            : null
      if (
        !recordingDeadline ||
        expectedKind === null ||
        recordingKind !== expectedKind ||
        recordingKind !== recordingDeadline.recordingKind ||
        sessionId !== recordingDeadline.sessionId ||
        typeof level !== 'number' ||
        !Number.isFinite(level)
      )
        return
      setAudioVolume(level)
    })
    addListener<string>('audio:better-microphone', (name) => {
      const { pipelineState } = useAppStore.getState()
      if (name.trim() && (pipelineState === 'recording' || pipelineState === 'ask_recording')) {
        useCapsuleAlertStore.getState().show('better_microphone', name)
      }
    })
    addListener<string>('audio:input-device', (name) => {
      const { pipelineState } = useAppStore.getState()
      if (name.trim() && (pipelineState === 'recording' || pipelineState === 'ask_recording')) {
        useCapsuleAlertStore.getState().show('microphone_in_use', name)
      }
    })
    addListener<void>('history:changed', () => {
      refreshHistory()
    })
    addListener<void>('dictionary:changed', () => {
      const requestId = ++dictionaryRequestId
      const previousDictionary = useAppStore.getState().dictionary
      getDictionary()
        .then((dictionary) => {
          if (
            !cancelled &&
            requestId === dictionaryRequestId &&
            useAppStore.getState().dictionary === previousDictionary
          ) {
            useAppStore.getState().setDictionary(dictionary)
          }
        })
        .catch(() => {
          if (!cancelled && requestId === dictionaryRequestId) {
            console.error('Failed to refresh dictionary')
          }
        })
    })
    addListener<WordAutoAddedPayload>('dictionary:word_auto_added', ({ term, id }) => {
      useCapsuleAlertStore.getState().show('dictionary_auto_added', term, id)
    })
    addListener<{ historyId: number; sessionId?: number }>(
      'pipeline:dismissed',
      ({ historyId, sessionId }) => {
        useCapsuleAlertStore
          .getState()
          .attachCancelledTake(
            historyId,
            Number.isSafeInteger(sessionId) && (sessionId ?? 0) > 0
              ? `dictation:${sessionId}`
              : undefined,
          )
      },
    )
    addListener<{ historyId: number }>('pipeline:failed_take', ({ historyId }) => {
      // Only the error alert that is showing for this take may offer Retry.
      if (useAppStore.getState().pipelineError !== null) {
        useAppStore.getState().setFailedTakeId(historyId)
      }
    })
    addListener<boolean>('pipeline:retry_available', (available) =>
      useAppStore.getState().setRetryAvailable(available),
    )
    addListener<string>('stt:partial', setPartialTranscript)
    addListener<string>('stt:final', setFinalTranscript)
    addListener<string>('llm:chunk', appendPolishedChunk)
    addListener<PipelineState>('pipeline:state', (state) => {
      if (useAppStore.getState().pipelineState !== state) setAudioVolume(0)
      if (state === 'preparing' || state === 'idle') dictationCounted = false
      const startingAsk =
        state === 'ask_recording' && useAppStore.getState().pipelineState !== 'ask_recording'
      if (state === 'preparing' || startingAsk) {
        useCapsuleAlertStore.getState().dismiss()
      }
      setPipelineState(state)
      if (state !== 'recording') {
        useCapsuleAlertStore.getState().dismiss('translating_to')
        useAppStore.getState().setTranslationTargetMenuOpen(false)
      }
      if (startingAsk) useCapsuleAlertStore.getState().show('ask_anything')
      if (state !== 'ask_recording') useCapsuleAlertStore.getState().dismiss('ask_anything')
      if (state === 'preparing' || state === 'recording' || state === 'ask_recording') {
        const config = useAppStore.getState().config
        managedRunActive =
          config.stt_provider === 'cloud' ||
          (config.polish_enabled && config.llm_provider === 'cloud')
      }
      if (state === 'preparing' || state === 'idle') {
        setRecordingDeadline(null)
        useCapsuleAlertStore.getState().dismiss('translating_to')
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
        refreshHistory()
        if (managedRunActive && useAuthStore.getState().user) {
          // The managed request has already completed, so this read cannot add
          // stop-to-output latency and Neon is already awake from real usage.
          void useAuthStore.getState().refreshSubscription()
        }
        managedRunActive = false
      }
    })
    addListener<unknown>('recording:deadline', (deadline) => {
      if (!isRecordingDeadline(deadline)) return
      const { pipelineState, recordingDeadline: previous } = useAppStore.getState()
      if (
        (pipelineState === 'recording' && deadline.recordingKind !== 'dictation') ||
        (pipelineState === 'ask_recording' && deadline.recordingKind !== 'ask')
      )
        return
      if (
        previous?.recordingKind === deadline.recordingKind &&
        deadline.sessionId < previous.sessionId
      )
        return
      if (
        previous?.sessionId !== deadline.sessionId ||
        previous?.recordingKind !== deadline.recordingKind
      ) {
        setAudioVolume(0)
      }
      setRecordingDeadline(deadline)
    })
    addListener<RecordingDeadlineNotice>('recording:deadline-warning', (payload) => {
      // Typeless warns once in the floating bar when a take enters its last minute.
      if (payload.secondsRemaining === 60) {
        useCapsuleAlertStore.getState().show('last_minute')
        return
      }
      toast(
        t('recordingLimits.deadlineWarning', {
          seconds: payload.secondsRemaining ?? 10,
        }),
        'info',
      )
    })
    addListener<RecordingDeadlineNotice>('recording:deadline-reached', (payload) => {
      // Typeless "Transcription session ended" in the floating bar.
      useCapsuleAlertStore
        .getState()
        .show(
          'input_duration_exceeded',
          formatDeadlineDuration(payload.effectiveMaxSeconds, t),
          undefined,
          payload.effectiveMaxSeconds,
        )
    })
    addListener<VoiceMode | null>('pipeline:voice_mode', (mode) => {
      setActiveVoiceMode(mode)
      const { config, pipelineState, recordingStyle } = useAppStore.getState()
      if (
        window.location.hash === '#capsule' &&
        mode === 'dictate' &&
        pipelineState === 'recording' &&
        recordingStyle === 'pushToTalk' &&
        !dictationCounted
      ) {
        dictationCounted = true
        const translate = config.hotkeys.translateBindings?.[0] ?? config.hotkeys.translate
        if (recordTranslationHint(Boolean(translate) && config.translation.targets.length > 0)) {
          useCapsuleAlertStore.getState().show('switch_translation_mode')
        }
      }
      if (mode === 'translate') {
        useCapsuleAlertStore.getState().show('translating_to')
      } else {
        useCapsuleAlertStore.getState().dismiss('translating_to')
      }
    })
    addListener<CapsuleRecordingStyle>('capsule:recording_style', (style) =>
      useAppStore.getState().setRecordingStyle(style),
    )
    addListener<string>('pipeline:target_app', setTargetApp)
    addListener<{ text: string; result: InsertResult }>('history:paste-result', (payload) => {
      setLastInsertResult(payload.result)
      if (
        payload.text &&
        (payload.result.status === 'copiedFallback' || payload.result.status === 'failed')
      ) {
        useCapsuleAlertStore.getState().show('copy_last_transcript', payload.text)
      }
    })
    addListener<InsertResult>('pipeline:insert_result', (result) => {
      setLastInsertResult(result)
      // Typeless "Copy last transcript": the focused field did not take the text, so offer it.
      if (result.status === 'copiedFallback' || result.status === 'failed') {
        const { polishedText, finalTranscript } = useAppStore.getState()
        const text = polishedText || finalTranscript
        if (text) useCapsuleAlertStore.getState().show('copy_last_transcript', text)
      }
    })
    addListener<{ kind: CapsuleAlertKind; pressId?: unknown }>('capsule:alert', (payload) => {
      if (payload.kind === 'shortcut_guide') {
        const pressId = parseShortcutGuidePressId(payload.pressId)
        if (pressId === null) return
        if (
          pressId !== undefined &&
          (pressId <= shortcutGuideIdentity.current.released ||
            (shortcutGuideIdentity.current.active !== undefined &&
              pressId < shortcutGuideIdentity.current.active))
        ) {
          return
        }
        shortcutGuideIdentity.current.active = pressId
      }
      if (payload.kind === 'use_voice') {
        const { pipelineState, pipelineError, config } = useAppStore.getState()
        const binding = config.hotkeys.dictationBindings?.[0] ?? config.hotkeys.dictation
        if (pipelineState !== 'idle' || pipelineError || !binding) return
      }
      useCapsuleAlertStore.getState().show(payload.kind)
    })
    addListener<void>('capsule:selected_text', () => {
      const askBinding = useAppStore.getState().config.hotkeys.ask
      if (askBinding && claimSelectedTextTip()) {
        useCapsuleAlertStore.getState().show('selected_text_tip')
      }
    })
    addListener<{ kind: CapsuleAlertKind; pressId?: unknown }>(
      'capsule:alert_dismiss',
      (payload) => {
        if (payload.kind === 'shortcut_guide') {
          const pressId = parseShortcutGuidePressId(payload.pressId)
          if (pressId === null) return
          if (pressId !== undefined) {
            shortcutGuideIdentity.current.released = Math.max(
              shortcutGuideIdentity.current.released,
              pressId,
            )
          }
          if (pressId !== undefined && pressId !== shortcutGuideIdentity.current.active) return
          shortcutGuideIdentity.current.active = undefined
        }
        useCapsuleAlertStore.getState().dismiss(payload.kind)
      },
    )
    addListener<ContextProfileSummary>('pipeline:context', setLastContext)
    addListener<PipelineErrorPayload>('pipeline:error', (payload) => {
      const capsuleErrorKey = capsuleErrorKeyFromPayload(payload)
      // A failed take's Retry arrives after its error; never carry one over to a new error.
      useAppStore.getState().setFailedTakeId(null)
      useAppStore.getState().setPipelineErrorKey(capsuleErrorKey)
      const messageKey = `capsule.errors.${capsuleErrorKey}`
      setPipelineError(t(messageKey), messageKey)
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
    addListener<unknown>('auth:session-invalid-owned', (payload) => {
      void invalidateOwnedCloudSession(payload).catch(() => {
        if (!cancelled) console.error('Failed to invalidate cloud session')
      })
    })
    addListener<Partial<AppConfig>>('config:patch', (patch) => {
      applyPersistedConfigPatch(patch)
      if (patch.ui_language) {
        i18n.changeLanguage(patch.ui_language)
        localStorage.setItem('ui_language', patch.ui_language)
      }
    })

    addListener<string>('navigate', (hash) => {
      window.location.hash = hash
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
