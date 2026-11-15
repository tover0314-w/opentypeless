import { describe, expect, it } from 'vitest'
import { siteUrl } from '../siteLinks'

describe('siteUrl', () => {
  it('prefixes the locale the site serves', () => {
    expect(siteUrl('privacy', 'zh')).toBe('https://www.opentypeless.com/zh/privacy')
    expect(siteUrl('terms', 'ja')).toBe('https://www.opentypeless.com/ja/terms')
  })

  it('falls back to English for regional or unknown languages', () => {
    expect(siteUrl('download', 'en-US')).toBe('https://www.opentypeless.com/en/download')
    expect(siteUrl('privacy', 'xx')).toBe('https://www.opentypeless.com/en/privacy')
  })
})
