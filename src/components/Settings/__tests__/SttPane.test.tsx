import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, cleanup, within } from '@testing-library/react'
import { SttPane } from '../SttPane'
import * as tauri from '../../../lib/tauri'

// Mock Tauri
vi.mock('../../../lib/tauri')

// Mock i18n
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, values?: Record<string, string | number>) => {
      const translations: Record<string, string> = {
        'settings.provider': 'Provider',
        'nav.upgrade': 'Upgrade',
        'settings.apiKey': 'API Key',
        'settings.test': 'Test',
        'settings.enterApiKey': 'Enter API Key',
        'settings.connectionSuccess': 'Connection successful',
        'settings.connectionFailed': 'Connection failed',
        'settings.storedLocally': 'Stored locally',
        'settings.sttLanguage': 'STT Language',
        'settings.maxRecordingDuration': 'Single recording duration',
        'recordingLimits.auto': 'Auto (recommended, up to {{duration}})',
        'recordingLimits.custom': 'Custom',
        'recordingLimits.customDuration': 'Custom duration',
        'recordingLimits.allowedRange': 'Supported range: {{min}}–{{max}}.',
        'recordingLimits.allowedRangeWithReason': 'Range: {{min}}–{{max}}. {{reason}}',
        'recordingLimits.allowedCloudRange':
          'Range: {{min}}–{{max}}. Set by current Cloud capability.',
        'recordingLimits.corrected': 'This provider will use {{duration}}.',
        'recordingLimits.currentSelectionWithCloudMax':
          'Current: {{current}}. Cloud supports up to {{max}}.',
        'recordingLimits.currentSelectionWithLimit':
          'Selected: {{current}}; app limit: {{max}}. {{reason}}',
        'recordingLimits.providerFixedLimit': 'This provider allows recordings up to {{max}}.',
        'recordingLimits.durationSeconds': '{{count}} seconds',
        'recordingLimits.durationMinute': '{{count}} minute',
        'recordingLimits.durationMinutes': '{{count}} minutes',
        'recordingLimits.secondsUnit': 'seconds',
        'recordingLimits.presets': 'Recording limit presets',
        'recordingLimits.numericEntry': 'Custom duration…',
        'recordingLimits.reasons.productSafety': 'Product safety limit',
        'recordingLimits.reasons.providerDuration': 'Provider duration limit',
        'recordingLimits.reasons.appleSpeech': 'Apple Speech session limit',
        'recordingLimits.reasons.clientBuffer': 'Client buffer limit',
        'recordingLimits.reasons.unknownUpstream': 'Upstream limit unknown',
        'recordingLimits.reasons.unknownProvider': 'Provider limit unknown',
        'recordingLimits.reasons.managedCapability': 'Set automatically for OpenTypeless Cloud.',
        'recordingLimits.reasons.managedFallback': 'Safe Cloud fallback',
        'settings.cloudSttPro': 'Cloud STT (Pro)',
        'settings.sttSignInHint': 'Sign in to use cloud STT',
        'settings.sttUpgradeHint': 'Upgrade to Pro to use cloud STT',
        'settings.sttProActive': 'Cloud STT active',
        'settings.customSttPreset': 'Preset',
        'settings.customSttPresetSpeaches': 'Speaches',
        'settings.customSttPresetCustom': 'Custom OpenAI-compatible',
        'settings.customSttBaseUrl': 'Base URL',
        'settings.customSttBaseUrlPlaceholder': 'http://localhost:8000/v1',
        'settings.customSttModel': 'Model',
        'settings.customSttModelPlaceholder': 'Systran/faster-whisper-large-v3',
        'settings.customSttApiKeyOptional': 'API Key (optional)',
        'settings.customSttSetupHint':
          'Start your local OpenAI-compatible STT server first, then test the connection here.',
        'settings.localSttReady': 'Local endpoint ready',
        'settings.localSttNeedsSetup': 'Local endpoint needs setup',
        'settings.appleSpeechReady': 'Apple Speech ready',
        'settings.appleSpeechUnavailable': 'Apple Speech unavailable',
        'settings.customSttConnectionFailed':
          'Local STT server is not reachable. Check that it is running and the port is correct.',
        'settings.volcengineSttKeyHint':
          'Use a Volcengine Speech API key, or app_id:access_token from the old console. Ark LLM keys are separate.',
        'settings.volcengineResourceId': 'Volcengine ASR resource',
        'settings.volcengineResourceSeedAsr': 'SeedASR 2.0',
        'settings.volcengineResourceBigAsr': 'BigASR 1.0',
        'providers.stt.aliyunQwen3Asr': 'Aliyun Qwen3 Realtime ASR',
        'providers.stt.volcengineDoubao': 'Volcengine Doubao Realtime ASR',
        'providers.stt.appleSpeech': 'Apple Speech (Local)',
      }
      return Object.entries(values ?? {}).reduce(
        (text, [name, value]) => text.replace(`{{${name}}}`, String(value)),
        translations[key] || key,
      )
    },
  }),
}))

