import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { isMacPlatform, useAppStore } from '../../stores/appStore'
import { hasManagedCloudAccess, useAuthStore } from '../../stores/authStore'
import {
  STT_PROVIDERS,
  LANGUAGES,
  APPLE_SPEECH_PROVIDER,
  CUSTOM_WHISPER_PROVIDER,
  CUSTOM_STT_DEFAULTS,
  CUSTOM_STT_PRESETS,
  VOLCENGINE_STT_RESOURCES,
} from '../../lib/constants'
import {
  benchSttConnection,
  getSttRecordingCapability,
  getSttProviderDiagnostics,
  type ResolvedSttRecordingLimit,
  type SttProviderDiagnostics,
} from '../../lib/tauri'
import { SettingsControlGroup, SettingsRow } from './shared/SettingsRow'
import { Select } from '../shared/Select'
import { ProviderLogo } from '../shared/ProviderLogo'
import { useProviderCredential } from '../../hooks/useProviderCredential'
import { credentialErrorDetails } from '../../lib/credentialErrorDetails'
import { providerErrorMessage } from '../../lib/providerErrorMessage'
import { CheckCircle2, XCircle, Loader2, Crown } from 'lucide-react'

const RECORDING_LIMIT_PRESETS = [30, 60, 120, 300, 600, 1800, 3600]
const MIN_CUSTOM_RECORDING_SECONDS = 30

function formatRecordingDuration(
  seconds: number,
  t: (key: string, values?: Record<string, number>) => string,
) {
  if (seconds === 60) return t('recordingLimits.durationMinute', { count: 1 })
  if (seconds > 0 && seconds % 60 === 0) {
    return t('recordingLimits.durationMinutes', { count: seconds / 60 })
  }
  return t('recordingLimits.durationSeconds', { count: seconds })
}

