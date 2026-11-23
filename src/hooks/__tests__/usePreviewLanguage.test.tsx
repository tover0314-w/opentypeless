import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import i18n from '../../i18n'
import { useAppStore } from '../../stores/appStore'
import { usePreviewLanguage } from '../usePreviewLanguage'

const originalConfig = useAppStore.getState().config
const originalLanguage = i18n.language

afterEach(async () => {
  cleanup()
  useAppStore.setState({ config: originalConfig })
  await i18n.changeLanguage(originalLanguage)
})

it('follows language changes initiated by the real welcome component', async () => {
  const { result } = renderHook(() => usePreviewLanguage())
  await act(async () => {
    await i18n.changeLanguage('ja')
    useAppStore.getState().updateConfig({ ui_language: 'ja' })
  })
  expect(result.current.language).toBe('ja')
  expect(result.current.t('onboarding.steps.welcome')).toBe(
    i18n.getFixedT('ja')('onboarding.steps.welcome'),
  )
})

it('updates the welcome selection when the preview toolbar changes language', async () => {
  const { result } = renderHook(() => usePreviewLanguage())
  await act(async () => result.current.changeLanguage('fr'))
  expect(result.current.language).toBe('fr')
  expect(useAppStore.getState().config.ui_language).toBe('fr')
})
