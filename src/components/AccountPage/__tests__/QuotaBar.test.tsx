import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import i18n from '../../../i18n'
import { QuotaBar } from '../QuotaBar'

beforeEach(async () => {
  await i18n.changeLanguage('en')
})

afterEach(cleanup)

describe('QuotaBar existing display boundaries', () => {
  it('shows remaining words consistently with the sidebar without changing the default used meter', () => {
    render(
      <QuotaBar
        label="Remaining words"
        used={500}
        limit={2000}
        unit="k words"
        divisor={1000}
        remaining
      />,
    )
    expect(screen.getByText('1.5 / 2.0 k words')).toBeInTheDocument()
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '75')
  })

  it.each([
    { used: 0, limit: 1000, percent: 0, warning: false },
    { used: 900, limit: 1000, percent: 90, warning: false },
    { used: 910, limit: 1000, percent: 91, warning: true },
    { used: 1250, limit: 1000, percent: 100, warning: true },
    { used: 100, limit: 0, percent: 0, warning: false },
  ])('preserves progress and warning state for $used / $limit', (testCase) => {
    render(
      <QuotaBar
        label="Cloud words"
        used={testCase.used}
        limit={testCase.limit}
        unit="k words"
        divisor={1000}
      />,
    )
    const progress = screen.getByRole('progressbar')
    expect(progress).toHaveAttribute('aria-valuenow', String(testCase.percent))
    expect(progress).toHaveAttribute('aria-valuemin', '0')
    expect(progress).toHaveAttribute('aria-valuemax', '100')
    const fill = progress.firstElementChild
    expect(fill).toHaveStyle({ width: `${testCase.percent}%` })
    expect(fill).toHaveClass(testCase.warning ? 'bg-error' : 'bg-accent')
    expect(
      screen.getByText(
        `${(testCase.used / 1000).toFixed(1)} / ${(testCase.limit / 1000).toFixed(1)} k words`,
      ),
    ).toBeInTheDocument()
  })
})
