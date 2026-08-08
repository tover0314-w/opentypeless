import { AnimatePresence, motion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { slideRight } from '../../lib/animations'
import { saveOnboardingCompleted, updateConfig as saveConfig } from '../../lib/tauri'
import { useAppStore } from '../../stores/appStore'
import { DoneStep } from './DoneStep'
import { LlmSetupStep } from './LlmSetupStep'
import { OnboardingLayout } from './OnboardingLayout'
import { PermissionsStep } from './PermissionsStep'
import { QuickTestStep } from './QuickTestStep'
import { SttSetupStep } from './SttSetupStep'
import { WelcomeStep } from './WelcomeStep'

const TOTAL_STEPS = 6

export function Onboarding() {
  const { t } = useTranslation()
  const step = useAppStore((state) => state.onboardingStep)
  const setStep = useAppStore((state) => state.setOnboardingStep)
  const setOnboardingCompleted = useAppStore((state) => state.setOnboardingCompleted)
  const sttTestStatus = useAppStore((state) => state.sttTestStatus)
  const llmTestStatus = useAppStore((state) => state.llmTestStatus)
  const config = useAppStore((state) => state.config)

  const canNext =
    step === 1 ? sttTestStatus === 'success' : step === 2 ? llmTestStatus === 'success' : true

  const titles = [
    { title: t('onboarding.steps.welcome'), subtitle: t('onboarding.steps.welcomeSub') },
    {
      title: t('onboarding.steps.speechRecognition'),
      subtitle: t('onboarding.steps.speechRecognitionSub'),
    },
    { title: t('onboarding.steps.aiPolish'), subtitle: t('onboarding.steps.aiPolishSub') },
    { title: t('onboarding.steps.permissions'), subtitle: t('onboarding.steps.permissionsSub') },
    { title: t('onboarding.steps.howItWorks'), subtitle: t('onboarding.steps.howItWorksSub') },
    { title: t('onboarding.steps.setupComplete'), subtitle: undefined },
  ]

  const persistConfig = async () => {
    try {
      await saveConfig(config)
    } catch {
      // Navigation remains available when persistence temporarily fails.
    }
  }

  const handleNext = async () => {
    if (step < TOTAL_STEPS - 1) {
      await persistConfig()
      setStep(step + 1)
      return
    }

    await saveConfig(config)
    await saveOnboardingCompleted()
    setOnboardingCompleted(true)
  }

  const handleBack = async () => {
    if (step === 0) return
    await persistConfig()
    setStep(step - 1)
  }

  const handleSkip = async () => {
    try {
      await saveConfig(config)
      await saveOnboardingCompleted()
    } catch {
      // The user can configure BYOK providers later from Settings.
    }
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
          {step === 1 && <SttSetupStep />}
          {step === 2 && <LlmSetupStep />}
          {step === 3 && <PermissionsStep />}
          {step === 4 && <QuickTestStep />}
          {step === 5 && <DoneStep />}
        </motion.div>
      </AnimatePresence>
    </OnboardingLayout>
  )
}