// Mock stores
const mockAppStore = {
  config: {
    stt_provider: 'deepgram' as string,
    stt_api_key: '',
    stt_custom_api_key: '',
    stt_language: 'en',
    stt_custom_preset: 'speaches',
    stt_custom_base_url: 'http://localhost:8000/v1',
    stt_custom_model: 'Systran/faster-whisper-large-v3',
    stt_volcengine_resource_id: 'volc.seedasr.sauc.duration',
    recording_limit_mode: 'auto' as 'auto' | 'custom',
    custom_recording_limit_seconds: 600,
    max_recording_seconds: 600,
  },
  updateConfig: vi.fn(),
  sttTestStatus: 'idle' as 'idle' | 'testing' | 'success' | 'error',
  setSttTestStatus: vi.fn(),
  sttLatencyMs: null as number | null,
  setSttLatencyMs: vi.fn(),
  platformCapabilities: {
    os: 'macos',
    sessionType: 'unknown',
    globalHotkeyReliable: true,
    keyboardOutputReliable: true,
    clipboardAutoPasteReliable: true,
  },
}

const mockAuthStore = {
  user: null as any,
  plan: null as any,
  source: 'free',
  cloudWordsLimit: 0,
  licenseStatus: null as any,
}

vi.mock('../../../stores/appStore', () => ({
  useAppStore: (selector: any) => {
    if (typeof selector === 'function') {
      return selector(mockAppStore)
    }
    return mockAppStore
  },
}))

vi.mock('../../../stores/authStore', () => ({
  hasManagedCloudAccess: (state: typeof mockAuthStore) =>
    state.licenseStatus !== 'refunded' &&
    state.licenseStatus !== 'deactivated' &&
    ((state.source === 'creem' && state.cloudWordsLimit > 0) ||
      (state.source === 'appsumo' &&
        state.cloudWordsLimit > 0 &&
        state.licenseStatus === 'active') ||
      state.plan === 'pro'),
  useAuthStore: (selector: any) => {
    if (typeof selector === 'function') {
      return selector(mockAuthStore)
    }
    return mockAuthStore
  },
}))

