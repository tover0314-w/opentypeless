import { lazy, Suspense } from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ErrorBoundary } from '../ErrorBoundary'

vi.mock('../../i18n', () => ({
  default: { t: (key: string) => key },
}))

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('error boundary', () => {
  it('provides a localized reload action without displaying rejected chunk details', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const DeferredPage = lazy(async () => {
      throw new Error('Failed module https://synthetic.invalid/?token=synthetic-private-value')
    })
    render(
      <ErrorBoundary>
        <Suspense fallback={<span>Pending page</span>}>
          <DeferredPage />
        </Suspense>
      </ErrorBoundary>,
    )
    expect(await screen.findByText('error.somethingWentWrong')).toBeInTheDocument()
    expect(screen.getByText('error.unexpectedError')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'error.reload' })).toBeInTheDocument()
    expect(screen.queryByText(/synthetic-private-value/)).toBeNull()
    expect(screen.queryByText('Pending page')).toBeNull()
  })
})
