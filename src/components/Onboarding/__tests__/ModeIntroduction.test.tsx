import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ModeIntroduction } from '../ModeIntroduction'
import i18n from '../../../i18n'

afterEach(cleanup)

describe('mode introduction', () => {
  it.each(['dictate', 'translate', 'ask'] as const)(
    'renders the source-backed %s introduction without starting practice',
    (mode) => {
      const onContinue = vi.fn()
      const { container } = render(<ModeIntroduction mode={mode} onContinue={onContinue} />)
      const heading = screen.getByRole('heading', { level: 1 })
      expect(heading).toHaveTextContent(i18n.t(`home.modes.${mode}.title`))
      expect(heading.className).toContain(mode === 'dictate' ? 'text-[48px]' : 'text-[36px]')
      expect(container.firstElementChild?.className).toContain('-mt-[58px]')
      expect(container.firstElementChild?.className).toContain('gap-6')
      expect(screen.queryByRole('meter')).toBeNull()
      fireEvent.click(screen.getByRole('button', { name: i18n.t('onboarding.layout.next') }))
      expect(onContinue).toHaveBeenCalledTimes(1)
    },
  )
})
