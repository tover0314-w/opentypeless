import { motion } from 'framer-motion'
import { Cloud, Key, Mic, Bot, Sparkles, Infinity as InfinityIcon, Layers } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../../stores/appStore'
import { spring } from '../../lib/animations'

export function ModeSelectStep() {
  const { t } = useTranslation()
  const onboardingMode = useAppStore((s) => s.onboardingMode)
  const setOnboardingMode = useAppStore((s) => s.setOnboardingMode)

  return (
    <div className="space-y-3 py-2">
      {/* Cloud card */}
      <motion.button
        onClick={() => setOnboardingMode('cloud')}
        whileHover={{ scale: 1.02 }}
        whileTap={{ scaleX: 1.03, scaleY: 0.97 }}
        transition={spring.jellyGentle}
        className={`w-full text-left p-4 rounded-[10px] border cursor-pointer transition-all ${
          onboardingMode === 'cloud'
            ? 'bg-accent/10 border-accent'
            : 'bg-bg-secondary border-border hover:border-text-tertiary'
        }`}
      >
        <div className="flex items-start gap-3">
          <div
            className={`mt-0.5 p-1.5 rounded-[8px] shrink-0 ${
              onboardingMode === 'cloud'
                ? 'bg-accent/15 text-accent'
                : 'bg-bg-tertiary text-text-tertiary'
            }`}
          >
            <Cloud size={18} />
          </div>
          <div className="space-y-1.5">
            <div className="flex items-center gap-2">
              <span className="text-[14px] font-medium text-text-primary">
                {t('onboarding.mode.cloud')}
              </span>
              <span className="text-[11px] text-accent font-medium bg-accent/10 px-1.5 py-0.5 rounded-full">
                {t('onboarding.mode.recommended')}
              </span>
            </div>
            <p className="text-[13px] text-text-secondary">{t('onboarding.mode.zeroConfig')}</p>
            <div className="flex flex-col gap-0.5">
              <Detail icon={Mic} text={t('onboarding.mode.cloudVoice')} />
              <Detail icon={Bot} text={t('onboarding.mode.cloudTokens')} />
              <Detail icon={Sparkles} text={t('onboarding.mode.oneTimeCredit')} />
            </div>
          </div>
        </div>
      </motion.button>

      {/* BYOK card */}
      <motion.button
        onClick={() => setOnboardingMode('byok')}
        whileHover={{ scale: 1.02 }}
        whileTap={{ scaleX: 1.03, scaleY: 0.97 }}
        transition={spring.jellyGentle}
        className={`w-full text-left p-4 rounded-[10px] border cursor-pointer transition-all ${
          onboardingMode === 'byok'
            ? 'bg-accent/10 border-accent'
            : 'bg-bg-secondary border-border hover:border-text-tertiary'
        }`}
      >
        <div className="flex items-start gap-3">
          <div
            className={`mt-0.5 p-1.5 rounded-[8px] shrink-0 ${
              onboardingMode === 'byok'
                ? 'bg-accent/15 text-accent'
                : 'bg-bg-tertiary text-text-tertiary'
            }`}
          >
            <Key size={18} />
          </div>
          <div className="space-y-1.5">
            <span className="text-[14px] font-medium text-text-primary">
              {t('onboarding.mode.byok')}
            </span>
            <p className="text-[13px] text-text-secondary">{t('onboarding.mode.byokDesc')}</p>
            <div className="flex flex-col gap-0.5">
              <Detail icon={InfinityIcon} text={t('onboarding.mode.unlimitedUsage')} />
              <Detail icon={Layers} text={t('onboarding.mode.providersSupported')} />
            </div>
          </div>
        </div>
      </motion.button>
    </div>
  )
}

function Detail({
  icon: Icon,
  text,
}: {
  icon: React.ComponentType<{ size?: number }>
  text: string
}) {
  return (
    <p className="flex items-center gap-1.5 text-[12px] text-text-tertiary">
      <Icon size={12} />
      {text}
    </p>
  )
}