export function SttPane({ grouped = false }: { grouped?: boolean }) {
  const config = useAppStore((s) => s.config)
  const updateConfig = useAppStore((s) => s.updateConfig)
  const sttTestStatus = useAppStore((s) => s.sttTestStatus)
  const setSttTestStatus = useAppStore((s) => s.setSttTestStatus)
  const sttLatencyMs = useAppStore((s) => s.sttLatencyMs)
  const setSttLatencyMs = useAppStore((s) => s.setSttLatencyMs)
  const platformCapabilities = useAppStore((s) => s.platformCapabilities)
  const { user } = useAuthStore()
  const hasCloudAccess = useAuthStore(hasManagedCloudAccess)
  const { t } = useTranslation()
  const [testErrorMessage, setTestErrorMessage] = useState<string | null>(null)
  const testRequestId = useRef(0)
  const testPending = useRef(false)
  const serviceIdentity = JSON.stringify([
    config.stt_provider,
    config.stt_api_key,
    config.stt_custom_api_key,
    config.stt_custom_base_url,
    config.stt_custom_model,
    config.stt_volcengine_resource_id,
    config.stt_aliyun_qwen_region,
  ])
  const previousServiceIdentity = useRef(serviceIdentity)
  useEffect(() => {
    if (previousServiceIdentity.current === serviceIdentity) return
    previousServiceIdentity.current = serviceIdentity
    testRequestId.current += 1
    testPending.current = false
    setSttTestStatus('idle')
    setSttLatencyMs(null)
    setTestErrorMessage(null)
  }, [serviceIdentity, setSttTestStatus, setSttLatencyMs])
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
  const [recordingLimit, setRecordingLimit] = useState<ResolvedSttRecordingLimit | null>(null)
  const [customDurationEntryRequested, setCustomDurationEntryRequested] = useState(false)

  const isCloud = config.stt_provider === 'cloud'
  const isAppleSpeech = config.stt_provider === APPLE_SPEECH_PROVIDER
  const isCustomWhisper = config.stt_provider === CUSTOM_WHISPER_PROVIDER
  const isVolcengineDoubao = config.stt_provider === 'volcengine-doubao'
  const isAliyunQwen3 = config.stt_provider === 'aliyun-qwen3-asr'
  const officialEndpoints: Record<string, string> = {
    deepgram: 'wss://api.deepgram.com/v1/listen',
    assemblyai: 'wss://streaming.assemblyai.com/v3/ws',
    'volcengine-doubao': 'wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async',
    'glm-asr': 'https://open.bigmodel.cn/api/paas/v4/audio/transcriptions',
    'openai-whisper': 'https://api.openai.com/v1/audio/transcriptions',
    'groq-whisper': 'https://api.groq.com/openai/v1/audio/transcriptions',
    siliconflow: 'https://api.siliconflow.cn/v1/audio/transcriptions',
    'aliyun-qwen3-asr':
      config.stt_aliyun_qwen_region === 'international'
        ? 'wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime'
        : 'wss://dashscope.aliyuncs.com/api-ws/v1/realtime',
  }
  const credentialProvider = isCustomWhisper ? CUSTOM_WHISPER_PROVIDER : config.stt_provider
  const legacyApiKey = isCustomWhisper ? config.stt_custom_api_key : config.stt_api_key
  const volcengineResourceId =
    config.stt_volcengine_resource_id || VOLCENGINE_STT_RESOURCES[0].value
  const {
    value: apiKeyDraft,
    error: credentialErrorMessage,
    edit: editCredential,
    flush: flushCredential,
  } = useProviderCredential('stt', credentialProvider, legacyApiKey, !isCloud && !isAppleSpeech)
  const [sttDiagnostics, setSttDiagnostics] = useState<SttProviderDiagnostics | null>(null)
  const supportsAppleSpeech = platformCapabilities
    ? platformCapabilities.os === 'macos'
    : isMacPlatform()
  const visibleSttProviders = STT_PROVIDERS.filter(
    (provider) => provider.value !== APPLE_SPEECH_PROVIDER || supportsAppleSpeech,
  )
  const appleSpeechReady = sttDiagnostics?.ready === true
  const appleSpeechUnavailable = sttDiagnostics?.ready === false
  const canTest = isAppleSpeech
    ? appleSpeechReady
    : isCustomWhisper
      ? Boolean(config.stt_custom_base_url.trim() && config.stt_custom_model.trim())
      : Boolean(apiKeyDraft.trim())
  const goUpgrade = () => {
    window.location.hash = '#/upgrade'
  }

  useEffect(() => {
    setSttDiagnostics(null)
    if (!isCustomWhisper && !isAppleSpeech) {
      return
    }

    let cancelled = false
    getSttProviderDiagnostics(
      isAppleSpeech ? '' : apiKeyDraft,
      config.stt_provider,
      isCustomWhisper ? config.stt_custom_base_url : undefined,
      isCustomWhisper ? config.stt_custom_model : undefined,
    )
      .then((diagnostics) => {
        if (!cancelled) setSttDiagnostics(diagnostics)
      })
      .catch((error) => {
        console.error('[stt] failed to read provider diagnostics', error)
        if (!cancelled) setSttDiagnostics(null)
      })

    return () => {
      cancelled = true
    }
  }, [
    apiKeyDraft,
    config.stt_custom_base_url,
    config.stt_custom_model,
    config.stt_provider,
    isAppleSpeech,
    isCustomWhisper,
  ])

  useEffect(() => {
    let cancelled = false
    setRecordingLimit(null)
    getSttRecordingCapability(
      config.stt_provider,
      config.recording_limit_mode,
      config.custom_recording_limit_seconds,
    )
      .then((resolved) => {
        if (!cancelled) setRecordingLimit(resolved)
      })
      .catch((error) => {
        console.error('[stt] failed to resolve recording limit', error)
        if (!cancelled) setRecordingLimit(null)
      })

    return () => {
      cancelled = true
    }
  }, [
    config.custom_recording_limit_seconds,
    config.max_recording_seconds,
    config.recording_limit_mode,
    config.stt_provider,
  ])

  useEffect(() => {
    setCustomDurationEntryRequested(false)
  }, [config.stt_provider])

  useEffect(() => {
    if (config.recording_limit_mode === 'auto') {
      setCustomDurationEntryRequested(false)
    }
  }, [config.recording_limit_mode])

  const handleTest = async () => {
    const isCurrentConfig = () => {
      const currentConfig = useAppStore.getState().config
      return (
        JSON.stringify([
          currentConfig.stt_provider,
          currentConfig.stt_api_key,
          currentConfig.stt_custom_api_key,
          currentConfig.stt_custom_base_url,
          currentConfig.stt_custom_model,
          currentConfig.stt_volcengine_resource_id,
          currentConfig.stt_aliyun_qwen_region,
        ]) === serviceIdentity
      )
    }
    if (testPending.current || !canTest || !isCurrentConfig()) return
    const requestId = ++testRequestId.current
    testPending.current = true
    setSttTestStatus('testing')
    setSttLatencyMs(null)
    setTestErrorMessage(null)
    try {
      let ms: number
      if (isCustomWhisper) {
        ms = await benchSttConnection(
          apiKeyDraft,
          config.stt_provider,
          config.stt_custom_base_url,
          config.stt_custom_model,
        )
      } else if (isVolcengineDoubao) {
        ms = await benchSttConnection(
          apiKeyDraft,
          config.stt_provider,
          undefined,
          undefined,
          volcengineResourceId,
        )
      } else if (isAliyunQwen3) {
        ms = await benchSttConnection(
          apiKeyDraft,
          config.stt_provider,
          undefined,
          undefined,
          undefined,
          config.stt_aliyun_qwen_region,
        )
      } else {
        ms = await benchSttConnection(apiKeyDraft, config.stt_provider)
      }
      if (requestId !== testRequestId.current || !isCurrentConfig()) return
      testPending.current = false
      setSttLatencyMs(ms)
      setSttTestStatus('success')
    } catch (err) {
      if (requestId !== testRequestId.current || !isCurrentConfig()) return
      testPending.current = false
      console.error('[STT Test] Connection failed')
      setTestErrorMessage(providerErrorMessage(err, apiKeyDraft, t('settings.connectionFailed')))
      setSttTestStatus('error')
    }
  }

  const availableRecordingPresets = recordingLimit
    ? RECORDING_LIMIT_PRESETS.filter(
        (seconds) => seconds <= recordingLimit.capability.hardMaxSeconds,
      )
    : []
  const savedDurationIsPreset =
    recordingLimit !== null &&
    availableRecordingPresets.includes(config.custom_recording_limit_seconds)
  const canEnterCustomDuration =
    recordingLimit !== null &&
    recordingLimit.capability.hardMaxSeconds > MIN_CUSTOM_RECORDING_SECONDS
  const recordingDurationValue =
    config.recording_limit_mode === 'auto'
      ? 'auto'
      : customDurationEntryRequested && canEnterCustomDuration
        ? 'custom'
        : savedDurationIsPreset
          ? String(config.custom_recording_limit_seconds)
          : canEnterCustomDuration
            ? 'custom'
            : String(recordingLimit?.effectiveMaxSeconds ?? MIN_CUSTOM_RECORDING_SECONDS)
  const showCustomDurationEntry =
    canEnterCustomDuration &&
    (customDurationEntryRequested ||
      (config.recording_limit_mode === 'custom' && !savedDurationIsPreset))
  const isManagedCapability =
    recordingLimit?.capability.explanationKey === 'recordingLimits.reasons.managedCapability'
  const isManagedFallback =
    recordingLimit?.capability.explanationKey === 'recordingLimits.reasons.managedFallback'
  const isProviderFixedLimit =
    !canEnterCustomDuration &&
    recordingLimit?.capability.explanationKey === 'recordingLimits.reasons.providerDuration'
  const recordingLimitHelper = recordingLimit
    ? showCustomDurationEntry
      ? t(
          isManagedCapability
            ? 'recordingLimits.allowedCloudRange'
            : 'recordingLimits.allowedRangeWithReason',
          {
            min: formatRecordingDuration(MIN_CUSTOM_RECORDING_SECONDS, t),
            max: formatRecordingDuration(recordingLimit.capability.hardMaxSeconds, t),
            reason: t(recordingLimit.capability.explanationKey),
          },
        )
      : config.recording_limit_mode === 'custom'
        ? isProviderFixedLimit
          ? t('recordingLimits.providerFixedLimit', {
              max: formatRecordingDuration(recordingLimit.capability.hardMaxSeconds, t),
            })
          : isManagedCapability
            ? t('recordingLimits.currentSelectionWithCloudMax', {
                current: formatRecordingDuration(recordingLimit.effectiveMaxSeconds, t),
                max: formatRecordingDuration(recordingLimit.capability.hardMaxSeconds, t),
              })
            : isManagedFallback
              ? t(recordingLimit.capability.explanationKey)
              : t('recordingLimits.currentSelectionWithLimit', {
                  current: formatRecordingDuration(recordingLimit.effectiveMaxSeconds, t),
                  max: formatRecordingDuration(recordingLimit.capability.hardMaxSeconds, t),
                  reason: t(recordingLimit.capability.explanationKey),
                })
        : t(recordingLimit.capability.explanationKey)
    : null

  const recordingDurationOptions = recordingLimit
    ? [
        {
          value: 'auto',
          label: t('recordingLimits.auto', {
            duration: formatRecordingDuration(recordingLimit.capability.recommendedMaxSeconds, t),
          }),
        },
        ...availableRecordingPresets.map((seconds) => ({
          value: String(seconds),
          label: formatRecordingDuration(seconds, t),
        })),
        ...(canEnterCustomDuration
          ? [{ value: 'custom', label: t('recordingLimits.numericEntry') }]
          : []),
      ]
    : []

  const handleRecordingDurationChange = (value: string) => {
    if (value === 'auto') {
      setCustomDurationEntryRequested(false)
      updateConfig({ recording_limit_mode: 'auto' })
      return
    }
    if (value === 'custom') {
      setCustomDurationEntryRequested(true)
      updateConfig({ recording_limit_mode: 'custom' })
      return
    }

    const seconds = Number(value)
    if (!Number.isFinite(seconds)) return
    setCustomDurationEntryRequested(false)
    updateConfig({
      recording_limit_mode: 'custom',
      custom_recording_limit_seconds: seconds,
    })
  }

  const resetTest = () => {
    testRequestId.current += 1
    testPending.current = false
    setSttTestStatus('idle')
    setSttLatencyMs(null)
    setTestErrorMessage(null)
  }

  const testButton = (
    <button
      type="button"
      onClick={handleTest}
      disabled={!canTest || sttTestStatus === 'testing'}
      className="btn-outline flex h-10 shrink-0 cursor-pointer items-center gap-1.5 px-4 text-[13px] disabled:cursor-not-allowed disabled:opacity-40"
    >
      {sttTestStatus === 'testing' && <Loader2 size={14} className="animate-spin" />}
      {t('settings.test')}
    </button>
  )

  const testFeedback = (
    <>
      {sttTestStatus === 'success' && (
        <p className="m-0 flex items-center gap-1 text-[12px] text-success">
          <CheckCircle2 size={13} />{' '}
          {sttLatencyMs !== null ? `${sttLatencyMs}ms` : t('settings.connectionSuccess')}
        </p>
      )}
      {(sttTestStatus === 'error' || testErrorMessage) && (
        <div className="flex items-start gap-1 text-[12px] text-error">
          <XCircle size={13} className="mt-[1px] flex-shrink-0" />
          <span>{testErrorMessage || t('settings.connectionFailed')}</span>
        </div>
      )}
    </>
  )

  return (
    <div>
      <SettingsRow title={t('settings.provider')} description={t('modelsPage.providerDesc')}>
        <Select
          aria-label={t('settings.provider')}
          value={config.stt_provider}
          options={[...visibleSttProviders]
            .sort((left, right) => Number(right.value === 'cloud') - Number(left.value === 'cloud'))
            .map((provider) => ({
              value: provider.value,
              label:
                provider.value === 'cloud'
                  ? `${t(provider.labelKey)} · ${t('modelsPage.recommended')}`
                  : t(provider.labelKey),
              leading: <ProviderLogo provider={provider.value} />,
            }))}
          onChange={(next) => {
            const provider = next as typeof config.stt_provider
            updateConfig({
              stt_provider: provider,
              ...(provider === CUSTOM_WHISPER_PROVIDER
                ? {
                    stt_custom_preset: config.stt_custom_preset || CUSTOM_STT_DEFAULTS.preset,
                    stt_custom_base_url: config.stt_custom_base_url || CUSTOM_STT_DEFAULTS.baseUrl,
                    stt_custom_model: config.stt_custom_model || CUSTOM_STT_DEFAULTS.model,
                  }
                : provider === 'volcengine-doubao' && !config.stt_volcengine_resource_id
                  ? {
                      stt_volcengine_resource_id: VOLCENGINE_STT_RESOURCES[0].value,
                    }
                  : {}),
            })
            resetTest()
          }}
        />
        {isCloud && (
          <div className="flex flex-col items-start gap-1.5 text-[12px] leading-[18px]">
            <span className="flex items-center gap-1.5 font-medium text-text-primary">
              <Crown size={13} className="text-accent" />
              {t('settings.cloudSttPro')}
            </span>
            {!user ? (
              <span className="text-text-tertiary">{t('settings.sttSignInHint')}</span>
            ) : !hasCloudAccess ? (
              <>
                <span className="text-text-tertiary">{t('settings.sttUpgradeHint')}</span>
                <button
                  type="button"
                  onClick={goUpgrade}
                  className="btn-accent cursor-pointer px-3 py-1 text-[12px] font-medium"
                >
                  {t('nav.upgrade')}
                </button>
              </>
            ) : (
              <span className="text-success">{t('settings.sttProActive')}</span>
            )}
          </div>
        )}
        {isAppleSpeech && (
          <p
            className={`m-0 flex items-center gap-1.5 text-[12px] ${
              appleSpeechReady
                ? 'text-success'
                : appleSpeechUnavailable
                  ? 'text-text-tertiary'
                  : 'text-text-secondary'
            }`}
          >
            {appleSpeechReady ? (
              <CheckCircle2 size={13} className="flex-shrink-0" />
            ) : appleSpeechUnavailable ? (
              <XCircle size={13} className="flex-shrink-0" />
            ) : (
              <Loader2 size={13} className="flex-shrink-0 animate-spin" />
            )}
            <span>
              {appleSpeechReady
                ? t('settings.appleSpeechReady')
                : appleSpeechUnavailable
                  ? t('settings.appleSpeechUnavailable')
                  : t('settings.healthChecking')}
            </span>
          </p>
        )}
      </SettingsRow>

      {isAppleSpeech && (
        <SettingsRow
          title={t('modelsPage.connection')}
          description={t('onboarding.permissions.appleSpeechDesc')}
        >
          <div className="flex justify-end">{testButton}</div>
          {testFeedback}
        </SettingsRow>
      )}

      {isCustomWhisper && (
        <>
          <SettingsRow
            title={t('settings.customSttPreset')}
            description={t('modelsPage.sttPresetDesc')}
          >
            <Select
              aria-label={t('settings.customSttPreset')}
              value={config.stt_custom_preset}
              options={CUSTOM_STT_PRESETS.map((preset) => ({
                value: preset.value,
                label: t(preset.labelKey),
              }))}
              onChange={(next) => {
                const preset = next as typeof config.stt_custom_preset
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
                resetTest()
              }}
            />
          </SettingsRow>
          <SettingsRow
            title={t('settings.customSttBaseUrl')}
            description={t('settings.customSttSetupHint')}
          >
            <input
              aria-label={t('settings.customSttBaseUrl')}
              value={config.stt_custom_base_url}
              onChange={(e) => {
                updateConfig({ stt_custom_base_url: e.target.value })
                resetTest()
              }}
              placeholder={t('settings.customSttBaseUrlPlaceholder')}
              className="field-control"
            />
          </SettingsRow>
          <SettingsRow
            title={t('settings.customSttModel')}
            description={t('modelsPage.sttModelDesc')}
          >
            <input
              aria-label={t('settings.customSttModel')}
              value={config.stt_custom_model}
              onChange={(e) => {
                updateConfig({ stt_custom_model: e.target.value })
                resetTest()
              }}
              placeholder={t('settings.customSttModelPlaceholder')}
              className="field-control"
            />
            {sttDiagnostics && (
              <p className="m-0 flex min-w-0 items-center gap-1.5 text-[11px] text-text-tertiary">
                {!sttDiagnostics.ready && <XCircle size={12} className="flex-shrink-0" />}
                <span className="flex-shrink-0">
                  {sttDiagnostics.ready
                    ? t('settings.localSttConfigured')
                    : t('settings.localSttNeedsSetup')}
                </span>
                {sttDiagnostics.endpoint && (
                  <>
                    <span className="text-text-tertiary">·</span>
                    <span className="truncate text-text-tertiary">{sttDiagnostics.endpoint}</span>
                  </>
                )}
              </p>
            )}
          </SettingsRow>
        </>
      )}

      {isVolcengineDoubao && (
        <SettingsRow
          title={t('settings.volcengineResourceId')}
          description={t('settings.volcengineResourceDesc')}
        >
          <Select
            aria-label={t('settings.volcengineResourceId')}
            value={volcengineResourceId}
            options={VOLCENGINE_STT_RESOURCES.map((resource) => ({
              value: resource.value,
              label: t(resource.labelKey),
            }))}
            onChange={(next) => {
              updateConfig({ stt_volcengine_resource_id: next })
              resetTest()
            }}
          />
        </SettingsRow>
      )}

      {isAliyunQwen3 && (
        <SettingsRow
          title={t('settings.aliyunQwenRegion')}
          description={t('settings.aliyunQwenRegionHint')}
        >
          <Select
            aria-label={t('settings.aliyunQwenRegion')}
            value={config.stt_aliyun_qwen_region}
            options={[
              { value: 'china-mainland', label: t('settings.aliyunQwenRegionChina') },
              { value: 'international', label: t('settings.aliyunQwenRegionInternational') },
            ]}
            onChange={(next) => {
              updateConfig({
                stt_aliyun_qwen_region: next as typeof config.stt_aliyun_qwen_region,
              })
              resetTest()
            }}
          />
        </SettingsRow>
      )}

      {!isCloud && !isAppleSpeech && (
        <SettingsRow
          title={isCustomWhisper ? t('settings.customSttApiKeyOptional') : t('settings.apiKey')}
          description={
            isVolcengineDoubao ? t('settings.volcengineSttKeyHint') : t('modelsPage.apiKeyDesc')
          }
        >
          <SettingsControlGroup action={testButton}>
            <input
              type="password"
              aria-label={
                isCustomWhisper ? t('settings.customSttApiKeyOptional') : t('settings.apiKey')
              }
              value={apiKeyDraft}
              onChange={(e) => {
                editCredential(e.target.value)
                resetTest()
              }}
              onBlur={() => void flushCredential()}
              placeholder={t('settings.enterApiKey')}
              className="field-control"
            />
          </SettingsControlGroup>
          {testFeedback}
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

      <details open={!grouped} className={grouped ? 'mt-3 border-t border-border-thin pt-4' : ''}>
        {grouped && (
          <summary className="w-fit cursor-pointer text-[13px] text-text-secondary">
            {t('modelsPage.advanced')}
          </summary>
        )}
        {grouped && officialEndpoints[config.stt_provider] && (
          <SettingsRow
            title={t('settings.baseUrl')}
            description={t('modelsPage.speechEndpointDesc')}
          >
            <input
              aria-label={t('settings.baseUrl')}
              value={officialEndpoints[config.stt_provider]}
              readOnly
              className="field-control text-text-secondary"
            />
          </SettingsRow>
        )}
        <SettingsRow
          title={t('settings.sttLanguage')}
          description={t('modelsPage.sttLanguageDesc')}
        >
          <Select
            aria-label={t('settings.sttLanguage')}
            value={config.stt_language}
            options={LANGUAGES.map((l) => ({
              value: l.value,
              label: l.labelKey ? t(l.labelKey) : (l.label ?? l.value),
            }))}
            onChange={(next) => updateConfig({ stt_language: next })}
          />
        </SettingsRow>

        <SettingsRow
          title={t('settings.maxRecordingDuration')}
          description={recordingLimitHelper ?? t('modelsPage.durationDesc')}
        >
          {recordingLimit ? (
            <>
              <Select
                aria-label={t('settings.maxRecordingDuration')}
                value={recordingDurationValue}
                options={recordingDurationOptions}
                onChange={(next) => handleRecordingDurationChange(next)}
              />

              {showCustomDurationEntry && (
                <div className="field-control flex items-center gap-2 px-0 pr-3">
                  <input
                    id="custom-recording-duration"
                    aria-label={t('recordingLimits.customDuration')}
                    type="number"
                    min={MIN_CUSTOM_RECORDING_SECONDS}
                    max={recordingLimit.capability.hardMaxSeconds}
                    step={1}
                    value={config.custom_recording_limit_seconds}
                    onChange={(event) => {
                      const seconds = Number(event.target.value)
                      if (Number.isFinite(seconds) && seconds >= 0) {
                        updateConfig({ custom_recording_limit_seconds: Math.floor(seconds) })
                      }
                    }}
                    className="h-full min-w-0 flex-1 border-none bg-transparent px-3 text-[14px] text-text-primary outline-none"
                  />
                  <span className="text-[12px] text-text-secondary" aria-hidden="true">
                    {t('recordingLimits.secondsUnit')}
                  </span>
                </div>
              )}

              {config.recording_limit_mode === 'custom' &&
                recordingLimit.requestedSeconds !== recordingLimit.effectiveMaxSeconds && (
                  <p className="m-0 text-[12px] text-warning">
                    {t('recordingLimits.corrected', {
                      duration: formatRecordingDuration(recordingLimit.effectiveMaxSeconds, t),
                    })}
                  </p>
                )}
            </>
          ) : (
            <p className="m-0 text-[12px] text-text-tertiary">{t('recordingLimits.loading')}</p>
          )}
        </SettingsRow>
      </details>
    </div>
  )
}
