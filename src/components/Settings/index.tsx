import { useState, useEffect, useRef } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../../stores/appStore'
import { SettingsSidebar, type PaneId } from './SettingsSidebar'
import { GeneralPane } from './GeneralPane'
import { SttPane } from './SttPane'
import { LlmPane } from './LlmPane'
import { DictionaryPane } from './DictionaryPane'
import { ScenesPane } from './ScenesPane'
import { AboutPane } from './AboutPane'
import { DirtyBar } from './shared/DirtyBar'
import { useDirtyConfig } from './shared/useDirtyConfig'

const paneTitleKeys: Record<PaneId, string> = {
  general: 'settings.general',
  stt: 'settings.speechRecognition',
  llm: 'settings.aiPolish',
  dictionary: 'settings.dictionary',
  scenes: 'settings.scenes',
  about: 'settings.about',
}

function paneFromHash(): PaneId | null {
  const query = window.location.hash.split('?')[1]
  if (!query) return null
  const requested = new URLSearchParams(query).get('pane')
  return requested && requested in paneTitleKeys ? (requested as PaneId) : null
}

export function Settings() {
  const [activePane, setActivePane] = useState<PaneId>(() => paneFromHash() ?? 'general')
  const contentRef = useRef<HTMLDivElement | null>(null)
  const config = useAppStore((s) => s.config)
  const setSavedConfig = useAppStore((s) => s.setSavedConfig)
  const isDirty = useDirtyConfig()
  const { t } = useTranslation()

  // First-run onboarding may enter Settings before MainApp has established backend truth.
  useEffect(() => {
    if (useAppStore.getState().savedConfig === null) setSavedConfig(config)
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const syncRequestedPane = () => {
      const requested = paneFromHash()
      if (requested) setActivePane(requested)
    }
    window.addEventListener('hashchange', syncRequestedPane)
    return () => window.removeEventListener('hashchange', syncRequestedPane)
  }, [])

  useEffect(() => {
    contentRef.current?.scrollTo?.({ top: 0 })
  }, [activePane])

  return (
    <div className="w-full h-full bg-bg-primary text-text-primary flex flex-col">
      <div className="flex-1 flex min-h-0">
        {/* Sidebar */}
        <SettingsSidebar activePane={activePane} onSelect={setActivePane} />

        {/* Content */}
        <div className="flex-1 flex flex-col min-w-0">
          {/* Title bar */}
          <div className="flex items-center justify-between px-6 pt-4 pb-3 border-b border-border bg-bg-primary/50">
            <h2 className="text-[15px] font-medium">{t(paneTitleKeys[activePane])}</h2>
          </div>

          {/* Pane content */}
          <div ref={contentRef} className="flex-1 overflow-y-auto overflow-x-hidden px-6 py-5">
            <motion.div
              key={activePane}
              className="w-full"
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.1, ease: 'easeOut' }}
            >
              {activePane === 'general' && <GeneralPane />}
              {activePane === 'stt' && <SttPane />}
              {activePane === 'llm' && <LlmPane />}
              {activePane === 'dictionary' && <DictionaryPane />}
              {activePane === 'scenes' && <ScenesPane />}
              {activePane === 'about' && <AboutPane />}
            </motion.div>
          </div>
        </div>
      </div>

      {/* Dirty bar */}
      <AnimatePresence>{isDirty && <DirtyBar />}</AnimatePresence>
    </div>
  )
}
