import { useTranslation } from 'react-i18next'
import i18n from '../../i18n'
import { invoke } from '@tauri-apps/api/core'
import { UI_LANGUAGES } from '../../lib/constants'
import { useAppStore } from '../../stores/appStore'
import { Mic, Languages, MessageCircleQuestion } from 'lucide-react'
import { BrandMark } from '../MainLayout/BrandMark'

export function WelcomeStep() {
  const { t } = useTranslation()
  const config = useAppStore((s) => s.config)
  const updateConfig = useAppStore((s) => s.updateConfig)

  const currentLang = config.ui_language || i18n.language || 'en'

  const handleSelectLanguage = (value: string) => {
    i18n.changeLanguage(value)
    try {
      localStorage.setItem('ui_language', value)
    } catch {
      console.error('Failed to cache interface language')
    }
    updateConfig({ ui_language: value })
    invoke('refresh_tray_labels').catch(() => {})
  }

  return (
    <div className="mx-auto flex h-full w-full max-w-[760px] flex-col justify-center gap-6">
      <div className="text-center py-4">
        <div className="mx-auto mb-4 grid h-16 w-16 place-items-center rounded-[18px] bg-accent text-white">
          <BrandMark size={32} />
        </div>
        <h1 className="mb-4 text-[30px] font-medium leading-[34px] text-text-primary">
          {t('onboarding.steps.welcome')}
        </h1>
        <p className="text-[15px] text-text-secondary leading-relaxed">
          {t('onboarding.speakToWrite')}
        </p>
      </div>
      <div className="grid grid-cols-3 gap-3">
        {[
          { mode: 'dictate', icon: Mic },
          { mode: 'translate', icon: Languages },
          { mode: 'ask', icon: MessageCircleQuestion },
        ].map(({ mode, icon: Icon }) => (
          <div key={mode} className="flex items-start gap-3 rounded-[10px] bg-bg-secondary p-3">
            <Icon size={18} className="mt-0.5 shrink-0 text-accent" aria-hidden="true" />
            <div>
              <p className="text-[13px] font-medium text-text-primary">
                {t(`home.modes.${mode}.title`)}
              </p>
              <p className="mt-1 text-[12px] text-text-secondary">
                {t(`home.modes.${mode}.description`)}
              </p>
            </div>
          </div>
        ))}
      </div>

      <div>
        <p className="text-[13px] font-medium text-text-secondary mb-3">
          {t('onboarding.selectLanguage')}
        </p>
        <div className="grid grid-cols-5 gap-3">
          {UI_LANGUAGES.map((lang) => (
            <button
              key={lang.value}
              type="button"
              aria-pressed={currentLang === lang.value}
              onClick={() => handleSelectLanguage(lang.value)}
              className={`px-3 py-2 rounded-[10px] text-[14px] border cursor-pointer transition-all ${
                currentLang === lang.value
                  ? 'bg-accent/10 border-accent text-accent font-medium'
                  : 'bg-bg-secondary border-border text-text-primary hover:border-text-tertiary'
              }`}
            >
              <div className="font-medium">{lang.label}</div>
            </button>
          ))}
        </div>
        <p className="text-[12px] text-text-tertiary mt-3">{t('onboarding.selectLanguageDesc')}</p>
      </div>
    </div>
  )
}
