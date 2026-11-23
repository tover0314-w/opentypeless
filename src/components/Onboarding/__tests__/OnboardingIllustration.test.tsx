import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OnboardingIllustration } from '../OnboardingIllustration'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

afterEach(cleanup)

describe('onboarding illustration', () => {
  it.each([2, 3, 4])('does not claim untested services are ready at step %s', (step) => {
    render(<OnboardingIllustration stage="setUp" step={step} />)
    expect(screen.getByText('onboarding.art.speech')).toBeInTheDocument()
    expect(screen.getByText('onboarding.art.polish')).toBeInTheDocument()
    expect(screen.queryAllByText('OK')).toEqual([])
    expect(document.querySelector('.text-success')).toBeNull()
  })
})
