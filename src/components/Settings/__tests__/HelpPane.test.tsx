import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { HelpPane } from '../HelpPane'

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: vi.fn() }))
afterEach(cleanup)

it('opens the selected lesson directly from help', () => {
  const onExploreFeatures = vi.fn()
  render(<HelpPane onExploreFeatures={onExploreFeatures} />)
  for (const mode of ['dictate', 'translate', 'ask']) {
    fireEvent.click(screen.getByRole('button', { name: `home.modes.${mode}.title` }))
    expect(onExploreFeatures).toHaveBeenLastCalledWith(mode)
  }
})
