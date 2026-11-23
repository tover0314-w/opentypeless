import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PageContent } from '../PageContent'
import pageContentSource from '../PageContent.tsx?raw'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))
vi.mock('../HomePage', () => ({ HomePage: () => <div>Home content</div> }))
vi.mock('../History', () => ({ History: () => <div>History content</div> }))
vi.mock('../Dictionary', () => ({ DictionaryPage: () => <div>Dictionary content</div> }))
vi.mock('../ModelsPage', () => ({ ModelsPage: () => <div>Models content</div> }))

afterEach(cleanup)

describe('page content', () => {
  it('loads non-home pages dynamically rather than in the initial entry graph', () => {
    const source = pageContentSource
    for (const path of ['./History', './Dictionary', './ModelsPage']) {
      expect(source).toContain(`import('${path}')`)
      expect(source).not.toMatch(new RegExp(`from ['"]${path.replace('.', '\\.')}['"]`))
    }
  })
  it.each(['home', 'history', 'dictionary', 'models'] as const)(
    'preserves the %s page through deferred loading',
    async (page) => {
      render(<PageContent page={page} />)
      const title = page[0].toUpperCase() + page.slice(1)
      expect(await screen.findByText(`${title} content`)).toBeInTheDocument()
      expect(screen.queryByText('common.loading')).toBeNull()
    },
  )
})
