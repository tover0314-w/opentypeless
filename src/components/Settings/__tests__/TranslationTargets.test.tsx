import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TranslationConfig } from '../../../stores/appStore'
import { TranslationTargets } from '../TranslationTargets'
import { chooseOption, selectOptions } from '../../shared/__tests__/selectTestUtils'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}))

afterEach(cleanup)

function renderTargets(
  value: TranslationConfig = {
    targets: ['en', 'ja'],
    active_target: 'en',
  },
) {
  const onChange = vi.fn()
  render(<TranslationTargets value={value} onChange={onChange} />)
  return onChange
}

describe('TranslationTargets', () => {
  it('shows one chip per target and hides remove for the last remaining one', () => {
    renderTargets({ targets: ['en'], active_target: 'en' })

    expect(screen.getByTestId('translation-target-en')).toHaveTextContent('English')
    expect(screen.queryByRole('button', { name: /translationTargets.remove/ })).toBeNull()
  })

  it('only offers languages that are not already targets', () => {
    renderTargets()

    const values = selectOptions(
      screen.getByRole('combobox', { name: 'translationTargets.addLanguage' }),
    ).map((option) => option.value)
    expect(values).not.toContain('en')
    expect(values).not.toContain('ja')
    expect(values).toContain('zh')
  })

  it('appends the chosen language and keeps the active target', () => {
    const onChange = renderTargets()

    chooseOption(screen.getByRole('combobox', { name: 'translationTargets.addLanguage' }), 'fr')

    expect(onChange).toHaveBeenCalledWith({ targets: ['en', 'ja', 'fr'], active_target: 'en' })
  })

  it('caps the target set at five', () => {
    renderTargets({ targets: ['en', 'ja', 'zh', 'fr', 'de'], active_target: 'en' })

    expect(screen.queryByRole('combobox', { name: 'translationTargets.addLanguage' })).toBeNull()
    expect(screen.getByText('translationTargets.max')).toBeInTheDocument()
  })

  it('selects the nearest remaining target when removing the active target', () => {
    const onChange = renderTargets({ targets: ['en', 'ja', 'fr'], active_target: 'ja' })

    fireEvent.click(screen.getByRole('button', { name: 'translationTargets.remove 日本語' }))

    expect(onChange).toHaveBeenCalledWith({ targets: ['en', 'fr'], active_target: 'fr' })
  })
})
