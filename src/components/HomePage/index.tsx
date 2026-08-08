import { History, Mic, Settings } from 'lucide-react'
import { motion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { spring } from '../../lib/animations'
import { useRoute } from '../../lib/router'
import { useAppStore } from '../../stores/appStore'

export function HomePage() {
  const config = useAppStore((state) => state.config)
  const history = useAppStore((state) => state.history)
  const { navigate } = useRoute()
  const { t } = useTranslation()

  const now = new Date()
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  const todayCount = history.filter((entry) => entry.created_at.startsWith(today)).length

  return (
    <div className="p-6 space-y-6">
      <div className="rounded-[18px] p-5 jelly-card">
        <div className="flex items-center gap-3 mb-2">
          <div
            className="w-9 h-9 rounded-[10px] flex items-center justify-center"
            style={{
              background: 'linear-gradient(145deg, rgba(42,187,167,0.15), rgba(42,187,167,0.08))',
            }}
          >
            <Mic size={18} className="text-text-secondary" />
          </div>
          <h2 className="text-[17px] font-semibold">{t('home.welcome')}</h2>
        </div>
        <p className="text-[13px] text-text-secondary leading-relaxed">
          {t('home.description', { hotkey: config.hotkey })}
        </p>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <StatCard label={t('home.totalRecordings')} value={history.length} />
        <StatCard label={t('home.today')} value={todayCount} />
      </div>

      <div className="rounded-[18px] p-5 jelly-card">
        <div className="flex items-center justify-between gap-3 mb-3">
          <h3 className="text-[13px] font-medium">{t('home.currentConfig')}</h3>
          <span className="rounded-full bg-accent/10 px-2 py-1 text-[11px] font-medium text-accent">
            BYOK
          </span>
        </div>
        <div className="space-y-2 text-[13px]">
          <ConfigRow label={t('home.sttProvider')} value={config.stt_provider} />
          <ConfigRow label={t('home.llmProvider')} value={config.llm_provider} />
          <ConfigRow
            label={t('home.aiPolish')}
            value={config.polish_enabled ? t('home.enabled') : t('home.disabled')}
          />
          <ConfigRow label={t('home.outputMode')} value={config.output_mode} />
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <QuickAction
          icon={Settings}
          label={t('nav.settings')}
          onClick={() => navigate('settings')}
        />
        <QuickAction icon={History} label={t('nav.history')} onClick={() => navigate('history')} />
      </div>
    </div>
  )
}

function StatCard({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-[18px] p-4 jelly-card">
      <p className="text-[11px] text-text-tertiary uppercase tracking-wider mb-1">{label}</p>
      <p className="text-[22px] font-semibold">{value}</p>
    </div>
  )
}

function ConfigRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <span className="text-text-secondary">{label}</span>
      <span className="text-text-primary font-medium truncate">{value}</span>
    </div>
  )
}

function QuickAction({
  icon: Icon,
  label,
  onClick,
}: {
  icon: typeof Settings
  label: string
  onClick: () => void
}) {
  return (
    <motion.button
      onClick={onClick}
      whileHover={{ scale: 1.04 }}
      whileTap={{ scaleX: 1.06, scaleY: 0.94 }}
      transition={spring.jellyGentle}
      className="flex items-center gap-2.5 rounded-[14px] p-4 cursor-pointer text-left jelly-btn"
    >
      <Icon size={16} className="text-text-secondary" />
      <span className="text-[13px] font-medium">{label}</span>
    </motion.button>
  )
}
