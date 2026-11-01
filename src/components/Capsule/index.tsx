import { useRef, useCallback, useEffect, useState } from 'react'
import { useReducedMotion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { X, Check, AlertTriangle } from 'lucide-react'
import { useAppStore } from '../../stores/appStore'
import { claimPressToFinishHint, useCapsuleAlertStore } from '../../stores/capsuleAlertStore'
import { useRecording } from '../../hooks/useRecording'
import { useCapsuleResize } from '../../hooks/useCapsuleResize'
import { useCapsuleClickThrough } from '../../hooks/useCapsuleClickThrough'
import { abortAskDictation, abortRecording, stopAskFlow } from '../../lib/tauri'
import {
  getCapsuleSize,
  getCapsuleTone,
  getCapsuleView,
  thinkingProgress,
  SLOW_PROCESSING_SECONDS,
  type CapsuleTone,
  type CapsuleView,
} from '../../lib/capsuleView'
import { CapsuleContextMenu } from './CapsuleContextMenu'
import { CapsuleAlertBubble } from './CapsuleAlertBubble'
import { useVoiceHotkeys } from '../../lib/hotkeyLabels'
import { Keycaps } from '../shared/Keycaps'
import { TranslateTargetChip, TranslateTargetMenu } from './TranslateTargetChip'
import { Waveform } from './Waveform'
import { DurationTimer } from './DurationTimer'
import { useCountdownRemainingMs } from './useCountdown'

const DRAG_THRESHOLD = 5
const HOVER_DELAY_MS = 250

export function Capsule() {
  const pipelineState = useAppStore((s) => s.pipelineState)
  const pipelineError = useAppStore((s) => s.pipelineError)
  const activeVoiceMode = useAppStore((s) => s.activeVoiceMode)
  const recordingStyle = useAppStore((s) => s.recordingStyle)
  const capsuleAutoHide = useAppStore((s) => s.config.capsule_auto_hide)
  const contextMenuOpen = useAppStore((s) => s.contextMenuOpen)
  const setContextMenuOpen = useAppStore((s) => s.setContextMenuOpen)
  const contextMenuReady = useAppStore((s) => s.contextMenuReady)
  const setContextMenuReady = useAppStore((s) => s.setContextMenuReady)
  const translationTargetMenuOpen = useAppStore((s) => s.translationTargetMenuOpen)
  const setTranslationTargetMenuOpen = useAppStore((s) => s.setTranslationTargetMenuOpen)
  const { startRecording, stopRecording, isRecording } = useRecording()
  const { t } = useTranslation()
  const [hovered, setHovered] = useState(false)
  const [finishHover, setFinishHover] = useState(false)
  const hoverTimer = useRef<number | null>(null)

  const dragStart = useRef<{ x: number; y: number } | null>(null)
  const isDragging = useRef(false)
  const rootRef = useRef<HTMLDivElement | null>(null)

  const handlePointerEnter = useCallback(() => {
    if (hoverTimer.current) window.clearTimeout(hoverTimer.current)
    hoverTimer.current = window.setTimeout(() => setHovered(true), HOVER_DELAY_MS)
  }, [])
  const handlePointerLeave = useCallback(() => {
    if (hoverTimer.current) window.clearTimeout(hoverTimer.current)
    setHovered(false)
  }, [])
  const handleBarHoverChange = useCallback(
    (over: boolean) => (over ? handlePointerEnter() : handlePointerLeave()),
    [handlePointerEnter, handlePointerLeave],
  )

  useCapsuleResize()
  useCapsuleClickThrough(rootRef, handleBarHoverChange)

  const hasError = pipelineError !== null
  const view = getCapsuleView(pipelineState, hasError, hovered)
  const tone = getCapsuleTone(pipelineState, activeVoiceMode)
  const isAsk = pipelineState === 'ask_recording' || pipelineState === 'ask_thinking'
  // Ask always needs explicit finish/cancel controls, so it never uses the push-to-talk bar.
  const style = isAsk || tone === 'translate' ? 'handsFree' : recordingStyle
  const countdown = useCountdownRemainingMs(isAsk ? 'ask' : 'dictation') !== null
  const targetChip = pipelineState === 'recording' && activeVoiceMode === 'translate'
  const size = getCapsuleSize(view, { recordingStyle: style, countdown, targetChip })

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

  const finish = useCallback(() => {
    if (pipelineState === 'ask_recording') {
      void stopAskFlow().catch((error) => {
        console.error('Failed to stop Ask flow:', error)
      })
    } else if (isRecording) {
      stopRecording()
    }
  }, [isRecording, pipelineState, stopRecording])

  const handlePointerUp = useCallback(
    (e: React.PointerEvent) => {
      if (e.button !== 0) return
      if (isDragging.current) {
        isDragging.current = false
        dragStart.current = null
        return
      }
      dragStart.current = null
      // The hover pill advertises "click to start"; every other state finishes the take.
      if (view === 'hover') {
        setHovered(false)
        startRecording().catch((error) => console.error('Failed to start dictation:', error))
        return
      }
      finish()
    },
    [finish, startRecording, view],
  )

  const handleContextMenu = (e: React.MouseEvent) => {
    e.preventDefault()
    if (!contextMenuOpen) {
      setTranslationTargetMenuOpen(false)
      setContextMenuOpen(true)
    }
  }

  const handleCloseMenu = () => {
    setContextMenuReady(false)
    setContextMenuOpen(false)
  }

  useEffect(() => {
    if (translationTargetMenuOpen && pipelineState !== 'recording') {
      setTranslationTargetMenuOpen(false)
    }
  }, [pipelineState, setTranslationTargetMenuOpen, translationTargetMenuOpen])

  useEffect(
    () => () => {
      if (hoverTimer.current) window.clearTimeout(hoverTimer.current)
    },
    [],
  )

  const handsFree = view === 'recording' && style === 'handsFree'

  return (
    <div
      ref={rootRef}
      className="relative flex h-full w-full flex-col items-center justify-end pb-2"
      style={{ background: 'transparent' }}
      onContextMenu={handleContextMenu}
    >
      <TranslateTargetMenu />
      {contextMenuOpen && contextMenuReady && (
        <div className="mb-2">
          <CapsuleContextMenu onClose={handleCloseMenu} />
        </div>
      )}
      <CapsuleAlertBubble />

      <div className="relative">
        {handsFree && finishHover && <FinishTooltip />}
        <div
          data-capsule-view={view}
          data-capsule-style={view === 'recording' ? style : undefined}
          data-idle-hidden={view === 'idle' && capsuleAutoHide ? '' : undefined}
          className={`capsule-bar capsule-bar--${view} pointer-events-auto relative shrink-0`}
          style={size}
          onPointerEnter={handlePointerEnter}
          onPointerLeave={handlePointerLeave}
          onPointerDown={handlePointerDown}
          onPointerMove={handlePointerMove}
          onPointerUp={handlePointerUp}
          title={view === 'hover' ? t('capsule.clickToStart') : undefined}
        >
          {(view === 'recording' || view === 'processing') && tone !== 'dictate' && (
            <ModeGlow tone={tone} />
          )}
          {(view === 'hover' || view === 'error') && <IdleDots />}
          {view === 'recording' && (
            <RecordingControls
              handsFree={handsFree}
              isAsk={isAsk}
              onFinishHover={setFinishHover}
              onFinish={() => {
                setFinishHover(false)
                finish()
                if (claimPressToFinishHint())
                  useCapsuleAlertStore.getState().show('press_to_finish')
              }}
              onCancel={() => {
                const abort = isAsk ? abortAskDictation : abortRecording
                abort().catch((error) => console.error('Failed to cancel recording:', error))
                useCapsuleAlertStore.getState().show('transcript_cancelled')
              }}
            />
          )}
          {view === 'processing' && <ThinkingLabel />}
        </div>
      </div>
      {view === 'error' && <ErrorAlert />}
    </div>
  )
}

/** Typeless hover / error filler: ten 2px grey dots. */
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
  onCancel,
}: {
  handsFree: boolean
  isAsk: boolean
  onFinish: () => void
  onFinishHover: (hovered: boolean) => void
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
      <TranslateTargetChip />
      {handsFree && (
        <button
          type="button"
          aria-label={t('capsule.finish')}
          onPointerDown={stop}
          onPointerUp={stop}
          onPointerEnter={() => onFinishHover(true)}
          onPointerLeave={() => onFinishHover(false)}
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
function FinishTooltip() {
  const { t } = useTranslation()
  const { dictate } = useVoiceHotkeys()
  return (
    <div
      role="tooltip"
      className="capsule-tooltip pointer-events-none absolute bottom-[calc(100%+8px)] right-[-14px] z-10 flex items-center gap-1.5 whitespace-nowrap text-[12px] leading-[18px]"
    >
      {t('capsule.finish')}
      {dictate && <Keycaps hotkey={dictate} />}
    </div>
  )
}

/** Typeless "Thinking": dim text with a sweeping highlight over a fake progress fill. */
function ThinkingLabel() {
  const { t } = useTranslation()
  const reduced = useReducedMotion()
  const [elapsed, setElapsed] = useState(0)
  const showAlert = useCapsuleAlertStore((s) => s.show)
  const dismissAlert = useCapsuleAlertStore((s) => s.dismiss)

  useEffect(() => {
    const started = Date.now()
    const timer = window.setInterval(() => setElapsed((Date.now() - started) / 1000), 100)
    return () => {
      window.clearInterval(timer)
      dismissAlert('weak_network')
    }
  }, [dismissAlert])

  useEffect(() => {
    if (elapsed >= SLOW_PROCESSING_SECONDS) showAlert('weak_network')
  }, [elapsed >= SLOW_PROCESSING_SECONDS, showAlert]) // eslint-disable-line react-hooks/exhaustive-deps

  const progress = reduced ? 0 : thinkingProgress(elapsed)

  return (
    <>
      <div className="pointer-events-none absolute inset-0 overflow-hidden rounded-full">
        <div
          className="absolute inset-y-0 left-0 bg-[rgba(242,241,240,0.25)] transition-[width] duration-100 ease-linear"
          style={{ width: `${progress}%` }}
          aria-hidden="true"
        />
      </div>
      <div className="relative z-[1] flex h-full items-center justify-center">
        <span className="capsule-thinking-text text-[14px] font-[450] leading-4">
          {t('capsule.thinking')}
        </span>
      </div>
    </>
  )
}

/** Errors keep the dotted bar and explain themselves in a Typeless-style alert above it. */
function ErrorAlert() {
  const { t } = useTranslation()
  const pipelineError = useAppStore((s) => s.pipelineError)
  const setPipelineError = useAppStore((s) => s.setPipelineError)
  const resetRecording = useAppStore((s) => s.resetRecording)

  useEffect(() => {
    const timer = setTimeout(() => {
      setPipelineError(null)
      // A new recording may have started during the error window; do not clobber it.
      if (useAppStore.getState().pipelineState === 'idle') resetRecording()
    }, 2500)
    return () => clearTimeout(timer)
  }, [setPipelineError, resetRecording, pipelineError])

  return (
    <div
      role="alert"
      className="capsule-alert pointer-events-none absolute bottom-[50px] left-1/2 flex max-w-[400px] -translate-x-1/2 items-center gap-2 whitespace-nowrap px-3 py-2 text-[13px] leading-5"
    >
      <AlertTriangle size={15} className="shrink-0 text-[#ff9e6c]" />
      <span className="truncate">{pipelineError || t('capsule.errors.unknown')}</span>
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
