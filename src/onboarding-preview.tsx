import { useCallback, useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { AnimatePresence, MotionConfig, motion } from 'framer-motion'
import i18n from './i18n'
import './styles/globals.css'
import { useAppStore } from './stores/appStore'
import { UI_LANGUAGES } from './lib/constants'
import { slideRight } from './lib/animations'
import { OnboardingLayout } from './components/Onboarding/OnboardingLayout'
import { WelcomeStep } from './components/Onboarding/WelcomeStep'
import { AccountStep } from './components/Onboarding/AccountStep'
import { ModeSelectStep } from './components/Onboarding/ModeSelectStep'
import { SttSetupStep } from './components/Onboarding/SttSetupStep'
import { LlmSetupStep } from './components/Onboarding/LlmSetupStep'
import { PermissionsStep } from './components/Onboarding/PermissionsStep'
import { QuickTestStep } from './components/Onboarding/QuickTestStep'
import { DoneStep } from './components/Onboarding/DoneStep'
import { ModeIntroduction } from './components/Onboarding/ModeIntroduction'
import { GuidedPracticeFlow, type PracticePage } from './components/Onboarding/GuidedPracticeFlow'
import type { OnboardingStage } from './components/Onboarding/stages'
import { usePreviewLanguage } from './hooks/usePreviewLanguage'
import { AskAnswerPreview } from './components/AskPanel/AskAnswerPreview'
import { HomeLayoutPreview } from './components/HomePage/HomeLayoutPreview'

const steps = [
  { label: '欢迎', title: 'welcome', component: WelcomeStep },
  { label: '账户', title: 'signIn', component: AccountStep },
  { label: '使用方式', title: 'chooseMode', component: ModeSelectStep },
  { label: '语音模型', title: 'speechRecognition', component: SttSetupStep },
  { label: '润色模型', title: 'aiPolish', component: LlmSetupStep },
  { label: '权限', title: 'permissions', component: PermissionsStep },
  { label: '三模式练习', title: 'howItWorks', component: QuickTestStep },
  { label: '完成汇总', title: 'setupComplete', component: DoneStep },
]

const practicePages: Array<{ label: string; page: PracticePage }> = [
  { label: '麦克风', page: { mode: 'dictate', stage: 'microphone' } },
  ...(['dictate', 'translate', 'ask'] as const).flatMap((mode) => [
    { label: `${mode} · 介绍`, page: { mode, stage: 'introduction' as const } },
    { label: `${mode} · 快捷键`, page: { mode, stage: 'shortcut' as const } },
    ...(mode === 'translate'
      ? [{ label: 'translate · 语言设置', page: { mode, stage: 'language' as const } }]
      : []),
    ...Array.from({ length: mode === 'translate' ? 1 : 4 }, (_, exampleIndex) => ({
      label: `${mode} · 练习 ${exampleIndex + 1}`,
      page: { mode, stage: 'exercise' as const, exampleIndex },
    })),
  ]),
]

export function Preview() {
  const [step, setStep] = useState(0)
  const [showAnswerPreview, setShowAnswerPreview] = useState(false)
  const [showHomePreview, setShowHomePreview] = useState(false)
  const { language, changeLanguage, t } = usePreviewLanguage()
  const [dark, setDark] = useState(false)
  const [windowSize, setWindowSize] = useState('1080x750')
  const [notice, setNotice] = useState('')
  const [freePractice, setFreePractice] = useState(false)
  const [practiceCompleted, setPracticeCompleted] = useState(false)
  const [practiceLayout, setPracticeLayout] = useState<'split' | 'fullWidth'>('fullWidth')
  const [practiceSurface, setPracticeSurface] = useState<React.ReactNode>(null)
  const [practiceProgressValue, setPracticeProgressValue] = useState(50)
  const [practiceStage, setPracticeStage] = useState<OnboardingStage>('setUp')
  const [previewPage, setPreviewPage] = useState(0)
  const practiceBack = useRef<(() => void) | null>(null)
  const registerPracticeBack = useCallback((handler: (() => void) | null) => {
    practiceBack.current = handler
  }, [])
  const [introduction, setIntroduction] = useState<'dictate' | 'translate' | 'ask' | null>(null)
  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark)
  }, [dark])
  const selected = steps[step]
  const Component = selected.component
  const navigate = (next: number) => {
    setShowHomePreview(false)
    setShowAnswerPreview(false)
    setStep(Math.max(0, Math.min(7, next)))
    setPracticeCompleted(false)
    setIntroduction(null)
    setNotice('')
  }
  return (
    <div className={`${dark ? 'dark' : ''} min-h-screen bg-bg-secondary text-text-primary`}>
      <header className="flex flex-wrap items-center gap-3 border-b border-border bg-bg-primary px-5 py-3">
        <strong className="text-[14px]">OpenTypeless · 真实组件预览</strong>
        <span className="text-[12px] text-text-secondary">
          真实组件与动画 · 仅本地预览 · 登录、录音、连接测试已拦截
        </span>
        <select
          aria-label="预览语言"
          value={language}
          onChange={async (event) => {
            const nextLanguage = event.target.value
            await changeLanguage(nextLanguage)
          }}
          className="field-control w-auto"
        >
          {UI_LANGUAGES.map((item) => (
            <option key={item.value} value={item.value}>
              {item.label}
            </option>
          ))}
        </select>
        <button className="btn-secondary px-3 py-2 text-[12px]" onClick={() => setDark(!dark)}>
          {dark ? '浅色' : '深色'}
        </button>
        <select
          aria-label="桌面窗口尺寸"
          className="field-control w-auto"
          value={windowSize}
          onChange={(event) => setWindowSize(event.target.value)}
        >
          <option value="1080x750">默认窗口 · 1080 × 750</option>
          <option value="988x685">最小窗口 · 988 × 685</option>
        </select>
      </header>
      <nav aria-label="预览步骤" className="flex flex-wrap gap-2 px-5 py-3">
        <button
          type="button"
          className="btn-secondary px-3 py-2 text-[12px]"
          onClick={() => {
            setShowHomePreview(true)
            setShowAnswerPreview(false)
          }}
        >
          Isolated home preview
        </button>
        <button
          type="button"
          className="btn-secondary px-3 py-2 text-[12px]"
          onClick={() => {
            setShowHomePreview(false)
            setShowAnswerPreview(true)
          }}
        >
          Synthetic answer preview
        </button>
        {steps.map((item, index) => (
          <button
            key={item.label}
            aria-current={step === index ? 'step' : undefined}
            className={`rounded-lg px-3 py-2 text-[12px] ${step === index ? 'bg-accent text-white' : 'bg-bg-primary'}`}
            onClick={() => navigate(index)}
          >
            {index + 1}. {item.label}
          </button>
        ))}
      </nav>
      <div className="flex flex-wrap items-center gap-2 px-5 pb-3">
        <span className="text-[12px] text-text-secondary">REA 结构重做：介绍页</span>
        {(['dictate', 'translate', 'ask'] as const).map((mode) => (
          <button
            key={mode}
            className="btn-secondary px-3 py-2 text-[12px]"
            onClick={() => {
              setStep(6)
              setIntroduction(mode)
            }}
          >
            {t(`home.modes.${mode}.title`)}
          </button>
        ))}
        <span className="text-[12px] text-text-tertiary">
          原版结构已取证；背景、文案为自有实现，尚未接入完整流程。
        </span>
      </div>
      {step === 6 && (
        <div className="px-5 pb-3">
          <label className="mb-3 flex items-center gap-3 text-[12px]">
            预览子页（只切换页面，不代表验收通过）
            <select
              aria-label="预览子页"
              className="field-control w-auto"
              value={previewPage}
              onChange={(event) => {
                setPreviewPage(Number(event.target.value))
                setIntroduction(null)
                setFreePractice(false)
                setPracticeCompleted(false)
              }}
            >
              {practicePages.map((item, index) => (
                <option key={item.label} value={index}>
                  {item.label}
                </option>
              ))}
            </select>
          </label>
          <button
            className="btn-secondary px-3 py-2 text-[12px]"
            onClick={() => setFreePractice(!freePractice)}
          >
            {freePractice ? '返回真实首次引导' : '预览工具：分别查看三种练习'}
          </button>
          <span className="ml-3 text-[12px] text-text-secondary">
            {freePractice
              ? '自由切换仅为预览工具，不是首次引导的标签导航。'
              : '首次引导依次口述、翻译、Ask；未完成练习不会解锁。'}
          </span>
        </div>
      )}
      <main className="mx-auto max-w-[1180px] px-5 pb-5">
        {showHomePreview && <HomeLayoutPreview />}
        {showAnswerPreview && <AskAnswerPreview />}
        <div
          hidden={showAnswerPreview || showHomePreview}
          data-preview-window
          className="box-content overflow-hidden rounded-xl border border-border shadow-lg"
          style={{
            width: Number(windowSize.split('x')[0]),
            height: Number(windowSize.split('x')[1]),
          }}
        >
          <OnboardingLayout
            step={step}
            totalSteps={steps.length}
            title={t(`onboarding.steps.${selected.title}`)}
            rightContent={step === 6 && !freePractice ? practiceSurface : undefined}
            layout={
              step === 0
                ? 'fullWidth'
                : step === 1
                  ? 'accountCard'
                  : introduction || step === 7
                    ? 'fullWidth'
                    : step === 6 && !freePractice
                      ? practiceLayout
                      : 'split'
            }
            subtitle={step === 7 ? undefined : t(`onboarding.steps.${selected.title}Sub`)}
            canNext={!introduction && (step !== 6 || practiceCompleted)}
            canBack={step > 0}
            nextLabel={step === 7 ? t('onboarding.steps.getStarted') : t('onboarding.layout.next')}
            progressValue={step === 6 && !freePractice ? practiceProgressValue : undefined}
            activeStage={step === 6 && !freePractice ? practiceStage : undefined}
            showNextButton={!introduction && (step !== 6 || practiceCompleted || freePractice)}
            showHeading={step !== 0 && (step !== 6 || freePractice)}
            onNext={() => {
              if (step === 7) {
                setNotice('这是完成页按钮预览；不会保存引导状态或启动桌面应用。')
              } else navigate(step + 1)
            }}
            onBack={() => {
              if (step === 6 && !freePractice && !introduction && practiceBack.current) {
                practiceBack.current()
              } else navigate(step - 1)
            }}
            onSkip={() => navigate(step + 1)}
            onClose={() => setNotice('预览保留打开，不会退出桌面应用。')}
          >
            <div
              onSubmitCapture={(event) => {
                event.preventDefault()
                event.stopPropagation()
                setNotice('预览不会登录、保存或发送任何请求。')
              }}
              onClickCapture={(event) => {
                const button = (event.target as HTMLElement).closest('button')
                if (!button || step === 0 || step === 2 || button.getAttribute('role') === 'tab')
                  return
                if (introduction) return
                if (
                  step === 6 &&
                  !freePractice &&
                  button.textContent === t('onboarding.layout.next')
                )
                  return
                if (
                  button.getAttribute('role') === 'switch' ||
                  button.getAttribute('role') === 'option'
                )
                  return
                if (button.getAttribute('aria-haspopup') === 'listbox') return
                event.preventDefault()
                event.stopPropagation()
                setNotice('该操作需要桌面服务；预览已拦截，不录音、不调用模型、不登录。')
              }}
            >
              <AnimatePresence mode="wait">
                <motion.div
                  key={`${step}-${introduction ?? 'step'}`}
                  variants={slideRight}
                  initial="initial"
                  animate="animate"
                  exit="exit"
                  transition={{ duration: 0.2 }}
                  className="h-full w-full"
                >
                  {introduction ? (
                    <ModeIntroduction
                      mode={introduction}
                      onContinue={() => setIntroduction(null)}
                    />
                  ) : step === 6 ? (
                    freePractice ? (
                      <QuickTestStep />
                    ) : (
                      <GuidedPracticeFlow
                        key={previewPage}
                        initialPage={practicePages[previewPage].page}
                        onComplete={() => setPracticeCompleted(true)}
                        onReset={() => setPracticeCompleted(false)}
                        onLayoutChange={setPracticeLayout}
                        onSurfaceChange={setPracticeSurface}
                        onProgressChange={setPracticeProgressValue}
                        onStageChange={setPracticeStage}
                        onBackChange={registerPracticeBack}
                        onExitBack={() => navigate(step - 1)}
                        onConfigureShortcut={() =>
                          setNotice(
                            '快捷键设置仅在桌面应用中操作；预览不会打开实时设置或保存配置。',
                          )
                        }
                      />
                    )
                  ) : (
                    <Component />
                  )}
                </motion.div>
              </AnimatePresence>
            </div>
          </OnboardingLayout>
        </div>
        <p role="status" className="mt-3 text-[12px] text-text-secondary">
          {notice || '上方是预览导航；框内复用真实页面、控件、进度动画与完成动画。练习结果未模拟。'}
        </p>
      </main>
    </div>
  )
}

useAppStore.getState().updateConfig({
  ui_language: 'zh',
  stt_provider: 'custom-whisper',
  hotkey: 'Fn',
  ask_hotkey: 'Fn+Space',
})
useAppStore.getState().setOnboardingMode('byok')
await i18n.changeLanguage('zh')
createRoot(document.getElementById('root')!).render(
  <MotionConfig reducedMotion="user">
    <Preview />
  </MotionConfig>,
)
