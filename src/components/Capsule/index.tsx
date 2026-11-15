import { useRef, useCallback, useEffect, useState, useId } from 'react'
import { useTranslation } from 'react-i18next'
import { X, Check, AlertTriangle, CircleAlert } from 'lucide-react'
import { isMacPlatform, useAppStore } from '../../stores/appStore'
import { claimPressToFinishHint, useCapsuleAlertStore } from '../../stores/capsuleAlertStore'
import { useRecording } from '../../hooks/useRecording'
import { useCapsuleResize } from '../../hooks/useCapsuleResize'
import { useCapsuleClickThrough } from '../../hooks/useCapsuleClickThrough'
import {
  abortAskDictation,
  abortRecording,
  recoverUnfinishedTake,
  stopAskFlow,
} from '../../lib/tauri'
import { capsuleErrorKeyFromPayload, type CapsuleErrorKey } from '../../lib/capsuleError'
import { historyRetryErrorKey } from '../../lib/historyRetryError'
import {
  getCapsuleSize,
  getCapsuleTone,
  getCapsuleView,
  type CapsuleTone,
  type CapsuleView,
} from '../../lib/capsuleView'
import { CapsuleAlertBubble } from './CapsuleAlertBubble'
import { useVoiceHotkeys } from '../../lib/hotkeyLabels'
import { Keycaps } from '../shared/Keycaps'
import { Waveform } from './Waveform'
import { DurationTimer } from './DurationTimer'
import { ThinkingLabel } from './ThinkingLabel'
import { useRetryAndCopy } from './useRetryAndCopy'
import { useCountdownRemainingMs } from './useCountdown'
import { capsuleSystemSettingsUrl, openCapsuleSettings } from '../../lib/capsuleNavigation'
import { openUrl } from '@tauri-apps/plugin-opener'
import { useCapsuleUpdateNotice } from '../../hooks/useUpdateEvents'

const DRAG_THRESHOLD = 5

function currentRecordingSessionKey() {
  const deadline = useAppStore.getState().recordingDeadline
  return deadline ? `${deadline.recordingKind}:${deadline.sessionId}` : 'unknown'
}

