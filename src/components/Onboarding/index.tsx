import { AnimatePresence, motion } from 'framer-motion'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppStore, type AppConfig } from '../../stores/appStore'
import { useAuthStore } from '../../stores/authStore'
import { saveOnboardingCompleted, saveOnboardingConfig } from '../../lib/tauri'
import { OnboardingLayout } from './OnboardingLayout'
import { WelcomeStep } from './WelcomeStep'
import { AccountStep } from './AccountStep'
import { ModeSelectStep } from './ModeSelectStep'
import { SttSetupStep } from './SttSetupStep'
import { LlmSetupStep } from './LlmSetupStep'
import { PermissionsStep } from './PermissionsStep'
import { GuidedPracticeFlow } from './GuidedPracticeFlow'
import { DoneStep } from './DoneStep'
import { slideRight } from '../../lib/animations'
import type { OnboardingStage } from './stages'
import { invoke } from '@tauri-apps/api/core'
import { valuesEqual } from '../../lib/configValuesEqual'

const TOTAL_STEPS = 8

export function Onboarding() {
  const { t } = useTranslation()
  const [saveFailed, setSaveFailed] = useState(false)
  const [saving, setSaving] = useState(false)
  const [practiceCompleted, setPracticeCompleted] = useState(false)
  const [practiceLayout, setPracticeLayout] = useState<'split' | 'fullWidth'>('fullWidth')
  const [practiceSurface, setPracticeSurface] = useState<React.ReactNode>(null)
  const [practiceProgressValue, setPracticeProgressValue] = useState(50)
  const [practiceStage, setPracticeStage] = useState<OnboardingStage>('setUp')
  const practiceBack = useRef<(() => void) | null>(null)
  const registerPracticeBack = useCallback((handler: (() => void) | null) => {
    practiceBack.current = handler
  }, [])
  const navigationPending = useRef(false)
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  const navigate = async (action: () => Promise<void>) => {
    if (!mounted.current || navigationPending.current) return
    navigationPending.current = true
    setSaving(true)
    try {
      await action()
    } finally {
      navigationPending.current = false
      if (mounted.current) setSaving(false)
    }
  }
  const step = useAppStore((s) => s.onboardingStep)
  const setStep = useAppStore((s) => s.setOnboardingStep)
  const setOnboardingCompleted = useAppStore((s) => s.setOnboardingCompleted)
  const sttTestStatus = useAppStore((s) => s.sttTestStatus)
  const llmTestStatus = useAppStore((s) => s.llmTestStatus)
  const onboardingMode = useAppStore((s) => s.onboardingMode)
  const setOnboardingMode = useAppStore((s) => s.setOnboardingMode)
  const updateConfig = useAppStore((s) => s.updateConfig)
  const setConfig = useAppStore((s) => s.setConfig)
  const setSavedConfig = useAppStore((s) => s.setSavedConfig)
  const user = useAuthStore((s) => s.user)
  const config = useAppStore((s) => s.config)
  const navigationIdentity = useRef({ step, mode: onboardingMode, user, config })
  navigationIdentity.current = { step, mode: onboardingMode, user, config }
  const isCurrentNavigation = (saved?: AppConfig) =>
    mounted.current &&
    navigationIdentity.current.step === step &&
    navigationIdentity.current.mode === onboardingMode &&
    navigationIdentity.current.user === user &&
    (valuesEqual(navigationIdentity.current.config, config) ||
      (saved !== undefined &&
        useAppStore.getState().configCommit !== null &&
        valuesEqual(navigationIdentity.current.config, saved)))

  const canNext = (() => {
    switch (step) {
      case 0:
        return true // Welcome — always
      case 1:
        return !!user // Account — need login to Next (Skip to bypass)
      case 2:
        return onboardingMode !== null // Mode — need selection
      case 3:
        return sttTestStatus === 'success' // STT must pass (BYOK only)
      case 4:
        return !config.polish_enabled || llmTestStatus === 'success'
      case 5:
        return true // Permissions — optional
      case 6:
        return practiceCompleted
      case 7:
        return true // Done
      default:
        return false
    }
  })()
  const currentCanNext = useRef(canNext)
  currentCanNext.current = canNext

  const titles = [
    { title: t('onboarding.steps.welcome'), subtitle: t('onboarding.steps.welcomeSub') },
    { title: t('onboarding.steps.signIn'), subtitle: t('onboarding.steps.signInSub') },
    { title: t('onboarding.steps.chooseMode'), subtitle: t('onboarding.steps.chooseModeSub') },
    {
      title: t('onboarding.steps.speechRecognition'),
      subtitle: t('onboarding.steps.speechRecognitionSub'),
    },
    { title: t('onboarding.steps.aiPolish'), subtitle: t('onboarding.steps.aiPolishSub') },
    { title: t('onboarding.steps.permissions'), subtitle: t('onboarding.steps.permissionsSub') },
    { title: t('onboarding.steps.howItWorks'), subtitle: t('onboarding.steps.howItWorksSub') },
    { title: t('onboarding.steps.setupComplete'), subtitle: undefined },
  ]

  const saveConfig = async (draft = config) => {
    setSaveFailed(false)
    try {
      const saved = await saveOnboardingConfig(draft)
      if (!isCurrentNavigation(saved)) throw new Error('Onboarding navigation is no longer active')
      return saved
    } catch (error) {
      if (isCurrentNavigation()) setSaveFailed(true)
      throw error
    }
  }

  const handleNext = async () => {
    if (!canNext || !isCurrentNavigation()) return
    if (step < TOTAL_STEPS - 1) {
      if (step === 2 && onboardingMode === 'cloud') {
        try {
          await saveConfig({ ...config, stt_provider: 'cloud', llm_provider: 'cloud' })
        } catch {
          return
        }
        updateConfig({ stt_provider: 'cloud', llm_provider: 'cloud' })
        setStep(5)
        return
      }

      try {
        await saveConfig(config)
      } catch {
        return
      }

      if (!currentCanNext.current) return
      setStep(step === 3 && !config.polish_enabled ? 5 : step + 1)
    } else {
      try {
        const saved = await saveConfig(config)
        await saveOnboardingCompleted()
        if (!isCurrentNavigation(saved)) return
        if (useAppStore.getState().configCommit === null) {
          setConfig(saved)
          setSavedConfig(saved)
        }
        setOnboardingCompleted(true)
      } catch {
        if (isCurrentNavigation()) setSaveFailed(true)
      }
    }
  }

  const handleBack = async () => {
    if (step > 0) {
      try {
        await saveConfig(config)
      } catch {
        return
      }

      // Cloud mode skips provider setup, so Permissions returns to Mode Select.
      if (step === 5 && onboardingMode === 'cloud') {
        setStep(2)
        return
      }
      if (step === 5 && !config.polish_enabled) {
        setStep(3)
        return
      }

      // If coming back from STT setup and user skipped login, go back to Account (step 1)
      if (step === 3 && !user) {
        setStep(1)
        return
      }

      setStep(step - 1)
    }
  }

  const handleSkip = async () => {
    if (step === 6) {
      try {
        await saveConfig(config)
      } catch {
        return
      }
      setStep(7)
      return
    }
    if (step === 1) {
      // Skip login → go straight to BYOK STT setup
      try {
        await saveConfig(config)
      } catch {
        return
      }
      setOnboardingMode('byok')
      setStep(3)
      return
    }
    // Original behavior for other steps — skip entire onboarding
    try {
      const saved = await saveConfig(config)
      await saveOnboardingCompleted()
      if (!isCurrentNavigation(saved)) return
      if (useAppStore.getState().configCommit === null) {
        setConfig(saved)
        setSavedConfig(saved)
      }
    } catch {
      if (isCurrentNavigation()) setSaveFailed(true)
      return
    }
    setOnboardingCompleted(true)
  }

  const handleClose = async () => {
    try {
      await saveConfig(config)
      await invoke('plugin:process|exit', { code: 0 })
    } catch {
      if (isCurrentNavigation()) setSaveFailed(true)
    }
  }

  return (
    <OnboardingLayout
      step={step}
      totalSteps={TOTAL_STEPS}
      layout={
        step === 0
          ? 'fullWidth'
          : step === 1
            ? 'accountCard'
            : step === 7
              ? 'fullWidth'
              : step === 6
                ? practiceLayout
                : 'split'
      }
      showNextButton={step !== 6 || practiceCompleted}
      showHeading={step !== 0 && step !== 6}
      progressValue={step === 6 ? practiceProgressValue : undefined}
      activeStage={step === 6 ? practiceStage : undefined}
      rightContent={step === 6 ? practiceSurface : undefined}
      title={titles[step].title}
      subtitle={titles[step].subtitle}
      canNext={canNext && !saving}
      canBack={step > 0 && !saving}
      busy={saving}
      nextLabel={
        step === TOTAL_STEPS - 1 ? t('onboarding.steps.getStarted') : t('onboarding.layout.next')
      }
      onNext={() => void navigate(handleNext)}
      onBack={() => {
        if (step === 6 && practiceBack.current) practiceBack.current()
        else void navigate(handleBack)
      }}
      onSkip={() => void navigate(handleSkip)}
      onClose={() => void navigate(handleClose)}
    >
      {saveFailed && (
        <p role="alert" className="mb-3 text-sm text-error">
          {t('common.saveFailed')}
        </p>
      )}
      <AnimatePresence mode="wait">
        <motion.div
          key={step}
          variants={slideRight}
          initial="initial"
          animate="animate"
          exit="exit"
          transition={{ duration: 0.2 }}
          className={step === 0 || step >= 6 ? 'h-full' : undefined}
        >
          {step === 0 && <WelcomeStep />}
          {step === 1 && <AccountStep />}
          {step === 2 && <ModeSelectStep />}
          {step === 3 && <SttSetupStep />}
          {step === 4 && <LlmSetupStep />}
          {step === 5 && <PermissionsStep />}
          {step === 6 && (
            <GuidedPracticeFlow
              onComplete={() => setPracticeCompleted(true)}
              onReset={() => setPracticeCompleted(false)}
              onLayoutChange={setPracticeLayout}
              onSurfaceChange={setPracticeSurface}
              onProgressChange={setPracticeProgressValue}
              onStageChange={setPracticeStage}
              onBackChange={registerPracticeBack}
              onExitBack={() => void navigate(handleBack)}
            />
          )}
          {step === 7 && <DoneStep />}
        </motion.div>
      </AnimatePresence>
    </OnboardingLayout>
  )
}
