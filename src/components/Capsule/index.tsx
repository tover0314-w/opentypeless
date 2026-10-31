import { useRef, useCallback, useEffect, useState } from 'react'
import { motion, useReducedMotion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { X, Check } from 'lucide-react'
import { useAppStore } from '../../stores/appStore'
import { useCapsuleAlertStore } from '../../stores/capsuleAlertStore'
import { useRecording } from '../../hooks/useRecording'
import { useCapsuleResize } from '../../hooks/useCapsuleResize'
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
import { TranslateTargetChip, TranslateTargetMenu } from './TranslateTargetChip'
import { Waveform } from './Waveform'
import { DurationTimer } from './DurationTimer'

const DRAG_THRESHOLD = 5
const HOVER_DELAY_MS = 250

export function Capsule() {
  const pipelineState = useAppStore((s) => s.pipelineState)
  const pipelineError = useAppStore((s) => s.pipelineError)
  const activeVoiceMode = useAppStore((s) => s.activeVoiceMode)
  const contextMenuOpen = useAppStore((s) => s.contextMenuOpen)
  const setContextMenuOpen = useAppStore((s) => s.setContextMenuOpen)
  const contextMenuReady = useAppStore((s) => s.contextMenuReady)
  const setContextMenuReady = useAppStore((s) => s.setContextMenuReady)
  const translationTargetMenuOpen = useAppStore((s) => s.translationTargetMenuOpen)
  const setTranslationTargetMenuOpen = useAppStore((s) => s.setTranslationTargetMenuOpen)
  const { stopRecording, isRecording } = useRecording()
  const { t } = useTranslation()
  const [hovered, setHovered] = useState(false)
  const hoverTimer = useRef<number | null>(null)

  const dragStart = useRef<{ x: number; y: number } | null>(null)
  const isDragging = useRef(false)

  useCapsuleResize()

  const hasError = pipelineError !== null
  const view = getCapsuleView(pipelineState, hasError, hovered)
  const tone = getCapsuleTone(pipelineState, activeVoiceMode)
  const size = getCapsuleSize(view)
  const isAsk = pipelineState === 'ask_recording' || pipelineState === 'ask_thinking'

  const handlePointerEnter = () => {
    if (hoverTimer.current) window.clearTimeout(hoverTimer.current)
    hoverTimer.current = window.setTimeout(() => setHovered(true), HOVER_DELAY_MS)
  }
  const handlePointerLeave = () => {
    if (hoverTimer.current) window.clearTimeout(hoverTimer.current)
    setHovered(false)
  }

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
      finish()
    },
    [finish],
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

  const shellClass =
    view === 'idle' ? 'capsule-idle' : view === 'error' ? 'capsule-error' : 'capsule-active'

  return (
    <div
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
      {view === 'recording' && tone !== 'dictate' && <ModeLabel tone={tone} />}

      <div className="relative">
        {(view === 'recording' || view === 'processing') && tone !== 'dictate' && (
          <ModeGlow tone={tone} />
        )}
        <motion.div
          layout
          transition={{ layout: { duration: 0.2, ease: [0.05, 0.6, 0.4, 0.95] } }}
          data-capsule-view={view}
          className={`relative pointer-events-auto shrink-0 ${shellClass}`}
          style={size}
          onPointerEnter={handlePointerEnter}
          onPointerLeave={handlePointerLeave}
          onPointerDown={handlePointerDown}
          onPointerMove={handlePointerMove}
          onPointerUp={handlePointerUp}
          title={view === 'hover' ? t('capsule.clickToStart') : undefined}
        >
          {view === 'hover' && <HoverDots />}
          {view === 'starting' && <div className="capsule-shimmer" />}
          {view === 'recording' && (
            <RecordingControls
              isAsk={isAsk}
              onFinish={finish}
              onCancel={() => {
                const abort = isAsk ? abortAskDictation : abortRecording
                abort().catch((error) => console.error('Failed to cancel recording:', error))
                useCapsuleAlertStore.getState().show('transcript_cancelled')
              }}
            />
          )}
          {view === 'processing' && <ThinkingLabel />}
          {view === 'done' && <DoneLabel />}
          {view === 'error' && <ErrorLabel />}
        </motion.div>
      </div>
    </div>
  )
}

