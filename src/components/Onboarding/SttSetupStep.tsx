import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useAppStore, type AppConfig } from '../../stores/appStore'
import {
  APPLE_SPEECH_PROVIDER,
  CUSTOM_WHISPER_PROVIDER,
  ONBOARDING_STT_PROVIDERS,
  STT_PROVIDERS,
} from '../../lib/constants'
import { testSttConnection } from '../../lib/tauri'
import { Select } from '../shared/Select'
import { CheckCircle2, XCircle, Loader2 } from 'lucide-react'

export function SttSetupStep() {
  const { t } = useTranslation()
  const config = useAppStore((s) => s.config)
  const updateConfig = useAppStore((s) => s.updateConfig)
  const sttTestStatus = useAppStore((s) => s.sttTestStatus)
  const setSttTestStatus = useAppStore((s) => s.setSttTestStatus)
  const testRequestId = useRef(0)
  const testPending = useRef(false)
  const fallbackProvider = ONBOARDING_STT_PROVIDERS[0]?.value ?? 'deepgram'
  const isCustomWhisper = config.stt_provider === CUSTOM_WHISPER_PROVIDER
  const isAppleSpeech = config.stt_provider === APPLE_SPEECH_PROVIDER
  const selectedProvider =
    isCustomWhisper || isAppleSpeech
      ? config.stt_provider
      : ONBOARDING_STT_PROVIDERS.some((p) => p.value === config.stt_provider)
        ? config.stt_provider
        : fallbackProvider
  const testIdentity = JSON.stringify([
    selectedProvider,
    config.stt_api_key,
    config.stt_custom_api_key,
    config.stt_custom_base_url,
    config.stt_custom_model,
    config.stt_aliyun_qwen_region,
    config.stt_volcengine_resource_id,
  ])
  const previousTestIdentity = useRef(testIdentity)

  useEffect(() => {
    if (previousTestIdentity.current === testIdentity) return
    previousTestIdentity.current = testIdentity
    testRequestId.current += 1
    testPending.current = false
    setSttTestStatus('idle')
  }, [testIdentity, setSttTestStatus])

  useEffect(
    () => () => {
      testRequestId.current += 1
      if (testPending.current) {
        testPending.current = false
        setSttTestStatus('idle')
      }
    },
    [setSttTestStatus],
  )

  useEffect(() => {
    if (isCustomWhisper) return
    if (selectedProvider === config.stt_provider) return

    updateConfig({ stt_provider: selectedProvider as AppConfig['stt_provider'] })
    setSttTestStatus('idle')
  }, [config.stt_provider, isCustomWhisper, selectedProvider, setSttTestStatus, updateConfig])

  const handleTest = async () => {
    if (testPending.current) return
    const requestId = ++testRequestId.current
    testPending.current = true
    setSttTestStatus('testing')
    try {
      let ok: boolean
      if (isAppleSpeech) {
        ok = await testSttConnection('', APPLE_SPEECH_PROVIDER)
      } else if (isCustomWhisper) {
        ok = await testSttConnection(
          config.stt_custom_api_key,
          CUSTOM_WHISPER_PROVIDER,
          config.stt_custom_base_url,
          config.stt_custom_model,
        )
      } else if (
        selectedProvider === 'aliyun-qwen3-asr' ||
        selectedProvider === 'volcengine-doubao'
      ) {
        ok = await testSttConnection(
          config.stt_api_key,
          selectedProvider,
          undefined,
          undefined,
          selectedProvider === 'volcengine-doubao' ? config.stt_volcengine_resource_id : undefined,
          selectedProvider === 'aliyun-qwen3-asr' ? config.stt_aliyun_qwen_region : undefined,
        )
      } else {
        ok = await testSttConnection(config.stt_api_key, selectedProvider)
      }
      if (requestId === testRequestId.current) setSttTestStatus(ok ? 'success' : 'error')
    } catch {
      if (requestId === testRequestId.current) setSttTestStatus('error')
    } finally {
      if (requestId === testRequestId.current) testPending.current = false
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
          <Select
            aria-label={t('onboarding.stt.serviceLabel')}
            value={selectedProvider}
            onChange={(value) => {
              updateConfig({ stt_provider: value as AppConfig['stt_provider'] })
              setSttTestStatus('idle')
            }}
            options={(isAppleSpeech
              ? STT_PROVIDERS.filter(
                  (provider) =>
                    provider.value === APPLE_SPEECH_PROVIDER ||
                    ONBOARDING_STT_PROVIDERS.some((option) => option.value === provider.value),
                )
              : ONBOARDING_STT_PROVIDERS
            ).map((p) => ({
              value: p.value,
              label: t(p.labelKey),
            }))}
          />
        </Field>
      )}

      <Field label={t(isAppleSpeech ? 'modelsPage.connection' : 'onboarding.stt.apiKeyLabel')}>
        <div className="flex gap-2">
          {!isAppleSpeech && (
            <input
              type="password"
              aria-label={t('onboarding.stt.apiKeyLabel')}
              value={isCustomWhisper ? config.stt_custom_api_key : config.stt_api_key}
              onChange={(e) => {
                updateConfig(
                  isCustomWhisper
                    ? { stt_custom_api_key: e.target.value }
                    : { stt_api_key: e.target.value },
                )
                setSttTestStatus('idle')
              }}
              placeholder={t('onboarding.stt.apiKeyPlaceholder')}
              className="field-control min-w-0 flex-1"
            />
          )}
          <button
            type="button"
            onClick={handleTest}
            disabled={
              sttTestStatus === 'testing' ||
              (!isAppleSpeech &&
                (isCustomWhisper
                  ? !config.stt_custom_base_url.trim() || !config.stt_custom_model.trim()
                  : !config.stt_api_key))
            }
            className="btn-outline flex h-10 shrink-0 cursor-pointer items-center gap-1.5 px-4 text-[13px] disabled:cursor-not-allowed disabled:opacity-40"
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
