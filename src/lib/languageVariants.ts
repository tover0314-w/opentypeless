export const LANGUAGE_VARIANTS = {
  en: [
    { value: 'en-US', label: 'English (US)' },
    { value: 'en-GB', label: 'English (UK)' },
    { value: 'en-AU', label: 'English (AU)' },
    { value: 'en-CA', label: 'English (CA)' },
  ],
  zh: [
    { value: 'zh-CN', label: '简体中文（中国大陆）' },
    { value: 'zh-HK', label: '繁體中文（香港）' },
    { value: 'zh-TW', label: '繁體中文（台灣）' },
  ],
  es: [
    { value: 'es-MX', label: 'Español (México)' },
    { value: 'es-ES', label: 'Español (España)' },
    { value: 'es-US', label: 'Español (Estados Unidos)' },
  ],
  fr: [
    { value: 'fr-FR', label: 'Français (France)' },
    { value: 'fr-CA', label: 'Français (Canada)' },
  ],
  pt: [
    { value: 'pt-PT', label: 'Português (Portugal)' },
    { value: 'pt-BR', label: 'Português (Brasil)' },
  ],
} as const

export type VariantLanguage = keyof typeof LANGUAGE_VARIANTS
export type LanguageVariants = Partial<Record<VariantLanguage, string>>

export function normalizeLanguageVariants(value: unknown): LanguageVariants {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  const variants: LanguageVariants = {}
  for (const base of Object.keys(LANGUAGE_VARIANTS) as VariantLanguage[]) {
    const code = (value as Record<string, unknown>)[base]
    if (LANGUAGE_VARIANTS[base].some((option) => option.value === code)) {
      variants[base] = code as string
    }
  }
  return variants
}
