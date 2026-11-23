import { useTranslation } from 'react-i18next'
import { Lock, Mic, Sparkles } from 'lucide-react'
import { BrandMark } from '../MainLayout/BrandMark'
import type { OnboardingStage } from './stages'

/** Right-hand preview for each onboarding stage, drawn with DOM so it themes and localizes. */
export function OnboardingIllustration({ stage, step }: { stage: OnboardingStage; step: number }) {
  if (stage === 'signUp') return <WelcomeArt />
  if (stage === 'setUp') return step === 5 ? <PermissionArt /> : <ProviderArt />
  return <DictationArt />
}

function Frame({ children }: { children: React.ReactNode }) {
  return (
    <div className="w-full max-w-[380px] overflow-hidden rounded-[16px] border border-border-thin bg-bg-primary shadow-lg">
      <div className="flex items-center gap-1.5 border-b border-border-thin px-4 py-3">
        <span className="h-2.5 w-2.5 rounded-full bg-[#ff5f57]" />
        <span className="h-2.5 w-2.5 rounded-full bg-[#febc2e]" />
        <span className="h-2.5 w-2.5 rounded-full bg-[#28c840]" />
      </div>
      <div className="p-5">{children}</div>
    </div>
  )
}

function WelcomeArt() {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col items-center gap-6 text-center">
      <div className="grid h-20 w-20 place-items-center rounded-[24px] bg-accent text-white shadow-lg">
        <BrandMark size={40} />
      </div>
      <p className="m-0 max-w-[300px] text-[17px] font-medium leading-7 text-text-primary">
        {t('onboarding.art.welcome')}
      </p>
    </div>
  )
}

function ProviderArt() {
  const { t } = useTranslation()
  return (
    <Frame>
      <div className="space-y-3">
        {[t('onboarding.art.speech'), t('onboarding.art.polish')].map((label) => (
          <div
            key={label}
            className="flex items-center justify-between rounded-[10px] bg-fill-thin px-3 py-2.5"
          >
            <span className="text-[13px] text-text-secondary">{label}</span>
          </div>
        ))}
      </div>
    </Frame>
  )
}

function PermissionArt() {
  const { t } = useTranslation()
  return (
    <Frame>
      <div className="flex flex-col items-center gap-3 py-2 text-center">
        <div className="grid h-12 w-12 place-items-center rounded-full bg-fill-tough text-text-secondary">
          <Lock size={22} />
        </div>
        <p className="m-0 text-[14px] font-semibold text-text-primary">
          {t('onboarding.art.privacy')}
        </p>
        <p className="m-0 text-[12px] leading-5 text-text-tertiary">
          {t('onboarding.art.privacyDesc', { app: t('app.name') })}
        </p>
        <div className="mt-1 flex w-full items-center justify-between rounded-[10px] bg-fill-thin px-3 py-2">
          <span className="flex items-center gap-2 text-[13px] text-text-primary">
            <BrandMark size={14} /> {t('app.name')}
          </span>
          <span className="h-5 w-9 rounded-full bg-accent p-0.5">
            <span className="block h-4 w-4 translate-x-4 rounded-full bg-white" />
          </span>
        </div>
      </div>
    </Frame>
  )
}

function DictationArt() {
  const { t } = useTranslation()
  return (
    <div className="flex w-full max-w-[380px] flex-col items-center gap-5">
      <Frame>
        <p className="m-0 text-[14px] leading-6 text-text-primary">
          {t('onboarding.art.sample')}
          <span className="ml-0.5 inline-block h-4 w-px animate-pulse bg-text-primary align-middle" />
        </p>
      </Frame>
      <div className="flex h-[34px] w-[116px] items-center justify-between rounded-full border border-white/30 bg-black px-1 shadow-lg">
        <span className="grid h-6 w-6 place-items-center rounded-full bg-[#424242] text-white/80">
          <Mic size={12} />
        </span>
        <span className="flex items-center gap-[2px]" aria-hidden="true">
          {[6, 12, 18, 10, 14, 8].map((height, index) => (
            <span key={index} className="w-[2px] rounded-full bg-white" style={{ height }} />
          ))}
        </span>
        <span className="grid h-6 w-6 place-items-center rounded-full bg-white text-black">
          <Sparkles size={12} />
        </span>
      </div>
    </div>
  )
}
