import { useTranslation } from 'react-i18next'
import i18n from '../i18n'
import { useAppStore } from '../stores/appStore'

export function usePreviewLanguage() {
  const { t } = useTranslation()
  const language = i18n.resolvedLanguage || 'en'
  const changeLanguage = async (nextLanguage: string) => {
    await i18n.changeLanguage(nextLanguage)
    useAppStore.getState().updateConfig({ ui_language: i18n.resolvedLanguage || 'en' })
  }
  return { language, changeLanguage, t }
}
