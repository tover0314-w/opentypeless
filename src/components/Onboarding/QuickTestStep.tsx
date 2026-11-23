import { useCallback, useEffect, useRef, useState } from 'react'
import { Mic, FileText, Sparkles, Type, Keyboard } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../../stores/appStore'
import { Keycaps } from '../shared/Keycaps'
import { useVoiceHotkeys } from '../../lib/hotkeyLabels'
import { usePracticeSession } from './usePracticeSession'
import { capsuleErrorKeyFromPayload } from '../../lib/capsuleError'
import { TARGET_LANGUAGES } from '../../lib/constants'
import type { PracticeOptions } from '../../lib/tauri'
import { usePracticeShortcut } from './usePracticeShortcut'

const STAGES = [
  { icon: Mic, label: 'onboarding.test.stageRecord', key: 'recording' },
  { icon: FileText, label: 'onboarding.test.stageStt', key: 'transcribing' },
  { icon: Sparkles, label: 'onboarding.test.stageLlm', key: 'polishing' },
  { icon: Type, label: 'onboarding.test.stageOutput', key: 'complete' },
] as const

export function QuickTestStep({
  guided = false,
  initialMode = 'dictate',
  onComplete,
}: {
  guided?: boolean
  initialMode?: PracticeOptions['mode']
  onComplete?: () => void
}) {
  const [mode, setMode] = useState<PracticeOptions['mode']>(guided ? 'dictate' : initialMode)
  const [completed, setCompleted] = useState(false)
  const markCompleted = useCallback(() => setCompleted(true), [])
  const resetCompleted = useCallback(() => setCompleted(false), [])
  const { t } = useTranslation()
  const next = () => {
    if (!completed) return
    if (mode === 'ask') {
      onComplete?.()
      return
    }
    setCompleted(false)
    setMode(mode === 'dictate' ? 'translate' : 'ask')
  }
  return (
    <div className="space-y-4">
      {guided ? (
        <h2 className="text-[16px] font-medium text-text-primary">
          {t(`home.modes.${mode}.title`)}
        </h2>
      ) : (
        <div role="tablist" aria-label={t('onboarding.steps.howItWorks')} className="flex gap-2">
          {(['dictate', 'translate', 'ask'] as const).map((value) => (
            <button
              key={value}
              type="button"
              role="tab"
              aria-selected={mode === value}
              onClick={() => setMode(value)}
              className={`rounded-[8px] px-3 py-2 text-[13px] ${
                mode === value ? 'bg-accent/10 text-accent' : 'bg-bg-secondary text-text-secondary'
              }`}
            >
              {t(`home.modes.${value}.title`)}
            </button>
          ))}
        </div>
      )}
      <PracticeExercise
        key={mode}
        mode={mode}
        onCompleted={markCompleted}
        onReset={resetCompleted}
      />
      {guided && (
        <button
          type="button"
          onClick={next}
          disabled={!completed}
          className="btn-primary px-4 py-2 text-[13px] disabled:opacity-40"
        >
          {t('onboarding.layout.next')}
        </button>
      )}
    </div>
  )
}

