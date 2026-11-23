import { ChevronRight, X } from 'lucide-react'
import { motion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { spring } from '../../lib/animations'
import { OnboardingIllustration } from './OnboardingIllustration'
import { ONBOARDING_STAGES, stageForStep, type OnboardingStage } from './stages'

const radialBackground =
  'radial-gradient(71.87% 50% at 50% 50%, var(--color-accent-glow) 0%, transparent 65.32%), var(--color-bg-bottom)'

interface Props {
  step: number
  totalSteps: number
  title: string
  subtitle?: string
  canNext: boolean
  canBack: boolean
  busy?: boolean
  nextLabel?: string
  layout?: 'split' | 'fullWidth' | 'accountCard'
  rightContent?: React.ReactNode
  showNextButton?: boolean
  progressValue?: number
  activeStage?: OnboardingStage
  showHeading?: boolean
  onNext: () => void
  onBack: () => void
  onSkip?: () => void
  onClose: () => void
  children: React.ReactNode
}

export function OnboardingLayout({
  step,
  totalSteps,
  title,
  subtitle,
  canNext,
  canBack,
  busy = false,
  nextLabel,
  layout = 'split',
  rightContent,
  showNextButton = true,
  progressValue,
  activeStage,
  showHeading = true,
  onNext,
  onBack,
  onSkip,
  onClose,
  children,
}: Props) {
  const { t } = useTranslation()
  const stage = activeStage ?? stageForStep(step)
  const requestedProgress = progressValue ?? Math.round(((step + 1) / totalSteps) * 100)
  const progress = Number.isFinite(requestedProgress)
    ? Math.min(100, Math.max(0, requestedProgress))
    : 0

  return (
    <div
      aria-busy={busy}
      className="onboarding-fonts @container flex h-full w-full flex-col bg-bg-primary"
    >
      <header className="relative shrink-0" data-tauri-drag-region>
        <div className="flex h-14 items-center justify-between pl-24 pr-4" data-tauri-drag-region>
          <nav
            aria-label={t('onboarding.layout.progress')}
            className="absolute left-1/2 flex -translate-x-1/2 items-center gap-6 text-[14px] leading-5"
          >
            {ONBOARDING_STAGES.map((item, index) => (
              <span key={item} className="flex items-center gap-6">
                {index > 0 && (
                  <ChevronRight size={16} className="text-text-tertiary" aria-hidden="true" />
                )}
                <span
                  aria-current={item === stage ? 'step' : undefined}
                  className={
                    item === stage ? 'font-medium text-text-primary' : 'text-text-tertiary'
                  }
                >
                  {t(`onboarding.stages.${item}`)}
                </span>
              </span>
            ))}
          </nav>
          <span />
          <div className="flex items-center gap-1">
            {onSkip && (
              <button
                type="button"
                onClick={onSkip}
                disabled={busy}
                className="rounded-[6px] border-none bg-transparent px-2 py-1 text-[12px] text-text-tertiary cursor-pointer transition-colors hover:text-text-primary"
              >
                {t('onboarding.layout.skip')}
              </button>
            )}
            <button
              type="button"
              onClick={onClose}
              disabled={busy}
              className="rounded-[6px] border-none bg-transparent p-1.5 text-text-tertiary cursor-pointer transition-colors hover:bg-bg-tertiary hover:text-text-primary"
              aria-label={t('onboarding.layout.close')}
            >
              <X size={14} />
            </button>
          </div>
        </div>
        <div
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={progress}
          className="h-1 w-full bg-fill-tough"
        >
          <motion.div
            className="h-full bg-gradient-to-r from-text-disabled to-text-primary"
            initial={false}
            animate={{ width: `${progress}%` }}
            transition={{ duration: 0.3, ease: 'easeInOut' }}
          />
        </div>
      </header>

      <div className="flex min-h-0 flex-1">
        <section
          style={layout === 'split' ? undefined : { background: radialBackground }}
          className={`flex min-w-0 w-full flex-col overflow-y-auto px-16 py-8 ${
            layout === 'split' ? 'onboarding-split-panel @min-[988px]:w-[55%]' : ''
          }`}
        >
          <div
            data-onboarding-text
            className={`m-auto flex h-full w-full flex-col ${
              layout === 'split'
                ? 'max-w-[600px] max-h-[630px]'
                : layout === 'accountCard'
                  ? 'max-w-[440px] max-h-[630px] rounded-2xl border border-border bg-bg-elevated px-6 py-6 shadow-lg'
                  : 'max-w-[1280px]'
            }`}
          >
            <div className="-ml-[14px] mb-6 flex h-9 shrink-0">
              <motion.button
                type="button"
                onClick={onBack}
                disabled={!canBack}
                whileHover={canBack ? { scale: 1.04 } : undefined}
                whileTap={canBack ? { scaleX: 1.06, scaleY: 0.94 } : undefined}
                transition={spring.jellyGentle}
                className="flex h-9 items-center gap-2 rounded-[18px] border-none bg-transparent px-3 py-2 text-[14px] leading-5 text-text-secondary cursor-pointer hover:text-text-primary disabled:cursor-default disabled:invisible"
              >
                <ChevronRight size={16} className="rotate-180" aria-hidden="true" />
                {t('onboarding.layout.back')}
              </motion.button>
            </div>
            <div className="min-h-0 flex-1 overflow-y-auto">
              {layout !== 'fullWidth' && showHeading && (
                <h1 className="m-0 text-[30px] font-bold leading-[38px] text-text-primary">
                  {title}
                </h1>
              )}
              {layout !== 'fullWidth' && showHeading && subtitle && (
                <p className="m-0 mt-3 text-[15px] leading-6 text-text-secondary">{subtitle}</p>
              )}
              <div className={layout !== 'fullWidth' && showHeading ? 'mt-8' : 'h-full'}>
                {children}
              </div>
            </div>
            {showNextButton && (
              <div className="mt-auto mb-8 flex shrink-0 justify-end pt-6">
                <motion.button
                  type="button"
                  onClick={onNext}
                  disabled={!canNext}
                  whileHover={canNext ? { scale: 1.04 } : undefined}
                  whileTap={canNext ? { scaleX: 1.06, scaleY: 0.94 } : undefined}
                  transition={spring.jellyGentle}
                  className="btn-primary rounded-full px-4 py-2 text-[16px] leading-6 font-normal cursor-pointer disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {nextLabel}
                </motion.button>
              </div>
            )}
          </div>
        </section>

        {layout === 'split' && (
          <aside
            style={{ background: radialBackground }}
            aria-hidden={rightContent ? undefined : true}
            className="onboarding-split-surface hidden w-[45%] shrink-0 items-center justify-center bg-bg-secondary px-10 @min-[988px]:flex"
          >
            {rightContent ?? <OnboardingIllustration stage={stage} step={step} />}
          </aside>
        )}
      </div>
    </div>
  )
}
