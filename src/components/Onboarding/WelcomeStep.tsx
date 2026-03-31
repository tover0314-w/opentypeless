import { useTranslation } from 'react-i18next'
import i18n from '../../i18n'
import { useAppStore } from '../../stores/appStore'

const UI_LANGUAGES = [
  { code: 'en', label: 'English', native: 'English' },
  { code: 'zh', label: 'Chinese', native: '中文' },
] as const

export function WelcomeStep() {
  const { t } = useTranslation()
  const config = useAppStore((s) => s.config)
  const updateConfig = useAppStore((s) => s.updateConfig)

  const currentLang = config.ui_language || i18n.language || 'en'

  const handleSelectLanguage = (code: string) => {
    i18n.changeLanguage(code)
    localStorage.setItem('ui_language', code)
    updateConfig({ ui_language: code })
  }

  return (
    <div className="space-y-6">
      <div className="text-center py-4">
        <div className="text-[40px] mb-2">🎙</div>
        <p className="text-[15px] text-text-secondary leading-relaxed">
          {t('onboarding.speakToWrite')}
        </p>
      </div>

      <div>
        <p className="text-[13px] font-medium text-text-secondary mb-3">
          {t('onboarding.selectLanguage')}
        </p>
        <div className="grid grid-cols-2 gap-3">
          {UI_LANGUAGES.map((lang) => (
            <button
              key={lang.code}
              onClick={() => handleSelectLanguage(lang.code)}
              className={`px-4 py-4 rounded-[10px] text-[14px] border cursor-pointer transition-all ${
                currentLang === lang.code
                  ? 'bg-accent/10 border-accent text-accent font-medium'
                  : 'bg-bg-secondary border-border text-text-primary hover:border-text-tertiary'
              }`}
            >
              <div className="font-medium">{lang.native}</div>
              <div className="text-[12px] text-text-tertiary mt-0.5">{lang.label}</div>
            </button>
          ))}
        </div>
        <p className="text-[12px] text-text-tertiary mt-3">{t('onboarding.selectLanguageDesc')}</p>
      </div>
    </div>
  )
}
