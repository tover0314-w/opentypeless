import { useState, useEffect, useCallback, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppStore } from '../../stores/appStore'
import { useAuthStore } from '../../stores/authStore'
import { LLM_PROVIDERS, LLM_DEFAULT_CONFIG, TARGET_LANGUAGES } from '../../lib/constants'
import { benchLlmConnection, fetchLlmModels } from '../../lib/tauri'
import { FormField } from './shared/FormField'
import { Toggle } from './shared/Toggle'
import { CheckCircle2, XCircle, Loader2, RefreshCw, Crown } from 'lucide-react'

export function LlmPane() {
  const config = useAppStore((s) => s.config)
  const updateConfig = useAppStore((s) => s.updateConfig)
  const llmTestStatus = useAppStore((s) => s.llmTestStatus)
  const setLlmTestStatus = useAppStore((s) => s.setLlmTestStatus)
  const llmLatencyMs = useAppStore((s) => s.llmLatencyMs)
  const setLlmLatencyMs = useAppStore((s) => s.setLlmLatencyMs)
  const { user, plan } = useAuthStore()
  const { t } = useTranslation()

  const isCloud = config.llm_provider === 'cloud'

  const models = useAppStore((s) => s.llmModels)
  const setModels = useAppStore((s) => s.setLlmModels)
  const [fetchingModels, setFetchingModels] = useState(false)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const doFetchModels = useCallback(
    async (apiKey: string, baseUrl: string) => {
      if (!baseUrl) return
      setFetchingModels(true)
      try {
        const list = await fetchLlmModels(apiKey, baseUrl)
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
    if (!config.llm_api_key || !config.llm_base_url) return
    if (models.length > 0) return
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => {
      doFetchModels(config.llm_api_key, config.llm_base_url)
    }, 500)
    return () => {
      if (debounceRef.current) {
        clearTimeout(debounceRef.current)
        debounceRef.current = null
      }
    }
  }, [config.llm_api_key, config.llm_base_url, doFetchModels, isCloud, models.length])

  const handleTest = async () => {
    setLlmTestStatus('testing')
    setLlmLatencyMs(null)
    try {
      const ms = await benchLlmConnection(
        config.llm_api_key,
        config.llm_provider,
        config.llm_base_url,
        config.llm_model,
      )
      console.log('[LLM Test] Received latency:', ms, 'type:', typeof ms)
      setLlmLatencyMs(ms)
      setLlmTestStatus('success')
    } catch (err) {
      console.error('[LLM Test] Error:', err)
      setLlmTestStatus('error')
    }
  }

  return (
    <div className="space-y-5">
      <FormField label={t('settings.provider')}>
        <select
          value={config.llm_provider}
          onChange={(e) => {
            const provider = e.target.value as typeof config.llm_provider
            const defaults = LLM_DEFAULT_CONFIG[provider]
            updateConfig({
              llm_provider: provider,
              llm_base_url: defaults?.baseUrl ?? config.llm_base_url,
              llm_model: defaults?.model ?? config.llm_model,
            })
            setLlmTestStatus('idle')
            setLlmLatencyMs(null)
            setModels([])
          }}
          className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
        >
          {LLM_PROVIDERS.map((p) => (
            <option key={p.value} value={p.value}>
              {p.label}
            </option>
          ))}
        </select>
      </FormField>

      {isCloud ? (
        <div className="border border-border rounded-[10px] px-3 py-3 space-y-2">
          <div className="flex items-center gap-2 text-[13px]">
            <Crown size={14} className="text-accent" />
            <span className="text-text-primary font-medium">{t('settings.cloudLlmPro')}</span>
          </div>
          {!user ? (
            <p className="text-[12px] text-text-secondary">{t('settings.llmSignInHint')}</p>
          ) : plan !== 'pro' ? (
            <p className="text-[12px] text-text-secondary">{t('settings.llmUpgradeHint')}</p>
          ) : (
            <p className="text-[12px] text-green-500">{t('settings.llmProActive')}</p>
          )}
        </div>
      ) : (
        <>
          <FormField label={t('settings.apiKey')}>
            <div className="flex gap-2">
              <input
                type="password"
                value={config.llm_api_key}
                onChange={(e) => {
                  updateConfig({ llm_api_key: e.target.value })
                  setLlmTestStatus('idle')
                  setLlmLatencyMs(null)
                }}
                placeholder={t('settings.enterApiKey')}
                className="flex-1 px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
              />
              <button
                onClick={handleTest}
                disabled={!config.llm_api_key || llmTestStatus === 'testing'}
                className="px-4 py-2.5 bg-accent text-white rounded-[10px] text-[13px] border-none cursor-pointer hover:bg-accent-hover disabled:opacity-40 disabled:cursor-not-allowed transition-colors flex items-center gap-1.5"
              >
                {llmTestStatus === 'testing' && <Loader2 size={14} className="animate-spin" />}
                {t('settings.test')}
              </button>
            </div>
            {llmTestStatus === 'success' && (
              <p className="flex items-center gap-1 text-[12px] text-success mt-2">
                <CheckCircle2 size={13} />{' '}
                {llmLatencyMs !== null ? `${llmLatencyMs}ms` : t('settings.connectionSuccess')}
              </p>
            )}
            {llmTestStatus === 'error' && (
              <p className="flex items-center gap-1 text-[12px] text-error mt-2">
                <XCircle size={13} /> {t('settings.connectionFailed')}
              </p>
            )}
            <p className="text-[11px] text-text-tertiary mt-1.5">{t('settings.storedLocally')}</p>
          </FormField>

          <FormField label={t('settings.model')}>
            <div className="flex gap-2">
              <div className="relative flex-1">
                <input
                  list="llm-model-list"
                  value={config.llm_model}
                  onChange={(e) => {
                    updateConfig({ llm_model: e.target.value })
                    setLlmLatencyMs(null)
                  }}
                  placeholder="e.g. gpt-4o-mini"
                  className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
                />
                <datalist id="llm-model-list">
                  {models.map((m) => (
                    <option key={m} value={m} />
                  ))}
                </datalist>
              </div>
              <button
                onClick={() => doFetchModels(config.llm_api_key, config.llm_base_url)}
                disabled={fetchingModels || !config.llm_base_url}
                className="px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-secondary cursor-pointer hover:border-border-focus disabled:opacity-40 disabled:cursor-not-allowed transition-colors flex items-center gap-1.5"
                title={t('settings.fetchModels')}
              >
                <RefreshCw size={14} className={fetchingModels ? 'animate-spin' : ''} />
              </button>
            </div>
            {models.length > 0 && (
              <p className="text-[11px] text-text-tertiary mt-1">
                {t('settings.modelsAvailable', { count: models.length })}
              </p>
            )}
          </FormField>

          <FormField label={t('settings.baseUrl')}>
            <input
              value={config.llm_base_url}
              onChange={(e) => updateConfig({ llm_base_url: e.target.value })}
              placeholder="https://open.bigmodel.cn/api/paas/v4"
              className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
            />
          </FormField>
        </>
      )}

      <div className="space-y-3 pt-1">
        <Toggle
          checked={config.polish_enabled}
          onChange={(checked) => updateConfig({ polish_enabled: checked })}
          label={t('settings.enableAiPolish')}
        />
        <Toggle
          checked={config.translate_enabled}
          onChange={(checked) => updateConfig({ translate_enabled: checked })}
          label={t('settings.translationMode')}
        />
        <Toggle
          checked={config.selected_text_enabled}
          onChange={(checked) => updateConfig({ selected_text_enabled: checked })}
          label={t('settings.selectedTextContext')}
        />
        {config.selected_text_enabled && (
          <p className="text-[11px] text-text-tertiary -mt-1 ml-[52px]">
            {t('settings.selectedTextContextDesc')}
          </p>
        )}
      </div>

      {config.translate_enabled && (
        <FormField label={t('settings.targetLanguage')}>
          <select
            value={config.target_lang}
            onChange={(e) => updateConfig({ target_lang: e.target.value })}
            className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
          >
            {TARGET_LANGUAGES.map((l) => (
              <option key={l.value} value={l.value}>
                {l.label}
              </option>
            ))}
          </select>
        </FormField>
      )}
    </div>
  )
}
