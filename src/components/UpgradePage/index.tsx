import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, Crown, Loader2 } from 'lucide-react'
import { openUrl } from '@tauri-apps/plugin-opener'
import { useAuthStore } from '../../stores/authStore'
import { PRO_PLAN } from '../../lib/constants'
import { createCheckout } from '../../lib/api'

export function UpgradePage() {
  const { user, plan, sttSecondsUsed, sttSecondsLimit, llmTokensUsed, llmTokensLimit } =
    useAuthStore()
  const { t } = useTranslation()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const isPro = plan === 'pro'

  const handleSubscribe = async () => {
    setLoading(true)
    setError(null)
    try {
      const { url } = await createCheckout('desktop')
      useAuthStore.setState({ checkoutPending: true })
      await openUrl(url)
    } catch (e) {
      setError(e instanceof Error ? e.message : t('account.toast.subscriptionFail'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="max-w-[480px] mx-auto py-8 px-6 text-[13px]">
      {/* Header */}
      <div className="text-center mb-6">
        <div className="inline-flex items-center gap-2 mb-2">
          <Crown size={20} className="text-amber-500" />
          <h1 className="text-[20px] font-semibold text-text-primary">{t('upgrade.title')}</h1>
        </div>
        <p className="text-text-secondary">{t('upgrade.subtitle')}</p>
      </div>

      {/* Current plan badge */}
      <div className="flex items-center justify-center mb-6">
        <span
          className={`px-3 py-1 rounded-full text-[12px] font-medium ${
            isPro ? 'bg-amber-500/10 text-amber-600' : 'bg-bg-secondary text-text-secondary'
          }`}
        >
          {t('upgrade.currentPlan', { plan: isPro ? t('upgrade.pro') : t('upgrade.free') })}
        </span>
      </div>

      {/* Pricing card */}
      <div className="border border-border rounded-[10px] overflow-hidden mb-5">
        <div className="px-4 py-4 bg-bg-secondary/50 border-b border-border">
          <p className="text-[22px] font-semibold text-text-primary">
            {PRO_PLAN.price}
            <span className="text-[13px] font-normal text-text-secondary">
              {' '}
              / {t('upgrade.month')}
            </span>
          </p>
        </div>

        {/* Features */}
        <div>
          {PRO_PLAN.features.map((f, i) => (
            <div
              key={i}
              className="flex items-start gap-2.5 px-4 py-2.5 border-b border-border last:border-b-0"
            >
              <Check size={14} className="text-green-500 mt-0.5 shrink-0" />
              <div>
                <span className="text-text-primary">{t(f.labelKey)}</span>
                <span className="text-text-tertiary ml-1.5 text-[12px]">{t(f.detailKey)}</span>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Pro quota progress */}
      {isPro && (
        <div className="border border-border rounded-[10px] overflow-hidden mb-5">
          <div className="px-4 py-2.5 bg-bg-secondary/50 border-b border-border">
            <h3 className="text-[13px] font-medium text-text-primary">
              {t('upgrade.usageThisMonth')}
            </h3>
          </div>
          <div className="px-4 py-3 space-y-3">
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
        </div>
      )}

      {/* Action */}
      {isPro ? (
        <div className="text-center py-3">
          <p className="text-text-secondary flex items-center justify-center gap-1.5">
            <Crown size={14} className="text-amber-500" />
            {t('upgrade.thankYou')}
          </p>
        </div>
      ) : (
        <>
          {!user && (
            <p className="text-text-tertiary text-[12px] text-center mb-3">
              {t('upgrade.signInFirst')}
            </p>
          )}
          <button
            onClick={handleSubscribe}
            disabled={loading || !user}
            className="w-full py-2.5 rounded-[8px] bg-accent text-white text-[13px] font-medium cursor-pointer border-none hover:opacity-90 transition-opacity disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
          >
            {loading && <Loader2 size={14} className="animate-spin" />}
            {t('upgrade.subscribeToPro')}
          </button>
          {error && <p className="text-red-500 text-[12px] mt-2 text-center">{error}</p>}
        </>
      )}
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
  const { t } = useTranslation()
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
      <div
        className="h-1.5 bg-bg-secondary rounded-full overflow-hidden"
        role="progressbar"
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={t('account.quotaUsage', {
          label,
          used: usedDisplay,
          limit: limitDisplay,
          unit,
        })}
      >
        <div
          className={`h-full rounded-full transition-all ${pct > 90 ? 'bg-red-500' : 'bg-accent'}`}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  )
}