function HoverDots() {
  return (
    <div className="flex h-full items-center justify-center gap-[2px]" aria-hidden="true">
      {Array.from({ length: 10 }).map((_, i) => (
        <span key={i} className="h-[2px] w-[2px] rounded-full bg-white/50" />
      ))}
    </div>
  )
}

function RecordingControls({
  isAsk,
  onFinish,
  onCancel,
}: {
  isAsk: boolean
  onFinish: () => void
  onCancel: () => void
}) {
  const { t } = useTranslation()
  const stop = (event: React.PointerEvent) => event.stopPropagation()

  return (
    <div className="flex h-full items-center justify-between px-[5px]">
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
        className="grid h-6 w-6 place-items-center rounded-full border-none bg-[#424242] text-white/90 cursor-pointer hover:bg-[#555]"
      >
        <X size={14} strokeWidth={2.5} />
      </button>
      <div className="flex items-center gap-1">
        <Waveform />
        <DurationTimer recordingKind={isAsk ? 'ask' : 'dictation'} />
        <TranslateTargetChip />
      </div>
      <button
        type="button"
        aria-label={t('capsule.finish')}
        title={t('capsule.finish')}
        onPointerDown={stop}
        onPointerUp={stop}
        onClick={(event) => {
          event.stopPropagation()
          onFinish()
        }}
        className="grid h-6 w-6 place-items-center rounded-full border-none bg-white text-black cursor-pointer hover:bg-white/85"
      >
        <Check size={14} strokeWidth={3} />
      </button>
    </div>
  )
}

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
    <div className="relative flex h-full items-center justify-center overflow-hidden rounded-full">
      <div
        className="absolute inset-y-0 left-0 bg-[rgba(242,241,240,0.25)] transition-[width] duration-100 ease-linear"
        style={{ width: `${progress}%` }}
        aria-hidden="true"
      />
      <span className="shimmer-text relative text-[14px] font-medium leading-4">
        {t('capsule.thinking')}
      </span>
    </div>
  )
}

function DoneLabel() {
  const { t } = useTranslation()
  const resetRecording = useAppStore((s) => s.resetRecording)
  const setPipelineState = useAppStore((s) => s.setPipelineState)

  useEffect(() => {
    const timer = setTimeout(() => {
      resetRecording()
      setPipelineState('idle')
    }, 1200)
    return () => clearTimeout(timer)
  }, [resetRecording, setPipelineState])

  return (
    <div className="flex h-full items-center justify-center gap-1.5 text-[13px] font-medium">
      <Check size={14} />
      {t('capsule.done')}
    </div>
  )
}

function ErrorLabel() {
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
    <div className="flex h-full items-center justify-center px-4">
      <p className="truncate text-[13px]">{pipelineError || t('capsule.errors.unknown')}</p>
    </div>
  )
}

function ModeLabel({ tone }: { tone: CapsuleTone }) {
  const { t } = useTranslation()
  const activeTarget = useAppStore((s) => s.config.translation.active_target)
  return (
    <div className="capsule-alert pointer-events-none mb-2 px-3 py-1.5 text-[13px] font-medium">
      {tone === 'ask'
        ? t('capsule.modeAsk')
        : t('capsule.modeTranslating', { language: activeTarget.toUpperCase() })}
    </div>
  )
}

const GLOW: Record<Exclude<CapsuleTone, 'dictate'>, string> = {
  translate:
    'radial-gradient(ellipse at center, rgba(60,203,182,1) 0%, rgba(60,203,182,0.55) 45%, transparent 75%)',
  ask: 'radial-gradient(ellipse at center, rgba(120,214,255,1) 0%, rgba(42,187,167,0.5) 45%, transparent 75%)',
}

function ModeGlow({ tone }: { tone: Exclude<CapsuleTone, 'dictate'> }) {
  return (
    <div
      aria-hidden="true"
      data-capsule-glow={tone}
      className="pointer-events-none absolute left-1/2 top-[-22px] h-[32px] w-[116px] -translate-x-1/2"
      style={{ background: GLOW[tone], filter: 'blur(7px)' }}
    />
  )
}

export type { CapsuleView }
