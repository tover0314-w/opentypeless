import { useCallback, useEffect, useRef, useState } from 'react'
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
  readCredential,
  setCredential,
  type ResolvedSttRecordingLimit,
  type SttProviderDiagnostics,
} from '../../lib/tauri'
import { FormField } from './shared/FormField'
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

export function SttPane() {
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
  const [credentialErrorMessage, setCredentialErrorMessage] = useState<string | null>(null)
  const [recordingLimit, setRecordingLimit] = useState<ResolvedSttRecordingLimit | null>(null)
  const [customDurationEntryRequested, setCustomDurationEntryRequested] = useState(false)

  const isCloud = config.stt_provider === 'cloud'
  const isAppleSpeech = config.stt_provider === APPLE_SPEECH_PROVIDER
  const isCustomWhisper = config.stt_provider === CUSTOM_WHISPER_PROVIDER
  const isVolcengineDoubao = config.stt_provider === 'volcengine-doubao'
  const isAliyunQwen3 = config.stt_provider === 'aliyun-qwen3-asr'
  const credentialProvider = isCustomWhisper ? CUSTOM_WHISPER_PROVIDER : config.stt_provider
  const legacyApiKey = isCustomWhisper ? config.stt_custom_api_key : config.stt_api_key
  const volcengineResourceId =
    config.stt_volcengine_resource_id || VOLCENGINE_STT_RESOURCES[0].value
  const credentialSaveRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [apiKeyDraft, setApiKeyDraft] = useState(legacyApiKey)
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
      : Boolean(apiKeyDraft)
  const goUpgrade = () => {
    window.location.hash = '#/upgrade'
  }

  useEffect(() => {
    if (isCloud || isAppleSpeech) {
      setApiKeyDraft('')
      setCredentialErrorMessage(null)
      return
    }

    let cancelled = false
    setApiKeyDraft(legacyApiKey)
    setCredentialErrorMessage(null)
    readCredential('stt', credentialProvider)
      .then((secret) => {
        if (!cancelled) setApiKeyDraft(legacyApiKey || secret || '')
      })
      .catch((error) => console.error('[credentials] failed to read STT credential', error))

    return () => {
      cancelled = true
    }
  }, [credentialProvider, isAppleSpeech, isCloud, legacyApiKey])

  useEffect(() => {
    if (!isCustomWhisper && !isAppleSpeech) {
      setSttDiagnostics(null)
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

  const persistSttCredential = useCallback(
    (value: string, delayMs = 350) => {
      if (isCloud || isAppleSpeech) return
      if (credentialSaveRef.current) clearTimeout(credentialSaveRef.current)
      credentialSaveRef.current = setTimeout(() => {
        credentialSaveRef.current = null
        setCredential('stt', credentialProvider, value)
          .then(() => setCredentialErrorMessage(null))
          .catch((error) => {
            const message = error instanceof Error ? error.message : String(error)
            setCredentialErrorMessage(message)
            console.error('[credentials] failed to save STT credential', error)
          })
      }, delayMs)
    },
    [credentialProvider, isAppleSpeech, isCloud],
  )

  const handleTest = async () => {
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
      console.log('[STT Test] Received latency:', ms, 'type:', typeof ms)
      setSttLatencyMs(ms)
      setSttTestStatus('success')
    } catch (err) {
      console.error('[STT Test] Error:', err)
      setTestErrorMessage(err instanceof Error ? err.message : typeof err === 'string' ? err : null)
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
            setSttTestStatus('idle')
            setSttLatencyMs(null)
            setTestErrorMessage(null)
          }}
          className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
        >
          {visibleSttProviders.map((p) => (
            <option key={p.value} value={p.value}>
              {t(p.labelKey)}
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
          ) : !hasCloudAccess ? (
            <div className="space-y-2">
              <p className="text-[12px] text-text-secondary">{t('settings.sttUpgradeHint')}</p>
              <button
                type="button"
                onClick={goUpgrade}
                className="rounded-[8px] border border-accent bg-accent px-3 py-1.5 text-[12px] font-medium text-white hover:bg-accent-hover"
              >
                {t('nav.upgrade')}
              </button>
            </div>
          ) : (
            <p className="text-[12px] text-green-500">{t('settings.sttProActive')}</p>
          )}
        </div>
      ) : isAppleSpeech ? (
        <FormField label={t('providers.stt.appleSpeech')}>
          <div className="flex gap-2">
            <div className="flex-1 px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary">
              <p
                className={`flex items-center gap-1.5 ${
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
            </div>
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
          {(sttTestStatus === 'error' || testErrorMessage) && (
            <div className="flex items-start gap-1 text-[12px] text-error mt-2">
              <XCircle size={13} className="mt-[1px] flex-shrink-0" />
              <span>{testErrorMessage || t('settings.connectionFailed')}</span>
            </div>
          )}
        </FormField>
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
                    setTestErrorMessage(null)
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
                    setTestErrorMessage(null)
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
                    setTestErrorMessage(null)
                  }}
                  placeholder={t('settings.customSttModelPlaceholder')}
                  className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
                />
                <p className="text-[11px] text-text-tertiary mt-1.5">
                  {t('settings.customSttSetupHint')}
                </p>
                {sttDiagnostics && (
                  <p
                    className={`flex items-center gap-1.5 text-[11px] mt-1.5 min-w-0 ${
                      sttDiagnostics.ready ? 'text-success' : 'text-text-tertiary'
                    }`}
                  >
                    {sttDiagnostics.ready ? (
                      <CheckCircle2 size={12} className="flex-shrink-0" />
                    ) : (
                      <XCircle size={12} className="flex-shrink-0" />
                    )}
                    <span className="flex-shrink-0">
                      {sttDiagnostics.ready
                        ? t('settings.localSttReady')
                        : t('settings.localSttNeedsSetup')}
                    </span>
                    {sttDiagnostics.endpoint && (
                      <>
                        <span className="text-text-tertiary">·</span>
                        <span className="truncate text-text-tertiary">
                          {sttDiagnostics.endpoint}
                        </span>
                      </>
                    )}
                  </p>
                )}
              </FormField>
            </>
          )}

          {isVolcengineDoubao && (
            <FormField label={t('settings.volcengineResourceId')}>
              <select
                aria-label={t('settings.volcengineResourceId')}
                value={volcengineResourceId}
                onChange={(e) => {
                  updateConfig({ stt_volcengine_resource_id: e.target.value })
                  setSttTestStatus('idle')
                  setSttLatencyMs(null)
                  setTestErrorMessage(null)
                  setCredentialErrorMessage(null)
                }}
                className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
              >
                {VOLCENGINE_STT_RESOURCES.map((resource) => (
                  <option key={resource.value} value={resource.value}>
                    {t(resource.labelKey)}
                  </option>
                ))}
              </select>
            </FormField>
          )}

          {isAliyunQwen3 && (
            <FormField label={t('settings.aliyunQwenRegion')}>
              <select
                aria-label={t('settings.aliyunQwenRegion')}
                value={config.stt_aliyun_qwen_region}
                onChange={(e) => {
                  updateConfig({
                    stt_aliyun_qwen_region: e.target.value as typeof config.stt_aliyun_qwen_region,
                  })
                  setSttTestStatus('idle')
                  setSttLatencyMs(null)
                  setTestErrorMessage(null)
                  setCredentialErrorMessage(null)
                }}
                className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
              >
                <option value="china-mainland">{t('settings.aliyunQwenRegionChina')}</option>
                <option value="international">{t('settings.aliyunQwenRegionInternational')}</option>
              </select>
              <p className="text-[11px] text-text-tertiary mt-1.5">
                {t('settings.aliyunQwenRegionHint')}
              </p>
            </FormField>
          )}

          <FormField
            label={isCustomWhisper ? t('settings.customSttApiKeyOptional') : t('settings.apiKey')}
          >
            <div className="flex gap-2">
              <input
                type="password"
                value={apiKeyDraft}
                onChange={(e) => {
                  setApiKeyDraft(e.target.value)
                  persistSttCredential(e.target.value)
                  setSttTestStatus('idle')
                  setSttLatencyMs(null)
                  setTestErrorMessage(null)
                }}
                onBlur={() => persistSttCredential(apiKeyDraft, 0)}
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
            {(sttTestStatus === 'error' || testErrorMessage) && (
              <div className="flex items-start gap-1 text-[12px] text-error mt-2">
                <XCircle size={13} className="mt-[1px] flex-shrink-0" />
                <span>{testErrorMessage || t('settings.connectionFailed')}</span>
              </div>
            )}
            {credentialErrorMessage ? (
              <p className="text-[11px] text-error mt-1.5">
                {t('settings.credentialSaveFailed', { details: credentialErrorMessage })}
              </p>
            ) : (
              <p className="text-[11px] text-text-tertiary mt-1.5">{t('settings.storedLocally')}</p>
            )}
            {isVolcengineDoubao && (
              <p className="text-[11px] text-text-tertiary mt-1.5">
                {t('settings.volcengineSttKeyHint')}
              </p>
            )}
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
              {l.labelKey ? t(l.labelKey) : l.label}
            </option>
          ))}
        </select>
      </FormField>

      <FormField label={t('settings.maxRecordingDuration')}>
        {recordingLimit ? (
          <div className="space-y-2">
            <select
              aria-label={t('settings.maxRecordingDuration')}
              value={recordingDurationValue}
              onChange={(event) => handleRecordingDurationChange(event.target.value)}
              className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
            >
              <option value="auto">
                {t('recordingLimits.auto', {
                  duration: formatRecordingDuration(
                    recordingLimit.capability.recommendedMaxSeconds,
                    t,
                  ),
                })}
              </option>
              {availableRecordingPresets.map((seconds) => (
                <option key={seconds} value={seconds}>
                  {formatRecordingDuration(seconds, t)}
                </option>
              ))}
              {canEnterCustomDuration && (
                <option value="custom">{t('recordingLimits.numericEntry')}</option>
              )}
            </select>

            {showCustomDurationEntry && (
              <div className="space-y-1.5">
                <label
                  htmlFor="custom-recording-duration"
                  className="block text-[12px] font-medium text-text-secondary"
                >
                  {t('recordingLimits.customDuration')}
                </label>
                <div className="flex items-center rounded-[10px] border border-border bg-bg-secondary transition-colors focus-within:border-border-focus">
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
                    className="min-w-0 flex-1 bg-transparent px-3 py-2.5 text-[13px] text-text-primary outline-none"
                  />
                  <span className="pr-3 text-[12px] text-text-secondary" aria-hidden="true">
                    {t('recordingLimits.secondsUnit')}
                  </span>
                </div>
              </div>
            )}

            {recordingLimitHelper && (
              <p className="text-[12px] text-text-secondary">{recordingLimitHelper}</p>
            )}
            {config.recording_limit_mode === 'custom' &&
              recordingLimit.requestedSeconds !== recordingLimit.effectiveMaxSeconds && (
                <p className="text-[12px] text-amber-500">
                  {t('recordingLimits.corrected', {
                    duration: formatRecordingDuration(recordingLimit.effectiveMaxSeconds, t),
                  })}
                </p>
              )}
          </div>
        ) : (
          <p className="text-[12px] text-text-tertiary">{t('recordingLimits.loading')}</p>
        )}
      </FormField>
    </div>
  )
}
