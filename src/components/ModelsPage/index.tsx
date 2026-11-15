import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { AudioLines } from 'lucide-react'
import { useAppStore } from '../../stores/appStore'
import { SttPane } from '../Settings/SttPane'
import { LlmPane } from '../Settings/LlmPane'
import { SettingsSection } from '../Settings/shared/SettingsRow'
import { useAutoSaveConfig } from '../Settings/shared/useAutoSaveConfig'
import { PageContainer, PageHeader } from '../shared/Page'
import { hasManagedCloudAccess, useAuthStore } from '../../stores/authStore'
import { AppLogo } from '../shared/AppLogo'
import { useRoute } from '../../lib/router'

/** Speech recognition and AI polish providers, laid out like Typeless settings groups. */
export function ModelsPage() {
  const { t } = useTranslation()
  const { navigate } = useRoute()
  const user = useAuthStore((state) => state.user)
  const cloudAccess = useAuthStore(hasManagedCloudAccess)
  const config = useAppStore((s) => s.config)
  const setSavedConfig = useAppStore((s) => s.setSavedConfig)
  useAutoSaveConfig()

  useEffect(() => {
    if (useAppStore.getState().savedConfig === null) setSavedConfig(config)
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <PageContainer>
      <div className="models-config max-w-[900px]">
        <PageHeader title={t('nav.models')} />
        {(!user || !cloudAccess) && (
          <div className="mb-8 flex items-center gap-3 rounded-[10px] border border-border-thin bg-fill-thin px-4 py-3">
            <AppLogo size={24} />
            <div className="min-w-0 flex-1">
              <div className="text-[14px] font-medium text-text-primary">
                {t('modelsPage.cloudTitle')}
              </div>
              <p className="m-0 mt-1 text-[12px] leading-5 text-text-tertiary">
                {t('modelsPage.cloudDescription')}
              </p>
            </div>
            <button
              type="button"
              onClick={() => navigate('upgrade')}
              className="btn-accent min-h-9 shrink-0 cursor-pointer px-3 text-[13px]"
            >
              {t('nav.upgrade')}
            </button>
          </div>
        )}
        <SettingsSection icon={AudioLines} title={t('settings.speechRecognition')}>
          <SttPane grouped />
        </SettingsSection>
        <LlmPane grouped />
      </div>
    </PageContainer>
  )
}
