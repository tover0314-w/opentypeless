import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SttSetupStep } from '../SttSetupStep'

const mockStore = {
  config: {
    stt_provider: 'deepgram',
    stt_api_key: '',
    stt_custom_api_key: '',
    stt_custom_base_url: 'http://localhost:8000/v1',
    stt_custom_model: 'Systran/faster-whisper-large-v3',
    stt_custom_preset: 'speaches',
  },
  updateConfig: vi.fn(),
  sttTestStatus: 'idle',
  setSttTestStatus: vi.fn(),
}

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) =>
      ({
        'onboarding.stt.serviceLabel': 'Service',
        'onboarding.stt.apiKeyLabel': 'API key',
        'onboarding.stt.apiKeyPlaceholder': 'API key',
        'onboarding.stt.testButton': 'Test',
        'onboarding.stt.connectionOk': 'OK',
        'onboarding.stt.connectionFail': 'Failed',
        'onboarding.stt.customWhisperConfigured': 'Custom Whisper',
        'providers.stt.deepgram': 'Deepgram',
        'providers.stt.customWhisper': 'Custom Whisper',
      })[key] ?? key,
  }),
}))

vi.mock('../../../stores/appStore', () => ({
  useAppStore: (selector: any) => selector(mockStore),
}))

vi.mock('../../../lib/tauri', () => ({
  testSttConnection: vi.fn().mockResolvedValue(true),
}))

beforeEach(() => {
  mockStore.config = {
    stt_provider: 'deepgram',
    stt_api_key: '',
    stt_custom_api_key: '',
    stt_custom_base_url: 'http://localhost:8000/v1',
    stt_custom_model: 'Systran/faster-whisper-large-v3',
    stt_custom_preset: 'speaches',
  }
  mockStore.updateConfig = vi.fn()
  mockStore.sttTestStatus = 'idle'
  mockStore.setSttTestStatus = vi.fn()
})

afterEach(() => cleanup())

describe('SttSetupStep', () => {
  it('does not offer managed Cloud inside BYOK provider setup', () => {
    render(<SttSetupStep />)

    const providerSelect = screen.getByRole('combobox')
    expect(providerSelect.querySelector('option[value="cloud"]')).toBeNull()
  })

  it('preserves an existing Custom Whisper setup instead of switching providers', () => {
    mockStore.config = {
      ...mockStore.config,
      stt_provider: 'custom-whisper',
      stt_custom_base_url: 'http://localhost:9000/v1',
      stt_custom_model: 'local-large-v3',
    }

    render(<SttSetupStep />)

    expect(screen.getByText('Custom Whisper')).toBeInTheDocument()
    expect(screen.getByText('http://localhost:9000/v1')).toBeInTheDocument()
    expect(screen.getByText('local-large-v3')).toBeInTheDocument()
    expect(mockStore.updateConfig).not.toHaveBeenCalledWith({ stt_provider: 'deepgram' })
  })

  it('tests Custom Whisper with its configured endpoint and model', async () => {
    const tauri = await import('../../../lib/tauri')
    mockStore.config = {
      ...mockStore.config,
      stt_provider: 'custom-whisper',
      stt_custom_api_key: 'custom-secret',
      stt_custom_base_url: 'http://localhost:9000/v1',
      stt_custom_model: 'local-large-v3',
    }

    render(<SttSetupStep />)
    fireEvent.click(screen.getByRole('button', { name: 'Test' }))

    await waitFor(() => {
      expect(tauri.testSttConnection).toHaveBeenCalledWith(
        'custom-secret',
        'custom-whisper',
        'http://localhost:9000/v1',
        'local-large-v3',
      )
    })
  })

  it('offers Azure OpenAI during onboarding', () => {
    render(<SttSetupStep />)

    const providerSelect = screen.getByRole('combobox')
    expect(providerSelect.querySelector('option[value="azure-openai"]')).not.toBeNull()
  })

  it('maps an Azure selection onto the custom-whisper transport', () => {
    render(<SttSetupStep />)

    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'azure-openai' } })

    expect(mockStore.updateConfig).toHaveBeenCalledWith(
      expect.objectContaining({
        stt_provider: 'custom-whisper',
        stt_custom_preset: 'azure-openai',
        // A stale local Speaches URL must not survive the switch to Azure.
        stt_custom_base_url: '',
      }),
    )
  })

  it('collects the per-tenant endpoint and deployment Azure requires', () => {
    mockStore.config = {
      ...mockStore.config,
      stt_provider: 'custom-whisper',
      stt_custom_preset: 'azure-openai',
      stt_custom_base_url: '',
      stt_custom_model: '',
    }

    render(<SttSetupStep />)

    // Azure stays switchable in the dropdown rather than collapsing to the
    // read-only summary used for endpoints configured outside onboarding.
    expect(screen.getByRole('combobox')).toBeInTheDocument()
    expect(screen.getByText('onboarding.stt.endpointLabel')).toBeInTheDocument()
    expect(screen.getByText('onboarding.stt.deploymentLabel')).toBeInTheDocument()
    // The placeholders must show the Azure URL shape, since the classic
    // deployment route with api-version is the only one that serves audio.
    expect(
      screen.getByPlaceholderText(/openai\/deployments\/.*audio\/transcriptions\?api-version=/),
    ).toBeInTheDocument()
    expect(screen.getByPlaceholderText('gpt-4o-mini-transcribe')).toBeInTheDocument()
  })

  it('keeps the Azure API key optional so Entra ID sign-in stays available', () => {
    mockStore.config = {
      ...mockStore.config,
      stt_provider: 'custom-whisper',
      stt_custom_preset: 'azure-openai',
      stt_custom_api_key: '',
      stt_custom_base_url:
        'https://r.openai.azure.com/openai/deployments/d/audio/transcriptions?api-version=2025-03-01-preview',
      stt_custom_model: 'gpt-4o-mini-transcribe',
    }

    render(<SttSetupStep />)

    expect(screen.getByRole('button', { name: 'Test' })).not.toBeDisabled()
  })
})