export function Capsule() {
  const pipelineState = useAppStore((s) => s.pipelineState)
  const pipelineError = useAppStore((s) => s.pipelineError)
  const activeVoiceMode = useAppStore((s) => s.activeVoiceMode)
  const recordingStyle = useAppStore((s) => s.recordingStyle)
  const processingSessionKey = useAppStore((state) =>
    state.recordingDeadline
      ? `${state.recordingDeadline.recordingKind}:${state.recordingDeadline.sessionId}`
      : 'unknown',
  )
  const capsuleAutoHide = useAppStore((s) => s.config.capsule_auto_hide)
  const translationTargetMenuOpen = useAppStore((s) => s.translationTargetMenuOpen)
  const setTranslationTargetMenuOpen = useAppStore((s) => s.setTranslationTargetMenuOpen)
  const { stopRecording, isRecording } = useRecording()
  const [finishHover, setFinishHover] = useState(false)
  const [finishFocused, setFinishFocused] = useState(false)
  const finishTooltipId = useId()
  const pendingAskStops = useRef(new Set<number | null>())
  const pendingCancellations = useRef(new Set<string>())
  const latestKnownSessionKey = useRef(processingSessionKey)
  if (processingSessionKey !== 'unknown') {
    latestKnownSessionKey.current = processingSessionKey
  }

  const dragStart = useRef<{ x: number; y: number } | null>(null)
  const isDragging = useRef(false)
  const rootRef = useRef<HTMLDivElement | null>(null)

  useCapsuleResize()
  useCapsuleClickThrough(rootRef)
  useCapsuleUpdateNotice()

  const hasError = pipelineError !== null
  const view = getCapsuleView(pipelineState, hasError)
  const tone = getCapsuleTone(pipelineState, activeVoiceMode)
  const isAsk = pipelineState === 'ask_recording' || pipelineState === 'ask_thinking'
  // Ask always needs explicit finish/cancel controls, so it never uses the push-to-talk bar.
  const style = isAsk || tone === 'translate' ? 'handsFree' : recordingStyle
  const countdown = useCountdownRemainingMs(isAsk ? 'ask' : 'dictation') !== null
  const size = getCapsuleSize(view, { recordingStyle: style, countdown })

  const handlePointerDown = useCallback((e: React.PointerEvent) => {
    if (e.button !== 0) return
    dragStart.current = { x: e.clientX, y: e.clientY }
    isDragging.current = false
  }, [])

  const handlePointerMove = useCallback((e: React.PointerEvent) => {
    if (!dragStart.current || isDragging.current) return
    const dx = e.clientX - dragStart.current.x
    const dy = e.clientY - dragStart.current.y
    if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
      isDragging.current = true
      dragStart.current = null
      import('@tauri-apps/api/window')
        .then(({ getCurrentWindow }) => {
          getCurrentWindow()
            .startDragging()
            .catch(() => {})
        })
        .catch(() => {})
    }
  }, [])

  const finish = useCallback(
    (hintId?: number) => {
      if (
        useAppStore.getState().pipelineState !== pipelineState ||
        currentRecordingSessionKey() !== processingSessionKey
      ) {
        if (hintId !== undefined) useCapsuleAlertStore.getState().dismissById(hintId)
        return
      }
      const kind = pipelineState === 'ask_recording' ? 'ask' : 'dictation'
      const currentDeadline = useAppStore.getState().recordingDeadline
      const currentSessionId =
        currentDeadline?.recordingKind === kind ? currentDeadline.sessionId : null
      if (pendingCancellations.current.has(`${kind}:${currentSessionId}`)) {
        if (hintId !== undefined) useCapsuleAlertStore.getState().dismissById(hintId)
        return
      }
      if (pipelineState === 'ask_recording') {
        const deadline = useAppStore.getState().recordingDeadline
        const sessionId = deadline?.recordingKind === 'ask' ? deadline.sessionId : null
        if (pendingAskStops.current.has(sessionId)) return
        pendingAskStops.current.add(sessionId)
        void stopAskFlow()
          .catch(() => {
            if (hintId !== undefined) useCapsuleAlertStore.getState().dismissById(hintId)
            console.error('Failed to stop Ask flow')
          })
          .finally(() => pendingAskStops.current.delete(sessionId))
      } else if (isRecording) {
        void stopRecording().catch(() => {
          if (hintId !== undefined) useCapsuleAlertStore.getState().dismissById(hintId)
          console.error('Failed to stop recording')
        })
      }
    },
    [isRecording, pipelineState, processingSessionKey, stopRecording],
  )

  const handlePointerUp = useCallback(
    (e: React.PointerEvent) => {
      if (e.button !== 0) return
      if (isDragging.current) {
        isDragging.current = false
        dragStart.current = null
        return
      }
      dragStart.current = null
      finish()
    },
    [finish],
  )

  useEffect(() => {
    setFinishHover(false)
    setFinishFocused(false)
  }, [pipelineState, processingSessionKey])

  useEffect(() => {
    if (translationTargetMenuOpen && pipelineState !== 'recording') {
      setTranslationTargetMenuOpen(false)
    }
  }, [pipelineState, setTranslationTargetMenuOpen, translationTargetMenuOpen])

  const handsFree = view === 'recording' && style === 'handsFree'

  return (
    <div
      ref={rootRef}
      className="relative flex h-full w-full flex-col items-center justify-end pb-2"
      style={{ background: 'transparent' }}
      onContextMenu={(event) => event.preventDefault()}
    >
      <CapsuleAlertBubble />

      <div className="relative">
        {handsFree && (finishHover || finishFocused) && (
          <FinishTooltip id={finishTooltipId} tone={tone} />
        )}
        <div
          data-capsule-view={view}
          data-capsule-material="glass"
          data-capsule-style={view === 'recording' ? style : undefined}
          data-idle-hidden={view === 'idle' && capsuleAutoHide ? '' : undefined}
          className={`capsule-bar capsule-bar--${view} pointer-events-auto relative shrink-0`}
          style={size}
          onPointerDown={handlePointerDown}
          onPointerMove={handlePointerMove}
          onPointerUp={handlePointerUp}
        >
          {(view === 'recording' || view === 'processing') && tone !== 'dictate' && (
            <ModeGlow tone={tone} />
          )}
          {view === 'error' && <IdleDots />}
          {view === 'recording' && (
            <RecordingControls
              handsFree={handsFree}
              isAsk={isAsk}
              onFinishHover={setFinishHover}
              onFinishFocus={setFinishFocused}
              finishDescriptionId={finishHover || finishFocused ? finishTooltipId : undefined}
              onFinish={() => {
                setFinishHover(false)
                if (
                  useAppStore.getState().pipelineState !== pipelineState ||
                  currentRecordingSessionKey() !== processingSessionKey
                )
                  return
                let hintId: number | undefined
                if (tone === 'dictate' && claimPressToFinishHint()) {
                  useCapsuleAlertStore.getState().show('press_to_finish')
                  hintId = useCapsuleAlertStore
                    .getState()
                    .queue.find((alert) => alert.kind === 'press_to_finish')?.id
                }
                finish(hintId)
              }}
              onCancel={() => {
                if (currentRecordingSessionKey() !== processingSessionKey) return
                const currentState = useAppStore.getState().pipelineState
                const cancellable = isAsk
                  ? currentState === 'ask_recording' || currentState === 'ask_thinking'
                  : currentState === 'recording' ||
                    currentState === 'transcribing' ||
                    currentState === 'polishing' ||
                    currentState === 'outputting'
                if (!cancellable) return
                const kind = isAsk ? 'ask' : 'dictation'
                const deadline = useAppStore.getState().recordingDeadline
                const sessionId = deadline?.recordingKind === kind ? deadline.sessionId : null
                const cancellationKey = `${kind}:${sessionId}`
                if (pendingCancellations.current.has(cancellationKey)) return
                pendingCancellations.current.add(cancellationKey)
                const abort = isAsk ? abortAskDictation : abortRecording
                void abort()
                  .then(() => {
                    const currentKey = currentRecordingSessionKey()
                    if (
                      currentKey !== processingSessionKey &&
                      !(
                        currentKey === 'unknown' &&
                        useAppStore.getState().pipelineState === 'idle' &&
                        latestKnownSessionKey.current === processingSessionKey
                      )
                    ) {
                      return
                    }
                    const currentAlert = useCapsuleAlertStore.getState().alert
                    if (currentAlert?.kind === 'transcript_cancelled') return
                    useCapsuleAlertStore
                      .getState()
                      .show(
                        'transcript_cancelled',
                        undefined,
                        undefined,
                        undefined,
                        sessionId === null ? undefined : cancellationKey,
                      )
                  })
                  .catch(() => {
                    console.error('Failed to cancel recording')
                  })
                  .finally(() => pendingCancellations.current.delete(cancellationKey))
              }}
            />
          )}
          {view === 'processing' && <ThinkingLabel key={processingSessionKey} />}
        </div>
      </div>
      {view === 'error' && <ErrorAlert />}
    </div>
  )
}

