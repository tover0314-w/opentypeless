import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SttSetupStep } from '../SttSetupStep'
import { selectOptions } from '../../shared/__tests__/selectTestUtils'
import { testSttConnection } from '../../../lib/tauri'

const mockStore = {
  config: {
    stt_provider: 'deepgram',
    stt_api_key: '',
    stt_custom_api_key: '',
    stt_custom_base_url: 'http://localhost:8000/v1',
    stt_custom_model: 'Systran/faster-whisper-large-v3',
    stt_aliyun_qwen_region: 'international',
    stt_volcengine_resource_id: 'volc.bigasr.sauc.duration',
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
  vi.clearAllMocks()
  mockStore.config = {
    stt_provider: 'deepgram',
    stt_api_key: '',
    stt_custom_api_key: '',
    stt_custom_base_url: 'http://localhost:8000/v1',
    stt_custom_model: 'Systran/faster-whisper-large-v3',
    stt_aliyun_qwen_region: 'international',
    stt_volcengine_resource_id: 'volc.bigasr.sauc.duration',
  }
  mockStore.updateConfig = vi.fn()
  mockStore.sttTestStatus = 'idle'
  mockStore.setSttTestStatus = vi.fn()
})

afterEach(() => cleanup())

describe('SttSetupStep', () => {
  it('names its credential input using the existing translated label', () => {
    render(<SttSetupStep />)
    expect(screen.getByLabelText('API key')).toHaveAttribute('type', 'password')
  })

  it('uses the shared field and secondary test-button styles', () => {
    const { container } = render(<SttSetupStep />)
    expect(container.querySelector('input[type="password"]')).toHaveClass('field-control')
    expect(screen.getByRole('button', { name: 'Test' })).toHaveClass('btn-outline')
  })

  it('preserves an existing Apple Speech setup and tests it without a key', async () => {
    mockStore.config.stt_provider = 'apple-speech'
    render(<SttSetupStep />)
    expect(mockStore.updateConfig).not.toHaveBeenCalled()
    expect(screen.queryByPlaceholderText('API key')).toBeNull()
    const button = screen.getByRole('button', { name: 'Test' })
    expect(button).toBeEnabled()
    fireEvent.click(button)
    await waitFor(() => expect(testSttConnection).toHaveBeenCalledWith('', 'apple-speech'))
  })

  it.each([
    {
      provider: 'aliyun-qwen3-asr',
      change: { stt_aliyun_qwen_region: 'china-mainland' },
    },
    {
      provider: 'volcengine-doubao',
      change: { stt_volcengine_resource_id: 'volc.seedasr.sauc.duration' },
    },
  ])('ignores a pending result after $provider settings change', async ({ provider, change }) => {
    let finish: (value: boolean) => void = () => {}
    vi.mocked(testSttConnection).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    mockStore.config.stt_provider = provider
    mockStore.config.stt_api_key = 'test-key'
    const { rerender } = render(<SttSetupStep />)
    fireEvent.click(screen.getByRole('button', { name: 'Test' }))
    mockStore.config = { ...mockStore.config, ...change }
    rerender(<SttSetupStep />)
    await act(async () => finish(true))
    expect(mockStore.setSttTestStatus).not.toHaveBeenCalledWith('success')
    expect(mockStore.setSttTestStatus).toHaveBeenLastCalledWith('idle')
  })

  it.each(['aliyun-qwen3-asr', 'volcengine-doubao'])(
    'tests %s with the configured regional or resource setting',
    async (provider) => {
      mockStore.config.stt_provider = provider
      mockStore.config.stt_api_key = 'test-key'
      render(<SttSetupStep />)
      fireEvent.click(screen.getByRole('button', { name: 'Test' }))
      await waitFor(() =>
        expect(testSttConnection).toHaveBeenCalledWith(
          'test-key',
          provider,
          undefined,
          undefined,
          provider === 'volcengine-doubao' ? 'volc.bigasr.sauc.duration' : undefined,
          provider === 'aliyun-qwen3-asr' ? 'international' : undefined,
        ),
      )
    },
  )

  it('ignores a connection result after the credential changes', async () => {
    let finish: (value: boolean) => void = () => {}
    vi.mocked(testSttConnection).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    mockStore.config.stt_api_key = 'old-key'
    const { rerender } = render(<SttSetupStep />)
    fireEvent.click(screen.getByRole('button', { name: 'Test' }))
    mockStore.config = { ...mockStore.config, stt_api_key: 'new-key' }
    rerender(<SttSetupStep />)
    await act(async () => finish(true))
    expect(mockStore.setSttTestStatus).not.toHaveBeenCalledWith('success')
    expect(mockStore.setSttTestStatus).toHaveBeenLastCalledWith('idle')
  })

  it('ignores a connection result after leaving setup', async () => {
    let finish: (value: boolean) => void = () => {}
    vi.mocked(testSttConnection).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    mockStore.config.stt_api_key = 'test-key'
    const { unmount } = render(<SttSetupStep />)
    fireEvent.click(screen.getByRole('button', { name: 'Test' }))
    unmount()
    await act(async () => finish(true))
    expect(mockStore.setSttTestStatus).not.toHaveBeenCalledWith('success')
    expect(mockStore.setSttTestStatus).toHaveBeenLastCalledWith('idle')
  })

  it('starts only one pending connection test', async () => {
    let finish: (value: boolean) => void = () => {}
    vi.mocked(testSttConnection).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve
      }),
    )
    mockStore.config.stt_api_key = 'test-key'
    render(<SttSetupStep />)
    const button = screen.getByRole('button', { name: 'Test' })
    fireEvent.click(button)
    fireEvent.click(button)
    const calls = vi.mocked(testSttConnection).mock.calls.length
    await act(async () => finish(true))
    expect(calls).toBe(1)
  })

  it('does not offer managed Cloud inside BYOK provider setup', () => {
    render(<SttSetupStep />)

    const providerSelect = screen.getByRole('combobox')
    expect(selectOptions(providerSelect).map((option) => option.value)).not.toContain('cloud')
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
})
