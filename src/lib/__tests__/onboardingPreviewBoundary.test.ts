import { describe, expect, it, vi } from 'vitest'
import previewHtml from '../../../onboarding-preview.html?raw'
import previewSource from '../../onboarding-preview.tsx?raw'
import { mountBrowserOnlyPreview } from '../onboardingPreviewBoundary'

describe('standalone onboarding preview boundary', () => {
  it.each(['__TAURI_INTERNALS__', '__TAURI__'])(
    'does not load component modules when %s is present',
    async (bridge) => {
      const load = vi.fn().mockResolvedValue(undefined)
      expect(await mountBrowserOnlyPreview({ [bridge]: undefined }, load)).toBe(false)
      expect(load).not.toHaveBeenCalled()
    },
  )

  it('loads the real preview once in a browser without a native bridge', async () => {
    const load = vi.fn().mockResolvedValue(undefined)
    expect(await mountBrowserOnlyPreview({}, load)).toBe(true)
    expect(load).toHaveBeenCalledOnce()
  })

  it('disables network connections and form submission at document level', () => {
    const document = new DOMParser().parseFromString(previewHtml, 'text/html')
    const policy = document
      .querySelector('meta[http-equiv="Content-Security-Policy"]')
      ?.getAttribute('content')
    expect(policy).toContain("connect-src 'none'")
    expect(policy).toContain("form-action 'none'")
  })

  it('uses a guarded bootstrap rather than mounting components directly from HTML', () => {
    expect(previewHtml).toContain('src="/src/onboarding-preview-entry.ts"')
    expect(previewSource).not.toContain('__TAURI_INTERNALS__ =')
  })
})
