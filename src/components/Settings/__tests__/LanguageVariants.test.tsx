import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LanguageVariants } from '../LanguageVariants'
import { useAppStore } from '../../../stores/appStore'
import { chooseOption } from '../../shared/__tests__/selectTestUtils'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

afterEach(() => {
  cleanup()
  useAppStore.setState(useAppStore.getInitialState())
})

describe('language variants dialog', () => {
  it('offers the five Typeless language groups and saves regional choices', () => {
    render(<LanguageVariants />)
    fireEvent.click(screen.getByRole('button', { name: 'languageVariants.select' }))
    expect(screen.getAllByRole('combobox')).toHaveLength(5)
    chooseOption(screen.getByRole('combobox', { name: '中文' }), 'zh-TW')
    chooseOption(screen.getByRole('combobox', { name: 'English' }), 'en-GB')
    expect(useAppStore.getState().config.language_variants).toEqual({
      en: 'en-GB',
      zh: 'zh-TW',
    })
    chooseOption(screen.getByRole('combobox', { name: '中文' }), '')
    expect(useAppStore.getState().config.language_variants).toEqual({ en: 'en-GB' })
  })

  it('warns when raw transcription cannot enforce the preference', () => {
    useAppStore.setState((state) => ({
      config: { ...state.config, polish_enabled: false },
    }))
    render(<LanguageVariants />)
    fireEvent.click(screen.getByRole('button', { name: 'languageVariants.select' }))
    expect(screen.getByRole('status')).toHaveTextContent('languageVariants.requiresPolish')
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
