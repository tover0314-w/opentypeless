import { Mic, Settings, History, Crown } from 'lucide-react'
import { motion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { spring } from '../../lib/animations'
import { useAppStore } from '../../stores/appStore'
import { useAuthStore } from '../../stores/authStore'
import { useRoute } from '../../lib/router'

export function HomePage() {
  const config = useAppStore((s) => s.config)
  const history = useAppStore((s) => s.history)
  const { navigate } = useRoute()
  const { user, plan, sttSecondsUsed, sttSecondsLimit, llmTokensUsed, llmTokensLimit } =
    useAuthStore()
  const { t } = useTranslation()
  const isPro = plan === 'pro'

  const now = new Date()
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  const todayCount = history.filter((h) => h.created_at.startsWith(today)).length

  return (
    <div className="p-6 space-y-6">
      {/* Welcome */}
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

      {/* Stats */}
      <div className="grid grid-cols-2 gap-3">
        <div className="rounded-[18px] p-4 jelly-card">
          <p className="text-[11px] text-text-tertiary uppercase tracking-wider mb-1">
            {t('home.totalRecordings')}
          </p>
          <p className="text-[22px] font-semibold">{history.length}</p>
        </div>
        <div className="rounded-[18px] p-4 jelly-card">
          <p className="text-[11px] text-text-tertiary uppercase tracking-wider mb-1">
            {t('home.today')}
          </p>
          <p className="text-[22px] font-semibold">{todayCount}</p>
        </div>
      </div>

      {/* Plan / Quota summary */}
      {user && (
        <div className="rounded-[18px] p-5 jelly-card">
          {isPro ? (
            <>
              <div className="flex items-center gap-2 mb-3">
                <Crown size={16} className="text-amber-500" />
                <h3 className="text-[13px] font-medium">{t('home.proPlan')}</h3>
              </div>
              <div className="space-y-3">
                <QuotaBar
                  label={t('upgrade.stt')}
                  used={sttSecondsUsed}
                  limit={sttSecondsLimit}
                  unit={t('account.quotaHours')}
                  divisor={3600}
                />
                <QuotaBar
                  label={t('upgrade.llm')}
                  used={llmTokensUsed}
                  limit={llmTokensLimit}
                  unit={t('account.quotaTokens')}
                  divisor={1000}
                />
              </div>
            </>
          ) : (
            <>
              <div className="flex items-center justify-between">
                <h3 className="text-[13px] font-medium">{t('home.freePlan')}</h3>
                <button
                  onClick={() => navigate('upgrade')}
                  className="text-[12px] text-accent font-medium bg-transparent border-none cursor-pointer hover:underline"
                >
                  {t('home.upgradeToPro')}
                </button>
              </div>
              {sttSecondsLimit > 0 && (
                <div className="space-y-3 mt-3">
                  <QuotaBar
                    label={t('upgrade.stt')}
                    used={sttSecondsUsed}
                    limit={sttSecondsLimit}
                    unit={t('account.quotaMin')}
                    divisor={60}
                  />
                  <QuotaBar
                    label={t('upgrade.llm')}
                    used={llmTokensUsed}
                    limit={llmTokensLimit}
                    unit={t('account.quotaTokens')}
                    divisor={1000}
                  />
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* Current config */}
      <div className="rounded-[18px] p-5 jelly-card">
        <h3 className="text-[13px] font-medium mb-3">{t('home.currentConfig')}</h3>
        <div className="space-y-2 text-[13px]">
          <div className="flex justify-between">
            <span className="text-text-secondary">{t('home.sttProvider')}</span>
            <span className="text-text-primary font-medium">{config.stt_provider}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-text-secondary">{t('home.llmProvider')}</span>
            <span className="text-text-primary font-medium">{config.llm_provider}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-text-secondary">{t('home.aiPolish')}</span>
            <span className="text-text-primary font-medium">
              {config.polish_enabled ? t('home.enabled') : t('home.disabled')}
            </span>
          </div>
          <div className="flex justify-between">
            <span className="text-text-secondary">{t('home.outputMode')}</span>
            <span className="text-text-primary font-medium">{config.output_mode}</span>
          </div>
        </div>
      </div>

      {/* Quick actions */}
      <div className="grid grid-cols-2 gap-3">
        <motion.button
          onClick={() => navigate('settings')}
          whileHover={{ scale: 1.04 }}
          whileTap={{ scaleX: 1.06, scaleY: 0.94 }}
          transition={spring.jellyGentle}
          className="flex items-center gap-2.5 rounded-[14px] p-4 cursor-pointer text-left jelly-btn"
        >
          <Settings size={16} className="text-text-secondary" />
          <span className="text-[13px] font-medium">{t('nav.settings')}</span>
        </motion.button>
        <motion.button
          onClick={() => navigate('history')}
          whileHover={{ scale: 1.04 }}
          whileTap={{ scaleX: 1.06, scaleY: 0.94 }}
          transition={spring.jellyGentle}
          className="flex items-center gap-2.5 rounded-[14px] p-4 cursor-pointer text-left jelly-btn"
        >
          <History size={16} className="text-text-secondary" />
          <span className="text-[13px] font-medium">{t('nav.history')}</span>
        </motion.button>
      </div>
    </div>
  )
}

function QuotaBar({
  label,
  used,
  limit,
  unit,
  divisor,
}: {
  label: string
  used: number
  limit: number
  unit: string
  divisor: number
}) {
  const pct = limit > 0 ? Math.min((used / limit) * 100, 100) : 0
  const usedDisplay = (used / divisor).toFixed(1)
  const limitDisplay = (limit / divisor).toFixed(1)

  return (
    <div className="space-y-1">
      <div className="flex justify-between text-[12px]">
        <span className="text-text-secondary">{label}</span>
        <span className="text-text-tertiary">
          {usedDisplay} / {limitDisplay} {unit}
        </span>
      </div>
      <div className="h-1.5 bg-bg-secondary rounded-full overflow-hidden">
        <div
          className={`h-full rounded-full transition-all ${pct > 90 ? 'bg-red-500' : 'bg-accent'}`}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  )
}
