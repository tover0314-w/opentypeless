import { useTranslation } from 'react-i18next'
import { motion } from 'framer-motion'
import {
  Check,
  Keyboard,
  MessageCircleQuestion,
  GripHorizontal,
  LayoutGrid,
  Languages,
} from 'lucide-react'
import { useAppStore } from '../../stores/appStore'
import { Keycaps } from '../shared/Keycaps'
import { hotkeyFromBinding } from '../../stores/appStore'

export function DoneStep() {
  const { t } = useTranslation()
  const config = useAppStore((s) => s.config)
  const dictationAction =
    config.hotkey_mode === 'hold' ? t('onboarding.test.hold') : t('onboarding.test.press')

  return (
    <div className="flex h-full flex-col items-center justify-center gap-5 py-2">
      {/* Success animation */}
      <motion.div
        className="w-16 h-16 rounded-full bg-success/10 flex items-center justify-center"
        initial={{ scale: 0 }}
        animate={{ scale: 1 }}
        transition={{ type: 'spring', stiffness: 500, damping: 20 }}
      >
        <motion.div
          initial={{ opacity: 0, scale: 0 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ delay: 0.2, type: 'spring', stiffness: 500, damping: 20 }}
        >
          <Check size={28} className="text-success" />
        </motion.div>
      </motion.div>

      <div className="text-center">
        <h1 className="text-[36px] leading-[40px] font-semibold text-text-primary">
          {t('onboarding.done.title')}
        </h1>
        <p className="text-[13px] text-text-secondary mt-1">
          {t('onboarding.done.capsuleAppearsWhenRecording')}
        </p>
      </div>

      {/* Usage tips */}
      <div
        data-shortcut-summary
        className="grid w-full max-w-[660px] grid-cols-1 gap-5 sm:grid-cols-2"
      >
        <Tip
          className="sm:col-span-2 sm:mx-auto sm:w-[320px]"
          icon={Keyboard}
          title={
            <>
              {dictationAction} <Keycaps hotkey={config.hotkey} />
            </>
          }
          desc={t('onboarding.done.holdPressSub')}
        />
        {config.hotkeys?.translate && (
          <Tip
            icon={Languages}
            title={
              <>
                {t('home.modes.translate.title')}{' '}
                <Keycaps hotkey={hotkeyFromBinding(config.hotkeys.translate)} />
              </>
            }
            desc={t('home.modes.translate.description')}
          />
        )}
        {config.ask_hotkey && (
          <Tip
            icon={MessageCircleQuestion}
            title={
              <>
                {t('onboarding.done.askAnything')} <Keycaps hotkey={config.ask_hotkey} />
              </>
            }
            desc={t('onboarding.done.askAnythingSub')}
          />
        )}
      </div>
      <div className="grid w-full max-w-[760px] grid-cols-1 gap-3 sm:grid-cols-2">
        <Tip
          icon={GripHorizontal}
          title={t('onboarding.done.dragToReposition')}
          desc={t('onboarding.done.dragToRepositionSub')}
        />
        <Tip
          icon={LayoutGrid}
          title={t('onboarding.done.appWritingModes')}
          desc={t('onboarding.done.appWritingModesSub')}
        />
      </div>
    </div>
  )
}

function Tip({
  icon: Icon,
  title,
  desc,
  className = '',
}: {
  icon: React.ComponentType<{ size?: number; className?: string }>
  title: React.ReactNode
  desc: string
  className?: string
}) {
  return (
    <div
      className={`flex items-center gap-3 px-3 py-2.5 bg-bg-secondary rounded-[16px] ${className}`}
    >
      <div className="p-1.5 rounded-[8px] bg-bg-tertiary text-text-tertiary shrink-0">
        <Icon size={14} />
      </div>
      <div>
        <p className="flex flex-wrap items-center gap-1 text-[13px] font-medium text-text-primary">
          {title}
        </p>
        <p className="text-[11px] text-text-tertiary">{desc}</p>
      </div>
    </div>
  )
}
