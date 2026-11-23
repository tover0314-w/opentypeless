import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { PracticeOptions } from '../../lib/tauri'
import { ModeIntroduction } from './ModeIntroduction'
import { ShortcutLesson } from './ShortcutLesson'
import { PracticeExercise } from './QuickTestStep'
import { SettingsModal } from '../Settings'
import { DICTATION_EXAMPLES, dictationExampleText } from './dictationExamples'
import { PracticeSurface } from './PracticeSurface'
import { ASK_EXAMPLES, askExampleText } from './askExamples'
import { MicrophoneTestStep } from './MicrophoneTestStep'
import { TARGET_LANGUAGES } from '../../lib/constants'
import { useAppStore } from '../../stores/appStore'
import { practiceProgress } from './practiceProgress'
import type { OnboardingStage } from './stages'
import { motion, useReducedMotion } from 'framer-motion'
import { slideRight } from '../../lib/animations'
import { ShortcutSurface } from './ShortcutSurface'
import { useVoiceHotkeys } from '../../lib/hotkeyLabels'

export interface PracticePage {
  mode: PracticeOptions['mode']
  stage: 'microphone' | 'introduction' | 'shortcut' | 'language' | 'exercise'
  exampleIndex?: number
}

export function GuidedPracticeFlow({
  onComplete,
  onReset,
  onLayoutChange,
  onSurfaceChange,
  onBackChange,
  onExitBack,
  initialPage,
  onConfigureShortcut,
  onProgressChange,
  onStageChange,
  singleMode = false,
}: {
  onComplete: () => void
  onReset?: () => void
  onLayoutChange: (layout: 'split' | 'fullWidth') => void
  onSurfaceChange?: (surface: React.ReactNode) => void
  onBackChange?: (handler: (() => void) | null) => void
  onExitBack?: () => void
  initialPage?: PracticePage
  onConfigureShortcut?: () => void
  onProgressChange?: (progress: number) => void
  onStageChange?: (stage: OnboardingStage) => void
  singleMode?: boolean
}) {
  const { t, i18n } = useTranslation()
  const reducedMotion = useReducedMotion()
  const [mode, setMode] = useState<PracticeOptions['mode']>(initialPage?.mode ?? 'dictate')
  const [stage, setStage] = useState<PracticePage['stage']>(initialPage?.stage ?? 'microphone')
  const [completed, setCompleted] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [exampleIndex, setExampleIndex] = useState(initialPage?.exampleIndex ?? 0)
  const [progress, setProgress] = useState({ text: '', complete: false })
  const [microphoneLevel, setMicrophoneLevel] = useState(0)
  const [shortcutPressed, setShortcutPressed] = useState(false)
  const shortcuts = useVoiceHotkeys()
  const currentShortcut = shortcuts[mode] ?? ''
  const configuredTarget = useAppStore((state) => state.config.translation.active_target)
  const [targetLanguage, setTargetLanguage] = useState(configuredTarget)
  useEffect(() => {
    onProgressChange?.(practiceProgress({ mode, stage, exampleIndex }))
  }, [mode, stage, exampleIndex, onProgressChange])
  useEffect(() => {
    onStageChange?.(stage === 'microphone' ? 'setUp' : 'tryIt')
  }, [stage, onStageChange])
  const resetCompletion = useCallback(() => {
    setCompleted(false)
    onReset?.()
  }, [onReset])
  const back = useCallback(() => {
    resetCompletion()
    setProgress({ text: '', complete: false })
    if (settingsOpen) {
      setSettingsOpen(false)
    } else if (stage === 'exercise' && mode !== 'translate' && exampleIndex > 0) {
      setExampleIndex(exampleIndex - 1)
    } else if (stage === 'exercise') {
      setStage(mode === 'translate' ? 'language' : 'shortcut')
    } else if (stage === 'language') {
      setStage('shortcut')
    } else if (stage === 'shortcut') {
      setStage('introduction')
    } else if (singleMode && stage === 'introduction') {
      onExitBack?.()
    } else if (mode !== 'dictate') {
      const previousMode = mode === 'ask' ? 'translate' : 'dictate'
      setMode(previousMode)
      setExampleIndex(previousMode === 'dictate' ? DICTATION_EXAMPLES.length - 1 : 0)
      setStage('exercise')
    } else if (stage === 'introduction') {
      setStage('microphone')
    } else {
      onExitBack?.()
    }
  }, [settingsOpen, stage, mode, exampleIndex, onExitBack, resetCompletion, singleMode])
  useEffect(() => {
    onBackChange?.(back)
    return () => onBackChange?.(null)
  }, [back, onBackChange])
  const onProgress = useCallback((text: string, complete: boolean) => {
    setProgress((previous) =>
      previous.text === text && previous.complete === complete ? previous : { text, complete },
    )
  }, [])
  useEffect(() => {
    onSurfaceChange?.(
      stage === 'microphone' ? (
        <div className="w-full max-w-[400px] rounded-2xl border border-border bg-bg-elevated p-6">
          <p className="mb-6 text-[18px] font-medium">{t('microphoneDialog.level')}</p>
          <meter
            min={0}
            max={1}
            value={microphoneLevel}
            aria-label={t('microphoneDialog.level')}
            className="h-6 w-full accent-accent"
          />
        </div>
      ) : stage === 'shortcut' ? (
        <ShortcutSurface shortcut={currentShortcut} pressed={shortcutPressed} />
      ) : stage === 'exercise' ? (
        <PracticeSurface
          mode={mode}
          example={exampleIndex}
          text={progress.text}
          complete={progress.complete}
        />
      ) : null,
    )
  }, [
    stage,
    mode,
    exampleIndex,
    progress,
    microphoneLevel,
    currentShortcut,
    shortcutPressed,
    t,
    onSurfaceChange,
  ])
  useEffect(() => () => onSurfaceChange?.(null), [onSurfaceChange])
  useEffect(() => {
    onLayoutChange(stage === 'introduction' ? 'fullWidth' : 'split')
    return () => onLayoutChange('split')
  }, [stage, onLayoutChange])
  const advance = () => {
    if (!completed) return
    const exampleCount =
      mode === 'dictate' ? DICTATION_EXAMPLES.length : mode === 'ask' ? ASK_EXAMPLES.length : 1
    if (exampleIndex < exampleCount - 1) {
      setCompleted(false)
      setExampleIndex(exampleIndex + 1)
      return
    }
    if (singleMode || mode === 'ask') {
      onComplete()
      return
    }
    setMode(mode === 'dictate' ? 'translate' : 'ask')
    setCompleted(false)
    setExampleIndex(0)
    setStage('introduction')
  }
  return (
    <motion.div
      key={`${mode}-${stage}-${exampleIndex}`}
      data-practice-page={`${mode}-${stage}-${exampleIndex}`}
      className="h-full"
      variants={slideRight}
      initial={reducedMotion ? false : 'initial'}
      animate="animate"
      transition={{ duration: reducedMotion ? 0 : 0.2 }}
    >
      {stage === 'microphone' && (
        <MicrophoneTestStep
          onContinue={() => setStage('introduction')}
          onLevelChange={onSurfaceChange ? setMicrophoneLevel : undefined}
        />
      )}
      {stage === 'introduction' && (
        <ModeIntroduction mode={mode} onContinue={() => setStage('shortcut')} />
      )}
      {stage === 'shortcut' && !settingsOpen && (
        <ShortcutLesson
          key={mode}
          mode={mode}
          onPressedChange={onSurfaceChange ? setShortcutPressed : undefined}
          onContinue={() => setStage(mode === 'translate' ? 'language' : 'exercise')}
          onConfigure={() => {
            if (onConfigureShortcut) onConfigureShortcut()
            else setSettingsOpen(true)
          }}
        />
      )}
      {stage === 'language' && (
        <div className="space-y-6 pt-10">
          <h2 className="text-[30px] font-semibold leading-[34px]">
            {t('settings.targetLanguage')}
          </h2>
          <label className="block text-[16px] text-text-secondary">
            {t('settings.targetLanguage')}
            <select
              className="field-control mt-4"
              value={targetLanguage}
              onChange={(event) => setTargetLanguage(event.target.value)}
            >
              {TARGET_LANGUAGES.map((language) => (
                <option key={language.value} value={language.value}>
                  {language.label}
                </option>
              ))}
            </select>
          </label>
          <button
            className="btn-primary rounded-full px-4 py-2"
            disabled={!TARGET_LANGUAGES.some((language) => language.value === targetLanguage)}
            onClick={() => setStage('exercise')}
          >
            {t('onboarding.layout.next')}
          </button>
        </div>
      )}
      {stage === 'exercise' && (
        <div className="space-y-4">
          <h1 className="pt-10 text-[30px] font-semibold leading-[34px]">
            {t(`home.modes.${mode}.title`)}
          </h1>
          {mode === 'dictate' && (
            <div className="rounded-xl bg-fill-thin p-3">
              <p className="mb-2 text-[12px] text-text-tertiary">
                {exampleIndex + 1} / {DICTATION_EXAMPLES.length}
              </p>
              <p className="text-[16px] leading-6 text-text-primary">
                {dictationExampleText(exampleIndex, i18n.language)}
              </p>
            </div>
          )}
          {mode === 'ask' && (
            <div className="rounded-xl bg-fill-thin p-3">
              <p className="mb-2 text-[12px] text-text-tertiary">
                {exampleIndex + 1} / {ASK_EXAMPLES.length}
              </p>
              <p className="text-[16px] leading-6 text-text-primary">
                {askExampleText(exampleIndex, i18n.language)}
              </p>
            </div>
          )}
          <PracticeExercise
            key={`${mode}-${exampleIndex}`}
            mode={mode}
            initialTargetLanguage={mode === 'translate' ? targetLanguage : undefined}
            onTargetLanguageChange={mode === 'translate' ? setTargetLanguage : undefined}
            onCompleted={() => setCompleted(true)}
            onReset={resetCompletion}
            onProgress={onProgress}
            askAction={mode === 'ask' ? ASK_EXAMPLES[exampleIndex].action : undefined}
            initialSelectedText={
              mode === 'ask' ? askExampleText(exampleIndex, i18n.language, true) : ''
            }
          />
          <button
            type="button"
            className="btn-primary rounded-full px-4 py-2 disabled:opacity-40"
            disabled={!completed}
            onClick={advance}
          >
            {t('onboarding.layout.next')}
          </button>
        </div>
      )}
      {settingsOpen && <SettingsModal onClose={() => setSettingsOpen(false)} />}
    </motion.div>
  )
}