/** Typeless error filler: ten 2px grey dots. */
function IdleDots() {
  return (
    <div className="flex h-full items-center justify-center gap-[2px]" aria-hidden="true">
      {Array.from({ length: 10 }).map((_, i) => (
        <span key={i} className="h-[2px] w-[2px] bg-[#808080]" />
      ))}
    </div>
  )
}

function RecordingControls({
  handsFree,
  isAsk,
  onFinish,
  onFinishHover,
  onFinishFocus,
  finishDescriptionId,
  onCancel,
}: {
  handsFree: boolean
  isAsk: boolean
  onFinish: () => void
  onFinishHover: (hovered: boolean) => void
  onFinishFocus: (focused: boolean) => void
  finishDescriptionId?: string
  onCancel: () => void
}) {
  const { t } = useTranslation()
  const stop = (event: React.PointerEvent) => event.stopPropagation()

  return (
    <div className="relative z-[1] flex h-6 w-full items-center justify-center">
      {handsFree && (
        <button
          type="button"
          aria-label={isAsk ? t('capsule.cancelAsk') : t('capsule.cancelRecording')}
          title={t('capsule.cancel')}
          onPointerDown={stop}
          onPointerUp={stop}
          onClick={(event) => {
            event.stopPropagation()
            onCancel()
          }}
          className="capsule-round-button capsule-round-button--cancel"
        >
          <X size={16} strokeWidth={2.25} />
        </button>
      )}
      <div className="mx-2 flex w-[38px] shrink-0 items-center justify-center">
        <Waveform />
      </div>
      <DurationTimer recordingKind={isAsk ? 'ask' : 'dictation'} />
      {handsFree && (
        <button
          type="button"
          aria-label={t('capsule.finish')}
          aria-describedby={finishDescriptionId}
          onPointerDown={stop}
          onPointerUp={stop}
          onPointerEnter={() => onFinishHover(true)}
          onPointerLeave={() => onFinishHover(false)}
          onFocus={() => onFinishFocus(true)}
          onBlur={() => onFinishFocus(false)}
          onClick={(event) => {
            event.stopPropagation()
            onFinish()
          }}
          className="capsule-round-button capsule-round-button--finish"
        >
          <Check size={16} strokeWidth={2.5} />
        </button>
      )}
    </div>
  )
}

