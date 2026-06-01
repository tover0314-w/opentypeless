import { AnimatePresence, motion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../../stores/appStore'
import { useAuthStore } from '../../stores/authStore'
import { saveOnboardingCompleted, updateConfig as saveConfig } from '../../lib/tauri'
import { OnboardingLayout } from './OnboardingLayout'
import { WelcomeStep } from './WelcomeStep'
import { AccountStep } from './AccountStep'
import { ModeSelectStep } from './ModeSelectStep'
import { SttSetupStep } from './SttSetupStep'
import { LlmSetupStep } from './LlmSetupStep'
import { QuickTestStep } from './QuickTestStep'
import { DoneStep } from './DoneStep'
import { slideRight } from '../../lib/animations'

const TOTAL_STEPS = 7

export function Onboarding() {
  const { t } = useTranslation()
  const step = useAppStore((s) => s.onboardingStep)
  const setStep = useAppStore((s) => s.setOnboardingStep)
  const setOnboardingCompleted = useAppStore((s) => s.setOnboardingCompleted)
  const sttTestStatus = useAppStore((s) => s.sttTestStatus)
  const llmTestStatus = useAppStore((s) => s.llmTestStatus)
  const onboardingMode = useAppStore((s) => s.onboardingMode)
  const setOnboardingMode = useAppStore((s) => s.setOnboardingMode)
  const updateConfig = useAppStore((s) => s.updateConfig)
  const user = useAuthStore((s) => s.user)

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
        return llmTestStatus === 'success' // LLM must pass (BYOK only)
      case 5:
        return true // Quick test — optional
      case 6:
        return true // Done
      default:
        return false
    }
  })()

  const titles = [
    { title: t('onboarding.steps.welcome'), subtitle: t('onboarding.steps.welcomeSub') },
    { title: t('onboarding.steps.signIn'), subtitle: t('onboarding.steps.signInSub') },
    { title: t('onboarding.steps.chooseMode'), subtitle: t('onboarding.steps.chooseModeSub') },
    {
      title: t('onboarding.steps.speechRecognition'),
      subtitle: t('onboarding.steps.speechRecognitionSub'),
    },
    { title: t('onboarding.steps.aiPolish'), subtitle: t('onboarding.steps.aiPolishSub') },
    { title: t('onboarding.steps.howItWorks'), subtitle: t('onboarding.steps.howItWorksSub') },
    { title: t('onboarding.steps.setupComplete'), subtitle: undefined },
  ]

  const config = useAppStore((s) => s.config)

  const handleNext = async () => {
    if (step < TOTAL_STEPS - 1) {
      // Cloud mode: set providers BEFORE saving, then skip STT/LLM setup
      if (step === 2 && onboardingMode === 'cloud') {
        updateConfig({ stt_provider: 'cloud', llm_provider: 'cloud' })
        try {
          await saveConfig({ ...config, stt_provider: 'cloud', llm_provider: 'cloud' })
        } catch {
          // Best-effort save
        }
        setStep(5)
        return
      }

      try {
        await saveConfig(config)
      } catch {
        // Best-effort save — continue navigation even if save fails
      }

      setStep(step + 1)
    } else {
      await saveConfig(config)
      await saveOnboardingCompleted()
      setOnboardingCompleted(true)
    }
  }

  const handleBack = async () => {
    if (step > 0) {
      try {
        await saveConfig(config)
      } catch {
        // Best-effort save
      }

      // If coming back from Quick Test in cloud mode, go back to Mode Select (step 2)
      if (step === 5 && onboardingMode === 'cloud') {
        setStep(2)
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
    if (step === 1) {
      // Skip login → go straight to BYOK STT setup
      setOnboardingMode('byok')
      try {
        await saveConfig(config)
      } catch {
        // Best-effort save
      }
      setStep(3)
      return
    }
    // Original behavior for other steps — skip entire onboarding
    await saveConfig(config)
    await saveOnboardingCompleted()
    setOnboardingCompleted(true)
  }

  return (
    <OnboardingLayout
      step={step}
      totalSteps={TOTAL_STEPS}
      title={titles[step].title}
      subtitle={titles[step].subtitle}
      canNext={canNext}
      canBack={step > 0}
      nextLabel={
        step === TOTAL_STEPS - 1 ? t('onboarding.steps.getStarted') : t('onboarding.layout.next')
      }
      onNext={handleNext}
      onBack={handleBack}
      onSkip={handleSkip}
    >
      <AnimatePresence mode="wait">
        <motion.div
          key={step}
          variants={slideRight}
          initial="initial"
          animate="animate"
          exit="exit"
          transition={{ duration: 0.2 }}
        >
          {step === 0 && <WelcomeStep />}
          {step === 1 && <AccountStep />}
          {step === 2 && <ModeSelectStep />}
          {step === 3 && <SttSetupStep />}
          {step === 4 && <LlmSetupStep />}
          {step === 5 && <QuickTestStep />}
          {step === 6 && <DoneStep />}
        </motion.div>
      </AnimatePresence>
    </OnboardingLayout>
  )
}