describe('SttPane', () => {
  beforeEach(() => {
    // Reset mock store state
    mockAppStore.config = {
      stt_provider: 'deepgram',
      stt_api_key: '',
      stt_custom_api_key: '',
      stt_language: 'en',
      stt_custom_preset: 'speaches',
      stt_custom_base_url: 'http://localhost:8000/v1',
      stt_custom_model: 'Systran/faster-whisper-large-v3',
      stt_volcengine_resource_id: 'volc.seedasr.sauc.duration',
      recording_limit_mode: 'auto',
      custom_recording_limit_seconds: 600,
      max_recording_seconds: 600,
    }
    mockAppStore.sttTestStatus = 'idle'
    mockAppStore.sttLatencyMs = null
    mockAppStore.platformCapabilities = {
      os: 'macos',
      sessionType: 'unknown',
      globalHotkeyReliable: true,
      keyboardOutputReliable: true,
      clipboardAutoPasteReliable: true,
    }
    mockAuthStore.user = null
    mockAuthStore.plan = null
    mockAuthStore.source = 'free'
    mockAuthStore.cloudWordsLimit = 0
    mockAuthStore.licenseStatus = null

    // Clear all mock function calls
    vi.clearAllMocks()
    vi.mocked(tauri.readCredential).mockResolvedValue(null)
    vi.mocked(tauri.setCredential).mockResolvedValue(undefined)
    vi.mocked(tauri.getSttRecordingCapability).mockResolvedValue({
      capability: {
        registryVersion: 1,
        providerId: 'deepgram',
        transport: 'streaming',
        recommendedMaxSeconds: 600,
        hardMaxSeconds: 3600,
        maxUploadBytes: null,
        source: 'productSafety',
        explanationKey: 'recordingLimits.reasons.productSafety',
      },
      mode: 'auto',
      requestedSeconds: 600,
      effectiveMaxSeconds: 600,
    })
    vi.mocked(tauri.getSttProviderDiagnostics).mockResolvedValue({
      provider: 'custom-whisper',
      kind: 'localCompatible',
      endpoint: 'http://localhost:8000/v1/audio/transcriptions',
      model: 'Systran/faster-whisper-large-v3',
      requiresApiKey: false,
      apiKeyConfigured: false,
      ready: true,
      issues: [],
    })
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  describe('Provider selection', () => {
    it('renders provider dropdown with current value', () => {
      render(<SttPane />)
      const selects = screen.getAllByRole('combobox')
      const providerSelect = selects[0] // First select is provider
      expect(providerSelect).toHaveValue('deepgram')
    })

    it('lists Volcengine Doubao realtime ASR as an STT provider', () => {
      render(<SttPane />)
      expect(screen.getByRole('option', { name: 'Volcengine Doubao Realtime ASR' })).toHaveValue(
        'volcengine-doubao',
      )
    })

    it('lists Aliyun Qwen3 realtime ASR as an STT provider', () => {
      render(<SttPane />)
      expect(screen.getByRole('option', { name: 'Aliyun Qwen3 Realtime ASR' })).toHaveValue(
        'aliyun-qwen3-asr',
      )
    })

    it('shows Apple Speech as a built-in local provider on macOS only', () => {
      render(<SttPane />)

      expect(screen.getByRole('option', { name: 'Apple Speech (Local)' })).toHaveValue(
        'apple-speech',
      )

      cleanup()
      mockAppStore.platformCapabilities = {
        os: 'windows',
        sessionType: 'unknown',
        globalHotkeyReliable: true,
        keyboardOutputReliable: true,
        clipboardAutoPasteReliable: true,
      }
      render(<SttPane />)

      expect(screen.queryByRole('option', { name: 'Apple Speech (Local)' })).not.toBeInTheDocument()
    })

    it('does not show API key input for Apple Speech and can test without credentials', async () => {
      mockAppStore.config.stt_provider = 'apple-speech'
      vi.mocked(tauri.getSttProviderDiagnostics).mockResolvedValueOnce({
        provider: 'apple-speech',
        kind: 'builtinLocal',
        endpoint: null,
        model: 'Apple Speech',
        requiresApiKey: false,
        apiKeyConfigured: false,
        ready: true,
        issues: [],
      })
      vi.mocked(tauri.benchSttConnection).mockResolvedValueOnce(0)

      const { container } = render(<SttPane />)

      expect(container.querySelector('input[placeholder="Enter API Key"]')).toBeNull()
      expect(await screen.findByText('Apple Speech ready')).toBeInTheDocument()

      fireEvent.click(screen.getByRole('button', { name: /test/i }))

      await waitFor(() => {
        expect(tauri.benchSttConnection).toHaveBeenCalledWith('', 'apple-speech')
      })
    })

    it('shows a credential hint for Volcengine Doubao realtime ASR', () => {
      mockAppStore.config.stt_provider = 'volcengine-doubao'

      render(<SttPane />)

      expect(
        screen.getByText(
          'Use a Volcengine Speech API key, or app_id:access_token from the old console. Ark LLM keys are separate.',
        ),
      ).toBeInTheDocument()
    })

    it('shows and updates Volcengine ASR resource id', () => {
      mockAppStore.config.stt_provider = 'volcengine-doubao'

      render(<SttPane />)

      const resourceSelect = screen.getByLabelText('Volcengine ASR resource')
      expect(resourceSelect).toHaveValue('volc.seedasr.sauc.duration')

      fireEvent.change(resourceSelect, { target: { value: 'volc.bigasr.sauc.duration' } })

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({
        stt_volcengine_resource_id: 'volc.bigasr.sauc.duration',
      })
      expect(mockAppStore.setSttTestStatus).toHaveBeenCalledWith('idle')
      expect(mockAppStore.setSttLatencyMs).toHaveBeenCalledWith(null)
    })

    it('updates config and resets state when provider changes', () => {
      render(<SttPane />)
      const selects = screen.getAllByRole('combobox')
      const providerSelect = selects[0]

      fireEvent.change(providerSelect, { target: { value: 'assemblyai' } })

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({ stt_provider: 'assemblyai' })
      expect(mockAppStore.setSttTestStatus).toHaveBeenCalledWith('idle')
      expect(mockAppStore.setSttLatencyMs).toHaveBeenCalledWith(null)
    })
  })

  describe('Recording limit', () => {
    it('shows the Rust-resolved Auto value and governing reason', async () => {
      render(<SttPane />)

      expect(
        await screen.findByRole('option', { name: /Auto \(recommended,.*10 minutes/i }),
      ).toBeInTheDocument()
      expect(screen.getByText('Product safety limit')).toBeInTheDocument()
      expect(tauri.getSttRecordingCapability).toHaveBeenCalledWith('deepgram', 'auto', 600)
    })

    it('filters choices in the single duration selector using the Rust hard maximum', async () => {
      mockAppStore.config.stt_provider = 'glm-asr'
      mockAppStore.config.recording_limit_mode = 'custom'
      mockAppStore.config.custom_recording_limit_seconds = 600
      vi.mocked(tauri.getSttRecordingCapability).mockResolvedValueOnce({
        capability: {
          registryVersion: 1,
          providerId: 'glm-asr',
          transport: 'fileUpload',
          recommendedMaxSeconds: 30,
          hardMaxSeconds: 30,
          maxUploadBytes: 24 * 1024 * 1024,
          source: 'provider',
          explanationKey: 'recordingLimits.reasons.providerDuration',
        },
        mode: 'custom',
        requestedSeconds: 600,
        effectiveMaxSeconds: 30,
      })

      render(<SttPane />)

      const duration = await screen.findByLabelText('Single recording duration')
      expect(within(duration).getByRole('option', { name: '30 seconds' })).toBeInTheDocument()
      expect(within(duration).queryByRole('option', { name: '1 minute' })).not.toBeInTheDocument()
      expect(
        within(duration).queryByRole('option', { name: 'Custom duration…' }),
      ).not.toBeInTheDocument()
      expect(
        screen.getByText('This provider allows recordings up to 30 seconds.'),
      ).toBeInTheDocument()
      expect(screen.getByText('This provider will use 30 seconds.')).toBeInTheDocument()
    })

    it('shows one selected duration and distinguishes it from the Cloud maximum', async () => {
      mockAppStore.config.stt_provider = 'cloud'
      mockAppStore.config.recording_limit_mode = 'custom'
      mockAppStore.config.custom_recording_limit_seconds = 30
      vi.mocked(tauri.getSttRecordingCapability).mockResolvedValueOnce({
        capability: {
          registryVersion: 1,
          providerId: 'cloud',
          transport: 'managedUpload',
          recommendedMaxSeconds: 600,
          hardMaxSeconds: 600,
          maxUploadBytes: 4_000_000,
          source: 'managedProduct',
          explanationKey: 'recordingLimits.reasons.managedCapability',
        },
        mode: 'custom',
        requestedSeconds: 30,
        effectiveMaxSeconds: 30,
      })

      render(<SttPane />)

      const duration = await screen.findByLabelText('Single recording duration')
      expect(duration).toHaveValue('30')
      expect(screen.queryByLabelText('Recording limit presets')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Custom duration')).not.toBeInTheDocument()
      expect(
        screen.getByText('Current: 30 seconds. Cloud supports up to 10 minutes.'),
      ).toBeInTheDocument()
    })

    it('reveals a bounded numeric entry only after Custom duration is selected', async () => {
      mockAppStore.config.stt_provider = 'cloud'
      vi.mocked(tauri.getSttRecordingCapability).mockResolvedValueOnce({
        capability: {
          registryVersion: 1,
          providerId: 'cloud',
          transport: 'managedUpload',
          recommendedMaxSeconds: 600,
          hardMaxSeconds: 600,
          maxUploadBytes: 4_000_000,
          source: 'managedProduct',
          explanationKey: 'recordingLimits.reasons.managedCapability',
        },
        mode: 'auto',
        requestedSeconds: 600,
        effectiveMaxSeconds: 600,
      })
      render(<SttPane />)

      const duration = await screen.findByLabelText('Single recording duration')
      expect(screen.queryByLabelText('Custom duration')).not.toBeInTheDocument()

      fireEvent.change(duration, { target: { value: 'custom' } })
      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({ recording_limit_mode: 'custom' })

      const input = await screen.findByLabelText('Custom duration')
      expect(input).toHaveAttribute('min', '30')
      expect(input).toHaveAttribute('max', '600')
      expect(screen.getByText('seconds')).toBeInTheDocument()
      expect(
        screen.getByText('Range: 30 seconds–10 minutes. Set by current Cloud capability.'),
      ).toBeInTheDocument()
      expect(
        screen.queryByText('Current: 10 minutes. Cloud supports up to 10 minutes.'),
      ).not.toBeInTheDocument()
      fireEvent.change(input, { target: { value: '300' } })
      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({
        custom_recording_limit_seconds: 300,
      })
    })

    it('describes an app buffer limit without claiming it is provider support', async () => {
      mockAppStore.config.stt_provider = 'openai-whisper'
      mockAppStore.config.recording_limit_mode = 'custom'
      mockAppStore.config.custom_recording_limit_seconds = 600
      vi.mocked(tauri.getSttRecordingCapability).mockResolvedValueOnce({
        capability: {
          registryVersion: 1,
          providerId: 'openai-whisper',
          transport: 'fileUpload',
          recommendedMaxSeconds: 600,
          hardMaxSeconds: 720,
          maxUploadBytes: 24 * 1024 * 1024,
          source: 'clientBuffer',
          explanationKey: 'recordingLimits.reasons.clientBuffer',
        },
        mode: 'custom',
        requestedSeconds: 600,
        effectiveMaxSeconds: 600,
      })

      render(<SttPane />)

      expect(
        await screen.findByText('Selected: 10 minutes; app limit: 12 minutes. Client buffer limit'),
      ).toBeInTheDocument()
      expect(
        screen.queryByText('Current: 10 minutes. This service supports up to 12 minutes.'),
      ).not.toBeInTheDocument()
    })

    it('keeps the managed fallback explanation and hides meaningless custom entry', async () => {
      mockAppStore.config.stt_provider = 'cloud'
      mockAppStore.config.recording_limit_mode = 'custom'
      mockAppStore.config.custom_recording_limit_seconds = 30
      vi.mocked(tauri.getSttRecordingCapability).mockResolvedValueOnce({
        capability: {
          registryVersion: 1,
          providerId: 'cloud',
          transport: 'managedUpload',
          recommendedMaxSeconds: 30,
          hardMaxSeconds: 30,
          maxUploadBytes: 4_000_000,
          source: 'managedProduct',
          explanationKey: 'recordingLimits.reasons.managedFallback',
        },
        mode: 'custom',
        requestedSeconds: 30,
        effectiveMaxSeconds: 30,
      })

      render(<SttPane />)

      const duration = await screen.findByLabelText('Single recording duration')
      expect(
        within(duration).queryByRole('option', { name: 'Custom duration…' }),
      ).not.toBeInTheDocument()
      expect(screen.getByText('Safe Cloud fallback')).toBeInTheDocument()
      expect(
        screen.queryByText('Current: 30 seconds. Cloud supports up to 30 seconds.'),
      ).not.toBeInTheDocument()
    })

    it('stores a preset as a custom duration from the single selector', async () => {
      render(<SttPane />)
      const duration = await screen.findByLabelText('Single recording duration')

      fireEvent.change(duration, { target: { value: '300' } })

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({
        recording_limit_mode: 'custom',
        custom_recording_limit_seconds: 300,
      })
    })

    it('shows 30 seconds for stale Cloud metadata and 10 minutes only for compatible v2', async () => {
      mockAppStore.config.stt_provider = 'cloud'
      vi.mocked(tauri.getSttRecordingCapability).mockResolvedValueOnce({
        capability: {
          registryVersion: 1,
          providerId: 'cloud',
          transport: 'managedUpload',
          recommendedMaxSeconds: 30,
          hardMaxSeconds: 30,
          maxUploadBytes: 4_000_000,
          source: 'managedProduct',
          explanationKey: 'recordingLimits.reasons.managedFallback',
        },
        mode: 'auto',
        requestedSeconds: 30,
        effectiveMaxSeconds: 30,
      })
      render(<SttPane />)

      expect(
        await screen.findByRole('option', { name: /Auto \(recommended,.*30 seconds/i }),
      ).toBeInTheDocument()
      expect(screen.getByText('Safe Cloud fallback')).toBeInTheDocument()

      cleanup()
      vi.mocked(tauri.getSttRecordingCapability).mockResolvedValueOnce({
        capability: {
          registryVersion: 1,
          providerId: 'cloud',
          transport: 'managedUpload',
          recommendedMaxSeconds: 600,
          hardMaxSeconds: 600,
          maxUploadBytes: 4_000_000,
          source: 'managedProduct',
          explanationKey: 'recordingLimits.reasons.managedCapability',
        },
        mode: 'auto',
        requestedSeconds: 600,
        effectiveMaxSeconds: 600,
      })
      render(<SttPane />)

      expect(
        await screen.findByRole('option', { name: /Auto \(recommended,.*10 minutes/i }),
      ).toBeInTheDocument()
      expect(screen.getByText('Set automatically for OpenTypeless Cloud.')).toBeInTheDocument()
    })
  })

  describe('Cloud provider UI', () => {
    it('shows cloud info when provider is cloud and user not signed in', () => {
      mockAppStore.config.stt_provider = 'cloud'
      render(<SttPane />)
      expect(screen.getByText('Sign in to use cloud STT')).toBeInTheDocument()
    })

    it('shows upgrade hint when user is signed in but not pro', () => {
      mockAppStore.config.stt_provider = 'cloud'
      mockAuthStore.user = { id: '1', email: 'test@example.com' }
      mockAuthStore.plan = 'free'

      render(<SttPane />)
      expect(screen.getByText('Upgrade to Pro to use cloud STT')).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'Upgrade' }))
      expect(window.location.hash).toBe('#/upgrade')
    })

    it('shows active status when user is pro', () => {
      mockAppStore.config.stt_provider = 'cloud'
      mockAuthStore.user = { id: '1', email: 'test@example.com' }
      mockAuthStore.plan = 'pro'

      render(<SttPane />)
      expect(screen.getByText('Cloud STT active')).toBeInTheDocument()
    })

    it('hides API key input when provider is cloud', () => {
      mockAppStore.config.stt_provider = 'cloud'

      const { container } = render(<SttPane />)
      const inputs = container.querySelectorAll('input[placeholder="Enter API Key"]')
      expect(inputs.length).toBe(0)
    })
  })

  describe('API Key input', () => {
    it('renders API key input with current value', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      const { container } = render(<SttPane />)
      const input = container.querySelector(
        'input[placeholder="Enter API Key"]',
      ) as HTMLInputElement
      expect(input.value).toBe('sk-test123')
      expect(input.type).toBe('password')
    })

    it('stores API key in credential vault and resets test state when API key changes', async () => {
      const { container } = render(<SttPane />)
      const input = container.querySelector(
        'input[placeholder="Enter API Key"]',
      ) as HTMLInputElement

      fireEvent.change(input, { target: { value: 'sk-new-key' } })
      fireEvent.blur(input)

      await waitFor(() =>
        expect(tauri.setCredential).toHaveBeenCalledWith('stt', 'deepgram', 'sk-new-key'),
      )
      expect(mockAppStore.updateConfig).not.toHaveBeenCalledWith({ stt_api_key: 'sk-new-key' })
      expect(mockAppStore.setSttTestStatus).toHaveBeenCalledWith('idle')
      expect(mockAppStore.setSttLatencyMs).toHaveBeenCalledWith(null)
    })

    it('shows an inline error when credential vault save fails', async () => {
      vi.mocked(tauri.setCredential).mockRejectedValueOnce(new Error('vault unavailable'))
      const { container } = render(<SttPane />)
      const input = container.querySelector(
        'input[placeholder="Enter API Key"]',
      ) as HTMLInputElement

      fireEvent.change(input, { target: { value: 'sk-new-key' } })
      fireEvent.blur(input)

      expect(await screen.findByText(/settings.credentialSaveFailed/)).toBeInTheDocument()
    })
  })

  describe('Custom Whisper provider UI', () => {
    beforeEach(() => {
      mockAppStore.config.stt_provider = 'custom-whisper'
      mockAppStore.config.stt_api_key = ''
      mockAppStore.config.stt_custom_api_key = ''
    })

    it('shows preset, base URL, model, and optional API key fields', () => {
      render(<SttPane />)
      const selects = screen.getAllByRole('combobox')
      const presetSelect = selects[1]

      expect(screen.getByText('Preset')).toBeInTheDocument()
      expect(screen.getByText('Base URL')).toBeInTheDocument()
      expect(screen.getByText('Model')).toBeInTheDocument()
      expect(screen.getByText('API Key (optional)')).toBeInTheDocument()
      expect(presetSelect).toHaveValue('speaches')
      expect(screen.getByDisplayValue('http://localhost:8000/v1')).toBeInTheDocument()
      expect(screen.getByDisplayValue('Systran/faster-whisper-large-v3')).toBeInTheDocument()
    })

    it('enables test without an API key when base URL and model are present', () => {
      render(<SttPane />)
      const button = screen.getAllByRole('button', { name: /test/i })[0]
      expect(button).not.toBeDisabled()
    })

    it('fills Speaches defaults when Speaches preset is selected', () => {
      mockAppStore.config.stt_custom_preset = 'custom'
      mockAppStore.config.stt_custom_base_url = 'http://localhost:9000/v1'
      mockAppStore.config.stt_custom_model = 'custom-model'

      render(<SttPane />)
      const selects = screen.getAllByRole('combobox')
      const presetSelect = selects[1]

      fireEvent.change(presetSelect, { target: { value: 'speaches' } })

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({
        stt_custom_preset: 'speaches',
        stt_custom_base_url: 'http://localhost:8000/v1',
        stt_custom_model: 'Systran/faster-whisper-large-v3',
      })
    })

    it('preserves values when Custom preset is selected', () => {
      render(<SttPane />)
      const selects = screen.getAllByRole('combobox')
      const presetSelect = selects[1]

      fireEvent.change(presetSelect, { target: { value: 'custom' } })

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({
        stt_custom_preset: 'custom',
      })
    })

    it('passes custom base URL and model to the benchmark command', async () => {
      const mockBenchStt = vi.mocked(tauri.benchSttConnection)
      mockBenchStt.mockResolvedValue(123)

      render(<SttPane />)
      fireEvent.click(screen.getAllByRole('button', { name: /test/i })[0])

      await waitFor(() => {
        expect(mockBenchStt).toHaveBeenCalledWith(
          '',
          'custom-whisper',
          'http://localhost:8000/v1',
          'Systran/faster-whisper-large-v3',
        )
      })
    })

    it('shows compact local endpoint diagnostics', async () => {
      render(<SttPane />)

      await waitFor(() => {
        expect(tauri.getSttProviderDiagnostics).toHaveBeenCalledWith(
          '',
          'custom-whisper',
          'http://localhost:8000/v1',
          'Systran/faster-whisper-large-v3',
        )
      })
      expect(screen.getByText('Local endpoint ready')).toBeInTheDocument()
      expect(screen.getByText('http://localhost:8000/v1/audio/transcriptions')).toBeInTheDocument()
    })

    it('shows a quiet setup status when local endpoint config is invalid', async () => {
      vi.mocked(tauri.getSttProviderDiagnostics).mockResolvedValueOnce({
        provider: 'custom-whisper',
        kind: 'localCompatible',
        endpoint: null,
        model: null,
        requiresApiKey: false,
        apiKeyConfigured: false,
        ready: false,
        issues: [{ code: 'invalid_custom_whisper_config', message: 'Model is required' }],
      })

      render(<SttPane />)

      expect(await screen.findByText('Local endpoint needs setup')).toBeInTheDocument()
    })

    it('does not reuse a hosted STT API key for custom Whisper tests', async () => {
      const mockBenchStt = vi.mocked(tauri.benchSttConnection)
      mockBenchStt.mockResolvedValue(123)
      mockAppStore.config.stt_api_key = 'hosted-secret'
      mockAppStore.config.stt_custom_api_key = ''

      render(<SttPane />)
      fireEvent.click(screen.getAllByRole('button', { name: /test/i })[0])

      await waitFor(() => {
        expect(mockBenchStt).toHaveBeenCalledWith(
          '',
          'custom-whisper',
          'http://localhost:8000/v1',
          'Systran/faster-whisper-large-v3',
        )
      })
    })

    it('stores the custom Whisper API key separately from hosted provider keys', async () => {
      mockAppStore.config.stt_api_key = 'hosted-secret'
      mockAppStore.config.stt_custom_api_key = ''

      const { container } = render(<SttPane />)
      const input = container.querySelector(
        'input[placeholder="Enter API Key"]',
      ) as HTMLInputElement

      expect(input.value).toBe('')

      fireEvent.change(input, { target: { value: 'custom-secret' } })
      fireEvent.blur(input)

      await waitFor(() =>
        expect(tauri.setCredential).toHaveBeenCalledWith('stt', 'custom-whisper', 'custom-secret'),
      )
      expect(mockAppStore.updateConfig).not.toHaveBeenCalledWith({ stt_api_key: 'custom-secret' })
    })
  })

  describe('Test button and latency display', () => {
    it('test button is disabled when API key is empty', () => {
      render(<SttPane />)
      const buttons = screen.getAllByRole('button', { name: /test/i })
      const button = buttons[0]
      expect(button).toBeDisabled()
    })

    it('test button is enabled when API key is present', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      render(<SttPane />)
      const buttons = screen.getAllByRole('button', { name: /test/i })
      const button = buttons[0]
      expect(button).not.toBeDisabled()
    })

    it('test button is disabled during testing', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      mockAppStore.sttTestStatus = 'testing'
      render(<SttPane />)
      const buttons = screen.getAllByRole('button', { name: /test/i })
      const button = buttons[0]
      expect(button).toBeDisabled()
    })

    it('calls benchSttConnection on test button click', async () => {
      const mockBenchStt = vi.mocked(tauri.benchSttConnection)
      mockBenchStt.mockResolvedValue(234)

      mockAppStore.config.stt_api_key = 'sk-test123'
      render(<SttPane />)
      const buttons = screen.getAllByRole('button', { name: /test/i })
      const button = buttons[0]

      fireEvent.click(button)

      await waitFor(() => {
        expect(mockAppStore.setSttTestStatus).toHaveBeenCalledWith('testing')
        expect(mockAppStore.setSttLatencyMs).toHaveBeenCalledWith(null)
      })

      await waitFor(() => {
        expect(mockBenchStt).toHaveBeenCalledWith('sk-test123', 'deepgram')
      })
    })

    it('displays latency in milliseconds when test succeeds', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      mockAppStore.sttTestStatus = 'success'
      mockAppStore.sttLatencyMs = 234

      render(<SttPane />)
      expect(screen.getByText('234ms')).toBeInTheDocument()
    })

    it('displays generic success message when latency is null', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      mockAppStore.sttTestStatus = 'success'
      mockAppStore.sttLatencyMs = null

      render(<SttPane />)
      expect(screen.getByText('Connection successful')).toBeInTheDocument()
    })

    it('shows error state UI', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      mockAppStore.sttTestStatus = 'error'

      render(<SttPane />)
      expect(screen.getByText('Connection failed')).toBeInTheDocument()
    })

    it('displays backend error details when benchmark fails', async () => {
      const mockBenchStt = vi.mocked(tauri.benchSttConnection)
      mockBenchStt.mockRejectedValue(new Error('Use a Volcengine Speech API key.'))

      mockAppStore.config.stt_provider = 'volcengine-doubao'
      mockAppStore.config.stt_api_key = 'ark-test'

      render(<SttPane />)
      fireEvent.click(screen.getAllByRole('button', { name: /test/i })[0])

      await waitFor(() => {
        expect(mockAppStore.setSttTestStatus).toHaveBeenCalledWith('error')
        expect(screen.getByText('Use a Volcengine Speech API key.')).toBeInTheDocument()
      })
    })

    it('does not display latency when status is error', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      mockAppStore.sttTestStatus = 'error'
      mockAppStore.sttLatencyMs = 234

      render(<SttPane />)
      expect(screen.queryByText('234ms')).not.toBeInTheDocument()
      expect(screen.getByText('Connection failed')).toBeInTheDocument()
    })
  })

  describe('Language selection', () => {
    it('renders language dropdown with current value', () => {
      render(<SttPane />)
      const selects = screen.getAllByRole('combobox')
      const languageSelect = selects[1] // Second select is language
      expect(languageSelect).toHaveValue('en')
    })

    it('updates config when language changes', () => {
      render(<SttPane />)
      const selects = screen.getAllByRole('combobox')
      const languageSelect = selects[1]

      fireEvent.change(languageSelect, { target: { value: 'zh' } })

      expect(mockAppStore.updateConfig).toHaveBeenCalledWith({ stt_language: 'zh' })
    })
  })

  describe('Integration: state reset on config changes', () => {
    it('resets latency when API key changes after successful test', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      mockAppStore.sttTestStatus = 'success'
      mockAppStore.sttLatencyMs = 234

      const { container } = render(<SttPane />)

      // Verify latency is displayed
      expect(screen.getByText('234ms')).toBeInTheDocument()

      // Change API key
      const input = container.querySelector(
        'input[placeholder="Enter API Key"]',
      ) as HTMLInputElement
      fireEvent.change(input, { target: { value: 'sk-new-key' } })

      // Verify state was reset
      expect(mockAppStore.setSttLatencyMs).toHaveBeenCalledWith(null)
      expect(mockAppStore.setSttTestStatus).toHaveBeenCalledWith('idle')
    })

    it('resets latency when provider changes after successful test', () => {
      mockAppStore.config.stt_api_key = 'sk-test123'
      mockAppStore.sttTestStatus = 'success'
      mockAppStore.sttLatencyMs = 234

      render(<SttPane />)

      // Change provider
      const selects = screen.getAllByRole('combobox')
      const providerSelect = selects[0]
      fireEvent.change(providerSelect, { target: { value: 'assemblyai' } })

      // Verify state was reset
      expect(mockAppStore.setSttLatencyMs).toHaveBeenCalledWith(null)
      expect(mockAppStore.setSttTestStatus).toHaveBeenCalledWith('idle')
    })
  })
})