/** Typeless "Finish [key]" tooltip above the ✓ button, teaching the faster keyboard finish. */
function FinishTooltip({ id, tone }: { id: string; tone: CapsuleTone }) {
  const { t } = useTranslation()
  const keys = useVoiceHotkeys()
  const shortcut = tone === 'ask' ? keys.ask : tone === 'translate' ? keys.translate : keys.dictate
  return (
    <div
      id={id}
      role="tooltip"
      className="capsule-tooltip pointer-events-none absolute bottom-[calc(100%+8px)] right-[-14px] z-10 flex items-center gap-1.5 whitespace-nowrap text-[12px] leading-[18px]"
    >
      {t('capsule.finish')}
      {shortcut && <Keycaps hotkey={shortcut} />}
    </div>
  )
}

/** Typeless offers Retry on transient recognition failures; config and permission errors are not
 * retried because the same take would fail again. */
const RETRYABLE_ERRORS = new Set<CapsuleErrorKey>([
  'stt_timeout',
  'stt_connection_failed',
  'stt_failed',
  'unknown',
])
/** Degraded but delivered results read as warnings, like Typeless `type: "warning"`. */
const WARNING_ERRORS = new Set<CapsuleErrorKey>([
  'output_fallback_clipboard',
  'output_wayland_unsupported',
  'output_wayland_clipboard_copy_only',
  'accessibility_required',
  'llm_failed',
])

const ALERT_PILL =
  'min-h-8 shrink-0 rounded-full border-none bg-[rgba(119,119,119,0.4)] px-3 text-[12px] leading-4 text-white cursor-pointer hover:bg-[rgba(119,119,119,0.5)] active:bg-[rgba(119,119,119,0.3)] disabled:cursor-default disabled:opacity-40'