export function PracticeExercise({
  mode,
  onCompleted,
  onReset,
  onProgress,
  askAction = 'answer',
  initialSelectedText = '',
  initialTargetLanguage,
  onTargetLanguageChange,
}: {
  mode: PracticeOptions['mode']
  onCompleted?: () => void
  onReset?: () => void
  onProgress?: (text: string, complete: boolean) => void
  askAction?: PracticeOptions['askAction']
  initialSelectedText?: string
  initialTargetLanguage?: string
  onTargetLanguageChange?: (language: string) => void
}) {
  const { t } = useTranslation()
  const config = useAppStore((state) => state.config)
  const hotkeys = useVoiceHotkeys()
  const shortcut = hotkeys[mode]
  const holdToTalk = mode !== 'ask' && config.hotkey_mode === 'hold'
  const [targetLanguage, setTargetLanguage] = useState(
    initialTargetLanguage ?? config.translation.active_target,
  )
  const [selectedText, setSelectedText] = useState(initialSelectedText)
  const practice = usePracticeSession({
    mode,
    ...(mode === 'ask' && askAction !== 'answer' ? { askAction } : {}),
    ...(mode === 'translate' || (mode === 'ask' && askAction === 'translate')
      ? { targetLanguage }
      : {}),
    ...(mode === 'ask' && selectedText.trim() ? { selectedText } : {}),
  })
  const [practicing, setPracticing] = useState(false)
  const shortcutPress = useRef<{
    startedAt: number
    mode: typeof config.hotkey_mode
    active: boolean
    guideShown: boolean
  } | null>(null)
  const guideTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const clearGuideTimer = () => {
    if (guideTimer.current !== null) clearTimeout(guideTimer.current)
    guideTimer.current = null
  }
  useEffect(
    () => () => {
      if (guideTimer.current !== null) clearTimeout(guideTimer.current)
      shortcutPress.current = null
    },
    [],
  )
  const [showGuide, setShowGuide] = useState(false)
  const phase = practice.snapshot?.phase
  const currentPhase = useRef(phase)
  currentPhase.current = phase
  const togglePractice = () => {
    if (
      !practicing ||
      practice.failed ||
      ['complete', 'error', 'cancelled'].includes(phase ?? '')
    ) {
      onReset?.()
      setPracticing(true)
      void practice.start()
    } else {
      void practice.stop()
    }
  }
  usePracticeShortcut(
    mode,
    (pressed) => {
      if (pressed) {
        if (shortcutPress.current) return
        const active =
          practicing &&
          !practice.failed &&
          !['complete', 'error', 'cancelled'].includes(phase ?? '')
        const press = { startedAt: Date.now(), mode: config.hotkey_mode, active, guideShown: false }
        shortcutPress.current = press
        setShowGuide(false)
        if (press.mode === 'auto' && !active) {
          guideTimer.current = setTimeout(() => {
            if (shortcutPress.current !== press) return
            if (
              mode === 'ask' &&
              ['complete', 'error', 'cancelled'].includes(currentPhase.current ?? '')
            )
              return
            press.guideShown = true
            setShowGuide(true)
            if (mode === 'ask') {
              onReset?.()
              void practice.cancel()
              setPracticing(false)
            }
          }, 600)
        }
        if (config.hotkey_mode === 'auto' && mode !== 'ask') return
        if (mode === 'ask') {
          togglePractice()
          return
        }
        if (
          !practicing ||
          practice.failed ||
          ['complete', 'error', 'cancelled'].includes(phase ?? '')
        ) {
          onReset?.()
          setPracticing(true)
          void practice.start()
        } else if (!holdToTalk) {
          void practice.stop()
        }
      } else {
        const press = shortcutPress.current
        shortcutPress.current = null
        clearGuideTimer()
        setShowGuide(false)
        if (!press) return
        if (press.mode === 'auto') {
          if (Date.now() - press.startedAt >= 600) {
            if (
              mode === 'ask' &&
              !press.active &&
              !press.guideShown &&
              !['complete', 'error', 'cancelled'].includes(currentPhase.current ?? '')
            ) {
              onReset?.()
              void practice.cancel()
              setPracticing(false)
            }
          } else if (mode !== 'ask') togglePractice()
        } else if (mode !== 'ask' && press.mode === 'hold') {
          void practice.stop()
        }
      }
    },
    () => {
      shortcutPress.current = null
      clearGuideTimer()
      setShowGuide(false)
      onReset?.()
      void practice.cancel()
      setPracticing(false)
    },
  )
  useEffect(() => {
    if (phase === 'complete' && practice.snapshot?.result.trim()) onCompleted?.()
  }, [phase, practice.snapshot?.result, onCompleted])
  const stages = STAGES.filter(
    (stage) => mode !== 'dictate' || config.polish_enabled || stage.key !== 'polishing',
  )
  const activeIndex = stages.findIndex((stage) => stage.key === phase)
  const terminal = ['complete', 'error', 'cancelled'].includes(phase ?? '')
  const resetInput = () => {
    clearGuideTimer()
    shortcutPress.current = null
    setShowGuide(false)
    onReset?.()
    void practice.cancel()
    setPracticing(false)
  }
  const errorKey = practice.snapshot?.error
    ? capsuleErrorKeyFromPayload({
        code: practice.snapshot.error.code,
        details: practice.snapshot.error.details ?? undefined,
      })
    : 'unknown'
  const output =
    practice.snapshot?.result || practice.snapshot?.polishPreview || practice.snapshot?.rawText
  useEffect(() => {
    onProgress?.(output ?? '', phase === 'complete')
    return () => onProgress?.('', false)
  }, [output, phase, onProgress])

  return (
    <div className="space-y-4">
      <p className="text-[12px] text-text-secondary leading-relaxed">{t('practice.notice')}</p>
      {showGuide && (
        <p role="status" className="rounded-lg bg-accent/10 p-3 text-[13px] text-accent">
          {t('settings.autoPressModeDesc')}
        </p>
      )}
      <div className="inline-flex flex-wrap items-center gap-1.5 rounded-[8px] border border-border bg-bg-secondary px-3 py-2">
        <Keyboard size={14} className="text-text-tertiary" />
        <span className="text-[12px] text-text-secondary">
          {holdToTalk ? t('onboarding.test.hold') : t('onboarding.test.press')}
        </span>
        {shortcut && <Keycaps hotkey={shortcut} />}
        <span className="text-[12px] text-text-secondary">
          {holdToTalk ? t('onboarding.test.toTalk') : t('onboarding.test.toStartStop')}
        </span>
      </div>
      {(mode === 'translate' || (mode === 'ask' && askAction === 'translate')) && (
        <label className="block text-[13px] text-text-secondary">
          {t('settings.targetLanguage')}
          <select
            className="field-control mt-2"
            value={targetLanguage}
            disabled={practicing && !terminal && !practice.failed}
            onChange={(event) => {
              resetInput()
              setTargetLanguage(event.target.value)
              onTargetLanguageChange?.(event.target.value)
            }}
          >
            {TARGET_LANGUAGES.map((language) => (
              <option key={language.value} value={language.value}>
                {language.label}
              </option>
            ))}
          </select>
        </label>
      )}
      {mode === 'ask' && askAction !== 'draft' && (
        <label className="block text-[13px] text-text-secondary">
          {t('ask.usingSelectedText')}
          <textarea
            className="field-control mt-2 min-h-24"
            value={selectedText}
            maxLength={4000}
            disabled={practicing && !terminal && !practice.failed}
            onChange={(event) => {
              resetInput()
              setSelectedText(event.target.value)
            }}
          />
        </label>
      )}
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {stages.map((stage, index) => {
          const Icon = stage.icon
          const active = activeIndex === index
          const finished = activeIndex > index
          return (
            <div
              key={stage.key}
              aria-current={active ? 'step' : undefined}
              className={`flex items-center gap-2 rounded-[8px] px-3 py-2 text-[12px] ${
                active
                  ? 'bg-accent/10 text-accent'
                  : finished
                    ? 'bg-success/10 text-success'
                    : 'bg-bg-secondary text-text-tertiary'
              }`}
            >
              <Icon size={14} />
              <span>{t(stage.label)}</span>
            </div>
          )
        })}
      </div>
      <div className="flex flex-wrap items-center gap-3">
        {(!practicing || practice.failed || terminal) && (
          <button
            type="button"
            className="rounded-[8px] bg-accent px-4 py-2 text-[13px] font-medium text-white disabled:opacity-50"
            disabled={practice.starting}
            onClick={() => {
              onReset?.()
              setPracticing(true)
              void practice.start()
            }}
          >
            {mode === 'dictate' ? t('practice.start') : t(`home.modes.${mode}.title`)}
          </button>
        )}
        {practicing && phase === 'recording' && (
          <button
            type="button"
            className="text-[13px] text-accent hover:underline disabled:opacity-50"
            disabled={practice.stopping}
            onClick={() => void practice.stop()}
          >
            {t('practice.stop')}
          </button>
        )}
        {practicing && (
          <button
            type="button"
            className="text-[13px] text-text-secondary hover:underline"
            onClick={() => {
              onReset?.()
              void practice.cancel()
              setPracticing(false)
            }}
          >
            {t('practice.cancel')}
          </button>
        )}
      </div>
      {practicing && (
        <div className="space-y-3" aria-live="polite">
          <p className="text-[12px] text-text-secondary">
            {practice.starting
              ? t('practice.preparing')
              : practice.failed || phase === 'error'
                ? errorKey === 'unknown'
                  ? t('practice.error')
                  : t(`capsule.errors.${errorKey}`)
                : phase && t(phase === 'cancelled' ? 'practice.cancel' : `practice.${phase}`)}
          </p>
          {phase === 'recording' && (
            <meter
              className="h-1 w-full accent-accent"
              min={0}
              max={1}
              value={practice.snapshot?.level ?? 0}
              aria-label={t('onboarding.test.stageRecord')}
            />
          )}
          {output && (
            <div className="rounded-[10px] bg-bg-secondary p-4 text-[13px] break-words whitespace-pre-wrap">
              {practice.snapshot?.rawText && practice.snapshot.rawText !== output && (
                <p className="mb-3 text-text-tertiary">{practice.snapshot.rawText}</p>
              )}
              <p className="mb-1 text-[11px] text-text-tertiary">
                {t(
                  phase === 'complete'
                    ? 'onboarding.test.result'
                    : phase === 'polishing'
                      ? 'onboarding.test.polished'
                      : 'onboarding.test.transcribing',
                )}
              </p>
              <p className="text-text-primary">{output}</p>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
