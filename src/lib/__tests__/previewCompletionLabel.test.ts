import { expect, it } from 'vitest'
import desktopSource from '../../components/Onboarding/index.tsx?raw'
import previewSource from '../../onboarding-preview.tsx?raw'

it('uses the actual completion action label without invoking native completion in the preview', () => {
  expect(desktopSource).toContain("t('onboarding.steps.getStarted')")
  expect(previewSource).toContain("t('onboarding.steps.getStarted')")
  expect(previewSource).not.toContain('saveOnboardingCompleted')
  expect(previewSource).not.toContain('saveOnboardingConfig')
})
