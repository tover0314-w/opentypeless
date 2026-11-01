import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { AudioLines, Sparkles } from 'lucide-react'
import { useAppStore } from '../../stores/appStore'
import { SttPane } from '../Settings/SttPane'
import { LlmPane } from '../Settings/LlmPane'
import { SettingsSection } from '../Settings/shared/SettingsRow'
import { useAutoSaveConfig } from '../Settings/shared/useAutoSaveConfig'
import { PageContainer, PageHeader } from '../shared/Page'

/** Speech recognition and AI polish providers, laid out like Typeless settings groups. */
export function ModelsPage() {
  const { t } = useTranslation()
  const config = useAppStore((s) => s.config)
  const setSavedConfig = useAppStore((s) => s.setSavedConfig)
  useAutoSaveConfig()

  useEffect(() => {
    if (useAppStore.getState().savedConfig === null) setSavedConfig(config)
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <PageContainer>
      <div className="max-w-[760px]">
        <PageHeader title={t('nav.models')} />
        <p className="-mt-3 mb-10 text-[14px] leading-5 text-text-tertiary">
          {t('modelsPage.description')}
        </p>
        <SettingsSection icon={AudioLines} title={t('settings.speechRecognition')}>
          <SttPane />
        </SettingsSection>
        <SettingsSection icon={Sparkles} title={t('settings.aiPolish')}>
          <LlmPane />
        </SettingsSection>
      </div>
    </PageContainer>
  )
}
