import { useTranslation } from 'react-i18next'
import { DictionaryPane } from '../Settings/DictionaryPane'
import { PageHeader, PageContainer } from '../shared/Page'

export function DictionaryPage() {
  const { t } = useTranslation()
  return (
    <PageContainer>
      <PageHeader title={t('nav.dictionary')} />
      <DictionaryPane />
    </PageContainer>
  )
}
