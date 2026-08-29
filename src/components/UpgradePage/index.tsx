import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, CreditCard, Loader2, LogIn } from 'lucide-react'
import { openUrl } from '@tauri-apps/plugin-opener'
import { hasManagedCloudAccess, useAuthStore } from '../../stores/authStore'
import { CHECKOUT_PLANS, PRO_PLAN, type CheckoutProduct } from '../../lib/constants'
import { CloudApiError, createCheckout } from '../../lib/api'
import { useRoute } from '../../lib/router'
import {
  clearPendingDesktopCheckout,
  readPendingDesktopCheckout,
  savePendingDesktopCheckout,
} from '../../lib/desktop-checkout-intent'

function checkoutErrorMessage(error: unknown, t: ReturnType<typeof useTranslation>['t']) {
  if (error instanceof CloudApiError) {
    if (error.status === 429) return t('upgrade.checkoutRateLimited')
    if (
      error.code === 'checkout_intent_conflict' ||
      error.code === 'checkout_intent_in_progress' ||
      error.code === 'checkout_intent_claimed'
    ) {
      return t('upgrade.checkoutInProgress')
    }
  }
  return t('upgrade.checkoutTemporarilyUnavailable')
}

export function UpgradePage() {
  const {
    user,
    plan,
    source,
    displayName,
    subscriptionStatus,
    billingProvider,
    canMigrateToStripe,
    quotaModel,
    displayWordsUsedEstimate,
    displayWordsLimit,
    cloudWordsUsed,
    cloudWordsLimit,
    sttSecondsUsed,
    sttSecondsLimit,
    llmTokensUsed,
    llmTokensLimit,
  } = useAuthStore()
  const { t } = useTranslation()
  const { navigate } = useRoute()
  const [loadingProduct, setLoadingProduct] = useState<CheckoutProduct | null>(null)
  const [error, setError] = useState<string | null>(null)
  const checkoutInFlight = useRef(false)
  const resumeAttempted = useRef(false)

  const hasCloudAccess = useAuthStore(hasManagedCloudAccess)
  const hasLifetimeAccess =
    plan === 'lifetime_starter' || source === 'lifetime' || source === 'appsumo'
  const hasMonthlyAccess = !hasLifetimeAccess && plan === 'pro' && hasCloudAccess
  const isCreemMigration =
    billingProvider === 'creem' &&
    canMigrateToStripe &&
    (subscriptionStatus === 'past_due' || subscriptionStatus === 'unpaid')
  const hasLifetimeCheckoutPlan = CHECKOUT_PLANS.some(
    (checkoutPlan) => checkoutPlan.product === 'lifetime_starter',
  )
  const visiblePlans = hasLifetimeAccess
    ? []
    : isCreemMigration
      ? CHECKOUT_PLANS.filter((checkoutPlan) => checkoutPlan.product === 'pro_monthly')
      : hasMonthlyAccess
        ? CHECKOUT_PLANS.filter((checkoutPlan) => checkoutPlan.product === 'lifetime_starter')
        : CHECKOUT_PLANS
  const wordsUsed =
    quotaModel === 'legacy_dual_meter' && displayWordsLimit > 0
      ? displayWordsUsedEstimate
      : cloudWordsUsed
  const wordsLimit =
    quotaModel === 'legacy_dual_meter' && displayWordsLimit > 0
      ? displayWordsLimit
      : cloudWordsLimit
  const canStartCheckout = (product: CheckoutProduct) => {
    if (hasLifetimeAccess) return false
    if (isCreemMigration) return product === 'pro_monthly'
    if (product === 'lifetime_starter') return true
    return !hasCloudAccess
  }

  const handleSubscribe = useCallback(
    async (product: CheckoutProduct) => {
      if (checkoutInFlight.current) return
      if (!user) {
        try {
          savePendingDesktopCheckout(localStorage, product)
          navigate('account')
        } catch {
          setError(t('upgrade.checkoutTemporarilyUnavailable'))
        }
        return
      }

      checkoutInFlight.current = true
      setLoadingProduct(product)
      setError(null)
      try {
        const { url } = await createCheckout('desktop', product)
        await openUrl(url)
        clearPendingDesktopCheckout(localStorage)
        useAuthStore.setState({ checkoutPending: true })
      } catch (e) {
        setError(checkoutErrorMessage(e, t))
      } finally {
        checkoutInFlight.current = false
        setLoadingProduct(null)
      }
    },
    [navigate, t, user],
  )

  useEffect(() => {
    if (!user || resumeAttempted.current) return
    const pending = readPendingDesktopCheckout(localStorage)
    if (!pending) return
    resumeAttempted.current = true
    clearPendingDesktopCheckout(localStorage)
    void handleSubscribe(pending.product)
  }, [handleSubscribe, user])

  return (
    <div className="max-w-[620px] mx-auto py-7 px-6 text-[13px]">
      <header className="mb-5 flex items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-[19px] font-semibold text-text-primary">{t('upgrade.title')}</h1>
          <p className="mt-1 max-w-[420px] text-[13px] leading-5 text-text-secondary">
            {t('upgrade.subtitle')}
          </p>
        </div>
        <span
          className={`shrink-0 rounded-full px-3 py-1 text-[12px] font-medium ${
            hasCloudAccess ? 'bg-accent-light text-accent' : 'bg-bg-secondary text-text-secondary'
          }`}
        >
          {t('upgrade.currentPlan', {
            plan: isCreemMigration ? t('upgrade.paymentNeedsAttention') : displayName,
          })}
        </span>
      </header>

      {/* Pricing cards */}
      {visiblePlans.length > 0 && (
        <div
          className={`grid gap-3 mb-4 ${visiblePlans.length > 1 ? 'min-[620px]:grid-cols-2' : ''}`}
        >
          {visiblePlans.map((checkoutPlan) => {
            const isLoading = loadingProduct === checkoutPlan.product
            const isLifetime = checkoutPlan.product === 'lifetime_starter'
            const isMigrationPlan = isCreemMigration && !isLifetime
            const price =
              hasMonthlyAccess && isLifetime && checkoutPlan.upgradePrice
                ? checkoutPlan.upgradePrice
                : checkoutPlan.price
            const sublineKey =
              hasMonthlyAccess && isLifetime && checkoutPlan.upgradeSublineKey
                ? checkoutPlan.upgradeSublineKey
                : checkoutPlan.sublineKey
            return (
              <section
                key={checkoutPlan.product}
                className={`jelly-card flex rounded-[18px] p-4 ${
                  isLifetime ? 'ring-1 ring-accent/30' : ''
                }`}
              >
                <div className="relative z-[1] flex h-full w-full flex-col">
                  <div className="flex min-h-6 items-start justify-between gap-2">
                    <h2 className="text-[14px] font-semibold text-text-primary">
                      {isMigrationPlan ? t('upgrade.restorePro') : t(checkoutPlan.nameKey)}
                    </h2>
                    {checkoutPlan.badgeKey && (
                      <span className="shrink-0 rounded-full border border-accent/20 bg-accent-light px-2 py-0.5 text-[10px] font-semibold text-accent">
                        {t(checkoutPlan.badgeKey)}
                      </span>
                    )}
                  </div>
                  <p className="mt-3 text-[24px] font-semibold leading-none text-text-primary">
                    {price}
                    <span className="text-[13px] font-normal text-text-secondary">
                      {' '}
                      / {t(checkoutPlan.periodKey)}
                    </span>
                  </p>
                  <p className="mt-2 min-h-[36px] text-[12px] leading-5 text-text-secondary">
                    {isMigrationPlan
                      ? t('upgrade.migrationDescription')
                      : t(checkoutPlan.descriptionKey)}
                  </p>
                  {sublineKey && (
                    <p className="mt-2 inline-flex items-center gap-1.5 rounded-full bg-bg-secondary/70 px-2 py-1 text-[11px] font-medium text-text-secondary">
                      {t(sublineKey)}
                    </p>
                  )}
                  {canStartCheckout(checkoutPlan.product) && (
                    <div className="mt-auto pt-4">
                      <button
                        onClick={() => handleSubscribe(checkoutPlan.product)}
                        disabled={loadingProduct !== null}
                        className="jelly-btn-accent flex w-full items-center justify-center gap-2 rounded-full bg-accent px-4 py-2.5 text-[13px] font-medium text-white transition-colors hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        {isLoading ? (
                          <Loader2 size={14} className="animate-spin" />
                        ) : !user ? (
                          <LogIn size={14} />
                        ) : (
                          <CreditCard size={14} />
                        )}
                        {isLoading
                          ? t('upgrade.openingCheckout')
                          : isMigrationPlan
                            ? t('upgrade.switchToStripe')
                            : t(checkoutPlan.ctaKey)}
                      </button>
                    </div>
                  )}
                </div>
              </section>
            )
          })}
        </div>
      )}

      {/* Cloud plan benefits */}
      <section className="mb-4 rounded-[18px] p-4 jelly-card">
        <div className="relative z-[1]">
          <h2 className="text-[12px] font-semibold text-text-primary">
            {t('upgrade.benefits.title')}
          </h2>
          <div className="mt-3 grid gap-2 min-[560px]:grid-cols-3">
            {PRO_PLAN.benefits.map((benefit) => (
              <div key={benefit.labelKey} className="flex items-start gap-2.5">
                <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-accent-light text-accent">
                  <Check size={12} />
                </span>
                <span className="text-[13px] leading-5 text-text-primary">
                  {t(benefit.labelKey)}
                </span>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Pro quota progress */}
      {hasCloudAccess && (
        <section className="mb-4 overflow-hidden rounded-[10px] border border-border">
          <div className="border-b border-border bg-bg-secondary/50 px-4 py-2.5">
            <h3 className="text-[13px] font-medium text-text-primary">
              {t('upgrade.usageThisMonth')}
            </h3>
          </div>
          <div className="px-4 py-3 space-y-3">
            {wordsLimit > 0 ? (
              <QuotaBar
                label={t('upgrade.cloudWords')}
                used={wordsUsed}
                limit={wordsLimit}
                unit={t('upgrade.kWords')}
                divisor={1000}
              />
            ) : (
              <>
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
              </>
            )}
          </div>
        </section>
      )}

      {/* Action */}
      {hasLifetimeAccess ? (
        <div className="py-3 text-center">
          <p className="text-text-secondary flex items-center justify-center gap-1.5">
            <Check size={14} className="text-accent" />
            {t('upgrade.thankYou')}
          </p>
        </div>
      ) : hasCloudAccess ? (
        <div className="py-3 text-center">
          <p className="text-text-secondary flex items-center justify-center gap-1.5">
            <Check size={14} className="text-accent" />
            {hasLifetimeCheckoutPlan
              ? t(
                  'upgrade.monthlyActiveLifetimeHint',
                  'Pro is active. Lifetime is available as a one-time upgrade.',
                )
              : t('upgrade.monthlyActive', 'Pro is active.')}
          </p>
        </div>
      ) : (
        <>
          {!user && (
            <p className="text-text-tertiary text-[12px] text-center mb-3">
              {t('upgrade.signInFirst')}
            </p>
          )}
        </>
      )}
      {error && (
        <p role="alert" className="text-red-500 text-[12px] mt-2 text-center">
          {error}
        </p>
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
