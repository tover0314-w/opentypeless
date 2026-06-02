import { useTranslation } from 'react-i18next'
import { useAppStore } from '../../stores/appStore'
import { useAuthStore } from '../../stores/authStore'
import {
  STT_PROVIDERS,
  LANGUAGES,
  CUSTOM_WHISPER_PROVIDER,
  CUSTOM_STT_DEFAULTS,
  CUSTOM_STT_PRESETS,
} from '../../lib/constants'
import { benchSttConnection } from '../../lib/tauri'
import { FormField } from './shared/FormField'
import { CheckCircle2, XCircle, Loader2, Crown } from 'lucide-react'

export function SttPane() {
  const config = useAppStore((s) => s.config)
  const updateConfig = useAppStore((s) => s.updateConfig)
  const sttTestStatus = useAppStore((s) => s.sttTestStatus)
  const setSttTestStatus = useAppStore((s) => s.setSttTestStatus)
  const sttLatencyMs = useAppStore((s) => s.sttLatencyMs)
  const setSttLatencyMs = useAppStore((s) => s.setSttLatencyMs)
  const { user, plan } = useAuthStore()
  const { t } = useTranslation()

  const isCloud = config.stt_provider === 'cloud'
  const isCustomWhisper = config.stt_provider === CUSTOM_WHISPER_PROVIDER
  const canTest = isCustomWhisper
    ? Boolean(config.stt_custom_base_url.trim() && config.stt_custom_model.trim())
    : Boolean(config.stt_api_key)

  const handleTest = async () => {
    setSttTestStatus('testing')
    setSttLatencyMs(null)
    try {
      const ms = isCustomWhisper
        ? await benchSttConnection(
            config.stt_api_key,
            config.stt_provider,
            config.stt_custom_base_url,
            config.stt_custom_model,
          )
        : await benchSttConnection(config.stt_api_key, config.stt_provider)
      console.log('[STT Test] Received latency:', ms, 'type:', typeof ms)
      setSttLatencyMs(ms)
      setSttTestStatus('success')
    } catch (err) {
      console.error('[STT Test] Error:', err)
      setSttTestStatus('error')
    }
  }

  return (
    <div className="space-y-5">
      <FormField label={t('settings.provider')}>
        <select
          value={config.stt_provider}
          onChange={(e) => {
            const provider = e.target.value as typeof config.stt_provider
            updateConfig({
              stt_provider: provider,
              ...(provider === CUSTOM_WHISPER_PROVIDER
                ? {
                    stt_custom_preset:
                      config.stt_custom_preset || CUSTOM_STT_DEFAULTS.preset,
                    stt_custom_base_url:
                      config.stt_custom_base_url || CUSTOM_STT_DEFAULTS.baseUrl,
                    stt_custom_model: config.stt_custom_model || CUSTOM_STT_DEFAULTS.model,
                  }
                : {}),
            })
            setSttTestStatus('idle')
            setSttLatencyMs(null)
          }}
          className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
        >
          {STT_PROVIDERS.map((p) => (
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
            <span className="text-text-primary font-medium">{t('settings.cloudSttPro')}</span>
          </div>
          {!user ? (
            <p className="text-[12px] text-text-secondary">{t('settings.sttSignInHint')}</p>
          ) : plan !== 'pro' ? (
            <p className="text-[12px] text-text-secondary">{t('settings.sttUpgradeHint')}</p>
          ) : (
            <p className="text-[12px] text-green-500">{t('settings.sttProActive')}</p>
          )}
        </div>
      ) : (
        <>
          {isCustomWhisper && (
            <>
              <FormField label={t('settings.customSttPreset')}>
                <select
                  value={config.stt_custom_preset}
                  onChange={(e) => {
                    const preset = e.target.value as typeof config.stt_custom_preset
                    const selected = CUSTOM_STT_PRESETS.find((p) => p.value === preset)
                    const hasDefaults = selected && 'baseUrl' in selected && 'model' in selected
                    updateConfig({
                      stt_custom_preset: preset,
                      ...(hasDefaults
                        ? {
                            stt_custom_base_url: selected.baseUrl,
                            stt_custom_model: selected.model,
                          }
                        : {}),
                    })
                    setSttTestStatus('idle')
                    setSttLatencyMs(null)
                  }}
                  className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
                >
                  {CUSTOM_STT_PRESETS.map((preset) => (
                    <option key={preset.value} value={preset.value}>
                      {t(preset.labelKey)}
                    </option>
                  ))}
                </select>
              </FormField>

              <FormField label={t('settings.customSttBaseUrl')}>
                <input
                  value={config.stt_custom_base_url}
                  onChange={(e) => {
                    updateConfig({ stt_custom_base_url: e.target.value })
                    setSttTestStatus('idle')
                    setSttLatencyMs(null)
                  }}
                  placeholder={t('settings.customSttBaseUrlPlaceholder')}
                  className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
                />
              </FormField>

              <FormField label={t('settings.customSttModel')}>
                <input
                  value={config.stt_custom_model}
                  onChange={(e) => {
                    updateConfig({ stt_custom_model: e.target.value })
                    setSttTestStatus('idle')
                    setSttLatencyMs(null)
                  }}
                  placeholder={t('settings.customSttModelPlaceholder')}
                  className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
                />
                <p className="text-[11px] text-text-tertiary mt-1.5">
                  {t('settings.customSttSetupHint')}
                </p>
              </FormField>
            </>
          )}

          <FormField
            label={
              isCustomWhisper ? t('settings.customSttApiKeyOptional') : t('settings.apiKey')
            }
          >
            <div className="flex gap-2">
              <input
                type="password"
                value={config.stt_api_key}
                onChange={(e) => {
                  updateConfig({ stt_api_key: e.target.value })
                  setSttTestStatus('idle')
                  setSttLatencyMs(null)
                }}
                placeholder={t('settings.enterApiKey')}
                className="flex-1 px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
              />
              <button
                onClick={handleTest}
                disabled={!canTest || sttTestStatus === 'testing'}
                className="px-4 py-2.5 bg-accent text-white rounded-[10px] text-[13px] border-none cursor-pointer hover:bg-accent-hover disabled:opacity-40 disabled:cursor-not-allowed transition-colors flex items-center gap-1.5"
              >
                {sttTestStatus === 'testing' && <Loader2 size={14} className="animate-spin" />}
                {t('settings.test')}
              </button>
            </div>
            {sttTestStatus === 'success' && (
              <p className="flex items-center gap-1 text-[12px] text-success mt-2">
                <CheckCircle2 size={13} />{' '}
                {sttLatencyMs !== null ? `${sttLatencyMs}ms` : t('settings.connectionSuccess')}
              </p>
            )}
            {sttTestStatus === 'error' && (
              <p className="flex items-center gap-1 text-[12px] text-error mt-2">
                <XCircle size={13} /> {t('settings.connectionFailed')}
              </p>
            )}
            <p className="text-[11px] text-text-tertiary mt-1.5">
              {t('settings.storedLocally')}
            </p>
          </FormField>
        </>
      )}

      <FormField label={t('settings.sttLanguage')}>
        <select
          value={config.stt_language}
          onChange={(e) => updateConfig({ stt_language: e.target.value })}
          className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
        >
          {LANGUAGES.map((l) => (
            <option key={l.value} value={l.value}>
              {l.label}
            </option>
          ))}
        </select>
      </FormField>
    </div>
  )
}
