import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ModelsPage } from '../index'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))
vi.mock('../../Settings/SttPane', () => ({
  SttPane: () => <div data-testid="speech-config" />,
}))
vi.mock('../../Settings/LlmPane', () => ({
  LlmPane: ({ grouped }: { grouped?: boolean }) => (
    <>
      <section aria-label="modelsPage.aiModel" data-testid="polish-config" data-grouped={grouped} />
      <section aria-label="modelsPage.textProcessing" />
    </>
  ),
}))
vi.mock('../../../stores/authStore', () => ({
  hasManagedCloudAccess: () => false,
  useAuthStore: (selector: (state: { user: null }) => unknown) => selector({ user: null }),
}))
vi.mock('../../Settings/shared/useAutoSaveConfig', () => ({
  useAutoSaveConfig: vi.fn(),
}))

afterEach(cleanup)

describe('minimal model configuration page', () => {
  it('keeps a single page title and both configuration groups without redundant introductory copy', () => {
    render(<ModelsPage />)
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByRole('region', { name: 'settings.speechRecognition' })).toContainElement(
      screen.getByTestId('speech-config'),
    )
    expect(screen.getByRole('region', { name: 'modelsPage.aiModel' })).toHaveAttribute(
      'data-grouped',
      'true',
    )
    expect(screen.getByRole('region', { name: 'modelsPage.textProcessing' })).toBeInTheDocument()
    expect(screen.queryByText('modelsPage.description')).toBeNull()
  })
  it('opens the existing upgrade route from the cloud entry', () => {
    render(<ModelsPage />)
    fireEvent.click(screen.getByRole('button', { name: 'nav.upgrade' }))
    expect(window.location.hash).toBe('#/upgrade')
  })
})
