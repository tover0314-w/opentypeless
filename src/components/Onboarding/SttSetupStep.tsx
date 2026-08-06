import { useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppStore, type AppConfig } from '../../stores/appStore'
import {
  CUSTOM_STT_PRESET_AZURE_OPENAI,
  CUSTOM_STT_PRESETS,
  CUSTOM_WHISPER_PROVIDER,
  ONBOARDING_AZURE_STT_VALUE,
  ONBOARDING_STT_OPTIONS,
  ONBOARDING_STT_PROVIDERS,
} from '../../lib/constants'
import { testSttConnection } from '../../lib/tauri'
import { CheckCircle2, XCircle, Loader2 } from 'lucide-react'

const AZURE_PRESET = CUSTOM_STT_PRESETS.find((p) => p.value === CUSTOM_STT_PRESET_AZURE_OPENAI)

export function SttSetupStep() {
  const { t } = useTranslation()
  const config = useAppStore((s) => s.config)
  const updateConfig = useAppStore((s) => s.updateConfig)
  const sttTestStatus = useAppStore((s) => s.sttTestStatus)
  const setSttTestStatus = useAppStore((s) => s.setSttTestStatus)
  const fallbackProvider = ONBOARDING_STT_PROVIDERS[0]?.value ?? 'deepgram'
  const usesCustomWhisper = config.stt_provider === CUSTOM_WHISPER_PROVIDER
  const isAzure = usesCustomWhisper && config.stt_custom_preset === CUSTOM_STT_PRESET_AZURE_OPENAI
  // A non-Azure custom endpoint was set up in Settings; onboarding only summarises it.
  const isCustomWhisper = usesCustomWhisper && !isAzure
  const selectedProvider = isAzure
    ? ONBOARDING_AZURE_STT_VALUE
    : isCustomWhisper
      ? CUSTOM_WHISPER_PROVIDER
      : ONBOARDING_STT_PROVIDERS.some((p) => p.value === config.stt_provider)
        ? config.stt_provider
        : fallbackProvider

  useEffect(() => {
    if (usesCustomWhisper) return
    if (selectedProvider === config.stt_provider) return

    updateConfig({ stt_provider: selectedProvider as AppConfig['stt_provider'] })
    setSttTestStatus('idle')
  }, [config.stt_provider, usesCustomWhisper, selectedProvider, setSttTestStatus, updateConfig])

  const handleProviderChange = (value: string) => {
    if (value === ONBOARDING_AZURE_STT_VALUE) {
      updateConfig({
        stt_provider: CUSTOM_WHISPER_PROVIDER,
        stt_custom_preset: CUSTOM_STT_PRESET_AZURE_OPENAI,
        // Leave the endpoint blank so the placeholder shows the required Azure URL shape
        // instead of a stale local Speaches address.
        stt_custom_base_url: '',
        stt_custom_model: AZURE_PRESET?.model ?? '',
      })
    } else {
      updateConfig({ stt_provider: value as AppConfig['stt_provider'] })
    }
    setSttTestStatus('idle')
  }

  const handleTest = async () => {
    setSttTestStatus('testing')
    try {
      const ok = usesCustomWhisper
        ? await testSttConnection(
            config.stt_custom_api_key,
            CUSTOM_WHISPER_PROVIDER,
            config.stt_custom_base_url,
            config.stt_custom_model,
          )
        : await testSttConnection(config.stt_api_key, selectedProvider)
      setSttTestStatus(ok ? 'success' : 'error')
    } catch {
      setSttTestStatus('error')
    }
  }

  return (
    <div className="space-y-5">
      {isCustomWhisper ? (
        <Field label={t('onboarding.stt.serviceLabel')}>
          <div className="rounded-[10px] border border-border bg-bg-secondary px-3 py-2.5">
            <p className="text-[13px] font-medium text-text-primary">
              {t('onboarding.stt.customWhisperConfigured')}
            </p>
            <p className="mt-1 truncate text-[11px] text-text-tertiary">
              {config.stt_custom_base_url}
            </p>
            <p className="mt-0.5 truncate text-[11px] text-text-tertiary">
              {config.stt_custom_model}
            </p>
          </div>
        </Field>
      ) : (
        <Field label={t('onboarding.stt.serviceLabel')}>
          <select
            value={selectedProvider}
            onChange={(e) => handleProviderChange(e.target.value)}
            className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
          >
            {ONBOARDING_STT_OPTIONS.map((p) => (
              <option key={p.value} value={p.value}>
                {t(p.labelKey)}
              </option>
            ))}
          </select>
        </Field>
      )}

      {isAzure && (
        <>
          <Field label={t('onboarding.stt.endpointLabel')}>
            <input
              value={config.stt_custom_base_url}
              onChange={(e) => {
                updateConfig({ stt_custom_base_url: e.target.value })
                setSttTestStatus('idle')
              }}
              placeholder={AZURE_PRESET?.baseUrl}
              className="w-full min-w-0 px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
            />
          </Field>
          <Field label={t('onboarding.stt.deploymentLabel')}>
            <input
              value={config.stt_custom_model}
              onChange={(e) => {
                updateConfig({ stt_custom_model: e.target.value })
                setSttTestStatus('idle')
              }}
              placeholder={AZURE_PRESET?.model}
              className="w-full min-w-0 px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
            />
          </Field>
        </>
      )}

      <Field
        label={isAzure ? t('onboarding.stt.apiKeyOptionalLabel') : t('onboarding.stt.apiKeyLabel')}
      >
        <div className="flex gap-2">
          <input
            type="password"
            value={usesCustomWhisper ? config.stt_custom_api_key : config.stt_api_key}
            onChange={(e) => {
              updateConfig(
                usesCustomWhisper
                  ? { stt_custom_api_key: e.target.value }
                  : { stt_api_key: e.target.value },
              )
              setSttTestStatus('idle')
            }}
            placeholder={
              isAzure
                ? t('onboarding.stt.apiKeyEntraPlaceholder')
                : t('onboarding.stt.apiKeyPlaceholder')
            }
            className="flex-1 px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
          />
          <button
            onClick={handleTest}
            disabled={
              sttTestStatus === 'testing' ||
              (usesCustomWhisper
                ? !config.stt_custom_base_url.trim() || !config.stt_custom_model.trim()
                : !config.stt_api_key)
            }
            className="px-4 py-2.5 bg-accent text-white rounded-[10px] text-[13px] border-none cursor-pointer hover:bg-accent-hover disabled:opacity-40 disabled:cursor-not-allowed transition-colors flex items-center gap-1.5"
          >
            {sttTestStatus === 'testing' && <Loader2 size={14} className="animate-spin" />}
            {t('onboarding.stt.testButton')}
          </button>
        </div>
        <TestStatusHint status={sttTestStatus} />
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
        <CheckCircle2 size={13} /> {t('onboarding.stt.connectionOk')}
      </p>
    )
  }
  if (status === 'error') {
    return (
      <p className="flex items-center gap-1 text-[12px] text-error mt-2">
        <XCircle size={13} /> {t('onboarding.stt.connectionFail')}
      </p>
    )
  }
  return null
}
