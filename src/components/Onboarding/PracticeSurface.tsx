import { motion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { Mail, MessageSquare, List, Languages, MessageCircleQuestion } from 'lucide-react'
import type { PracticeOptions } from '../../lib/tauri'

export function PracticeSurface({
  example,
  text,
  complete,
  mode = 'dictate',
}: {
  example: number
  text: string
  complete: boolean
  mode?: PracticeOptions['mode']
}) {
  const { t } = useTranslation()
  const Icon =
    mode === 'translate'
      ? Languages
      : mode === 'ask'
        ? MessageCircleQuestion
        : example === 0
          ? MessageSquare
          : example === 1
            ? Mail
            : List
  return (
    <div className="w-full max-w-[620px] min-w-[320px]">
      <div className="overflow-hidden rounded-xl border border-border bg-bg-elevated shadow-lg">
        <header className="flex items-center gap-2 border-b border-border px-4 py-3">
          <Icon size={18} className="text-accent" aria-hidden="true" />
          <span className="text-[14px] font-medium">
            {mode !== 'dictate'
              ? t(`home.modes.${mode}.title`)
              : example === 0
                ? t('contextFamilies.work_chat')
                : example === 1
                  ? t('contextFamilies.email')
                  : t('onboarding.test.result')}
          </span>
        </header>
        <div className="p-3">
          <textarea
            readOnly
            rows={mode === 'dictate' && example === 0 ? 6 : 10}
            aria-label={t('onboarding.test.result')}
            placeholder={t('onboarding.test.press')}
            value={text}
            className="w-full resize-none rounded-lg border border-border bg-bg-primary p-3 text-[14px] leading-6 text-text-primary outline-none"
          />
        </div>
      </div>
      {text && (
        <motion.p
          initial={{ opacity: 0, height: 0 }}
          animate={{ opacity: 1, height: 'auto' }}
          transition={{ duration: 0.5 }}
          className="mt-4 text-[14px] text-text-secondary"
        >
          {t(complete ? 'onboarding.test.result' : 'onboarding.test.polished')}
        </motion.p>
      )}
    </div>
  )
}
