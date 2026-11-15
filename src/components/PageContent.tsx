import { lazy, Suspense } from 'react'
import { useTranslation } from 'react-i18next'
import type { Page } from '../lib/router'
import { HomePage } from './HomePage'

const History = lazy(() => import('./History').then((module) => ({ default: module.History })))
const DictionaryPage = lazy(() =>
  import('./Dictionary').then((module) => ({ default: module.DictionaryPage })),
)
const ModelsPage = lazy(() =>
  import('./ModelsPage').then((module) => ({ default: module.ModelsPage })),
)

export function PageContent({ page }: { page: Page }) {
  const { t } = useTranslation()
  return (
    <Suspense
      fallback={
        <div className="flex h-full items-center justify-center">
          <span className="text-[13px] text-text-tertiary">{t('common.loading')}</span>
        </div>
      }
    >
      <ResolvedPage page={page} />
    </Suspense>
  )
}

function ResolvedPage({ page }: { page: Page }) {
  switch (page) {
    case 'home':
      return <HomePage />
    case 'history':
      return <History />
    case 'dictionary':
      return <DictionaryPage />
    case 'models':
      return <ModelsPage />
  }
}
