import { useState, useEffect, useCallback, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../../stores/appStore'
import type { PolishStyle } from '../../stores/appStore'
import { hasManagedCloudAccess, useAuthStore } from '../../stores/authStore'
import {
  LLM_PROVIDERS,
  LLM_DEFAULT_CONFIG,
  TARGET_LANGUAGES,
  llmProviderRequiresApiKey,
} from '../../lib/constants'
import {
  benchLlmConnection,
  fetchLlmModels,
  getLatestMappingCandidate,
  listCustomAppMappings,
} from '../../lib/tauri'
import type { CustomAppMappingView, MappingCandidateView } from '../../lib/tauri'
import { SettingsControlGroup, SettingsRow, SettingsSection } from './shared/SettingsRow'
import type { ReactNode } from 'react'
import { Select } from '../shared/Select'
import { ProviderLogo } from '../shared/ProviderLogo'
import { ModelInput } from '../shared/ModelInput'
import { useProviderCredential } from '../../hooks/useProviderCredential'
import { credentialErrorDetails } from '../../lib/credentialErrorDetails'
import { providerErrorMessage } from '../../lib/providerErrorMessage'
import { Toggle } from './shared/Toggle'
import { TranslationTargets } from './TranslationTargets'
import { LanguageVariants } from './LanguageVariants'
import {
  CheckCircle2,
  XCircle,
  Loader2,
  RefreshCw,
  Crown,
  ChevronDown,
  MoreHorizontal,
  Sparkles,
  SlidersHorizontal,
} from 'lucide-react'
import { AppLogo } from '../AppLogo'
import { ContextAdaptationApps } from './ContextAdaptationApps'
import { AppStyleMappingDialog } from './AppStyleMappingDialog'
import { ManageAppMappingsDialog } from './ManageAppMappingsDialog'

function ModelGroup({
  enabled,
  processing = false,
  children,
}: {
  enabled: boolean
  processing?: boolean
  children: ReactNode
}) {
  const { t } = useTranslation()
  if (!enabled) return <>{children}</>
  return (
    <SettingsSection
      icon={processing ? SlidersHorizontal : Sparkles}
      title={t(processing ? 'modelsPage.textProcessing' : 'modelsPage.aiModel')}
    >
      {children}
    </SettingsSection>
  )
}

export function LlmPane({ grouped = false }: { grouped?: boolean }) {
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
  const [polishAdvancedOpen, setPolishAdvancedOpen] = useState(hasCustomPolishConfig)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const modelRequestId = useRef(0)
  const pendingModelRequest = useRef<number | null>(null)
  const modelEndpoint = useRef({
    provider: config.llm_provider,
    baseUrl: config.llm_base_url,
    apiKey: config.llm_api_key,
  })
  useEffect(() => {
    const previous = modelEndpoint.current
    if (
      previous.provider === config.llm_provider &&
      previous.baseUrl === config.llm_base_url &&
      previous.apiKey === config.llm_api_key
    ) {
      return
    }
    modelEndpoint.current = {
      provider: config.llm_provider,
      baseUrl: config.llm_base_url,
      apiKey: config.llm_api_key,
    }
    modelRequestId.current += 1
    pendingModelRequest.current = null
    setFetchingModels(false)
  }, [config.llm_provider, config.llm_base_url, config.llm_api_key])
  const testRequestId = useRef(0)
  const testPending = useRef(false)
  const testIdentity = JSON.stringify([
    config.llm_provider,
    config.llm_model,
    config.llm_base_url,
    config.llm_api_key,
  ])
  const previousTestIdentity = useRef(testIdentity)
  useEffect(() => {
    if (previousTestIdentity.current === testIdentity) return
    previousTestIdentity.current = testIdentity
    testRequestId.current += 1
    testPending.current = false
    setLlmTestStatus('idle')
    setLlmLatencyMs(null)
    setTestErrorMessage(null)
  }, [testIdentity, setLlmTestStatus, setLlmLatencyMs])
  useEffect(
    () => () => {
      testRequestId.current += 1
      modelRequestId.current += 1
      if (testPending.current) {
        testPending.current = false
        setLlmTestStatus('idle')
      }
    },
    [setLlmTestStatus],
  )
  const {
    value: llmApiKey,
    error: credentialErrorMessage,
    edit: editCredential,
    flush: flushCredential,
  } = useProviderCredential(
    'llm',
    config.llm_provider,
    config.llm_api_key,
    !isCloud && requiresApiKey,
  )
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
    if (!grouped) return
    const openRequestedSection = () => {
      const [path, query] = window.location.hash.split('?')
      if (path === '#/models' && new URLSearchParams(query).get('section') === 'translation') {
        setPolishAdvancedOpen(true)
      }
    }
    openRequestedSection()
    window.addEventListener('hashchange', openRequestedSection)
    return () => window.removeEventListener('hashchange', openRequestedSection)
  }, [grouped])

  const doFetchModels = useCallback(
    async (apiKey: string, provider: string, baseUrl: string) => {
      if (!baseUrl.trim() || (llmProviderRequiresApiKey(provider) && !apiKey.trim())) return
      if (pendingModelRequest.current !== null) return
      const stateBeforeRequest = useAppStore.getState()
      if (
        stateBeforeRequest.config.llm_provider !== provider ||
        stateBeforeRequest.config.llm_base_url !== baseUrl ||
        stateBeforeRequest.config.llm_api_key !== config.llm_api_key
      )
        return
      const requestId = ++modelRequestId.current
      const cacheBeforeRequest = stateBeforeRequest.llmModels
      const configBeforeRequest = stateBeforeRequest.config
      pendingModelRequest.current = requestId
      setFetchingModels(true)
      try {
        const list = await fetchLlmModels(apiKey, provider, baseUrl)
        const currentState = useAppStore.getState()
        if (
          requestId !== modelRequestId.current ||
          currentState.llmModels !== cacheBeforeRequest ||
          currentState.config.llm_provider !== configBeforeRequest.llm_provider ||
          currentState.config.llm_base_url !== configBeforeRequest.llm_base_url ||
          currentState.config.llm_api_key !== configBeforeRequest.llm_api_key
        )
          return
        setModels(list)
      } catch {
        // Do not clear existing cache on failure — avoids infinite retry loop
        // (clearing would re-trigger the useEffect that checks models.length > 0)
      } finally {
        if (pendingModelRequest.current === requestId) pendingModelRequest.current = null
        if (requestId === modelRequestId.current) setFetchingModels(false)
      }
    },
    [setModels, config.llm_api_key],
  )

  // Auto-fetch when API key or base URL changes (debounced); skips if models already cached
  useEffect(() => {
    if (isCloud) return
    if ((requiresApiKey && !llmApiKey.trim()) || !config.llm_base_url.trim()) return
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
    const isCurrentConfig = () => {
      const currentConfig = useAppStore.getState().config
      return (
        currentConfig.llm_provider === config.llm_provider &&
        currentConfig.llm_base_url === config.llm_base_url &&
        currentConfig.llm_api_key === config.llm_api_key &&
        currentConfig.llm_model === config.llm_model
      )
    }
    if (!isCurrentConfig()) return
    if (testPending.current || !config.llm_base_url.trim() || (requiresApiKey && !llmApiKey.trim()))
      return
    const requestId = ++testRequestId.current
    testPending.current = true
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
      if (requestId !== testRequestId.current || !isCurrentConfig()) return
      testPending.current = false
      setLlmLatencyMs(ms)
      setLlmTestStatus('success')
    } catch (err) {
      if (requestId !== testRequestId.current || !isCurrentConfig()) return
      testPending.current = false
      console.error('[LLM Test] Connection failed')
      setTestErrorMessage(providerErrorMessage(err, llmApiKey, t('settings.connectionFailed')))
      setLlmTestStatus('error')
    }
  }

  const resetTest = () => {
    testRequestId.current += 1
    modelRequestId.current += 1
    pendingModelRequest.current = null
    setFetchingModels(false)
    testPending.current = false
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

  const connectionAddress = (
    <SettingsRow title={t('settings.baseUrl')} description={t('modelsPage.baseUrlDesc')}>
      <SettingsControlGroup action={!requiresApiKey && testButton(!config.llm_base_url.trim())}>
        <input
          aria-label={t('settings.baseUrl')}
          value={config.llm_base_url}
          onChange={(event) => {
            updateConfig({ llm_base_url: event.target.value })
            resetTest()
          }}
          placeholder={LLM_DEFAULT_CONFIG[config.llm_provider]?.baseUrl}
          className="field-control"
        />
      </SettingsControlGroup>
      {!requiresApiKey && connectionFeedback}
    </SettingsRow>
  )

  return (
    <div>
      <ModelGroup enabled={grouped}>
        <SettingsRow
          title={t('settings.provider')}
          description={t('modelsPage.polishProviderDesc')}
        >
          <Select
            aria-label={t('settings.provider')}
            value={config.llm_provider}
            options={[...LLM_PROVIDERS]
              .sort(
                (left, right) => Number(right.value === 'cloud') - Number(left.value === 'cloud'),
              )
              .map((provider) => ({
                value: provider.value,
                label:
                  provider.value === 'cloud'
                    ? `${t(provider.labelKey)} · ${t('modelsPage.recommended')}`
                    : t(provider.labelKey),
                leading: <ProviderLogo provider={provider.value} />,
              }))}
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
            <SettingsControlGroup
              action={testButton(!llmApiKey.trim() || !config.llm_base_url.trim())}
            >
              <input
                type="password"
                aria-label={t('settings.apiKey')}
                value={llmApiKey}
                onChange={(e) => {
                  editCredential(e.target.value)
                  resetTest()
                }}
                onBlur={() => void flushCredential()}
                placeholder={t('settings.enterApiKey')}
                className="field-control"
              />
            </SettingsControlGroup>
            {connectionFeedback}
            {credentialErrorMessage && (
              <p className="m-0 text-[11px] text-error">
                {credentialErrorMessage === 'Could not read credential from the system vault'
                  ? t('common.loadFailed')
                  : t('settings.credentialSaveFailed', {
                      details: credentialErrorDetails(credentialErrorMessage),
                    })}
              </p>
            )}
          </SettingsRow>
        )}

        {!isCloud && (
          <SettingsRow title={t('settings.model')} description={t('modelsPage.modelDesc')}>
            <SettingsControlGroup
              action={
                <button
                  type="button"
                  onClick={() => doFetchModels(llmApiKey, config.llm_provider, config.llm_base_url)}
                  disabled={
                    fetchingModels ||
                    !config.llm_base_url.trim() ||
                    (requiresApiKey && !llmApiKey.trim())
                  }
                  className="btn-outline grid h-10 w-10 shrink-0 cursor-pointer place-items-center text-text-secondary disabled:cursor-not-allowed disabled:opacity-40"
                  title={t('settings.fetchModels')}
                  aria-label={t('settings.fetchModels')}
                >
                  <RefreshCw size={14} className={fetchingModels ? 'animate-spin' : ''} />
                </button>
              }
            >
              <ModelInput
                label={t('settings.model')}
                value={config.llm_model}
                models={models}
                onChange={(model) => {
                  updateConfig({ llm_model: model })
                  resetTest()
                }}
                placeholder={t('settings.llmModelPlaceholder')}
              />
            </SettingsControlGroup>
            {models.length > 0 && (
              <p className="m-0 text-[11px] text-text-tertiary">
                {t('settings.modelsAvailable', { count: models.length })}
              </p>
            )}
          </SettingsRow>
        )}

        {grouped &&
          !isCloud &&
          (requiresApiKey ? (
            <details className="mt-3 border-t border-border-thin pt-4">
              <summary className="w-fit cursor-pointer text-[13px] text-text-secondary">
                {t('modelsPage.advanced')}
              </summary>
              {connectionAddress}
            </details>
          ) : (
            connectionAddress
          ))}
      </ModelGroup>
      <ModelGroup enabled={grouped} processing>
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
                { value: 'custom', label: t('recordingLimits.custom') },
              ]}
              onChange={(next) => updateConfig({ polish_style: next as PolishStyle })}
            />
            {config.polish_style === 'custom' && (
              <>
                <p className="m-0 text-[12px] leading-5 text-text-tertiary">
                  {t('settings.customPolishInstructionsCount', { count: polishPromptLength })}
                </p>
                <textarea
                  aria-label={t('settings.customPolishInstructions')}
                  value={config.polish_custom_prompt}
                  onChange={(event) => updateConfig({ polish_custom_prompt: event.target.value })}
                  maxLength={2000}
                  rows={4}
                  placeholder={t('settings.customPolishInstructionsPlaceholder')}
                  className="field-control resize-y"
                />
              </>
            )}
          </SettingsRow>
        )}

        <SettingsRow
          title={t('settings.contextAdaptation')}
          description={
            <>
              {t('modelsPage.contextDesc')}
              {(grouped || (config.polish_enabled && config.context_adaptation_enabled)) && (
                <ContextAdaptationApps
                  disabled={!config.polish_enabled || !config.context_adaptation_enabled}
                  showCounts={!grouped}
                />
              )}
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

        {grouped ? (
          <SettingsRow
            title={t('settings.translationTarget')}
            description={t('modelsPage.translationLanguageDesc')}
          >
            <Select
              aria-label={t('settings.translationTarget')}
              value={config.translation.active_target}
              options={TARGET_LANGUAGES.map((language) => ({
                value: language.value,
                label: language.labelKey ? t(language.labelKey) : language.label,
                disabled:
                  config.translation.targets.length >= 5 &&
                  !config.translation.targets.includes(language.value),
              }))}
              onChange={(activeTarget) => {
                if (
                  config.translation.targets.length >= 5 &&
                  !config.translation.targets.includes(activeTarget)
                )
                  return
                updateConfig({
                  translation: {
                    targets: config.translation.targets.includes(activeTarget)
                      ? config.translation.targets
                      : [...config.translation.targets, activeTarget],
                    active_target: activeTarget,
                  },
                })
              }}
            />
          </SettingsRow>
        ) : (
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
        )}

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
              {grouped && (
                <SettingsRow
                  title={t('generalPane.translationTargets')}
                  description={t('generalPane.translationTargetsDesc')}
                >
                  <TranslationTargets
                    value={config.translation}
                    onChange={(translation) => updateConfig({ translation })}
                  />
                </SettingsRow>
              )}
              {grouped && (
                <SettingsRow
                  title={t('languageVariants.title')}
                  description={t('languageVariants.description')}
                >
                  <LanguageVariants />
                </SettingsRow>
              )}
              {grouped && (
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
              )}
              {!grouped && !isCloud && (
                <SettingsRow
                  title={t('settings.baseUrl')}
                  description={t('modelsPage.baseUrlDesc')}
                >
                  <SettingsControlGroup
                    action={!requiresApiKey && testButton(!config.llm_base_url.trim())}
                  >
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
                      className="field-control"
                    />
                  </SettingsControlGroup>
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

              {config.polish_enabled && config.polish_style !== 'custom' && (
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
      </ModelGroup>
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
