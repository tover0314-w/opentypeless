import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { PracticeSurface } from '../PracticeSurface'
import i18n from '../../../i18n'

afterEach(cleanup)

it.each(['translate', 'ask'] as const)(
  'labels the %s surface without implying a chat result',
  (mode) => {
    render(<PracticeSurface example={0} mode={mode} text="" complete={false} />)
    expect(screen.getByText(i18n.t(`home.modes.${mode}.title`))).toBeInTheDocument()
    expect(screen.queryByText(i18n.t('contextFamilies.work_chat'))).toBeNull()
    expect(screen.getByRole('textbox')).toHaveValue('')
  },
)

it('keeps the practice surface empty until supplied actual progress', () => {
  const { rerender } = render(<PracticeSurface example={0} text="" complete={false} />)
  const result = screen.getByRole('textbox', { name: i18n.t('onboarding.test.result') })
  expect(result).toHaveValue('')
  expect(result).toHaveAttribute('readonly')
  expect(screen.getByText(i18n.t('contextFamilies.work_chat'))).toBeInTheDocument()
  rerender(<PracticeSurface example={1} text="Synthetic streamed text" complete={false} />)
  expect(result).toHaveValue('Synthetic streamed text')
  expect(screen.getByText(i18n.t('contextFamilies.email'))).toBeInTheDocument()
  expect(screen.queryByText(i18n.t('onboarding.test.result'))).not.toBeInTheDocument()
  rerender(<PracticeSurface example={1} text="Synthetic final text" complete />)
  expect(result).toHaveValue('Synthetic final text')
  expect(screen.getByText(i18n.t('onboarding.test.result'))).toBeInTheDocument()
  rerender(<PracticeSurface example={2} text="" complete={false} />)
  expect(result).toHaveValue('')
})