/** Errors keep the dotted bar and explain themselves in a Typeless-style alert above it. */
function ErrorAlert() {
  const { t } = useTranslation()
  const pipelineError = useAppStore((s) => s.pipelineError)
  const errorKey = useAppStore((s) => s.pipelineErrorKey)
  const messageKey = useAppStore((s) => s.pipelineErrorMessageKey)
  const retryAvailable = useAppStore((s) => s.retryAvailable)
  const failedTakeId = useAppStore((s) => s.failedTakeId)
  const idle = useAppStore((s) => s.pipelineState === 'idle')
  const { retrying, retryAndCopy } = useRetryAndCopy()
  const setPipelineError = useAppStore((s) => s.setPipelineError)
  const resetRecording = useAppStore((s) => s.resetRecording)

  const message = messageKey ? t(messageKey) : pipelineError || t('capsule.errors.unknown')
  const retryable = errorKey !== null && RETRYABLE_ERRORS.has(errorKey)
  const recoverable = failedTakeId !== null && (errorKey === null || retryable)

  useEffect(() => {
    const timer = setTimeout(
      () => {
        const current = useAppStore.getState()
        if (
          current.pipelineError !== pipelineError ||
          current.pipelineErrorKey !== errorKey ||
          current.failedTakeId !== failedTakeId ||
          current.retryAvailable !== retryAvailable
        )
          return
        setPipelineError(null)
        // A new recording may have started during the error window; do not clobber it.
        if (useAppStore.getState().pipelineState === 'idle') resetRecording()
      },
      recoverable || retryAvailable ? 600000 : errorKey ? 10000 : 2500,
    )
    return () => clearTimeout(timer)
  }, [
    setPipelineError,
    resetRecording,
    pipelineError,
    errorKey,
    retryAvailable,
    recoverable,
    failedTakeId,
  ])

  // Typeless Retry re-runs the stored take in full and writes it into the focused app.
  const retryTake = () => {
    if (failedTakeId === null) return
    const app = useAppStore.getState()
    if (app.pipelineState !== 'idle' || app.failedTakeId !== failedTakeId) return
    app.resetRecording()
    app.setPipelineError(null)
    recoverUnfinishedTake(failedTakeId).catch((error) => {
      console.error('Failed to retry failed take')
      const state = useAppStore.getState()
      if (
        state.pipelineState !== 'idle' ||
        state.pipelineError !== null ||
        state.failedTakeId !== null
      )
        return
      state.setPipelineErrorKey(capsuleErrorKeyFromPayload(String(error)))
      const messageKey = historyRetryErrorKey(error)
      state.setPipelineError(t(messageKey), messageKey)
    })
  }

  const route =
    errorKey === 'authentication_required'
      ? '#/settings?pane=account'
      : errorKey === 'accessibility_required' ||
          errorKey === 'microphone_permission_required' ||
          errorKey === 'microphone_unavailable' ||
          errorKey === 'audio_failed'
        ? '#/settings'
        : '#/models'
  const systemSettingsUrl = isMacPlatform() ? capsuleSystemSettingsUrl(errorKey) : null
  const showSettings = errorKey !== null && !recoverable

  // Typeless titles: timeout → unstable, dropped connection → poor network, else generic. The
  // stored take's Retry arrives just after the error, so the title must not change when it does.
  const typelessCopy = recoverable || (retryable && errorKey !== 'unknown')
  const title = typelessCopy
    ? t(
        errorKey === 'stt_timeout'
          ? 'capsule.alerts.unstableNetworkTitle'
          : errorKey === 'stt_connection_failed'
            ? 'capsule.alerts.poorNetworkTitle'
            : 'capsule.alerts.somethingWrongTitle',
      )
    : message
  const description = recoverable
    ? t('capsule.alerts.retryToContinue')
    : typelessCopy
      ? message
      : null
  const warning = errorKey !== null && WARNING_ERRORS.has(errorKey)

  return (
    <div
      role="alert"
      data-capsule-error={errorKey ?? 'unknown'}
      className={`capsule-alert pointer-events-auto absolute bottom-[50px] left-1/2 flex w-max max-w-[360px] -translate-x-1/2 flex-col gap-2 ${description ? 'p-4' : 'p-2'}`}
    >
      <div className="flex items-start gap-2">
        <div className="mx-auto flex min-w-0 items-start justify-center gap-2">
          <span className="grid h-5 w-5 shrink-0 place-items-center">
            {warning ? (
              <AlertTriangle size={16} className="text-[#ff9e6c]" />
            ) : (
              <CircleAlert size={16} className="text-[#ea4d00]" />
            )}
          </span>
          <span className="min-w-0 whitespace-pre-wrap break-words text-[14px] font-medium leading-5 text-white">
            {title}
          </span>
        </div>
        <button
          type="button"
          aria-label={t('common.close')}
          onClick={() => setPipelineError(null)}
          className="grid h-5 w-5 shrink-0 place-items-center rounded border-none bg-transparent text-[#8f8f8f] cursor-pointer hover:bg-white/10"
        >
          <X size={16} />
        </button>
      </div>
      {description && (
        <p className="m-0 line-clamp-5 break-words text-center text-[12px] leading-4 text-[#c9c9c9]">
          {description}
        </p>
      )}
      {(recoverable || retryAvailable || showSettings) && (
        <div className="mt-2 flex flex-wrap items-center justify-center gap-2">
          {recoverable ? (
            <button type="button" disabled={!idle} onClick={retryTake} className={ALERT_PILL}>
              {t('capsule.alerts.retry')}
            </button>
          ) : (
            retryAvailable && (
              <button
                type="button"
                disabled={retrying}
                onClick={() => void retryAndCopy()}
                className={ALERT_PILL}
              >
                {t('capsule.alerts.retryAndCopy')}
              </button>
            )
          )}
          {showSettings && (
            <button
              type="button"
              onClick={() =>
                void (
                  systemSettingsUrl ? openUrl(systemSettingsUrl) : openCapsuleSettings(route)
                ).catch(() => console.error('Failed to open capsule settings'))
              }
              className={ALERT_PILL}
            >
              {systemSettingsUrl
                ? t('capsule.alerts.openSystemSettings')
                : errorKey === 'authentication_required'
                  ? t('nav.account')
                  : t('capsule.menu.settings')}
            </button>
          )}
        </div>
      )}
    </div>
  )
}

// Typeless mode glows (REA `_0x250ce3`): blue for translation, green for Ask, blurred into the
// top edge of the bar.
const GLOW: Record<Exclude<CapsuleTone, 'dictate'>, string> = {
  translate:
    'radial-gradient(50% 50% at 50% 50%, rgba(52, 107, 255, 1) 0%, rgba(52, 107, 255, 0.6) 100%)',
  ask: 'radial-gradient(50% 50% at 50% 50%, rgba(0, 163, 137, 1) 0%, rgba(0, 163, 124, 0.4) 100%)',
}

function ModeGlow({ tone }: { tone: Exclude<CapsuleTone, 'dictate'> }) {
  return (
    <div
      aria-hidden="true"
      data-capsule-glow={tone}
      className="pointer-events-none absolute inset-0 z-0 overflow-hidden rounded-full"
    >
      <div
        className="absolute left-1/2 top-[-25px] h-[32px] w-[116px] -translate-x-1/2 rounded-[50%]"
        style={{ background: GLOW[tone], filter: 'blur(7px)' }}
      />
    </div>
  )
}

export type { CapsuleView }
