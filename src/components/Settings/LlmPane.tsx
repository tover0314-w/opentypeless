import { useState, useEffect, useCallback, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../../stores/appStore'
import type { PolishStyle } from '../../stores/appStore'
import { hasManagedCloudAccess, useAuthStore } from '../../stores/authStore'
import { LLM_PROVIDERS, LLM_DEFAULT_CONFIG, llmProviderRequiresApiKey } from '../../lib/constants'
import {
  benchLlmConnection,
  fetchLlmModels,
  getLatestMappingCandidate,
  listCustomAppMappings,
  readCredential,
  setCredential,
} from '../../lib/tauri'
import type { CustomAppMappingView, MappingCandidateView } from '../../lib/tauri'
import { SettingsRow } from './shared/SettingsRow'
import { Select } from '../shared/Select'
import { Toggle } from './shared/Toggle'
import {
  CheckCircle2,
  XCircle,
  Loader2,
  RefreshCw,
  Crown,
  ChevronDown,
  MoreHorizontal,
} from 'lucide-react'
import { AppLogo } from '../AppLogo'
import { ContextAdaptationApps } from './ContextAdaptationApps'
import { AppStyleMappingDialog } from './AppStyleMappingDialog'
import { ManageAppMappingsDialog } from './ManageAppMappingsDialog'

export function LlmPane() {
  const config = useAppStore((s) => s.config)
  const updateConfig = useAppStore((s) => s.updateConfig)
  const llmTestStatus = useAppStore((s) => s.llmTestStatus)
  const setLlmTestStatus = useAppStore((s) => s.setLlmTestStatus)
  const llmLatencyMs = useAppStore((s) => s.llmLatencyMs)
  const setLlmLatencyMs = useAppStore((s) => s.setLlmLatencyMs)
  const lastContext = useAppStore((s) => s.lastContext)
  const { user } = useAuthStore()
  const hasCloudAccess = useAuthStore(hasManagedCloudAccess)
  const { t } = useTranslation()

  const isCloud = config.llm_provider === 'cloud'
  const requiresApiKey = llmProviderRequiresApiKey(config.llm_provider)
  const polishPromptLength = config.polish_custom_prompt.length
  const hasCustomPolishConfig = config.polish_custom_prompt.trim().length > 0
  const goUpgrade = () => {
    window.location.hash = '#/upgrade'
  }

  const models = useAppStore((s) => s.llmModels)
  const setModels = useAppStore((s) => s.setLlmModels)
  const [fetchingModels, setFetchingModels] = useState(false)
  const [testErrorMessage, setTestErrorMessage] = useState<string | null>(null)
  const [credentialErrorMessage, setCredentialErrorMessage] = useState<string | null>(null)
  const [polishAdvancedOpen, setPolishAdvancedOpen] = useState(hasCustomPolishConfig)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const credentialSaveRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [llmApiKey, setLlmApiKey] = useState(config.llm_api_key)
  const [mappingCandidate, setMappingCandidate] = useState<MappingCandidateView | null>(null)
  const [appMappings, setAppMappings] = useState<CustomAppMappingView[]>([])
  const [appStyleMenuOpen, setAppStyleMenuOpen] = useState(false)
  const [appStyleDialogOpen, setAppStyleDialogOpen] = useState(false)
  const [manageMappingsOpen, setManageMappingsOpen] = useState(false)
  const [editingMapping, setEditingMapping] = useState<CustomAppMappingView | null>(null)
  const appStyleMenuButtonRef = useRef<HTMLButtonElement>(null)
  const showBrowserAccessHint = Boolean(
    config.polish_enabled &&
    config.context_adaptation_enabled &&
    lastContext?.profileId === 'general.browser' &&
    lastContext.browserAccessStatus === 'needs_permission',
  )

  const refreshAppMappings = useCallback(async () => {
    const [candidate, mappings] = await Promise.all([
      getLatestMappingCandidate(),
      listCustomAppMappings(),
    ])
    setMappingCandidate(candidate)
    setAppMappings(mappings)
  }, [])

  useEffect(() => {
    if (!lastContext) {
      setMappingCandidate(null)
      setAppMappings([])
      return
    }
    let cancelled = false
    Promise.all([getLatestMappingCandidate(), listCustomAppMappings()])
      .then(([candidate, mappings]) => {
        if (cancelled) return
        setMappingCandidate(candidate)
        setAppMappings(mappings)
      })
      .catch(() => {
        if (cancelled) return
        setMappingCandidate(null)
        setAppMappings([])
      })
    return () => {
      cancelled = true
    }
  }, [lastContext])

  const closeAppStyleMenu = useCallback((restoreFocus = false) => {
    setAppStyleMenuOpen(false)
    if (restoreFocus) requestAnimationFrame(() => appStyleMenuButtonRef.current?.focus())
  }, [])

  useEffect(() => {
    if (!appStyleMenuOpen) return
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      closeAppStyleMenu(true)
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [appStyleMenuOpen, closeAppStyleMenu])

  useEffect(() => {
    if (hasCustomPolishConfig) setPolishAdvancedOpen(true)
  }, [hasCustomPolishConfig])

  useEffect(() => {
    if (isCloud || !requiresApiKey) {
      setLlmApiKey('')
      setCredentialErrorMessage(null)
      return
    }

    let cancelled = false
    const legacyApiKey = config.llm_api_key
    setLlmApiKey(legacyApiKey)
    setCredentialErrorMessage(null)
    readCredential('llm', config.llm_provider)
      .then((secret) => {
        if (!cancelled) setLlmApiKey(secret ?? legacyApiKey)
      })
      .catch((error) => console.error('[credentials] failed to read LLM credential', error))

    return () => {
      cancelled = true
    }
  }, [config.llm_api_key, config.llm_provider, isCloud, requiresApiKey])

  const persistLlmCredential = useCallback(
    (value: string, delayMs = 350) => {
      if (isCloud || !requiresApiKey) return
      if (credentialSaveRef.current) clearTimeout(credentialSaveRef.current)
      credentialSaveRef.current = setTimeout(() => {
        credentialSaveRef.current = null
        setCredential('llm', config.llm_provider, value)
          .then(() => setCredentialErrorMessage(null))
          .catch((error) => {
            const message = error instanceof Error ? error.message : String(error)
            setCredentialErrorMessage(message)
            console.error('[credentials] failed to save LLM credential', error)
          })
      }, delayMs)
    },
    [config.llm_provider, isCloud, requiresApiKey],
  )

  const doFetchModels = useCallback(
    async (apiKey: string, provider: string, baseUrl: string) => {
      if (!baseUrl) return
      setFetchingModels(true)
      try {
        const list = await fetchLlmModels(apiKey, provider, baseUrl)
        setModels(list)
      } catch {
        // Do not clear existing cache on failure — avoids infinite retry loop
        // (clearing would re-trigger the useEffect that checks models.length > 0)
      } finally {
        setFetchingModels(false)
      }
    },
    [setModels],
  )

  // Auto-fetch when API key or base URL changes (debounced); skips if models already cached
  useEffect(() => {
    if (isCloud) return
    if ((requiresApiKey && !llmApiKey) || !config.llm_base_url) return
    if (models.length > 0) return
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => {
      doFetchModels(llmApiKey, config.llm_provider, config.llm_base_url)
    }, 500)
    return () => {
      if (debounceRef.current) {
        clearTimeout(debounceRef.current)
        debounceRef.current = null
      }
    }
  }, [
    config.llm_base_url,
    config.llm_provider,
    doFetchModels,
    isCloud,
    llmApiKey,
    models.length,
    requiresApiKey,
  ])

  const handleTest = async () => {
    setLlmTestStatus('testing')
    setLlmLatencyMs(null)
    setTestErrorMessage(null)
    try {
      const ms = await benchLlmConnection(
        llmApiKey,
        config.llm_provider,
        config.llm_base_url,
        config.llm_model,
      )
      console.log('[LLM Test] Received latency:', ms, 'type:', typeof ms)
      setLlmLatencyMs(ms)
      setLlmTestStatus('success')
    } catch (err) {
      console.error('[LLM Test] Error:', err)
      setTestErrorMessage(err instanceof Error ? err.message : typeof err === 'string' ? err : null)
      setLlmTestStatus('error')
    }
  }

  const resetTest = () => {
    setLlmTestStatus('idle')
    setLlmLatencyMs(null)
    setTestErrorMessage(null)
  }

  const testButton = (disabled: boolean) => (
    <button
      type="button"
      onClick={handleTest}
      disabled={disabled || llmTestStatus === 'testing'}
      className="btn-outline flex h-10 shrink-0 cursor-pointer items-center gap-1.5 px-4 text-[13px] disabled:cursor-not-allowed disabled:opacity-40"
    >
      {llmTestStatus === 'testing' && <Loader2 size={14} className="animate-spin" />}
      {t('settings.test')}
    </button>
  )

  const connectionFeedback = (
    <>
      {llmTestStatus === 'success' && (
        <p className="m-0 flex items-center gap-1 text-[12px] text-success">
          <CheckCircle2 size={13} />{' '}
          {llmLatencyMs !== null ? `${llmLatencyMs}ms` : t('settings.connectionSuccess')}
        </p>
      )}
      {(llmTestStatus === 'error' || testErrorMessage) && (
        <div className="flex items-start gap-1 text-[12px] text-error">
          <XCircle size={13} className="mt-[1px] flex-shrink-0" />
          <span>{testErrorMessage || t('settings.connectionFailed')}</span>
        </div>
      )}
    </>
  )

  return (
    <div>
      <SettingsRow title={t('settings.provider')} description={t('modelsPage.polishProviderDesc')}>
        <Select
          aria-label={t('settings.provider')}
          value={config.llm_provider}
          options={LLM_PROVIDERS.map((p) => ({ value: p.value, label: t(p.labelKey) }))}
          onChange={(next) => {
            const provider = next as typeof config.llm_provider
            const defaults = LLM_DEFAULT_CONFIG[provider]
            updateConfig({
              llm_provider: provider,
              llm_base_url: defaults?.baseUrl ?? config.llm_base_url,
              llm_model: defaults?.model ?? config.llm_model,
            })
            resetTest()
            setModels([])
          }}
        />
        {isCloud && (
          <div className="flex flex-col items-start gap-1.5 text-[12px] leading-[18px]">
            <span className="flex items-center gap-1.5 font-medium text-text-primary">
              <Crown size={13} className="text-accent" />
              {t('settings.cloudLlmPro')}
            </span>
            {!user ? (
              <span className="text-text-tertiary">{t('settings.llmSignInHint')}</span>
            ) : !hasCloudAccess ? (
              <>
                <span className="text-text-tertiary">{t('settings.llmUpgradeHint')}</span>
                <button
                  type="button"
                  onClick={goUpgrade}
                  className="btn-accent cursor-pointer px-3 py-1 text-[12px] font-medium"
                >
                  {t('nav.upgrade')}
                </button>
              </>
            ) : (
              <span className="text-success">{t('settings.llmProActive')}</span>
            )}
          </div>
        )}
      </SettingsRow>

      {!isCloud && requiresApiKey && (
        <SettingsRow title={t('settings.apiKey')} description={t('modelsPage.apiKeyDesc')}>
          <div className="flex gap-2">
            <input
              type="password"
              aria-label={t('settings.apiKey')}
              value={llmApiKey}
              onChange={(e) => {
                setLlmApiKey(e.target.value)
                persistLlmCredential(e.target.value)
                resetTest()
                setCredentialErrorMessage(null)
              }}
              onBlur={() => persistLlmCredential(llmApiKey, 0)}
              placeholder={t('settings.enterApiKey')}
              className="field-control flex-1"
            />
            {testButton(!llmApiKey)}
          </div>
          {connectionFeedback}
          {credentialErrorMessage && (
            <p className="m-0 text-[11px] text-error">
              {t('settings.credentialSaveFailed', { details: credentialErrorMessage })}
            </p>
          )}
        </SettingsRow>
      )}

      {!isCloud && (
        <SettingsRow title={t('settings.model')} description={t('modelsPage.modelDesc')}>
          <div className="flex gap-2">
            <input
              list="llm-model-list"
              aria-label={t('settings.model')}
              value={config.llm_model}
              onChange={(e) => {
                updateConfig({ llm_model: e.target.value })
                setLlmLatencyMs(null)
                setTestErrorMessage(null)
              }}
              placeholder={t('settings.llmModelPlaceholder')}
              className="field-control flex-1"
            />
            <datalist id="llm-model-list">
              {models.map((m) => (
                <option key={m} value={m} />
              ))}
            </datalist>
            <button
              type="button"
              onClick={() => doFetchModels(llmApiKey, config.llm_provider, config.llm_base_url)}
              disabled={fetchingModels || !config.llm_base_url || (requiresApiKey && !llmApiKey)}
              className="btn-outline grid h-10 w-10 shrink-0 cursor-pointer place-items-center text-text-secondary disabled:cursor-not-allowed disabled:opacity-40"
              title={t('settings.fetchModels')}
              aria-label={t('settings.fetchModels')}
            >
              <RefreshCw size={14} className={fetchingModels ? 'animate-spin' : ''} />
            </button>
          </div>
          {models.length > 0 && (
            <p className="m-0 text-[11px] text-text-tertiary">
              {t('settings.modelsAvailable', { count: models.length })}
            </p>
          )}
        </SettingsRow>
      )}

      <SettingsRow title={t('settings.enableAiPolish')} description={t('modelsPage.polishDesc')}>
        <div className="flex justify-end">
          <Toggle
            checked={config.polish_enabled}
            onChange={(checked) => updateConfig({ polish_enabled: checked })}
            ariaLabel={t('settings.enableAiPolish')}
          />
        </div>
      </SettingsRow>

      {config.polish_enabled && (
        <SettingsRow
          title={t('settings.polishStyle')}
          description={t('modelsPage.polishStyleDesc')}
        >
          <Select
            aria-label={t('settings.polishStyle')}
            value={config.polish_style}
            options={[
              { value: 'minimal', label: t('settings.polishStyleMinimal') },
              { value: 'clean', label: t('settings.polishStyleClean') },
              { value: 'structured', label: t('settings.polishStyleStructured') },
              { value: 'professional', label: t('settings.polishStyleProfessional') },
            ]}
            onChange={(next) => updateConfig({ polish_style: next as PolishStyle })}
          />
        </SettingsRow>
      )}

      <SettingsRow
        title={t('settings.contextAdaptation')}
        description={
          <>
            {t('modelsPage.contextDesc')}
            <ContextAdaptationApps
              disabled={!config.polish_enabled || !config.context_adaptation_enabled}
            />
            {lastContext && (
              <div className="mt-2 min-w-0">
                <p className="m-0 text-[12px] leading-relaxed text-text-tertiary">
                  {t('settings.lastDictationContext')}
                </p>
                <div className="mt-1 flex min-w-0 items-center gap-1.5 text-[12px] text-text-secondary">
                  <AppLogo iconKey={lastContext.iconKey} family={lastContext.family} />
                  <span className="min-w-0 truncate">{lastContext.appLabel}</span>
                  {(mappingCandidate || appMappings.length > 0) && (
                    <div className="relative flex-none">
                      <button
                        ref={appStyleMenuButtonRef}
                        type="button"
                        aria-label={t('settings.appStyleMenu')}
                        title={t('settings.appStyleMenu')}
                        aria-expanded={appStyleMenuOpen}
                        onClick={() => setAppStyleMenuOpen((open) => !open)}
                        className="btn-ghost grid h-7 w-7 cursor-pointer place-items-center"
                      >
                        <MoreHorizontal size={15} />
                      </button>
                      {appStyleMenuOpen && (
                        <>
                          <div
                            className="fixed inset-0 z-30"
                            onClick={() => closeAppStyleMenu(true)}
                          />
                          <div className="surface-popover absolute left-0 top-8 z-40 min-w-[210px] py-1">
                            {mappingCandidate && (
                              <button
                                type="button"
                                onClick={() => {
                                  closeAppStyleMenu()
                                  setEditingMapping(null)
                                  setAppStyleDialogOpen(true)
                                }}
                                className="block w-full border-none bg-transparent px-3 py-2 text-left text-[12px] text-text-secondary hover:bg-fill-hover hover:text-text-primary"
                              >
                                {t('settings.useDifferentWritingStyle')}
                              </button>
                            )}
                            {appMappings.length > 0 && (
                              <button
                                type="button"
                                onClick={() => {
                                  closeAppStyleMenu()
                                  setManageMappingsOpen(true)
                                }}
                                className="block w-full border-none bg-transparent px-3 py-2 text-left text-[12px] text-text-secondary hover:bg-fill-hover hover:text-text-primary"
                              >
                                {t('settings.manageAppMappings')}
                              </button>
                            )}
                          </div>
                        </>
                      )}
                    </div>
                  )}
                </div>
                {showBrowserAccessHint && (
                  <p className="mt-1 text-[12px] leading-relaxed text-warning">
                    {t('settings.browserAccessHint')}
                  </p>
                )}
              </div>
            )}
          </>
        }
      >
        <div className="flex justify-end">
          <Toggle
            checked={config.context_adaptation_enabled}
            disabled={!config.polish_enabled}
            onChange={(checked) => updateConfig({ context_adaptation_enabled: checked })}
            ariaLabel={t('settings.contextAdaptation')}
          />
        </div>
      </SettingsRow>

      <SettingsRow
        title={t('settings.translationMode')}
        description={t('modelsPage.translateDesc')}
      >
        <div className="flex justify-end">
          <Toggle
            checked={config.translate_enabled}
            onChange={(checked) => updateConfig({ translate_enabled: checked })}
            ariaLabel={t('settings.translationMode')}
          />
        </div>
      </SettingsRow>

      <div className="pt-2">
        <button
          type="button"
          aria-expanded={polishAdvancedOpen}
          onClick={() => setPolishAdvancedOpen((open) => !open)}
          className="flex cursor-pointer items-center gap-1 border-none bg-transparent p-0 text-[14px] font-medium text-text-secondary hover:text-text-primary"
        >
          {t('settings.advancedPolishSettings')}
          <ChevronDown
            size={15}
            className={`transition-transform ${polishAdvancedOpen ? 'rotate-180' : ''}`}
          />
        </button>

        {polishAdvancedOpen && (
          <div className="mt-2">
            {!isCloud && (
              <SettingsRow title={t('settings.baseUrl')} description={t('modelsPage.baseUrlDesc')}>
                <div className="flex gap-2">
                  <input
                    aria-label={t('settings.baseUrl')}
                    value={config.llm_base_url}
                    onChange={(e) => {
                      updateConfig({ llm_base_url: e.target.value })
                      resetTest()
                    }}
                    placeholder={
                      LLM_DEFAULT_CONFIG[config.llm_provider]?.baseUrl ??
                      'https://api.openai.com/v1'
                    }
                    className="field-control flex-1"
                  />
                  {!requiresApiKey && testButton(!config.llm_base_url)}
                </div>
                {!requiresApiKey && connectionFeedback}
              </SettingsRow>
            )}

            <SettingsRow
              title={t('settings.selectedTextContext')}
              description={t('settings.selectedTextContextDesc')}
            >
              <div className="flex justify-end">
                <Toggle
                  checked={config.selected_text_enabled}
                  onChange={(checked) => updateConfig({ selected_text_enabled: checked })}
                  ariaLabel={t('settings.selectedTextContext')}
                />
              </div>
            </SettingsRow>

            {config.polish_enabled && (
              <SettingsRow
                stacked
                title={t('settings.customPolishInstructions')}
                description={t('settings.customPolishInstructionsCount', {
                  count: polishPromptLength,
                })}
              >
                <textarea
                  aria-label={t('settings.customPolishInstructions')}
                  value={config.polish_custom_prompt}
                  onChange={(e) => updateConfig({ polish_custom_prompt: e.target.value })}
                  maxLength={2000}
                  rows={4}
                  placeholder={t('settings.customPolishInstructionsPlaceholder')}
                  className="field-control resize-y"
                />
              </SettingsRow>
            )}
          </div>
        )}
      </div>

      {appStyleDialogOpen && lastContext && (
        <AppStyleMappingDialog
          candidate={editingMapping ? null : mappingCandidate}
          mapping={editingMapping}
          context={lastContext}
          config={config}
          onCancel={() => {
            setAppStyleDialogOpen(false)
            setEditingMapping(null)
          }}
          onSaved={async () => {
            await refreshAppMappings()
            setAppStyleDialogOpen(false)
            setEditingMapping(null)
          }}
        />
      )}

      {manageMappingsOpen && (
        <ManageAppMappingsDialog
          mappings={appMappings}
          onCancel={() => setManageMappingsOpen(false)}
          onChanged={refreshAppMappings}
          onEdit={(mapping) => {
            setManageMappingsOpen(false)
            setEditingMapping(mapping)
            setAppStyleDialogOpen(true)
          }}
        />
      )}
    </div>
  )
}
