import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { useAppStore } from '../../stores/appStore'
import { GeneralPane } from './GeneralPane'
import { SttPane } from './SttPane'
import { LlmPane } from './LlmPane'
import { ScenesPane } from './ScenesPane'
import { AboutPane } from './AboutPane'
import { HelpPane } from './HelpPane'
import { AccountPage } from '../AccountPage'
import { useAutoSaveConfig } from './shared/useAutoSaveConfig'
import { SETTINGS_TABS, paneFromHash, type SettingsTabId } from './settingsTabs'

interface Props {
  initialTab?: SettingsTabId
  onClose?: () => void
}

export function Settings({ initialTab, onClose }: Props) {
  const [activeTab, setActiveTab] = useState<SettingsTabId>(
    () => paneFromHash() ?? initialTab ?? 'general',
  )
  const contentRef = useRef<HTMLDivElement | null>(null)
  const config = useAppStore((s) => s.config)
  const setSavedConfig = useAppStore((s) => s.setSavedConfig)
  const { t } = useTranslation()
  useAutoSaveConfig()

  // First-run onboarding may enter Settings before MainApp has established backend truth.
  useEffect(() => {
    if (useAppStore.getState().savedConfig === null) setSavedConfig(config)
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const syncRequestedPane = () => {
      const requested = paneFromHash()
      if (requested) setActiveTab(requested)
    }
    window.addEventListener('hashchange', syncRequestedPane)
    return () => window.removeEventListener('hashchange', syncRequestedPane)
  }, [])

  useEffect(() => {
    contentRef.current?.scrollTo?.({ top: 0 })
  }, [activeTab])

  const activeLabel = t(SETTINGS_TABS.find((tab) => tab.id === activeTab)!.labelKey)

  return (
    <div className="relative flex h-full w-full overflow-hidden bg-bg-elevated text-text-primary">
      <nav
        aria-label={t('settingsModal.title')}
        className="flex w-[202px] shrink-0 flex-col gap-0.5 bg-fill-thin px-3 py-5"
      >
        {SETTINGS_TABS.map((tab) => {
          const Icon = tab.icon
          const isActive = activeTab === tab.id
          return (
            <button
              key={tab.id}
              type="button"
              aria-current={isActive ? 'page' : undefined}
              onClick={() => setActiveTab(tab.id)}
              className={`flex w-full items-center gap-2.5 rounded-[10px] border-none px-3 py-2.5 text-left text-[14px] transition-colors cursor-pointer ${
                isActive
                  ? 'bg-fill-selected text-text-primary'
                  : 'bg-transparent text-text-secondary hover:bg-fill-hover hover:text-text-primary'
              }`}
            >
              <Icon size={16} strokeWidth={1.8} />
              <span>{t(tab.labelKey)}</span>
            </button>
          )
        })}
      </nav>

      <div ref={contentRef} className="min-w-0 flex-1 overflow-y-auto overflow-x-hidden px-9 py-6">
        <h2 className="mb-6 text-[24px] font-medium leading-8 text-text-primary">{activeLabel}</h2>
        <div key={activeTab} className="w-full">
          {activeTab === 'account' && <AccountPage embedded />}
          {activeTab === 'general' && <GeneralPane />}
          {activeTab === 'models' && (
            <div className="space-y-10">
              <section aria-label={t('settings.speechRecognition')}>
                <h3 className="mb-3 border-b border-border-thin pb-4 text-[16px] font-medium text-text-tertiary">
                  {t('settings.speechRecognition')}
                </h3>
                <SttPane />
              </section>
              <section aria-label={t('settings.aiPolish')}>
                <h3 className="mb-3 border-b border-border-thin pb-4 text-[16px] font-medium text-text-tertiary">
                  {t('settings.aiPolish')}
                </h3>
                <LlmPane />
              </section>
            </div>
          )}
          {activeTab === 'personalization' && <ScenesPane />}
          {activeTab === 'about' && <AboutPane />}
          {activeTab === 'help' && <HelpPane />}
        </div>
      </div>

      {onClose && (
        <button
          type="button"
          aria-label={t('common.close')}
          onClick={onClose}
          className="absolute right-3 top-3 grid h-8 w-8 place-items-center rounded-full border-none bg-transparent text-text-secondary cursor-pointer hover:bg-fill-hover hover:text-text-primary"
        >
          <X size={18} />
        </button>
      )}
    </div>
  )
}

/** Settings rendered as a modal above the current page. */
export function SettingsModal({ onClose }: { onClose: () => void }) {
  const handleKey = useCallback(
    (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !event.defaultPrevented) onClose()
    },
    [onClose],
  )

  useEffect(() => {
    window.addEventListener('keydown', handleKey)
    return () => window.removeEventListener('keydown', handleKey)
  }, [handleKey])

  return (
    <ModalFrame onClose={onClose}>
      <Settings onClose={onClose} />
    </ModalFrame>
  )
}

export function ModalFrame({
  children,
  onClose,
  maxWidth = 940,
  maxHeight = 652,
}: {
  children: React.ReactNode
  onClose: () => void
  maxWidth?: number
  maxHeight?: number
}) {
  return (
    <div
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-10"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <div
        className="h-[calc(100vh-80px)] w-full overflow-hidden rounded-[12px] shadow-float"
        style={{ maxWidth, maxHeight }}
      >
        {children}
      </div>
    </div>
  )
}
