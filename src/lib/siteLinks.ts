import { UI_LANGUAGES } from './constants'

export type SitePage = 'privacy' | 'terms' | 'download'

const SITE_ORIGIN = 'https://www.opentypeless.com'

/** The site only serves these pages under a locale prefix; unknown languages fall back to English. */
export function siteUrl(page: SitePage, language: string): string {
  const base = language.split('-')[0]
  const locale = UI_LANGUAGES.some((item) => item.value === base) ? base : 'en'
  return `${SITE_ORIGIN}/${locale}/${page}`
}
