import { useState, useEffect, useCallback, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppStore, type LlmProvider } from '../../stores/appStore'
import {
  LLM_DEFAULT_CONFIG,
  ONBOARDING_LLM_PROVIDERS,
  llmProviderRequiresApiKey,
} from '../../lib/constants'
import { testLlmConnection, fetchLlmModels } from '../../lib/tauri'
import { Select } from '../shared/Select'
import { ModelInput } from '../shared/ModelInput'
import { CheckCircle2, XCircle, Loader2, RefreshCw } from 'lucide-react'

export function LlmSetupStep() {
  const { t } = useTranslation()
  const config = useAppStore((s) => s.config)
  const updateConfig = useAppStore((s) => s.updateConfig)
  const llmTestStatus = useAppStore((s) => s.llmTestStatus)
  const setLlmTestStatus = useAppStore((s) => s.setLlmTestStatus)

  const [models, setModels] = useState<string[]>([])
  const [fetchingModels, setFetchingModels] = useState(false)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const testRequestId = useRef(0)
  const testPending = useRef(false)
  const modelRequestId = useRef(0)
  const fallbackProvider =
    (ONBOARDING_LLM_PROVIDERS[0]?.value as LlmProvider | undefined) ?? 'zhipu'
  const selectedProvider: LlmProvider = ONBOARDING_LLM_PROVIDERS.some(
    (provider) => provider.value === config.llm_provider,
  )
    ? config.llm_provider
    : fallbackProvider
  const requiresApiKey = llmProviderRequiresApiKey(selectedProvider)
  const endpointIdentity = JSON.stringify([
    selectedProvider,
    config.llm_api_key,
    config.llm_base_url,
  ])
  const testIdentity = JSON.stringify([endpointIdentity, config.llm_model])
  const previousEndpointIdentity = useRef(endpointIdentity)
  const previousTestIdentity = useRef(testIdentity)

  useEffect(() => {
    if (previousTestIdentity.current === testIdentity) return
    previousTestIdentity.current = testIdentity
    testRequestId.current += 1
    testPending.current = false
    setLlmTestStatus('idle')
  }, [testIdentity, setLlmTestStatus])

  useEffect(() => {
    if (previousEndpointIdentity.current === endpointIdentity) return
    previousEndpointIdentity.current = endpointIdentity
    modelRequestId.current += 1
    setModels([])
    setFetchingModels(false)
  }, [endpointIdentity])

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

  useEffect(() => {
    if (selectedProvider === config.llm_provider) return
    const defaults = LLM_DEFAULT_CONFIG[selectedProvider]
    updateConfig({
      llm_provider: selectedProvider,
      llm_base_url: defaults?.baseUrl ?? config.llm_base_url,
      llm_model: defaults?.model ?? config.llm_model,
    })
    setLlmTestStatus('idle')
    setModels([])
  }, [
    config.llm_base_url,
    config.llm_model,
    config.llm_provider,
    selectedProvider,
    setLlmTestStatus,
    updateConfig,
  ])

  const doFetchModels = useCallback(async (apiKey: string, provider: string, baseUrl: string) => {
    if (!baseUrl) return
    const requestId = ++modelRequestId.current
    setFetchingModels(true)
    try {
      const list = await fetchLlmModels(apiKey, provider, baseUrl)
      if (requestId === modelRequestId.current) setModels(list)
    } catch {
      if (requestId === modelRequestId.current) setModels([])
    } finally {
      if (requestId === modelRequestId.current) setFetchingModels(false)
    }
  }, [])

  // Auto-fetch when API key changes (debounced)
  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current)
    if ((requiresApiKey && !config.llm_api_key) || !config.llm_base_url) return
    debounceRef.current = setTimeout(() => {
      doFetchModels(config.llm_api_key, selectedProvider, config.llm_base_url)
    }, 500)
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current)
    }
  }, [config.llm_api_key, config.llm_base_url, doFetchModels, requiresApiKey, selectedProvider])

  const handleTest = async () => {
    if (testPending.current) return
    const requestId = ++testRequestId.current
    testPending.current = true
    setLlmTestStatus('testing')
    try {
      const ok = await testLlmConnection(
        config.llm_api_key,
        selectedProvider,
        config.llm_base_url,
        config.llm_model,
      )
      if (requestId === testRequestId.current) setLlmTestStatus(ok ? 'success' : 'error')
    } catch {
      if (requestId === testRequestId.current) setLlmTestStatus('error')
    } finally {
      if (requestId === testRequestId.current) testPending.current = false
    }
  }

  return (
    <div className="space-y-5">
      <Field label={t('onboarding.llm.serviceLabel')}>
        <Select
          aria-label={t('onboarding.llm.serviceLabel')}
          value={selectedProvider}
          onChange={(value) => {
            const provider = value as typeof config.llm_provider
            const defaults = LLM_DEFAULT_CONFIG[provider]
            updateConfig({
              llm_provider: provider,
              llm_base_url: defaults?.baseUrl ?? config.llm_base_url,
              llm_model: defaults?.model ?? config.llm_model,
            })
            setLlmTestStatus('idle')
            setModels([])
          }}
          options={ONBOARDING_LLM_PROVIDERS.map((p) => ({ value: p.value, label: t(p.labelKey) }))}
        />
      </Field>

      {requiresApiKey && (
        <Field label={t('onboarding.llm.apiKeyLabel')}>
          <div className="flex gap-2">
            <input
              type="password"
              aria-label={t('onboarding.llm.apiKeyLabel')}
              value={config.llm_api_key}
              onChange={(e) => {
                updateConfig({ llm_api_key: e.target.value })
                setLlmTestStatus('idle')
              }}
              placeholder={t('onboarding.llm.apiKeyPlaceholder')}
              className="field-control min-w-0 flex-1"
            />
            <button
              type="button"
              onClick={handleTest}
              disabled={!config.llm_api_key || llmTestStatus === 'testing'}
              className="btn-outline flex h-10 shrink-0 cursor-pointer items-center gap-1.5 px-4 text-[13px] disabled:cursor-not-allowed disabled:opacity-40"
            >
              {llmTestStatus === 'testing' && <Loader2 size={14} className="animate-spin" />}
              {t('onboarding.llm.testButton')}
            </button>
          </div>
          <TestStatusHint status={llmTestStatus} />
        </Field>
      )}

      <Field label={t('onboarding.llm.modelLabel')}>
        <div className="flex gap-2">
          <div className="relative min-w-0 flex-1">
            <ModelInput
              label={t('onboarding.llm.modelLabel')}
              value={config.llm_model}
              models={models}
              onChange={(model) => updateConfig({ llm_model: model })}
              placeholder={t('onboarding.llm.modelPlaceholder')}
            />
          </div>
          <button
            type="button"
            onClick={() => doFetchModels(config.llm_api_key, selectedProvider, config.llm_base_url)}
            disabled={
              fetchingModels || !config.llm_base_url || (requiresApiKey && !config.llm_api_key)
            }
            className="btn-outline grid h-10 w-10 shrink-0 cursor-pointer place-items-center disabled:cursor-not-allowed disabled:opacity-40"
            title={t('onboarding.llm.fetchModelsTitle')}
          >
            <RefreshCw size={14} className={fetchingModels ? 'animate-spin' : ''} />
          </button>
        </div>
        {models.length > 0 && (
          <p className="text-[11px] text-text-tertiary mt-1">
            {t('onboarding.llm.modelsAvailable', { count: models.length })}
          </p>
        )}
      </Field>

      <Field label={t('onboarding.llm.baseUrlLabel')}>
        <div className="flex gap-2">
          <input
            aria-label={t('onboarding.llm.baseUrlLabel')}
            value={config.llm_base_url}
            onChange={(e) => updateConfig({ llm_base_url: e.target.value })}
            placeholder={
              LLM_DEFAULT_CONFIG[selectedProvider]?.baseUrl ?? 'https://api.openai.com/v1'
            }
            className="field-control min-w-0 flex-1"
          />
          {!requiresApiKey && (
            <button
              type="button"
              onClick={handleTest}
              disabled={!config.llm_base_url || llmTestStatus === 'testing'}
              className="btn-outline flex h-10 shrink-0 cursor-pointer items-center gap-1.5 px-4 text-[13px] disabled:cursor-not-allowed disabled:opacity-40"
            >
              {llmTestStatus === 'testing' && <Loader2 size={14} className="animate-spin" />}
              {t('onboarding.llm.testButton')}
            </button>
          )}
        </div>
        {!requiresApiKey && <TestStatusHint status={llmTestStatus} />}
      </Field>
    </div>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="block text-[13px] font-medium text-text-secondary mb-2">{label}</label>
      {children}
    </div>
  )
}

function TestStatusHint({ status }: { status: string }) {
  const { t } = useTranslation()
  if (status === 'success') {
    return (
      <p className="flex items-center gap-1 text-[12px] text-success mt-2">
        <CheckCircle2 size={13} /> {t('onboarding.llm.connectionOk')}
      </p>
    )
  }
  if (status === 'error') {
    return (
      <p className="flex items-center gap-1 text-[12px] text-error mt-2">
        <XCircle size={13} /> {t('onboarding.llm.connectionFail')}
      </p>
    )
  }
  return null
}
