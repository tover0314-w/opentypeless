import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import en from './locales/en.json'
import zh from './locales/zh.json'
import ja from './locales/ja.json'
import ko from './locales/ko.json'
import fr from './locales/fr.json'
import de from './locales/de.json'
import es from './locales/es.json'
import pt from './locales/pt.json'
import ru from './locales/ru.json'
import it from './locales/it.json'

const savedLang =
  typeof localStorage !== 'undefined' ? localStorage.getItem('ui_language') || 'en' : 'en'

i18n.use(initReactI18next).init({
  resources: {
    en: { translation: en },
    zh: { translation: zh },
    ja: { translation: ja },
    ko: { translation: ko },
    fr: { translation: fr },
    de: { translation: de },
    es: { translation: es },
    pt: { translation: pt },
    ru: { translation: ru },
    it: { translation: it },
  },
  lng: savedLang,
  fallbackLng: 'en',
  supportedLngs: ['en', 'zh', 'ja', 'ko', 'fr', 'de', 'es', 'pt', 'ru', 'it'],
  interpolation: { escapeValue: false },
})

export default i18n
