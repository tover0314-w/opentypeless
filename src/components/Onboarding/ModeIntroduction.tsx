import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'
import type { PracticeOptions } from '../../lib/tauri'

export function ModeIntroduction({
  mode,
  onContinue,
}: {
  mode: PracticeOptions['mode']
  onContinue: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="-mt-[58px] flex h-full min-h-[360px] flex-col items-center justify-center gap-6 text-center">
      <h1
        className={`m-0 font-semibold leading-[40px] text-text-primary ${
          mode === 'dictate' ? 'text-[48px]' : 'text-[36px]'
        }`}
      >
        {t(`home.modes.${mode}.title`)}
      </h1>
      <p className="m-0 max-w-[560px] text-[16px] leading-6 text-text-secondary">
        {t(`home.modes.${mode}.description`)}
      </p>
      <motion.button
        type="button"
        onClick={onContinue}
        whileHover={{ scale: 1.02 }}
        whileTap={{ scale: 0.98 }}
        className="btn-primary rounded-full px-4 py-2 text-[16px] font-normal leading-6"
      >
        {t('onboarding.layout.next')}
      </motion.button>
    </div>
  )
}
