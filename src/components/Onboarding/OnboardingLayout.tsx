import { ChevronRight, X } from 'lucide-react'
import { motion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { spring } from '../../lib/animations'
import { OnboardingIllustration } from './OnboardingIllustration'
import { ONBOARDING_STAGES, stageForStep } from './stages'

interface Props {
  step: number
  totalSteps: number
  title: string
  subtitle?: string
  canNext: boolean
  canBack: boolean
  busy?: boolean
  nextLabel?: string
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
  onNext,
  onBack,
  onSkip,
  onClose,
  children,
}: Props) {
  const { t } = useTranslation()
  const stage = stageForStep(step)
  const progress = Math.round(((step + 1) / totalSteps) * 100)

  return (
    <div aria-busy={busy} className="flex h-full w-full flex-col bg-bg-primary">
      <header className="relative shrink-0" data-tauri-drag-region>
        <div className="flex h-14 items-center justify-between pl-24 pr-4" data-tauri-drag-region>
          <nav
            aria-label={t('onboarding.layout.progress')}
            className="absolute left-1/2 flex -translate-x-1/2 items-center gap-4 text-[15px]"
          >
            {ONBOARDING_STAGES.map((item, index) => (
              <span key={item} className="flex items-center gap-4">
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
            className="h-full bg-text-primary"
            initial={false}
            animate={{ width: `${progress}%` }}
            transition={{ duration: 0.3, ease: 'easeOut' }}
          />
        </div>
      </header>

      <div className="flex min-h-0 flex-1">
        <section className="flex min-w-0 flex-1 flex-col">
          <div className="flex-1 overflow-y-auto px-14 pb-6 pt-12">
            <div className="mx-auto max-w-[460px]">
              <h1 className="m-0 text-[30px] font-bold leading-[38px] text-text-primary">
                {title}
              </h1>
              {subtitle && (
                <p className="m-0 mt-3 text-[15px] leading-6 text-text-secondary">{subtitle}</p>
              )}
              <div className="mt-8">{children}</div>
            </div>
          </div>

          <div className="flex items-center justify-between px-14 py-5">
            <motion.button
              type="button"
              onClick={onBack}
              disabled={!canBack}
              whileHover={canBack ? { scale: 1.04 } : undefined}
              whileTap={canBack ? { scaleX: 1.06, scaleY: 0.94 } : undefined}
              transition={spring.jellyGentle}
              className="rounded-full border border-border bg-transparent px-5 py-2 text-[14px] text-text-secondary cursor-pointer transition-colors hover:text-text-primary disabled:cursor-default disabled:opacity-0"
            >
              {t('onboarding.layout.back')}
            </motion.button>
            <motion.button
              type="button"
              onClick={onNext}
              disabled={!canNext}
              whileHover={canNext ? { scale: 1.04 } : undefined}
              whileTap={canNext ? { scaleX: 1.06, scaleY: 0.94 } : undefined}
              transition={spring.jellyGentle}
              className="btn-primary px-6 py-2 text-[14px] font-medium cursor-pointer disabled:cursor-not-allowed disabled:opacity-40"
            >
              {nextLabel}
            </motion.button>
          </div>
        </section>

        <aside
          aria-hidden="true"
          className="hidden w-[45%] shrink-0 items-center justify-center bg-bg-secondary px-10 lg:flex"
        >
          <OnboardingIllustration stage={stage} step={step} />
        </aside>
      </div>
    </div>
  )
}
